package loomnet

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/hashicorp/yamux"
	quic "github.com/quic-go/quic-go"
)

// This file implements the relay tier client (design §4.4–4.5): the RelayTransport
// for reaching a peer when direct and hole-punch both fail. It lives IN-PACKAGE
// and shares loomnet's single *quic.Transport/UDP socket (via transport.dialRelayQUIC)
// so the reflexive address the relay reports in HELLO-OK is the very socket QUIC
// punches with (§3.1, §4.3).
//
// The wire protocol to the Hub relay is implemented verbatim from
// services/hub/internal/relay/wire.go: 4-byte big-endian length-prefixed JSON
// control frames, a READY delimiter that flips the circuit stream from framed to
// RAW, and the inner A↔B mutual-TLS (ALPN "loom/1") + yamux that the relay never
// sees. The relay only splices ciphertext.

// relayALPN is the OUTER client↔relay ALPN, distinct from the inner overlay
// "loom/1" that the relay never sees. Matches relay.ALPN.
const relayALPN = "loom-relay/1"

// maxRelayFrame bounds a single control-phase JSON body (matches
// relay.MaxFrameSize). It does not limit the raw spliced payload.
const maxRelayFrame = 65535

// Relay wire message type discriminators (matches the relay Msg* constants).
const (
	relayMsgHello    = "HELLO"
	relayMsgHelloOK  = "HELLO-OK"
	relayMsgHelloErr = "HELLO-ERR"
	relayMsgOpen     = "OPEN"
	relayMsgIncoming = "INCOMING"
	relayMsgAccept   = "ACCEPT"
	relayMsgReady    = "READY"
	relayMsgOpenErr  = "OPEN-ERR"
)

// Timeouts and reconnect budgets for the relay client.
const (
	relayDialTimeout    = 8 * time.Second  // dial + control HELLO round trip
	relayCircuitSetup   = 15 * time.Second // OPEN/ACCEPT → READY (matches server setup timeout)
	relayInnerHandshake = 10 * time.Second // inner mTLS handshake over the spliced stream
	relayInitialBackoff = 500 * time.Millisecond
	relayMaxBackoff     = 30 * time.Second
)

// relayMsg is the on-wire control frame, mirroring relay.Message field-for-field
// so JSON marshalling is byte-compatible with the server.
type relayMsg struct {
	Type         string `json:"type"`
	MachineID    string `json:"machineID,omitempty"`
	JWT          string `json:"jwt,omitempty"`
	Target       string `json:"target,omitempty"`
	CircuitID    string `json:"circuitID,omitempty"`
	From         string `json:"from,omitempty"`
	ObservedAddr string `json:"observedAddr,omitempty"`
	Error        string `json:"error,omitempty"`
}

// writeRelayFrame encodes m as a length-prefixed JSON frame in a single Write
// (one frame == one message on message-oriented transports).
func writeRelayFrame(w io.Writer, m *relayMsg) error {
	body, err := json.Marshal(m)
	if err != nil {
		return fmt.Errorf("loomnet: relay marshal frame: %w", err)
	}
	if len(body) > maxRelayFrame {
		return fmt.Errorf("loomnet: relay frame too large (%d > %d)", len(body), maxRelayFrame)
	}
	frame := make([]byte, 4+len(body))
	binary.BigEndian.PutUint32(frame[:4], uint32(len(body)))
	copy(frame[4:], body)
	_, err = w.Write(frame)
	return err
}

// readRelayFrame reads exactly one length-prefixed JSON frame. It reads directly
// (io.ReadFull, no buffering) so it never consumes a byte past the frame — the
// raw inner-mTLS bytes that follow READY stay intact on the stream.
func readRelayFrame(r io.Reader) (*relayMsg, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return nil, err
	}
	n := binary.BigEndian.Uint32(hdr[:])
	if n == 0 || n > maxRelayFrame {
		return nil, fmt.Errorf("loomnet: relay bad frame length %d", n)
	}
	body := make([]byte, n)
	if _, err := io.ReadFull(r, body); err != nil {
		return nil, err
	}
	var m relayMsg
	if err := json.Unmarshal(body, &m); err != nil {
		return nil, fmt.Errorf("loomnet: relay unmarshal frame: %w", err)
	}
	return &m, nil
}

