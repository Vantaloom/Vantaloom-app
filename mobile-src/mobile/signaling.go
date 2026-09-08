package mobile

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Waiters belong to a socket, never to a future reconnect. The legacy wire has
// no request ID: concurrent offers to the same peer are rejected, not overwritten.
type signalSession struct {
	conn    *websocket.Conn
	done    chan struct{}
	once    sync.Once
	writeMu sync.Mutex
	mu      sync.Mutex
	pending map[string]chan string
	offers  map[string]bool
}

func (s *signalSession) close() { s.once.Do(func() { close(s.done); _ = s.conn.Close() }) }
func (s *signalSession) write(kind int, data []byte) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	select {
	case <-s.done:
		return errors.New("signaling disconnected")
	default:
	}
	_ = s.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if err := s.conn.WriteMessage(kind, data); err != nil {
		s.close()
		return errors.New("signaling write failed")
	}
	return nil
}
func (s *signalSession) send(kind, peer, addr string) error {
	payload, _ := json.Marshal(struct {
		Addr string `json:"reflexiveAddr"`
	}{addr})
	data, _ := json.Marshal(signalMessage{Type: kind, To: peer, Payload: payload})
	return s.write(websocket.TextMessage, data)
}
func (c *hubClient) closeSignaling() {
	c.wsMu.Lock()
	defer c.wsMu.Unlock()
	if c.ws != nil {
		c.ws.close()
	}
}
func (c *hubClient) signalingConnected() bool {
	c.wsMu.Lock()
	defer c.wsMu.Unlock()
	if c.ws == nil {
		return false
	}
	select {
	case <-c.ws.done:
		return false
	default:
		return true
	}
}
func (c *hubClient) knownPeer(peer string) bool {
	if peer == "" || peer == c.machineID {
		return false
	}
	_, _, ok := c.PeerInfo(peer)
	return ok
}
func (c *hubClient) sendLoomOffer(ctx context.Context, peer, addr string) (string, error) {
	if !c.knownPeer(peer) {
		return "", errors.New("unknown signaling peer")
	}
	c.wsMu.Lock()
	s := c.ws
	c.wsMu.Unlock()
	if s == nil {
		return "", errors.New("signaling disconnected")
	}
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	ch := make(chan string, 1)
	s.mu.Lock()
	if _, exists := s.pending[peer]; exists {
		s.mu.Unlock()
		return "", errors.New("loom offer already pending for peer")
	}
	s.pending[peer] = ch
	s.mu.Unlock()
	defer func() { s.mu.Lock(); delete(s.pending, peer); s.mu.Unlock() }()
	if err := s.send("loom-offer", peer, addr); err != nil {
		return "", err
	}
	select {
	case <-ctx.Done():
		// Without wire request IDs a late answer could match the next attempt.
		// Retire this socket on timeout to make that ambiguity impossible.
		s.close()
		return "", ctx.Err()
	case <-c.done:
		return "", errors.New("signaling stopped")
	case <-s.done:
		return "", errors.New("signaling disconnected")
	case answer := <-ch:
		return answer, nil
	}
}

func (c *hubClient) wsLoop(ctx context.Context) {
	backoff := time.Second
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.done:
			return
		default:
		}
		if c.dialAndServe(ctx) {
			backoff = time.Second
		}
		timer := time.NewTimer(backoff)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-c.done:
			timer.Stop()
			return
		case <-c.reconnect:
			timer.Stop()
		case <-timer.C:
		}
		backoff *= 2
		if backoff > 30*time.Second {
			backoff = 30 * time.Second
		}
	}
}

