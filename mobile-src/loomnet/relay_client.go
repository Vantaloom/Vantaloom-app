package loomnet

import (
	"bufio"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"
	quic "github.com/quic-go/quic-go"
)

// relayALPN 是中继 QUIC 连接的 ALPN 标识，与 overlay 直连的 alpn 区分。
const relayALPN = "loom-relay/1"

// relayOpenAckWait 是发送 OPEN 后等待回执（OPEN-OK / OPEN-ERR）的兜底上限。
// 中继必回一帧，正常情况下一个 RTT 就到；这个值只用于中继失联时不无限挂起，
// 且仅在拨号 ctx 没有 deadline 时才用得上（有 deadline 时以 ctx 为准）。
const relayOpenAckWait = 10 * time.Second

// relayHandshakeTimeout 是 B 侧（被叫方）入站 mTLS 握手的显式超时。A 侧拨号
// 的握手走 relayDialer 的 dctx（relayTimeout=15s）；B 侧 handleIncoming 在
// goroutine 里，不能依赖 QUIC idle timeout（30s）间接超时——恶意/损坏对端
// 可借此挂起 B 侧 goroutine（2026-08-11 review 发现）。
const relayHandshakeTimeout = 10 * time.Second

// relayIdleTimeout 是中继控制连接的 QUIC 空闲超时。比 overlay 直连（30s）更
// 宽容：中继链路（本机→Hub relay→对端）跨公网，UDP 抖动窗口可能超过 30s，
// 过短的 idle 会让控制连接在网络抖动时被误判死亡、触发重建（日志里的
// "timeout: no recent network activity"）。QUIC keepalive（15s）仍负责保持
// 活跃，60s 只作为更长久的兜底。
const relayIdleTimeout = 60 * time.Second

// relayMsg 是中继 NDJSON 协议的通用帧结构（每条消息 JSON + '\n'）。
type relayMsg struct {
	Type         string `json:"type"`
	MachineID    string `json:"machineId,omitempty"`
	JWT          string `json:"jwt,omitempty"`
	ObservedAddr string `json:"observedAddr,omitempty"`
	CircuitID    string `json:"circuitId,omitempty"`
	Target       string `json:"target,omitempty"`
	Reason       string `json:"reason,omitempty"`
	From         string `json:"from,omitempty"` // INCOMING（主叫 machineId）
	// Rendezvous 是匿名会合键（tempconn）：双方都没有 Hub 账号时，用它代替
	// machineId + JWT 在中继处配对。见 services/hub/internal/relay/protocol.go。
	Rendezvous string `json:"rz,omitempty"`
}

// relayClient 管理到中继服务器的 QUIC 控制连接，并按需建立电路。同一个中继
// 地址只连一次，多条电路复用同一 QUIC 连接的不同流。中继是 TURN 式密文包转发
// 器：A 连中继，发 OPEN 请求连 B，中继把 A、B 两条流字节级对拼，A↔B 在拼出的
// 流上跑 mTLS + yamux。中继看不到明文。
type relayClient struct {
	mu        sync.Mutex
	quicAddr  string
	wssURL    string // WSS 回退端点（走 Cloudflare Tunnel）
	machineID string
	jwt       string
	identity  *Identity
	dir        Directory                                        // 用于校验入站 A 的指纹（mTLS server 模式）
	onIncoming func(fromMachineID string, s Session)            // B 侧回调：收到 INCOMING 并 ACCEPT 后把入站 Session 交给 node

	// relaySPKI 是中继自签证书公钥的 SPKI 指纹（hex sha256），由 Hub
	// /api/overlay/config 下发（S2 review 2026-08-14）。connectQUIC 用它钉扎
	// 中继 QUIC 连接的 TLS（VerifyPeerCertificate），防 on-path 伪装中继窃取
	// HELLO/OPEN 中的用户 Hub JWT。空 = 旧 Hub 不下发，不钉扎（构造时已打
	// 警告日志，向后兼容）。构造后不可变，锁外读安全。
	relaySPKI string

	// rendezvous 非空 = **匿名会合模式**（tempconn）：HELLO/OPEN/ACCEPT 全部
	// 改用会合键寻址，不带 machineId 也不带 JWT。这是让「两台都没登录 Hub 的
	// 机器」也能互通的唯一途径——它们既拿不到 JWT 去登记控制连接，也收不到反拨
	// 信令，被控端一旦在 NAT 后面就彻底不可达。
	rendezvous string
	// dialKey 是**对端**的会合键，只用于 A 角色拨号（OPEN 与 WSS 升级参数）。
	// 它与 rendezvous 分开是一条硬约束：A 绝不能拿对端的键去 HELLO 登记——中继
	// 的登记是「后来者顶掉先来者」，那样会把对端自己的登记挤掉，谁也连不上。
	dialKey string
	// dialOnly = 只拨号、不登记：跳过 HELLO（A 角色的会合客户端没有身份可报，
	// 也不需要接收呼入）。
	dialOnly bool
	// allowProvisional 只在会合模式下使用：B 侧接受入站会合电路时，对端通常
	// 还没进信任表，握手要走与直连监听器同一套 provisional 校验（见
	// newRelaySessionServerProvisional）。
	allowProvisional func() bool

	// 连接状态机（M6 review 2026-08-14）：状态与锁分离。网络 I/O（connectQUIC/
	// connectWSS，最坏路径 ≈ 30s）在锁外进行，持锁只做状态判断/切换。
	// connecting 是单飞标记：并发 ensureConnected 共享同一次建连（参考 dialGroup
	// 的 dialGroup 模式），等待者 select 锁/ctx 竞争——中继不可达时锁等待不再
	// 无限，relayDialer 的 15s 预算（relayTimeout）可正常击穿，不再被队头阻塞拖挂。
	connecting  bool          // 单飞中（锁内读写）
	connectDone chan struct{} // 单飞完成时关闭（锁内读写）

	conn   *quic.Conn
	udp    *net.UDPConn
	qt     *quic.Transport
	hello  bool // HELLO 已成功
	closed bool
	useWSS bool // 当前使用 WSS 模式（QUIC 不可达时回退）

	// 控制流（HELLO 后持续监听 INCOMING）。HELLO-OK 后不关闭控制流，保存后
	// 启动 serveIncoming goroutine 持续读取并处理 INCOMING 消息。
	// QUIC 模式用 ctrlStream/ctrlBr，WSS 模式用 ctrlWS。
	ctrlStream *quic.Stream
	ctrlBr     *bufio.Reader
	ctrlWS     *websocket.Conn
}

