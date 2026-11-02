package main

import (
	"bytes"
	"context"
	"path/filepath"
	"strings"
	"testing"
)

func TestCLI_DevicesAllowRevokeAuditVerify(t *testing.T) {
	dir := t.TempDir()
	store := newMemStateStore(t)

	st, _ := store.Load()
	st.Devices = append(st.Devices, Device{
		Name:      "Fold5",
		PublicKey: []byte("01234567890123456789012345678901"),
		Permissions: DevicePermissions{
			CLIs: []string{},
			Dirs: []string{},
		},
	})
	_ = store.Save(st)

	ctx := context.Background()
	var out bytes.Buffer

	// 1. allow <device> --cli claude --dir <dir> --approve
	allowDir := filepath.Join(dir, "workspace")
	if err := runCLI(ctx, []string{"allow", "Fold5", "--cli", "claude", "--dir", allowDir, "--approve"}, dir, store, strings.NewReader(""), &out); err != nil {
		t.Fatalf("allow failed: %v", err)
	}

	updated, _ := store.Load()
	dev := updated.Devices[0]
	if len(dev.Permissions.CLIs) != 1 || dev.Permissions.CLIs[0] != "claude" || !dev.Permissions.CanApprove || len(dev.Permissions.Dirs) != 1 {
		t.Fatalf("unexpected permissions after allow: %+v", dev.Permissions)
	}

	// 2. devices
	out.Reset()
	if err := runCLI(ctx, []string{"devices"}, dir, store, strings.NewReader(""), &out); err != nil {
		t.Fatalf("devices failed: %v", err)
	}
	if !strings.Contains(out.String(), "Fold5") || !strings.Contains(out.String(), "claude") {
		t.Fatalf("unexpected devices output: %s", out.String())
	}

	// 3. revoke <device>
	out.Reset()
	if err := runCLI(ctx, []string{"revoke", "Fold5"}, dir, store, strings.NewReader(""), &out); err != nil {
		t.Fatalf("revoke failed: %v", err)
	}
	afterRevoke, _ := store.Load()
	if len(afterRevoke.Devices) != 0 {
		t.Fatalf("expected 0 devices after revoke, got %d", len(afterRevoke.Devices))
	}

	// 4. audit verify
	audit := NewAuditLog(filepath.Join(dir, auditLogFileName))
	if err := audit.Append("Fold5", "pairing", map[string]any{"topic": "t1"}); err != nil {
		t.Fatal(err)
	}
	out.Reset()
	if err := runCLI(ctx, []string{"audit", "verify"}, dir, store, strings.NewReader(""), &out); err != nil {
		t.Fatalf("audit verify failed: %v", err)
	}
}
