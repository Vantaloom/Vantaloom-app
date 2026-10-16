package loomnet

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

// 两项「本机网络体检」（#129）：梯队里有几档在某些网络上**注定失败**，却每次都要
// 等满自己的超时才让位——一次重连白等二十多秒，期间对端看到的就是「掉线」。
//
//  1. 出站 UDP 探测。中国大量网络（校园网、企业网、部分移动网络）除 DNS 外一律丢弃
//     出站 UDP。那里打洞（12s 预算）与公网直连（4s，QUIC/UDP）不可能成功。向 Hub
//     的两个 STUN 观测点各发两次 Binding，一个回应都没有 ⇒ 判「出站 UDP 被封」，
//     这两档对所有对端立刻让位，并把原因写在拓扑里。换了网络（笔记本从学校回家）
//     不能一直判死：被封的结论 2 分钟后重测，通的结论 10 分钟后重测。
//  2. 本机公网直连地址自检。地址是用户手填的（端口转发 / frp），转发一停它就死了，
//     而本机照样请对方反拨（9s）。经 Hub 从公网测一下这个地址：测不通就不再请对方
//     反拨，原因同样写在拓扑里；测通了自动恢复。
//
// 两项都**只读缓存、异步刷新**：Explain 跑在拓扑渲染里，不能为它等几秒。唯一的
// 同步探测在打洞 Dial 开头（≤3s），用来把「第一次就白等 12 秒」压成 3 秒。

const (
	udpEgressOKTTL      = 10 * time.Minute
	udpEgressBlockedTTL = 2 * time.Minute
	udpEgressBudget     = 3 * time.Second
	udpEgressAttempt    = 1400 * time.Millisecond

	selfPublicOKTTL   = 15 * time.Minute
	selfPublicFailTTL = 3 * time.Minute
	selfPublicBudget  = 15 * time.Second
)

// SelfPublicProber 经 Hub 从公网测活本机的公网直连地址（Hub 的 /api/overlay/probe）。
type SelfPublicProber func(ctx context.Context, addr string) (reachable bool, detail string, err error)

type netHealth struct {
	mu sync.Mutex

	udpCheckedAt time.Time
	udpBlocked   bool
	udpReason    string
	udpProbing   bool

	pubAddr      string
	pubCheckedAt time.Time
	pubOK        bool
	pubReason    string
	pubProbing   bool
	prober       SelfPublicProber
}

// SetSelfPublicProber 接上公网地址自检（运行时在登录 Hub 后接线；nil = 不自检）。
func (n *Node) SetSelfPublicProber(fn SelfPublicProber) {
	n.health.mu.Lock()
	n.health.prober = fn
	n.health.pubCheckedAt = time.Time{}
	n.health.mu.Unlock()
	n.refreshSelfPublicAsync()
}

// udpEgressBlocked 读缓存：known=false 表示还没测过（按「通」处理）。过期则后台重测。
func (n *Node) udpEgressBlocked() (known, blocked bool, reason string) {
	n.health.mu.Lock()
	checked := n.health.udpCheckedAt
	blocked, reason = n.health.udpBlocked, n.health.udpReason
	n.health.mu.Unlock()
	if checked.IsZero() || time.Since(checked) > udpEgressTTL(blocked) {
		n.refreshUDPEgressAsync()
	}
	return !checked.IsZero(), blocked, reason
}

// ensureUDPEgress 在打洞前同步确认一次（缓存新鲜就直接用）。
func (n *Node) ensureUDPEgress(ctx context.Context) (blocked bool, reason string) {
	n.health.mu.Lock()
	checked := n.health.udpCheckedAt
	blocked, reason = n.health.udpBlocked, n.health.udpReason
	n.health.mu.Unlock()
	if !checked.IsZero() && time.Since(checked) <= udpEgressTTL(blocked) {
		return blocked, reason
	}
	return n.probeUDPEgress(ctx)
}

func udpEgressTTL(blocked bool) time.Duration {
	if blocked {
		return udpEgressBlockedTTL
	}
	return udpEgressOKTTL
}

func (n *Node) refreshUDPEgressAsync() {
	n.health.mu.Lock()
	if n.health.udpProbing {
		n.health.mu.Unlock()
		return
	}
	n.health.udpProbing = true
	n.health.mu.Unlock()
	go func() {
		ctx := context.Background()
		if n.ctx != nil {
			ctx = n.ctx
		}
		_, _ = n.probeUDPEgress(ctx)
	}()
}

