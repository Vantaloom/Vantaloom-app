package loomnet

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"
	"time"
)

// Per-tier budgets for a single dial attempt (§4.6). QUIC handshakes are fast,
// so these are tighter than the legacy easytier ladder.
const (
	directTimeout = 3 * time.Second
)

// Last-path labels reported by LastPath for the topology UI (§4.6, §7.4).
const (
	pathDirect = "direct"
)

// getOrDialConn returns a live Session to machineID, reusing the cached one or
// running the dial ladder (§4, §3.4). Concurrent callers for the same machineID
// collapse onto one dial via singleflight; the winning session is cached and
// reused until it dies.
func (n *Node) getOrDialConn(ctx context.Context, machineID string) (Session, error) {
	if n.tr == nil {
		return nil, errors.New("loomnet: node not started")
	}
	if s := n.cachedConn(machineID); s != nil {
		return s, nil
	}
	return n.dials.do(machineID, func() (Session, error) {
		// Re-check the cache: a concurrent flight may have just populated it.
		if s := n.cachedConn(machineID); s != nil {
			return s, nil
		}
		s, path, err := n.dialLadder(ctx, machineID)
		if err != nil {
			return nil, err
		}
		n.storeConn(machineID, s, path)
		return s, nil
	})
}

// dialLadder delegates to the DialerRegistry, which runs registered dialers in
// priority order (currently just LAN direct). New methods can be added via
// n.Registry.Register() without modifying this function.
func (n *Node) dialLadder(ctx context.Context, machineID string) (Session, string, error) {
	return n.Registry.DialLadder(ctx, machineID)
}

// dialResult carries one parallel direct-dial outcome.
type dialResult struct {
	s   *quicSession
	err error
}

// dialDirect races QUIC handshakes against every LAN candidate in parallel;
// the first success wins and the losers are cancelled (§4.2).
func (n *Node) dialDirect(ctx context.Context, machineID, fp string, eps Endpoints) (*quicSession, error) {
	cands := candidateAddrs(eps)
	if len(cands) == 0 {
		return nil, errors.New("loomnet: direct: no candidate addresses")
	}
	dctx, cancel := context.WithTimeout(ctx, directTimeout)
	defer cancel()

	ch := make(chan dialResult, len(cands))
	for _, addr := range cands {
		go func(a *net.UDPAddr) {
			s, err := n.tr.dial(dctx, a, fp, machineID)
			ch <- dialResult{s, err}
		}(addr)
	}

	var errs []error
	for i := 0; i < len(cands); i++ {
		r := <-ch
		if r.err == nil {
			cancel() // stop the losing handshakes
			go drainSessions(ch, len(cands)-i-1)
			return r.s, nil
		}
		errs = append(errs, r.err)
	}
	return nil, fmt.Errorf("loomnet: direct: %w", errors.Join(errs...))
}

// drainSessions closes any sessions that finished their handshake after a winner
// was already chosen, so a cancelled-but-completed dial doesn't leak a conn.
func drainSessions(ch <-chan dialResult, remaining int) {
	for i := 0; i < remaining; i++ {
		if r := <-ch; r.s != nil {
			_ = r.s.Close()
		}
	}
}

// candidateAddrs expands a peer's endpoints into concrete UDP addresses: each
// LAN entry paired with the overlay UDP port (§4.1). LAN-direct only — no
// public/reflexive candidates.
func candidateAddrs(eps Endpoints) []*net.UDPAddr {
	var out []*net.UDPAddr
	seen := map[string]bool{}
	add := func(s string) {
		a, err := resolveCandidate(s, eps.UDPPort)
		if err != nil || a == nil {
			return
		}
		if key := a.String(); !seen[key] {
			seen[key] = true
			out = append(out, a)
		}
	}
	for _, lan := range eps.LAN {
		add(lan)
	}
	return out
}

// resolveCandidate turns "ip", "ip:port", or "[v6]:port" into a UDP address,
// defaulting a missing port to udpPort.
func resolveCandidate(s string, udpPort int) (*net.UDPAddr, error) {
	if s == "" {
		return nil, errors.New("empty candidate")
	}
	if _, _, err := net.SplitHostPort(s); err != nil {
		// No port present → attach the overlay UDP port.
		if udpPort <= 0 {
			return nil, fmt.Errorf("loomnet: candidate %q has no port and no udpPort", s)
		}
		s = net.JoinHostPort(s, strconv.Itoa(udpPort))
	}
	return net.ResolveUDPAddr("udp", s)
}

// --- connection cache + last-path tracking (§3.4) ---

func (n *Node) cachedConn(machineID string) Session {
	n.connsMu.Lock()
	defer n.connsMu.Unlock()
	return n.conns[machineID]
}

func (n *Node) storeConn(machineID string, s Session, path string) {
	n.connsMu.Lock()
	n.conns[machineID] = s
	n.connsMu.Unlock()

	n.pathsMu.Lock()
	n.paths[machineID] = path
	n.pathsMu.Unlock()

	// Evict from the cache when the underlying connection dies so the next dial
	// re-runs the ladder (§3.4); dialStream's retry on an OpenStream failure is
	// the backstop.
	if cs, ok := s.(*quicSession); ok {
		go n.watchConn(machineID, cs)
	}
}

func (n *Node) watchConn(machineID string, s *quicSession) {
	select {
	case <-s.conn.Context().Done():
	case <-n.ctx.Done():
	}
	n.evictConn(machineID, s)
}

// evictConn removes a session from the cache if it is still the current one.
func (n *Node) evictConn(machineID string, s Session) {
	n.connsMu.Lock()
	if n.conns[machineID] == s {
		delete(n.conns, machineID)
	}
	n.connsMu.Unlock()
}

// --- minimal per-key singleflight (avoids an extra module dependency) ---

type dialGroup struct {
	mu       sync.Mutex
	inflight map[string]*dialCall
}

type dialCall struct {
	done chan struct{}
	s    Session
	err  error
}

// do runs fn for key, ensuring concurrent callers for the same key share one
// execution and its result.
func (g *dialGroup) do(key string, fn func() (Session, error)) (Session, error) {
	g.mu.Lock()
	if g.inflight == nil {
		g.inflight = map[string]*dialCall{}
	}
	if c, ok := g.inflight[key]; ok {
		g.mu.Unlock()
		<-c.done
		return c.s, c.err
	}
	c := &dialCall{done: make(chan struct{})}
	g.inflight[key] = c
	g.mu.Unlock()

	c.s, c.err = fn()

	g.mu.Lock()
	delete(g.inflight, key)
	g.mu.Unlock()
	close(c.done)
	return c.s, c.err
}
