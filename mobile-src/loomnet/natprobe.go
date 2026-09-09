package loomnet

// NAT mapping observations on a dedicated, pre-QUIC UDP socket. Different
// mappings demonstrate destination dependence; equal mappings (especially two
// ports on one server IP) do NOT establish filtering behavior or punchability.
// QUIC+mTLS, not this classification, proves connectivity.
// STUN 协议（RFC 5389 子集）：20 字节 header（2 type + 2 length + 4 magic +
// 12 txn id），Binding Request type=0x0001 length=0。响应含 XOR-MAPPED-ADDRESS
// 属性（type=0x0020），port^magicHi16 / ip^magic 防 NAT 重写。

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"time"
)

const (
	stunMagic    = 0x2112A442
	stunTypeReq  = 0x0001
	stunTypeResp = 0x0101
	stunAttrXor  = 0x0020
	stunHdrLen   = 20
	stunFamily4  = 0x01
	stunTimeout  = 5 * time.Second
)

// NATType 是探测到的 NAT 类型。
type NATType int

const (
	NATUnknown   NATType = iota // 映射/过滤行为证据不足；仍可尝试 QUIC
	NATCone                     // legacy enum; basic binding probes cannot prove cone filtering
	NATSymmetric                // 已观察到目标相关映射，不代表所有方向必败
	NATOpen                     // reflexive == local，未证明入站过滤放行
)

func (t NATType) String() string {
	switch t {
	case NATCone:
		return "cone NAT"
	case NATSymmetric:
		return "symmetric NAT"
	case NATOpen:
		return "本地与反射地址相同（入站可达性未验证）"
	default:
		return "未知（映射/过滤行为证据不足）"
	}
}

// ReflexiveInfo 是 STUN 探测结果：reflexive 公网地址 + NAT 类型。
type ReflexiveInfo struct {
	NATType     NATType
	ReflexiveIP net.IP
	// ReflexivePort 是第一个观测点返回的 reflexive port（打洞用这个地址）。
	ReflexivePort int
}

// probeNAT uses a dedicated socket before QUIC owns its reads. One successful
// observation still yields a candidate, but never a fabricated cone verdict.
func probeNAT(ctx context.Context, conn *net.UDPConn, stunAddrs []string) (ReflexiveInfo, error) {
	if len(stunAddrs) == 0 {
		return ReflexiveInfo{NATType: NATUnknown}, errors.New("loomnet/nat: 无 STUN 观测点")
	}

	// 探测第一个观测点（主 reflexive 地址，打洞用这个）
	r1, err := stunBinding(ctx, conn, stunAddrs[0])
	if err != nil {
		return ReflexiveInfo{NATType: NATUnknown}, fmt.Errorf("STUN 探测 %s: %w", stunAddrs[0], err)
	}

	// 单点成功只提供候选，不能推导 NAT 类型。
	if len(stunAddrs) == 1 {
		return classifyNAT(conn, r1, nil), ctx.Err()
	}

	// 探测第二个观测点
	r2, err := stunBinding(ctx, conn, stunAddrs[1])
	if err != nil {
		// 第二点失败可保留候选；父 context 结束则不能继续发 offer。
		return classifyNAT(conn, r1, nil), ctx.Err()
	}

	return classifyNAT(conn, r1, r2), ctx.Err()
}

// classifyNAT 对比两次 reflexive 地址判定 NAT 类型。
func classifyNAT(conn *net.UDPConn, r1, r2 *net.UDPAddr) ReflexiveInfo {
	local := conn.LocalAddr().(*net.UDPAddr)
	// reflexive == local → 无 NAT（公网直连）
	if r1.IP.Equal(local.IP) && r1.Port == local.Port {
		return ReflexiveInfo{NATType: NATOpen, ReflexiveIP: r1.IP, ReflexivePort: r1.Port}
	}
	// Equal mappings cannot distinguish address-dependent mapping or filtering.
	if r2 == nil || (r1.Port == r2.Port && r1.IP.Equal(r2.IP)) {
		return ReflexiveInfo{NATType: NATUnknown, ReflexiveIP: r1.IP, ReflexivePort: r1.Port}
	}
	return ReflexiveInfo{NATType: NATSymmetric, ReflexiveIP: r1.IP, ReflexivePort: r1.Port}
}

