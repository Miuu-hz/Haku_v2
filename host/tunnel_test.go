package main

import (
	"context"
	"strings"
	"testing"
)

func TestParseTunnelURL(t *testing.T) {
	cases := []struct {
		name string
		line string
		want string
		ok   bool
	}{
		{
			name: "banner url line",
			line: "2026-11-03T00:00:02Z INF |  https://autumn-river-8421.trycloudflare.com                                  |",
			want: "https://autumn-river-8421.trycloudflare.com",
			ok:   true,
		},
		{
			name: "uppercase subdomain normalized",
			line: "INF Visit https://Silent-Meadow-99.trycloudflare.com/some/path for access",
			want: "https://silent-meadow-99.trycloudflare.com",
			ok:   true,
		},
		{
			name: "terms of service line ignored",
			line: "2026-11-03T00:00:00Z INF Thank you for trying Cloudflare Tunnel. https://www.cloudflare.com/website-terms/",
			want: "",
			ok:   false,
		},
		{
			name: "bare trycloudflare or api ignored",
			line: "INF https://api.trycloudflare.com and https://www.trycloudflare.com",
			want: "",
			ok:   false,
		},
		{
			name: "border line ignored",
			line: "2026-11-03T00:00:01Z INF +--------------------------------------------------------------------------------------------+",
			want: "",
			ok:   false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, ok := parseTunnelURL(tc.line)
			if ok != tc.ok || got != tc.want {
				t.Fatalf("parseTunnelURL(%q) = (%q, %v), want (%q, %v)", tc.line, got, ok, tc.want, tc.ok)
			}
		})
	}
}

func TestWSURL(t *testing.T) {
	cases := []struct {
		in   string
		want string
	}{
		{"https://autumn-river-8421.trycloudflare.com", "wss://autumn-river-8421.trycloudflare.com/ws"},
		{"https://autumn-river-8421.trycloudflare.com/", "wss://autumn-river-8421.trycloudflare.com/ws"},
		{"http://127.0.0.1:47321", "ws://127.0.0.1:47321/ws"},
		{"wss://autumn-river-8421.trycloudflare.com/ws", "wss://autumn-river-8421.trycloudflare.com/ws"},
		{"", ""},
	}
	for _, tc := range cases {
		if got := wsURL(tc.in); got != tc.want {
			t.Fatalf("wsURL(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestCloudflareTunnel_MissingBinary(t *testing.T) {
	tun := &cloudflareTunnel{bin: "cloudflared-definitely-not-installed-binary-xyz"}
	_, err := tun.Start(context.Background(), DefaultPort)
	if err == nil || !strings.Contains(err.Error(), "install") {
		t.Fatalf("expected clear install error when binary is missing, got: %v", err)
	}
}