// relayDeps are the in-package wiring a Node hands the relayClient so it can
// share the transport/identity/directory and reach the Node's serve path.
type relayDeps struct {
	acceptCtx context.Context // node lifetime; bounds the control loop and inbound serving
	tr        *transport      // shared QUIC transport/socket
	identity  *Identity       // presents the inner mTLS certificate
	dir       Directory       // target fingerprint (initiator pin) + account set (responder verify)
	serve     func(Session)   // serve a responder session's streams into LocalHandler
	cfg       RelayConfig
}

// relayClient is the in-package RelayTransport implementation. It keeps one
// persistent control connection to the relay (auto-reconnecting), records the
// reflexive ObservedAddr from HELLO-OK, and services both directions of a
// circuit: DialViaRelay (initiator) and pushed INCOMING (responder).
type relayClient struct {
	acceptCtx context.Context
	tr        *transport
	identity  *Identity
	dir       Directory
	serve     func(Session)
	cfg       RelayConfig
	relayAddr *net.UDPAddr // resolved QUIC addr, nil if QUIC disabled

	mu       sync.Mutex
	link     relayLink     // current live relay connection, nil when down
	observed string        // reflexive addr from the latest HELLO-OK
	linkWait chan struct{} // closed whenever a new link is published (wakes awaitLink)
}

// compile-time assertion: relayClient satisfies the injected RelayTransport.
var _ RelayTransport = (*relayClient)(nil)

// newRelayClient validates the config and builds the client. It does not connect
// yet — call start.
func newRelayClient(d relayDeps) (*relayClient, error) {
	cfg := d.cfg
	if cfg.QUICAddr == "" && cfg.WSSURL == "" {
		return nil, errors.New("loomnet: RelayConfig needs a QUICAddr or WSSURL")
	}
	if cfg.JWTProvider == nil {
		return nil, errors.New("loomnet: RelayConfig.JWTProvider is required")
	}
	rc := &relayClient{
		acceptCtx: d.acceptCtx,
		tr:        d.tr,
		identity:  d.identity,
		dir:       d.dir,
		serve:     d.serve,
		cfg:       cfg,
		linkWait:  make(chan struct{}),
	}
	if cfg.QUICAddr != "" {
		if cfg.RelayFingerprint == "" {
			return nil, errors.New("loomnet: RelayConfig.QUICAddr requires RelayFingerprint (SPKI pin)")
		}
		addr, err := net.ResolveUDPAddr("udp", cfg.QUICAddr)
		if err != nil {
			return nil, fmt.Errorf("loomnet: resolve relay QUICAddr %q: %w", cfg.QUICAddr, err)
		}
		rc.relayAddr = addr
	}
	return rc, nil
}

// start launches the control connection loop for the node's lifetime.
func (rc *relayClient) start() { go rc.controlLoop() }

// ObservedAddr is this node's reflexive "ip:port" from the latest HELLO-OK, or ""
// if the control connection is not up (then hole punching is skipped, §4.3).
func (rc *relayClient) ObservedAddr() string {
	rc.mu.Lock()
	defer rc.mu.Unlock()
	return rc.observed
}

// jwt re-reads the Hub token per use (tokens rotate).
func (rc *relayClient) jwt() string { return rc.cfg.JWTProvider() }

// ── control connection loop ───────────────────────────────────────────────────

// controlLoop keeps one control connection to the relay alive, reconnecting with
// capped exponential backoff. Each session owns one relayLink (a QUIC connection
// or the WSS dialer); the control stream lives on it and inbound circuit streams
// are opened on it.
func (rc *relayClient) controlLoop() {
	backoff := relayInitialBackoff
	for rc.acceptCtx.Err() == nil {
		link, control, observed, err := rc.connect(rc.acceptCtx)
		if err != nil {
			if rc.sleep(backoff) {
				return
			}
			backoff = nextBackoff(backoff)
			continue
		}
		backoff = relayInitialBackoff // reset after a successful HELLO-OK
		rc.setLink(link, observed)
		rc.readIncoming(link, control) // blocks until the control stream dies
		rc.clearLink(link)
		_ = control.Close()
		_ = link.close()
		if rc.sleep(backoff) {
			return
		}
	}
}