// stunBinding 从 conn 发 STUN Binding Request 到 stunAddr，等待 Binding Response，
// 解析 XOR-MAPPED-ADDRESS 返回 reflexive 公网地址。
func stunBinding(ctx context.Context, conn *net.UDPConn, stunAddr string) (*net.UDPAddr, error) {
	// The per-point budget includes name resolution, not only socket reads.
	ctx, cancel := context.WithTimeout(ctx, stunTimeout)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	addrs, err := publicCandidates(ctx, stunAddr)
	if err != nil {
		return nil, fmt.Errorf("解析 STUN 地址 %q: %w", stunAddr, err)
	}
	addr, err := stunIPv4Candidate(addrs)
	if err != nil {
		return nil, fmt.Errorf("STUN 地址 %q: %w", stunAddr, err)
	}

	// 构造 Binding Request：type=0x0001, length=0, magic, 12 字节随机 txn id
	req := make([]byte, stunHdrLen)
	binary.BigEndian.PutUint16(req[0:2], stunTypeReq)
	binary.BigEndian.PutUint16(req[2:4], 0) // 无属性
	binary.BigEndian.PutUint32(req[4:8], stunMagic)
	txnID := req[8:20]
	if _, err := rand.Read(txnID); err != nil {
		return nil, fmt.Errorf("生成 txn id: %w", err)
	}

	// min(parent deadline, 5s); cancellation wakes the blocking read promptly.
	// Join the callback BEFORE clearing deadlines/handing this socket to QUIC.
	deadline := time.Now().Add(stunTimeout)
	if parent, ok := ctx.Deadline(); ok && parent.Before(deadline) {
		deadline = parent
	}
	_ = conn.SetDeadline(deadline)
	wakeDone := make(chan struct{})
	stopWake := context.AfterFunc(ctx, func() {
		_ = conn.SetDeadline(time.Now())
		close(wakeDone)
	})
	defer func() {
		if !stopWake() {
			<-wakeDone
		}
		_ = conn.SetDeadline(time.Time{})
	}()
	if err := ctx.Err(); err != nil {
		return nil, err
	}

	if _, err := conn.WriteToUDP(req, addr); err != nil {
		return nil, fmt.Errorf("发送 STUN 请求: %w", err)
	}

	// 读响应（可能收到 QUIC 包等无关包，循环跳过直到拿到匹配的 STUN 响应）
	buf := make([]byte, 1500)
	for {
		n, raddr, err := conn.ReadFromUDP(buf)
		if err != nil {
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			return nil, fmt.Errorf("读 STUN 响应: %w", err)
		}
		resp := buf[:n]
		if !isSTUNResp(resp) {
			continue // 非 STUN 响应（QUIC 包等），跳过
		}
		// 校验 txn id 匹配
		if len(resp) < stunHdrLen {
			continue
		}
		respTxn := resp[8:20]
		if !equalBytes(respTxn, txnID) {
			continue
		}
		reflexive, err := parseXorMappedAddr(resp)
		if err != nil {
			return nil, fmt.Errorf("解析 XOR-MAPPED-ADDRESS: %w", err)
		}
		_ = raddr // STUN 响应来源不是 reflexive 地址（是 STUN 服务器地址）
		return reflexive, nil
	}
}

// stunIPv4Candidate is STUN-specific: this binding parser only supports IPv4
// XOR-MAPPED-ADDRESS. DNS may list AAAA before A; generic QUIC candidates must
// remain dual-stack, so restrict the family here rather than in publicCandidates.
func stunIPv4Candidate(addrs []*net.UDPAddr) (*net.UDPAddr, error) {
	for _, addr := range addrs {
		if addr != nil && addr.IP.To4() != nil {
			return addr, nil
		}
	}
	return nil, errors.New("不支持 IPv6-only STUN 观测点（需要 IPv4 地址）")
}

// isSTUNResp 判断是否为 STUN Binding Success Response（type=0x0101）。
func isSTUNResp(b []byte) bool {
	if len(b) < stunHdrLen {
		return false
	}
	if b[0] >= 0x40 {
		return false // QUIC
	}
	if binary.BigEndian.Uint16(b[0:2]) != stunTypeResp {
		return false
	}
	return binary.BigEndian.Uint32(b[4:8]) == stunMagic
}

// parseXorMappedAddr 从 STUN 响应解析 XOR-MAPPED-ADDRESS 属性，返回 reflexive 地址。
func parseXorMappedAddr(resp []byte) (*net.UDPAddr, error) {
	if len(resp) < stunHdrLen {
		return nil, errors.New("响应过短")
	}
	payloadLen := binary.BigEndian.Uint16(resp[2:4])
	off := stunHdrLen
	end := stunHdrLen + int(payloadLen)
	if end > len(resp) {
		end = len(resp)
	}
	for off+4 <= end {
		attrType := binary.BigEndian.Uint16(resp[off : off+2])
		attrLen := binary.BigEndian.Uint16(resp[off+2 : off+4])
		valStart := off + 4
		valEnd := valStart + int(attrLen)
		if valEnd > end {
			break
		}
		if attrType == stunAttrXor && attrLen >= 8 {
			// XOR-MAPPED-ADDRESS: 1 reserved + 1 family + 2 port^cookieHi + 4 ip^cookie
			family := resp[valStart+1]
			if family != stunFamily4 {
				off = valEnd
				continue // IPv6 未支持，跳过
			}
			xorPort := binary.BigEndian.Uint16(resp[valStart+2 : valStart+4])
			port := int(xorPort) ^ int(stunMagic>>16)
			cookieBytes := make([]byte, 4)
			binary.BigEndian.PutUint32(cookieBytes, stunMagic)
			ip := make(net.IP, 4)
			for i := 0; i < 4; i++ {
				ip[i] = resp[valStart+4+i] ^ cookieBytes[i]
			}
			return &net.UDPAddr{IP: ip, Port: port}, nil
		}
		// 4 字节对齐
		off = valEnd
		if pad := off % 4; pad != 0 {
			off += 4 - pad
		}
	}
	return nil, errors.New("未找到 XOR-MAPPED-ADDRESS 属性")
}

func equalBytes(a, b []byte) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
