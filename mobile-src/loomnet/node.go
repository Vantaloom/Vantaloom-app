package loomnet

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"
)

// headerLoomFrom carries the mTLS-verified caller machineID into the local mux.
// It occupies the semantic slot of today's spoofable X-Relay-From but its value
// is now cryptographically trusted (§2.4).
const headerLoomFrom = "X-Loom-From"

// Signal is one message on the peer-to-peer signaling channel (§5), used to
// exchange hole-punch offers/answers. For punching, Payload is a JSON
// signalPayload.
type Signal struct {
	Type    string // signalOffer | signalAnswer
	From    string // sender machineID (set by the Signaler on inbound)
	To      string // target machineID (set by the sender on outbound)
	Payload []byte // JSON-encoded signalPayload
}

// Signaler carries small typed control messages between peers over the Hub
// signaling WS (§5). The real implementation lands in P2 (hubconn); tests use an
// in-memory double.
type Signaler interface {
	// SendSignal delivers sig to sig.To. It must not block indefinitely.
	SendSignal(ctx context.Context, sig Signal) error
	// Signals returns inbound signals addressed to this node. The channel stays
	// open for the node's lifetime.
	Signals() <-chan Signal
}

// Directory supplies per-peer overlay metadata and the account trust set,
// sourced from the Hub machine list (§4.1, §2.2). The real implementation lands
// in P2.
type Directory interface {
	// PeerInfo returns machineID's pinned SPKI fingerprint and dial endpoints,
	// or ok=false if the peer is unknown.
	PeerInfo(machineID string) (fingerprint string, endpoints Endpoints, ok bool)
	// AccountFingerprints maps every same-account machineID to its SPKI
	// fingerprint; the listener verifies inbound peers against it (§2.3). It is
	// called fresh on every inbound handshake.
	AccountFingerprints() map[string]string
}

// RelayTransport is the injected relay-tier dependency (§4.4) plus the
// STUN-free reflexive-address observer used by hole punching (§4.3). It may be
// nil: with no relay, both the relay tier and hole punching are skipped and the
// ladder is direct-only. The real implementation lands in a later task.
type RelayTransport interface {
	// DialViaRelay returns a Session to target via the Hub relay (inner mTLS
	// over a spliced reliable stream); the relay only forwards ciphertext.
	DialViaRelay(ctx context.Context, target string) (Session, error)
	// ObservedAddr is this node's reflexive "ip:port" as seen by the relay
	// control connection, or "" if unavailable (then punching is skipped).
	ObservedAddr() string
}

// Endpoints are a machine's overlay dial candidates (§4.1). LAN entries are bare
// IPs (paired with UDPPort) or "ip:port"; Public is the reflexive "ip:port";
// UDPPort is the overlay UDP socket port (the revived, now-real peer_port).
type Endpoints struct {
	LAN     []string `json:"lan"`
	Public  string   `json:"public"`
	UDPPort int      `json:"udpPort"`
}

// RelayConfig builds the in-package relay client (§4.4–4.5) when Options.Relay is
// nil. The client keeps a persistent control connection to the Hub relay over the
// SHARED transport/socket (so ObservedAddr is the reflexive address hole punching
// uses), pins the relay's outer cert, and speaks the relay wire protocol for both
// dial directions. When both QUICAddr and WSSURL are empty the relay tier — and,
// lacking a reflexive observer, hole punching — is disabled.
type RelayConfig struct {
	// QUICAddr is the relay's public QUIC/UDP address ("host:port"): the primary
	// control/circuit transport, dialed over the shared socket. Empty → QUIC off.
	QUICAddr string
	// WSSURL is the relay's WSS fallback ("wss://host/api/loom-relay"), used when
	// QUIC/UDP is blocked (§4.5). Empty → no WSS fallback.
	WSSURL string
	// RelayFingerprint pins the relay's outer self-signed cert: base64(sha256(SPKI))
	// of its leaf (the value the relay prints as "Cert SPKI: sha256/<base64>").
	// Required whenever QUICAddr is set.
	RelayFingerprint string
	// JWTProvider returns the current Hub JWT, re-read per connection since tokens
	// rotate. Required. The relay derives the account (userID) from it and pairs
	// only same-account machines.
	JWTProvider func() string
}

