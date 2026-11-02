package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os/exec"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"
)

const (
	tunnelInitialBackoff = 1 * time.Second
	tunnelMaxBackoff     = 30 * time.Second
	tunnelBackoffFactor  = 2
)

var tryCloudflareRE = regexp.MustCompile(`https://([a-zA-Z0-9-]+)\.trycloudflare\.com\b`)

type cloudflareTunnel struct {
	bin string
}

// NewCloudflareTunnel returns a Tunnel backed by `cloudflared tunnel --url`.
func NewCloudflareTunnel() Tunnel {
	return &cloudflareTunnel{bin: "cloudflared"}
}

func (t *cloudflareTunnel) Start(ctx context.Context, port int) (<-chan string, error) {
	if port <= 0 || port > 65535 {
		return nil, fmt.Errorf("invalid tunnel port: %d", port)
	}
	binPath, err := exec.LookPath(t.bin)
	if err != nil {
		return nil, fmt.Errorf("cloudflared not found on PATH (install with `winget install Cloudflare.cloudflared` or from https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/): %w", err)
	}

	ch := make(chan string, 1)
	go func() {
		defer close(ch)
		backoff := tunnelInitialBackoff
		for {
			if ctx.Err() != nil {
				return
			}
			gotURL := t.runOnce(ctx, binPath, port, ch)
			if ctx.Err() != nil {
				return
			}
			if gotURL {
				backoff = tunnelInitialBackoff
			}
			timer := time.NewTimer(backoff)
			select {
			case <-ctx.Done():
				timer.Stop()
				return
			case <-timer.C:
			}
			backoff *= tunnelBackoffFactor
			if backoff > tunnelMaxBackoff {
				backoff = tunnelMaxBackoff
			}
		}
	}()
	return ch, nil
}

func (t *cloudflareTunnel) runOnce(ctx context.Context, binPath string, port int, ch chan<- string) bool {
	target := fmt.Sprintf("http://127.0.0.1:%d", port)
	cmd := exec.CommandContext(ctx, binPath, "tunnel", "--url", target)
	cmd.Cancel = func() error {
		if cmd.Process == nil {
			return nil
		}
		if runtime.GOOS == "windows" {
			if err := exec.Command("taskkill", "/T", "/F", "/PID", strconv.Itoa(cmd.Process.Pid)).Run(); err == nil {
				return nil
			}
		}
		return cmd.Process.Kill()
	}

	stderr, err := cmd.StderrPipe()
	if err != nil {
		return false
	}
	if err := cmd.Start(); err != nil {
		return false
	}

	gotURL := false
	scanner := bufio.NewScanner(stderr)
	for scanner.Scan() {
		if u, ok := parseTunnelURL(scanner.Text()); ok {
			gotURL = true
			select {
			case ch <- u:
			case <-ctx.Done():
			}
			break
		}
	}
	// Drain remaining stderr so cloudflared never blocks writing logs.
	_, _ = io.Copy(io.Discard, stderr)
	_ = cmd.Wait()
	return gotURL
}

// parseTunnelURL extracts a Quick Tunnel URL (https://<subdomain>.trycloudflare.com) from a cloudflared log line.
func parseTunnelURL(line string) (string, bool) {
	m := tryCloudflareRE.FindStringSubmatch(line)
	if len(m) != 2 {
		return "", false
	}
	sub := strings.ToLower(m[1])
	if sub == "" || sub == "www" || sub == "api" || strings.HasPrefix(sub, "-") || strings.HasSuffix(sub, "-") {
		return "", false
	}
	return "https://" + sub + ".trycloudflare.com", true
}

// wsURL converts an http(s) tunnel base URL into the WebSocket endpoint URL (wss://.../ws).
func wsURL(base string) string {
	u := strings.TrimSpace(base)
	u = strings.TrimRight(u, "/")
	if u == "" {
		return ""
	}
	switch {
	case strings.HasPrefix(u, "https://"):
		u = "wss://" + strings.TrimPrefix(u, "https://")
	case strings.HasPrefix(u, "http://"):
		u = "ws://" + strings.TrimPrefix(u, "http://")
	}
	if !strings.HasSuffix(u, "/ws") {
		u += "/ws"
	}
	return u
}