// probeUDPEgress 向 Hub 的 STUN 观测点并行发 Binding（每点两次）。任一回应 ⇒ 通。
// 没有观测点（自建 Hub 没配 / 还没拿到坐标）时不下结论。
func (n *Node) probeUDPEgress(ctx context.Context) (blocked bool, reason string) {
	defer func() {
		n.health.mu.Lock()
		n.health.udpProbing = false
		n.health.mu.Unlock()
	}()
	rc := n.RelayConfig()
	if rc == nil || len(rc.StunAddrs) == 0 {
		return false, ""
	}
	ctx, cancel := context.WithTimeout(ctx, udpEgressBudget)
	defer cancel()

	type result struct {
		ok  bool
		err error
	}
	results := make(chan result, len(rc.StunAddrs))
	for _, addr := range rc.StunAddrs {
		go func(addr string) {
			var lastErr error
			for attempt := 0; attempt < 2 && ctx.Err() == nil; attempt++ {
				conn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4zero, Port: 0})
				if err != nil {
					lastErr = err
					break
				}
				actx, acancel := context.WithTimeout(ctx, udpEgressAttempt)
				_, err = stunBinding(actx, conn, addr)
				acancel()
				conn.Close()
				if err == nil {
					results <- result{ok: true}
					return
				}
				lastErr = fmt.Errorf("%s: %w", addr, err)
			}
			results <- result{err: lastErr}
		}(addr)
	}
	var errs []error
	ok := false
	for range rc.StunAddrs {
		got := <-results
		if got.ok {
			ok = true
			cancel()
			break
		}
		if got.err != nil {
			errs = append(errs, got.err)
		}
	}
	// 调用方自己取消（节点关闭 / 拨号放弃）不是网络证据，不落结论。
	if !ok && ctx.Err() != nil && !errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return false, ""
	}
	if ok {
		reason = ""
	} else {
		reason = fmt.Sprintf("本机所在网络屏蔽了出站 UDP（Hub 的 STUN 观测点 %s 均无回应）——校园网、企业网常见。"+
			"打洞与公网直连都走 UDP，不可能成功，直接走中继（中继已有不经 Cloudflare 的 TCP 直连）。换了网络会自动重测。",
			strings.Join(rc.StunAddrs, "、"))
	}
	n.health.mu.Lock()
	changed := n.health.udpCheckedAt.IsZero() || n.health.udpBlocked != !ok
	n.health.udpCheckedAt = time.Now()
	n.health.udpBlocked = !ok
	n.health.udpReason = reason
	n.health.mu.Unlock()
	if changed {
		if ok {
			log.Printf("[loomnet/health] 出站 UDP 正常（STUN 有回应）")
		} else {
			log.Printf("[loomnet/health] 出站 UDP 被封：%v", errors.Join(errs...))
		}
	}
	return !ok, reason
}

// selfPublicStatus 读本机公网直连地址的自检结论：known=false = 没测过 / 没配置 /
// 没接 Hub。过期则后台重测。
func (n *Node) selfPublicStatus() (known, ok bool, reason string) {
	addr := strings.TrimSpace(n.opts.PublicAdvertise)
	if addr == "" {
		return false, false, ""
	}
	n.health.mu.Lock()
	sameAddr := n.health.pubAddr == addr
	checked := n.health.pubCheckedAt
	ok, reason = n.health.pubOK, n.health.pubReason
	n.health.mu.Unlock()
	ttl := selfPublicFailTTL
	if ok {
		ttl = selfPublicOKTTL
	}
	if !sameAddr || checked.IsZero() || time.Since(checked) > ttl {
		n.refreshSelfPublicAsync()
	}
	if !sameAddr || checked.IsZero() {
		return false, false, ""
	}
	return true, ok, reason
}

// SelfPublicStatus 是 selfPublicStatus 的导出版（拓扑里本机那一行用）。
func (n *Node) SelfPublicStatus() (known, ok bool, reason string) {
	return n.selfPublicStatus()
}

func (n *Node) refreshSelfPublicAsync() {
	addr := strings.TrimSpace(n.opts.PublicAdvertise)
	n.health.mu.Lock()
	prober := n.health.prober
	if addr == "" || prober == nil || n.health.pubProbing {
		n.health.mu.Unlock()
		return
	}
	n.health.pubProbing = true
	n.health.mu.Unlock()
	go func() {
		defer func() {
			n.health.mu.Lock()
			n.health.pubProbing = false
			n.health.mu.Unlock()
		}()
		ctx := context.Background()
		if n.ctx != nil {
			ctx = n.ctx
		}
		ctx, cancel := context.WithTimeout(ctx, selfPublicBudget)
		defer cancel()
		reachable, detail, err := prober(ctx, addr)
		if err != nil {
			// 探测本身没做成（Hub 不通 / 没登录）不是「地址死了」的证据：不落结论，
			// 但留一行日志——否则「自检为什么一直没结果」无从查起。
			log.Printf("[loomnet/health] 本机公网直连地址 %s 自检未完成（下次需要时重试）: %v", addr, err)
			return
		}
		reason := ""
		if !reachable {
			if strings.TrimSpace(detail) == "" {
				detail = "Hub 从公网连不上"
			}
			reason = fmt.Sprintf("本机公网直连地址 %s 测活失败（%s）——端口转发 / frp 可能已停。对方反拨也连不上，暂不请对方反拨；地址恢复后自动启用。", addr, detail)
		}
		n.health.mu.Lock()
		changed := n.health.pubAddr != addr || n.health.pubCheckedAt.IsZero() || n.health.pubOK != reachable
		n.health.pubAddr = addr
		n.health.pubCheckedAt = time.Now()
		n.health.pubOK = reachable
		n.health.pubReason = reason
		n.health.mu.Unlock()
		if changed {
			log.Printf("[loomnet/health] 本机公网直连地址 %s 自检：reachable=%v %s", addr, reachable, detail)
		}
	}()
}
