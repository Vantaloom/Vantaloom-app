package loomnet

import (
	"context"
	"fmt"
	"net"

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
	conn      *quic.Conn
	remoteID  string
	acceptCtx context.Context // node lifetime; bounds the blocking AcceptStream
}

func newQUICSession(acceptCtx context.Context, conn *quic.Conn, remoteID string) *quicSession {
	return &quicSession{conn: conn, remoteID: remoteID, acceptCtx: acceptCtx}
}

func (s *quicSession) OpenStream(ctx context.Context) (net.Conn, error) {
	st, err := s.conn.OpenStreamSync(ctx)
	if err != nil {
		return nil, fmt.Errorf("loomnet: open stream to %s: %w", s.remoteID, err)
	}
	return newStreamConn(s.conn, st, s.remoteID), nil
}

func (s *quicSession) AcceptStream() (net.Conn, error) {
	st, err := s.conn.AcceptStream(s.acceptCtx)
	if err != nil {
		return nil, fmt.Errorf("loomnet: accept stream from %s: %w", s.remoteID, err)
	}
	return newStreamConn(s.conn, st, s.remoteID), nil
}

func (s *quicSession) RemoteMachineID() string { return s.remoteID }

// RemoteAddr is the peer's UDP address on this connection — used by the
// topology to classify HOW an adopted-inbound connection reached us (private
// source = 局域网, else 公网). Optional-interface style: callers type-assert
// `interface{ RemoteAddr() net.Addr }`.
func (s *quicSession) RemoteAddr() net.Addr { return s.conn.RemoteAddr() }

func (s *quicSession) Close() error {
	return s.conn.CloseWithError(quic.ApplicationErrorCode(0), "closed")
}

// streamConn adapts a *quic.Stream (which has no Local/RemoteAddr of its own)
// into a net.Conn by delegating addressing to the owning connection, per the
// design's dialStream note. It also carries the peer's mTLS-verified machineID
// so the inbound listener can stamp a trusted X-Loom-From (§2.4).
//
// Note: the embedded Stream.Close closes only the write side (FIN), which is the
// correct semantics for one HTTP request/response over a stream.
type streamConn struct {
	*quic.Stream
	local    net.Addr
	remote   net.Addr
	remoteID string
}

func newStreamConn(conn *quic.Conn, st *quic.Stream, remoteID string) *streamConn {
	return &streamConn{Stream: st, local: conn.LocalAddr(), remote: conn.RemoteAddr(), remoteID: remoteID}
}

func (c *streamConn) LocalAddr() net.Addr     { return c.local }
func (c *streamConn) RemoteAddr() net.Addr    { return c.remote }
func (c *streamConn) RemoteMachineID() string { return c.remoteID }

func (c *streamConn) Close() error {
	c.Stream.CancelRead(0)
	return c.Stream.Close()
}