// connect brings up one control connection: QUIC first (primary, over the shared
// socket), WSS as the fallback when QUIC is unavailable (§4.5).
func (rc *relayClient) connect(ctx context.Context) (relayLink, net.Conn, string, error) {
	var errs []error
	if rc.relayAddr != nil {
		link, control, observed, err := rc.connectQUIC(ctx)
		if err == nil {
			return link, control, observed, nil
		}
		errs = append(errs, fmt.Errorf("quic: %w", err))
	}
	if rc.cfg.WSSURL != "" {
		link, control, observed, err := rc.connectWSS(ctx)
		if err == nil {
			return link, control, observed, nil
		}
		errs = append(errs, fmt.Errorf("wss: %w", err))
	}
	return nil, nil, "", fmt.Errorf("loomnet: relay connect failed: %w", errors.Join(errs...))
}

func (rc *relayClient) connectQUIC(ctx context.Context) (relayLink, net.Conn, string, error) {
	dctx, cancel := context.WithTimeout(ctx, relayDialTimeout)
	defer cancel()
	conn, err := rc.tr.dialRelayQUIC(dctx, rc.relayAddr, rc.relayTLS())
	if err != nil {
		return nil, nil, "", err
	}
	link := &quicRelayLink{conn: conn}
	control, observed, err := rc.handshakeControl(dctx, link)
	if err != nil {
		_ = link.close()
		return nil, nil, "", err
	}
	return link, control, observed, nil
}

func (rc *relayClient) connectWSS(ctx context.Context) (relayLink, net.Conn, string, error) {
	dctx, cancel := context.WithTimeout(ctx, relayDialTimeout)
	defer cancel()
	link := &wssRelayLink{
		dialer: &websocket.Dialer{HandshakeTimeout: relayDialTimeout},
		url:    rc.cfg.WSSURL,
	}
	control, observed, err := rc.handshakeControl(dctx, link)
	if err != nil {
		return nil, nil, "", err
	}
	return link, control, observed, nil
}

// handshakeControl opens the control stream, sends HELLO, and reads HELLO-OK. The
// control stream is the first stream opened on the link; on QUIC it multiplexes
// with later circuit streams, on WSS it is its own connection.
func (rc *relayClient) handshakeControl(ctx context.Context, link relayLink) (net.Conn, string, error) {
	control, err := link.openStream(ctx)
	if err != nil {
		return nil, "", err
	}
	// Bound the framed handshake, then clear the deadline for the long-lived read.
	if dl, ok := ctx.Deadline(); ok {
		_ = control.SetDeadline(dl)
	}
	if err := writeRelayFrame(control, &relayMsg{Type: relayMsgHello, MachineID: rc.identity.MachineID(), JWT: rc.jwt()}); err != nil {
		_ = control.Close()
		return nil, "", err
	}
	m, err := readRelayFrame(control)
	if err != nil {
		_ = control.Close()
		return nil, "", err
	}
	if m.Type != relayMsgHelloOK {
		_ = control.Close()
		if m.Type == relayMsgHelloErr {
			return nil, "", fmt.Errorf("loomnet: relay HELLO-ERR: %s", m.Error)
		}
		return nil, "", fmt.Errorf("loomnet: relay unexpected HELLO reply %q", m.Type)
	}
	_ = control.SetDeadline(time.Time{}) // long-lived: block until INCOMING or close
	return control, m.ObservedAddr, nil
}

// readIncoming drains pushed INCOMING frames on the control stream for the life
// of the connection; each spawns the responder half. Any read error (control
// stream / connection died) returns so controlLoop reconnects.
func (rc *relayClient) readIncoming(link relayLink, control net.Conn) {
	for {
		m, err := readRelayFrame(control)
		if err != nil {
			return
		}
		if m.Type == relayMsgIncoming {
			go rc.handleIncoming(link, m.CircuitID)
		}
		// Other frames on the control stream are ignored (the relay does not send
		// any once HELLO-OK is delivered).
	}
}

// ── initiator: DialViaRelay ───────────────────────────────────────────────────

