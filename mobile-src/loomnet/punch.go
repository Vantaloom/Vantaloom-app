package loomnet

// P2P NAT 打洞（阶段2）。
//
// 打洞流程（A 侧主动，B 侧响应）：
//  1. A 用独立 UDP socket（随机端口）发 STUN 到两个观测点，探测本机 NAT 类型
//     和这个 socket 的 reflexive 公网地址。
//  2. symmetric NAT → 直接放弃（打洞必败），返回错误让 ladder 降级中继。
//     cone NAT / 无 NAT → 继续。
//  3. A 经 Hub 信令发 loom-offer（含 A 的 reflexive addr）给 B。
//  4. B 收到 offer 后用 overlay socket 探测本机 NAT 类型，symmetric NAT → 回空
//     answer（拒绝）；cone NAT → 回 answer（含 B overlay socket 的 reflexive
//     addr）。
//  5. 双方拿到对方的 reflexive addr 后互发 UDP 包打洞。
//  6. A 在独立 socket 上拨 QUIC+mTLS 到 B 的 reflexive addr；B 的 overlay
//     listener 接受连接。
//
// 为什么 A 用独立 socket 而非 overlay socket：quic-go 独占 overlay socket 的
// ReadFrom（"After passing the connection to the Transport, it's invalid to call
// ReadFrom"），natprobe/punchHole 直接 ReadFrom overlay socket 会和 quic.Transport
// 竞争抢包。独立 socket 避免冲突，打洞成功后在独立 socket 上建 QUIC 连接，注册
// 为 overlay session（与 relayDialer 同律）。
//
// mTLS 指纹钉扎不变——reflexive addr 只是地址提示，无法用于冒充。

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"sync"
	"time"

	quic "github.com/quic-go/quic-go"
)

const (
	// punchTimeout 是单次打洞的总超时（含 NAT 探测 + 信令交换 + 打洞 + QUIC 握手）。
	punchTimeout = 12 * time.Second
	// punchHoleDeadline 是互发打洞包后等待 QUIC 握手成功的超时。
	punchHoleDeadline = 5 * time.Second
	// symmetricPunchDialTimeout 是 symmetric NAT 场景直接拨 QUIC 的握手超时。
	// symmetric 打洞到「不可打洞目标」（无公网直连的 VPS——其 reflexive 是出站
	// SNAT 映射端口，入站无端口转发必丢包）大概率失败——短超时快速失败降级
	// 中继，避免占满 punchTimeout(12s) 拖慢每次首次拨号（2026-08-16 实测
	// 未优化前打洞到无公网直连 VPS 要 13.5s 才降级中继）。
	symmetricPunchDialTimeout = 5 * time.Second
	// punchCacheTTL 是打洞结果缓存的失效时间（成功的 peer 不缓存，每次都尝试；
	// 失败的 peer 缓存 5 分钟，避免 symmetric NAT 反复等超时）。
	punchCacheTTL = 5 * time.Minute
)

// punchPacket 是打洞用的 UDP 包内容（任意内容即可，目的是在 NAT 上开映射）。
var punchPacket = []byte("LOOM-PUNCH")

// punchDialer 是 P2P 打洞连接方式：独立 UDP socket 上 STUN 探测 NAT 类型 + Hub
// 信令交换 reflexive addr + 互发 UDP 包打洞 + QUIC+mTLS 建连。Priority 35，在
// 反向公网直连（30）之后、中继（40）之前——打洞失败自动降级中继。
type punchDialer struct{ n *Node }

func (d *punchDialer) Name() string          { return pathPunch }
func (d *punchDialer) Label() string         { return "P2P 打洞" }
func (d *punchDialer) Priority() int         { return 35 }
func (d *punchDialer) Budget() time.Duration { return punchTimeout }

func (d *punchDialer) Available(ctx context.Context, peerID string) bool {
	ok, _ := d.Explain(ctx, peerID)
	return ok
}

