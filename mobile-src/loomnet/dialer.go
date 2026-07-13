package loomnet

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sort"
	"strings"
	"sync"
)

// Dialer is the pluggable connection-method interface. Each concrete
// implementation is registered with a DialerRegistry; the registry runs them in
// priority order during the dial ladder and the first to succeed wins. The
// overlay currently ships exactly one method — LAN direct — and future methods
// are added one at a time once they meet the production bar
// (docs/network-connectivity-redesign.md §8).
type Dialer interface {
	// Name is a stable machine-readable identifier for this method (e.g.
	// "direct"). It appears in topology UIs.
	Name() string

	// Label is a human-readable Chinese label for the method (e.g. "局域网直连").
	Label() string

	// Priority controls the dial order: lower numbers are tried first.
	Priority() int

	// Available reports whether this method CAN reach peerID right now, without
	// actually dialing. It checks preconditions only and must be cheap (<100ms,
	// no network round trips). False means the ladder skips this method
	// entirely; a true return does not guarantee Dial will succeed.
	Available(ctx context.Context, peerID string) bool

	// Explain is Available plus a human-readable (Chinese) explanation for the
	// topology UI: when unavailable, WHY and what condition would enable it;
	// when available, any useful context (may be ""). Same cheapness contract
	// as Available.
	Explain(ctx context.Context, peerID string) (available bool, detail string)

	// Dial attempts to establish a Session to peerID via this method. The ctx
	// carries the per-method timeout budget. On success it returns a live
	// Session; on failure it returns an error and the ladder tries the next
	// method.
	Dial(ctx context.Context, peerID string) (Session, error)
}

// DialerRegistry is an ordered collection of Dialers. It is safe for concurrent
// use after initial registration (Register should be called before Start).
type DialerRegistry struct {
	mu      sync.Mutex
	dialers []Dialer
	sorted  bool
}

// NewDialerRegistry creates an empty registry.
func NewDialerRegistry() *DialerRegistry {
	return &DialerRegistry{}
}

// Register adds a Dialer to the registry. Call before the Node starts dialing.
func (r *DialerRegistry) Register(d Dialer) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.dialers = append(r.dialers, d)
	r.sorted = false
}

// Methods returns the list of registered dialers in priority order.
func (r *DialerRegistry) Methods() []Dialer {
	return r.snapshot()
}

func (r *DialerRegistry) snapshot() []Dialer {
	r.mu.Lock()
	if !r.sorted {
		sort.SliceStable(r.dialers, func(i, j int) bool {
			return r.dialers[i].Priority() < r.dialers[j].Priority()
		})
		r.sorted = true
	}
	out := make([]Dialer, len(r.dialers))
	copy(out, r.dialers)
	r.mu.Unlock()
	return out
}

// DialLadder runs the registered dialers in priority order. The first to
// succeed returns its Session and method name. If all fail, a joined error
// carrying every method's concrete failure reason is returned — the caller
// surfaces it verbatim (no silent fallback beyond the registered methods).
func (r *DialerRegistry) DialLadder(ctx context.Context, peerID string) (Session, string, error) {
	dialers := r.snapshot()

	if len(dialers) == 0 {
		return nil, "", errors.New("loomnet: no dialers registered")
	}

	var errs []error
	for _, d := range dialers {
		if ok, why := d.Explain(ctx, peerID); !ok {
			errs = append(errs, fmt.Errorf("%s(%s): %s", d.Label(), d.Name(), why))
			continue
		}
		s, err := d.Dial(ctx, peerID)
		if err == nil {
			return s, d.Name(), nil
		}
		errs = append(errs, fmt.Errorf("%s(%s): %w", d.Label(), d.Name(), err))
	}
	return nil, "", fmt.Errorf("loomnet: 无法连接 %s: %w", peerID, errors.Join(errs...))
}

// PeerReachability returns a snapshot of which registered methods are available
// for peerID and which one is currently active (has a LIVE cached session; a
// dead session must not be reported active — pass the live path, not the last
// remembered one). This powers the topology UI's per-method badges, and each
// entry carries a human-readable Detail: active → what this path is; available
// but not chosen → why; unavailable → what's missing and what would enable it.
func (r *DialerRegistry) PeerReachability(ctx context.Context, peerID string, activePath string) []MethodStatus {
	dialers := r.snapshot()

	// The active dialer's label, for the "why not chosen" copy.
	activeLabel := ""
	for _, d := range dialers {
		if d.Name() == activePath {
			activeLabel = d.Label()
		}
	}

	out := make([]MethodStatus, len(dialers))
	for i, d := range dialers {
		available, why := d.Explain(ctx, peerID)
		ms := MethodStatus{
			Name:      d.Name(),
			Label:     d.Label(),
			Available: available,
			Active:    d.Name() == activePath,
		}
		switch {
		case ms.Active:
			ms.Detail = activePathDetail(d.Name())
			if why != "" {
				ms.Detail += " " + why
			}
		case !available:
			ms.Detail = why
		case activePath == "":
			ms.Detail = "条件已具备，当前无活跃连接。有访问需求时会自动建连。 " + why
		default:
			ms.Detail = fmt.Sprintf("条件已具备，但当前连接使用的是「%s」。 ", activeLabel) + why
		}
		out[i] = ms
	}
	return out
}