// DialViaRelay opens a circuit to target through the relay and negotiates the
// inner mTLS (as TLS client, pinning target's fingerprint) + yamux client on top
// of the spliced byte stream, returning a relaySession. The relay only ever sees
// the resulting ciphertext.
func (rc *relayClient) DialViaRelay(ctx context.Context, target string) (Session, error) {
	fp, _, ok := rc.dir.PeerInfo(target)
	if !ok {
		return nil, fmt.Errorf("loomnet: relay: no directory entry for %s", target)
	}
	link, err := rc.awaitLink(ctx)
	if err != nil {
		return nil, fmt.Errorf("loomnet: relay: control not connected: %w", err)
	}
	st, err := link.openStream(ctx)
	if err != nil {
		return nil, fmt.Errorf("loomnet: relay: open circuit: %w", err)
	}
	// Bound the framed OPEN→READY exchange by the smaller of the circuit-setup cap
	// and the caller's deadline, so the relay tier never overruns the dial ladder's
	// per-tier budget (§4.6).
	setupDeadline := time.Now().Add(relayCircuitSetup)
	if d, ok := ctx.Deadline(); ok && d.Before(setupDeadline) {
		setupDeadline = d
	}
	_ = st.SetDeadline(setupDeadline)

	cid := newCircuitID()
	open := &relayMsg{Type: relayMsgOpen, CircuitID: cid, Target: target, MachineID: rc.identity.MachineID(), JWT: rc.jwt()}
	if err := writeRelayFrame(st, open); err != nil {
		_ = st.Close()
		return nil, fmt.Errorf("loomnet: relay: send OPEN: %w", err)
	}
	m, err := readRelayFrame(st)
	if err != nil {
		_ = st.Close()
		return nil, fmt.Errorf("loomnet: relay: await READY: %w", err)
	}
	if m.Type != relayMsgReady {
		_ = st.Close()
		if m.Type == relayMsgOpenErr {
			return nil, fmt.Errorf("loomnet: relay OPEN-ERR: %s", m.Error)
		}
		return nil, fmt.Errorf("loomnet: relay: unexpected OPEN reply %q", m.Type)
	}
	_ = st.SetDeadline(time.Time{}) // raw phase from here; the inner handshake bounds itself

	// Inner mTLS (client, pin target) + yamux client — reusing the exact same peer
	// mTLS config as the direct tier (ALPN "loom/1", present our cert, verifyOutbound).
	tconn := tls.Client(st, rc.tr.clientTLS(fp))
	if err := rc.innerHandshake(ctx, tconn); err != nil {
		_ = st.Close()
		return nil, fmt.Errorf("loomnet: relay: inner TLS to %s: %w", target, err)
	}
	remoteID, _, err := peerIdentity(tconn.ConnectionState())
	if err != nil {
		_ = tconn.Close()
		return nil, err
	}
	sess, err := yamux.Client(tconn, yamuxConfig())
	if err != nil {
		_ = tconn.Close()
		return nil, fmt.Errorf("loomnet: relay: yamux client: %w", err)
	}
	return newRelaySession(sess, remoteID), nil
}

// ── responder: handle a pushed INCOMING ───────────────────────────────────────

// handleIncoming is the responder half of a circuit: on the same link the
// INCOMING arrived on, open a fresh circuit stream, ACCEPT, await READY, then run
// the inner mTLS as TLS server (verify the peer against the account set) + yamux
// server, and serve the accepted streams into LocalHandler exactly like the
// direct-path listener does.
func (rc *relayClient) handleIncoming(link relayLink, circuitID string) {
	if circuitID == "" {
		return
	}
	ctx, cancel := context.WithTimeout(rc.acceptCtx, relayCircuitSetup)
	defer cancel()

	st, err := link.openStream(ctx)
	if err != nil {
		return
	}
	_ = st.SetDeadline(time.Now().Add(relayCircuitSetup))
	if err := writeRelayFrame(st, &relayMsg{Type: relayMsgAccept, CircuitID: circuitID, JWT: rc.jwt()}); err != nil {
		_ = st.Close()
		return
	}
	m, err := readRelayFrame(st)
	if err != nil || m.Type != relayMsgReady {
		_ = st.Close()
		return
	}
	_ = st.SetDeadline(time.Time{})

	// Inner mTLS (server) — reuse the direct tier's server config: present our
	// cert, RequireAnyClientCert, verifyInbound against the fresh account set.
	tconn := tls.Server(st, rc.tr.serverTLS())
	if err := rc.innerHandshake(rc.acceptCtx, tconn); err != nil {
		_ = st.Close()
		return
	}
	remoteID, _, err := peerIdentity(tconn.ConnectionState())
	if err != nil {
		_ = tconn.Close()
		return
	}
	sess, err := yamux.Server(tconn, yamuxConfig())
	if err != nil {
		_ = tconn.Close()
		return
	}
	rc.serve(newRelaySession(sess, remoteID))
}

