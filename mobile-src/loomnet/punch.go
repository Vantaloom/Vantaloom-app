package loomnet

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"time"
)

// punchTimeout bounds a whole punch attempt: offer/answer exchange, probing,
// and the QUIC handshake (§4.6).
const punchTimeout = 8 * time.Second

// Signal types exchanged over the Hub signaling WS for hole punching (§5).
const (
	signalOffer  = "loom-offer"
	signalAnswer = "loom-answer"
)

// signalPayload is the JSON body of a loom-offer/loom-answer (§4.3): reflexive
// and LAN candidates, our SPKI fingerprint, and a nonce that pairs the exchange
// and tags the UDP probes.
type signalPayload struct {
	Endpoints Endpoints `json:"endpoints"`
	Reflexive string    `json:"reflexive"`
	Pubkey    string    `json:"pubkey"`
	Nonce     string    `json:"nonce"`
}

// packetWriter is satisfied by both *quic.Transport and net.PacketConn. Probes
// MUST go through the Transport (never the raw socket) once the socket is owned
// by a quic.Transport.
type packetWriter interface {
	WriteTo(b []byte, addr net.Addr) (int, error)
}

// punchStrategy is the pluggable NAT-traversal policy. v1 ships cone-only
// (coneStrategy); v1.1 can add port-prediction for symmetric NATs (§4.3) behind
// the same interface without touching the ladder.
type punchStrategy interface {
	// punchable reports whether this strategy can traverse local↔remote.
	punchable(local, remote Endpoints) bool
	// probe opens the local NAT binding toward target on the shared socket
	// before the QUIC handshake.
	probe(ctx context.Context, w packetWriter, target *net.UDPAddr, nonce []byte) error
}

// coneStrategy implements simultaneous-probe hole punching for cone↔cone NATs.
// It requires the peer's reflexive (public) address; without it, or against a
// symmetric NAT, the handshake fails and the ladder falls to relay.
type coneStrategy struct{}

func (coneStrategy) punchable(_, remote Endpoints) bool { return remote.Public != "" }

func (coneStrategy) probe(ctx context.Context, w packetWriter, target *net.UDPAddr, nonce []byte) error {
	const probes = 5
	msg := append([]byte("loom-punch:"), nonce...)
	for i := 0; i < probes; i++ {
		if _, err := w.WriteTo(msg, target); err != nil {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(150 * time.Millisecond):
		}
	}
	return nil
}

// punch attempts a cone↔cone UDP hole punch to machineID (§4.3): it swaps
// reflexive candidates over the Signaler, probes to open the local NAT binding,
// then dials QUIC over the shared transport. The initiator dials (client); the
// responder's own probes open its binding and its listener accepts the inbound
// QUIC through the normal serve path. Anything not cone-punchable errors here so
// the ladder falls to relay.
func (n *Node) punch(ctx context.Context, machineID, fp string, eps Endpoints) (Session, error) {
	if n.opts.Signaler == nil {
		return nil, errors.New("loomnet: punch: no signaler")
	}
	// The reflexive address is observed via the relay control connection (§4.3);
	// with no relay we have no reflexive address and cannot punch.
	if n.opts.Relay == nil {
		return nil, errors.New("loomnet: punch: no reflexive observer")
	}
	myReflexive := n.opts.Relay.ObservedAddr()
	if myReflexive == "" {
		return nil, errors.New("loomnet: punch: no local reflexive address")
	}
	if !n.strategy.punchable(n.LocalEndpoints(), eps) {
		return nil, errors.New("loomnet: punch: peer not cone-punchable (v1 cone-only)")
	}

	pctx, cancel := context.WithTimeout(ctx, punchTimeout)
	defer cancel()

	nonce := newNonce()
	body, err := json.Marshal(signalPayload{
		Endpoints: n.LocalEndpoints(),
		Reflexive: myReflexive,
		Pubkey:    n.identity.Fingerprint(),
		Nonce:     nonce,
	})
	if err != nil {
		return nil, fmt.Errorf("loomnet: punch: marshal offer: %w", err)
	}

	answerCh := n.registerWaiter(machineID)
	defer n.clearWaiter(machineID)

	if err := n.opts.Signaler.SendSignal(pctx, Signal{Type: signalOffer, To: machineID, Payload: body}); err != nil {
		return nil, fmt.Errorf("loomnet: punch: send offer: %w", err)
	}

	var ans signalPayload
	select {
	case sig := <-answerCh:
		if err := json.Unmarshal(sig.Payload, &ans); err != nil {
			return nil, fmt.Errorf("loomnet: punch: parse answer: %w", err)
		}
	case <-pctx.Done():
		return nil, fmt.Errorf("loomnet: punch: awaiting answer: %w", pctx.Err())
	}

	target, err := net.ResolveUDPAddr("udp", ans.Reflexive)
	if err != nil || target == nil {
		return nil, fmt.Errorf("loomnet: punch: bad answer reflexive %q: %w", ans.Reflexive, err)
	}
	if err := n.strategy.probe(pctx, n.tr.qt, target, []byte(nonce)); err != nil {
		return nil, fmt.Errorf("loomnet: punch: probe: %w", err)
	}
	s, err := n.tr.dial(pctx, target, fp, machineID)
	if err != nil {
		return nil, fmt.Errorf("loomnet: punch: handshake: %w", err)
	}
	return s, nil
}

