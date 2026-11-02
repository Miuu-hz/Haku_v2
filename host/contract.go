// Package main is haku-host (P7.0): one binary on the user's PC that lets the Haku phone app drive AI CLIs.
//
// contract.go is the shared contract written by Claude; the protocol is PROTOCOL.md. Agents implement against it
// and do not change it. File ownership:
//   - core agent:   main.go (subcommands), server.go (WebSocket + Noise), pairing.go, frames.go, sessions.go,
//     cli_claude.go, audit.go, *_test.go for those
//   - infra agent:  state.go (DPAPI-encrypted state), tunnel.go (cloudflared), announce.go (ntfy), *_test.go for those
package main

import "context"

const (
	ProtocolVersion = "1"
	NoisePrologue   = "haku-host/1"
	DefaultPort     = 47321
	MaxPlaintext    = 64 * 1024
)

// DevicePermissions starts empty for every new device: nothing allowed (PROTOCOL.md §5).
type DevicePermissions struct {
	CLIs       []string `json:"clis"`
	Dirs       []string `json:"dirs"`
	CanApprove bool     `json:"canApprove"`
}

// Device is one paired phone. PublicKey is the phone's X25519 static key (32 bytes).
type Device struct {
	Name        string            `json:"name"`
	PublicKey   []byte            `json:"publicKey"`
	Topic       string            `json:"topic"`
	AnnounceKey []byte            `json:"announceKey"`
	PairedAtMs  int64             `json:"pairedAtMs"`
	Permissions DevicePermissions `json:"permissions"`
}

// HostState is everything secret, stored only through a StateStore (DPAPI on Windows).
type HostState struct {
	PrivateKey []byte   `json:"privateKey"`
	PublicKey  []byte   `json:"publicKey"`
	Devices    []Device `json:"devices"`
}

// StateStore loads and saves HostState encrypted at rest. Load on a fresh install returns a new state with a generated keypair.
type StateStore interface {
	Load() (HostState, error)
	Save(HostState) error
}

// Frame is the JSON envelope inside Noise transport messages (PROTOCOL.md §4). Exactly one of the field groups is used per T.
type Frame struct {
	T         string         `json:"t"` // "req" | "res" | "event"
	ID        string         `json:"id,omitempty"`
	Method    string         `json:"method,omitempty"`
	Params    map[string]any `json:"params,omitempty"`
	OK        *bool          `json:"ok,omitempty"`
	Result    any            `json:"result,omitempty"`
	Error     *FrameError    `json:"error,omitempty"`
	Event     string         `json:"event,omitempty"`
	Seq       int64          `json:"seq,omitempty"`
	SessionID string         `json:"sessionId,omitempty"`
	Data      map[string]any `json:"data,omitempty"`
}

type FrameError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type SessionState string

const (
	StateRunning         SessionState = "running"
	StateWaitingApproval SessionState = "waiting_approval"
	StateDone            SessionState = "done"
	StateFailed          SessionState = "failed"
	StateStopped         SessionState = "stopped"
)

type SessionSummary struct {
	SessionID   string       `json:"sessionId"`
	CLI         string       `json:"cli"`
	Cwd         string       `json:"cwd"`
	State       SessionState `json:"state"`
	StartedAtMs int64        `json:"startedAtMs"`
	LastSeq     int64        `json:"lastSeq"`
}

// CLIEvent is what an adapter reports while the CLI runs; sessions.go turns it into event frames with seq numbers.
type CLIEvent struct {
	Output   string // non-empty → session.output
	Result   string // non-empty on the final message → session.result
	ExitCode *int   // set once, when the process ended
}

// CLIAdapter runs one AI CLI headless. Run blocks until the process ends or ctx is cancelled (then it kills the process tree).
type CLIAdapter interface {
	Name() string
	// Version returns the installed version, or an error when the CLI is not on PATH.
	Version(ctx context.Context) (string, error)
	Run(ctx context.Context, cwd, prompt string, emit func(CLIEvent)) error
}

// Tunnel exposes 127.0.0.1:port to the internet and reports each public base URL (it changes when cloudflared restarts).
type Tunnel interface {
	// Start returns a channel that yields "https://….trycloudflare.com" each time a new URL is up; closed when ctx ends.
	Start(ctx context.Context, port int) (<-chan string, error)
}

// Announcer publishes the encrypted current URL (and wake-ups) to each device's topic (PROTOCOL.md §9).
type Announcer interface {
	AnnounceURL(ctx context.Context, d Device, wsURL string) error
	Wake(ctx context.Context, d Device) error
}

// AuditLog is the hash-chained append-only log (PROTOCOL.md §7).
type AuditLog interface {
	Append(device, kind string, detail map[string]any) error
	Verify() error
}