func (c *hubClient) dialAndServe(ctx context.Context) bool {
	c.tokenMu.RLock()
	token, generation := c.tok, c.generation
	c.tokenMu.RUnlock()
	dctx, cancel := context.WithTimeout(ctx, 6*time.Second)
	defer cancel()
	hdr := http.Header{}
	u := c.wsURL
	if token != "" {
		hdr.Set("Authorization", "Bearer "+token)
		u += "&token=" + url.QueryEscape(token)
	}
	conn, resp, err := (&websocket.Dialer{HandshakeTimeout: 6 * time.Second}).DialContext(dctx, u, hdr)
	if resp != nil && resp.Body != nil {
		resp.Body.Close()
	}
	if err != nil {
		return false
	} // err can contain the tokenized URL; do not log it.
	s := &signalSession{conn: conn, done: make(chan struct{}), pending: map[string]chan string{}, offers: map[string]bool{}}
	c.tokenMu.RLock()
	select {
	case <-c.done:
		c.tokenMu.RUnlock()
		s.close()
		return false
	default:
	}
	if generation != c.generation || ctx.Err() != nil {
		c.tokenMu.RUnlock()
		s.close()
		return false
	}
	c.wsMu.Lock()
	c.ws = s
	c.wsMu.Unlock()
	c.tokenMu.RUnlock()
	defer func() {
		s.close()
		c.wsMu.Lock()
		if c.ws == s {
			c.ws = nil
		}
		c.wsMu.Unlock()
	}()
	writerDone := make(chan struct{})
	go func() { defer close(writerDone); c.writePump(ctx, s) }()
	c.readPump(s)
	s.close()
	<-writerDone
	return true
}

func (c *hubClient) writePump(ctx context.Context, s *signalSession) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	defer s.close()
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.done:
			return
		case <-s.done:
			return
		case <-ticker.C:
			if s.write(websocket.PingMessage, nil) != nil {
				return
			}
		}
	}
}

func validReflexive(addr string) bool {
	host, port, err := net.SplitHostPort(addr)
	if err != nil || net.ParseIP(host) == nil {
		return false
	}
	p, err := net.LookupPort("udp", port)
	return err == nil && p > 0
}

func (c *hubClient) readPump(s *signalSession) {
	conn := s.conn
	conn.SetReadLimit(64 << 10)
	_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
	conn.SetPongHandler(func(string) error { return conn.SetReadDeadline(time.Now().Add(90 * time.Second)) })
	conn.SetPingHandler(func(data string) error {
		_ = conn.SetReadDeadline(time.Now().Add(90 * time.Second))
		return s.write(websocket.PongMessage, []byte(data))
	})
	for {
		var msg signalMessage
		if conn.ReadJSON(&msg) != nil {
			return
		}
		if !c.knownPeer(msg.From) {
			continue
		}
		var payload struct {
			Addr string `json:"reflexiveAddr"`
		}
		if json.Unmarshal(msg.Payload, &payload) != nil {
			continue
		}
		if payload.Addr != "" && !validReflexive(payload.Addr) {
			continue
		}
		switch msg.Type {
		case "loom-answer":
			s.mu.Lock()
			if ch := s.pending[msg.From]; ch != nil {
				select {
				case ch <- payload.Addr:
				default:
				}
			}
			s.mu.Unlock()
		case "loom-offer":
			if payload.Addr == "" || c.handleOffer == nil {
				continue
			}
			s.mu.Lock()
			if s.offers[msg.From] {
				s.mu.Unlock()
				continue
			}
			select {
			case c.handlers <- struct{}{}:
			default:
				s.mu.Unlock()
				continue
			}
			s.offers[msg.From] = true
			s.mu.Unlock()
			c.wg.Add(1) // wsLoop remains counted until readPump returns
			go func(peer, addr string) {
				defer c.wg.Done()
				defer func() { <-c.handlers; s.mu.Lock(); delete(s.offers, peer); s.mu.Unlock() }()
				select {
				case <-s.done:
					return
				case <-c.done:
					return
				default:
				}
				answer, err := c.handleOffer(peer, addr)
				if err != nil {
					answer = ""
				}
				_ = s.send("loom-answer", peer, answer)
			}(msg.From, payload.Addr)
		}
	}
}
