package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"golang.org/x/crypto/chacha20poly1305"
)

const (
	defaultNtfyBaseURL = "https://ntfy.sh"
	announceAAD        = "haku-announce/1"
	announceTimeout    = 10 * time.Second
)

// NtfyAnnouncer publishes encrypted tunnel URLs and wake-ups to ntfy topics (PROTOCOL.md §9).
type NtfyAnnouncer struct {
	BaseURL string
	Client  *http.Client
}

// NewNtfyAnnouncer returns an Announcer configured for https://ntfy.sh.
func NewNtfyAnnouncer() Announcer {
	return &NtfyAnnouncer{
		BaseURL: defaultNtfyBaseURL,
		Client:  &http.Client{Timeout: announceTimeout},
	}
}

func (a *NtfyAnnouncer) AnnounceURL(ctx context.Context, d Device, wsURL string) error {
	if strings.TrimSpace(wsURL) == "" {
		return errors.New("announce: empty wsURL")
	}
	return a.publish(ctx, d, wsURL)
}

func (a *NtfyAnnouncer) Wake(ctx context.Context, d Device) error {
	return a.publish(ctx, d, "")
}

func (a *NtfyAnnouncer) publish(ctx context.Context, d Device, plaintext string) error {
	topic := strings.TrimSpace(d.Topic)
	if topic == "" || strings.ContainsAny(topic, "/\\?#") || topic == "." || topic == ".." {
		return errors.New("announce: invalid device topic")
	}
	body, err := sealAnnounce(d.AnnounceKey, plaintext)
	if err != nil {
		return err
	}

	base := strings.TrimRight(a.BaseURL, "/")
	if base == "" {
		base = defaultNtfyBaseURL
	}
	endpoint := base + "/" + url.PathEscape(topic)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(body))
	if err != nil {
		return fmt.Errorf("announce request: %w", err)
	}

	client := a.Client
	if client == nil {
		client = &http.Client{Timeout: announceTimeout}
	}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("announce post: %w", err)
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("announce post failed: status %d", resp.StatusCode)
	}
	return nil
}

// sealAnnounce encrypts plaintext as base64url(nonce(12) || ChaCha20-Poly1305(key, nonce, plaintext, aad="haku-announce/1")).
func sealAnnounce(key []byte, plaintext string) (string, error) {
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return "", fmt.Errorf("announce cipher: %w", err)
	}
	nonce := make([]byte, chacha20poly1305.NonceSize)
	if _, err := rand.Read(nonce); err != nil {
		return "", fmt.Errorf("announce nonce: %w", err)
	}
	out := make([]byte, chacha20poly1305.NonceSize, chacha20poly1305.NonceSize+len(plaintext)+chacha20poly1305.Overhead)
	copy(out, nonce)
	out = aead.Seal(out, nonce, []byte(plaintext), []byte(announceAAD))
	return base64.RawURLEncoding.EncodeToString(out), nil
}

// openAnnounce decrypts a payload produced by sealAnnounce.
func openAnnounce(key []byte, body string) (string, error) {
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return "", fmt.Errorf("announce cipher: %w", err)
	}
	trimmed := strings.TrimRight(strings.TrimSpace(body), "=")
	raw, err := base64.RawURLEncoding.DecodeString(trimmed)
	if err != nil {
		return "", fmt.Errorf("announce base64 decode: %w", err)
	}
	if len(raw) < chacha20poly1305.NonceSize+chacha20poly1305.Overhead {
		return "", errors.New("announce payload too short")
	}
	nonce := raw[:chacha20poly1305.NonceSize]
	ct := raw[chacha20poly1305.NonceSize:]
	pt, err := aead.Open(nil, nonce, ct, []byte(announceAAD))
	if err != nil {
		return "", fmt.Errorf("announce decrypt: %w", err)
	}
	return string(pt), nil
}
