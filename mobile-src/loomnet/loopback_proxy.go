package loomnet

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
)

// LoopbackProxy is the 127.0.0.1 HTTP server a control-only shell (Android
// WebView / iOS WKWebView) points its web client at. It rewrites every request
// to http://<target>.loom<uri> and round-trips it over the overlay transport,
// streaming the response (flush per chunk → SSE-safe) and tunnelling WebSocket
// upgrades end-to-end.
//
// It lives in loomnet (not in the gomobile facade) so that both mobile shells
// share ONE implementation: `apps/api/mobile` (Android, gomobile bind) and
// `apps/api/internal/controllernode` (iOS, c-archive) both build on it. loomnet
// is copied verbatim into the public repo's mobile-src by
// scripts/sync-mobile-src.ps1, so the Android build picks this file up with
// zero pipeline changes. It imports only the standard library.
//
// The transport is taken as an http.RoundTripper (normally node.Transport())
// rather than a *Node so the proxy is unit-testable without a live overlay.
type LoopbackProxy struct {
	client   *http.Client  // over the overlay transport; NO timeout (streaming/WS)
	targetFn func() string // the current peer machineID ("" = none)
	srv      *http.Server
	ln       net.Listener
	mu       sync.Mutex
	bound    int
}

// loopbackHopHeaders are stripped when proxying a normal request/response (RFC
// 7230 §6.1). WebSocket upgrades deliberately keep Connection/Upgrade (handled
// apart).
var loopbackHopHeaders = []string{
	"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate",
	"Proxy-Authorization", "Te", "Trailer", "Transfer-Encoding", "Upgrade",
}

// NewLoopbackProxy builds a proxy that forwards over rt to the peer returned by
// targetFn at request time. Nothing listens until Start; the proxy is also a
// plain http.Handler (ServeHTTP) so a shell can mount it under its own server.
func NewLoopbackProxy(rt http.RoundTripper, targetFn func() string) *LoopbackProxy {
	if targetFn == nil {
		targetFn = func() string { return "" }
	}
	p := &LoopbackProxy{
		// No client timeout: SSE and WebSocket streams are long-lived. Cancellation
		// rides on the inbound request context (the WebView closing the connection).
		client:   &http.Client{Transport: rt},
		targetFn: targetFn,
	}
	p.srv = &http.Server{Handler: p}
	return p
}

// Start binds 127.0.0.1:0 and serves until Stop; returns once listening.
func (p *LoopbackProxy) Start() error {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("loomnet: loopback listen: %w", err)
	}
	p.mu.Lock()
	p.ln = ln
	p.bound = ln.Addr().(*net.TCPAddr).Port
	p.mu.Unlock()
	go func() { _ = p.srv.Serve(ln) }()
	return nil
}

// Port is the bound 127.0.0.1 port, or 0 before Start.
func (p *LoopbackProxy) Port() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.bound
}

// Stop closes the listener and every in-flight connection. A proxy mounted via
// ServeHTTP under another server is unaffected (nothing to stop).
func (p *LoopbackProxy) Stop() {
	if p.srv != nil {
		_ = p.srv.Close()
	}
}

// ServeHTTP rewrites the request to the current peer over the overlay. With no
// target selected it answers 503 — the web client shows the machine picker.
func (p *LoopbackProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	target := p.targetFn()
	if target == "" {
		http.Error(w, "no overlay target selected", http.StatusServiceUnavailable)
		return
	}
	// RequestURI() preserves the raw path + query so encoded segments survive.
	outURL := "http://" + target + ".loom" + r.URL.RequestURI()

	if isLoopbackWebSocketUpgrade(r) {
		p.proxyWebSocket(w, r, outURL)
		return
	}
	p.proxyHTTP(w, r, outURL)
}

