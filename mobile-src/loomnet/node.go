package loomnet

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// headerLoomFrom carries the mTLS-verified caller machineID into the local mux.
// It occupies the semantic slot of today's spoofable X-Relay-From but its value
// is now cryptographically trusted (§2.4).
const headerLoomFrom = "X-Loom-From"

// headerLoomFp carries the mTLS-verified SPKI fingerprint of a trusted OR
// provisional inbound peer (stamped by serveHandler; any client-supplied value
// is overwritten). tempconn 兑换/审计据此认对端身份。
const headerLoomFp = "X-Loom-Peer-Fp"

// headerLoomProvisionalFrom carries an UNTRUSTED (provisional, tempconn 配对期)
// inbound peer's cert CN. It is stamped INSTEAD of headerLoomFrom so no
// downstream handler mistakes a not-yet-trusted peer for a trusted caller; such
// a connection is also confined to Options.ProvisionalPath.
const headerLoomProvisionalFrom = "X-Loom-Provisional-From"

// Directory supplies per-peer overlay metadata and the account trust set,
// sourced live from the Hub machine list (§4.1, §2.2). The Hub is the ONLY
// recognized source of peer information — there is no on-disk peer cache.
type Directory interface {
	// PeerInfo returns machineID's pinned SPKI fingerprint and dial endpoints,
	// or ok=false if the peer is unknown.
	PeerInfo(machineID string) (fingerprint string, endpoints Endpoints, ok bool)
	// AccountFingerprints maps every same-account machineID to its SPKI
	// fingerprint; the listener verifies inbound peers against it (§2.3). It is
	// called fresh on every inbound handshake.
	AccountFingerprints() map[string]string
}

// Endpoints are a machine's overlay dial candidates (§4.1). LAN entries are bare
// IPs (paired with UDPPort) or "ip:port"; UDPPort is the overlay UDP socket
// port. Public is the machine's EXPLICITLY CONFIGURED 公网直连 address
// ("host:port"; 0.14 第二连接方式) — never auto-derived: no reflexive
// discovery, no hole punching, no relay. Only machines with a real public
// address (cloud box / port-forward) set it, in 设置→网络与设备→公网直连.
// Further methods are added one at a time via the DialerRegistry — see
// docs/network-connectivity-redesign.md §8.
type Endpoints struct {
	LAN     []string `json:"lan"`
	UDPPort int      `json:"udpPort"`
	Public  string   `json:"public,omitempty"`
}

// Options configures a Node.
type Options struct {
	DataDir      string       // identity key under <DataDir>/loomnet
	MachineID    string       // this machine's stable overlay identity (cert CN)
	Directory    Directory    // peer metadata + account trust set (required)
	LocalHandler http.Handler // inbound overlay requests are served by this mux
	// UDPPort fixes the overlay socket's bind port (0 = ephemeral). 公网直连
	// requires a fixed port so port-forward/安全组 rules stay valid.
	UDPPort int
	// PublicAdvertise is this machine's 公网直连 address ("host:port") reported
	// to peers via heartbeat; "" = 公网直连 off. Validated by New.
	PublicAdvertise string
	// ProvisionalGate reports whether an unknown inbound peer may be accepted as
	// a provisional (redeem-only) connection — true while a tempconn pairing
	// window is open. nil = never (the pre-tempconn behavior: unknown peers are
	// rejected at the mTLS handshake).
	ProvisionalGate func() bool
	// ProvisionalPath is the ONLY request path a provisional (untrusted) inbound
	// peer may reach; every other path returns 403. Empty = provisional peers
	// can reach nothing (they still complete the handshake but are dead-ended).
	// tempconn sets it to the one-time redeem endpoint.
	ProvisionalPath string
	// RelayConfig 是中继服务坐标（由 server 层从 Hub /api/overlay/config 拉取
	// 后注入）。空值 = 中继未启用，relayDialer 不可用。
	//
	// JWT 为空时中继仍然可用于**匿名会合**（tempconn）：坐标本身是公开信息，
	// 未登录 Hub 的机器经公开端点也能取到。此时账号内的 relayDialer 不注册，
	// 只有 rendezvousDialer 生效。
	RelayConfig *RelayConfig
	// RendezvousKeyFor 返回用于经中继会合联系 peerID 的会合键（tempconn 的持久
	// 密钥，双方在兑换连接码时共享）。返回 "" = 该对端没有会合通道。nil = 会合
	// 拨号方式整体不可用。由 server 层接 tempconn 注入。
	RendezvousKeyFor func(peerID string) string
}

// RelayConfig 是中继服务的连接坐标。中继是 TURN 式密文包转发器，用于直连/
// 公网直连均不可用时的兜底连接方式。StunAddrs 是 STUN 观测点（含中继端口和
// 第二端口），用于客户端 NAT 类型检测——两次 reflexive port 相同 = cone NAT
// （可打洞），不同 = symmetric NAT（降级中继）。
type RelayConfig struct {
	QuicAddr  string   // 中继 QUIC/UDP 公网地址，如 "vanta.timefiles.online:7343"
	WSSUrl    string   // WSS 降级路径（QUIC/UDP 不可达时走 Cloudflare Tunnel 回退）
	JWT       string   // Hub JWT，用于中继 HELLO/OPEN 认证
	RelaySPKI string   // 中继自签证书公钥的 SPKI 指纹（hex sha256，Hub 下发）；空 = 不钉扎中继 TLS（旧 Hub，向后兼容）
	StunAddrs []string // STUN 观测点公网坐标，用于 NAT 类型检测
	// ConnectionPrefs 是该账号的连接偏好（nil = 未设置，默认全开）。控制
	// 本机注册哪些拨号方式：Direct=false 禁用局域网/公网/反向直连，P2P=false
	// 禁用 NAT 打洞，Relay=false 禁用中继。
	ConnectionPrefs *ConnectionPrefs
}

// ConnectionPrefs 是账号级连接偏好，控制 DialerRegistry 注册哪些拨号方式。
// 三个字段用 *bool 区分「显式设置」与「未设置」——未设置按默认值处理。
type ConnectionPrefs struct {
	Direct *bool
	P2P    *bool
	Relay  *bool
	// SyncModels 控制「模型渠道（含密钥）自动同步到本账号所有主机」。
	// nil/未设置默认 true（保持服务端自动领养行为）。false 关闭自动领养，
	// 各机器模型渠道各自维护。
	SyncModels *bool
}

