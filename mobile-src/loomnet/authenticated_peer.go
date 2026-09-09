package loomnet

import "context"

// AuthenticatedPeer is populated only by the mTLS demultiplexer, never headers.
// Valid rechecks the live directory (including temporary trust expiry).
type AuthenticatedPeer struct {
	MachineID   string
	Fingerprint string
	valid       func() bool
}

func (p AuthenticatedPeer) Valid() bool { return p.valid != nil && p.valid() }

type authenticatedPeerKey struct{}

func AuthenticatedPeerFrom(ctx context.Context) (AuthenticatedPeer, bool) {
	p, ok := ctx.Value(authenticatedPeerKey{}).(AuthenticatedPeer)
	return p, ok
}
