package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/flynn/noise"
)

const (
	HandshakeTimeout = 10 * time.Second
	IdleTimeout      = 5 * time.Minute
	WriteTimeout     = 10 * time.Second
	MaxNoiseMessage  = MaxPlaintext + 256
)

var noiseCipherSuite = noise.NewCipherSuite(noise.DH25519, noise.CipherChaChaPoly, noise.HashBLAKE2s)

type handshakePayload struct {
	Type       string `json:"type"`
	DeviceName string `json:"deviceName,omitempty"`
	OTP        string `json:"otp,omitempty"`
	Host       string `json:"host,omitempty"`
	Version    string `json:"version,omitempty"`
}

// Server handles WebSocket upgrade, Noise IK handshakes, pairing, and transport frame dispatch.
type Server struct {
	store    StateStore
	sessions *SessionManager
	audit    AuditLog
	pairing  *pairingState
	stateMu  sync.Mutex
}

func NewServer(store StateStore, sessions *SessionManager, audit AuditLog) *Server {
	return &Server{
		store:    store,
		sessions: sessions,
		audit:    audit,
		pairing:  newPairingState(),
	}
}

// StartPairing creates a new single-use 5-minute pairing session and returns the QR payload.
func (s *Server) StartPairing(wsURL string, confirm func(sas string) bool) (PairingQR, <-chan error, error) {
	st, err := s.loadState()
	if err != nil {
		return PairingQR{}, nil, err
	}
	return s.pairing.create(st.PublicKey, wsURL, confirm)
}

// ListenAndServe binds strictly to 127.0.0.1:<port> and serves until ctx is cancelled.
func (s *Server) ListenAndServe(ctx context.Context, port int) error {
	if port <= 0 || port > 65535 {
		return fmt.Errorf("invalid port: %d", port)
	}
	addr := fmt.Sprintf("127.0.0.1:%d", port)
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		return err
	}
	httpSrv := &http.Server{
		Addr:              addr,
		Handler:           s,
		ReadHeaderTimeout: HandshakeTimeout,
	}
	errCh := make(chan error, 1)
	go func() {
		err := httpSrv.Serve(ln)
		if errors.Is(err, http.ErrServerClosed) {
			err = nil
		}
		errCh <- err
	}()

	select {
	case <-ctx.Done():
		shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = httpSrv.Shutdown(shutCtx)
		return <-errCh
	case err := <-errCh:
		return err
	}
}

// ServeHTTP serves GET /ws and returns an empty 404 for every other request (PROTOCOL.md §1).
func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || r.URL.Path != "/ws" {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		InsecureSkipVerify: true,
	})
	if err != nil {
		return
	}
	defer conn.CloseNow()
	conn.SetReadLimit(MaxNoiseMessage)

	s.handleWS(r.Context(), conn)
}

func (s *Server) handleWS(ctx context.Context, conn *websocket.Conn) {
	st, err := s.loadState()
	if err != nil {
		_ = conn.CloseNow()
		return
	}

	hs, err := noise.NewHandshakeState(noise.Config{
		CipherSuite: noiseCipherSuite,
		Random:      rand.Reader,
		Pattern:     noise.HandshakeIK,
		Initiator:   false,
		Prologue:    []byte(NoisePrologue),
		StaticKeypair: noise.DHKey{
			Private: st.PrivateKey,
			Public:  st.PublicKey,
		},
	})
	if err != nil {
		_ = conn.CloseNow()
		return
	}

	hctx, hcancel := context.WithTimeout(ctx, HandshakeTimeout)
	typ, msg1, err := conn.Read(hctx)
	hcancel()
	if err != nil || typ != websocket.MessageBinary {
		_ = conn.CloseNow()
		return
	}

	payload1, _, _, err := hs.ReadMessage(nil, msg1)
	if err != nil || len(payload1) > MaxPlaintext {
		_ = conn.CloseNow()
		return
	}
	peerKey := bytes.Clone(hs.PeerStatic())
	if len(peerKey) == 0 {
		_ = conn.CloseNow()
		return
	}

	var hp handshakePayload
	if err := json.Unmarshal(payload1, &hp); err != nil {
		_ = conn.CloseNow()
		return
	}

	switch hp.Type {
	case "hello":
		dev, ok := findDeviceByKey(st.Devices, peerKey)
		if !ok {
			// Unpaired key -> close with zero bytes written (PROTOCOL.md §2).
			_ = conn.CloseNow()
			return
		}
		host, err := os.Hostname()
		if err != nil || host == "" {
			host = "localhost"
		}
		welcomeRaw, err := json.Marshal(handshakePayload{
			Type:    "welcome",
			Host:    host,
			Version: ProtocolVersion,
		})
		if err != nil {
			_ = conn.CloseNow()
			return
		}
		// In Noise Split(), cs1 is initiator->responder (recv) and cs2 is responder->initiator (send).
		msg2, recvCS, sendCS, err := hs.WriteMessage(nil, welcomeRaw)
		if err != nil {
			_ = conn.CloseNow()
			return
		}
		wctx, wcancel := context.WithTimeout(ctx, WriteTimeout)
		err = conn.Write(wctx, websocket.MessageBinary, msg2)
		wcancel()
		if err != nil {
			_ = conn.CloseNow()
			return
		}
		if s.audit != nil {
			_ = s.audit.Append(dev.Name, "connect", map[string]any{})
		}
		s.serveTransport(ctx, conn, dev.PublicKey, recvCS, sendCS)

	case "pair":
		deviceName := strings.TrimSpace(hp.DeviceName)
		if deviceName == "" {
			_ = conn.CloseNow()
			return
		}
		topic, confirm, done, ok := s.pairing.consume(hp.OTP)
		if !ok {
			// Wrong, expired, or reused OTP -> close with zero bytes written (PROTOCOL.md §2, §3).
			_ = conn.CloseNow()
			return
		}

		// No SAS in the payload: message 2's payload is mixed into the handshake hash, so each side computes the SAS
		// from its own final hash (PROTOCOL.md §3).
		pairReplyRaw, err := json.Marshal(handshakePayload{Type: "pairing"})
		if err != nil {
			notifyPairingDone(done, err)
			_ = conn.CloseNow()
			return
		}
		msg2, _, _, err := hs.WriteMessage(nil, pairReplyRaw)
		if err != nil {
			notifyPairingDone(done, err)
			_ = conn.CloseNow()
			return
		}

		// Send message 2 first so the phone can show its SAS while the PC asks the user to compare.
		wctx, wcancel := context.WithTimeout(ctx, WriteTimeout)
		writeErr := conn.Write(wctx, websocket.MessageBinary, msg2)
		wcancel()
		if writeErr != nil {
			notifyPairingDone(done, writeErr)
			_ = conn.CloseNow()
			return
		}

		handshakeHash := bytes.Clone(hs.ChannelBinding())
		sas := ComputeSAS(handshakeHash)
		announceKey := DeriveAnnounceKey(handshakeHash)

		if confirm != nil && !confirm(sas) {
			notifyPairingDone(done, errors.New("pairing rejected by user"))
			_ = conn.CloseNow()
			return
		}

		dev := Device{
			Name:        deviceName,
			PublicKey:   peerKey,
			Topic:       topic,
			AnnounceKey: announceKey,
			PairedAtMs:  time.Now().UnixMilli(),
			Permissions: DevicePermissions{
				CLIs:       []string{},
				Dirs:       []string{},
				CanApprove: false,
			},
		}
		s.stateMu.Lock()
		saveErr := storePairedDevice(s.store, dev)
		s.stateMu.Unlock()
		if saveErr != nil {
			notifyPairingDone(done, saveErr)
			_ = conn.CloseNow()
			return
		}
		if s.audit != nil {
			_ = s.audit.Append(dev.Name, "pairing", map[string]any{"topic": dev.Topic})
		}

		notifyPairingDone(done, nil)
		_ = conn.Close(websocket.StatusNormalClosure, "")

	default:
		_ = conn.CloseNow()
	}
}

