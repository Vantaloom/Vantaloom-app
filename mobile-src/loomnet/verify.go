package loomnet

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
)

// verifyOutbound builds a tls.Config.VerifyPeerCertificate callback for the
// dialer (§2.3): it pins the single fingerprint the Directory published for the
// peer we intend to reach, rejecting any leaf whose SPKI fingerprint differs.
// This defeats impersonation and MITM. The tls.Config must set
// InsecureSkipVerify so this callback is the sole trust authority (the standard
// pinning pattern — standard chain verification is meaningless for our
// self-signed certs).
func verifyOutbound(expectedFingerprint string) func([][]byte, [][]*x509.Certificate) error {
	return func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		leaf, fp, err := leafFingerprint(rawCerts)
		if err != nil {
			return err
		}
		if fp != expectedFingerprint {
			return fmt.Errorf("loomnet: peer %q fingerprint mismatch: got %s, pinned %s",
				leaf.Subject.CommonName, fp, expectedFingerprint)
		}
		return nil
	}
}

// verifyInbound builds a VerifyPeerCertificate callback for the listener (§2.3):
// it accepts a peer only if its machineID (cert CN) maps to its presented
// fingerprint in this node's account fingerprint set. The set is fetched fresh
// per handshake so a newly-joined peer is honoured without rebuilding TLS
// config. Binding CN→fingerprint (rather than merely "fingerprint is in the
// set") prevents an in-account key from spoofing another machine's ID.
func verifyInbound(accountFingerprints func() map[string]string) func([][]byte, [][]*x509.Certificate) error {
	return func(rawCerts [][]byte, _ [][]*x509.Certificate) error {
		leaf, fp, err := leafFingerprint(rawCerts)
		if err != nil {
			return err
		}
		cn := leaf.Subject.CommonName
		var set map[string]string
		if accountFingerprints != nil {
			set = accountFingerprints()
		}
		want, ok := set[cn]
		if !ok {
			return fmt.Errorf("loomnet: inbound peer %q not in account set", cn)
		}
		if want != fp {
			return fmt.Errorf("loomnet: inbound peer %q fingerprint mismatch: got %s, expected %s", cn, fp, want)
		}
		return nil
	}
}

// leafFingerprint parses the leaf certificate (rawCerts[0]) and returns it with
// its SPKI fingerprint.
func leafFingerprint(rawCerts [][]byte) (*x509.Certificate, string, error) {
	if len(rawCerts) == 0 {
		return nil, "", fmt.Errorf("loomnet: peer presented no certificate")
	}
	leaf, err := x509.ParseCertificate(rawCerts[0])
	if err != nil {
		return nil, "", fmt.Errorf("loomnet: parse peer certificate: %w", err)
	}
	return leaf, spkiFingerprint(leaf), nil
}

// peerIdentity reads the mTLS-verified machineID (cert CN) and SPKI fingerprint
// from an established connection's TLS state. VerifyPeerCertificate has already
// enforced trust, so this only extracts the proven identity for request
// attribution (§2.4, the trusted replacement for X-Relay-From).
func peerIdentity(cs tls.ConnectionState) (machineID, fingerprint string, err error) {
	if len(cs.PeerCertificates) == 0 {
		return "", "", fmt.Errorf("loomnet: connection has no verified peer certificate")
	}
	leaf := cs.PeerCertificates[0]
	return leaf.Subject.CommonName, spkiFingerprint(leaf), nil
}