func (d *punchDialer) Explain(_ context.Context, peerID string) (bool, string) {
	if prefs := d.n.ConnectionPrefs(); !prefs.P2PEnabled() {
		return false, "账号「连接偏好」已关闭 P2P 穿透。"
	}
	rc := d.n.RelayConfig()
	if rc == nil || len(rc.StunAddrs) == 0 {
		return false, "打洞未启用（未配置 STUN 观测点）。打洞需要 Hub 提供 STUN 服务用于 NAT 类型探测。"
	}
	sender := d.n.loomOfferSenderFn()
	if sender == nil {
		return false, "打洞信令未接入（Hub 信令连接未建立）。"
	}
	fp, _, ok := d.n.opts.Directory.PeerInfo(peerID)
	if !ok {
		return false, "尚未从 Hub 获取到对方的 overlay 连接信息。"
	}
	if fp == "" {
		return false, "对方未上报 overlay 指纹（可能未运行 overlay 或版本过旧）。"
	}
	// 检查打洞结果缓存：失败的 peer 在 TTL 内直接跳过
	d.n.punchMu.Lock()
	entry, cached := d.n.punchCache[peerID]
	d.n.punchMu.Unlock()
	if cached && !entry.ok && time.Since(entry.cachedAt) < punchCacheTTL {
		return false, fmt.Sprintf("上次打洞失败（%s），%d 秒内自动跳过走中继。",
			entry.failReason, int(punchCacheTTL.Seconds()-time.Since(entry.cachedAt).Seconds()))
	}
	return true, "将经 STUN 探测 NAT 类型并尝试打洞建立 P2P 直连（QUIC+mTLS，不经过服务器）。"
}

