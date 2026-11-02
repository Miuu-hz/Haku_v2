package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAuditLog_ChainAndVerify(t *testing.T) {
	path := filepath.Join(t.TempDir(), "audit.jsonl")
	log := NewAuditLog(path)

	// Empty / non-existent log verifies cleanly.
	if err := log.Verify(); err != nil {
		t.Fatalf("Verify on missing file failed: %v", err)
	}

	steps := []struct {
		device string
		kind   string
		detail map[string]any
	}{
		{"Pixel9", "pairing", map[string]any{"topic": "t1"}},
		{"Pixel9", "connect", nil},
		{"Pixel9", "session.start", map[string]any{"sessionId": "s1", "cli": "claude"}},
		{"Pixel9", "session.stop", map[string]any{"sessionId": "s1"}},
		{"Pixel9", "session end", map[string]any{"sessionId": "s1", "state": "stopped"}},
	}

	for _, s := range steps {
		if err := log.Append(s.device, s.kind, s.detail); err != nil {
			t.Fatalf("Append(%s) failed: %v", s.kind, err)
		}
	}

	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("ReadFile failed: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(string(raw)), "\n")
	if len(lines) != len(steps) {
		t.Fatalf("got %d lines, want %d", len(lines), len(steps))
	}
	if !strings.Contains(lines[0], `"prev":"`+auditZeroPrev+`"`) {
		t.Fatalf("first line does not contain 64-zero prev: %s", lines[0])
	}

	// Re-open with a new FileAuditLog instance to verify persistence across restarts.
	log2 := NewAuditLog(path)
	if err := log2.Append("Pixel9", "connect", map[string]any{"reconnected": true}); err != nil {
		t.Fatalf("Append on reopened log failed: %v", err)
	}
	if err := log2.Verify(); err != nil {
		t.Fatalf("Verify failed: %v", err)
	}
}

func TestAuditLog_TamperDetection(t *testing.T) {
	cases := []struct {
		name     string
		mutate   func(lines []string) []string
		wantLine string
	}{
		{
			name: "tamper detail in line 2",
			mutate: func(lines []string) []string {
				lines[1] = strings.Replace(lines[1], `"claude"`, `"codex"`, 1)
				return lines
			},
			wantLine: "line 2",
		},
		{
			name: "delete line 2 breaks prev on line 3",
			mutate: func(lines []string) []string {
				return []string{lines[0], lines[2]}
			},
			wantLine: "line 2",
		},
		{
			name: "corrupt json on line 3",
			mutate: func(lines []string) []string {
				lines[2] = "{not-valid-json"
				return lines
			},
			wantLine: "line 3",
		},
		{
			name: "tamper first line prev",
			mutate: func(lines []string) []string {
				lines[0] = strings.Replace(lines[0], auditZeroPrev, strings.Repeat("a", 64), 1)
				return lines
			},
			wantLine: "line 1",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "audit.jsonl")
			log := NewAuditLog(path)
			_ = log.Append("Phone", "pairing", map[string]any{"topic": "abc"})
			_ = log.Append("Phone", "session.start", map[string]any{"cli": "claude"})
			_ = log.Append("Phone", "session.stop", map[string]any{"sessionId": "123"})

			raw, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("ReadFile: %v", err)
			}
			lines := strings.Split(strings.TrimSpace(string(raw)), "\n")
			mutated := tc.mutate(lines)
			if err := os.WriteFile(path, []byte(strings.Join(mutated, "\n")+"\n"), 0o600); err != nil {
				t.Fatalf("WriteFile: %v", err)
			}

			err = log.Verify()
			if err == nil {
				t.Fatalf("expected Verify error, got nil")
			}
			if !strings.Contains(err.Error(), tc.wantLine) {
				t.Fatalf("Verify error = %q, want mention of %q", err.Error(), tc.wantLine)
			}
		})
	}
}
