package loomnet

import (
	"context"
	"fmt"
	"net"
	"sync"

	"github.com/hashicorp/yamux"
)

// relaySession is the relay-tier implementation of Session (design §3.2): a
// yamux multiplexer running over the inner mutual-TLS connection that A and B
// negotiated directly across the relay's spliced byte stream. The relay only
// ever saw ciphertext, so this Session is exactly as trusted as the direct/punch
// quicSession — RemoteMachineID is the peer identity proven by that inner mTLS.
type relaySession struct {
	sess     *yamux.Session
	remoteID string
}

func newRelaySession(sess *yamux.Session, remoteID string) *relaySession {
	return &relaySession{sess: sess, remoteID: remoteID}
}

// OpenStream opens a new multiplexed stream to the peer. yamux.OpenStream has no
// context, so we run it off-goroutine and honour ctx cancellation, closing a
// late-arriving stream if the caller already gave up.
func (s *relaySession) OpenStream(ctx context.Context) (net.Conn, error) {
	type res struct {
		st  *yamux.Stream
		err error
	}
	ch := make(chan res, 1)
	go func() {
		st, err := s.sess.OpenStream()
		ch <- res{st, err}
	}()
	select {
	case <-ctx.Done():
		go func() {
			if r := <-ch; r.st != nil {
				_ = r.st.Close()
			}
		}()
		return nil, ctx.Err()
	case r := <-ch:
		if r.err != nil {
			return nil, fmt.Errorf("loomnet: relay open stream to %s: %w", s.remoteID, r.err)
		}
		return r.st, nil
	}
}

func (s *relaySession) AcceptStream() (net.Conn, error) {
	st, err := s.sess.AcceptStream()
	if err != nil {
		return nil, fmt.Errorf("loomnet: relay accept stream from %s: %w", s.remoteID, err)
	}
	return st, nil
}

func (s *relaySession) RemoteMachineID() string { return s.remoteID }

func (s *relaySession) Close() error { return s.sess.Close() }

// closed reports whether the underlying yamux session has died, so the Node can
// evict a dead relay session from its connection cache (§3.4).
func (s *relaySession) closed() <-chan struct{} { return s.sess.CloseChan() }

// sessionListener adapts a responder relaySession into a net.Listener whose
// Accept yields one relayServeConn per inbound yamux stream. Feeding it to the
// Node's existing http.Server means relayed requests reach LocalHandler through
// the SAME serve path as direct QUIC streams — including the trusted X-Loom-From
// stamping keyed off RemoteMachineID (§3.3, §4.4, §2.4).
type sessionListener struct {
	sess Session
	addr net.Addr
	once sync.Once
}

func newSessionListener(s Session, addr net.Addr) *sessionListener {
	return &sessionListener{sess: s, addr: addr}
}

func (l *sessionListener) Accept() (net.Conn, error) {
	c, err := l.sess.AcceptStream()
	if err != nil {
		// Session gone: returning a non-temporary error makes http.Server.Serve
		// stop serving this listener (the session's streams are all dead anyway).
		return nil, err
	}
	return relayServeConn{Conn: c, remoteID: l.sess.RemoteMachineID()}, nil
}

func (l *sessionListener) Close() error {
	l.once.Do(func() { _ = l.sess.Close() })
	return nil
}

func (l *sessionListener) Addr() net.Addr { return l.addr }

// relayServeConn carries the mTLS-verified peer machineID alongside an accepted
// yamux stream so the shared http.Server's ConnContext (n.connContext) can stamp
// a trusted X-Loom-From — mirroring streamConn on the direct path.
type relayServeConn struct {
	net.Conn
	remoteID string
}

func (c relayServeConn) RemoteMachineID() string { return c.remoteID }