// DirectEnabled 报告「直接连接」（局域网/公网/反向直连）是否启用。nil/未设置
// 默认启用。
func (p *ConnectionPrefs) DirectEnabled() bool {
	return p == nil || p.Direct == nil || *p.Direct
}

// P2PEnabled 报告「P2P 穿透」是否启用。nil/未设置默认启用。
func (p *ConnectionPrefs) P2PEnabled() bool {
	return p == nil || p.P2P == nil || *p.P2P
}

// RelayEnabled 报告「服务器中继」是否启用。未设置（nil）时返回 true——不
// 拦截中继，让 relayDialer 自行按 QuicAddr 是否配置判断可用性（保持未设置
// 偏好的原有行为）。显式关闭（Relay=false）才禁用中继。
func (p *ConnectionPrefs) RelayEnabled() bool {
	return p == nil || p.Relay == nil || *p.Relay
}

// SyncModelsEnabled 报告「模型渠道自动同步」是否启用。nil/未设置默认 true
// （保持方案 A 的服务端自动领养行为）。
func (p *ConnectionPrefs) SyncModelsEnabled() bool {
	return p == nil || p.SyncModels == nil || *p.SyncModels
}

// HasCoordinate 报告这份配置里有没有**任何**一个可用的中继坐标。
//
// 两个坐标是同一件事的两条路，relayClient.connectWithFallback 一直是「QuicAddr
// 优先，没配/连不上就走 WSSUrl」——所以「中继可不可用」的判据必须是两者取或，
// 不能只看 QuicAddr。
//
// 只看 QuicAddr 曾让**账号内中继在生产上从未存在过**：官方 Hub 是 WSS-only 启用
// 的（服务器 ~/hub/.loom-relay-wss-url，走 Cloudflare Tunnel 的 443，不需要放行
// 任何 UDP），/api/overlay/rendezvous 实测只回 wssUrl、没有 quicAddr。于是
// rendezvousDialer（判据本来就是两者取或）照常注册、临时连接可用，而 relayDialer
// 与 ensureServeRelay 全被 QuicAddr=="" 挡死 ⇒ 跨网段的两台机器梯队走完只剩四条
// 失败原因，连接报告里连「中继」这一档都不出现。线上实锤：2026-08-31 的用户诊断
// 包里，直连/公网直连/反向/会合四条全灭 → 502，而中继本该正是这一格的兜底。
func (rc *RelayConfig) HasCoordinate() bool {
	return rc != nil && (rc.QuicAddr != "" || rc.WSSUrl != "")
}

// Fingerprint 返回中继配置的确定性指纹（坐标 + JWT + SPKI + STUN 观测点），用于
// 检测配置变化（Hub 换中继地址、JWT 轮换、中继重启换自签证书等）——relayClient
// 懒单例据此 close 旧 client 并重建。nil → ""。JWT 只进 sha256 哈希、绝不进日志
// （哈希不可逆）。RelaySPKI 进指纹是 S2 钉扎的正确性必需：Hub 重启会重新生成
// 一次性自签证书（SPKI 变化），若不进指纹，客户端会沿用旧 SPKI 钉扎新证书而
// 永久拒绝握手。
func (rc *RelayConfig) Fingerprint() string {
	if rc == nil {
		return ""
	}
	h := sha256.New()
	io.WriteString(h, rc.QuicAddr)
	h.Write([]byte{0})
	io.WriteString(h, rc.WSSUrl)
	h.Write([]byte{0})
	io.WriteString(h, rc.JWT)
	h.Write([]byte{0})
	io.WriteString(h, rc.RelaySPKI)
	h.Write([]byte{0})
	for _, s := range rc.StunAddrs {
		io.WriteString(h, s)
		h.Write([]byte{0})
	}
	// 连接偏好进指纹：偏好变化必须触发 SetRelayConfig 重新注入，否则
	// PollRelayConfig 的指纹判断认为「没变」→ dialer 的偏好拦截永远不生效。
	if p := rc.ConnectionPrefs; p != nil {
		h.Write([]byte("prefs"))
		h.Write([]byte{0})
		io.WriteString(h, p.Fingerprint())
	}
	return hex.EncodeToString(h.Sum(nil))
}

// Fingerprint 返回连接偏好的确定性指纹（四个三态开关的逐位编码）。
// **四个开关一个都不能漏**：轮询侧靠指纹判断「偏好变没变」，漏掉哪个，
// 用户在 Hub 上改那个开关就永远不会下发到客户端——SyncModels 曾被漏掉，
// 导致「模型渠道（含密钥）自动同步」关掉后后端仍在同步（假失效的隐私开关）。
// nil → ""。
func (p *ConnectionPrefs) Fingerprint() string {
	if p == nil {
		return ""
	}
	var b strings.Builder
	for _, v := range []*bool{p.Direct, p.P2P, p.Relay, p.SyncModels} {
		switch {
		case v == nil:
			b.WriteByte('x')
		case *v:
			b.WriteByte('1')
		default:
			b.WriteByte('0')
		}
		b.WriteByte(0)
	}
	return b.String()
}