// proxyHTTP forwards a normal/SSE request and streams the response back, flushing
// each chunk so server-sent events are not buffered until close.
func (p *LoopbackProxy) proxyHTTP(w http.ResponseWriter, r *http.Request, outURL string) {
	outReq, err := http.NewRequestWithContext(r.Context(), r.Method, outURL, r.Body)
	if err != nil {
		http.Error(w, "bad gateway: "+err.Error(), http.StatusBadGateway)
		return
	}
	copyLoopbackHeader(outReq.Header, r.Header, false)
	// Preserve the client's declared body length for non-chunked requests.
	outReq.ContentLength = r.ContentLength

	resp, err := p.client.Do(outReq)
	if err != nil {
		http.Error(w, "overlay dial failed: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	copyLoopbackHeader(w.Header(), resp.Header, false)
	w.WriteHeader(resp.StatusCode)
	flushLoopbackStream(w, resp.Body)
}

// proxyWebSocket tunnels a WebSocket upgrade end-to-end over the overlay. The
// overlay stream is a plain net.Conn, so http.Transport hands back the raw
// connection as an io.ReadWriteCloser on the peer's 101 response; we then splice
// it to the hijacked client connection.
func (p *LoopbackProxy) proxyWebSocket(w http.ResponseWriter, r *http.Request, outURL string) {
	outReq, err := http.NewRequestWithContext(r.Context(), r.Method, outURL, nil)
	if err != nil {
		http.Error(w, "bad gateway: "+err.Error(), http.StatusBadGateway)
		return
	}
	// Keep the upgrade headers (Connection/Upgrade/Sec-WebSocket-*) so the peer
	// completes the handshake against the client's own key.
	copyLoopbackHeader(outReq.Header, r.Header, true)

	resp, err := p.client.Do(outReq)
	if err != nil {
		http.Error(w, "overlay dial failed: "+err.Error(), http.StatusBadGateway)
		return
	}
	if resp.StatusCode != http.StatusSwitchingProtocols {
		// The peer declined the upgrade; relay its response verbatim.
		defer resp.Body.Close()
		copyLoopbackHeader(w.Header(), resp.Header, false)
		w.WriteHeader(resp.StatusCode)
		flushLoopbackStream(w, resp.Body)
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

	if err := writeLoopbackSwitchingProtocols(clientConn, resp); err != nil {
		return
	}

	// Splice both directions; either close ends the tunnel.
	errc := make(chan error, 2)
	go func() { _, e := io.Copy(peerConn, clientBuf); errc <- e }()  // client → peer
	go func() { _, e := io.Copy(clientConn, peerConn); errc <- e }() // peer → client
	<-errc
}

// writeLoopbackSwitchingProtocols writes the peer's 101 status line + headers
// to the hijacked client connection, ensuring the upgrade headers are present.
func writeLoopbackSwitchingProtocols(w io.Writer, resp *http.Response) error {
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

// flushLoopbackStream copies src to w, flushing after every chunk so SSE events
// arrive promptly instead of buffering until EOF.
func flushLoopbackStream(w http.ResponseWriter, src io.Reader) {
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

// copyLoopbackHeader copies headers from src to dst. When keepUpgrade is false,
// hop-by-hop headers are dropped (normal/SSE proxying); when true, all headers
// pass through (WebSocket upgrade).
func copyLoopbackHeader(dst, src http.Header, keepUpgrade bool) {
	for k, vv := range src {
		if !keepUpgrade && isLoopbackHopHeader(k) {
			continue
		}
		for _, v := range vv {
			dst.Add(k, v)
		}
	}
}

func isLoopbackHopHeader(key string) bool {
	for _, h := range loopbackHopHeaders {
		if strings.EqualFold(key, h) {
			return true
		}
	}
	return false
}

// isLoopbackWebSocketUpgrade reports whether r is a WebSocket upgrade handshake.
func isLoopbackWebSocketUpgrade(r *http.Request) bool {
	return loopbackTokenInHeader(r.Header, "Connection", "upgrade") &&
		strings.EqualFold(strings.TrimSpace(r.Header.Get("Upgrade")), "websocket")
}

// loopbackTokenInHeader reports whether the comma-separated header contains
// token (case-insensitive).
func loopbackTokenInHeader(h http.Header, key, token string) bool {
	for _, part := range strings.Split(h.Get(key), ",") {
		if strings.EqualFold(strings.TrimSpace(part), token) {
			return true
		}
	}
	return false
}
