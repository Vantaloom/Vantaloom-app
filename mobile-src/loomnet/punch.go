package loomnet

// P2P uses dedicated UDP sockets: STUN first, then QUIC exclusively owns reads.
// Candidates are hints, not proof of reachability or identity. A immediately
// starts pinned QUIC+mTLS; B sends legacy LOOM-PUNCH hints throughout its
// existing handshake budget for older A implementations. No marker is an
// authentication or readiness gate for the new initiator.

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"time"

	quic "github.com/quic-go/quic-go"
)

const (
	// punchTimeout 是单次打洞的总超时（含 NAT 探测 + 信令交换 + 打洞 + QUIC 握手）。
	punchTimeout = 12 * time.Second
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
	if d.n.peerKnownOffline(peerID) {
		return false, offlinePeerReason
	}
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
	stopNodeCancel := context.AfterFunc(d.n.ctx, cancel)
	defer stopNodeCancel()
	// User / node cancellation is not evidence of a failed network path.
	recordFailure := func(reason string) {
		if ctx.Err() == nil && d.n.ctx.Err() == nil {
			d.recordPunchFailure(peerID, reason)
		}
	}
	if err := dctx.Err(); err != nil {
		return nil, err
	}

	// 独立 UDP socket（随机端口），避免和 overlay socket 的 quic.Transport 竞争。
	// socket 生命周期由返回的 punchSession 持有（Close 时才关闭）。
	punchConn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		recordFailure(fmt.Sprintf("绑定打洞 socket: %v", err))
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
		recordFailure(fmt.Sprintf("NAT 探测失败: %v", err))
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
		recordFailure(fmt.Sprintf("NAT 探测返回内网地址 %s（可能本地有 VPN/ALG 拦截 UDP），打洞不可行", myNAT.ReflexiveIP))
		return nil, fmt.Errorf("loomnet: punch: NAT 探测返回内网地址 %s，打洞不可行（降级中继）", myNAT.ReflexiveIP)
	}
	myReflexive := fmt.Sprintf("%s:%d", myNAT.ReflexiveIP.String(), myNAT.ReflexivePort)
	log.Printf("[loomnet/punch] A 侧 NAT=%s reflexive=%s", myNAT.NATType, myReflexive)

	if err := dctx.Err(); err != nil {
		return nil, err
	}
	// 2. 经 Hub 信令发 loom-offer，等 B 的 loom-answer（含 B 的 reflexive addr）
	peerReflexive, err := sender(dctx, peerID, myReflexive)
	if ctxErr := dctx.Err(); ctxErr != nil {
		err = ctxErr
	}
	if err != nil {
		recordFailure(fmt.Sprintf("信令交换失败: %v", err))
		return nil, fmt.Errorf("loomnet: punch: 信令交换失败: %w", err)
	}
	if peerReflexive == "" {
		recordFailure("对方为 symmetric NAT 或未启用打洞，对方拒绝")
		return nil, errors.New("loomnet: punch: 对方为 symmetric NAT 或未启用打洞（降级中继）")
	}
	log.Printf("[loomnet/punch] B 侧 reflexive=%s", peerReflexive)

	peerAddr, err := net.ResolveUDPAddr("udp", peerReflexive)
	if err != nil {
		recordFailure(fmt.Sprintf("解析对方地址失败: %v", err))
		return nil, fmt.Errorf("loomnet: punch: 解析对方 reflexive 地址 %q: %w", peerReflexive, err)
	}

	// QUIC is the sole read owner after STUN. No unauthenticated marker gate.
	dialCtx := dctx
	if myNAT.NATType == NATSymmetric {
		var cancel context.CancelFunc
		dialCtx, cancel = context.WithTimeout(dctx, symmetricPunchDialTimeout)
		defer cancel()
	}
	sess, err := dialPunchQUIC(dialCtx, d.n.ctx, punchConn, peerAddr, fp, peerID, d.n.identity)
	if err != nil {
		recordFailure(fmt.Sprintf("QUIC 握手失败: %v", err))
		return nil, fmt.Errorf("loomnet: punch: QUIC 握手失败: %w", err)
	}

	// 成功：清除失败缓存（如果有）
	d.n.punchMu.Lock()
	delete(d.n.punchCache, peerID)
	d.n.punchMu.Unlock()

	log.Printf("[loomnet/punch] 打洞成功 peer=%s", peerID)
	// punchConn 与 qt 的生命周期转交给 punchSession（Close 时才关闭）。
	handedOff = true
	return sess, nil
}

// recordPunchFailure 记录打洞失败结果到缓存，避免对 symmetric NAT 反复尝试。
func (d *punchDialer) recordPunchFailure(peerID, reason string) {
	d.n.punchMu.Lock()
	d.n.punchCache[peerID] = punchCacheEntry{ok: false, failReason: reason, cachedAt: time.Now()}
	d.n.punchMu.Unlock()
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
	handedOff := false
	defer func() {
		if !handedOff {
			cancel()
		}
	}()
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

	if err := ctx.Err(); err != nil {
		punchConn.Close()
		return "", err
	}
	// Transfer the ORIGINAL budget and cancel function; do not restart 12s.
	handedOff = true
	go func() {
		defer cancel()
		n.acceptPunchQUIC(ctx, punchConn, peerAddr, peerFP, fromMachineID)
	}()

	return myReflexive, nil
}