// Node is the process-local overlay endpoint: one shared QUIC/UDP socket that
// both dials peers (Transport) and serves inbound peer requests (Listener),
// every connection authenticated by mutual TLS fingerprint pinning.
type Node struct {
	started  atomic.Bool
	opts     Options
	identity *Identity

	ctx    context.Context
	cancel context.CancelFunc

	tr       *transport
	listener *quicListener
	httpSrv  *http.Server
	rt       *http.Transport

	connsMu sync.Mutex
	conns   map[string]Session

	// inboundConns tracks every LIVE verified inbound session (adopted or not)
	// so ClosePeer can tear down a peer's active connection — needed by tempconn
	// 取消信任/立刻接管, since a provisional (unadopted) controller connection
	// is not in conns. Deregistered when the connection dies.
	inboundMu    sync.Mutex
	inboundConns map[*quicSession]struct{}

	pathsMu sync.Mutex
	paths   map[string]string

	dials dialGroup

	// Registry holds all pluggable dial methods in priority order.
	Registry *DialerRegistry

	// 反向互通（0.14.3）：reverseRequest 是「经 Hub 信令请 peer 反拨本机」的
	// 注入口（server 层接 hubconn；nil = 信令未接入，reverse 拨号器不可用）。
	// reverseWaiters 是按机器 id 排队的一次性等待者——storeConn 落任何一条到
	// 该机器的活连接、或对方回报反拨失败（0.14.4 富错误闭环），都会终结它们。
	reverseRequest func(ctx context.Context, peerID string) error
	reverseMu      sync.Mutex
	reverseWaiters map[string][]reverseWaiter

	// 中继客户端（懒初始化，可重建）：与当前中继配置匹配的 client 只建一次，
	// 多条电路复用同一 QUIC 连接的不同流；配置变化（坐标/JWT/STUN 变更）时
	// close 旧 client 并重建。nil = 中继未配置或尚未使用。
	relay   *relayClient
	relayMu sync.Mutex
	// connPrefs 是账号连接偏好的独立注入槽（relayMu 保护）——与中继坐标解耦，
	// Hub 未配置中继时偏好照样生效。nil = 未注入（回落 RelayConfig 里的旧通道，
	// 再回落全开默认）。
	connPrefs *ConnectionPrefs

	// relayCfgFp 是已创建 relayClient 对应的中继配置指纹（relayMu 保护）。
	// relayClient() 据此检测配置变化——Hub 换中继地址 / JWT 轮换后不再沿用
	// 旧坐标无限 HELLO-ERR（M8）。
	relayCfgFp string
	// relayPunchRegistered / relayDialerRegistered 分别标记 P2P 打洞与中继
	// dialer 是否已注册（各自幂等——DialerRegistry.Register 是 append，重复
	// 注册会重复尝试；拆成两个标记让「先只有 STUN 观测点、后补中继地址」的
	// 配置演进也能注册上 relay dialer）。relayServing 标记 serveRelay 循环
	// 是否已启动（只启动一次）。
	relayPunchRegistered  atomic.Bool
	relayDialerRegistered atomic.Bool
	relayServing          atomic.Bool

	// P2P 打洞（阶段2）：loomOfferSender 是「经 Hub 信令向 peer 发 loom-offer
	// 并等待 loom-answer」的注入口（server 层接 hubconn；nil = 信令未接入，
	// punchDialer 不可用）。loomOfferHandler 是「收到 peer 的 loom-offer 后
	// 返回本机 reflexive addr 作为 answer」的 B 侧处理（nil = 不响应打洞请求）。
	loomOfferSender  func(ctx context.Context, peerID, myReflexiveAddr string) (peerReflexiveAddr string, err error)
	loomOfferHandler func(fromMachineID, offerAddr string) (answerAddr string, err error)
	punchMu          sync.Mutex
	// punchCache 记录每个 peer 上次打洞的结果（成功/失败），失败的 peer 下次
	// 直接跳过打洞走中继，避免 symmetric NAT 用户每次都等 5s 超时。
	punchCache map[string]punchCacheEntry

	// 匿名会合（tempconn，0.15.37）：未登录 Hub 的两台机器经中继会合。
	// rzKey 是本机自己的会合键（B 角色，用于在中继登记等待呼入）；rzServer 是
	// 对应的常驻登记客户端；rzDialers 按**对端**会合键缓存拨号客户端（A 角色，
	// 电路是其上的一条流，必须与会话同寿）。rzCoordFp 是这批客户端对应的中继
	// 坐标指纹，坐标一变全部作废重建。
	rzMu         sync.Mutex
	rzKey        string
	rzServer     *relayClient
	rzServerFp   string
	rzDialers    map[string]*relayClient
	rzCoordFp    string
	rzServing    atomic.Bool
	rzRegistered atomic.Bool
}

// punchCacheEntry 是打洞结果缓存，避免对 symmetric NAT 反复尝试打洞。
type punchCacheEntry struct {
	ok         bool
	failReason string
	cachedAt   time.Time
}

// New builds a Node and loads/creates its overlay identity. Start must be called
// before the Node dials or serves.
func New(opts Options) (*Node, error) {
	if opts.MachineID == "" {
		return nil, fmt.Errorf("loomnet: Options.MachineID is required")
	}
	if opts.Directory == nil {
		return nil, fmt.Errorf("loomnet: Options.Directory is required")
	}
	if opts.LocalHandler == nil {
		opts.LocalHandler = http.NotFoundHandler()
	}
	if opts.PublicAdvertise != "" {
		if err := ValidatePublicAdvertise(opts.PublicAdvertise); err != nil {
			return nil, err
		}
	}
	if opts.UDPPort < 0 || opts.UDPPort > 65535 {
		return nil, fmt.Errorf("loomnet: UDPPort %d 超出范围（0–65535）", opts.UDPPort)
	}
	id, err := LoadOrCreateIdentity(opts.DataDir, opts.MachineID)
	if err != nil {
		return nil, err
	}
	n := &Node{
		opts:           opts,
		identity:       id,
		conns:          map[string]Session{},
		inboundConns:   map[*quicSession]struct{}{},
		paths:          map[string]string{},
		Registry:       NewDialerRegistry(),
		reverseWaiters: map[string][]reverseWaiter{},
		punchCache:     map[string]punchCacheEntry{},
	}
	n.rt = &http.Transport{
		DialContext:           n.dialStream,
		MaxIdleConns:          64,
		IdleConnTimeout:       idleTimeout,
		ExpectContinueTimeout: time.Second,
	}
	return n, nil
}

// Start binds the shared UDP socket and starts the inbound listener (served by
// LocalHandler). The ctx bounds the node's lifetime. Start must be called
// exactly once.
func (n *Node) Start(ctx context.Context) error {
	if !n.started.CompareAndSwap(false, true) {
		return errors.New("loomnet: node already started")
	}
	n.ctx, n.cancel = context.WithCancel(ctx)

	tr, err := newTransport(n.ctx, n.identity, n.opts.Directory, n.opts.UDPPort, n.opts.ProvisionalGate)
	if err != nil {
		n.cancel()
		return err
	}
	n.tr = tr

	// 入站连接注册回调（0.14.3 反向互通）：mTLS 验证过的入站连接先登记进
	// inboundConns（供 ClosePeer 断开），再——仅当对端已受信时——登记为到该
	// 对端的可复用出站会话。provisional（tempconn 配对期未受信）连接绝不被
	// adopt：否则其自报 CN 可污染本机出站缓存，把发往同 CN 机器的流量导给它。
	ln, err := tr.listen(func(s *quicSession) {
		n.trackInbound(s)
		if n.inboundTrusted(s.RemoteMachineID(), s.fingerprint) {
			n.adoptInbound(s)
		}
	})
	if err != nil {
		n.cancel()
		tr.close()
		return err
	}
	n.listener = ln

	n.httpSrv = &http.Server{
		Handler:     n.serveHandler(),
		ConnContext: n.connContext,
	}
	go func() { _ = n.httpSrv.Serve(ln) }()

	// Register the built-in dial methods in priority order: LAN direct (10)，
	// 公网直连 (20; dials a peer's explicitly configured public address)，
	// 反向公网直连 (30; 0.14.3——本机可公网直连时经 Hub 信令请对方反拨，
	// 单侧可达即可互通)。Further methods are added one at a time via
	// n.Registry.Register() once they meet the production bar — see
	// docs/network-connectivity-redesign.md §8.
	n.Registry.Register(&directDialer{n: n})
	n.Registry.Register(&publicDialer{n: n})
	n.Registry.Register(&reverseDialer{n: n})

	// P2P 打洞 + 中继（阶段2）+ 临时连接会合（0.15.37）：按 RelayConfig 注册
	// （Start 时若有配置则立即注册；未配置则等 server 层拉到坐标后经
	// SetRelayConfig 动态注入）。
	n.applyRelayConfig()

	return nil
}