func (d *punchDialer) Dial(ctx context.Context, peerID string) (Session, error) {
	rc := d.n.RelayConfig()
	if rc == nil || len(rc.StunAddrs) == 0 {
		return nil, errors.New("loomnet: punch: STUN 观测点未配置")
	}
	sender := d.n.loomOfferSenderFn()
	if sender == nil {
		return nil, errors.New("loomnet: punch: 打洞信令未接入")
	}
	fp, _, ok := d.n.opts.Directory.PeerInfo(peerID)
	if !ok {
		return nil, fmt.Errorf("loomnet: punch: 无 %s 的目录信息", peerID)
	}
	if fp == "" {
		return nil, fmt.Errorf("loomnet: punch: %s 未上报指纹", peerID)
	}

	// 检查缓存：失败的 peer 在 TTL 内直接返回错误让 ladder 降级中继
	d.n.punchMu.Lock()
	entry, cached := d.n.punchCache[peerID]
	d.n.punchMu.Unlock()
	if cached && !entry.ok && time.Since(entry.cachedAt) < punchCacheTTL {
		return nil, fmt.Errorf("loomnet: punch: %s（缓存，%d 秒后重试）",
			entry.failReason, int(punchCacheTTL.Seconds()-time.Since(entry.cachedAt).Seconds()))
	}

	dctx, cancel := context.WithTimeout(ctx, punchTimeout)
	defer cancel()

	// 独立 UDP socket（随机端口），避免和 overlay socket 的 quic.Transport 竞争。
	// socket 生命周期由返回的 punchSession 持有（Close 时才关闭）。
	punchConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		d.recordPunchFailure(peerID, fmt.Sprintf("绑定打洞 socket: %v", err))
		return nil, fmt.Errorf("loomnet: punch: 绑定打洞 socket: %w", err)
	}
	// 失败路径统一关闭 punchConn（成功路径由 punchSession 接管），否则每个
	// 失败的打洞尝试泄漏一个 UDP socket（M5 review 2026-08-13）。
	handedOff := false
	defer func() {
		if !handedOff {
			punchConn.Close()
		}
	}()

	// 1. A 侧 NAT 探测（用独立 socket）
	myNAT, err := probeNAT(dctx, punchConn, rc.StunAddrs)
	if err != nil {
		d.recordPunchFailure(peerID, fmt.Sprintf("NAT 探测失败: %v", err))
		return nil, fmt.Errorf("loomnet: punch: NAT 探测失败: %w", err)
	}
	if myNAT.NATType == NATSymmetric {
		// 2026-08-16 增强（用户要求「P2P 打洞经 hub 打通」）：symmetric NAT 不再
		// 一刀切放弃。symmetric NAT 作为**发起方**打洞到对端仍可行——A 主动拨
		// QUIC 建立映射（M_B），QUIC 握手回包到源地址，symmetric 映射按目标
		// 匹配，双向自然打通。不再依赖「B 回打洞包到 STUN 反射地址」（那对
		// symmetric 无效：M_STUN ≠ M_B，B 回 M_STUN 被 NAT 丢弃）。打洞失败由
		// QUIC 握手超时兜底，降级中继（punchCache 缓存 5 分钟避免反复等超时）。
		log.Printf("[loomnet/punch] 本机 symmetric NAT：仍尝试打洞（跳过打洞包互证，直接拨 QUIC）")
	}
	// reflexive 地址是内网地址 → STUN 探测被本地 NAT/VPN/ALG 篡改，reflexive
	// 地址不可信，打洞无法工作（对方到不了内网地址）。降级中继。
	if myNAT.ReflexiveIP.IsPrivate() || myNAT.ReflexiveIP.IsLoopback() {
		d.recordPunchFailure(peerID, fmt.Sprintf("NAT 探测返回内网地址 %s（可能本地有 VPN/ALG 拦截 UDP），打洞不可行", myNAT.ReflexiveIP))
		return nil, fmt.Errorf("loomnet: punch: NAT 探测返回内网地址 %s，打洞不可行（降级中继）", myNAT.ReflexiveIP)
	}
	myReflexive := fmt.Sprintf("%s:%d", myNAT.ReflexiveIP.String(), myNAT.ReflexivePort)
	log.Printf("[loomnet/punch] A 侧 NAT=%s reflexive=%s", myNAT.NATType, myReflexive)

	// 2. 经 Hub 信令发 loom-offer，等 B 的 loom-answer（含 B 的 reflexive addr）
	peerReflexive, err := sender(dctx, peerID, myReflexive)
	if err != nil {
		d.recordPunchFailure(peerID, fmt.Sprintf("信令交换失败: %v", err))
		return nil, fmt.Errorf("loomnet: punch: 信令交换失败: %w", err)
	}
	if peerReflexive == "" {
		d.recordPunchFailure(peerID, "对方为 symmetric NAT 或未启用打洞，对方拒绝")
		return nil, errors.New("loomnet: punch: 对方为 symmetric NAT 或未启用打洞（降级中继）")
	}
	log.Printf("[loomnet/punch] B 侧 reflexive=%s", peerReflexive)

	peerAddr, err := net.ResolveUDPAddr("udp", peerReflexive)
	if err != nil {
		d.recordPunchFailure(peerID, fmt.Sprintf("解析对方地址失败: %v", err))
		return nil, fmt.Errorf("loomnet: punch: 解析对方 reflexive 地址 %q: %w", peerReflexive, err)
	}

	// 3. 互发 UDP 包打洞（A 从独立 socket 发，B 从 overlay socket 发）
	// symmetric NAT：跳过打洞包互证——B 回包目标是 offer 里的 STUN 反射地址
	// （M_STUN），对 symmetric NAT 无效（A 打洞建立的映射是 M_B，M_STUN 的回包
	// 被 NAT 丢弃）。直接拨 QUIC：QUIC 握手回包到源地址（M_B），symmetric 映射
	// 按目标匹配，双向自然打通，握手成功本身即双向验证。
	if myNAT.NATType != NATSymmetric {
		if err := punchHole(dctx, punchConn, peerAddr); err != nil {
			d.recordPunchFailure(peerID, fmt.Sprintf("打洞失败: %v", err))
			return nil, fmt.Errorf("loomnet: punch: 打洞失败: %w", err)
		}
		log.Printf("[loomnet/punch] 打洞包互通，开始 QUIC 握手")
	} else {
		log.Printf("[loomnet/punch] 本机 symmetric NAT：跳过打洞包互证，直接拨 QUIC（握手验证双向）")
	}

	// 4. 在独立 socket 上拨 QUIC+mTLS（复用已打开的 NAT 映射）
	// qt 和 punchConn 的生命周期由 punchSession 持有，Close 时才关闭。
	qt := &quic.Transport{Conn: punchConn}
	dialCtx := dctx
	if myNAT.NATType == NATSymmetric {
		// symmetric 场景：打洞目标不可达时快速失败降级中继（5s 而非 12s）。
		var cancel context.CancelFunc
		dialCtx, cancel = context.WithTimeout(dctx, symmetricPunchDialTimeout)
		defer cancel()
	}
	sess, err := dialQUICOnTransport(dialCtx, qt, punchConn, peerAddr, fp, peerID, d.n.identity)
	if err != nil {
		qt.Close()
		// punchConn 由上面的 defer 统一关闭（M5 review）。
		d.recordPunchFailure(peerID, fmt.Sprintf("QUIC 握手失败: %v", err))
		return nil, fmt.Errorf("loomnet: punch: QUIC 握手失败: %w", err)
	}

	// 成功：清除失败缓存（如果有）
	d.n.punchMu.Lock()
	delete(d.n.punchCache, peerID)
	d.n.punchMu.Unlock()

	log.Printf("[loomnet/punch] 打洞成功 peer=%s", peerID)
	// punchConn 与 qt 的生命周期转交给 punchSession（Close 时才关闭）。
	handedOff = true
	return &punchSession{quicSession: sess, qt: qt, conn: punchConn}, nil
}

