package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/flynn/noise"
)

// memStateStore is an in-memory StateStore fake for tests.
type memStateStore struct {
	mu    sync.Mutex
	state HostState
}

func newMemStateStore(t *testing.T) *memStateStore {
	t.Helper()
	kp, err := noiseCipherSuite.GenerateKeypair(rand.Reader)
	if err != nil {
		t.Fatalf("GenerateKeypair: %v", err)
	}
	return &memStateStore{
		state: HostState{
			PrivateKey: kp.Private,
			PublicKey:  kp.Public,
			Devices:    []Device{},
		},
	}
}

func (m *memStateStore) Load() (HostState, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	raw, err := json.Marshal(m.state)
	if err != nil {
		return HostState{}, err
	}
	var clone HostState
	if err := json.Unmarshal(raw, &clone); err != nil {
		return HostState{}, err
	}
	if clone.Devices == nil {
		clone.Devices = []Device{}
	}
	return clone, nil
}

func (m *memStateStore) Save(st HostState) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	raw, err := json.Marshal(st)
	if err != nil {
		return err
	}
	var clone HostState
	if err := json.Unmarshal(raw, &clone); err != nil {
		return err
	}
	m.state = clone
	return nil
}

type countingConn struct {
	net.Conn
	readBytes *atomic.Int64
}

func (c *countingConn) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if n > 0 && c.readBytes != nil {
		c.readBytes.Add(int64(n))
	}
	return n, err
}

func dialCountingWS(ctx context.Context, t *testing.T, httpURL string) (*websocket.Conn, *atomic.Int64) {
	t.Helper()
	wsEndpoint := "ws" + strings.TrimPrefix(httpURL, "http") + "/ws"
	counter := &atomic.Int64{}
	tr := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			var d net.Dialer
			c, err := d.DialContext(ctx, network, addr)
			if err != nil {
				return nil, err
			}
			return &countingConn{Conn: c, readBytes: counter}, nil
		},
	}
	conn, _, err := websocket.Dial(ctx, wsEndpoint, &websocket.DialOptions{
		HTTPClient: &http.Client{Transport: tr},
	})
	if err != nil {
		t.Fatalf("websocket.Dial: %v", err)
	}
	conn.SetReadLimit(MaxNoiseMessage)
	return conn, counter
}

func newInitiatorHandshake(t *testing.T, phoneKP noise.DHKey, hostPub []byte) *noise.HandshakeState {
	t.Helper()
	hs, err := noise.NewHandshakeState(noise.Config{
		CipherSuite:   noiseCipherSuite,
		Random:        rand.Reader,
		Pattern:       noise.HandshakeIK,
		Initiator:     true,
		Prologue:      []byte(NoisePrologue),
		StaticKeypair: phoneKP,
		PeerStatic:    hostPub,
	})
	if err != nil {
		t.Fatalf("NewHandshakeState: %v", err)
	}
	return hs
}