// SetReverseRequester injects the「请对方反拨」signal sender (server wiring →
// hubconn). fn must send the reverse-connect signal to peerID and return nil
// once it is on its way (or an immediate, human-readable error: Hub 断连 /
// 对方不在线 nack). Safe to call before Start; nil disables the reverse dialer.
func (n *Node) SetReverseRequester(fn func(ctx context.Context, peerID string) error) {
	n.reverseMu.Lock()
	n.reverseRequest = fn
	n.reverseMu.Unlock()
}

// SetRelayConfig 动态设置中继/打洞配置（server 层在 Hub 连接后从
// /api/overlay/config 拉取注入；启动时若有配置也可在 Options 传入）。可以
// 在 New 之后、Start 之后调用；重复调用会更新 opts.RelayConfig（relayClient
// 懒重建读取新值），punch/relay dialer 只注册一次、serveRelay 只启动一次
// （DialerRegistry.Register 是 append，重复注册会重复尝试）。
// 并发安全：opts.RelayConfig 的写与 relayClient()/RelayConfig() 的读都持
// relayMu；dialer 必须通过 RelayConfig() 取得快照，不能无锁直读 opts。
func (n *Node) SetRelayConfig(rc *RelayConfig) {
	n.relayMu.Lock()
	n.opts.RelayConfig = rc
	// 中继配置携带的偏好（旧通道）同步进独立字段，否则 SetConnectionPrefs
	// 注入过一次之后，relay 带来的新偏好会被那个字段永久遮蔽。
	if rc != nil && rc.ConnectionPrefs != nil {
		n.connPrefs = rc.ConnectionPrefs
	}
	n.relayMu.Unlock()
	n.applyRelayConfig()
}

// applyRelayConfig 根据当前 opts.RelayConfig 注册 P2P 打洞与中继 dialer
// （各自幂等，只注册一次），并在 node 已启动时确保 B 侧 serveRelay 循环只
// 启动一次。Start 与 SetRelayConfig 共用。node 未启动（started == false）时
// 只注册 dialer 不启动 serveRelay——Start 尾部会再调一次本函数补启动
// （SetRelayConfig 可能在 Start 前调用：启动注入经 hubRunCtx 异步轮询，
// 不能拿未就绪的 n.ctx 起 serveRelay）。
func (n *Node) applyRelayConfig() {
	n.relayMu.Lock()
	rc := n.opts.RelayConfig
	n.relayMu.Unlock()
	if rc == nil {
		return
	}
	if len(rc.StunAddrs) > 0 && n.relayPunchRegistered.CompareAndSwap(false, true) {
		// P2P 打洞（阶段2）：Priority 35，在反向公网直连（30）之后、中继（40）
		// 之前——打洞失败自动降级中继。
		n.Registry.Register(&punchDialer{n: n})
	}
	if rc.HasCoordinate() && n.relayDialerRegistered.CompareAndSwap(false, true) {
		// 中继（TURN 式密文包转发）：Priority 40，在所有直连方式之后尝试。
		// 判据是 HasCoordinate（QUIC 或 WSS 任一），与下面的会合同口径——官方
		// Hub 是 WSS-only 的，只看 QuicAddr 等于让账号内中继永不注册。
		n.Registry.Register(&relayDialer{n: n})
	}
	if rc.HasCoordinate() && n.rzRegistered.CompareAndSwap(false, true) {
		// 临时连接会合：Priority 50，梯队最后一级。坐标可用即注册——它不依赖
		// JWT，未登录 Hub 的机器正是它服务的对象。
		n.Registry.Register(&rendezvousDialer{n: n})
	}
	n.ensureServeRelay(rc)
	n.ensureServeRendezvous()
}

// ensureServeRelay 在 node 已启动且配置含中继地址时，确保 B 侧 serveRelay
// 循环只启动一次。用 n.started（atomic）判断启动态——atomic 读写建立
// happens-before，保证 serveRelay goroutine 一定能看到 Start 写入的 n.ctx
// （无 data race）。node 未启动时静默跳过，由 Start 尾部 applyRelayConfig
// 补启动。
func (n *Node) ensureServeRelay(rc *RelayConfig) {
	// 判据同注册门：WSS-only 的 Hub 上 B 侧也必须常驻，否则本机拨得出去、却
	// 永远收不到别人经中继拨过来的 INCOMING（单向可用比全不可用更难查）。
	if !rc.HasCoordinate() {
		return
	}
	if !n.started.Load() {
		return
	}
	if n.relayServing.CompareAndSwap(false, true) {
		go n.serveRelay()
	}
}

// RelayConfig 返回当前生效的中继配置快照（SetRelayConfig 注入的），nil = 未
// 配置。返回拷贝，调用方可安全持有。供诊断/测试观察配置是否注入成功。
func (n *Node) RelayConfig() *RelayConfig {
	n.relayMu.Lock()
	defer n.relayMu.Unlock()
	rc := n.opts.RelayConfig
	if rc == nil {
		return nil
	}
	cp := *rc
	cp.StunAddrs = append([]string(nil), rc.StunAddrs...)
	return &cp
}

// SetConnectionPrefs 独立注入账号连接偏好。**偏好绝不能寄生在中继配置的通道
// 上**：Hub 没配中继坐标（自建 Hub / 中继临时关闭）时 FetchRelayConfig 没有
// 中继配置可注入，四个开关就会一个都不生效、syncModels 被硬锁在「开」。所以
// 偏好走这条独立通道，与中继坐标彻底解耦。nil = 回落默认（全开）。
// 并发安全（持 relayMu 写，与 ConnectionPrefs() 的读同锁）。
func (n *Node) SetConnectionPrefs(p *ConnectionPrefs) {
	n.relayMu.Lock()
	n.connPrefs = p
	n.relayMu.Unlock()
}