// activePathDetail is the "why blue" copy per path kind.
func activePathDetail(name string) string {
	switch name {
	case pathDirect:
		return "正在使用：局域网内 QUIC 直连（mTLS 指纹双向校验），不经过任何服务器。"
	default:
		return "正在使用此连接方式。"
	}
}

// MethodStatus is a per-method availability snapshot for a single peer.
type MethodStatus struct {
	Name      string `json:"name"`
	Label     string `json:"label"`
	Available bool   `json:"available"`
	Active    bool   `json:"active"`
	// Detail is the human-readable explanation shown on hover/click: why the
	// method is unavailable (and what would enable it), why an available method
	// wasn't chosen, or what the active method is doing.
	Detail string `json:"detail,omitempty"`
}

// ── Concrete dialer implementations ──────────────────────────────────────────

// directDialer is the LAN-direct method: parallel QUIC handshakes against the
// peer's Hub-reported LAN addresses, mTLS fingerprint pinned.
type directDialer struct{ n *Node }

func (d *directDialer) Name() string  { return pathDirect }
func (d *directDialer) Label() string { return "局域网直连" }
func (d *directDialer) Priority() int { return 10 }

func (d *directDialer) Available(ctx context.Context, peerID string) bool {
	ok, _ := d.Explain(ctx, peerID)
	return ok
}

func (d *directDialer) Explain(_ context.Context, peerID string) (bool, string) {
	_, eps, ok := d.n.opts.Directory.PeerInfo(peerID)
	if !ok {
		return false, "尚未从 Hub 获取到对方的 overlay 连接信息。对方需在线并运行 0.13.7 及以上版本（0.13.6 存在应用内重新登录后停止上报连接信息的缺陷，升级后自动恢复）；本机每 60 秒自动刷新一次对方信息。"
	}
	cands := candidateAddrs(eps)
	if len(cands) == 0 {
		return false, "对方未向 Hub 上报任何局域网地址（对方 overlay 可能未启动，或没有可用网卡）。"
	}
	lanList := strings.Join(eps.LAN, "、")
	if matched := firstSameSubnet(localLANNets(), eps.LAN); matched != "" {
		return true, fmt.Sprintf("对方通告了 %d 个局域网地址（%s），其中 %s 与本机同网段，可直连（QUIC/UDP 端口 %d）。", len(eps.LAN), lanList, matched, eps.UDPPort)
	}
	return true, fmt.Sprintf("对方通告了 %d 个局域网地址（%s），但与本机任一网段均不重叠——两台机器大概率不在同一局域网，直连很可能失败。当前版本仅支持局域网直连。", len(eps.LAN), lanList)
}

func (d *directDialer) Dial(ctx context.Context, peerID string) (Session, error) {
	fp, eps, ok := d.n.opts.Directory.PeerInfo(peerID)
	if !ok {
		return nil, fmt.Errorf("loomnet: no directory entry for %s", peerID)
	}
	return d.n.dialDirect(ctx, peerID, fp, eps)
}

// localLANNets enumerates this host's non-loopback, non-link-local unicast
// subnets, used to judge whether a peer's LAN address is likely reachable.
func localLANNets() []*net.IPNet {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil
	}
	var out []*net.IPNet
	for _, a := range addrs {
		ipnet, ok := a.(*net.IPNet)
		if !ok || ipnet.IP.IsLoopback() || ipnet.IP.IsLinkLocalUnicast() {
			continue
		}
		out = append(out, ipnet)
	}
	return out
}

// firstSameSubnet returns the first peer LAN address that falls inside one of
// the local subnets, or "" when none overlaps.
func firstSameSubnet(nets []*net.IPNet, peerLAN []string) string {
	for _, cand := range peerLAN {
		host := cand
		if h, _, err := net.SplitHostPort(cand); err == nil {
			host = h
		}
		ip := net.ParseIP(host)
		if ip == nil {
			continue
		}
		for _, n := range nets {
			if n.Contains(ip) {
				return cand
			}
		}
	}
	return ""
}