// recordPunchFailure 记录打洞失败结果到缓存，避免对 symmetric NAT 反复尝试。
func (d *punchDialer) recordPunchFailure(peerID, reason string) {
	d.n.punchMu.Lock()
	d.n.punchCache[peerID] = punchCacheEntry{ok: false, failReason: reason, cachedAt: time.Now()}
	d.n.punchMu.Unlock()
}

// punchHole 从 conn 向 peerAddr 发 UDP 打洞包，并等待收到对方发来的打洞包
// （证明 NAT 映射已打开）。超时返回 error。
func punchHole(ctx context.Context, conn *net.UDPConn, peerAddr *net.UDPAddr) error {
	// 设置读写超时
	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(punchHoleDeadline)
	} else if remaining := time.Until(deadline); remaining > punchHoleDeadline {
		deadline = time.Now().Add(punchHoleDeadline)
	}
	_ = conn.SetReadDeadline(deadline)
	defer conn.SetReadDeadline(time.Time{})

	// 连发几个打洞包（应对丢包）
	punchDone := make(chan struct{})
	var punchOnce sync.Once
	go func() {
		for i := 0; i < 5; i++ {
			_, _ = conn.WriteToUDP(punchPacket, peerAddr)
			time.Sleep(200 * time.Millisecond)
			select {
			case <-ctx.Done():
				return
			case <-punchDone:
				return
			default:
			}
		}
	}()
	defer punchOnce.Do(func() { close(punchDone) })

	// 等待收到对方的打洞包（punchPacket 内容，来自对方 reflexive addr）
	buf := make([]byte, 1500)
	for {
		n, raddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return fmt.Errorf("等待打洞包超时: %w", err)
		}
		// 只认 punchPacket 内容的包，且来自对方 reflexive addr
		if n == len(punchPacket) && string(buf[:n]) == string(punchPacket) &&
			raddr.IP.Equal(peerAddr.IP) && raddr.Port == peerAddr.Port {
			return nil
		}
		// 其他包（STUN 响应等）丢弃
	}
}