// ConnectionPrefs 返回当前生效的连接偏好，nil = 未设置（默认全开）。
// 优先取 SetConnectionPrefs 注入的独立值；未注入时回落 RelayConfig.ConnectionPrefs
// （Options 直接构造 / SetRelayConfig 携带的旧通道）。并发安全（持 relayMu 读）。
func (n *Node) ConnectionPrefs() *ConnectionPrefs {
	n.relayMu.Lock()
	defer n.relayMu.Unlock()
	if n.connPrefs != nil {
		return n.connPrefs
	}
	if n.opts.RelayConfig == nil {
		return nil
	}
	return n.opts.RelayConfig.ConnectionPrefs
}

func (n *Node) reverseRequester() func(ctx context.Context, peerID string) error {
	n.reverseMu.Lock()
	defer n.reverseMu.Unlock()
	return n.reverseRequest
}

// SetLoomOfferSender injects the「经 Hub 信令向 peer 发 loom-offer 并等待
// loom-answer」的发送器（server 层接 hubconn.SendLoomOffer；nil = 信令未接入，
// punchDialer 不可用）。Safe to call before Start。
func (n *Node) SetLoomOfferSender(fn func(ctx context.Context, peerID, myReflexiveAddr string) (peerReflexiveAddr string, err error)) {
	n.punchMu.Lock()
	n.loomOfferSender = fn
	n.punchMu.Unlock()
}

func (n *Node) loomOfferSenderFn() func(ctx context.Context, peerID, myReflexiveAddr string) (string, error) {
	n.punchMu.Lock()
	defer n.punchMu.Unlock()
	return n.loomOfferSender
}

// SetLoomOfferHandler injects the「收到 peer 的 loom-offer 后返回本机 reflexive
// addr 作为 answer」的 B 侧处理（server 层接 hubconn 的 SetLoomOfferHandler →
// 调用本机 punch B 侧逻辑）。Safe to call before Start。
func (n *Node) SetLoomOfferHandler(fn func(fromMachineID, offerAddr string) (answerAddr string, err error)) {
	n.punchMu.Lock()
	n.loomOfferHandler = fn
	n.punchMu.Unlock()
}

func (n *Node) loomOfferHandlerFn() func(fromMachineID, offerAddr string) (string, error) {
	n.punchMu.Lock()
	defer n.punchMu.Unlock()
	return n.loomOfferHandler
}

// NotifyReverseOutcome ingests the peer's dial-back result（0.14.4，经 Hub 信令
// reverse-connect-result 回传）。失败 → 立即以对方侧的原样报错终结所有等待者
// （不再干等 9s 超时）；成功 → 不做事——成功的信号是连接本身：对方拨入的
// QUIC 连接经 adoptInbound/storeConn 落地时已满足等待者，ok 结果只是尾灯。
func (n *Node) NotifyReverseOutcome(peerID string, ok bool, reason string) {
	if ok {
		return
	}
	if strings.TrimSpace(reason) == "" {
		reason = "对方未说明原因"
	}
	n.failReverseWaiters(peerID, reason)
}

// DialBack handles a peer's reverse-connect request (0.14.3 反向互通)：the
// requester (fromID) says it cannot reach us but WE can reach it. Run our own
// direct+public ladder toward it — explicitly NOT the full registry (the
// reverse dialer would signal back and loop). hintPublic is the requester's
// self-reported public address, used only when our directory has no public
// entry for it yet (fingerprint pinning still comes from the directory, so a
// forged hint cannot impersonate the peer). The established connection is
// stored (both sides reuse it); errors are returned for the caller to log —
// the requester times out on its own if we fail.
func (n *Node) DialBack(ctx context.Context, fromID, hintPublic string) error {
	if n.tr == nil {
		return errors.New("loomnet: node not started")
	}
	// 缓存里已有到请求方的连接？一条活的 QUIC 连接在两侧都已登记（出站经
	// storeConn、入站经 adoptInbound），对方若真有活连接就不会发反拨请求——
	// 所以这里的缓存命中大概率是本侧还没检测到死亡的僵尸。开一条探测流验证：
	// 活 → 对方那侧也有（它的等待者会在注册后复查缓存命中），无需新拨；
	// 死 → 清掉缓存走全新拨号。
	if s := n.cachedConn(fromID); s != nil {
		probeCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
		st, perr := s.OpenStream(probeCtx)
		cancel()
		if perr == nil {
			_ = st.Close()
			return nil
		}
		n.evictConn(fromID, s)
	}
	_, err := n.dials.do("dialback:"+fromID, func() (Session, error) {
		if s := n.cachedConn(fromID); s != nil {
			return s, nil
		}
		fp, eps, ok := n.opts.Directory.PeerInfo(fromID)
		if !ok {
			return nil, fmt.Errorf("loomnet: dialback: 本机目录中没有 %s 的信息（Hub 目录未刷新？）", fromID)
		}
		if eps.Public == "" && hintPublic != "" {
			// 对方刚开启公网直连、本机目录还没刷到——用它自报的地址补位。
			// 指纹仍取自 Hub 目录，地址伪造无法通过 mTLS 钉扎。
			eps.Public = hintPublic
		}
		var errs []error
		if s, derr := n.dialDirect(ctx, fromID, fp, eps); derr == nil {
			n.storeConn(fromID, s, pathDirect)
			return s, nil
		} else {
			errs = append(errs, derr)
		}
		if s, derr := n.dialPublic(ctx, fromID, fp, eps); derr == nil {
			n.storeConn(fromID, s, pathPublic)
			return s, nil
		} else {
			errs = append(errs, derr)
		}
		return nil, fmt.Errorf("loomnet: dialback 至 %s 失败: %w", fromID, errors.Join(errs...))
	})
	return err
}

// Stop tears down the node: cancels its context, closes the HTTP server,
// listener, cached sessions, and the shared transport.
func (n *Node) Stop() {
	if n.cancel != nil {
		n.cancel()
	}
	if n.httpSrv != nil {
		_ = n.httpSrv.Close()
	}
	if n.listener != nil {
		_ = n.listener.Close()
	}
	n.connsMu.Lock()
	for _, s := range n.conns {
		_ = s.Close()
	}
	n.conns = map[string]Session{}
	n.connsMu.Unlock()
	if n.rt != nil {
		n.rt.CloseIdleConnections()
	}
	if n.tr != nil {
		n.tr.close()
	}
	n.relayMu.Lock()
	if n.relay != nil {
		n.relay.close()
		n.relay = nil
	}
	n.relayMu.Unlock()
	n.closeRendezvous()
}

