package loomnet

import (
	"context"
	"fmt"
	"net"
	"sync"

	quic "github.com/quic-go/quic-go"
)

// Session is the transport-agnostic view of a peer connection (design §3.2).
// Every dial method produces a Session, so the HTTP layer only ever calls
// OpenStream/AcceptStream and streaming (SSE, terminals) works identically on
// every method.
type Session interface {
	// OpenStream opens a new multiplexed stream to the peer (near side sends).
	OpenStream(ctx context.Context) (net.Conn, error)
	// AcceptStream returns the next stream the peer opened (far side receives).
	AcceptStream() (net.Conn, error)
	// RemoteMachineID is the peer's mTLS-verified machine identity.
	RemoteMachineID() string
	Close() error
}

// quicSession is the QUIC implementation of Session: QUIC over the shared UDP
// socket, with native stream multiplexing.
type quicSession struct {
	conn        *quic.Conn
	remoteID    string
	fingerprint string          // peer's mTLS-verified SPKI fingerprint
	acceptCtx   context.Context // node lifetime; bounds the blocking AcceptStream
}

func newQUICSession(acceptCtx context.Context, conn *quic.Conn, remoteID, fingerprint string) *quicSession {
	return &quicSession{conn: conn, remoteID: remoteID, fingerprint: fingerprint, acceptCtx: acceptCtx}
}

func (s *quicSession) OpenStream(ctx context.Context) (net.Conn, error) {
	st, err := s.conn.OpenStreamSync(ctx)
	if err != nil {
		return nil, fmt.Errorf("loomnet: open stream to %s: %w", s.remoteID, err)
	}
	return newStreamConn(s.conn, st, s.remoteID, s.fingerprint), nil
}

func (s *quicSession) AcceptStream() (net.Conn, error) {
	st, err := s.conn.AcceptStream(s.acceptCtx)
	if err != nil {
		return nil, fmt.Errorf("loomnet: accept stream from %s: %w", s.remoteID, err)
	}
	return newStreamConn(s.conn, st, s.remoteID, s.fingerprint), nil
}

func (s *quicSession) RemoteMachineID() string   { return s.remoteID }
func (s *quicSession) RemoteFingerprint() string { return s.fingerprint }

// RemoteAddr is the peer's UDP address on this connection — used by the
// topology to classify HOW an adopted-inbound connection reached us (private
// source = 局域网, else 公网). Optional-interface style: callers type-assert
// `interface{ RemoteAddr() net.Addr }`.
func (s *quicSession) RemoteAddr() net.Addr { return s.conn.RemoteAddr() }

func (s *quicSession) Close() error {
	return s.conn.CloseWithError(quic.ApplicationErrorCode(0), "closed")
}

// punchSession 包装 quicSession，额外持有独立 UDP socket 和 QUIC transport。
// 打洞使用独立 socket（非 overlay socket），socket 生命周期必须延续到 QUIC 连接
// 关闭——Close 时先关 QUIC 连接，再关 transport 和 UDP socket。
type punchSession struct {
	*quicSession
	qt        *quic.Transport
	conn      *net.UDPConn
	closeOnce sync.Once
	closeErr  error
}

// A punch owns a transport/socket even when it is no longer the cache winner.
// Watch its own lifetime, not cache membership: replacement must neither leak
// it on natural death nor interrupt a healthy predecessor's active streams.
func newPunchSession(owner context.Context, qs *quicSession, qt *quic.Transport, conn *net.UDPConn) *punchSession {
	s := &punchSession{quicSession: qs, qt: qt, conn: conn}
	go func() {
		select {
		case <-qs.conn.Context().Done():
		case <-owner.Done():
		}
		_ = s.Close()
	}()
	return s
}

func (s *punchSession) Close() error {
	s.closeOnce.Do(func() {
		if s.quicSession != nil {
			s.closeErr = s.quicSession.Close()
		}
		if s.qt != nil {
			_ = s.qt.Close()
		}
		if s.conn != nil {
			_ = s.conn.Close()
		}
	})
	return s.closeErr
}

// RemoteAddr 透传到内层 quicSession（拓扑分类用）。
func (s *punchSession) RemoteAddr() net.Addr { return s.quicSession.RemoteAddr() }

// streamConn adapts a *quic.Stream (which has no Local/RemoteAddr of its own)
// into a net.Conn by delegating addressing to the owning connection, per the
// design's dialStream note. It also carries the peer's mTLS-verified machineID
// so the inbound listener can stamp a trusted X-Loom-From (§2.4).
//
// Note: the embedded Stream.Close closes only the write side (FIN), which is the
// correct semantics for one HTTP request/response over a stream.
type streamConn struct {
	*quic.Stream
	local       net.Addr
	remote      net.Addr
	remoteID    string
	fingerprint string
}

func newStreamConn(conn *quic.Conn, st *quic.Stream, remoteID, fingerprint string) *streamConn {
	return &streamConn{Stream: st, local: conn.LocalAddr(), remote: conn.RemoteAddr(), remoteID: remoteID, fingerprint: fingerprint}
}

func (c *streamConn) LocalAddr() net.Addr       { return c.local }
func (c *streamConn) RemoteAddr() net.Addr      { return c.remote }
func (c *streamConn) RemoteMachineID() string   { return c.remoteID }
func (c *streamConn) RemoteFingerprint() string { return c.fingerprint }

func (c *streamConn) Close() error {
	c.Stream.CancelRead(0)
	return c.Stream.Close()
}
