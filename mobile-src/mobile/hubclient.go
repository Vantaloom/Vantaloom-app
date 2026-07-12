package mobile

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"

	"vantaloom.local/loomnetmobile/loomnet"
)

// hubClient is the phone's minimal Hub client. It satisfies loomnet.Directory
// (peer fingerprints + endpoints + the account trust set, from GET
// /api/machines/peers) and loomnet.Signaler (hole-punch loom-offer/loom-answer
// over the signaling WS), and fetches GET /api/overlay/config to build the
// loomnet.RelayConfig. It mirrors the desktop hubconn.Client's overlay role but
// imports only stdlib + gorilla/websocket so the whole package stays
// gomobile-bindable.
type hubClient struct {
	httpBase  string // normalized http(s):// base (no trailing slash)
	wsURL     string // ws(s)://<host>/api/ws/signal
	machineID string
	http      *http.Client

	tokenMu sync.RWMutex
	tok     string

	// Directory cache: same-account peers' pinned fingerprint + dial endpoints,
	// rebuilt on every refreshPeers (full replace so departed peers drop out).
	peerMu      sync.RWMutex
	peerOverlay map[string]overlayPeer

	// overlayProvider reports our own fingerprint + endpoints for the heartbeat;
	// set after the node is built (setOverlayProvider).
	provMu      sync.RWMutex
	overlayProv func() (string, loomnet.Endpoints)

	// Signaler plumbing.
	signalCh chan loomnet.Signal // inbound loom-offer/answer to the node
	sendCh   chan []byte         // outbound SignalMessages to the WS

	stopOnce sync.Once
	done     chan struct{}
}

// overlayPeer is a cached peer's pinned identity + dial endpoints.
type overlayPeer struct {
	fingerprint string
	endpoints   loomnet.Endpoints
}