// innerHandshake runs the crypto/tls handshake over the spliced stream, bounded
// by a timeout derived from ctx.
func (rc *relayClient) innerHandshake(ctx context.Context, tconn *tls.Conn) error {
	hctx, cancel := context.WithTimeout(ctx, relayInnerHandshake)
	defer cancel()
	return tconn.HandshakeContext(hctx)
}

// ── outer relay TLS (pin) ──────────────────────────────────────────────────────

// relayTLS is the OUTER client→relay QUIC TLS: ALPN "loom-relay/1", no client
// cert (the relay authenticates by Hub JWT, not TLS), and the relay's self-signed
// leaf pinned by SPKI fingerprint via VerifyConnection (§4.4, wire contract).
func (rc *relayClient) relayTLS() *tls.Config {
	return &tls.Config{
		NextProtos:         []string{relayALPN},
		MinVersion:         tls.VersionTLS13,
		InsecureSkipVerify: true, // the pin below is the sole trust authority
		VerifyConnection:   verifyRelayPin(rc.cfg.RelayFingerprint),
	}
}

// verifyRelayPin compares the relay leaf's SPKI fingerprint to the pinned value.
// spkiFingerprint is base64(sha256(SubjectPublicKeyInfo)) — identical to the
// relay's published SPKISHA256B64.
func verifyRelayPin(pinned string) func(tls.ConnectionState) error {
	return func(cs tls.ConnectionState) error {
		if len(cs.PeerCertificates) == 0 {
			return errors.New("loomnet: relay presented no certificate")
		}
		leaf := cs.PeerCertificates[0]
		if got := spkiFingerprint(leaf); got != pinned {
			return fmt.Errorf("loomnet: relay fingerprint mismatch: got %s, pinned %s", got, pinned)
		}
		return nil
	}
}

// ── link publish/await ─────────────────────────────────────────────────────────

func (rc *relayClient) setLink(l relayLink, observed string) {
	rc.mu.Lock()
	rc.link = l
	rc.observed = observed
	prev := rc.linkWait
	rc.linkWait = make(chan struct{})
	rc.mu.Unlock()
	close(prev) // wake awaitLink waiters
}

func (rc *relayClient) clearLink(l relayLink) {
	rc.mu.Lock()
	if rc.link == l {
		rc.link = nil
	}
	rc.mu.Unlock()
}

// awaitLink returns the current live link, waiting (up to ctx / node lifetime)
// for one to come up if the control connection is mid-reconnect.
func (rc *relayClient) awaitLink(ctx context.Context) (relayLink, error) {
	for {
		rc.mu.Lock()
		l, ch := rc.link, rc.linkWait
		rc.mu.Unlock()
		if l != nil {
			return l, nil
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-rc.acceptCtx.Done():
			return nil, rc.acceptCtx.Err()
		case <-ch:
		}
	}
}

func (rc *relayClient) sleep(d time.Duration) (stop bool) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-rc.acceptCtx.Done():
		return true
	case <-t.C:
		return false
	}
}

func nextBackoff(d time.Duration) time.Duration {
	d *= 2
	if d > relayMaxBackoff {
		return relayMaxBackoff
	}
	return d
}

func newCircuitID() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}

func yamuxConfig() *yamux.Config {
	c := yamux.DefaultConfig()
	c.EnableKeepAlive = true
	c.LogOutput = io.Discard
	return c
}

// ── relayLink: a live connection to the relay over one transport ──────────────