// newRelayClient 创建一个中继客户端（尚未连接，首次 openCircuit 时懒建连）。
// quicAddr 是 QUIC/UDP 公网地址（首选），wssURL 是 WSS 回退端点（走 Cloudflare
// Tunnel）。spki 是中继自签证书公钥的 SPKI 指纹（hex sha256，Hub /api/overlay/
// config 下发），空 = 不钉扎中继 TLS（旧 Hub，向后兼容）——此时打警告日志。
// dir 用于 B 侧校验入站 A 的指纹；onIncoming 是收到 INCOMING 并 ACCEPT 后把
// 入站 Session 交给 node 的回调（B 侧逻辑）。A 侧拨号时不依赖这两个参数。
func newRelayClient(quicAddr, wssURL, machineID, jwt, spki string, id *Identity, dir Directory, onIncoming func(string, Session)) *relayClient {
	if spki == "" {
		log.Printf("[loomnet/relay] 警告：中继未下发 SPKI 指纹（旧 Hub/中继未启用），中继 QUIC 连接不钉扎证书——HELLO/OPEN 里的用户 JWT 有被 on-path 中间人窃取的风险")
	}
	return &relayClient{
		quicAddr:   quicAddr,
		wssURL:     wssURL,
		machineID:  machineID,
		jwt:        jwt,
		relaySPKI:  spki,
		identity:   id,
		dir:        dir,
		onIncoming: onIncoming,
	}
}

// newRendezvousRelayClient 创建一个**匿名会合**中继客户端（tempconn）：不带
// machineId/JWT，改用会合键在中继处配对。key 是会合键（连接码里那把持久密钥的
// sha256）；allowProvisional 供 B 侧接受尚未受信的入站电路（配对窗口）。
func newRendezvousRelayClient(quicAddr, wssURL, spki, key string, id *Identity, dir Directory, allowProvisional func() bool, onIncoming func(string, Session)) *relayClient {
	c := newRelayClient(quicAddr, wssURL, "", "", spki, id, dir, onIncoming)
	c.rendezvous = key
	c.allowProvisional = allowProvisional
	return c
}

// helloMsg 组装 HELLO 帧：会合模式只报会合键，账号模式报 machineId + JWT。
// withJWT=false 用于 WSS（JWT 已在握手 header 里）。
func (c *relayClient) helloMsg(withJWT bool) relayMsg {
	if c.rendezvous != "" {
		return relayMsg{Type: "HELLO", Rendezvous: c.rendezvous}
	}
	msg := relayMsg{Type: "HELLO", MachineID: c.machineID}
	if withJWT {
		msg.JWT = c.jwt
	}
	return msg
}

// openRendezvousKey 是 OPEN/WSS 升级用的会合键：A 角色用对端的键（dialKey），
// 纯 B 角色的客户端偶尔也会主动拨（同键回拨），退回自己的登记键。
func (c *relayClient) openRendezvousKey() string {
	if c.dialKey != "" {
		return c.dialKey
	}
	return c.rendezvous
}

// openMsg 组装 OPEN 帧。会合模式不带 target/jwt/from——被叫方由会合键定位。
func (c *relayClient) openMsg(circuitID, targetMachineID string, withFrom bool) relayMsg {
	if key := c.openRendezvousKey(); key != "" {
		return relayMsg{Type: "OPEN", CircuitID: circuitID, Rendezvous: key}
	}
	msg := relayMsg{Type: "OPEN", CircuitID: circuitID, Target: targetMachineID, JWT: c.jwt}
	if withFrom {
		msg.From = c.machineID
	}
	return msg
}

// acceptMsg 组装 ACCEPT 帧。会合模式用会合键认领电路（中继据此校验认领者）。
func (c *relayClient) acceptMsg(circuitID string) relayMsg {
	if c.rendezvous != "" {
		return relayMsg{Type: "ACCEPT", CircuitID: circuitID, Rendezvous: c.rendezvous}
	}
	return relayMsg{Type: "ACCEPT", CircuitID: circuitID, JWT: c.jwt}
}

// wssDialURL 是 WSS 拨号地址。会合模式没有 JWT 可放进 Authorization 头，改用
// 查询参数 rz 让中继在升级阶段就识别为匿名会合连接。
func (c *relayClient) wssDialURL() string {
	key := c.openRendezvousKey()
	if key == "" {
		return c.wssURL
	}
	sep := "?"
	if strings.Contains(c.wssURL, "?") {
		sep = "&"
	}
	return c.wssURL + sep + "rz=" + url.QueryEscape(key)
}

