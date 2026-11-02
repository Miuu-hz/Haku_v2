package main

import (
	"bufio"
	"bytes"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/mdp/qrterminal/v3"
	"golang.org/x/crypto/blake2s"
)

const (
	PairingTTL      = 5 * time.Minute
	pairingAnnounce = "haku-announce"
)

// PairingQR is the JSON payload encoded into the terminal QR code (PROTOCOL.md §3).
type PairingQR struct {
	V       int    `json:"v"`
	HostKey string `json:"hostKey"`
	URL     string `json:"url"`
	Topic   string `json:"topic"`
	OTP     string `json:"otp"`
	Exp     int64  `json:"exp"`
}

// PrintPairingQR renders the PairingQR JSON as a QR code to w using qrterminal.
func PrintPairingQR(w io.Writer, qr PairingQR) (string, error) {
	raw, err := json.Marshal(qr)
	if err != nil {
		return "", fmt.Errorf("marshal pairing qr: %w", err)
	}
	s := string(raw)
	qrterminal.GenerateHalfBlock(s, qrterminal.L, w)
	return s, nil
}

// ComputeSAS derives the 6-digit zero-padded SAS code from a Noise handshake hash (PROTOCOL.md §3).
func ComputeSAS(handshakeHash []byte) string {
	sum := blake2s.Sum256(handshakeHash)
	val := binary.BigEndian.Uint32(sum[:4]) % 1_000_000
	return fmt.Sprintf("%06d", val)
}

// DeriveAnnounceKey computes BLAKE2s-256(handshakeHash || "haku-announce") (PROTOCOL.md §3).
func DeriveAnnounceKey(handshakeHash []byte) []byte {
	buf := make([]byte, 0, len(handshakeHash)+len(pairingAnnounce))
	buf = append(buf, handshakeHash...)
	buf = append(buf, pairingAnnounce...)
	sum := blake2s.Sum256(buf)
	return sum[:]
}

// ConfirmFromReader prompts for y/N confirmation of the SAS on out and reads the response from in.
func ConfirmFromReader(in io.Reader, out io.Writer) func(sas string) bool {
	return func(sas string) bool {
		if out != nil {
			_, _ = fmt.Fprintf(out, "Confirm pairing SAS %s [y/N]: ", sas)
		}
		reader := bufio.NewReader(in)
		line, err := reader.ReadString('\n')
		if err != nil && !errors.Is(err, io.EOF) {
			return false
		}
		ans := strings.ToLower(strings.TrimSpace(line))
		return ans == "y" || ans == "yes"
	}
}

type pairingTicket struct {
	otp       string
	topic     string
	expiresAt time.Time
	used      bool
	confirm   func(sas string) bool
	done      chan error
}

type pairingState struct {
	mu     sync.Mutex
	ticket *pairingTicket
	nowFn  func() time.Time
}

func newPairingState() *pairingState {
	return &pairingState{
		nowFn: time.Now,
	}
}

func (p *pairingState) create(hostPubKey []byte, wsURL string, confirm func(sas string) bool) (PairingQR, <-chan error, error) {
	if len(hostPubKey) == 0 {
		return PairingQR{}, nil, errors.New("missing host public key")
	}
	otp, err := newRandomToken(16)
	if err != nil {
		return PairingQR{}, nil, err
	}
	topic, err := newRandomToken(16)
	if err != nil {
		return PairingQR{}, nil, err
	}
	if confirm == nil {
		confirm = ConfirmFromReader(os.Stdin, os.Stdout)
	}

	p.mu.Lock()
	now := p.nowFn()
	exp := now.Add(PairingTTL)
	done := make(chan error, 1)
	p.ticket = &pairingTicket{
		otp:       otp,
		topic:     topic,
		expiresAt: exp,
		confirm:   confirm,
		done:      done,
	}
	p.mu.Unlock()

	qr := PairingQR{
		V:       1,
		HostKey: base64.StdEncoding.EncodeToString(hostPubKey),
		URL:     wsURL,
		Topic:   topic,
		OTP:     otp,
		Exp:     exp.Unix(),
	}
	return qr, done, nil
}

func (p *pairingState) consume(otp string) (topic string, confirm func(string) bool, done chan error, ok bool) {
	if otp == "" {
		return "", nil, nil, false
	}
	p.mu.Lock()
	defer p.mu.Unlock()

	t := p.ticket
	if t == nil || t.used {
		return "", nil, nil, false
	}
	now := p.nowFn()
	if now.After(t.expiresAt) {
		return "", nil, nil, false
	}
	if subtle.ConstantTimeCompare([]byte(t.otp), []byte(otp)) != 1 {
		return "", nil, nil, false
	}
	// Burn OTP on first valid match (single use).
	t.used = true
	return t.topic, t.confirm, t.done, true
}

func storePairedDevice(store StateStore, dev Device) error {
	st, err := store.Load()
	if err != nil {
		return err
	}
	if dev.Permissions.CLIs == nil {
		dev.Permissions.CLIs = []string{}
	}
	if dev.Permissions.Dirs == nil {
		dev.Permissions.Dirs = []string{}
	}
	dev.Permissions.CanApprove = false

	replaced := false
	for i := range st.Devices {
		if bytes.Equal(st.Devices[i].PublicKey, dev.PublicKey) || st.Devices[i].Name == dev.Name {
			st.Devices[i] = dev
			replaced = true
			break
		}
	}
	if !replaced {
		st.Devices = append(st.Devices, dev)
	}
	return store.Save(st)
}
