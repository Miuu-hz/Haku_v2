package main

import (
	"context"
	"encoding/base64"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"slices"
	"strings"
	"syscall"
)

const auditLogFileName = "audit.jsonl"

func main() {
	cfgDir, err := os.UserConfigDir()
	if err != nil {
		fmt.Fprintf(os.Stderr, "haku-host: user config dir: %v\n", err)
		os.Exit(1)
	}
	dir := filepath.Join(cfgDir, "haku-host")
	store := NewDPAPIStateStore(dir)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := runCLI(ctx, os.Args[1:], dir, store, os.Stdin, os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "haku-host: %v\n", err)
		os.Exit(1)
	}
}

func runCLI(ctx context.Context, args []string, dir string, store StateStore, in io.Reader, out io.Writer) error {
	if len(args) == 0 {
		return errors.New("usage: haku-host <serve|pair|devices|allow|revoke|audit>")
	}

	switch args[0] {
	case "serve":
		return cmdServe(ctx, args[1:], dir, store, out)
	case "pair":
		return cmdPair(ctx, args[1:], dir, store, in, out)
	case "devices":
		return cmdDevices(store, out)
	case "allow":
		return cmdAllow(args[1:], store, out)
	case "revoke":
		return cmdRevoke(args[1:], store, out)
	case "audit":
		return cmdAudit(args[1:], dir, out)
	default:
		return fmt.Errorf("unknown subcommand %q", args[0])
	}
}