// ensureConnected 建立到中继的控制连接并完成 HELLO 握手（幂等，已连接则
// 直接返回）。优先 QUIC/UDP，连不上自动回退 WSS（走 Cloudflare Tunnel）。
// 连接断开后会自动重连。
//
// M6 review 2026-08-14：连接状态机与锁分离——网络 I/O（connectQUIC/connectWSS，
// 最坏路径 ≈ 30s：公网拨号 10s + 本地回退 10s + WSS 回退 10s）在锁外进行，
// 持锁只做状态判断/切换。并发调用经 connecting 单飞共享同一次建连（参考
// dialGroup），等待者 select 锁/ctx 竞争——中继不可达时锁等待不再无限，
// relayDialer 的 15s 预算（relayTimeout）可正常击穿，不再被队头阻塞拖挂
// （B 侧 serveRelay 重连与任意 A 侧 openCircuit 的 ensureConnected 不再互相
// 互斥等待）。
func (c *relayClient) ensureConnected(ctx context.Context) error {
	for {
		c.mu.Lock()
		if c.hello && !c.closed && (c.conn != nil || c.useWSS) {
			c.mu.Unlock()
			return nil
		}
		if c.closed {
			c.mu.Unlock()
			return fmt.Errorf("loomnet/relay: 控制连接已关闭")
		}
		if c.connecting {
			// 已有单飞在建连：等它完成（select 锁/ctx 竞争，感知 ctx 的
			// 等待——不无限占锁），完成后重新检查状态。
			done := c.connectDone
			c.mu.Unlock()
			select {
			case <-done:
				continue // 单飞完成，重新检查状态
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		// 成为单飞发起者：连接状态在锁外建立（网络 I/O），完成后锁内提交。
		c.connecting = true
		done := make(chan struct{})
		c.connectDone = done
		c.mu.Unlock()

		err := c.connectWithFallback(ctx)

		// 锁内提交完成状态（先置位再放锁 + close，保证等待者 close 后
		// 拿锁必然看到最新状态），然后唤醒等待者。
		c.mu.Lock()
		c.connecting = false
		c.connectDone = nil
		c.mu.Unlock()
		close(done)
		return err
	}
}

// connectWithFallback 在锁外建立中继控制连接（网络 I/O），成功时锁内提交连接
// 状态并启动 serveIncoming。优先 QUIC/UDP，连不上自动回退 WSS。调用者不得
// 持有 c.mu。
func (c *relayClient) connectWithFallback(ctx context.Context) error {
	// 先尝试 QUIC（首选），连不上再回退 WSS
	var lastErr error
	if c.quicAddr != "" {
		res, err := c.connectQUIC(ctx)
		if err == nil {
			c.mu.Lock()
			c.ctrlStream = res.stream
			c.ctrlBr = res.br
			c.conn = res.conn
			c.udp = res.udp
			c.qt = res.qt
			c.useWSS = false
			c.hello = true
			c.mu.Unlock()
			go c.serveIncoming()
			return nil
		}
		lastErr = err
		log.Printf("[loomnet/relay] QUIC 连接 %s 失败，尝试 WSS 回退: %v", c.quicAddr, err)
	}

	// 回退 WSS（走 Cloudflare Tunnel，穿透 NAT）
	if c.wssURL != "" {
		ws, err := c.connectWSS(ctx)
		if err == nil {
			c.mu.Lock()
			c.ctrlWS = ws
			c.useWSS = true
			c.hello = true
			c.mu.Unlock()
			go c.serveIncoming()
			return nil
		}
		return err
	}

	// 无 WSS 可回退时，把 QUIC 的具体失败原因原样上抛（含 SPKI 钉扎失败等
	// 根因），不吞成笼统的「无可用连接方式」——禁静默兜底。
	if lastErr != nil {
		return lastErr
	}
	return fmt.Errorf("loomnet/relay: 无可用中继连接方式（QUIC 和 WSS 均未配置或失败）")
}

// quicRelayConn 是一次成功建立的 QUIC 中继控制连接的资源集合。
// connectQUIC 在锁外创建并返回，由 connectWithFallback 持锁提交给 relayClient。
type quicRelayConn struct {
	conn   *quic.Conn
	stream *quic.Stream
	br     *bufio.Reader
	udp    *net.UDPConn
	qt     *quic.Transport
}

// connectQUIC 通过 QUIC/UDP 建立中继控制连接并完成 HELLO 握手。网络 I/O，
// 全程不持有 c.mu（M6 review 2026-08-14：锁外建连，锁只做状态提交）。成功时
// 返回已建好的资源集合（未提交给 c.*），调用方须在锁内提交并随后启动
// serveIncoming；失败时已关闭全部临时资源。
func (c *relayClient) connectQUIC(ctx context.Context) (*quicRelayConn, error) {
	// 中继连接是独立的 QUIC 连接，不复用 overlay 的共享 socket（中继地址在
	// 公网，overlay socket 绑在本地）。开一个临时 UDP socket 给中继连接。
	udp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
	if err != nil {
		return nil, fmt.Errorf("loomnet/relay: 绑定中继 UDP socket: %w", err)
	}
	qt := &quic.Transport{Conn: udp}

	addr, err := net.ResolveUDPAddr("udp", c.quicAddr)
	if err != nil {
		udp.Close()
		return nil, fmt.Errorf("loomnet/relay: 解析中继地址 %q: %w", c.quicAddr, err)
	}

	tlsConf, err := c.relayTLSConfig()
	if err != nil {
		udp.Close()
		return nil, err
	}
	quicConf := &quic.Config{
		MaxIdleTimeout:       relayIdleTimeout,
		KeepAlivePeriod:      keepAlivePeriod,
		HandshakeIdleTimeout: handshakeIdle,
	}
	conn, err := qt.Dial(ctx, addr, tlsConf, quicConf)
	if err != nil {
		// NAT 回环（hairpin NAT）回退：客户端和 Hub 可能同一台机器/同一内网，
		// 往公网 IP 发的 UDP 包被 NAT 出去后回不来（很多路由器不支持 hairpin）。
		// 回退用 127.0.0.1:port 直连本地中继监听（Hub 绑 :port 所有接口）。
		localhostAddr := &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: addr.Port}
		conn2, err2 := qt.Dial(ctx, localhostAddr, tlsConf, quicConf)
		if err2 != nil {
			udp.Close()
			return nil, fmt.Errorf("loomnet/relay: 连接中继 %s: %w（回退本地也失败: %v）", c.quicAddr, err, err2)
		}
		conn = conn2
		log.Printf("[loomnet/relay] 公网地址 %s 不可达（NAT 回环），回退本地 127.0.0.1:%d", c.quicAddr, addr.Port)
	}

	// 只拨号的会合客户端不登记：没有身份可报，也不接收呼入。直接把裸连接交回，
	// 电路流在 tryOpenCircuit 里另开（中继对 OPEN 不要求先 HELLO）。
	if c.dialOnly {
		return &quicRelayConn{conn: conn, udp: udp, qt: qt}, nil
	}

	// 开控制流发 HELLO，等 HELLO-OK。
	stream, err := conn.OpenStreamSync(ctx)
	if err != nil {
		conn.CloseWithError(quic.ApplicationErrorCode(1), "open ctrl stream")
		udp.Close()
		return nil, fmt.Errorf("loomnet/relay: 开控制流: %w", err)
	}
	if err := writeRelayMsg(stream, c.helloMsg(true)); err != nil {
		stream.Close()
		conn.CloseWithError(quic.ApplicationErrorCode(1), "hello write")
		udp.Close()
		return nil, fmt.Errorf("loomnet/relay: 发送 HELLO: %w", err)
	}
	br := bufio.NewReader(stream)
	resp, err := readRelayMsg(br)
	if err != nil {
		stream.Close()
		conn.CloseWithError(quic.ApplicationErrorCode(1), "hello read")
		udp.Close()
		return nil, fmt.Errorf("loomnet/relay: 读 HELLO 响应: %w", err)
	}
	switch resp.Type {
	case "HELLO-OK":
		log.Printf("[loomnet/relay] 控制连接已建立（反射地址 %s）", resp.ObservedAddr)
	case "HELLO-ERR":
		stream.Close()
		conn.CloseWithError(quic.ApplicationErrorCode(1), "hello rejected")
		udp.Close()
		return nil, fmt.Errorf("loomnet/relay: 中继拒绝 HELLO：%s", resp.Reason)
	default:
		stream.Close()
		conn.CloseWithError(quic.ApplicationErrorCode(1), "bad hello resp")
		udp.Close()
		return nil, fmt.Errorf("loomnet/relay: HELLO 返回意外消息类型 %q", resp.Type)
	}

	return &quicRelayConn{conn: conn, stream: stream, br: br, udp: udp, qt: qt}, nil
}

// relayTLSConfig 构造中继 QUIC 连接的 TLS 配置。中继 TLS 仅传输层加密；认证
// 靠 HELLO 中的 JWT，端到端安全靠 A↔B 的 mTLS。
//
// S2 review 2026-08-14：中继坐标是公网 host:port（DNS 解析），InsecureSkipVerify
// 之下攻击者可用 on-path 拦截或 DNS 投毒自签证书伪装中继，TLS 1.3 照样完成握手
// 并解密 HELLO/OPEN 里的用户 Hub JWT（7 天有效完整身份凭据）。因此当 Hub 下发
// 中继证书 SPKI 指纹（RelayConfig.RelaySPKI）时，用 VerifyPeerCertificate 钉扎
// 服务端证书的公钥指纹，不匹配拒绝握手。指纹为空（旧 Hub 不下发）时不钉扎、
// 向后兼容（newRelayClient 已打警告日志）。
func (c *relayClient) relayTLSConfig() (*tls.Config, error) {
	tlsConf := &tls.Config{
		InsecureSkipVerify: true,
		NextProtos:         []string{relayALPN},
		MinVersion:         tls.VersionTLS13,
	}
	if c.relaySPKI != "" {
		tlsConf.VerifyPeerCertificate = relaySPKIMatcher(c.relaySPKI)
	}
	return tlsConf, nil
}

// relaySPKIMatcher 构造 VerifyPeerCertificate 回调：校验服务端证书公钥的 SPKI
// 指纹（x509.MarshalPKIXPublicKey 后 sha256，hex 编码）与下发值一致，不匹配
// → 握手失败。InsecureSkipVerify 时该回调仍被调用（verifiedChains 为 nil），
// 指纹比对是唯一信任锚。
func relaySPKIMatcher(expected string) func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
	return func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		if len(rawCerts) == 0 {
			return errors.New("loomnet/relay: 服务端未提供证书，SPKI 钉扎失败")
		}
		cert, err := x509.ParseCertificate(rawCerts[0])
		if err != nil {
			return fmt.Errorf("loomnet/relay: 解析服务端证书: %w", err)
		}
		got, err := publicKeySPKIFingerprint(cert.PublicKey)
		if err != nil {
			return fmt.Errorf("loomnet/relay: 计算服务端证书 SPKI 指纹: %w", err)
		}
		if !strings.EqualFold(got, expected) {
			return fmt.Errorf("loomnet/relay: 中继证书 SPKI 指纹不匹配（期望 %s，实际 %s），疑似中间人伪装", expected, got)
		}
		return nil
	}
}

