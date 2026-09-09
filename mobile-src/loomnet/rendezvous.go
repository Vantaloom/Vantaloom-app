package loomnet

// 匿名会合（rendezvous）——临时连接的第五种连接方式（0.15.37）。
//
// 要解决的死局：临时连接（tempconn）的整个前提是**被控端没有登录 Hub**。没有
// 账号就没有 JWT，于是它既登记不了中继控制连接，也收不到反向公网直连的信令。
// 前四种方式（局域网直连 / 公网直连 / 反向公网直连 / 中继）在这种机器上一条都
// 走不通——只要它在 NAT 后面，主控端就永远拨不进去，连接码给得再对也没用。
//
// 会合模式补上这最后一环：双方拿同一把**会合键**在中继处配对。中继看到的只有
// 这把键的哈希，看不到密钥本身，也就没法冒充任何一方去兑换；配对出来的仍然是
// 一条字节管道，A 与 B 在其上跑 mTLS（A 按连接码里的 SPKI 指纹钉扎 B），所以
// 中继即便想插到中间也只能拒绝服务，冒不了名。
//
// 键的来源与寿命见 internal/tempconn：它随连接码下发、随兑换持久化，因此授信
// 之后的日常流量也走同一条会合通道，不必每次都重新要码。

import (
	"context"
	"errors"
	"fmt"
	"log"
	"time"
)

// rendezvousTimeout 是会合拨号的单次预算。与中继同量级：链路形态一样（本机→
// 中继→对端），只是配对方式不同。
const rendezvousTimeout = relayTimeout

// SetRendezvousKey 设置本机自己的会合键（B 角色）：非空即在中继登记、等待主控端
// 呼入；空则停止登记。key 变化时旧登记立即作废重建。可在 Start 前后调用。
func (n *Node) SetRendezvousKey(key string) {
	n.rzMu.Lock()
	if n.rzKey == key {
		n.rzMu.Unlock()
		n.ensureServeRendezvous()
		return
	}
	n.rzKey = key
	if n.rzServer != nil {
		n.rzServer.close()
		n.rzServer = nil
		n.rzServerFp = ""
	}
	n.rzMu.Unlock()
	n.ensureServeRendezvous()
}

// RendezvousKey 返回本机当前的会合键（诊断用）。
func (n *Node) RendezvousKey() string {
	n.rzMu.Lock()
	defer n.rzMu.Unlock()
	return n.rzKey
}

// rendezvousCoords 返回中继坐标（会合与中继共用同一套坐标）。第二个返回值为
// false 表示坐标不可用。**刻意不看 JWT**：坐标是公开信息，未登录 Hub 的机器
// 经公开端点也能取到，而会合本来就不需要账号。
func (n *Node) rendezvousCoords() (*RelayConfig, bool) {
	n.relayMu.Lock()
	rc := n.opts.RelayConfig
	n.relayMu.Unlock()
	if rc == nil || (rc.QuicAddr == "" && rc.WSSUrl == "") {
		return nil, false
	}
	return rc, true
}

// coordFingerprint 是坐标部分的指纹（不含 JWT）：坐标变了才需要重建会合客户端，
// JWT 轮换与会合无关——把 JWT 算进去会让每次令牌刷新都白白重建一遍会合连接。
func coordFingerprint(rc *RelayConfig) string {
	if rc == nil {
		return ""
	}
	return rc.QuicAddr + "|" + rc.WSSUrl + "|" + rc.RelaySPKI
}

// rendezvousServer 返回（必要时创建）B 角色的常驻登记客户端。无会合键或无坐标
// 时返回 nil。
func (n *Node) rendezvousServer() *relayClient {
	rc, ok := n.rendezvousCoords()
	if !ok {
		return nil
	}
	fp := coordFingerprint(rc)

	n.rzMu.Lock()
	defer n.rzMu.Unlock()
	if n.rzKey == "" {
		if n.rzServer != nil {
			n.rzServer.close()
			n.rzServer = nil
			n.rzServerFp = ""
		}
		return nil
	}
	if n.rzServer != nil {
		if n.rzServerFp == fp {
			return n.rzServer
		}
		n.rzServer.close()
		n.rzServer = nil
	}
	n.rzServer = newRendezvousRelayClient(rc.QuicAddr, rc.WSSUrl, rc.RelaySPKI, n.rzKey,
		n.identity, n.opts.Directory, n.opts.ProvisionalGate, n.adoptRendezvousInbound)
	n.rzServerFp = fp
	return n.rzServer
}