func TestServer_PairedHandshakeAndHostInfo(t *testing.T) {
	store := newMemStateStore(t)
	phoneKP, err := noiseCipherSuite.GenerateKeypair(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	allowedDir := t.TempDir()
	st, _ := store.Load()
	st.Devices = append(st.Devices, Device{
		Name:      "Pixel9",
		PublicKey: phoneKP.Public,
		Permissions: DevicePermissions{
			CLIs:       []string{"claude"},
			Dirs:       []string{allowedDir},
			CanApprove: true,
		},
	})
	_ = store.Save(st)

	adapter := &fakeCLIAdapter{name: "claude", version: "claude 1.2.3"}
	mgr := NewSessionManager(nil, adapter)
	srv := NewServer(store, mgr, nil)
	ts := httptest.NewServer(srv)
	defer ts.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, _ := dialCountingWS(ctx, t, ts.URL)
	defer conn.CloseNow()

	hs := newInitiatorHandshake(t, phoneKP, st.PublicKey)
	helloRaw, _ := json.Marshal(handshakePayload{Type: "hello", DeviceName: "Pixel9"})
	msg1, _, _, err := hs.WriteMessage(nil, helloRaw)
	if err != nil {
		t.Fatalf("WriteMessage: %v", err)
	}
	if err := conn.Write(ctx, websocket.MessageBinary, msg1); err != nil {
		t.Fatalf("conn.Write msg1: %v", err)
	}

	typ, msg2, err := conn.Read(ctx)
	if err != nil || typ != websocket.MessageBinary {
		t.Fatalf("conn.Read msg2: typ=%v err=%v", typ, err)
	}
	// For initiator: cs1 is send, cs2 is recv.
	welcomeRaw, sendCS, recvCS, err := hs.ReadMessage(nil, msg2)
	if err != nil {
		t.Fatalf("ReadMessage msg2: %v", err)
	}
	var welcome handshakePayload
	if err := json.Unmarshal(welcomeRaw, &welcome); err != nil {
		t.Fatalf("unmarshal welcome: %v", err)
	}
	if welcome.Type != "welcome" || welcome.Version != ProtocolVersion {
		t.Fatalf("unexpected welcome payload: %+v", welcome)
	}

	// Send encrypted host.info request.
	reqPlain, err := encodeFrame(Frame{T: "req", ID: "req-1", Method: "host.info"})
	if err != nil {
		t.Fatal(err)
	}
	reqCT, err := sendCS.Encrypt(nil, nil, reqPlain)
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.Write(ctx, websocket.MessageBinary, reqCT); err != nil {
		t.Fatal(err)
	}

	_, respCT, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("conn.Read host.info resp: %v", err)
	}
	respPlain, err := recvCS.Decrypt(nil, nil, respCT)
	if err != nil {
		t.Fatalf("Decrypt resp: %v", err)
	}
	resp, err := decodeFrame(respPlain)
	if err != nil {
		t.Fatalf("decodeFrame: %v", err)
	}
	if resp.T != "res" || resp.ID != "req-1" || resp.OK == nil || !*resp.OK {
		t.Fatalf("unexpected host.info response: %+v", resp)
	}
	resMap, ok := resp.Result.(map[string]any)
	if !ok || resMap["version"] != ProtocolVersion {
		t.Fatalf("unexpected host.info result: %+v", resp.Result)
	}
}