// acceptPunchQUIC 是 B 侧打洞的后台 goroutine：从独立 socket 发打洞包给 A，
// 同时在独立 socket 上 quic.Listen 等待 A 的 QUIC 拨号。收到 A 的 QUIC 连接后
// 完成 mTLS 握手（server 模式，pin A 的指纹），注册为入站 session。
// conn 和 qt 的生命周期由注册的 punchSession 持有（Close 时才关闭）。
func (n *Node) acceptPunchQUIC(ctx context.Context, conn *net.UDPConn, peerAddr *net.UDPAddr, peerFP, peerID string) {
	// Legacy A still waits for a marker. Keep hints alive through the handshake,
	// including delayed answers, but stop and join before socket handoff/close.
	stopPunch := startLegacyPunch(ctx, conn, peerAddr)
	defer stopPunch()

	// 2. 在独立 socket 上 quic.Listen（等待 A 的 QUIC 拨号）。TLS 配置走
	// 共享的 tlsConfForServer（要求客户端证书 + pin 对方指纹）。
	qt := &quic.Transport{Conn: conn}
	handedOff := false
	defer func() {
		stopPunch()
		if !handedOff {
			_ = qt.Close()
			_ = conn.Close()
		}
	}()
	ln, err := qt.Listen(tlsConfForServer(n.identity, peerFP), &quic.Config{
		MaxIdleTimeout:       idleTimeout,
		KeepAlivePeriod:      keepAlivePeriod,
		HandshakeIdleTimeout: handshakeIdle,
		MaxIncomingStreams:   maxIncomingStreams,
	})
	if err != nil {
		log.Printf("[loomnet/punch] B 侧 quic.Listen 失败: %v", err)
		return
	}
	defer ln.Close()

	// Accept only within the original offer budget (STUN already consumed part).
	qconn, err := ln.Accept(ctx)
	if err != nil {
		log.Printf("[loomnet/punch] B 侧接受 QUIC 连接超时: %v", err)
		return
	}

	// 4. 验证 A 的指纹 + 提取 A 的 machineId
	gotID, peerFp, err := peerIdentity(qconn.ConnectionState().TLS)
	if err != nil {
		_ = qconn.CloseWithError(quic.ApplicationErrorCode(1), "missing identity")
		log.Printf("[loomnet/punch] B 侧验证 A 身份失败: %v", err)
		return
	}
	if gotID != peerID {
		_ = qconn.CloseWithError(quic.ApplicationErrorCode(1), "identity mismatch")
		log.Printf("[loomnet/punch] B 侧身份不匹配: 期望 %s 实际 %s", peerID, gotID)
		return
	}

	// 5. 注册为入站 session（与 overlay listener 入站同律：adoptInbound 把
	// 已验证的入站 QUIC 连接登记为到该 peer 的可复用出站会话）。
	// punchSession 持有 conn 和 qt，Close 时才关闭——不再 defer close。
	stopPunch()
	if err := ctx.Err(); err != nil {
		_ = qconn.CloseWithError(0, "cancelled")
		return
	}
	handedOff = true
	sess := newPunchSession(n.ctx, newQUICSession(n.ctx, qconn, gotID, peerFp), qt, conn)
	n.adoptInbound(sess)
	// punch session 不走 overlay listener（用独立 socket 上的 quic.Listen），
	// 所以入站流不会被 listener 自动 demux。手动启动 demux 把对方开的流接入
	// 本机 http server（与 storeConn 里出站连接的 demux 同律）。
	if n.listener != nil {
		go n.listener.demux(qconn, gotID, peerFp)
	}
	log.Printf("[loomnet/punch] B 侧接受打洞连接成功 peer=%s", peerID)
}

// dialPunchQUIC is the production after-STUN handoff. It takes ownership of
// conn on BOTH success and failure; only QUIC reads after this point.
func dialPunchQUIC(ctx, owner context.Context, conn *net.UDPConn, addr *net.UDPAddr, fp, peerID string, id *Identity) (*punchSession, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	stopOwner := context.AfterFunc(owner, cancel)
	defer stopOwner()
	qt := &quic.Transport{Conn: conn}
	handedOff := false
	defer func() {
		if !handedOff {
			_ = qt.Close()
			_ = conn.Close()
		}
	}()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := owner.Err(); err != nil {
		return nil, err
	}
	s, err := dialQUICOnTransport(ctx, qt, conn, addr, fp, peerID, id)
	if err != nil {
		return nil, err
	}
	if s.RemoteMachineID() != peerID {
		_ = s.Close()
		return nil, fmt.Errorf("loomnet: punch: identity mismatch: expected %s, got %s", peerID, s.RemoteMachineID())
	}
	if err := ctx.Err(); err != nil {
		_ = s.Close()
		return nil, err
	}
	if err := owner.Err(); err != nil {
		_ = s.Close()
		return nil, err
	}
	handedOff = true
	return newPunchSession(owner, s, qt, conn), nil
}

// startLegacyPunch only WRITES. Its stop function joins the worker, so no
// delayed write survives successful handoff, failure, or cancellation.
func startLegacyPunch(ctx context.Context, conn *net.UDPConn, peerAddr *net.UDPAddr) func() {
	pctx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(300 * time.Millisecond)
		defer ticker.Stop()
		for {
			if pctx.Err() != nil {
				return
			}
			if _, err := conn.WriteToUDP(punchPacket, peerAddr); err != nil {
				return
			}
			select {
			case <-pctx.Done():
				return
			case <-ticker.C:
			}
		}
	}()
	return func() { cancel(); <-done }
}