// Options configures a Node.
type Options struct {
	DataDir      string         // identity key + caches under <DataDir>/loomnet
	MachineID    string         // this machine's stable overlay identity (cert CN)
	Signaler     Signaler       // hole-punch signaling; nil disables punching
	Directory    Directory      // peer metadata + account trust set (required)
	Relay        RelayTransport // injected relay tier + reflexive observer; wins over RelayConfig when non-nil (tests)
	RelayConfig  *RelayConfig   // build the in-package relay client when Relay is nil; nil (with nil Relay) disables the relay tier
	LocalHandler http.Handler   // inbound overlay requests are served by this mux
}

// Node is the process-local overlay endpoint: one shared QUIC/UDP socket that
// both dials peers (Transport) and serves inbound peer requests (Listener),
// every connection authenticated by mutual TLS fingerprint pinning.
type Node struct {
	opts     Options
	identity *Identity

	ctx    context.Context
	cancel context.CancelFunc

	tr       *transport
	listener *quicListener
	httpSrv  *http.Server
	rt       *http.Transport
	strategy punchStrategy

	connsMu sync.Mutex
	conns   map[string]Session

	pathsMu sync.Mutex
	paths   map[string]string

	dials dialGroup

	waitersMu sync.Mutex
	waiters   map[string]chan Signal // machineID → pending loom-answer
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
	id, err := LoadOrCreateIdentity(opts.DataDir, opts.MachineID)
	if err != nil {
		return nil, err
	}
	n := &Node{
		opts:     opts,
		identity: id,
		strategy: coneStrategy{},
		conns:    map[string]Session{},
		paths:    map[string]string{},
		waiters:  map[string]chan Signal{},
	}
	n.rt = &http.Transport{
		DialContext:           n.dialStream,
		MaxIdleConns:          64,
		IdleConnTimeout:       idleTimeout,
		ExpectContinueTimeout: time.Second,
	}
	return n, nil
}

// Start binds the shared UDP socket, starts the inbound listener (served by
// LocalHandler), and wires signaling for hole punching. The ctx bounds the
// node's lifetime.
func (n *Node) Start(ctx context.Context) error {
	n.ctx, n.cancel = context.WithCancel(ctx)

	tr, err := newTransport(n.ctx, n.identity, n.opts.Directory)
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

	// Relay tier. An injected RelayTransport (tests) wins; otherwise build the
	// in-package relayClient from RelayConfig so it shares this transport/socket,
	// identity, directory, and the serve path. With neither, the relay tier (and,
	// lacking a reflexive observer, hole punching) stays disabled.
	if n.opts.Relay == nil && n.opts.RelayConfig != nil {
		rc, err := newRelayClient(relayDeps{
			acceptCtx: n.ctx,
			tr:        n.tr,
			identity:  n.identity,
			dir:       n.opts.Directory,
			serve:     n.serveRelaySession,
			cfg:       *n.opts.RelayConfig,
		})
		if err != nil {
			n.cancel()
			_ = n.httpSrv.Close()
			_ = ln.Close()
			tr.close()
			return err
		}
		n.opts.Relay = rc // the ladder, punch, and LocalEndpoints now see it
		rc.start()
	}

	if n.opts.Signaler != nil {
		go n.signalPump()
	}
	return nil
}

// serveRelaySession serves a responder relay session's inbound streams through
// the SAME http.Server as direct QUIC streams, by adapting the session into a
// net.Listener whose conns carry the mTLS-verified peer id. Reusing n.httpSrv
// (Handler=n.serveHandler, ConnContext=n.connContext) means relayed requests hit
// the identical mux and get the identical trusted X-Loom-From stamping (§3.3,
// §4.4, §2.4). The Serve call returns when the session dies; n.httpSrv.Close
// tears any live ones down at Stop.
func (n *Node) serveRelaySession(s Session) {
	ln := newSessionListener(s, n.listener.Addr())
	go func() { _ = n.httpSrv.Serve(ln) }()
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

// LocalEndpoints reports this node's overlay dial candidates for the Hub
// heartbeat (§6.2): local LAN IPs, the bound UDP port, and (if a relay observer
// is wired) the reflexive public address.
func (n *Node) LocalEndpoints() Endpoints {
	ep := Endpoints{LAN: localLANIPs()}
	if n.tr != nil {
		ep.UDPPort = n.tr.localUDPAddr().Port
	}
	if n.opts.Relay != nil {
		ep.Public = n.opts.Relay.ObservedAddr()
	}
	return ep
}

// LastPath reports the tier that last established the cached connection to
// machineID ("direct"/"p2p"/"relay"), or "" if none — for the topology UI
// (§7.4).
func (n *Node) LastPath(machineID string) string {
	n.pathsMu.Lock()
	defer n.pathsMu.Unlock()
	return n.paths[machineID]
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
