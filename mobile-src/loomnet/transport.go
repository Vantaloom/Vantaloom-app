package loomnet

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"sync"
	"time"

	quic "github.com/quic-go/quic-go"
)

const (
	// alpn is the QUIC/TLS application protocol; TLS 1.3 is forced (§3.1).
	alpn = "loom/1"

	idleTimeout        = 30 * time.Second
	keepAlivePeriod    = 15 * time.Second
	handshakeIdle      = 10 * time.Second
	maxIncomingStreams = 1 << 16
)

// transport owns the single shared UDP socket and its quic.Transport (§3.1):
// LAN-direct dialing and the inbound listener multiplex over this one socket.
type transport struct {
	acceptCtx context.Context // node lifetime; bounds inbound accepts and sessions
	identity  *Identity
	dir       Directory
	udp       *net.UDPConn
	qt        *quic.Transport
	quicConf  *quic.Config
}

func newTransport(acceptCtx context.Context, id *Identity, dir Directory, udpPort int) (*transport, error) {
	// udpPort 0 = ephemeral (LAN-only machines re-advertise each heartbeat);
	// a fixed port is required for 公网直连 so port-forward/安全组 rules hold.
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: udpPort})
	if err != nil {
		return nil, fmt.Errorf("loomnet: bind overlay udp socket (port %d): %w", udpPort, err)
	}
	return &transport{
		acceptCtx: acceptCtx,
		identity:  id,
		dir:       dir,
		udp:       udp,
		qt:        &quic.Transport{Conn: udp},
		quicConf: &quic.Config{
			MaxIdleTimeout:       idleTimeout,
			KeepAlivePeriod:      keepAlivePeriod,
			HandshakeIdleTimeout: handshakeIdle,
			MaxIncomingStreams:   maxIncomingStreams,
		},
	}, nil
}

func (t *transport) localUDPAddr() *net.UDPAddr {
	return t.udp.LocalAddr().(*net.UDPAddr)
}

// clientTLS is the outbound (dialer) config: it presents our certificate and
// pins the expected peer fingerprint (§2.3).
func (t *transport) clientTLS(expectedFingerprint string) *tls.Config {
	return &tls.Config{
		Certificates:          []tls.Certificate{t.identity.TLSCertificate()},
		NextProtos:            []string{alpn},
		MinVersion:            tls.VersionTLS13,
		InsecureSkipVerify:    true,
		VerifyPeerCertificate: verifyOutbound(expectedFingerprint),
	}
}

// serverTLS is the inbound (listener) config: it forces client certificates and
// accepts any peer whose CN→fingerprint is in the account set (§2.3).
func (t *transport) serverTLS() *tls.Config {
	return &tls.Config{
		Certificates:          []tls.Certificate{t.identity.TLSCertificate()},
		NextProtos:            []string{alpn},
		MinVersion:            tls.VersionTLS13,
		ClientAuth:            tls.RequireAnyClientCert,
		InsecureSkipVerify:    true,
		VerifyPeerCertificate: verifyInbound(t.dir.AccountFingerprints),
	}
}

// dial performs one outbound QUIC handshake to a concrete UDP address, pinning
// expectedFingerprint. intendedID is only for error context; the session's
// RemoteMachineID is taken from the verified peer certificate.
func (t *transport) dial(ctx context.Context, addr net.Addr, expectedFingerprint, intendedID string) (*quicSession, error) {
	conn, err := t.qt.Dial(ctx, addr, t.clientTLS(expectedFingerprint), t.quicConf)
	if err != nil {
		return nil, fmt.Errorf("loomnet: dial %s at %s: %w", intendedID, addr, err)
	}
	gotID, _, err := peerIdentity(conn.ConnectionState().TLS)
	if err != nil {
		_ = conn.CloseWithError(quic.ApplicationErrorCode(1), "missing identity")
		return nil, err
	}
	return newQUICSession(t.acceptCtx, conn, gotID), nil
}

func (t *transport) close() {
	if t.qt != nil {
		_ = t.qt.Close()
	}
	if t.udp != nil {
		_ = t.udp.Close()
	}
}

// quicListener adapts inbound QUIC connections into a net.Listener that yields
// one net.Conn per accepted stream (§3.3), so vantaloom-api can serve overlay
// requests with the same mux as local requests. Each yielded conn carries the
// peer's mTLS-verified machineID for X-Loom-From stamping.
type quicListener struct {
	tr       *transport
	ln       *quic.Listener
	ctx      context.Context
	accepted chan net.Conn
	closed   chan struct{}
	once     sync.Once
}

func (t *transport) listen() (*quicListener, error) {
	ln, err := t.qt.Listen(t.serverTLS(), t.quicConf)
	if err != nil {
		return nil, fmt.Errorf("loomnet: start overlay listener: %w", err)
	}
	l := &quicListener{
		tr:       t,
		ln:       ln,
		ctx:      t.acceptCtx,
		accepted: make(chan net.Conn),
		closed:   make(chan struct{}),
	}
	go l.acceptConns()
	return l, nil
}

func (l *quicListener) acceptConns() {
	for {
		conn, err := l.ln.Accept(l.ctx)
		if err != nil {
			return // listener closed or context done
		}
		id, _, err := peerIdentity(conn.ConnectionState().TLS)
		if err != nil {
			_ = conn.CloseWithError(quic.ApplicationErrorCode(1), "missing identity")
			continue
		}
		go l.demux(conn, id)
	}
}

// demux turns every stream a peer opens on one QUIC connection into a separate
// net.Conn handed to Accept, so http.Server treats each stream as its own HTTP
// connection.
func (l *quicListener) demux(conn *quic.Conn, id string) {
	for {
		st, err := conn.AcceptStream(l.ctx)
		if err != nil {
			return // connection gone
		}
		c := newStreamConn(conn, st, id)
		select {
		case l.accepted <- c:
		case <-l.closed:
			_ = st.Close()
			return
		case <-l.ctx.Done():
			_ = st.Close()
			return
		}
	}
}

func (l *quicListener) Accept() (net.Conn, error) {
	select {
	case c := <-l.accepted:
		return c, nil
	case <-l.closed:
		return nil, net.ErrClosed
	case <-l.ctx.Done():
		return nil, net.ErrClosed
	}
}

func (l *quicListener) Close() error {
	l.once.Do(func() {
		close(l.closed)
		_ = l.ln.Close()
	})
	return nil
}

func (l *quicListener) Addr() net.Addr { return l.tr.localUDPAddr() }