func TestServer_UnpairedKeySilentCloseZeroBytes(t *testing.T) {
	store := newMemStateStore(t)
	st, _ := store.Load()

	unpairedKP, err := noiseCipherSuite.GenerateKeypair(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	srv := NewServer(store, NewSessionManager(nil), nil)
	ts := httptest.NewServer(srv)
	defer ts.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	conn, counter := dialCountingWS(ctx, t, ts.URL)
	defer conn.CloseNow()

	hs := newInitiatorHandshake(t, unpairedKP, st.PublicKey)
	helloRaw, _ := json.Marshal(handshakePayload{Type: "hello", DeviceName: "Stranger"})
	msg1, _, _, err := hs.WriteMessage(nil, helloRaw)
	if err != nil {
		t.Fatal(err)
	}

	// Reset byte counter after WebSocket HTTP upgrade so we measure exact bytes sent by server after msg1.
	counter.Store(0)
	if err := conn.Write(ctx, websocket.MessageBinary, msg1); err != nil {
		t.Fatalf("conn.Write msg1: %v", err)
	}

	_, data, err := conn.Read(ctx)
	if err == nil {
		t.Fatalf("expected socket close error for unpaired key, got data (%d bytes)", len(data))
	}
	if len(data) != 0 {
		t.Fatalf("expected 0 WebSocket payload bytes, got %d", len(data))
	}
	if got := counter.Load(); got != 0 {
		t.Fatalf("expected 0 raw TCP bytes written by server for unpaired key, got %d", got)
	}
}

func TestServer_NonWSRoutesReturnEmpty404(t *testing.T) {
	store := newMemStateStore(t)
	srv := NewServer(store, NewSessionManager(nil), nil)
	ts := httptest.NewServer(srv)
	defer ts.Close()

	for _, tc := range []struct {
		method string
		path   string
	}{
		{http.MethodGet, "/"},
		{http.MethodGet, "/health"},
		{http.MethodPost, "/ws"},
	} {
		req, err := http.NewRequest(tc.method, ts.URL+tc.path, nil)
		if err != nil {
			t.Fatal(err)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != http.StatusNotFound || len(body) != 0 {
			t.Fatalf("%s %s -> status %d, bodyLen %d (want 404 with 0 bytes)", tc.method, tc.path, resp.StatusCode, len(body))
		}
	}
}

func TestServer_SessionReplayAndStopOverNoise(t *testing.T) {
	store := newMemStateStore(t)
	phoneKP, _ := noiseCipherSuite.GenerateKeypair(rand.Reader)
	workDir := t.TempDir()

	st, _ := store.Load()
	st.Devices = append(st.Devices, Device{
		Name:      "Phone",
		PublicKey: bytes.Clone(phoneKP.Public),
		Permissions: DevicePermissions{
			CLIs: []string{"claude"},
			Dirs: []string{workDir},
		},
	})
	_ = store.Save(st)

	gate := make(chan struct{})
	adapter := &fakeCLIAdapter{
		name: "claude",
		runFn: func(ctx context.Context, cwd, prompt string, emit func(CLIEvent)) error {
			if prompt == "hang" {
				<-ctx.Done()
				return ctx.Err()
			}
			emit(CLIEvent{Output: "first"})
			<-gate
			emit(CLIEvent{Output: "second"})
			code := 0
			emit(CLIEvent{Result: "completed", ExitCode: &code})
			return nil
		},
	}

	mgr := NewSessionManager(nil, adapter)
	srv := NewServer(store, mgr, nil)
	ts := httptest.NewServer(srv)
	defer ts.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	connectPaired := func() (*websocket.Conn, *noise.CipherState, *noise.CipherState) {
		c, _ := dialCountingWS(ctx, t, ts.URL)
		hs := newInitiatorHandshake(t, phoneKP, st.PublicKey)
		helloRaw, _ := json.Marshal(handshakePayload{Type: "hello", DeviceName: "Phone"})
		m1, _, _, err := hs.WriteMessage(nil, helloRaw)
		if err != nil {
			t.Fatal(err)
		}
		if err := c.Write(ctx, websocket.MessageBinary, m1); err != nil {
			t.Fatal(err)
		}
		_, m2, err := c.Read(ctx)
		if err != nil {
			t.Fatal(err)
		}
		_, sendCS, recvCS, err := hs.ReadMessage(nil, m2)
		if err != nil {
			t.Fatal(err)
		}
		return c, sendCS, recvCS
	}

	callRPC := func(c *websocket.Conn, sendCS, recvCS *noise.CipherState, req Frame) Frame {
		plain, _ := encodeFrame(req)
		ct, _ := sendCS.Encrypt(nil, nil, plain)
		if err := c.Write(ctx, websocket.MessageBinary, ct); err != nil {
			t.Fatal(err)
		}
		for {
			_, rct, err := c.Read(ctx)
			if err != nil {
				t.Fatal(err)
			}
			rplain, err := recvCS.Decrypt(nil, nil, rct)
			if err != nil {
				t.Fatal(err)
			}
			f, err := decodeFrame(rplain)
			if err != nil {
				t.Fatal(err)
			}
			if f.T == "res" && f.ID == req.ID {
				return f
			}
		}
	}

	// 1. Connect and start a session.
	c1, send1, recv1 := connectPaired()
	startResp := callRPC(c1, send1, recv1, Frame{
		T:      "req",
		ID:     "s1",
		Method: "session.start",
		Params: map[string]any{"cli": "claude", "cwd": workDir, "prompt": "run"},
	})
	if startResp.OK == nil || !*startResp.OK {
		t.Fatalf("session.start failed: %+v", startResp)
	}
	sid := startResp.Result.(map[string]any)["sessionId"].(string)

	// 2. Disconnect phone while session finishes in the background.
	_ = c1.CloseNow()
	close(gate)
	_ = mgr.Wait(sid)

	// 3. Reconnect and replay events via session.events.
	c2, send2, recv2 := connectPaired()
	defer c2.CloseNow()

	evResp := callRPC(c2, send2, recv2, Frame{
		T:      "req",
		ID:     "e1",
		Method: "session.events",
		Params: map[string]any{"sessionId": sid, "afterSeq": float64(0)},
	})
	if evResp.OK == nil || !*evResp.OK {
		t.Fatalf("session.events failed: %+v", evResp)
	}
	evList := evResp.Result.(map[string]any)["events"].([]any)
	if len(evList) != 5 {
		t.Fatalf("expected 5 replayed events, got %d: %+v", len(evList), evList)
	}

	// 4. Start a hanging session and stop it via session.stop.
	start2 := callRPC(c2, send2, recv2, Frame{
		T:      "req",
		ID:     "s2",
		Method: "session.start",
		Params: map[string]any{"cli": "claude", "cwd": workDir, "prompt": "hang"},
	})
	sid2 := start2.Result.(map[string]any)["sessionId"].(string)

	stopResp := callRPC(c2, send2, recv2, Frame{
		T:      "req",
		ID:     "stop1",
		Method: "session.stop",
		Params: map[string]any{"sessionId": sid2},
	})
	if stopResp.OK == nil || !*stopResp.OK {
		t.Fatalf("session.stop failed: %+v", stopResp)
	}
}
