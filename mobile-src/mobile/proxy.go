package mobile

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"

	"vantaloom.local/loomnetmobile/loomnet"
)

// loopbackProxy is the 127.0.0.1 HTTP server the WebView points at (via the web
// client's runtimeTarget). It rewrites every request to http://<target>.loom<uri>
// and round-trips it over the overlay node's Transport, streaming the response
// (flush per chunk → SSE-safe) and tunnelling WebSocket upgrades end-to-end.
type loopbackProxy struct {
	client   *http.Client  // over node.Transport(); NO timeout (streaming/WS)
	targetFn func() string // the current peer machineID ("" = none)
	srv      *http.Server
	ln       net.Listener
	mu       sync.Mutex
	bound    int
}

// hopHeaders are stripped when proxying a normal request/response (RFC 7230
// §6.1). WebSocket upgrades deliberately keep Connection/Upgrade (handled apart).
var hopHeaders = []string{
	"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate",
	"Proxy-Authorization", "Te", "Trailer", "Transfer-Encoding", "Upgrade",
}

func newLoopbackProxy(node *loomnet.Node, targetFn func() string) *loopbackProxy {
	p := &loopbackProxy{
		// No client timeout: SSE and WebSocket streams are long-lived. Cancellation
		// rides on the inbound request context (the WebView closing the connection).
		client:   &http.Client{Transport: node.Transport()},
		targetFn: targetFn,
	}
	p.srv = &http.Server{Handler: http.HandlerFunc(p.serveHTTP)}
	return p
}

// start binds 127.0.0.1:0 and serves until stop; returns once listening.
func (p *loopbackProxy) start() error {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("mobile: loopback listen: %w", err)
	}
	p.mu.Lock()
	p.ln = ln
	p.bound = ln.Addr().(*net.TCPAddr).Port
	p.mu.Unlock()
	go func() { _ = p.srv.Serve(ln) }()
	return nil
}

func (p *loopbackProxy) port() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.bound
}

func (p *loopbackProxy) stop() {
	if p.srv != nil {
		_ = p.srv.Close()
	}
}

// serveHTTP rewrites the request to the current peer over the overlay.
func (p *loopbackProxy) serveHTTP(w http.ResponseWriter, r *http.Request) {
	target := p.targetFn()
	if target == "" {
		http.Error(w, "no overlay target selected", http.StatusServiceUnavailable)
		return
	}
	// RequestURI() preserves the raw path + query so encoded segments survive.
	outURL := "http://" + target + ".loom" + r.URL.RequestURI()

	if isWebSocketUpgrade(r) {
		p.proxyWebSocket(w, r, outURL)
		return
	}
	p.proxyHTTP(w, r, outURL)
}