// publicKeySPKIFingerprint 计算公钥的 SPKI 指纹：x509.MarshalPKIXPublicKey(pub)
// 后 sha256，hex 编码（小写）。与 Hub 侧 relay.Server 下发的中继指纹同算法，
// 客户端用它对中继证书做钉扎比对。
func publicKeySPKIFingerprint(pub any) (string, error) {
	der, err := x509.MarshalPKIXPublicKey(pub)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(der)
	return hex.EncodeToString(sum[:]), nil
}

// connectWSS 通过 WebSocket 建立中继控制连接并完成 HELLO 握手。网络 I/O，
// 全程不持有 c.mu（M6 review 2026-08-14：锁外建连，锁只做状态提交）。WSS 模式
// 下每个 WebSocket 连接 = 一条 relay 流；控制流是持久的 WebSocket，HELLO 后
// 保持连接持续读 INCOMING。JWT 走 Authorization header（不走 URL query——
// token 进 URL 会落在访问日志/浏览器历史/代理缓存，2026-08-11 review P3）。
// 成功时返回已建好的 WebSocket 连接（未提交给 c.*），调用方须在锁内提交并
// 随后启动 serveIncoming。WSS 走标准 HTTPS 证书校验（Cloudflare Tunnel 的合法
// 证书，正常验证），不钉扎 SPKI。
func (c *relayClient) connectWSS(ctx context.Context) (*websocket.Conn, error) {
	dialer := websocket.Dialer{
		HandshakeTimeout: handshakeIdle,
	}
	ws, _, err := dialer.DialContext(ctx, c.wssDialURL(), wssAuthHeader(c.jwt))
	if err != nil {
		return nil, fmt.Errorf("loomnet/relay: WSS 连接 %s: %w", c.wssURL, err)
	}
	if c.dialOnly {
		// 只拨号：不登记控制连接。WSS 每条电路各开一个连接，这个连接本身用不到
		// ——关掉它，让 useWSS 状态成立即可（tryOpenCircuitWSS 会另开）。
		_ = ws.Close()
		return nil, nil
	}
	// 发 HELLO 帧（带 machineId，JWT 已在握手 header 中）
	if err := writeWSSRelayMsg(ws, c.helloMsg(false)); err != nil {
		ws.Close()
		return nil, fmt.Errorf("loomnet/relay: WSS 发送 HELLO: %w", err)
	}
	resp, err := readWSSRelayMsg(ws)
	if err != nil {
		ws.Close()
		return nil, fmt.Errorf("loomnet/relay: WSS 读 HELLO 响应: %w", err)
	}
	switch resp.Type {
	case "HELLO-OK":
		log.Printf("[loomnet/relay] WSS 控制连接已建立（反射地址 %s）", resp.ObservedAddr)
	case "HELLO-ERR":
		ws.Close()
		return nil, fmt.Errorf("loomnet/relay: WSS 中继拒绝 HELLO：%s", resp.Reason)
	default:
		ws.Close()
		return nil, fmt.Errorf("loomnet/relay: WSS HELLO 返回意外消息类型 %q", resp.Type)
	}
	return ws, nil
}