// signalMessage matches the Hub signaling wire (services/hub .../signaling and
// apps/api/internal/hubconn.SignalMessage) so JSON is byte-compatible.
type signalMessage struct {
	Type    string          `json:"type"`
	From    string          `json:"from,omitempty"`
	To      string          `json:"to,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

// peerMachineInfo is the subset of the Hub GET /api/machines/peers entry the
// overlay needs. overlayEndpoints deserializes straight into loomnet.Endpoints.
type peerMachineInfo struct {
	ID                 string            `json:"id"`
	OverlayFingerprint string            `json:"overlayFingerprint"`
	OverlayEndpoints   loomnet.Endpoints `json:"overlayEndpoints"`
}

// overlayConfigResp mirrors GET /api/overlay/config.
type overlayConfigResp struct {
	RelayQuicAddr    string `json:"relayQuicAddr"`
	RelayWssURL      string `json:"relayWssUrl"`
	RelayFingerprint string `json:"relayFingerprint"`
}

func newHubClient(hubBaseURL, machineID, token string) *hubClient {
	base := strings.TrimRight(upgradeHubScheme(strings.TrimSpace(hubBaseURL)), "/")
	return &hubClient{
		httpBase:    base,
		wsURL:       signalWSURL(base, machineID),
		machineID:   machineID,
		http:        &http.Client{Timeout: 15 * time.Second},
		tok:         token,
		peerOverlay: map[string]overlayPeer{},
		signalCh:    make(chan loomnet.Signal, 64),
		sendCh:      make(chan []byte, 256),
		done:        make(chan struct{}),
	}
}

// ── token ──────────────────────────────────────────────────────────────────

func (c *hubClient) setToken(t string) {
	c.tokenMu.Lock()
	c.tok = t
	c.tokenMu.Unlock()
}

func (c *hubClient) token() string {
	c.tokenMu.RLock()
	defer c.tokenMu.RUnlock()
	return c.tok
}

func (c *hubClient) setOverlayProvider(fn func() (string, loomnet.Endpoints)) {
	c.provMu.Lock()
	c.overlayProv = fn
	c.provMu.Unlock()
}

// ── loomnet.Directory ────────────────────────────────────────────────────────

// PeerInfo returns machineID's pinned fingerprint + endpoints, or ok=false when
// unknown or pre-0.13 (empty fingerprint).
func (c *hubClient) PeerInfo(machineID string) (string, loomnet.Endpoints, bool) {
	c.peerMu.RLock()
	defer c.peerMu.RUnlock()
	p, ok := c.peerOverlay[machineID]
	if !ok || p.fingerprint == "" {
		return "", loomnet.Endpoints{}, false
	}
	return p.fingerprint, p.endpoints, true
}

// AccountFingerprints returns every same-account machine's fingerprint for the
// inbound-handshake trust set. (The phone is client-only, but the set is required
// by the interface and harmless.)
func (c *hubClient) AccountFingerprints() map[string]string {
	c.peerMu.RLock()
	defer c.peerMu.RUnlock()
	out := make(map[string]string, len(c.peerOverlay))
	for id, p := range c.peerOverlay {
		if p.fingerprint != "" {
			out[id] = p.fingerprint
		}
	}
	return out
}

// ── loomnet.Signaler ─────────────────────────────────────────────────────────

// SendSignal enqueues a hole-punch offer/answer to sig.To over the signaling WS.
func (c *hubClient) SendSignal(_ context.Context, sig loomnet.Signal) error {
	msg := signalMessage{Type: sig.Type, To: sig.To, Payload: json.RawMessage(sig.Payload)}
	data, err := json.Marshal(msg)
	if err != nil {
		return fmt.Errorf("mobile: marshal signal: %w", err)
	}
	select {
	case c.sendCh <- data:
		return nil
	default:
		return fmt.Errorf("mobile: signal send buffer full")
	}
}

// Signals returns inbound loom-offer/loom-answer routed to this node. The channel
// lives for the client's lifetime (survives WS reconnects).
func (c *hubClient) Signals() <-chan loomnet.Signal { return c.signalCh }

func (c *hubClient) deliverSignal(sig loomnet.Signal) {
	select {
	case c.signalCh <- sig:
	default:
		log.Printf("[mobile] signal buffer full, dropping %s from %s", sig.Type, sig.From)
	}
}

// ── lifecycle ────────────────────────────────────────────────────────────────

// start launches the signaling-WS, heartbeat, and peer-poll loops for the node's
// lifetime (ctx).
func (c *hubClient) start(ctx context.Context) {
	go c.wsLoop(ctx)
	go c.heartbeatLoop(ctx)
	go c.peerPollLoop(ctx)
}

func (c *hubClient) stop() {
	c.stopOnce.Do(func() { close(c.done) })
}

// ── REST: overlay config, peers, heartbeat ───────────────────────────────────

// buildRelayConfig fetches GET /api/overlay/config and, mirroring the desktop's
// buildRelayConfig, returns a RelayConfig only when the relay fingerprint is set
// (else the overlay runs direct-only until the Hub advertises a configured relay).
func (c *hubClient) buildRelayConfig(ctx context.Context) *loomnet.RelayConfig {
	oc, err := c.fetchOverlayConfig(ctx)
	if err != nil || oc == nil || strings.TrimSpace(oc.RelayFingerprint) == "" {
		return nil
	}
	return &loomnet.RelayConfig{
		QUICAddr:         oc.RelayQuicAddr,
		WSSURL:           oc.RelayWssURL,
		RelayFingerprint: oc.RelayFingerprint,
		JWTProvider:      c.token,
	}
}

func (c *hubClient) fetchOverlayConfig(ctx context.Context) (*overlayConfigResp, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.httpBase+"/api/overlay/config", nil)
	if err != nil {
		return nil, err
	}
	c.authorize(req)
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetch overlay config: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("fetch overlay config: status %d", resp.StatusCode)
	}
	var oc overlayConfigResp
	if err := json.NewDecoder(resp.Body).Decode(&oc); err != nil {
		return nil, fmt.Errorf("decode overlay config: %w", err)
	}
	return &oc, nil
}

// refreshPeers pulls GET /api/machines/peers and rebuilds the Directory cache
// (fingerprint + endpoints, full replace).
func (c *hubClient) refreshPeers(ctx context.Context) error {
	u := c.httpBase + "/api/machines/peers?machineId=" + url.QueryEscape(c.machineID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	c.authorize(req)
	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("fetch peers: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("fetch peers: status %d", resp.StatusCode)
	}
	var peers []peerMachineInfo
	if err := json.NewDecoder(resp.Body).Decode(&peers); err != nil {
		return fmt.Errorf("decode peers: %w", err)
	}

	next := make(map[string]overlayPeer, len(peers))
	for _, p := range peers {
		next[p.ID] = overlayPeer{fingerprint: p.OverlayFingerprint, endpoints: p.OverlayEndpoints}
	}
	c.peerMu.Lock()
	c.peerOverlay = next
	c.peerMu.Unlock()
	return nil
}

// sendHeartbeat posts our overlay fingerprint + endpoints so peers can pin and
// (in principle) dial us. Best-effort.
func (c *hubClient) sendHeartbeat(ctx context.Context) {
	payload := map[string]any{
		"machineId":    c.machineID,
		"status":       "online",
		"connectivity": "relay",
	}
	c.provMu.RLock()
	prov := c.overlayProv
	c.provMu.RUnlock()
	if prov != nil {
		fp, ep := prov()
		if fp != "" {
			payload["overlayFingerprint"] = fp
		}
		payload["overlayEndpoints"] = ep
		if len(ep.LAN) > 0 {
			payload["localIp"] = ep.LAN[0]
		}
	}

	body, _ := json.Marshal(payload)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.httpBase+"/api/machines/heartbeat", bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Content-Type", "application/json")
	c.authorize(req)
	resp, err := c.http.Do(req)
	if err != nil {
		return
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	_ = resp.Body.Close()
}

// authorize attaches the current Hub JWT.
func (c *hubClient) authorize(req *http.Request) {
	if t := c.token(); t != "" {
		req.Header.Set("Authorization", "Bearer "+t)
	}
}

// ── background loops ─────────────────────────────────────────────────────────

func (c *hubClient) heartbeatLoop(ctx context.Context) {
	ticker := time.NewTicker(60 * time.Second)
	defer ticker.Stop()
	c.sendHeartbeat(ctx)
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.done:
			return
		case <-ticker.C:
			c.sendHeartbeat(ctx)
		}
	}
}

func (c *hubClient) peerPollLoop(ctx context.Context) {
	ticker := time.NewTicker(45 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.done:
			return
		case <-ticker.C:
			rc, cancel := context.WithTimeout(ctx, 10*time.Second)
			if err := c.refreshPeers(rc); err != nil {
				log.Printf("[mobile] peer refresh failed: %v", err)
			}
			cancel()
		}
	}
}

// ── signaling WebSocket ──────────────────────────────────────────────────────

// wsLoop keeps one signaling WS alive, reconnecting with capped backoff, until
// ctx or Stop. Inbound loom-offer/loom-answer are routed to the node's Signals();
// SendSignal's queued frames are written by the per-connection write pump.
func (c *hubClient) wsLoop(ctx context.Context) {
	backoff := time.Second
	const maxBackoff = 30 * time.Second
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.done:
			return
		default:
		}

		if c.dialAndServe(ctx) {
			backoff = time.Second // reset after a live connection
		}

		select {
		case <-ctx.Done():
			return
		case <-c.done:
			return
		case <-time.After(backoff):
		}
		if backoff *= 2; backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
}

// dialAndServe runs one WS connection to completion; established reports whether
// the dial succeeded (so the caller can reset backoff).
func (c *hubClient) dialAndServe(ctx context.Context) (established bool) {
	dctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()

	dialer := &websocket.Dialer{HandshakeTimeout: 15 * time.Second}
	hdr := http.Header{}
	if t := c.token(); t != "" {
		hdr.Set("Authorization", "Bearer "+t)
	}
	// The signaling URL carries the token as a query fallback (some proxies drop
	// the Authorization header on Upgrade); the Hub validates it either way.
	conn, _, err := dialer.DialContext(dctx, c.tokenizedWSURL(), hdr)
	if err != nil {
		log.Printf("[mobile] signaling dial failed: %v", err)
		return false
	}
	defer conn.Close()

	connDone := make(chan struct{})
	go c.writePump(conn, connDone)
	c.readPump(conn) // blocks until the connection breaks
	close(connDone)
	return true
}

func (c *hubClient) readPump(conn *websocket.Conn) {
	conn.SetReadDeadline(time.Now().Add(90 * time.Second))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		return nil
	})
	conn.SetPingHandler(func(appData string) error {
		conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		_ = conn.WriteControl(websocket.PongMessage, []byte(appData), time.Now().Add(10*time.Second))
		return nil
	})
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			return
		}
		var msg signalMessage
		if err := json.Unmarshal(data, &msg); err != nil {
			continue
		}
		switch msg.Type {
		case "loom-offer", "loom-answer":
			c.deliverSignal(loomnet.Signal{Type: msg.Type, From: msg.From, Payload: msg.Payload})
		default:
			// presence / relay-data / legacy WebRTC: the phone ignores them.
		}
	}
}

func (c *hubClient) writePump(conn *websocket.Conn, connDone <-chan struct{}) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-connDone:
			return
		case <-c.done:
			return
		case msg := <-c.sendCh:
			conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := conn.WriteMessage(websocket.TextMessage, msg); err != nil {
				return
			}
		case <-ticker.C:
			conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// tokenizedWSURL appends the current token as a query param fallback.
func (c *hubClient) tokenizedWSURL() string {
	t := c.token()
	if t == "" {
		return c.wsURL
	}
	sep := "&"
	if !strings.Contains(c.wsURL, "?") {
		sep = "?"
	}
	return c.wsURL + sep + "token=" + url.QueryEscape(t)
}

// ── URL helpers (stdlib only) ────────────────────────────────────────────────

// signalWSURL derives ws(s)://<host>/api/ws/signal?machineId=<id> from the http
// base.
func signalWSURL(httpBase, machineID string) string {
	ws := httpBase
	switch {
	case strings.HasPrefix(ws, "https://"):
		ws = "wss://" + strings.TrimPrefix(ws, "https://")
	case strings.HasPrefix(ws, "http://"):
		ws = "ws://" + strings.TrimPrefix(ws, "http://")
	}
	return ws + "/api/ws/signal?machineId=" + url.QueryEscape(machineID)
}

// upgradeHubScheme promotes a bare-domain http:// Hub URL to https:// (the
// official Hub is TLS-only behind Cloudflare). Mirrors hubconn.upgradeHubScheme:
// only public domain names with no explicit port are upgraded; localhost, IP
// literals, single-label hosts, and host:port self-hosted Hubs keep their scheme.
func upgradeHubScheme(raw string) string {
	s := strings.TrimSpace(raw)
	if s == "" {
		return s
	}
	parsed, err := url.Parse(s)
	if err != nil || parsed.Host == "" || parsed.Scheme != "http" || parsed.Port() != "" {
		return s
	}
	host := strings.ToLower(parsed.Hostname())
	// localhost, IP literals, and single-label hosts keep http; only a public
	// domain name (dotted, non-IP) is upgraded to https.
	if host == "localhost" || net.ParseIP(host) != nil || !strings.Contains(host, ".") {
		return s
	}
	parsed.Scheme = "https"
	return parsed.String()
}
