package main

import (
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"golang.org/x/crypto/chacha20poly1305"
)

func TestSealOpenAnnounce_RoundTrip(t *testing.T) {
	key := bytes.Repeat([]byte{0x42}, chacha20poly1305.KeySize)

	for _, pt := range []string{"wss://autumn-river-8421.trycloudflare.com/ws", ""} {
		sealed, err := sealAnnounce(key, pt)
		if err != nil {
			t.Fatalf("sealAnnounce(%q) failed: %v", pt, err)
		}
		opened, err := openAnnounce(key, sealed)
		if err != nil {
			t.Fatalf("openAnnounce(%q) failed: %v", pt, err)
		}
		if opened != pt {
			t.Fatalf("got %q, want %q", opened, pt)
		}
	}
}

func TestOpenAnnounce_WrongKeyAndTamper(t *testing.T) {
	key1 := bytes.Repeat([]byte{0x01}, chacha20poly1305.KeySize)
	key2 := bytes.Repeat([]byte{0x02}, chacha20poly1305.KeySize)

	sealed, err := sealAnnounce(key1, "wss://example.trycloudflare.com/ws")
	if err != nil {
		t.Fatalf("sealAnnounce failed: %v", err)
	}
	if _, err := openAnnounce(key2, sealed); err == nil {
		t.Fatalf("expected error with wrong key")
	}
	if _, err := openAnnounce(key1, "not-valid-ciphertext"); err == nil {
		t.Fatalf("expected error on truncated ciphertext")
	}
}

func TestNtfyAnnouncer_HTTPPostAndDecrypt(t *testing.T) {
	var gotMethod, gotPath, gotBody string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	key := bytes.Repeat([]byte{0x77}, chacha20poly1305.KeySize)
	dev := Device{
		Name:        "Phone",
		Topic:       "topic_xyz_987",
		AnnounceKey: key,
	}

	ann := NewNtfyAnnouncer().(*NtfyAnnouncer)
	ann.BaseURL = srv.URL

	wantURL := "wss://quick-fox-123.trycloudflare.com/ws"
	if err := ann.AnnounceURL(context.Background(), dev, wantURL); err != nil {
		t.Fatalf("AnnounceURL failed: %v", err)
	}
	if gotMethod != http.MethodPost || gotPath != "/topic_xyz_987" {
		t.Fatalf("unexpected request: %s %s", gotMethod, gotPath)
	}
	decrypted, err := openAnnounce(key, gotBody)
	if err != nil {
		t.Fatalf("openAnnounce on server body failed: %v", err)
	}
	if decrypted != wantURL {
		t.Fatalf("decrypted body = %q, want %q", decrypted, wantURL)
	}

	if err := ann.Wake(context.Background(), dev); err != nil {
		t.Fatalf("Wake failed: %v", err)
	}
	wakePlain, err := openAnnounce(key, gotBody)
	if err != nil {
		t.Fatalf("openAnnounce on wake body failed: %v", err)
	}
	if wakePlain != "" {
		t.Fatalf("expected empty wake plaintext, got %q", wakePlain)
	}
}

func TestNtfyAnnouncer_Non2xxReturnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	ann := NewNtfyAnnouncer().(*NtfyAnnouncer)
	ann.BaseURL = srv.URL
	dev := Device{
		Name:        "Phone",
		Topic:       "topic_err",
		AnnounceKey: bytes.Repeat([]byte{0x55}, chacha20poly1305.KeySize),
	}

	if err := ann.AnnounceURL(context.Background(), dev, "wss://example.trycloudflare.com/ws"); err == nil {
		t.Fatalf("expected error on HTTP 500 from AnnounceURL")
	}
	if err := ann.Wake(context.Background(), dev); err == nil {
		t.Fatalf("expected error on HTTP 500 from Wake")
	}
}