// relayClient 返回与当前中继配置匹配的懒客户端（同一个中继地址只连一次，
// 多条电路复用同一 QUIC 连接的不同流）。配置变化（坐标/JWT/STUN 变更）时
// close 旧 client 并重建——M8：不再「首次创建后永远用旧坐标」，Hub 换中继
// 地址或 JWT 轮换后 serveRelay 以 30s backoff 无限 HELLO-ERR 的问题由此消除。
// 配置被清除（nil）时关闭旧 client 并返回 nil。注入 Directory 和 onIncoming
// 回调以支持 B 侧（被叫方）逻辑：收到 INCOMING 后 ACCEPT 并把入站 Session
// 接入 node。
// closeRelayClient 关闭当前中继 client（若有）并清空配置指纹——下一次
// relayClient() 会按当时的配置懒重建。偏好关闭中继时由 serveRelay 调用，
// 切断本机与 Hub 中继之间的常驻 QUIC 连接（入站面）。
func (n *Node) closeRelayClient() {
	n.relayMu.Lock()
	defer n.relayMu.Unlock()
	if n.relay != nil {
		n.relay.close()
		n.relay = nil
	}
	n.relayCfgFp = ""
}

func (n *Node) relayClient() *relayClient {
	n.relayMu.Lock()
	defer n.relayMu.Unlock()
	rc := n.opts.RelayConfig
	if rc == nil {
		// 配置被清除：关闭旧 client（切断旧坐标的控制连接/电路）并清空指纹。
		if n.relay != nil {
			n.relay.close()
			n.relay = nil
		}
		n.relayCfgFp = ""
		return nil
	}
	fp := rc.Fingerprint()
	if n.relay != nil {
		if n.relayCfgFp == fp {
			// 同一配置：复用现有 client（幂等）。
			return n.relay
		}
		// 配置变化：关闭旧 client（旧坐标的 HELLO/电路立即失效）并重建。
		n.relay.close()
		n.relay = nil
	}
	n.relay = newRelayClient(rc.QuicAddr, rc.WSSUrl, n.opts.MachineID, rc.JWT, rc.RelaySPKI, n.identity, n.opts.Directory, n.adoptRelayInbound)
	n.relayCfgFp = fp
	return n.relay
}

// serveRelay 维护到中继的持久连接（B 侧）。连接断开后自动重连；配置被清除
// 时保持循环存活，等配置重新注入（PollRelayConfig 每 30s 一轮）后按新坐标
// 重建连接。A 侧拨号时也会懒连中继，但 B 侧必须一直在线才能接收 INCOMING。
func (n *Node) serveRelay() {
	backoff := time.Second
	for {
		select {
		case <-n.ctx.Done():
			return
		default:
		}
		if !n.ConnectionPrefs().RelayEnabled() {
			// 用户关掉「服务器中继」：出站已被 relayDialer 的偏好门拦下，
			// **入站也必须停**——否则本机仍与 Hub 中继保持常驻 QUIC 连接并
			// 照常接受入站中继会话，那个开关就是名不副实的（「所有流量经过
			// Hub 服务器转发」的开关关掉后流量照走）。开关重开后下一轮自动
			// 重连（relayClient 懒重建）。
			n.closeRelayClient()
			select {
			case <-n.ctx.Done():
				return
			case <-time.After(5 * time.Second):
			}
			continue
		}
		rc := n.relayClient()
		if rc == nil {
			// 中继配置被清除（或尚未注入）：等配置重新注入再连。
			select {
			case <-n.ctx.Done():
				return
			case <-time.After(time.Second):
			}
			continue
		}
		err := rc.ensureConnected(n.ctx)
		if err != nil {
			log.Printf("[loomnet/relay] B 侧主动连中继失败：%v，%v 后重试", err, backoff)
			select {
			case <-n.ctx.Done():
				return
			case <-time.After(backoff):
			}
			backoff = min(backoff*2, 30*time.Second)
			continue
		}
		// 连接成功，重置 backoff。ensureConnected 是幂等的——已连接则直接返回。
		// 连接断开后 serveIncoming 退出 + reset() 置 hello=false，
		// 下次 ensureConnected 会重新建连。定期轮询检测+重连。
		backoff = time.Second
		select {
		case <-n.ctx.Done():
			return
		case <-time.After(10 * time.Second):
		}
	}
}

// Transport is the http.RoundTripper for reaching peers: requests to
// "http://<machineID>.loom/..." are dialed over the overlay, so SSE/streaming
// works transparently (§3.3). One shared client can be built over it.
func (n *Node) Transport() http.RoundTripper { return n.rt }

// Fingerprint is this node's overlay identity fingerprint (base64 SPKI sha256),
// reported to the Hub via heartbeat so peers can pin it during the mTLS
// handshake (§2.1, §6.2).
func (n *Node) Fingerprint() string { return n.identity.Fingerprint() }

// MachineID is the stable overlay identity this node was built for (the cert
// CN). /v1/hub/connect compares it with the effective machine ID to decide
// whether the node must be rebuilt on a runtime Hub/machine switch.
func (n *Node) MachineID() string { return n.opts.MachineID }

// LocalEndpoints reports this node's overlay dial candidates for the Hub
// heartbeat (§6.2): local LAN IPs and the bound UDP port.
func (n *Node) LocalEndpoints() Endpoints {
	ep := Endpoints{LAN: localLANIPs(), Public: n.opts.PublicAdvertise}
	if n.tr != nil {
		ep.UDPPort = n.tr.localUDPAddr().Port
	}
	return ep
}

// LastPath reports the method that last established a connection to machineID
// ("direct"/"public"/"reverse"/"inbound"), or "" if none. It is a memory: it
// survives the session dying. For "what is in use RIGHT NOW" use ActivePath.
func (n *Node) LastPath(machineID string) string {
	n.pathsMu.Lock()
	defer n.pathsMu.Unlock()
	return n.paths[machineID]
}

// ActivePath reports the tier of the LIVE cached session to machineID, or ""
// when there is no live session. Unlike LastPath it never reports a dead
// connection's path — this is what the topology UI's "正在使用" must use.
func (n *Node) ActivePath(machineID string) string {
	if n.cachedConn(machineID) == nil {
		return ""
	}
	return n.LastPath(machineID)
}

