package loomnet

import (
	"sort"
)

// net_summary.go —— 给 Hub 看的连接概况（随心跳上报）。
//
// Hub 的「我的机器」三枚芯片（直连 / P2P / 中继）与管理员拓扑图过去读的是一个早已
// 删除的 connectivity 字段（客户端曾把它写死成 relay），于是永远灰着、永远「未知」。
// 真实的连接方式是**每一对机器之间**按对端算出来的，只有运行时知道——所以由运行时
// 汇总上报：本机三类方式各自可不可用（判据与对应拨号器的 Explain 同源，不另立一套），
// 以及此刻每条活连接走的是哪种路径。Hub 只展示，不推断。

// NetMethodSummary 是一类连接方式在本机的状态。
type NetMethodSummary struct {
	// Available = 这类方式此刻能用（偏好开着、配置齐了、网络没被封）。
	Available bool `json:"available"`
	// Reason 说明不可用的原因（或可用时的一句说明）。
	Reason string `json:"reason,omitempty"`
	// Active 是此刻正经这类方式通信的活连接数。
	Active int `json:"active"`
}

// NetLinkSummary 是一条活连接：对端机器 + 路径。
type NetLinkSummary struct {
	Peer string `json:"peer"`
	// Path 是 direct / public / reverse / inbound / punch / relay / rendezvous。
	Path string `json:"path"`
	// Via 只对 inbound 有值：对方经局域网（lan）还是公网（public）拨进来。
	Via string `json:"via,omitempty"`
}

// NetSummary 是整份概况。
type NetSummary struct {
	Direct NetMethodSummary `json:"direct"`
	P2P    NetMethodSummary `json:"p2p"`
	Relay  NetMethodSummary `json:"relay"`
	// Links 永远非 nil（JSON 里是数组，不是 null）。
	Links []NetLinkSummary `json:"links"`
}

// maxSummaryLinks 限制上报的连接条数：心跳要小，一台机器同时连着几十台对端已经罕见。
const maxSummaryLinks = 64

// pathKindGroup 把具体路径归到三类方式：
// 直连 = 局域网 / 公网 / 反向公网 / 对方拨入复用；P2P = 打洞；中继 = 中继 / 会合。
func pathKindGroup(path string) string {
	switch path {
	case pathDirect, pathPublic, pathReverse, pathInbound:
		return "direct"
	case pathPunch:
		return "p2p"
	case pathRelay, pathRendezvous:
		return "relay"
	}
	return ""
}

// NetSummary 汇总本机的连接概况。
func (n *Node) NetSummary() NetSummary {
	summary := NetSummary{Links: []NetLinkSummary{}}
	prefs := n.ConnectionPrefs()
	rc := n.RelayConfig()

	// 活连接与路径。
	n.connsMu.Lock()
	ids := make([]string, 0, len(n.conns))
	for id, s := range n.conns {
		if s != nil {
			ids = append(ids, id)
		}
	}
	n.connsMu.Unlock()
	sort.Strings(ids)
	for _, id := range ids {
		path := n.LastPath(id)
		if path == "" {
			continue
		}
		switch pathKindGroup(path) {
		case "direct":
			summary.Direct.Active++
		case "p2p":
			summary.P2P.Active++
		case "relay":
			summary.Relay.Active++
		}
		if len(summary.Links) < maxSummaryLinks {
			link := NetLinkSummary{Peer: id, Path: path}
			if path == pathInbound {
				link.Via = n.ActiveInboundVia(id)
			}
			summary.Links = append(summary.Links, link)
		}
	}

	// 直连：局域网地址或公网直连地址，任一即可。
	eps := n.LocalEndpoints()
	switch {
	case !prefs.DirectEnabled():
		summary.Direct.Reason = "账号「连接偏好」已关闭直接连接"
	case eps.Public != "":
		summary.Direct.Available = true
		summary.Direct.Reason = "局域网直连 + 公网直连（" + eps.Public + "）"
	case len(eps.LAN) > 0:
		summary.Direct.Available = true
		summary.Direct.Reason = "局域网直连（跨网络需对方开公网直连）"
	default:
		summary.Direct.Reason = "本机没有可通告的局域网地址"
	}

	// P2P 打洞：与 punchDialer.Explain 的本机侧判据一致。
	switch {
	case !prefs.P2PEnabled():
		summary.P2P.Reason = "账号「连接偏好」已关闭 P2P 穿透"
	case rc == nil || len(rc.StunAddrs) == 0:
		summary.P2P.Reason = "Hub 没有提供 STUN 观测点"
	case n.loomOfferSenderFn() == nil:
		summary.P2P.Reason = "打洞信令未接入（Hub 信令连接未建立）"
	default:
		if known, blocked, why := n.udpEgressBlocked(); known && blocked {
			summary.P2P.Reason = why
		} else {
			summary.P2P.Available = true
			summary.P2P.Reason = "可经 STUN 探测后打洞直连"
		}
	}

	// 中继：与 relayDialer.Explain 的本机侧判据一致。
	switch {
	case !prefs.RelayEnabled():
		summary.Relay.Reason = "账号「连接偏好」已关闭服务器中继"
	case !rc.HasCoordinate():
		summary.Relay.Reason = "Hub 没有提供中继坐标"
	default:
		summary.Relay.Available = true
		summary.Relay.Reason = "可经 Hub 中继（端到端加密）"
	}
	return summary
}

// evictDisabledPaths 关掉走着「刚被偏好关闭的方式」的活连接。
//
// 偏好只拦**新拨号**：不关掉旧连接的话，关了中继，正在走中继的那条连接照用不误——
// 开关看起来就是没生效。关掉后下一次请求按新偏好重新选路（被关掉的方式不再参与）。
// 只关本机缓存的出站会话；对方拨入的连接（inbound）是对方的选择，不在这里断。
func (n *Node) evictDisabledPaths(prefs *ConnectionPrefs) {
	disabled := map[string]bool{
		"direct": !prefs.DirectEnabled(),
		"p2p":    !prefs.P2PEnabled(),
		"relay":  !prefs.RelayEnabled(),
	}
	if !disabled["direct"] && !disabled["p2p"] && !disabled["relay"] {
		return
	}
	n.connsMu.Lock()
	ids := make([]string, 0, len(n.conns))
	for id := range n.conns {
		ids = append(ids, id)
	}
	n.connsMu.Unlock()
	var victims []string
	for _, id := range ids {
		path := n.LastPath(id)
		if path == pathInbound {
			continue
		}
		if group := pathKindGroup(path); group != "" && disabled[group] {
			victims = append(victims, id)
		}
	}
	for _, id := range victims {
		n.connsMu.Lock()
		if s := n.conns[id]; s != nil {
			delete(n.conns, id)
			_ = s.Close()
		}
		n.connsMu.Unlock()
	}
}