// rendezvousDialClient 返回（必要时创建）拨向 peerKey 的 A 角色客户端。电路是
// 这个客户端某条连接上的一条流，必须与会话同寿，所以按对端会合键缓存、不随手关。
func (n *Node) rendezvousDialClient(peerKey string) (*relayClient, error) {
	rc, ok := n.rendezvousCoords()
	if !ok {
		return nil, errors.New("loomnet/rendezvous: 中继坐标不可用")
	}
	fp := coordFingerprint(rc)

	n.rzMu.Lock()
	defer n.rzMu.Unlock()
	if n.rzCoordFp != fp {
		// 坐标变了：旧拨号客户端全部作废（它们连的是旧地址）。
		for _, c := range n.rzDialers {
			c.close()
		}
		n.rzDialers = nil
		n.rzCoordFp = fp
	}
	if n.rzDialers == nil {
		n.rzDialers = map[string]*relayClient{}
	}
	if c := n.rzDialers[peerKey]; c != nil {
		return c, nil
	}
	c := newRendezvousRelayClient(rc.QuicAddr, rc.WSSUrl, rc.RelaySPKI, "",
		n.identity, n.opts.Directory, n.opts.ProvisionalGate, nil)
	// 只拨号、不登记：拿对端的键去 HELLO 会把对端自己的登记顶掉（中继是后来者
	// 覆盖），谁也别想连上。
	c.dialKey = peerKey
	c.dialOnly = true
	n.rzDialers[peerKey] = c
	return c, nil
}

// closeRendezvous 关闭所有会合客户端（Stop 时调用）。
func (n *Node) closeRendezvous() {
	n.rzMu.Lock()
	defer n.rzMu.Unlock()
	if n.rzServer != nil {
		n.rzServer.close()
		n.rzServer = nil
		n.rzServerFp = ""
	}
	for _, c := range n.rzDialers {
		c.close()
	}
	n.rzDialers = nil
}

// adoptRendezvousInbound 接入一条会合入站会话（B 角色）。与 adoptRelayInbound
// 的区别只有一处，但那处是安全边界：**未受信的对端绝不进出站缓存**——它自报的
// CN 会污染本机发往同名机器的流量（与直连监听器 provisional 分支同律）。入站
// 服务照常启动，serveHandler 会逐请求重算信任，未受信者只够得到兑换端点。
func (n *Node) adoptRendezvousInbound(fromID string, s Session) {
	rs, ok := s.(*relaySession)
	if !ok {
		log.Printf("[loomnet/rendezvous] 非 *relaySession 类型 (%T)，忽略", s)
		s.Close()
		return
	}
	if n.inboundTrusted(rs.RemoteMachineID(), rs.RemoteFingerprint()) {
		n.storeConn(rs.RemoteMachineID(), rs, pathInbound)
	} else {
		log.Printf("[loomnet/rendezvous] 接受未受信对端 %s 的会合电路（仅可访问兑换端点）", rs.RemoteMachineID())
	}
	ln := newRelayInboundListener(rs)
	go func() { _ = n.httpSrv.Serve(ln) }()
}

// ensureServeRendezvous 在 node 已启动且会合键与坐标齐备时，确保 B 侧登记循环
// 只启动一次（与 ensureServeRelay 同模式：未启动时静默跳过，由 Start 尾部补）。
func (n *Node) ensureServeRendezvous() {
	if !n.started.Load() {
		return
	}
	if _, ok := n.rendezvousCoords(); !ok {
		return
	}
	if n.rzServing.CompareAndSwap(false, true) {
		go n.serveRendezvousLoop()
	}
}