// relayPeerRejected 是中继对**目标机器**的裁决（OPEN-ERR：不在线 / 没接受）。
// 它是在一条**已经握手成功、正在正常收发**的控制连接上收到的，所以它恰好证明
// 我们这一侧是好的——绝不能拿它去重置控制连接。两条传输（QUIC 与 WSS）都返回
// 这个类型；判据只有 peerRejected 一处。
type relayPeerRejected struct{ reason string }

func (e *relayPeerRejected) Error() string {
	return "loomnet/relay: 电路建立失败：" + e.reason
}

// peerRejected 报告一个错误是否是「目标机器的问题」而不是「我们的连接的问题」。
func peerRejected(err error) bool {
	var rejected *relayPeerRejected
	return errors.As(err, &rejected)
}

// openCircuit 开一条电路流，发 OPEN，等待字节管道就绪或 OPEN-ERR。返回的
// net.Conn 是已配对的字节管道（中继把 A↔B 两条流对拼）。**连接**断开时自动重连
// 并重试一次；目标机器的裁决（OPEN-ERR）直接上抛，不重置也不重试。
func (c *relayClient) openCircuit(ctx context.Context, targetMachineID string) (net.Conn, error) {
	if err := c.ensureConnected(ctx); err != nil {
		return nil, err
	}

	pipe, err := c.tryOpenCircuit(ctx, targetMachineID)
	if err == nil {
		return pipe, nil
	}
	// 中继在**健康的控制连接上**回的 OPEN-ERR（「目标机器不在线」/「等待被叫方
	// 接受超时」）是关于**目标机器**的裁决，不是我们这条控制连接的故障——收到它
	// 本身就证明连接是通的。重置只该用在「连接可能已断开」上。
	//
	// 这条分类修的是一次线上事故：控制连接是**全账号共享**的（serveRelay 的
	// INCOMING 监听也骑在它上面），而界面会周期性地对**离线**机器发跨机请求。
	// 每次失败都 reset 之后，日志里是每 4 秒一轮「电路建立失败，重置连接后
	// 重试」，于是一台离线机器把健康 peer 的中继路径一起打断，跨机往返从
	// 162ms 涨到 5.7s、23.6s；重连期间本机也短暂收不到别人经中继拨进来的
	// INCOMING。除此之外这次重试还把一次注定失败的拨号的耗时翻倍。
	if peerRejected(err) {
		return nil, err
	}
	// 连接可能已断开，重置后重试一次。
	log.Printf("[loomnet/relay] 电路建立失败，重置连接后重试：%v", err)
	c.reset()
	if err := c.ensureConnected(ctx); err != nil {
		return nil, err
	}
	return c.tryOpenCircuit(ctx, targetMachineID)
}

// tryOpenCircuit 在当前连接上开一条电路流。QUIC 模式开新 stream 发 OPEN，
// WSS 模式新开 WebSocket 发 OPEN。不处理重连。
func (c *relayClient) tryOpenCircuit(ctx context.Context, targetMachineID string) (net.Conn, error) {
	c.mu.Lock()
	useWSS := c.useWSS
	conn := c.conn
	c.mu.Unlock()

	if useWSS {
		return c.tryOpenCircuitWSS(ctx, targetMachineID)
	}
	if conn == nil {
		return nil, fmt.Errorf("loomnet/relay: 控制连接未建立")
	}

	stream, err := conn.OpenStreamSync(ctx)
	if err != nil {
		return nil, fmt.Errorf("loomnet/relay: 开电路流: %w", err)
	}
	circuitID := uuid.NewString()
	if err := writeRelayMsg(stream, c.openMsg(circuitID, targetMachineID, false)); err != nil {
		stream.Close()
		return nil, fmt.Errorf("loomnet/relay: 发送 OPEN: %w", err)
	}

	// OPEN 之后中继必回一帧：OPEN-OK（配对成功，此后是字节管道）或 OPEN-ERR。
	br := bufio.NewReader(stream)
	return awaitCircuitReady(ctx, br, stream)
}