// relayLink abstracts the two carriers of the relay wire protocol (§4.4): a QUIC
// connection (streams multiplexed on it) or the WSS dialer (each stream a fresh
// connection). Either way openStream yields a fresh reliable byte stream whose
// first frame's type tells the relay whether it is the control stream or a
// circuit stream.
type relayLink interface {
	openStream(ctx context.Context) (net.Conn, error)
	close() error
}

// quicRelayLink multiplexes control + circuit streams on one relay QUIC
// connection over the shared socket.
type quicRelayLink struct {
	conn *quic.Conn
}

func (l *quicRelayLink) openStream(ctx context.Context) (net.Conn, error) {
	st, err := l.conn.OpenStreamSync(ctx)
	if err != nil {
		return nil, err
	}
	return &relayStreamConn{Stream: st, local: l.conn.LocalAddr(), remote: l.conn.RemoteAddr()}, nil
}

func (l *quicRelayLink) close() error {
	return l.conn.CloseWithError(quic.ApplicationErrorCode(0), "")
}

// relayStreamConn adapts a relay circuit/control *quic.Stream into a net.Conn.
// Unlike the direct-path streamConn (FIN-only Close for one request/response),
// its Close fully tears the stream down (CancelRead + FIN) so a blocked Read
// unblocks when the yamux session over it ends — matching the relay's own
// splice-teardown semantics.
type relayStreamConn struct {
	*quic.Stream
	local  net.Addr
	remote net.Addr
}

func (c *relayStreamConn) LocalAddr() net.Addr  { return c.local }
func (c *relayStreamConn) RemoteAddr() net.Addr { return c.remote }
func (c *relayStreamConn) Close() error {
	c.Stream.CancelRead(0)
	return c.Stream.Close()
}

// wssRelayLink dials a fresh WSS connection per stream (control or circuit); one
// WSS connection == one relay stream (§4.5). There is no shared connection object,
// so close is a no-op — each stream conn is closed on its own.
type wssRelayLink struct {
	dialer *websocket.Dialer
	url    string
}

func (l *wssRelayLink) openStream(ctx context.Context) (net.Conn, error) {
	ws, _, err := l.dialer.DialContext(ctx, l.url, http.Header{})
	if err != nil {
		return nil, err
	}
	return newWSConn(ws), nil
}

func (l *wssRelayLink) close() error { return nil }

// wsConn adapts a gorilla *websocket.Conn to a net.Conn over binary messages:
// each Write is one binary message; Read reassembles the byte stream across
// messages. It mirrors the relay server's wsStream so the framed control phase
// and the raw splice phase both work as an ordered byte stream. gorilla allows
// one concurrent reader and one concurrent writer, which is exactly how tls+yamux
// drive it.
type wsConn struct {
	conn      *websocket.Conn
	r         io.Reader // current binary message reader, nil between messages
	wmu       sync.Mutex
	closeOnce sync.Once
}

func newWSConn(c *websocket.Conn) *wsConn { return &wsConn{conn: c} }

func (s *wsConn) Read(p []byte) (int, error) {
	for {
		if s.r == nil {
			mt, r, err := s.conn.NextReader()
			if err != nil {
				return 0, err
			}
			if mt != websocket.BinaryMessage {
				continue // only binary carries protocol bytes
			}
			s.r = r
		}
		n, err := s.r.Read(p)
		if err == io.EOF {
			s.r = nil
			if n > 0 {
				return n, nil
			}
			continue
		}
		return n, err
	}
}

func (s *wsConn) Write(p []byte) (int, error) {
	s.wmu.Lock()
	defer s.wmu.Unlock()
	if err := s.conn.WriteMessage(websocket.BinaryMessage, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (s *wsConn) Close() error {
	s.closeOnce.Do(func() { _ = s.conn.Close() })
	return nil
}

func (s *wsConn) LocalAddr() net.Addr  { return s.conn.LocalAddr() }
func (s *wsConn) RemoteAddr() net.Addr { return s.conn.RemoteAddr() }

func (s *wsConn) SetDeadline(t time.Time) error {
	_ = s.conn.SetReadDeadline(t)
	return s.conn.SetWriteDeadline(t)
}
func (s *wsConn) SetReadDeadline(t time.Time) error  { return s.conn.SetReadDeadline(t) }
func (s *wsConn) SetWriteDeadline(t time.Time) error { return s.conn.SetWriteDeadline(t) }
