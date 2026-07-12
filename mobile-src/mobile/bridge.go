// Package mobile is a gomobile-bindable facade over loomnet.Node for the
// Android (and, later, iOS) client shell. It is compiled to a .aar by
// `gomobile bind` and driven from the Kotlin shell through window.__loomBridge
// (docs/loomnet-design.md §8).
//
// The phone is a CLIENT-ONLY overlay node: it dials peers (Transport) but never
// serves inbound (LocalHandler=nil). This package therefore wraps a loomnet.Node
// plus:
//
//   - a mini Hub client (hubclient.go) implementing loomnet.Directory +
//     loomnet.Signaler and fetching /api/overlay/config → loomnet.RelayConfig;
//   - a 127.0.0.1 loopback HTTP proxy (proxy.go) that rewrites requests to
//     http://<target>.loom<path> and round-trips them over node.Transport(),
//     streaming responses (SSE-safe) and tunnelling WebSocket upgrades.
//
// IMPORT DISCIPLINE: gomobile bind compiles the bound package's whole import
// graph, so this package imports ONLY internal/loomnet + stdlib + gorilla/
// websocket (already a dep of loomnet). It must NOT pull in the rest of
// apps/api (chromedp, sqlite, browsertls, …).
//
// gomobile shape: only Bridge and NewBridge are exported; every method takes/
// returns string/int/bool/error. No maps, slices, channels, or http.Handler
// cross the binding.
package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"vantaloom.local/loomnetmobile/loomnet"
)

// Bridge is the single object the Kotlin shell holds. NewBridge builds it;
// StartNode brings up the overlay node + loopback proxy; Connect points the
// loopback at a peer; StatusJSON reports the connection state.
type Bridge struct {
	// currentTarget is the peer machineID the loopback proxy currently forwards
	// to. Stored in an atomic so the proxy hot path is lock-free. Holds a string.
	currentTarget atomic.Value

	mu     sync.Mutex // guards the fields below + lifecycle transitions
	ctx    context.Context
	cancel context.CancelFunc
	node   *loomnet.Node
	hub    *hubClient
	proxy  *loopbackProxy
	warm   *http.Client // over node.Transport(), for the Connect warm-up dial

	state string // "idle" | "connecting" | "connected" | "error"
	path  string // "direct" | "p2p" | "relay" (last established), for status
	err   string // last error message, for status
}

// NewBridge returns an idle Bridge. Call StartNode before Connect.
func NewBridge() *Bridge {
	b := &Bridge{state: "idle"}
	b.currentTarget.Store("")
	return b
}

// target returns the loopback proxy's current peer machineID ("" = none). It is
// the lock-free func the proxy consults per request.
func (b *Bridge) target() string {
	s, _ := b.currentTarget.Load().(string)
	return s
}

// StartNode builds the overlay node (Directory + Signaler = the mini Hub client;
// RelayConfig from /api/overlay/config; LocalHandler=nil) and starts the loopback
// proxy, returning once the proxy is listening. machineID MUST be the Hub-assigned
// machine.id for this device (the value the Hub peer list keys on and pins for the
// mTLS handshake) — NOT the raw SSAID. The frontend supplies it after registering
// the device (hubAutoRegisterMachine → machine.id). Idempotent: a second call
// while running is a no-op.
func (b *Bridge) StartNode(dataDir, hubBaseURL, machineID, hubToken string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.node != nil {
		return nil // already started
	}
	if strings.TrimSpace(machineID) == "" {
		return errors.New("mobile: machineID is required")
	}
	if strings.TrimSpace(hubBaseURL) == "" {
		return errors.New("mobile: hubBaseURL is required")
	}

	ctx, cancel := context.WithCancel(context.Background())
	hub := newHubClient(hubBaseURL, machineID, hubToken)

	// Populate the Directory (peer fingerprints + endpoints) before the first dial
	// so a Connect issued right after StartNode has candidates. Best-effort: the
	// background poll refreshes it regardless.
	initCtx, initCancel := context.WithTimeout(ctx, 6*time.Second)
	_ = hub.refreshPeers(initCtx)
	relayCfg := hub.buildRelayConfig(initCtx)
	initCancel()

	node, err := loomnet.New(loomnet.Options{
		DataDir:      dataDir,
		MachineID:    machineID,
		Signaler:     hub,
		Directory:    hub,
		RelayConfig:  relayCfg,
		LocalHandler: nil, // client-only: no inbound serving
	})
	if err != nil {
		cancel()
		return err
	}
	if err := node.Start(ctx); err != nil {
		cancel()
		return err
	}

	// Report our overlay fingerprint + endpoints via heartbeat so same-account
	// peers can pin us for the inbound mTLS check (§2.3 verifyInbound).
	hub.setOverlayProvider(func() (string, loomnet.Endpoints) {
		return node.Fingerprint(), node.LocalEndpoints()
	})
	hub.start(ctx) // WS signaling + heartbeat + peer-list poll loops

	proxy := newLoopbackProxy(node, b.target)
	if err := proxy.start(); err != nil {
		node.Stop()
		hub.stop()
		cancel()
		return err
	}

	b.ctx, b.cancel = ctx, cancel
	b.node, b.hub, b.proxy = node, hub, proxy
	b.warm = &http.Client{Transport: node.Transport()}
	b.state, b.path, b.err = "idle", "", ""
	return nil
}

