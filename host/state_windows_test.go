//go:build windows

package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"golang.org/x/crypto/curve25519"
)

func TestDPAPIStateStore_FreshInstallAndRoundTrip(t *testing.T) {
	dir := t.TempDir()
	store := NewDPAPIStateStore(dir)

	st, err := store.Load()
	if err != nil {
		t.Fatalf("fresh Load() failed: %v", err)
	}
	if len(st.PrivateKey) != 32 || len(st.PublicKey) != 32 {
		t.Fatalf("expected 32-byte keypair, got priv=%d pub=%d", len(st.PrivateKey), len(st.PublicKey))
	}
	derivedPub, err := curve25519.X25519(st.PrivateKey, curve25519.Basepoint)
	if err != nil {
		t.Fatalf("derive public key failed: %v", err)
	}
	if !bytes.Equal(derivedPub, st.PublicKey) {
		t.Fatalf("public key does not match private key")
	}

	rawFile, err := os.ReadFile(filepath.Join(dir, stateFileName))
	if err != nil {
		t.Fatalf("ReadFile(state.bin) failed: %v", err)
	}
	if json.Valid(rawFile) || bytes.Contains(rawFile, []byte("privateKey")) || bytes.Contains(rawFile, st.PrivateKey) {
		t.Fatalf("state.bin must be DPAPI-encrypted, not plaintext JSON")
	}

	st.Devices = []Device{
		{
			Name:        "Pixel 9",
			PublicKey:   bytes.Repeat([]byte{0x11}, 32),
			Topic:       "topic-abc-123",
			AnnounceKey: bytes.Repeat([]byte{0x22}, 32),
			PairedAtMs:  1700000000000,
			Permissions: DevicePermissions{
				CLIs:       []string{"claude"},
				Dirs:       []string{`C:\work\project`},
				CanApprove: true,
			},
		},
	}
	if err := store.Save(st); err != nil {
		t.Fatalf("Save() failed: %v", err)
	}

	reloaded, err := NewDPAPIStateStore(dir).Load()
	if err != nil {
		t.Fatalf("reloaded Load() failed: %v", err)
	}
	if !reflect.DeepEqual(reloaded, st) {
		t.Fatalf("round-trip mismatch:\ngot:  %+v\nwant: %+v", reloaded, st)
	}
}

func TestConfig_LoadSaveRoundTrip(t *testing.T) {
	dir := t.TempDir()

	cfg, err := LoadConfig(dir)
	if err != nil {
		t.Fatalf("LoadConfig on missing file failed: %v", err)
	}
	if cfg.Port != DefaultPort {
		t.Fatalf("expected default port %d, got %d", DefaultPort, cfg.Port)
	}

	if err := SaveConfig(dir, Config{Port: 51234}); err != nil {
		t.Fatalf("SaveConfig failed: %v", err)
	}
	cfg2, err := LoadConfig(dir)
	if err != nil {
		t.Fatalf("LoadConfig after save failed: %v", err)
	}
	if cfg2.Port != 51234 {
		t.Fatalf("expected port 51234, got %d", cfg2.Port)
	}
}