func (s *Server) serveTransport(ctx context.Context, conn *websocket.Conn, deviceKey []byte, recvCS, sendCS *noise.CipherState) {
	var writeMu sync.Mutex
	sendEncrypted := func(f Frame) error {
		plain, err := encodeFrame(f)
		if err != nil {
			return err
		}
		writeMu.Lock()
		defer writeMu.Unlock()
		ct, err := sendCS.Encrypt(nil, nil, plain)
		if err != nil {
			return err
		}
		wctx, cancel := context.WithTimeout(ctx, WriteTimeout)
		defer cancel()
		return conn.Write(wctx, websocket.MessageBinary, ct)
	}

	if s.sessions != nil {
		unsub := s.sessions.Subscribe(deviceKey, func(ev Frame) {
			_ = sendEncrypted(ev)
		})
		defer unsub()
	}

	for {
		rctx, rcancel := context.WithTimeout(ctx, IdleTimeout)
		typ, ct, err := conn.Read(rctx)
		rcancel()
		if err != nil || typ != websocket.MessageBinary {
			return
		}
		plain, err := recvCS.Decrypt(nil, nil, ct)
		if err != nil || len(plain) > MaxPlaintext {
			return
		}

		req, err := decodeFrame(plain)
		dev, ok := s.loadDevice(deviceKey)
		if !ok {
			_ = sendEncrypted(errFrame(req.ID, ErrCodeNotAllowed, "device not allowed"))
			_ = conn.Close(websocket.StatusNormalClosure, "")
			return
		}

		var resp Frame
		if err != nil {
			resp = errFrame("", ErrCodeBadRequest, "malformed frame json")
		} else {
			resp = dispatchRequest(ctx, dev, s.sessions, s.audit, req)
		}
		if err := sendEncrypted(resp); err != nil {
			return
		}
	}
}

// ponytail: re-read state from store/disk on every request; requests are rare so skip caching or file watchers.
func (s *Server) loadDevice(pubKey []byte) (Device, bool) {
	st, err := s.loadState()
	if err != nil {
		return Device{}, false
	}
	return findDeviceByKey(st.Devices, pubKey)
}

func (s *Server) loadState() (HostState, error) {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()

	st, err := s.store.Load()
	if err != nil {
		return HostState{}, err
	}
	if len(st.PrivateKey) == 0 || len(st.PublicKey) == 0 {
		kp, err := noiseCipherSuite.GenerateKeypair(rand.Reader)
		if err != nil {
			return HostState{}, err
		}
		st.PrivateKey = kp.Private
		st.PublicKey = kp.Public
		if st.Devices == nil {
			st.Devices = []Device{}
		}
		if err := s.store.Save(st); err != nil {
			return HostState{}, err
		}
	}
	return st, nil
}

func findDeviceByKey(devices []Device, pubKey []byte) (Device, bool) {
	for _, d := range devices {
		if bytes.Equal(d.PublicKey, pubKey) {
			return d, true
		}
	}
	return Device{}, false
}

func notifyPairingDone(ch chan error, err error) {
	if ch == nil {
		return
	}
	select {
	case ch <- err:
	default:
	}
}