// ActiveInboundVia classifies how the LIVE adopted-inbound connection from
// machineID reached us: "lan" (private / ULA / loopback source address) or
// "public". "" when there is no live inbound session. This lets the topology
// label an inbound row with its actual transport（入站连接复用 · 局域网/公网）
// so both ends of the same wire describe it consistently — the dialer side
// already shows 局域网直连/公网直连.
func (n *Node) ActiveInboundVia(machineID string) string {
	if n.ActivePath(machineID) != pathInbound {
		return ""
	}
	s := n.cachedConn(machineID)
	ra, ok := s.(interface{ RemoteAddr() net.Addr })
	if !ok {
		return ""
	}
	return classifyRemoteAddr(ra.RemoteAddr())
}

// classifyRemoteAddr maps a peer source address to "lan" / "public" ("" when
// unparseable). Private (RFC1918 + ULA), link-local, and loopback all count as
// LAN — they can only have arrived over the local network.
func classifyRemoteAddr(addr net.Addr) string {
	if addr == nil {
		return ""
	}
	var ip net.IP
	switch a := addr.(type) {
	case *net.UDPAddr:
		ip = a.IP
	case *net.TCPAddr:
		ip = a.IP
	default:
		host, _, err := net.SplitHostPort(addr.String())
		if err != nil {
			return ""
		}
		ip = net.ParseIP(host)
	}
	if ip == nil {
		return ""
	}
	if ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLoopback() {
		return "lan"
	}
	return "public"
}

// livePathCounts tallies how many live peer sessions currently use each path
// kind ("direct"/"public"/"reverse"/"inbound").
func (n *Node) livePathCounts() map[string]int {
	counts := map[string]int{}
	n.connsMu.Lock()
	ids := make([]string, 0, len(n.conns))
	for id := range n.conns {
		ids = append(ids, id)
	}
	n.connsMu.Unlock()
	n.pathsMu.Lock()
	for _, id := range ids {
		if p := n.paths[id]; p != "" {
			counts[p]++
		}
	}
	n.pathsMu.Unlock()
	return counts
}

// PeerReachability returns per-method availability for a peer, plus which
// method is currently active (has a LIVE cached session). Powers the topology
// UI's connection-method badges. An adopted inbound connection（对方拨入被
// 复用，0.14.3）is not a dialer — surface it as its own synthetic row so the
// "正在使用" state never disappears from the UI.
func (n *Node) PeerReachability(ctx context.Context, peerID string) []MethodStatus {
	active := n.ActivePath(peerID)
	out := n.Registry.PeerReachability(ctx, peerID, active)
	if active == pathInbound {
		out = append(out, MethodStatus{
			Name:      pathInbound,
			Label:     "入站连接复用",
			Available: true,
			Active:    true,
			Detail:    activePathDetail(pathInbound),
		})
	}
	return out
}

// SelfReachability describes the LOCAL node's own connection-method surface for
// the topology UI's 本机 row: 局域网直连 always, 公网直连 as the second row
// (configured or not — the unconfigured copy tells the user how to enable it).
func (n *Node) SelfReachability() []MethodStatus {
	eps := n.LocalEndpoints()
	inUse := n.livePathCounts()

	direct := MethodStatus{Name: pathDirect, Label: "局域网直连", Active: inUse[pathDirect] > 0}
	switch {
	case len(eps.LAN) > 0:
		direct.Available = true
		direct.Detail = fmt.Sprintf("本机监听 UDP 端口 %d，已向 Hub 通告 %d 个局域网地址（%s）。同一局域网内的机器可直连本机。", eps.UDPPort, len(eps.LAN), strings.Join(eps.LAN, "、"))
	default:
		direct.Detail = "本机未发现可通告的局域网地址，其他机器无法直连本机。请检查网络接口。"
	}
	if direct.Active {
		direct.Detail = fmt.Sprintf("当前有 %d 条活跃连接经此方式通信。", inUse[pathDirect]) + " " + direct.Detail
	}

	public := MethodStatus{Name: pathPublic, Label: "公网直连", Active: inUse[pathPublic] > 0}
	switch {
	case eps.Public != "":
		public.Available = true
		public.Detail = fmt.Sprintf("已配置公网直连地址 %s（本机实际监听 UDP 端口 %d）。任意网络的机器可经此直连本机；本机主动访问不同网络的机器时也会自动请其反拨本机（反向公网直连，0.14.3）——单侧可公网直连即可互通。请确保该 UDP 端口已在系统防火墙与云安全组放行，且端口转发（如有）指向本机。", eps.Public, eps.UDPPort)
	default:
		public.Detail = "未配置。拥有公网 IP 或已做端口转发的机器（如云服务器）可在 设置→网络与设备→公网直连 开启：固定 UDP 端口并填写公网地址，跨网络的机器即可直连本机；且只要任一方开启，双方即可互通（反向公网直连）。"
	}
	if public.Active {
		public.Detail = fmt.Sprintf("当前有 %d 条活跃连接经此方式通信。", inUse[pathPublic]) + " " + public.Detail
	}

	out := []MethodStatus{direct, public}

	// 入站连接复用：对端拨入本机、被本机双向复用的活连接。它经由局域网/公网
	// 线路而来但 path 记账是 pathInbound，不计入上面两枚芯片的 Active——若不
	// 单独显示，本机明明承载着活跃通信、自机行却两枚全绿（无一“正在使用”），
	// 与对端视角的蓝色活跃芯片互相矛盾（2026-07-14 用户实测困惑点）。
	if n := inUse[pathInbound]; n > 0 {
		out = append(out, MethodStatus{
			Name:      pathInbound,
			Label:     "入站连接复用",
			Available: true,
			Active:    true,
			Detail:    fmt.Sprintf("当前有 %d 条由对方拨入本机的连接正被双向复用：对方→本机方向建立，本机→对方的请求经同一连接送达（QUIC 天然双向）。", n),
		})
	}
	if n := inUse[pathReverse]; n > 0 {
		out = append(out, MethodStatus{
			Name:      pathReverse,
			Label:     "反向公网直连",
			Available: true,
			Active:    true,
			Detail:    fmt.Sprintf("当前有 %d 条经反向公网直连建立的活跃连接（本机经 Hub 信令请对方拨入本机公网地址后双向复用）。", n),
		})
	}

	return out
}

// ctxKeyPeerID keys the verified peer machineID stashed by connContext.
type ctxKeyPeerID struct{}

// ctxKeyPeerFp keys the verified peer SPKI fingerprint stashed by connContext.
type ctxKeyPeerFp struct{}

