package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
)

func TestCheckPermission_Table(t *testing.T) {
	perms := DevicePermissions{
		CLIs: []string{"claude"},
		Dirs: []string{`C:\work`},
	}

	cases := []struct {
		name      string
		perms     DevicePermissions
		cli       string
		cwd       string
		wantAllow bool
	}{
		{
			name:      "exact allowed dir",
			perms:     perms,
			cli:       "claude",
			cwd:       `C:\work`,
			wantAllow: true,
		},
		{
			name:      "subdirectory inside allowed dir",
			perms:     perms,
			cli:       "claude",
			cwd:       `C:\work\haku-v2\host`,
			wantAllow: true,
		},
		{
			name:      "case-insensitive match on windows",
			perms:     perms,
			cli:       "claude",
			cwd:       `c:\WORK\sub`,
			wantAllow: true,
		},
		{
			name:      "prefix trick C:\\workshop rejected",
			perms:     perms,
			cli:       "claude",
			cwd:       `C:\workshop`,
			wantAllow: false,
		},
		{
			name:      "prefix trick C:\\work-other rejected",
			perms:     perms,
			cli:       "claude",
			cwd:       `C:\work-other\sub`,
			wantAllow: false,
		},
		{
			name:      "dot-dot traversal out of allowed dir rejected",
			perms:     perms,
			cli:       "claude",
			cwd:       `C:\work\..\workshop`,
			wantAllow: false,
		},
		{
			name:      "dot-dot to parent root rejected",
			perms:     perms,
			cli:       "claude",
			cwd:       `C:\work\..`,
			wantAllow: false,
		},
		{
			name:      "unallowed cli rejected",
			perms:     perms,
			cli:       "codex",
			cwd:       `C:\work`,
			wantAllow: false,
		},
		{
			name:      "empty permissions reject everything",
			perms:     DevicePermissions{},
			cli:       "claude",
			cwd:       `C:\work`,
			wantAllow: false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := checkPermission(tc.perms, tc.cli, tc.cwd)
			if tc.wantAllow && err != nil {
				t.Fatalf("expected allowed for (%q, %q), got error: %v", tc.cli, tc.cwd, err)
			}
			if !tc.wantAllow && err == nil {
				t.Fatalf("expected denied for (%q, %q), got nil", tc.cli, tc.cwd)
			}
		})
	}
}

func TestCheckPermission_SymlinkEscape(t *testing.T) {
	root := t.TempDir()
	allowed := filepath.Join(root, "allowed")
	outside := filepath.Join(root, "outside")
	if err := os.MkdirAll(allowed, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(outside, 0o700); err != nil {
		t.Fatal(err)
	}

	link := filepath.Join(allowed, "escape")
	if err := os.Symlink(outside, link); err != nil {
		// On Windows without Developer Mode, os.Symlink may require privileges; skip only symlink creation if denied.
		t.Skipf("symlink creation not permitted in this environment: %v", err)
	}

	perms := DevicePermissions{
		CLIs: []string{"claude"},
		Dirs: []string{allowed},
	}
	if _, err := checkPermission(perms, "claude", link); err == nil {
		t.Fatalf("expected symlink pointing outside allowed dir to be rejected")
	}
}

func TestDispatchRequest_ErrorCodes(t *testing.T) {
	dir := t.TempDir()
	dev := Device{
		Name:      "Phone1",
		PublicKey: []byte("01234567890123456789012345678901"),
		Permissions: DevicePermissions{
			CLIs:       []string{"claude", "missing-cli"},
			Dirs:       []string{dir},
			CanApprove: false,
		},
	}
	mgr := NewSessionManager(nil)

	cases := []struct {
		name     string
		dev      Device
		req      Frame
		wantCode string
	}{
		{
			name:     "unknown method",
			dev:      dev,
			req:      Frame{T: "req", ID: "1", Method: "no.such.method"},
			wantCode: ErrCodeBadRequest,
		},
		{
			name:     "session.start disallowed cli",
			dev:      dev,
			req:      Frame{T: "req", ID: "2", Method: "session.start", Params: map[string]any{"cli": "codex", "cwd": dir, "prompt": "hi"}},
			wantCode: ErrCodeNotAllowed,
		},
		{
			name:     "session.start disallowed dir",
			dev:      dev,
			req:      Frame{T: "req", ID: "3", Method: "session.start", Params: map[string]any{"cli": "claude", "cwd": filepath.Dir(dir), "prompt": "hi"}},
			wantCode: ErrCodeNotAllowed,
		},
		{
			name:     "session.start missing cli adapter",
			dev:      dev,
			req:      Frame{T: "req", ID: "4", Method: "session.start", Params: map[string]any{"cli": "missing-cli", "cwd": dir, "prompt": "hi"}},
			wantCode: ErrCodeCLIMissing,
		},
		{
			name:     "session.events unknown session",
			dev:      dev,
			req:      Frame{T: "req", ID: "5", Method: "session.events", Params: map[string]any{"sessionId": "nope", "afterSeq": float64(0)}},
			wantCode: ErrCodeUnknownSession,
		},
		{
			name:     "session.stop unknown session",
			dev:      dev,
			req:      Frame{T: "req", ID: "6", Method: "session.stop", Params: map[string]any{"sessionId": "nope"}},
			wantCode: ErrCodeUnknownSession,
		},
		{
			name:     "approval.respond without canApprove permission",
			dev:      dev,
			req:      Frame{T: "req", ID: "7", Method: "approval.respond", Params: map[string]any{"approvalId": "a1", "decision": "allow_once"}},
			wantCode: ErrCodeNotAllowed,
		},
		{
			name: "approval.respond unknown approval when permitted",
			dev: Device{
				Name:        "Phone1",
				PublicKey:   dev.PublicKey,
				Permissions: DevicePermissions{CanApprove: true},
			},
			req:      Frame{T: "req", ID: "8", Method: "approval.respond", Params: map[string]any{"approvalId": "a1", "decision": "deny"}},
			wantCode: ErrCodeUnknownApproval,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			resp := dispatchRequest(context.Background(), tc.dev, mgr, nil, tc.req)
			if resp.OK == nil || *resp.OK || resp.Error == nil {
				t.Fatalf("expected error frame, got %+v", resp)
			}
			if resp.Error.Code != tc.wantCode {
				t.Fatalf("Error.Code = %q, want %q (msg=%q)", resp.Error.Code, tc.wantCode, resp.Error.Message)
			}
		})
	}
}