// tryOpenCircuitWSS 在 WSS 模式下新开一个 WebSocket 发 OPEN。每个 WebSocket
// 连接 = 一条电路流。OPEN 帧需要带 from（客户端 machineId），因为 WSS 模式下
// 服务端无法从连接反查主叫身份。
func (c *relayClient) tryOpenCircuitWSS(ctx context.Context, targetMachineID string) (net.Conn, error) {
	dialer := websocket.Dialer{
		HandshakeTimeout: handshakeIdle,
	}
	ws, _, err := dialer.DialContext(ctx, c.wssDialURL(), wssAuthHeader(c.jwt))
	if err != nil {
		return nil, fmt.Errorf("loomnet/relay: WSS 开电路流: %w", err)
	}
	circuitID := uuid.NewString()
	if err := writeWSSRelayMsg(ws, c.openMsg(circuitID, targetMachineID, true)); err != nil {
		ws.Close()
		return nil, fmt.Errorf("loomnet/relay: WSS 发送 OPEN: %w", err)
	}
	return awaitWSSCircuitReady(ctx, ws)
}

// awaitCircuitReady 读中继对 OPEN 的回执：OPEN-OK = 配对成功，此后本流是
// 字节管道，返回包装 bufio.Reader 的 net.Conn（预读的字节留给后续 TLS）；
// OPEN-ERR = 建立失败，带原因返回。
//
// 读超时取拨号 ctx 的 deadline（没有则用 relayOpenAckWait 兜底）——回执是
// 必达的，等多久由本次拨号的预算说了算，不该由一个写死的猜测窗口说了算。
func awaitCircuitReady(ctx context.Context, br *bufio.Reader, stream *quic.Stream) (net.Conn, error) {
	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(relayOpenAckWait)
	}
	stream.SetReadDeadline(deadline)
	line, err := br.ReadString('\n')
	if err != nil {
		if errors.Is(err, io.EOF) {
			// 电路流被中继关闭（链路中断），不是目标拒绝——openCircuit 会重置
			// 连接后重试。
			return nil, fmt.Errorf("loomnet/relay: 等待电路回执: 中继链路中断（控制连接断开）")
		}
		return nil, fmt.Errorf("loomnet/relay: 等待电路回执: %w", err)
	}
	var msg relayMsg
	if err := json.Unmarshal([]byte(line), &msg); err != nil {
		return nil, fmt.Errorf("loomnet/relay: 解析电路回执: %w", err)
	}
	switch msg.Type {
	case "OPEN-OK":
		stream.SetReadDeadline(time.Time{})
		return &bufferedStreamConn{br: br, stream: stream}, nil
	case "OPEN-ERR":
		return nil, &relayPeerRejected{reason: msg.Reason}
	default:
		return nil, fmt.Errorf("loomnet/relay: 收到意外消息类型 %q（预期 OPEN-OK 或 OPEN-ERR）", msg.Type)
	}
}

// bufferedStreamConn 把 bufio.Reader 中已 peek 的字节保留给后续 TLS 读取，
// 其余操作委托给底层 QUIC 流。quic.Stream 没有 LocalAddr/RemoteAddr，这里
// 返回 nil（TLS 层不依赖地址值，仅用于 ConnectionState 展示）。
type bufferedStreamConn struct {
	br     *bufio.Reader
	stream *quic.Stream
}

func (c *bufferedStreamConn) Read(p []byte) (int, error)       { return c.br.Read(p) }
func (c *bufferedStreamConn) Write(p []byte) (int, error)      { return c.stream.Write(p) }
func (c *bufferedStreamConn) Close() error                     { c.stream.CancelRead(0); return c.stream.Close() }
func (c *bufferedStreamConn) LocalAddr() net.Addr              { return nil }
func (c *bufferedStreamConn) RemoteAddr() net.Addr             { return nil }
func (c *bufferedStreamConn) SetDeadline(t time.Time) error    { return c.stream.SetDeadline(t) }
func (c *bufferedStreamConn) SetReadDeadline(t time.Time) error { return c.stream.SetReadDeadline(t) }
func (c *bufferedStreamConn) SetWriteDeadline(t time.Time) error { return c.stream.SetWriteDeadline(t) }

// wsConn 把 WebSocket 电路连接包装为 net.Conn，用于 WSS 模式的字节管道。
// 首帧（OPEN/ACCEPT NDJSON）后的消息作为字节管道传输（BinaryMessage）。
// Read 跨消息缓冲：一条 WebSocket 消息可能比请求的 p 大或小，剩余字节留到下次。
type wsConn struct {
	ws      *websocket.Conn
	pending []byte // 已读但未消费的消息字节
}

func (c *wsConn) Read(p []byte) (int, error) {
	if len(c.pending) == 0 {
		_, b, err := c.ws.ReadMessage()
		if err != nil {
			return 0, err
		}
		c.pending = b
	}
	n := copy(p, c.pending)
	c.pending = c.pending[n:]
	return n, nil
}

func (c *wsConn) Write(p []byte) (int, error) {
	if err := c.ws.WriteMessage(websocket.BinaryMessage, p); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *wsConn) Close() error                      { return c.ws.Close() }
func (c *wsConn) LocalAddr() net.Addr               { return nil }
func (c *wsConn) RemoteAddr() net.Addr              { return nil }
func (c *wsConn) SetDeadline(t time.Time) error {
	if err := c.ws.SetReadDeadline(t); err != nil {
		return err
	}
	return c.ws.SetWriteDeadline(t)
}
func (c *wsConn) SetReadDeadline(t time.Time) error  { return c.ws.SetReadDeadline(t) }
func (c *wsConn) SetWriteDeadline(t time.Time) error { return c.ws.SetWriteDeadline(t) }