// connContext stashes each inbound stream's mTLS-verified machineID and SPKI
// fingerprint into the request context so serveHandler can stamp trusted
// headers and recompute per-request trust (§2.4 + tempconn provisional).
func (n *Node) connContext(ctx context.Context, c net.Conn) context.Context {
	if mc, ok := c.(interface{ RemoteMachineID() string }); ok {
		if id := mc.RemoteMachineID(); id != "" {
			ctx = context.WithValue(ctx, ctxKeyPeerID{}, id)
		}
	}
	if fc, ok := c.(interface{ RemoteFingerprint() string }); ok {
		if fp := fc.RemoteFingerprint(); fp != "" {
			ctx = context.WithValue(ctx, ctxKeyPeerFp{}, fp)
		}
	}
	return ctx
}

// inboundTrusted reports whether an inbound peer (CN, fp) is a fully-trusted
// account/tempconn member — i.e. the account fingerprint set binds this exact
// CN→fp. A provisional (tempconn 配对期) peer is NOT in the set and returns false.
func (n *Node) inboundTrusted(cn, fp string) bool {
	if cn == "" || fp == "" || n.opts.Directory == nil {
		return false
	}
	return n.opts.Directory.AccountFingerprints()[cn] == fp
}

// serveHandler wraps LocalHandler and stamps the verified caller identity. Any
// client-supplied X-Loom-* header is FIRST stripped so no spoofed value ever
// survives. A trusted peer gets X-Loom-From (+ fingerprint); a provisional
// (untrusted, tempconn 配对期) peer gets NO X-Loom-From — only the provisional
// headers — and is confined to Options.ProvisionalPath (everything else 403),
// so a not-yet-trusted machine can reach the one-time redeem endpoint and
// nothing more.
func (n *Node) serveHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		r.Header.Del(headerLoomFrom)
		r.Header.Del(headerLoomFp)
		r.Header.Del(headerLoomProvisionalFrom)

		id, _ := r.Context().Value(ctxKeyPeerID{}).(string)
		fp, _ := r.Context().Value(ctxKeyPeerFp{}).(string)
		if id == "" {
			n.opts.LocalHandler.ServeHTTP(w, r)
			return
		}
		if n.inboundTrusted(id, fp) {
			r.Header.Set(headerLoomFrom, id)
			r.Header.Set(headerLoomFp, fp)
			n.opts.LocalHandler.ServeHTTP(w, r)
			return
		}
		// Provisional: redeem-only.
		if n.opts.ProvisionalPath == "" || r.URL.Path != n.opts.ProvisionalPath {
			http.Error(w, "loomnet: 未建立信任的对端只能访问临时连接兑换端点", http.StatusForbidden)
			return
		}
		r.Header.Set(headerLoomProvisionalFrom, id)
		r.Header.Set(headerLoomFp, fp)
		n.opts.LocalHandler.ServeHTTP(w, r)
	})
}

// trackInbound registers a live inbound session for ClosePeer, and drops it
// when the connection dies.
func (n *Node) trackInbound(s *quicSession) {
	n.inboundMu.Lock()
	n.inboundConns[s] = struct{}{}
	n.inboundMu.Unlock()
	go func() {
		select {
		case <-s.conn.Context().Done():
		case <-n.ctx.Done():
		}
		n.inboundMu.Lock()
		delete(n.inboundConns, s)
		n.inboundMu.Unlock()
	}()
}

// ClosePeer tears down every live connection to machineID — the adopted
// outbound session (conns) and any inbound sessions — evicting the cache so a
// later dial re-runs the ladder. tempconn 用它在取消信任/立刻接管时立即断开
// 一台主控端的活跃会话。
func (n *Node) ClosePeer(machineID string) {
	n.connsMu.Lock()
	if s := n.conns[machineID]; s != nil {
		delete(n.conns, machineID)
		_ = s.Close()
	}
	n.connsMu.Unlock()

	n.inboundMu.Lock()
	var victims []*quicSession
	for s := range n.inboundConns {
		if s.RemoteMachineID() == machineID {
			victims = append(victims, s)
		}
	}
	n.inboundMu.Unlock()
	for _, s := range victims {
		_ = s.Close()
	}
}

// localLANIPs enumerates this host's non-loopback, non-link-local unicast IPs
// for the LAN candidate list. When interface enumeration yields nothing —
// Android 11+ denies apps the netlink RTM_GETLINK query behind
// net.InterfaceAddrs (golang/go#40569), so in the phone shell it ALWAYS comes
// back empty — fall back to UDP "dials": the OS picks the preferred outbound
// interface and reveals its source address without netlink and without sending
// a single packet. Without this the phone's heartbeat never carried a localIp
// and its Hub row sat on the registration placeholder forever (2026-07-15
// 实锤：手机行 local_ip 恒 127.0.0.1，「局域网直连」tab 因此永远匹配不到).
func localLANIPs() []string {
	var out []string
	if addrs, err := net.InterfaceAddrs(); err == nil {
		for _, a := range addrs {
			ipnet, ok := a.(*net.IPNet)
			if !ok || ipnet.IP.IsLoopback() || ipnet.IP.IsLinkLocalUnicast() {
				continue
			}
			if v4 := ipnet.IP.To4(); v4 != nil {
				out = append(out, v4.String())
			} else if ipnet.IP.To16() != nil {
				out = append(out, ipnet.IP.String())
			}
		}
	}
	if len(out) > 0 {
		return out
	}
	for _, probe := range []struct{ network, addr string }{
		{"udp4", "8.8.8.8:80"},
		{"udp6", "[2001:4860:4860::8888]:80"},
	} {
		if ip := outboundLocalIP(probe.network, probe.addr); ip != "" {
			out = append(out, ip)
		}
	}
	return out
}

// outboundLocalIP reports the local source IP the OS would use to reach addr,
// via a connectionless UDP "dial" (no packets are sent, no netlink is needed).
// Returns "" when the family is unavailable or the source is loopback/link-local.
func outboundLocalIP(network, addr string) string {
	conn, err := net.Dial(network, addr)
	if err != nil {
		return ""
	}
	defer conn.Close()
	ua, ok := conn.LocalAddr().(*net.UDPAddr)
	if !ok || ua.IP == nil || ua.IP.IsLoopback() || ua.IP.IsLinkLocalUnicast() {
		return ""
	}
	if v4 := ua.IP.To4(); v4 != nil {
		return v4.String()
	}
	return ua.IP.String()
}