// LoopbackPort is the 127.0.0.1 port the loopback proxy listens on, or 0 before
// StartNode. The Kotlin shell composes http://127.0.0.1:<port> from it.
func (b *Bridge) LoopbackPort() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.proxy == nil {
		return 0
	}
	return b.proxy.port()
}

// Connect points the loopback proxy at machineID and warms the overlay dial so
// the first real request is fast (and so status reflects the tier). It returns an
// error only when the overlay cannot reach the peer at all (dial failure across
// all tiers); any HTTP status from the peer counts as connected.
func (b *Bridge) Connect(machineID string) error {
	b.mu.Lock()
	node, warm := b.node, b.warm
	b.mu.Unlock()
	if node == nil {
		return errors.New("mobile: node not started")
	}
	if strings.TrimSpace(machineID) == "" {
		return errors.New("mobile: machineID is required")
	}

	b.currentTarget.Store(machineID)
	b.setState("connecting", "", "")

	// Warm the dial ladder with a lightweight request. A transport-level error
	// means every tier failed to reach the peer; any HTTP response (even 404)
	// means a session is up.
	ctx, cancel := context.WithTimeout(b.rootCtx(), 12*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, "http://"+machineID+".loom/", nil)
	if err != nil {
		b.setState("error", "", err.Error())
		return err
	}
	resp, err := warm.Do(req)
	if err != nil {
		b.setState("error", "", err.Error())
		return err
	}
	_ = resp.Body.Close()

	b.setState("connected", node.LastPath(machineID), "")
	return nil
}

// Disconnect clears the loopback's target and returns to idle. The overlay node
// and cached sessions stay up (a later Connect reuses them); use Stop to tear the
// node down.
func (b *Bridge) Disconnect() error {
	b.currentTarget.Store("")
	b.setState("idle", "", "")
	return nil
}

// StatusJSON reports the connection state as {"state","path","error"} — path is
// present only when connected, error only in the error state.
func (b *Bridge) StatusJSON() string {
	b.mu.Lock()
	state, path, errMsg := b.state, b.path, b.err
	b.mu.Unlock()

	out := struct {
		State string `json:"state"`
		Path  string `json:"path,omitempty"`
		Error string `json:"error,omitempty"`
	}{State: state}
	if state == "connected" {
		out.Path = path
	}
	if state == "error" {
		out.Error = errMsg
	}
	data, err := json.Marshal(out)
	if err != nil {
		return `{"state":"error","error":"status marshal failed"}`
	}
	return string(data)
}

// SetToken rotates the Hub JWT used by the mini Hub client for REST auth, the
// signaling WS, and the relay JWTProvider. Called from JS onToken as the token
// refreshes.
func (b *Bridge) SetToken(token string) {
	b.mu.Lock()
	hub := b.hub
	b.mu.Unlock()
	if hub != nil {
		hub.setToken(token)
	}
}

// Stop tears the whole node down: loopback proxy, overlay node, Hub client, and
// the root context. The Bridge can be re-used by calling StartNode again.
func (b *Bridge) Stop() {
	b.mu.Lock()
	proxy, node, hub, cancel := b.proxy, b.node, b.hub, b.cancel
	b.proxy, b.node, b.hub, b.cancel, b.ctx = nil, nil, nil, nil, nil
	b.warm = nil
	b.state, b.path, b.err = "idle", "", ""
	b.mu.Unlock()

	b.currentTarget.Store("")
	if proxy != nil {
		proxy.stop()
	}
	if node != nil {
		node.Stop()
	}
	if hub != nil {
		hub.stop()
	}
	if cancel != nil {
		cancel()
	}
}

// setState atomically updates the status triple.
func (b *Bridge) setState(state, path, errMsg string) {
	b.mu.Lock()
	b.state, b.path, b.err = state, path, errMsg
	b.mu.Unlock()
}

// rootCtx returns the node's lifetime context, or Background if not started.
func (b *Bridge) rootCtx() context.Context {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.ctx != nil {
		return b.ctx
	}
	return context.Background()
}