// awaitWSSCircuitReady 是 awaitCircuitReady 的 WSS 版：读中继对 OPEN 的回执
// （OPEN-OK / OPEN-ERR），超时同样取拨号 ctx 的 deadline。
func awaitWSSCircuitReady(ctx context.Context, ws *websocket.Conn) (net.Conn, error) {
	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(relayOpenAckWait)
	}
	ws.SetReadDeadline(deadline)
	_, data, err := ws.ReadMessage()
	ws.SetReadDeadline(time.Time{})
	if err != nil {
		ws.Close()
		return nil, fmt.Errorf("loomnet/relay: WSS 等待电路回执: %w", err)
	}
	var msg relayMsg
	if err := json.Unmarshal(data, &msg); err != nil {
		ws.Close()
		return nil, fmt.Errorf("loomnet/relay: WSS 解析电路回执: %w", err)
	}
	switch msg.Type {
	case "OPEN-OK":
		return &wsConn{ws: ws}, nil
	case "OPEN-ERR":
		ws.Close()
		return nil, &relayPeerRejected{reason: msg.Reason}
	default:
		ws.Close()
		return nil, fmt.Errorf("loomnet/relay: WSS 收到意外消息类型 %q（预期 OPEN-OK 或 OPEN-ERR）", msg.Type)
	}
}

// serveIncoming 持续读控制流，处理 INCOMING 消息。收到 INCOMING 后开新流发
// ACCEPT，在 ACCEPT 流上跑 mTLS(server)+yamux(server) 得到 relaySession，调用
// onIncoming 回调把入站 Session 交给 node。控制流断开时标记需要重连。
// QUIC 模式从 ctrlStream 读 NDJSON，WSS 模式从 ctrlWS 读 TextMessage。
func (c *relayClient) serveIncoming() {
	c.mu.Lock()
	useWSS := c.useWSS
	br := c.ctrlBr
	ws := c.ctrlWS
	c.mu.Unlock()

	if useWSS {
		if ws == nil {
			return
		}
		c.serveIncomingWSS(ws)
		return
	}

	// 捕获 ctrlBr 的本地引用：close/reset 会置空 c.ctrlBr，但本 goroutine
	// 持续使用启动时的 reader 即可（流关闭后 readRelayMsg 会返回错误退出）。
	if br == nil {
		return
	}
	for {
		msg, err := readRelayMsg(br)
		if err != nil {
			// 控制流断开，标记需要重连
			log.Printf("[loomnet/relay] 控制流读取结束: %v", err)
			c.reset()
			return
		}
		if msg.Type != "INCOMING" {
			continue // 忽略非 INCOMING 消息
		}
		go c.handleIncoming(msg.From, msg.CircuitID)
	}
}

// serveIncomingWSS 在 WSS 模式下从控制 WebSocket 读 NDJSON 帧，
// 处理 INCOMING 消息。逻辑与 serveIncoming 的 QUIC 分支一致，只是读取方式不同。
func (c *relayClient) serveIncomingWSS(ws *websocket.Conn) {
	for {
		msg, err := readWSSRelayMsg(ws)
		if err != nil {
			log.Printf("[loomnet/relay] WSS 控制流读取结束: %v", err)
			c.reset()
			return
		}
		if msg.Type != "INCOMING" {
			continue // 忽略非 INCOMING 消息
		}
		go c.handleIncoming(msg.From, msg.CircuitID)
	}
}

// handleIncoming 处理一条 INCOMING：开新流发 ACCEPT，在流上跑 mTLS(server)+
// yamux(server)，调用 onIncoming 把入站 Session 交给 node。
// QUIC 模式开新 stream 发 ACCEPT，WSS 模式新开 WebSocket 发 ACCEPT。
func (c *relayClient) handleIncoming(fromID, circuitID string) {
	c.mu.Lock()
	useWSS := c.useWSS
	conn := c.conn
	c.mu.Unlock()

	if useWSS {
		c.handleIncomingWSS(fromID, circuitID)
		return
	}
	if conn == nil {
		return
	}
	stream, err := conn.OpenStreamSync(context.Background())
	if err != nil {
		log.Printf("[loomnet/relay] 开 ACCEPT 流失败: %v", err)
		return
	}
	if err := writeRelayMsg(stream, c.acceptMsg(circuitID)); err != nil {
		stream.Close()
		log.Printf("[loomnet/relay] 发送 ACCEPT 失败: %v", err)
		return
	}
	// ACCEPT 后流变成字节管道，在它上面跑 mTLS(server) + yamux(server)。
	pipe := &bufferedStreamConn{br: bufio.NewReader(stream), stream: stream}
	// 显式握手超时：恶意/损坏对端不能靠 Background() 挂起本 goroutine（P1 review）。
	hctx, hcancel := context.WithTimeout(context.Background(), relayHandshakeTimeout)
	defer hcancel()
	sess, err := c.serverHandshake(hctx, pipe, fromID)
	if err != nil {
		log.Printf("[loomnet/relay] 入站 mTLS 握手失败 (%s): %v", fromID, err)
		stream.Close()
		return
	}
	if c.onIncoming != nil {
		c.onIncoming(sess.RemoteMachineID(), sess)
	}
}

// serverHandshake 跑 B 侧入站握手。账号模式按 Directory 里 fromID 的指纹钉扎；
// 会合模式（tempconn）对端多半还没进信任表，改走 provisional 校验，身份从证书里读
// ——中继转发的 from 字段在会合模式下本来就是空的，更不该被采信。
func (c *relayClient) serverHandshake(ctx context.Context, pipe net.Conn, fromID string) (*relaySession, error) {
	if c.rendezvous != "" {
		return newRelaySessionServerProvisional(ctx, pipe, c.identity, c.dir, c.allowProvisional)
	}
	if c.dir == nil {
		return nil, fmt.Errorf("loomnet/relay: 收到 %s 的 INCOMING 但无 Directory", fromID)
	}
	fp, _, ok := c.dir.PeerInfo(fromID)
	if !ok {
		return nil, fmt.Errorf("loomnet/relay: 收到 %s 的 INCOMING 但无其指纹", fromID)
	}
	return newRelaySessionServer(ctx, pipe, fp, fromID, c.identity)
}