// signalPump dispatches inbound signals for the node's lifetime: answers route
// to the waiting punch dialer, offers trigger the responder half.
func (n *Node) signalPump() {
	ch := n.opts.Signaler.Signals()
	for {
		select {
		case <-n.ctx.Done():
			return
		case sig, ok := <-ch:
			if !ok {
				return
			}
			switch sig.Type {
			case signalAnswer:
				n.deliverAnswer(sig)
			case signalOffer:
				go n.answerPunch(sig)
			}
		}
	}
}

// answerPunch is the responder half of a punch: it sends our reflexive back and
// probes the offerer so their QUIC handshake reaches us. We do not dial — our
// listener accepts the resulting connection through the normal serve path.
func (n *Node) answerPunch(sig Signal) {
	if n.opts.Relay == nil || n.tr == nil {
		return
	}
	myReflexive := n.opts.Relay.ObservedAddr()
	if myReflexive == "" {
		return
	}
	var offer signalPayload
	if err := json.Unmarshal(sig.Payload, &offer); err != nil {
		return
	}
	target, err := net.ResolveUDPAddr("udp", offer.Reflexive)
	if err != nil || target == nil {
		return
	}
	body, err := json.Marshal(signalPayload{
		Endpoints: n.LocalEndpoints(),
		Reflexive: myReflexive,
		Pubkey:    n.identity.Fingerprint(),
		Nonce:     offer.Nonce,
	})
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(n.ctx, punchTimeout)
	defer cancel()
	if err := n.opts.Signaler.SendSignal(ctx, Signal{Type: signalAnswer, To: sig.From, Payload: body}); err != nil {
		return
	}
	_ = n.strategy.probe(ctx, n.tr.qt, target, []byte(offer.Nonce))
}

// registerWaiter installs a one-shot channel to receive the loom-answer from
// machineID.
func (n *Node) registerWaiter(machineID string) chan Signal {
	ch := make(chan Signal, 1)
	n.waitersMu.Lock()
	n.waiters[machineID] = ch
	n.waitersMu.Unlock()
	return ch
}

func (n *Node) clearWaiter(machineID string) {
	n.waitersMu.Lock()
	delete(n.waiters, machineID)
	n.waitersMu.Unlock()
}

func (n *Node) deliverAnswer(sig Signal) {
	n.waitersMu.Lock()
	ch := n.waiters[sig.From]
	n.waitersMu.Unlock()
	if ch != nil {
		select {
		case ch <- sig:
		default: // waiter already satisfied or gone
		}
	}
}

func newNonce() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	return base64.RawURLEncoding.EncodeToString(b[:])
}