// HandlePunchOfferB 是 B 侧打洞处理：收到 A 的 loom-offer（含 A 的 reflexive
// addr）后，用独立 UDP socket 探测本机 NAT 类型，symmetric NAT → 返回空 answer
// （拒绝）；cone NAT → 返回独立 socket 的 reflexive addr 作为 answer，并启动
// 打洞（从独立 socket 发打洞包给 A，打开 A 的 NAT 映射），同时在独立 socket
// 上 quic.Listen 等待 A 的 QUIC 拨号。
//
// B 侧也用独立 socket（与 A 侧对称）：quic-go 独占 overlay socket 的 ReadFrom，
// B 侧 STUN 探测/打洞/quic.Listen 都不能在 overlay socket 上做。独立 socket 的
// reflexive 地址就是 A 要拨的目标。打洞成功后 B 在独立 socket 上接受 QUIC 连接，
// 注册为入站 session（与 relayDialer 的入站路径同律）。
//
// 注意：B 侧的 quic.Listen 和打洞包接收都在独立 socket 上，独立 socket 的生命
// 周期必须延续到 QUIC 连接建立后——由 acceptPunchQUIC goroutine 持有。
func (n *Node) HandlePunchOfferB(fromMachineID, offerAddr string) (string, error) {
	rc := n.RelayConfig()
	if rc == nil || len(rc.StunAddrs) == 0 {
		return "", errors.New("本机未启用打洞")
	}

	ctx, cancel := context.WithTimeout(n.ctx, punchTimeout)
	defer cancel()

	// B 侧独立 UDP socket
	punchConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return "", fmt.Errorf("B 侧绑定打洞 socket: %w", err)
	}

	// B 侧 NAT 探测
	myNAT, err := probeNAT(ctx, punchConn, rc.StunAddrs)
	if err != nil {
		punchConn.Close()
		log.Printf("[loomnet/punch] B 侧 NAT 探测失败: %v", err)
		return "", fmt.Errorf("B 侧 NAT 探测失败: %w", err)
	}
	if myNAT.NATType == NATSymmetric {
		punchConn.Close()
		log.Printf("[loomnet/punch] B 侧为 symmetric NAT，拒绝打洞")
		return "", nil // 空 answer = 拒绝
	}
	// reflexive 地址是内网地址 → STUN 探测不可靠，拒绝打洞
	if myNAT.ReflexiveIP.IsPrivate() || myNAT.ReflexiveIP.IsLoopback() {
		punchConn.Close()
		log.Printf("[loomnet/punch] B 侧 NAT 探测返回内网地址 %s，拒绝打洞", myNAT.ReflexiveIP)
		return "", nil // 空 answer = 拒绝
	}

	myReflexive := fmt.Sprintf("%s:%d", myNAT.ReflexiveIP.String(), myNAT.ReflexivePort)
	log.Printf("[loomnet/punch] B 侧 NAT=%s reflexive=%s", myNAT.NATType, myReflexive)

	peerAddr, err := net.ResolveUDPAddr("udp", offerAddr)
	if err != nil {
		punchConn.Close()
		return "", fmt.Errorf("解析 A 侧地址 %q: %w", offerAddr, err)
	}

	// 获取 A 的指纹（用于 mTLS pin）
	peerFP, _, ok := n.opts.Directory.PeerInfo(fromMachineID)
	if !ok || peerFP == "" {
		punchConn.Close()
		return "", fmt.Errorf("无 %s 的指纹信息", fromMachineID)
	}

	// 启动 B 侧打洞 + quic.Listen goroutine（独立 socket 生命周期由它持有）
	go n.acceptPunchQUIC(punchConn, peerAddr, peerFP, fromMachineID)

	return myReflexive, nil
}