// handleIncomingWSS 在 WSS 模式下处理一条 INCOMING：新开 WebSocket 发 ACCEPT，
// 在 WebSocket 字节管道上跑 mTLS(server)+yamux(server)，调用 onIncoming 把入站
// Session 交给 node。
func (c *relayClient) handleIncomingWSS(fromID, circuitID string) {
	dialer := websocket.Dialer{
		HandshakeTimeout: handshakeIdle,
	}
	ws, _, err := dialer.DialContext(context.Background(), c.wssDialURL(), wssAuthHeader(c.jwt))
	if err != nil {
		log.Printf("[loomnet/relay] WSS 开 ACCEPT 流失败: %v", err)
		return
	}
	if err := writeWSSRelayMsg(ws, c.acceptMsg(circuitID)); err != nil {
		ws.Close()
		log.Printf("[loomnet/relay] WSS 发送 ACCEPT 失败: %v", err)
		return
	}
	// ACCEPT 后 WebSocket 变成字节管道，在它上面跑 mTLS(server) + yamux(server)。
	pipe := &wsConn{ws: ws}
	// 显式握手超时：与 QUIC 分支同律（P1 review）。
	hctx, hcancel := context.WithTimeout(context.Background(), relayHandshakeTimeout)
	defer hcancel()
	sess, err := c.serverHandshake(hctx, pipe, fromID)
	if err != nil {
		log.Printf("[loomnet/relay] WSS 入站 mTLS 握手失败 (%s): %v", fromID, err)
		ws.Close()
		return
	}
	fromID = sess.RemoteMachineID()
	if c.onIncoming != nil {
		c.onIncoming(fromID, sess)
	}
}

// reset 关闭当前连接（QUIC 或 WSS），标记需要重连。
func (c *relayClient) reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.hello = false
	c.useWSS = false
	c.ctrlStream = nil
	c.ctrlBr = nil
	if c.ctrlWS != nil {
		c.ctrlWS.Close()
		c.ctrlWS = nil
	}
	if c.conn != nil {
		c.conn.CloseWithError(quic.ApplicationErrorCode(0), "reset")
		c.conn = nil
	}
	if c.qt != nil {
		c.qt.Close()
		c.qt = nil
	}
	if c.udp != nil {
		c.udp.Close()
		c.udp = nil
	}
}

// close 关闭中继控制连接（QUIC 或 WSS）和 UDP socket。
func (c *relayClient) close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.closed = true
	c.hello = false
	c.useWSS = false
	c.ctrlStream = nil
	c.ctrlBr = nil
	if c.ctrlWS != nil {
		c.ctrlWS.Close()
		c.ctrlWS = nil
	}
	if c.conn != nil {
		c.conn.CloseWithError(quic.ApplicationErrorCode(0), "closed")
		c.conn = nil
	}
	if c.qt != nil {
		c.qt.Close()
		c.qt = nil
	}
	if c.udp != nil {
		c.udp.Close()
		c.udp = nil
	}
}

// writeRelayMsg 编码一条 NDJSON 帧（JSON + '\n'）并写入 w。
func writeRelayMsg(w io.Writer, msg relayMsg) error {
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	data = append(data, '\n')
	_, err = w.Write(data)
	return err
}

// readRelayMsg 从 br 读取一条 NDJSON 帧并解码。
func readRelayMsg(br *bufio.Reader) (relayMsg, error) {
	line, err := br.ReadString('\n')
	if err != nil {
		return relayMsg{}, err
	}
	var msg relayMsg
	if err := json.Unmarshal([]byte(line), &msg); err != nil {
		return relayMsg{}, fmt.Errorf("解析中继消息: %w", err)
	}
	return msg, nil
}

// isTimeoutErr 判断错误是否为网络超时。
func isTimeoutErr(err error) bool {
	if ne, ok := err.(net.Error); ok && ne.Timeout() {
		return true
	}
	return false
}

// wssAuthHeader 构造 WSS 握手的 Authorization header（Bearer JWT）。
// JWT 走 header 而非 URL query：query 里的 token 会进 URL（访问日志/浏览器
// 历史/代理缓存）造成泄露——2026-08-11 review P3。服务端 wssBearerToken
// 同时接受 header（优先）与 query（老客户端向后兼容）。
func wssAuthHeader(jwt string) http.Header {
	h := http.Header{}
	if jwt != "" {
		h.Set("Authorization", "Bearer "+jwt)
	}
	return h
}

// writeWSSRelayMsg 编码一条 NDJSON 帧并通过 WebSocket 发送（TextMessage）。
func writeWSSRelayMsg(ws *websocket.Conn, msg relayMsg) error {
	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}
	return ws.WriteMessage(websocket.TextMessage, data)
}

// readWSSRelayMsg 从 WebSocket 读取一条 NDJSON 帧并解码。
// 接受 TextMessage 和 BinaryMessage 两种消息类型——服务端 wsStream 的
// WriteText 发 TextMessage，但做兼容处理以防混合实现。
func readWSSRelayMsg(ws *websocket.Conn) (relayMsg, error) {
	_, data, err := ws.ReadMessage()
	if err != nil {
		return relayMsg{}, err
	}
	var msg relayMsg
	if err := json.Unmarshal(data, &msg); err != nil {
		return relayMsg{}, fmt.Errorf("loomnet/relay: 解析 WSS 中继消息: %w", err)
	}
	return msg, nil
}
