package loomnet

import (
	"context"
	"errors"
	"fmt"
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

	pathsMu sync.Mutex
	paths   map[string]string

	dials dialGroup

	// Registry holds all pluggable dial methods in priority order.
	Registry *DialerRegistry
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
		opts:     opts,
		identity: id,
		conns:    map[string]Session{},
		paths:    map[string]string{},
		Registry: NewDialerRegistry(),
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

	tr, err := newTransport(n.ctx, n.identity, n.opts.Directory, n.opts.UDPPort)
	if err != nil {
		n.cancel()
		return err
	}
	n.tr = tr

	ln, err := tr.listen()
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

	// Register the built-in dial methods in priority order: LAN direct (10)
	// then 公网直连 (20; dials a peer's explicitly configured public address).
	// Further methods (hole punch, relay, …) are added one at a time via
	// n.Registry.Register() once they meet the production bar — see
	// docs/network-connectivity-redesign.md §8.
	n.Registry.Register(&directDialer{n: n})
	n.Registry.Register(&publicDialer{n: n})

	return nil
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
// (currently only "direct"), or "" if none. It is a memory: it survives the
// session dying. For "what is in use RIGHT NOW" use ActivePath.
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

// livePathCounts tallies how many live peer sessions currently use each path
// kind (currently only "direct").
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
// UI's connection-method badges.
func (n *Node) PeerReachability(ctx context.Context, peerID string) []MethodStatus {
	return n.Registry.PeerReachability(ctx, peerID, n.ActivePath(peerID))
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
		public.Detail = fmt.Sprintf("已配置公网直连地址 %s（本机实际监听 UDP 端口 %d）。任意网络的机器可经此直连本机——请确保该 UDP 端口已在系统防火墙与云安全组放行，且端口转发（如有）指向本机。", eps.Public, eps.UDPPort)
	default:
		public.Detail = "未配置。拥有公网 IP 或已做端口转发的机器（如云服务器）可在 设置→网络与设备→公网直连 开启：固定 UDP 端口并填写公网地址，跨网络的机器即可直连本机。"
	}
	if public.Active {
		public.Detail = fmt.Sprintf("当前有 %d 条活跃连接经此方式通信。", inUse[pathPublic]) + " " + public.Detail
	}

	return []MethodStatus{direct, public}
}

// ctxKeyPeerID keys the verified peer machineID stashed by connContext.
type ctxKeyPeerID struct{}

// connContext stashes each inbound stream's mTLS-verified machineID into the
// request context so serveHandler can stamp a trusted X-Loom-From (§2.4).
func (n *Node) connContext(ctx context.Context, c net.Conn) context.Context {
	if mc, ok := c.(interface{ RemoteMachineID() string }); ok {
		if id := mc.RemoteMachineID(); id != "" {
			return context.WithValue(ctx, ctxKeyPeerID{}, id)
		}
	}
	return ctx
}

// serveHandler wraps LocalHandler, overwriting X-Loom-From with the verified
// peer identity (an unverifiable caller has the header stripped so no spoofed
// value survives), then dispatches to the same mux local requests use.
func (n *Node) serveHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if id, ok := r.Context().Value(ctxKeyPeerID{}).(string); ok && id != "" {
			r.Header.Set(headerLoomFrom, id)
		} else {
			r.Header.Del(headerLoomFrom)
		}
		n.opts.LocalHandler.ServeHTTP(w, r)
	})
}

// localLANIPs enumerates this host's non-loopback, non-link-local unicast IPs
// for the LAN candidate list.
func localLANIPs() []string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return nil
	}
	var out []string
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
	return out
}
