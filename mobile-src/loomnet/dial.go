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
// so these are tighter than the legacy easytier ladder; the public tier gets a
// little more headroom for real internet RTTs. The reverse tier covers a full
// signal round trip PLUS the peer's own dial-back ladder (direct 3s + public
// 4s), so it is the widest.
const (
	directTimeout  = 3 * time.Second
	publicTimeout  = 4 * time.Second
	reverseTimeout = 9 * time.Second
)

// Last-path labels reported by LastPath for the topology UI (§4.6, §7.4).
// pathInbound marks a connection the PEER established that we adopted for
// outbound reuse (反向互通/入站复用，0.14.3)。
const (
	pathDirect  = "direct"
	pathPublic  = "public"
	pathReverse = "reverse"
	pathInbound = "inbound"
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

// raceCandidates races QUIC handshakes against every candidate in parallel;
// the first success wins and the losers are cancelled (§4.2). Shared by the
// LAN-direct and 公网直连 dialers — the transport/mTLS layer is identical,
// only the address source differs.
func (n *Node) raceCandidates(
	ctx context.Context,
	machineID, fp string,
	cands []*net.UDPAddr,
	budget time.Duration,
) (*quicSession, error) {
	dctx, cancel := context.WithTimeout(ctx, budget)
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
	return nil, errors.Join(errs...)
}

// dialDirect races QUIC handshakes against every LAN candidate in parallel.
func (n *Node) dialDirect(ctx context.Context, machineID, fp string, eps Endpoints) (*quicSession, error) {
	cands := candidateAddrs(eps)
	if len(cands) == 0 {
		return nil, errors.New("loomnet: direct: no candidate addresses")
	}
	s, err := n.raceCandidates(ctx, machineID, fp, cands, directTimeout)
	if err == nil {
		return s, nil
	}
	// Every candidate failed. Attach the most likely CAUSE (judged by subnet
	// overlap) so the UI shows an actionable reason, not just raw per-address
	// handshake errors: same-subnet timeouts are almost always AP/client
	// isolation or a peer-side firewall, never a code path worth retrying.
	hint := "对方地址与本机任一网段均不重叠——两台机器不在同一局域网"
	if firstSameSubnet(localLANNets(), eps.LAN) != "" {
		hint = "与对方已处于同一网段但仍未拨通——常见原因：路由器开启了客户端/AP 隔离（校园网、公司网常见，可用手机热点验证），或对方系统防火墙拦截了 UDP 入站（Windows 需放行 vantaloom-api；macOS 需在 系统设置→隐私与安全性→本地网络 中允许 Vantaloom）"
	}
	return nil, fmt.Errorf("loomnet: direct: %s。逐地址结果：%w", hint, err)
}

// dialPublic dials the peer's explicitly configured 公网直连 address（0.14
// 第二方式）: a single "host:port" candidate, mTLS fingerprint pinned exactly
// like LAN direct.
func (n *Node) dialPublic(ctx context.Context, machineID, fp string, eps Endpoints) (*quicSession, error) {
	if eps.Public == "" {
		return nil, errors.New("loomnet: public: 对方未配置公网直连地址")
	}
	addr, err := resolveCandidate(eps.Public, 0)
	if err != nil {
		return nil, fmt.Errorf("loomnet: public: 对方通告的公网地址 %q 无法解析: %w", eps.Public, err)
	}
	s, rerr := n.raceCandidates(ctx, machineID, fp, []*net.UDPAddr{addr}, publicTimeout)
	if rerr == nil {
		return s, nil
	}
	return nil, fmt.Errorf(
		"loomnet: public: 公网直连 %s 未拨通——请确认对方公网地址/端口正确、该 UDP 端口已在对方防火墙与云安全组放行、端口转发（如有）指向对方运行时。逐地址结果：%w",
		eps.Public, rerr,
	)
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

// ValidatePublicAdvertise checks a 公网直连 address: must be "host:port" with
// a valid port (the host may be an IP or a domain). Shared by loomnet.New and
// the settings endpoint so a bad config is rejected at BOTH doors.
func ValidatePublicAdvertise(s string) error {
	host, portStr, err := net.SplitHostPort(s)
	if err != nil {
		return fmt.Errorf("loomnet: 公网直连地址必须是 host:port（如 1.2.3.4:51820 或 example.com:51820）: %w", err)
	}
	if host == "" {
		return errors.New("loomnet: 公网直连地址缺少主机名/IP")
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port < 1 || port > 65535 {
		return fmt.Errorf("loomnet: 公网直连端口 %q 无效（1–65535）", portStr)
	}
	return nil
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

	// 双向复用（0.14.3）：出站连接上对方开的流同样要进本机 http server（QUIC
	// 连接天然双向；没有这一步，对方经反向互通复用我们的出站连接发请求会被
	// 无人 Accept 而挂死）。入站连接由 listener 自己 demux，不重复。
	if path != pathInbound {
		if cs, ok := s.(*quicSession); ok && n.listener != nil {
			go n.listener.demux(cs.conn, machineID)
		}
	}

	// 反向等待者：本机 reverse 拨号器正等着这台机器的连接（我们发了信令请它
	// 拨入/我们拨通了它）——任何一条到该机器的活连接都满足需求。
	n.notifyReverseWaiters(machineID, s)
}

// adoptInbound registers a VERIFIED inbound connection as a reusable outbound
// session to its peer (反向互通/入站复用，0.14.3): QUIC connections are fully
// bidirectional, so once the peer dialed us there is no reason to dial back —
// requests toward that peer ride the same connection. The listener has already
// demuxed the connection's inbound streams into the http server; this only
// registers the OUTBOUND direction. Replaces any cached session (the inbound
// one is live RIGHT NOW; a possibly-zombie predecessor dies via idle timeout).
func (n *Node) adoptInbound(s *quicSession) {
	n.storeConn(s.RemoteMachineID(), s, pathInbound)
}

// --- reverse-connect waiters (0.14.3 反向互通) -------------------------------

// registerReverseWaiter arms a one-shot channel that notifyReverseWaiters
// (called from storeConn) fulfills when ANY live session to machineID lands.
func (n *Node) registerReverseWaiter(machineID string) chan Session {
	ch := make(chan Session, 1)
	n.reverseMu.Lock()
	n.reverseWaiters[machineID] = append(n.reverseWaiters[machineID], ch)
	n.reverseMu.Unlock()
	return ch
}

func (n *Node) unregisterReverseWaiter(machineID string, ch chan Session) {
	n.reverseMu.Lock()
	waiters := n.reverseWaiters[machineID]
	for i, w := range waiters {
		if w == ch {
			n.reverseWaiters[machineID] = append(waiters[:i], waiters[i+1:]...)
			break
		}
	}
	if len(n.reverseWaiters[machineID]) == 0 {
		delete(n.reverseWaiters, machineID)
	}
	n.reverseMu.Unlock()
}

func (n *Node) notifyReverseWaiters(machineID string, s Session) {
	n.reverseMu.Lock()
	waiters := n.reverseWaiters[machineID]
	delete(n.reverseWaiters, machineID)
	n.reverseMu.Unlock()
	for _, ch := range waiters {
		select {
		case ch <- s:
		default:
		}
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
