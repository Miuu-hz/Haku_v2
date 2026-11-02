package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const auditZeroPrev = "0000000000000000000000000000000000000000000000000000000000000000"

type auditPreHash struct {
	At     int64           `json:"at"`
	Device string          `json:"device"`
	Kind   string          `json:"kind"`
	Detail json.RawMessage `json:"detail"`
	Prev   string          `json:"prev"`
}

type auditEntry struct {
	At     int64           `json:"at"`
	Device string          `json:"device"`
	Kind   string          `json:"kind"`
	Detail json.RawMessage `json:"detail"`
	Prev   string          `json:"prev"`
	Hash   string          `json:"hash"`
}

// FileAuditLog implements AuditLog over an append-only JSONL file (PROTOCOL.md §7).
type FileAuditLog struct {
	path string
	mu   sync.Mutex
}

func NewAuditLog(path string) *FileAuditLog {
	return &FileAuditLog{path: path}
}

func (l *FileAuditLog) Append(device, kind string, detail map[string]any) error {
	l.mu.Lock()
	defer l.mu.Unlock()

	if detail == nil {
		detail = map[string]any{}
	}
	detailRaw, err := json.Marshal(detail)
	if err != nil {
		return fmt.Errorf("marshal audit detail: %w", err)
	}

	prev, err := l.lastHash()
	if err != nil {
		return err
	}

	pre := auditPreHash{
		At:     time.Now().UnixMilli(),
		Device: device,
		Kind:   kind,
		Detail: detailRaw,
		Prev:   prev,
	}
	hash, err := computeAuditHash(pre)
	if err != nil {
		return err
	}

	entry := auditEntry{
		At:     pre.At,
		Device: pre.Device,
		Kind:   pre.Kind,
		Detail: pre.Detail,
		Prev:   pre.Prev,
		Hash:   hash,
	}
	line, err := json.Marshal(entry)
	if err != nil {
		return fmt.Errorf("marshal audit entry: %w", err)
	}

	if err := os.MkdirAll(filepath.Dir(l.path), 0o700); err != nil {
		return fmt.Errorf("create audit dir: %w", err)
	}
	f, err := os.OpenFile(l.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return fmt.Errorf("open audit log: %w", err)
	}
	defer f.Close()

	line = append(line, '\n')
	if _, err := f.Write(line); err != nil {
		return fmt.Errorf("write audit log: %w", err)
	}
	return nil
}

func (l *FileAuditLog) Verify() error {
	l.mu.Lock()
	defer l.mu.Unlock()

	f, err := os.Open(l.path)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("open audit log: %w", err)
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	expectedPrev := auditZeroPrev
	lineNum := 0

	for scanner.Scan() {
		raw := bytes.TrimSpace(scanner.Bytes())
		if len(raw) == 0 {
			continue
		}
		lineNum++
		var entry auditEntry
		if err := json.Unmarshal(raw, &entry); err != nil {
			return fmt.Errorf("audit log broken at line %d: invalid json: %w", lineNum, err)
		}
		if entry.Prev != expectedPrev {
			return fmt.Errorf("audit log broken at line %d: prev mismatch (got %s, want %s)", lineNum, entry.Prev, expectedPrev)
		}
		wantHash, err := computeAuditHash(auditPreHash{
			At:     entry.At,
			Device: entry.Device,
			Kind:   entry.Kind,
			Detail: entry.Detail,
			Prev:   entry.Prev,
		})
		if err != nil {
			return fmt.Errorf("audit log broken at line %d: %w", lineNum, err)
		}
		if entry.Hash != wantHash {
			return fmt.Errorf("audit log broken at line %d: hash mismatch (got %s, want %s)", lineNum, entry.Hash, wantHash)
		}
		expectedPrev = entry.Hash
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("read audit log: %w", err)
	}
	return nil
}

func (l *FileAuditLog) lastHash() (string, error) {
	f, err := os.Open(l.path)
	if errors.Is(err, os.ErrNotExist) {
		return auditZeroPrev, nil
	}
	if err != nil {
		return "", fmt.Errorf("open audit log: %w", err)
	}
	defer f.Close()

	var lastLine []byte
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		trimmed := bytes.TrimSpace(scanner.Bytes())
		if len(trimmed) > 0 {
			lastLine = bytes.Clone(trimmed)
		}
	}
	if err := scanner.Err(); err != nil {
		return "", fmt.Errorf("scan audit log: %w", err)
	}
	if len(lastLine) == 0 {
		return auditZeroPrev, nil
	}
	var entry auditEntry
	if err := json.Unmarshal(lastLine, &entry); err != nil {
		return "", fmt.Errorf("parse last audit entry: %w", err)
	}
	if strings.TrimSpace(entry.Hash) == "" {
		return "", errors.New("last audit entry missing hash")
	}
	return entry.Hash, nil
}

func computeAuditHash(pre auditPreHash) (string, error) {
	raw, err := json.Marshal(pre)
	if err != nil {
		return "", fmt.Errorf("marshal audit pre-hash: %w", err)
	}
	sum := sha256.Sum256(raw)
	return hex.EncodeToString(sum[:]), nil
}
