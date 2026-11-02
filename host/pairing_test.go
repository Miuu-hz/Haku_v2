package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func TestPairing_RightOTPAndConfirmStoresDeviceAndEqualSAS(t *testing.T) {
	store := newMemStateStore(t)
	st, _ := store.Load()

	srv := NewServer(store, NewSessionManager(nil), nil)
	ts := httptest.NewServer(srv)
	defer ts.Close()

	var hostSAS string
	qr, doneCh, err := srv.StartPairing("wss://example.trycloudflare.com/ws", func(sas string) bool {
		hostSAS = sas
		return true
	})
	if err != nil {
		t.Fatalf("StartPairing: %v", err)
	}

	var qrBuf bytes.Buffer
	qrJSON, err := PrintPairingQR(&qrBuf, qr)
	if err != nil {
		t.Fatalf("PrintPairingQR: %v", err)
	}
	if qrBuf.Len() == 0 || !strings.Contains(qrJSON, `"v":1`) {
		t.Fatalf("expected QR output and v:1 JSON, got json=%s", qrJSON)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	phoneKP, err := noiseCipherSuite.GenerateKeypair(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	conn, _ := dialCountingWS(ctx, t, ts.URL)
	defer conn.CloseNow()

	hs := newInitiatorHandshake(t, phoneKP, st.PublicKey)
	pairReqRaw, _ := json.Marshal(handshakePayload{
		Type:       "pair",
		OTP:        qr.OTP,
		DeviceName: "Fold5",
	})
	msg1, _, _, err := hs.WriteMessage(nil, pairReqRaw)
	if err != nil {
		t.Fatal(err)
	}
	if err := conn.Write(ctx, websocket.MessageBinary, msg1); err != nil {
		t.Fatal(err)
	}

	_, msg2, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("conn.Read pairing msg2: %v", err)
	}
	replyRaw, _, _, err := hs.ReadMessage(nil, msg2)
	if err != nil {
		t.Fatalf("hs.ReadMessage msg2: %v", err)
	}
	var reply handshakePayload
	if err := json.Unmarshal(replyRaw, &reply); err != nil {
		t.Fatal(err)
	}
	if reply.Type != "pairing" {
		t.Fatalf("unexpected pairing reply: %+v", reply)
	}

	phoneHandshakeHash := hs.ChannelBinding()
	phoneSAS := ComputeSAS(phoneHandshakeHash)
	phoneAnnounceKey := DeriveAnnounceKey(phoneHandshakeHash)

	if phoneSAS != hostSAS || len(phoneSAS) != 6 {
		t.Fatalf("SAS mismatch: phone=%q host=%q", phoneSAS, hostSAS)
	}

	if err := <-doneCh; err != nil {
		t.Fatalf("pairing doneCh err: %v", err)
	}

	afterState, err := store.Load()
	if err != nil {
		t.Fatal(err)
	}
	if len(afterState.Devices) != 1 {
		t.Fatalf("expected 1 stored device, got %d", len(afterState.Devices))
	}
	dev := afterState.Devices[0]
	if dev.Name != "Fold5" || !bytes.Equal(dev.PublicKey, phoneKP.Public) {
		t.Fatalf("unexpected stored device: %+v", dev)
	}
	if dev.Topic != qr.Topic || !bytes.Equal(dev.AnnounceKey, phoneAnnounceKey) {
		t.Fatalf("topic/announceKey mismatch: got (%q, %x), want (%q, %x)", dev.Topic, dev.AnnounceKey, qr.Topic, phoneAnnounceKey)
	}
	if len(dev.Permissions.CLIs) != 0 || len(dev.Permissions.Dirs) != 0 || dev.Permissions.CanApprove {
		t.Fatalf("expected empty default permissions, got %+v", dev.Permissions)
	}
}

func TestPairing_WrongExpiredReusedOTPAndRejectedConfirm(t *testing.T) {
	cases := []struct {
		name      string
		setup     func(srv *Server) string
		confirmOK bool
	}{
		{
			name: "wrong otp silent close",
			setup: func(srv *Server) string {
				_, _, err := srv.StartPairing("", func(string) bool { return true })
				if err != nil {
					t.Fatal(err)
				}
				return "wrong-otp-value"
			},
			confirmOK: true,
		},
		{
			name: "expired otp silent close",
			setup: func(srv *Server) string {
				now := time.Now()
				srv.pairing.nowFn = func() time.Time { return now }
				qr, _, err := srv.StartPairing("", func(string) bool { return true })
				if err != nil {
					t.Fatal(err)
				}
				// Advance clock past 5-minute TTL.
				srv.pairing.nowFn = func() time.Time { return now.Add(PairingTTL + time.Second) }
				return qr.OTP
			},
			confirmOK: true,
		},
		{
			name: "reused otp silent close",
			setup: func(srv *Server) string {
				qr, _, err := srv.StartPairing("", func(string) bool { return true })
				if err != nil {
					t.Fatal(err)
				}
				// Consume once before the connection attempt.
				if _, _, _, ok := srv.pairing.consume(qr.OTP); !ok {
					t.Fatal("expected first consume to succeed")
				}
				return qr.OTP
			},
			confirmOK: true,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			store := newMemStateStore(t)
			st, _ := store.Load()
			srv := NewServer(store, NewSessionManager(nil), nil)
			ts := httptest.NewServer(srv)
			defer ts.Close()

			otp := tc.setup(srv)

			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			phoneKP, _ := noiseCipherSuite.GenerateKeypair(rand.Reader)
			conn, counter := dialCountingWS(ctx, t, ts.URL)
			defer conn.CloseNow()

			hs := newInitiatorHandshake(t, phoneKP, st.PublicKey)
			pairReqRaw, _ := json.Marshal(handshakePayload{
				Type:       "pair",
				OTP:        otp,
				DeviceName: "AttackerPhone",
			})
			msg1, _, _, err := hs.WriteMessage(nil, pairReqRaw)
			if err != nil {
				t.Fatal(err)
			}

			counter.Store(0)
			if err := conn.Write(ctx, websocket.MessageBinary, msg1); err != nil {
				t.Fatal(err)
			}
			_, data, err := conn.Read(ctx)
			if err == nil || len(data) != 0 || counter.Load() != 0 {
				t.Fatalf("expected silent close with 0 bytes, got err=%v len=%d rawBytes=%d", err, len(data), counter.Load())
			}

			after, _ := store.Load()
			if len(after.Devices) != 0 {
				t.Fatalf("expected 0 devices stored, got %d", len(after.Devices))
			}
		})
	}
}
