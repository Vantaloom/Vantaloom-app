package loomnet

// PresenceDirectory 是 Directory 的**可选**扩展：报告某台对端此刻在不在线。
// 在线的唯一口径与全系统一致 —— Hub 信令 WS 是否存活（见
// docs/network-connectivity-redesign.md）。
//
// 为什么拨号阶梯需要它：reverse / punch / relay / rendezvous 这四档**结构上都
// 要求对方配合**——反拨要 Hub 把信令送到对方、打洞要对方回 answer、中继要对方
// 在中继上登记过。对方信令都断了，这四档一定失败，可它们的预算加起来是
// 9+12+15+15 = 51 秒。界面又会周期性地对离线机器发跨机请求，于是每一次都要
// 烧掉这些预算，而浏览器对同一 origin 只有 6 条连接 —— 那些注定失败的长请求
// 把槽位占着，健康机器的请求跟着一起排队（用户报的「延迟高达一分钟」）。
//
// 两条纪律：
//   - **不知道就不跳过**（known=false 时一律照常拨）。取不到清单 ≠ 清单为空，
//     这是本仓反复踩过的坑；宁可多花几秒，不可把能连的说成连不上。
//   - **本机 Hub 链路断开时，presence 一律未知**。否则我们自己掉线会把所有对端
//     判成离线，连局域网直连都会被误伤——而那一档恰恰不需要 Hub。
type PresenceDirectory interface {
	// PeerOnline 返回 (在线, 是否可判定)。
	PeerOnline(machineID string) (online bool, known bool)
}

// peerKnownOffline 只在「确定对方离线」时为真。Directory 没实现 PresenceDirectory
// （测试、纯 tempconn 场景、旧实现）时恒为假 —— 行为与加这个扩展之前一模一样。
func (n *Node) peerKnownOffline(machineID string) bool {
	dir, ok := n.opts.Directory.(PresenceDirectory)
	if !ok {
		return false
	}
	online, known := dir.PeerOnline(machineID)
	return known && !online
}

// offlinePeerReason 是这四档在对方离线时给出的统一说明。连接报告直接显示它，
// 所以它必须说清楚「为什么这一档现在没意义」，而不是笼统的「不可用」。
const offlinePeerReason = "对方当前离线（Hub 信令连接已断开）。这条方式需要对方在线配合，本机不再为它浪费拨号预算；对方上线后自动恢复。局域网直连不受影响，仍会尝试。"
