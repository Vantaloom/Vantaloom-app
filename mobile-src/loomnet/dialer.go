package loomnet

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sort"
	"strings"
	"sync"
	"time"
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

	// Budget 是本方式单次拨号的最长耗时（Dial 内部自己套的那个超时）。
	// 调用方据此推导「整条梯队跑满」的外层预算——外层 deadline 一旦短于
	// 梯队总和，就会在中途掐断并把逐方式富错误吞成裸 context deadline
	// exceeded（0.13.8 教训）。放进接口而不是让调用方手抄一个数字：新加
	// 方式不声明预算就编译不过，那个数字从此不会过期。
	Budget() time.Duration
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

// LadderWorstCase 返回整条梯队全部方式串行跑满的总预算。外层 deadline
// （如拓扑拨测）必须严格大于它，否则会在梯队中途掐断，把每种方式的具体
// 失败原因吞成一句 context deadline exceeded（0.13.8 教训）。
func (r *DialerRegistry) LadderWorstCase() time.Duration {
	var total time.Duration
	for _, d := range r.snapshot() {
		total += d.Budget()
	}
	return total
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

	// The active dialer's label, for the "why not chosen" copy. An adopted
	// inbound connection (对方拨入并被复用，0.14.3) is not a dialer — give it
	// its own label so the copy stays truthful.
	activeLabel := ""
	for _, d := range dialers {
		if d.Name() == activePath {
			activeLabel = d.Label()
		}
	}
	if activePath == pathInbound {
		activeLabel = "入站连接复用（对方拨入）"
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
	case pathPublic:
		return "正在使用：经对方公网地址的 QUIC 直连（mTLS 指纹双向校验），不经过任何服务器。"
	case pathReverse, pathInbound:
		return "正在使用：对方主动建立的直连连接（QUIC 双向复用，mTLS 指纹双向校验），不经过任何服务器。"
	case pathPunch:
		return "正在使用：经 NAT 打洞建立的 P2P 直连（QUIC+mTLS 指纹双向校验），不经过任何服务器。"
	case pathRelay:
		return "正在使用：经 Hub 中继转发连接（中继只见密文，A↔B 之间 mTLS 端到端加密）。"
	case pathRendezvous:
		return "正在使用：经 Hub 中继的临时连接会合电路（凭连接码里的会合键配对；中继只见密文，两端 mTLS 端到端加密）。"
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

func (d *directDialer) Name() string          { return pathDirect }
func (d *directDialer) Label() string         { return "局域网直连" }
func (d *directDialer) Priority() int         { return 10 }
func (d *directDialer) Budget() time.Duration { return directTimeout }

func (d *directDialer) Available(ctx context.Context, peerID string) bool {
	ok, _ := d.Explain(ctx, peerID)
	return ok
}

func (d *directDialer) Explain(_ context.Context, peerID string) (bool, string) {
	if prefs := d.n.ConnectionPrefs(); !prefs.DirectEnabled() {
		return false, "账号「连接偏好」已关闭直接连接（局域网/公网/反向直连）。"
	}
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

// publicDialer is the 公网直连 method (0.14 第二方式): dials the peer's
// EXPLICITLY CONFIGURED public "host:port". Same QUIC/mTLS transport as LAN
// direct — only the address source differs. Priority 20: the LAN path is
// always tried first (cheaper, lower RTT); the ladder falls through here when
// the peers aren't on the same LAN.
type publicDialer struct{ n *Node }

func (d *publicDialer) Name() string          { return pathPublic }
func (d *publicDialer) Label() string         { return "公网直连" }
func (d *publicDialer) Priority() int         { return 20 }
func (d *publicDialer) Budget() time.Duration { return publicTimeout }

func (d *publicDialer) Available(ctx context.Context, peerID string) bool {
	ok, _ := d.Explain(ctx, peerID)
	return ok
}

func (d *publicDialer) Explain(_ context.Context, peerID string) (bool, string) {
	if prefs := d.n.ConnectionPrefs(); !prefs.DirectEnabled() {
		return false, "账号「连接偏好」已关闭直接连接（局域网/公网/反向直连）。"
	}
	_, eps, ok := d.n.opts.Directory.PeerInfo(peerID)
	if !ok {
		return false, "尚未从 Hub 获取到对方的 overlay 连接信息。对方需在线并运行 0.13.7 及以上版本；本机每 60 秒自动刷新一次对方信息。"
	}
	if eps.Public == "" {
		return false, "对方未配置公网直连地址。拥有公网 IP 或已做端口转发的机器（如云服务器）可在对方的 设置→网络与设备→公网直连 开启（固定 UDP 端口 + 公网地址），任意网络的机器即可直连它。"
	}
	return true, fmt.Sprintf("对方通告了公网直连地址 %s，可从任意网络发起 QUIC/UDP 直连（mTLS 指纹校验）。", eps.Public)
}

func (d *publicDialer) Dial(ctx context.Context, peerID string) (Session, error) {
	fp, eps, ok := d.n.opts.Directory.PeerInfo(peerID)
	if !ok {
		return nil, fmt.Errorf("loomnet: no directory entry for %s", peerID)
	}
	return d.n.dialPublic(ctx, peerID, fp, eps)
}

// reverseDialer is the 反向公网直连 method（0.14.3 第三方式）：本机正向拨不通
// 对方、但本机自己配置了公网直连时，经 Hub 信令请对方反拨本机——QUIC 连接
// 双向复用，单侧可公网直连即可互通。信任模型不变：对方反拨仍按 Hub 目录里
// 本机的指纹做 mTLS 钉扎，Hub 只转发一条「请拨我」的信令，看不到任何流量。
// Priority 30：仅在 LAN 直连与正向公网直连都不可用/失败后才尝试。
type reverseDialer struct{ n *Node }

func (d *reverseDialer) Name() string          { return pathReverse }
func (d *reverseDialer) Label() string         { return "反向公网直连" }
func (d *reverseDialer) Priority() int         { return 30 }
func (d *reverseDialer) Budget() time.Duration { return reverseTimeout }

func (d *reverseDialer) Available(ctx context.Context, peerID string) bool {
	ok, _ := d.Explain(ctx, peerID)
	return ok
}

func (d *reverseDialer) Explain(_ context.Context, peerID string) (bool, string) {
	if prefs := d.n.ConnectionPrefs(); !prefs.DirectEnabled() {
		return false, "账号「连接偏好」已关闭直接连接（局域网/公网/反向直连）。"
	}
	if d.n.opts.PublicAdvertise == "" {
		// 方向感知（0.14.4）：反拨解决的是「本机可被公网直连、对方不可」的
		// 方向。对方已有公网时，本机→对方走正向「公网直连」即可——此时把
		// 「本机未配置公网」当缺陷提示纯属误导（三机拓扑显示不一致的根源）。
		if _, eps, ok := d.n.opts.Directory.PeerInfo(peerID); ok && eps.Public != "" {
			return false, fmt.Sprintf("无需反拨：对方已配置公网直连（%s），本机可经「公网直连」正向直达。反拨只服务反方向（对方拨不通本机、而本机可被公网直连）的场景。", eps.Public)
		}
		return false, "本机未配置公网直连，无法请对方反拨本机。任一方在 设置→网络与设备→公网直连 开启后，双方即可互通（另一方无需公网）。"
	}
	if d.n.reverseRequester() == nil {
		return false, "Hub 信令未接入（未登录 Hub 或运行时尚未完成接线），无法发送反拨请求。"
	}
	if _, _, ok := d.n.opts.Directory.PeerInfo(peerID); !ok {
		return false, "尚未从 Hub 获取到对方的信息，无法确认其在线状态。"
	}
	return true, fmt.Sprintf("本机已配置公网直连 %s；若对方在线（信令可达）且版本 ≥0.14.3，将请其反拨本机建立连接。", d.n.opts.PublicAdvertise)
}

func (d *reverseDialer) Dial(ctx context.Context, peerID string) (Session, error) {
	request := d.n.reverseRequester()
	if request == nil {
		return nil, errors.New("loomnet: reverse: Hub 信令未接入")
	}
	dctx, cancel := context.WithTimeout(ctx, reverseTimeout)
	defer cancel()

	// 先挂等待者再查缓存：对方的连接可能在梯队前两级期间已经拨入。
	waiter := d.n.registerReverseWaiter(peerID)
	defer d.n.unregisterReverseWaiter(peerID, waiter)
	if s := d.n.cachedConn(peerID); s != nil {
		return s, nil
	}

	if err := request(dctx, peerID); err != nil {
		return nil, fmt.Errorf("反拨信令未送达：%w", err)
	}

	select {
	case s := <-waiter.conn:
		return s, nil
	case reason := <-waiter.fail:
		// 对方（≥0.14.4）回报了反拨失败——带对方侧原样报错立即失败，
		// 不再干等超时。典型形态：对方出站 UDP 被防火墙/安全组阻断。
		return nil, fmt.Errorf("对方已收到反拨请求并尝试回拨本机 %s，但失败（对方侧报错原样）：%s", d.n.opts.PublicAdvertise, reason)
	case <-dctx.Done():
		return nil, fmt.Errorf("已请求对方反拨，但 %s 内未等到对方的连接，也未收到对方的失败回报（对方 ≥0.14.4 会即时回报失败原因；未回报=对方版本较旧或其回拨仍未完成）。对方需在线且能拨通本机公网地址 %s（确认本机 UDP 端口已在防火墙/安全组放行）", reverseTimeout, d.n.opts.PublicAdvertise)
	}
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

// relayDialer 是中继连接方式（TURN 式密文包转发）：直连/公网直连/反向均不可用
// 时的兜底。本机（A）连中继，发 OPEN 请求连对端（B），中继把 A、B 两条流字节
// 级对拼，A↔B 在拼出的流上跑 mTLS + yamux。中继看不到明文。Priority 40：在
// 所有直连方式之后尝试。
type relayDialer struct{ n *Node }

func (d *relayDialer) Name() string          { return pathRelay }
func (d *relayDialer) Label() string         { return "中继" }
func (d *relayDialer) Priority() int         { return 40 }
func (d *relayDialer) Budget() time.Duration { return relayTimeout }

func (d *relayDialer) Available(ctx context.Context, peerID string) bool {
	ok, _ := d.Explain(ctx, peerID)
	return ok
}

func (d *relayDialer) Explain(_ context.Context, peerID string) (bool, string) {
	if prefs := d.n.ConnectionPrefs(); !prefs.RelayEnabled() {
		return false, "账号「连接偏好」已关闭服务器中继。"
	}
	rc := d.n.RelayConfig()
	// 判据是「有任一坐标」而不是「有 QUIC 坐标」：官方 Hub 是 WSS-only 启用的，
	// 而 relayClient 本来就会在没有 QUIC 时直接走 WSS（见 RelayConfig.HasCoordinate）。
	if !rc.HasCoordinate() {
		return false, "中继未启用（未配置中继坐标）。中继是 TURN 式密文包转发器，用于直连/公网直连均不可用时的兜底连接方式。"
	}
	fp, _, ok := d.n.opts.Directory.PeerInfo(peerID)
	if !ok {
		return false, "尚未从 Hub 获取到对方的 overlay 连接信息。"
	}
	if fp == "" {
		return false, "对方未上报 overlay 指纹（可能未运行 overlay 或版本过旧）。"
	}
	return true, "将经 Hub 中继建立到对方的密文转发电路（中继只见密文，A↔B 之间 mTLS 端到端加密）。"
}

func (d *relayDialer) Dial(ctx context.Context, peerID string) (Session, error) {
	rc := d.n.RelayConfig()
	if !rc.HasCoordinate() {
		return nil, errors.New("loomnet: relay: 中继未配置")
	}
	fp, _, ok := d.n.opts.Directory.PeerInfo(peerID)
	if !ok {
		return nil, fmt.Errorf("loomnet: relay: 无 %s 的目录信息", peerID)
	}
	dctx, cancel := context.WithTimeout(ctx, relayTimeout)
	defer cancel()

	client := d.n.relayClient()
	pipe, err := client.openCircuit(dctx, peerID)
	if err != nil {
		return nil, err
	}
	sess, err := newRelaySession(dctx, pipe, fp, peerID, d.n.identity)
	if err != nil {
		return nil, err
	}
	// 发起方也要把入站流接进 http server：对端（被叫方）会经这条电路开流过来
	//（双向复用）。被叫方在 adoptRelayInbound 里做了同样的事——发起方缺了这个
	// AcceptStream 循环，对端开的流会堆积无人处理 → 反向请求永久超时
	//（2026-08-11 三机联调实锤：A 拨 B 成功后 B 反向拨 A 复用同一电路，B 的请求卡死）。
	ln := newRelayInboundListener(sess)
	go func() { _ = d.n.httpSrv.Serve(ln) }()
	return sess, nil
}
