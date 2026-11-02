package main

import (
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"golang.org/x/crypto/curve25519"
)

const (
	stateFileName  = "state.bin"
	configFileName = "config.json"
)

// Config holds non-secret host settings stored in config.json (PROTOCOL.md §6).
type Config struct {
	Port int `json:"port"`
}

type dpapiStateStore struct {
	dir string
}

// NewDPAPIStateStore returns a StateStore backed by Windows DPAPI (CurrentUser) in dir/state.bin.
func NewDPAPIStateStore(dir string) StateStore {
	return &dpapiStateStore{dir: dir}
}

func (s *dpapiStateStore) Load() (HostState, error) {
	path := filepath.Join(s.dir, stateFileName)
	enc, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		priv := make([]byte, curve25519.ScalarSize)
		if _, err := rand.Read(priv); err != nil {
			return HostState{}, fmt.Errorf("generate host private key: %w", err)
		}
		pub, err := curve25519.X25519(priv, curve25519.Basepoint)
		if err != nil {
			return HostState{}, fmt.Errorf("derive host public key: %w", err)
		}
		st := HostState{
			PrivateKey: priv,
			PublicKey:  pub,
			Devices:    []Device{},
		}
		if err := s.Save(st); err != nil {
			return HostState{}, err
		}
		return st, nil
	}
	if err != nil {
		return HostState{}, fmt.Errorf("read state file: %w", err)
	}

	raw, err := dpapiUnprotect(enc)
	if err != nil {
		return HostState{}, err
	}

	var st HostState
	if err := json.Unmarshal(raw, &st); err != nil {
		return HostState{}, fmt.Errorf("unmarshal state: %w", err)
	}
	if st.Devices == nil {
		st.Devices = []Device{}
	}
	return st, nil
}

func (s *dpapiStateStore) Save(st HostState) error {
	if st.Devices == nil {
		st.Devices = []Device{}
	}
	raw, err := json.Marshal(st)
	if err != nil {
		return fmt.Errorf("marshal state: %w", err)
	}
	enc, err := dpapiProtect(raw)
	if err != nil {
		return err
	}
	return writeStateAtomic(s.dir, stateFileName, enc)
}

// LoadConfig reads dir/config.json. If missing, it returns DefaultPort without error.
func LoadConfig(dir string) (Config, error) {
	path := filepath.Join(dir, configFileName)
	raw, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return Config{Port: DefaultPort}, nil
	}
	if err != nil {
		return Config{}, fmt.Errorf("read config: %w", err)
	}
	var cfg Config
	if err := json.Unmarshal(raw, &cfg); err != nil {
		return Config{}, fmt.Errorf("unmarshal config: %w", err)
	}
	if cfg.Port <= 0 {
		cfg.Port = DefaultPort
	}
	return cfg, nil
}

// SaveConfig writes dir/config.json atomically.
func SaveConfig(dir string, cfg Config) error {
	if cfg.Port <= 0 {
		cfg.Port = DefaultPort
	}
	raw, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return fmt.Errorf("marshal config: %w", err)
	}
	return writeStateAtomic(dir, configFileName, raw)
}

func writeStateAtomic(dir, name string, data []byte) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("create state dir: %w", err)
	}
	tmp, err := os.CreateTemp(dir, name+".tmp.*")
	if err != nil {
		return fmt.Errorf("create temp file: %w", err)
	}
	tmpName := tmp.Name()
	cleanup := func() { _ = os.Remove(tmpName) }

	if _, err := tmp.Write(data); err != nil {
		_ = tmp.Close()
		cleanup()
		return fmt.Errorf("write temp file: %w", err)
	}
	if err := tmp.Sync(); err != nil {
		_ = tmp.Close()
		cleanup()
		return fmt.Errorf("sync temp file: %w", err)
	}
	if err := tmp.Close(); err != nil {
		cleanup()
		return fmt.Errorf("close temp file: %w", err)
	}
	target := filepath.Join(dir, name)
	if err := os.Rename(tmpName, target); err != nil {
		cleanup()
		return fmt.Errorf("rename state file: %w", err)
	}
	return nil
}