// proxyHTTP forwards a normal/SSE request and streams the response back, flushing
// each chunk so server-sent events are not buffered until close.
func (p *loopbackProxy) proxyHTTP(w http.ResponseWriter, r *http.Request, outURL string) {
	outReq, err := http.NewRequestWithContext(r.Context(), r.Method, outURL, r.Body)
	if err != nil {
		http.Error(w, "bad gateway: "+err.Error(), http.StatusBadGateway)
		return
	}
	copyHeader(outReq.Header, r.Header, false)
	// Preserve the client's declared body length for non-chunked requests.
	outReq.ContentLength = r.ContentLength

	resp, err := p.client.Do(outReq)
	if err != nil {
		http.Error(w, "overlay dial failed: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	copyHeader(w.Header(), resp.Header, false)
	w.WriteHeader(resp.StatusCode)
	flushStream(w, resp.Body)
}

// proxyWebSocket tunnels a WebSocket upgrade end-to-end over the overlay. The
// overlay stream is a plain net.Conn, so http.Transport hands back the raw
// connection as an io.ReadWriteCloser on the peer's 101 response; we then splice
// it to the hijacked client connection.
func (p *loopbackProxy) proxyWebSocket(w http.ResponseWriter, r *http.Request, outURL string) {
	outReq, err := http.NewRequestWithContext(r.Context(), r.Method, outURL, nil)
	if err != nil {
		http.Error(w, "bad gateway: "+err.Error(), http.StatusBadGateway)
		return
	}
	// Keep the upgrade headers (Connection/Upgrade/Sec-WebSocket-*) so the peer
	// completes the handshake against the client's own key.
	copyHeader(outReq.Header, r.Header, true)

	resp, err := p.client.Do(outReq)
	if err != nil {
		http.Error(w, "overlay dial failed: "+err.Error(), http.StatusBadGateway)
		return
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		// The peer declined the upgrade; relay its response verbatim.
		defer resp.Body.Close()
		copyHeader(w.Header(), resp.Header, false)
		w.WriteHeader(resp.StatusCode)
		flushStream(w, resp.Body)
		return
	}
	peerConn, ok := resp.Body.(io.ReadWriteCloser)
	if !ok {
		resp.Body.Close()
		http.Error(w, "overlay upgrade not tunnelable", http.StatusBadGateway)
		return
	}
	defer peerConn.Close()

	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "hijack unsupported", http.StatusInternalServerError)
		return
	}
	clientConn, clientBuf, err := hj.Hijack()
	if err != nil {
		return
	}
	defer clientConn.Close()

	if err := writeSwitchingProtocols(clientConn, resp); err != nil {
		return
	}

	// Splice both directions; either close ends the tunnel.
	errc := make(chan error, 2)
	go func() { _, e := io.Copy(peerConn, clientBuf); errc <- e }()  // client → peer
	go func() { _, e := io.Copy(clientConn, peerConn); errc <- e }() // peer → client
	<-errc
}

// writeSwitchingProtocols writes the peer's 101 status line + headers to the
// hijacked client connection, ensuring the upgrade headers are present.
func writeSwitchingProtocols(w io.Writer, resp *http.Response) error {
	resp.Header.Set("Connection", "Upgrade")
	resp.Header.Set("Upgrade", "websocket")
	var b strings.Builder
	fmt.Fprintf(&b, "HTTP/1.1 %d %s\r\n", resp.StatusCode, http.StatusText(resp.StatusCode))
	if err := resp.Header.Write(&b); err != nil {
		return err
	}
	b.WriteString("\r\n")
	_, err := io.WriteString(w, b.String())
	return err
}

// flushStream copies src to w, flushing after every chunk so SSE events arrive
// promptly instead of buffering until EOF.
func flushStream(w http.ResponseWriter, src io.Reader) {
	rc := http.NewResponseController(w)
	buf := make([]byte, 32*1024)
	for {
		n, rerr := src.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			_ = rc.Flush() // ignore: some writers don't flush, harmless
		}
		if rerr != nil {
			return
		}
	}
}

// copyHeader copies headers from src to dst. When keepUpgrade is false, hop-by-hop
// headers are dropped (normal/SSE proxying); when true, all headers pass through
// (WebSocket upgrade).
func copyHeader(dst, src http.Header, keepUpgrade bool) {
	for k, vv := range src {
		if !keepUpgrade && isHopHeader(k) {
			continue
		}
		for _, v := range vv {
			dst.Add(k, v)
		}
	}
}

func isHopHeader(key string) bool {
	for _, h := range hopHeaders {
		if strings.EqualFold(key, h) {
			return true
		}
	}
	return false
}

// isWebSocketUpgrade reports whether r is a WebSocket upgrade handshake.
func isWebSocketUpgrade(r *http.Request) bool {
	return tokenInHeader(r.Header, "Connection", "upgrade") &&
		strings.EqualFold(strings.TrimSpace(r.Header.Get("Upgrade")), "websocket")
}

// tokenInHeader reports whether the comma-separated header contains token
// (case-insensitive).
func tokenInHeader(h http.Header, key, token string) bool {
	for _, part := range strings.Split(h.Get(key), ",") {
		if strings.EqualFold(strings.TrimSpace(part), token) {
			return true
		}
	}
	return false
}
