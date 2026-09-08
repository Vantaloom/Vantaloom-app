package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"vantaloom.local/loomnetmobile/loomnet"
)

const configTimeout = 6 * time.Second
const configInterval = 30 * time.Second
const configMaxBytes = 256 << 10

type overlayBundle struct {
	Relay *struct {
		QuicAddr  string   `json:"quicAddr"`
		WSSURL    string   `json:"wssUrl"`
		StunAddrs []string `json:"stunAddrs"`
		RelaySPKI string   `json:"relaySpki"`
	} `json:"relay"`
	Connection *loomnet.ConnectionPrefs `json:"connection"`
}

type relayStatus struct {
	Configured    bool     `json:"configured"`
	HasCoordinate bool     `json:"hasCoordinate"`
	ConfigKnown   bool     `json:"configKnown"`
	StunAddrs     []string `json:"stunAddrs"`
	LastError     string   `json:"lastError,omitempty"`
}

// bindNode is called once, before any worker starts.
func (c *hubClient) bindNode(n *loomnet.Node) {
	c.node = n
	n.SetLoomOfferSender(c.sendLoomOffer)
	c.handleOffer = func(peer, addr string) (string, error) {
		// HandlePunchOfferB in older independent loomnet copies reads opts without
		// relayMu. Serialize that read with this bridge's config publication.
		c.applyMu.Lock()
		defer c.applyMu.Unlock()
		if !n.ConnectionPrefs().P2PEnabled() {
			return "", errors.New("punch disabled")
		}
		return n.HandlePunchOfferB(peer, addr)
	}
}

// All returned errors are fixed text/status codes: never expose a response body,
// request URL, Authorization header, or JWT from a transport error.
func (c *hubClient) fetchConfig(ctx context.Context, token string) (*overlayBundle, error) {
	ctx, cancel := context.WithTimeout(ctx, configTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.httpBase+"/api/overlay/config", nil)
	if err != nil {
		return nil, errors.New("overlay config: invalid Hub URL")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, errors.New("overlay config: request failed or timed out")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("overlay config: HTTP %d", resp.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, configMaxBytes+1))
	if err != nil {
		return nil, errors.New("overlay config: read failed")
	}
	if len(data) > configMaxBytes {
		return nil, errors.New("overlay config: response too large")
	}
	var bundle *overlayBundle
	if json.Unmarshal(data, &bundle) != nil || bundle == nil {
		return nil, errors.New("overlay config: invalid JSON")
	}
	return bundle, nil
}

func (c *hubClient) refreshConfig(ctx context.Context) {
	c.tokenMu.RLock()
	token, generation := c.tok, c.generation
	c.tokenMu.RUnlock()
	bundle, err := c.fetchConfig(ctx, token)
	// Never block token rotation on B-side NAT probing. Take applyMu first.
	c.applyMu.Lock()
	defer c.applyMu.Unlock()
	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()
	select {
	case <-c.done:
		return
	case <-ctx.Done():
		return
	default:
	}
	if generation != c.generation {
		return
	}
	if err != nil {
		c.configErr = err.Error()
	} else {
		var rc *loomnet.RelayConfig
		if r := bundle.Relay; r != nil {
			rc = &loomnet.RelayConfig{QuicAddr: r.QuicAddr, WSSUrl: r.WSSURL, JWT: token, RelaySPKI: r.RelaySPKI, StunAddrs: r.StunAddrs}
		}
		if c.node != nil {
			c.node.SetConnectionPrefs(bundle.Connection)
			old := c.node.RelayConfig()
			if (old == nil) != (rc == nil) || (old != nil && rc != nil && old.Fingerprint() != rc.Fingerprint()) {
				c.node.SetRelayConfig(rc)
			}
		}
		c.configKnown = true
		c.configErr = ""
	}
	c.configOnce.Do(func() { close(c.configReady) })
}

func (c *hubClient) configLoop(ctx context.Context) {
	c.pollConfig(ctx, configInterval)
}

func (c *hubClient) pollConfig(ctx context.Context, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		c.refreshConfig(ctx)
		select {
		case <-ctx.Done():
			return
		case <-c.done:
			return
		case <-c.refresh:
		case <-ticker.C:
		}
	}
}

// A fast first Connect waits for the first attempt, not just goroutine startup.
// The bounded wait also covers a hung/unavailable Hub without blocking forever.
func (c *hubClient) waitConfig(ctx context.Context) {
	timer := time.NewTimer(configTimeout + time.Second)
	defer timer.Stop()
	select {
	case <-c.configReady:
	case <-ctx.Done():
	case <-c.done:
	case <-timer.C:
		c.tokenMu.Lock()
		if !c.configKnown && c.configErr == "" {
			c.configErr = "overlay config: initial wait timed out"
		}
		c.tokenMu.Unlock()
	}
}

func (c *hubClient) relayDiagnostics() relayStatus {
	c.tokenMu.RLock()
	out := relayStatus{ConfigKnown: c.configKnown, LastError: c.configErr, StunAddrs: []string{}}
	c.tokenMu.RUnlock()
	if c.node != nil {
		if rc := c.node.RelayConfig(); rc != nil {
			out.Configured = true
			out.HasCoordinate = rc.HasCoordinate()
			out.StunAddrs = append(out.StunAddrs, rc.StunAddrs...)
		}
	}
	return out
}

func connectBudget(n *loomnet.Node) time.Duration {
	return n.Registry.LadderWorstCase() + 5*time.Second
}