// acceptPunchQUIC 是 B 侧打洞的后台 goroutine：从独立 socket 发打洞包给 A，
// 同时在独立 socket 上 quic.Listen 等待 A 的 QUIC 拨号。收到 A 的 QUIC 连接后
// 完成 mTLS 握手（server 模式，pin A 的指纹），注册为入站 session。
// conn 和 qt 的生命周期由注册的 punchSession 持有（Close 时才关闭）。
func (n *Node) acceptPunchQUIC(conn *net.UDPConn, peerAddr *net.UDPAddr, peerFP, peerID string) {
	ctx, cancel := context.WithTimeout(n.ctx, punchTimeout)
	defer cancel()

	// 1. 发打洞包给 A（打开 A 的 NAT 映射，让 A 的 QUIC 拨号能进来）
	punchDone := make(chan struct{})
	go func() {
		for i := 0; i < 10; i++ {
			_, _ = conn.WriteToUDP(punchPacket, peerAddr)
			time.Sleep(300 * time.Millisecond)
			select {
			case <-ctx.Done():
				return
			case <-punchDone:
				return
			default:
			}
		}
	}()
	defer close(punchDone)

	// 2. 在独立 socket 上 quic.Listen（等待 A 的 QUIC 拨号）。TLS 配置走
	// 共享的 tlsConfForServer（要求客户端证书 + pin 对方指纹）。
	qt := &quic.Transport{Conn: conn}
	ln, err := qt.Listen(tlsConfForServer(n.identity, peerFP), &quic.Config{
		MaxIdleTimeout:       idleTimeout,
		KeepAlivePeriod:      keepAlivePeriod,
		HandshakeIdleTimeout: handshakeIdle,
		MaxIncomingStreams:   maxIncomingStreams,
	})
	if err != nil {
		log.Printf("[loomnet/punch] B 侧 quic.Listen 失败: %v", err)
		qt.Close()
		conn.Close()
		return
	}
	defer ln.Close()

	// 3. 接受 A 的 QUIC 连接（带超时）
	// accept 窗口放宽到 punchTimeout 剩余余量（原 punchHoleDeadline=5s 在
	// 高延迟网络下 A 侧 punchHole + QUIC 拨号可能到不了 B → 打洞失败降级
	// 中继）。A 侧收到 answer 后 punchHole（最多 5s）立即拨 QUIC，12s 总
	// 预算内应完成。2026-08-11 review 发现。
	qconn, err := ln.Accept(ctx)
	if err != nil {
		log.Printf("[loomnet/punch] B 侧接受 QUIC 连接超时: %v", err)
		qt.Close()
		conn.Close()
		return
	}

	// 4. 验证 A 的指纹 + 提取 A 的 machineId
	gotID, peerFp, err := peerIdentity(qconn.ConnectionState().TLS)
	if err != nil {
		_ = qconn.CloseWithError(quic.ApplicationErrorCode(1), "missing identity")
		log.Printf("[loomnet/punch] B 侧验证 A 身份失败: %v", err)
		qt.Close()
		conn.Close()
		return
	}
	if gotID != peerID {
		_ = qconn.CloseWithError(quic.ApplicationErrorCode(1), "identity mismatch")
		log.Printf("[loomnet/punch] B 侧身份不匹配: 期望 %s 实际 %s", peerID, gotID)
		qt.Close()
		conn.Close()
		return
	}

	// 5. 注册为入站 session（与 overlay listener 入站同律：adoptInbound 把
	// 已验证的入站 QUIC 连接登记为到该 peer 的可复用出站会话）。
	// punchSession 持有 conn 和 qt，Close 时才关闭——不再 defer close。
	sess := &punchSession{
		quicSession: newQUICSession(context.Background(), qconn, gotID, peerFp),
		qt:          qt,
		conn:        conn,
	}
	n.adoptInbound(sess)
	// punch session 不走 overlay listener（用独立 socket 上的 quic.Listen），
	// 所以入站流不会被 listener 自动 demux。手动启动 demux 把对方开的流接入
	// 本机 http server（与 storeConn 里出站连接的 demux 同律）。
	if n.listener != nil {
		go n.listener.demux(qconn, gotID, peerFp)
	}
	log.Printf("[loomnet/punch] B 侧接受打洞连接成功 peer=%s", peerID)
}
