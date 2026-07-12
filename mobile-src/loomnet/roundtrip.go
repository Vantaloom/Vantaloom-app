package loomnet

import (
	"context"
	"fmt"
	"net"
	"strings"
)

// dialStream is the http.Transport.DialContext for the overlay: it resolves the
// "<machineID>.loom[:port]" authority to a peer Session and opens a fresh QUIC
// stream as the request's net.Conn. Because the stream is a plain net.Conn, the
// standard http.Transport gives keep-alive and streaming (SSE, terminals) for
// free — one path replacing proxyViaMesh + proxyViaDirect + CallRemoteAPI (§3.3,
// §7.2).
func (n *Node) dialStream(ctx context.Context, _, addr string) (net.Conn, error) {
	machineID, err := parseLoomHost(addr)
	if err != nil {
		return nil, err
	}
	sess, err := n.getOrDialConn(ctx, machineID)
	if err != nil {
		return nil, err
	}
	c, err := sess.OpenStream(ctx)
	if err == nil {
		return c, nil
	}
	// A cached session may have died between reuse and OpenStream; evict it and
	// retry the ladder once.
	n.evictConn(machineID, sess)
	_ = sess.Close()
	sess, err = n.getOrDialConn(ctx, machineID)
	if err != nil {
		return nil, err
	}
	return sess.OpenStream(ctx)
}

// parseLoomHost extracts the machineID from a "<machineID>.loom[:port]"
// authority.
func parseLoomHost(addr string) (string, error) {
	host := addr
	if h, _, err := net.SplitHostPort(addr); err == nil {
		host = h
	}
	id, ok := strings.CutSuffix(host, ".loom")
	if !ok || id == "" {
		return "", fmt.Errorf("loomnet: cannot dial %q: host must be <machineID>.loom", addr)
	}
	return id, nil
}