func cmdServe(ctx context.Context, args []string, dir string, store StateStore, out io.Writer) error {
	cfg, err := LoadConfig(dir)
	if err != nil {
		return err
	}

	fs := flag.NewFlagSet("serve", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	port := fs.Int("port", cfg.Port, "listen port on 127.0.0.1")
	useTunnel := fs.Bool("tunnel", false, "expose via Cloudflare Quick Tunnel and announce on ntfy")
	if err := fs.Parse(args); err != nil {
		return err
	}
	if *port <= 0 || *port > 65535 {
		return fmt.Errorf("invalid port: %d", *port)
	}
	if *port != cfg.Port {
		_ = SaveConfig(dir, Config{Port: *port})
	}

	audit := NewAuditLog(filepath.Join(dir, auditLogFileName))
	sessions := NewSessionManager(audit, NewClaudeAdapter())
	srv := NewServer(store, sessions, audit)

	if *useTunnel {
		tun := NewCloudflareTunnel()
		announcer := NewNtfyAnnouncer()
		urlCh, err := tun.Start(ctx, *port)
		if err != nil {
			return err
		}
		go func() {
			for base := range urlCh {
				endpoint := wsURL(base)
				if endpoint == "" {
					continue
				}
				_, _ = fmt.Fprintf(out, "tunnel: %s\n", endpoint)
				st, err := store.Load()
				if err != nil {
					continue
				}
				for _, d := range st.Devices {
					_ = announcer.AnnounceURL(ctx, d, endpoint)
				}
			}
		}()
	}

	_, _ = fmt.Fprintf(out, "listening on 127.0.0.1:%d\n", *port)
	return srv.ListenAndServe(ctx, *port)
}

func cmdPair(ctx context.Context, args []string, dir string, store StateStore, in io.Reader, out io.Writer) error {
	cfg, err := LoadConfig(dir)
	if err != nil {
		return err
	}

	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	port := fs.Int("port", cfg.Port, "listen port on 127.0.0.1")
	pairURL := fs.String("url", "", "optional wss://.../ws URL to embed in QR")
	if err := fs.Parse(args); err != nil {
		return err
	}

	audit := NewAuditLog(filepath.Join(dir, auditLogFileName))
	sessions := NewSessionManager(audit, NewClaudeAdapter())
	srv := NewServer(store, sessions, audit)

	qr, doneCh, err := srv.StartPairing(*pairURL, ConfirmFromReader(in, out))
	if err != nil {
		return err
	}
	if _, err := PrintPairingQR(out, qr); err != nil {
		return err
	}

	pairCtx, cancel := context.WithTimeout(ctx, PairingTTL)
	defer cancel()

	errCh := make(chan error, 1)
	go func() {
		errCh <- srv.ListenAndServe(pairCtx, *port)
	}()

	select {
	case pairErr := <-doneCh:
		cancel()
		<-errCh
		return pairErr
	case srvErr := <-errCh:
		return srvErr
	case <-pairCtx.Done():
		return pairCtx.Err()
	}
}

func cmdDevices(store StateStore, out io.Writer) error {
	st, err := store.Load()
	if err != nil {
		return err
	}
	if len(st.Devices) == 0 {
		_, _ = fmt.Fprintln(out, "no paired devices")
		return nil
	}
	for _, d := range st.Devices {
		_, _ = fmt.Fprintf(
			out,
			"%s\tkey=%s\tclis=%s\tdirs=%s\tapprove=%v\n",
			d.Name,
			base64.StdEncoding.EncodeToString(d.PublicKey),
			strings.Join(d.Permissions.CLIs, ","),
			strings.Join(d.Permissions.Dirs, ";"),
			d.Permissions.CanApprove,
		)
	}
	return nil
}

func cmdAllow(args []string, store StateStore, out io.Writer) error {
	reordered := reorderFlagsFirst(args)
	fs := flag.NewFlagSet("allow", flag.ContinueOnError)
	fs.SetOutput(io.Discard)
	cliName := fs.String("cli", "", "CLI name to allow")
	dirPath := fs.String("dir", "", "directory path to allow")
	approve := fs.Bool("approve", false, "allow responding to approvals")
	if err := fs.Parse(reordered); err != nil {
		return err
	}
	if fs.NArg() < 1 {
		return errors.New("usage: haku-host allow <device> --cli <name> --dir <path> [--approve]")
	}
	deviceName := strings.TrimSpace(fs.Arg(0))
	if deviceName == "" {
		return errors.New("device name is required")
	}

	st, err := store.Load()
	if err != nil {
		return err
	}
	idx := -1
	for i := range st.Devices {
		if st.Devices[i].Name == deviceName {
			idx = i
			break
		}
	}
	if idx < 0 {
		return fmt.Errorf("device %q not found", deviceName)
	}

	dev := &st.Devices[idx]
	if c := strings.TrimSpace(*cliName); c != "" {
		if !slices.Contains(dev.Permissions.CLIs, c) {
			dev.Permissions.CLIs = append(dev.Permissions.CLIs, c)
		}
	}
	if d := strings.TrimSpace(*dirPath); d != "" {
		resolved, err := resolveCanonicalPath(d)
		if err != nil {
			return fmt.Errorf("resolve dir %q: %w", d, err)
		}
		if !slices.Contains(dev.Permissions.Dirs, resolved) {
			dev.Permissions.Dirs = append(dev.Permissions.Dirs, resolved)
		}
	}
	if *approve {
		dev.Permissions.CanApprove = true
	}

	if err := store.Save(st); err != nil {
		return err
	}
	_, _ = fmt.Fprintf(out, "updated permissions for %s\n", deviceName)
	return nil
}

func cmdRevoke(args []string, store StateStore, out io.Writer) error {
	if len(args) < 1 || strings.TrimSpace(args[0]) == "" {
		return errors.New("usage: haku-host revoke <device>")
	}
	deviceName := strings.TrimSpace(args[0])
	st, err := store.Load()
	if err != nil {
		return err
	}
	filtered := make([]Device, 0, len(st.Devices))
	found := false
	for _, d := range st.Devices {
		if d.Name == deviceName {
			found = true
			continue
		}
		filtered = append(filtered, d)
	}
	if !found {
		return fmt.Errorf("device %q not found", deviceName)
	}
	st.Devices = filtered
	if err := store.Save(st); err != nil {
		return err
	}
	_, _ = fmt.Fprintf(out, "revoked %s\n", deviceName)
	return nil
}

func cmdAudit(args []string, dir string, out io.Writer) error {
	if len(args) < 1 || args[0] != "verify" {
		return errors.New("usage: haku-host audit verify")
	}
	log := NewAuditLog(filepath.Join(dir, auditLogFileName))
	if err := log.Verify(); err != nil {
		return err
	}
	_, _ = fmt.Fprintln(out, "audit log verified")
	return nil
}

func reorderFlagsFirst(args []string) []string {
	var flags, pos []string
	for i := 0; i < len(args); i++ {
		a := args[i]
		if strings.HasPrefix(a, "-") {
			flags = append(flags, a)
			if !strings.Contains(a, "=") && a != "--approve" && a != "-approve" && i+1 < len(args) {
				flags = append(flags, args[i+1])
				i++
			}
		} else {
			pos = append(pos, a)
		}
	}
	return append(flags, pos...)
}
