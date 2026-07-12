// Package loomnet is the process-in native Go overlay that carries Vantaloom's
// own HTTP traffic between machines, replacing the vendored EasyTier sidecar. It
// has no TUN, no virtual IP, and no OS-visible network interface: every peer
// connection is a QUIC connection with mutual TLS fingerprint pinning, and the
// three dial tiers (direct / hole-punch / relay) all present the same Session
// abstraction to the HTTP layer. See docs/loomnet-design.md for the contract.
package loomnet

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"time"
)

const identityFileName = "identity.key"

// certValidity is deliberately long: the SPKI fingerprint (not the certificate
// dates) is the durable overlay identity, and the certificate is re-minted from
// the persisted key on every start, so validity windows never need renewal.
const certValidity = 100 * 365 * 24 * time.Hour

// Identity is a machine's durable overlay identity (design §2.1): a persisted
// ed25519 keypair plus a self-signed X.509 certificate (CN = machineID) minted
// from it. The certificate's SubjectPublicKeyInfo fingerprint (sha256, base64)
// is the machine's overlay identity, pinned by peers during the mTLS handshake.
type Identity struct {
	machineID   string
	priv        ed25519.PrivateKey
	cert        tls.Certificate
	leaf        *x509.Certificate
	fingerprint string
}

// LoadOrCreateIdentity loads the ed25519 private key from
// <dataDir>/loomnet/identity.key, generating and persisting it 0600 on first
// use, then mints a self-signed certificate from it. The private key never
// leaves this machine.
func LoadOrCreateIdentity(dataDir, machineID string) (*Identity, error) {
	if machineID == "" {
		return nil, fmt.Errorf("loomnet: machineID is required for identity")
	}
	dir := filepath.Join(dataDir, "loomnet")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, fmt.Errorf("loomnet: create identity dir: %w", err)
	}
	priv, err := loadOrGenKey(filepath.Join(dir, identityFileName))
	if err != nil {
		return nil, err
	}
	return identityFromKey(machineID, priv)
}

func loadOrGenKey(keyPath string) (ed25519.PrivateKey, error) {
	switch b, err := os.ReadFile(keyPath); {
	case err == nil:
		return parsePrivateKey(b)
	case !os.IsNotExist(err):
		return nil, fmt.Errorf("loomnet: read identity key: %w", err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("loomnet: generate identity key: %w", err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return nil, fmt.Errorf("loomnet: marshal identity key: %w", err)
	}
	pemBytes := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
	if err := os.WriteFile(keyPath, pemBytes, 0o600); err != nil {
		return nil, fmt.Errorf("loomnet: persist identity key: %w", err)
	}
	return priv, nil
}

func parsePrivateKey(pemBytes []byte) (ed25519.PrivateKey, error) {
	block, _ := pem.Decode(pemBytes)
	if block == nil {
		return nil, fmt.Errorf("loomnet: identity key is not valid PEM")
	}
	key, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("loomnet: parse identity key: %w", err)
	}
	priv, ok := key.(ed25519.PrivateKey)
	if !ok {
		return nil, fmt.Errorf("loomnet: identity key is %T, want ed25519", key)
	}
	return priv, nil
}

func identityFromKey(machineID string, priv ed25519.PrivateKey) (*Identity, error) {
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: machineID},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(certValidity),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
	}
	pub, ok := priv.Public().(ed25519.PublicKey)
	if !ok {
		return nil, fmt.Errorf("loomnet: identity key has no ed25519 public half")
	}
	// Self-signed: the key is both the subject and the signer.
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, pub, priv)
	if err != nil {
		return nil, fmt.Errorf("loomnet: create self-signed cert: %w", err)
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		return nil, fmt.Errorf("loomnet: parse self-signed cert: %w", err)
	}
	return &Identity{
		machineID:   machineID,
		priv:        priv,
		cert:        tls.Certificate{Certificate: [][]byte{der}, PrivateKey: priv, Leaf: leaf},
		leaf:        leaf,
		fingerprint: spkiFingerprint(leaf),
	}, nil
}

// spkiFingerprint is the base64 sha256 of the certificate's
// SubjectPublicKeyInfo — the stable overlay identity fingerprint (§2.1). It
// depends only on the public key, so it survives cert re-minting.
func spkiFingerprint(cert *x509.Certificate) string {
	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	return base64.StdEncoding.EncodeToString(sum[:])
}

// MachineID is the certificate's CN, the machine's overlay identity.
func (id *Identity) MachineID() string { return id.machineID }

// Fingerprint is the base64 SPKI sha256 that peers pin.
func (id *Identity) Fingerprint() string { return id.fingerprint }

// TLSCertificate is the presented client/server certificate for the QUIC mTLS
// handshake (and the crypto/tls relay path later, same shape).
func (id *Identity) TLSCertificate() tls.Certificate { return id.cert }