// serveRendezvousLoop 维护 B 侧的会合登记。没有会合键时空转等待（键随信任表/
// 配对窗口变化而设置），有键则保持登记、断线重连。
func (n *Node) serveRendezvousLoop() {
	backoff := time.Second
	for {
		select {
		case <-n.ctx.Done():
			return
		default:
		}
		client := n.rendezvousServer()
		if client == nil {
			// 没有会合键（或坐标暂时不可用）：等待配置到位。
			select {
			case <-n.ctx.Done():
				return
			case <-time.After(2 * time.Second):
			}
			continue
		}
		if err := client.ensureConnected(n.ctx); err != nil {
			log.Printf("[loomnet/rendezvous] 会合登记失败：%v，%v 后重试", err, backoff)
			select {
			case <-n.ctx.Done():
				return
			case <-time.After(backoff):
			}
			backoff = min(backoff*2, 30*time.Second)
			continue
		}
		backoff = time.Second
		select {
		case <-n.ctx.Done():
			return
		case <-time.After(10 * time.Second):
		}
	}
}

// rendezvousKeyFor 查对端的会合键（provider 由 server 层接 tempconn 注入）。
func (n *Node) rendezvousKeyFor(peerID string) string {
	fn := n.opts.RendezvousKeyFor
	if fn == nil {
		return ""
	}
	return fn(peerID)
}

// rendezvousDialer 是**临时连接会合**方式（Priority 50，梯队最后一级）：双方都
// 没有 Hub 账号时，用连接码里共享的会合键在中继处配对。排在中继（40）之后是因为
// 它只适用于临时连接的对端——账号内的机器走前面四种方式已经够了。
type rendezvousDialer struct{ n *Node }

func (d *rendezvousDialer) Name() string          { return pathRendezvous }
func (d *rendezvousDialer) Label() string         { return "临时连接会合" }
func (d *rendezvousDialer) Priority() int         { return 50 }
func (d *rendezvousDialer) Budget() time.Duration { return rendezvousTimeout }

func (d *rendezvousDialer) Available(ctx context.Context, peerID string) bool {
	ok, _ := d.Explain(ctx, peerID)
	return ok
}

func (d *rendezvousDialer) Explain(_ context.Context, peerID string) (bool, string) {
	if d.n.peerKnownOffline(peerID) {
		return false, offlinePeerReason
	}
	if _, ok := d.n.rendezvousCoords(); !ok {
		return false, "中继坐标不可用（Hub 不可达或中继未启用），无法经会合连接临时设备。"
	}
	if d.n.rendezvousKeyFor(peerID) == "" {
		return false, "该对端没有临时连接会合通道。会合键随连接码下发、兑换时保存——请对方生成新的连接码并重新兑换（双方均需 ≥0.15.37）。"
	}
	if fp, _, ok := d.n.opts.Directory.PeerInfo(peerID); !ok || fp == "" {
		return false, "没有对端的 overlay 指纹，无法钉扎 mTLS。请重新用连接码建立信任。"
	}
	return true, "将经 Hub 中继与对方会合建立密文转发电路（中继只见密文，两端 mTLS 端到端加密，指纹取自连接码）。"
}

func (d *rendezvousDialer) Dial(ctx context.Context, peerID string) (Session, error) {
	key := d.n.rendezvousKeyFor(peerID)
	if key == "" {
		return nil, errors.New("loomnet/rendezvous: 该对端没有会合键")
	}
	fp, _, ok := d.n.opts.Directory.PeerInfo(peerID)
	if !ok || fp == "" {
		return nil, fmt.Errorf("loomnet/rendezvous: 无 %s 的指纹", peerID)
	}
	client, err := d.n.rendezvousDialClient(key)
	if err != nil {
		return nil, err
	}

	dctx, cancel := context.WithTimeout(ctx, rendezvousTimeout)
	defer cancel()

	pipe, err := client.openCircuit(dctx, peerID)
	if err != nil {
		return nil, err
	}
	sess, err := newRelaySession(dctx, pipe, fp, peerID, d.n.identity)
	if err != nil {
		return nil, err
	}
	// 与 relayDialer 同律：发起方也要把入站流接进 http server，否则对端经同一
	// 电路开过来的流无人处理、反向请求永久超时（0.15.34 三机联调实锤）。
	ln := newRelayInboundListener(sess)
	go func() { _ = d.n.httpSrv.Serve(ln) }()
	return sess, nil
}
