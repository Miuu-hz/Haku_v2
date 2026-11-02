package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"sync"
	"time"
)

// MaxSessionEvents is the maximum number of event frames retained per session for replay.
const MaxSessionEvents = 1000

var (
	errUnknownSession  = errors.New("unknown session")
	errUnknownApproval = errors.New("unknown approval")
	errCLIMissing      = errors.New("cli missing")
)

type session struct {
	id            string
	deviceKey     []byte
	deviceName    string
	cli           string
	cwd           string
	state         SessionState
	startedAtMs   int64
	lastSeq       int64
	events        []Frame
	pendingResult string
	resultEmitted bool
	cancel        context.CancelFunc
	done          chan struct{}
}

type subscriber struct {
	id        int64
	deviceKey []byte
	fn        func(Frame)
}

// SessionManager manages running and completed CLI sessions across phone reconnects.
type SessionManager struct {
	mu          sync.Mutex
	adapters    map[string]CLIAdapter
	sessions    map[string]*session
	order       []string
	subscribers map[int64]subscriber
	nextSubID   int64
	audit       AuditLog
}

func NewSessionManager(audit AuditLog, adapters ...CLIAdapter) *SessionManager {
	m := &SessionManager{
		adapters:    make(map[string]CLIAdapter, len(adapters)),
		sessions:    make(map[string]*session),
		subscribers: make(map[int64]subscriber),
		audit:       audit,
	}
	for _, a := range adapters {
		if a != nil {
			m.adapters[a.Name()] = a
		}
	}
	return m
}

func (m *SessionManager) RegisterAdapter(a CLIAdapter) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.adapters[a.Name()] = a
}

func (m *SessionManager) Adapters() []CLIAdapter {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]CLIAdapter, 0, len(m.adapters))
	for _, a := range m.adapters {
		out = append(out, a)
	}
	return out
}

// Subscribe registers fn to receive live event frames for sessions owned by deviceKey.
func (m *SessionManager) Subscribe(deviceKey []byte, fn func(Frame)) func() {
	m.mu.Lock()
	m.nextSubID++
	id := m.nextSubID
	m.subscribers[id] = subscriber{
		id:        id,
		deviceKey: bytes.Clone(deviceKey),
		fn:        fn,
	}
	m.mu.Unlock()

	return func() {
		m.mu.Lock()
		delete(m.subscribers, id)
		m.mu.Unlock()
	}
}

// Start launches a new CLI session that continues running even if the phone disconnects.
func (m *SessionManager) Start(deviceKey []byte, deviceName, cliName, cwd, prompt string) (string, error) {
	m.mu.Lock()
	adapter, ok := m.adapters[cliName]
	m.mu.Unlock()
	if !ok {
		return "", errCLIMissing
	}

	verCtx, verCancel := context.WithTimeout(context.Background(), 5*time.Second)
	_, err := adapter.Version(verCtx)
	verCancel()
	if err != nil {
		return "", fmt.Errorf("%w: %v", errCLIMissing, err)
	}

	sessionID, err := newRandomToken(16)
	if err != nil {
		return "", err
	}

	runCtx, cancel := context.WithCancel(context.Background())
	sess := &session{
		id:          sessionID,
		deviceKey:   bytes.Clone(deviceKey),
		deviceName:  deviceName,
		cli:         cliName,
		cwd:         cwd,
		state:       StateRunning,
		startedAtMs: time.Now().UnixMilli(),
		events:      []Frame{},
		cancel:      cancel,
		done:        make(chan struct{}),
	}

	m.mu.Lock()
	m.sessions[sessionID] = sess
	m.order = append(m.order, sessionID)
	ev := m.appendEventLocked(sess, "session.status", map[string]any{"state": string(StateRunning)})
	subs := m.matchingSubscribersLocked(sess.deviceKey)
	m.mu.Unlock()

	notifySubscribers(subs, ev)

	go m.runSession(runCtx, adapter, sess, cwd, prompt)
	return sessionID, nil
}

func (m *SessionManager) runSession(ctx context.Context, adapter CLIAdapter, sess *session, cwd, prompt string) {
	defer close(sess.done)

	var lastExitCode *int
	runErr := adapter.Run(ctx, cwd, prompt, func(cev CLIEvent) {
		var toNotify []Frame
		var subs []func(Frame)

		m.mu.Lock()
		if cev.Output != "" {
			f := m.appendEventLocked(sess, "session.output", map[string]any{"text": cev.Output})
			toNotify = append(toNotify, f)
		}
		if cev.Result != "" && cev.ExitCode == nil {
			sess.pendingResult = cev.Result
		}
		if cev.ExitCode != nil {
			code := *cev.ExitCode
			lastExitCode = &code
			summary := cev.Result
			if summary == "" {
				summary = sess.pendingResult
			}
			if !sess.resultEmitted {
				sess.resultEmitted = true
				f := m.appendEventLocked(sess, "session.result", map[string]any{
					"summary":  summary,
					"exitCode": code,
				})
				toNotify = append(toNotify, f)
			}
		}
		if len(toNotify) > 0 {
			subs = m.matchingSubscribersLocked(sess.deviceKey)
		}
		m.mu.Unlock()

		for _, f := range toNotify {
			notifySubscribers(subs, f)
		}
	})

	m.mu.Lock()
	var statusFrame *Frame
	var subs []func(Frame)
	finalState := sess.state
	if sess.state != StateStopped {
		if runErr == nil && (lastExitCode == nil || *lastExitCode == 0) {
			sess.state = StateDone
		} else {
			sess.state = StateFailed
		}
		finalState = sess.state
		f := m.appendEventLocked(sess, "session.status", map[string]any{"state": string(sess.state)})
		statusFrame = &f
		subs = m.matchingSubscribersLocked(sess.deviceKey)
	}
	m.mu.Unlock()

	if statusFrame != nil {
		notifySubscribers(subs, *statusFrame)
	}
	if m.audit != nil {
		_ = m.audit.Append(sess.deviceName, "session end", map[string]any{
			"sessionId": sess.id,
			"state":     string(finalState),
		})
	}
}

// List returns summaries of sessions belonging to deviceKey only.
func (m *SessionManager) List(deviceKey []byte) []SessionSummary {
	m.mu.Lock()
	defer m.mu.Unlock()

	out := []SessionSummary{}
	for _, id := range m.order {
		s := m.sessions[id]
		if s == nil || !bytes.Equal(s.deviceKey, deviceKey) {
			continue
		}
		out = append(out, SessionSummary{
			SessionID:   s.id,
			CLI:         s.cli,
			Cwd:         s.cwd,
			State:       s.state,
			StartedAtMs: s.startedAtMs,
			LastSeq:     s.lastSeq,
		})
	}
	return out
}

// Events returns stored event frames with seq > afterSeq for a session owned by deviceKey.
func (m *SessionManager) Events(deviceKey []byte, sessionID string, afterSeq int64) ([]Frame, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	s, ok := m.sessions[sessionID]
	if !ok || !bytes.Equal(s.deviceKey, deviceKey) {
		return nil, errUnknownSession
	}
	out := []Frame{}
	for _, ev := range s.events {
		if ev.Seq > afterSeq {
			out = append(out, ev)
		}
	}
	return out, nil
}

// Stop cancels a running session owned by deviceKey and transitions its state to stopped.
func (m *SessionManager) Stop(deviceKey []byte, sessionID string) error {
	m.mu.Lock()
	s, ok := m.sessions[sessionID]
	if !ok || !bytes.Equal(s.deviceKey, deviceKey) {
		m.mu.Unlock()
		return errUnknownSession
	}

	var statusFrame *Frame
	var subs []func(Frame)
	if s.state == StateRunning || s.state == StateWaitingApproval {
		s.state = StateStopped
		f := m.appendEventLocked(s, "session.status", map[string]any{"state": string(StateStopped)})
		statusFrame = &f
		subs = m.matchingSubscribersLocked(s.deviceKey)
	}
	cancel := s.cancel
	m.mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if statusFrame != nil {
		notifySubscribers(subs, *statusFrame)
	}
	return nil
}

// RespondApproval handles approval.respond for a session owned by deviceKey.
func (m *SessionManager) RespondApproval(deviceKey []byte, approvalID, decision string) error {
	return errUnknownApproval
}

// Wait blocks until the specified session's process goroutine exits (used by tests).
func (m *SessionManager) Wait(sessionID string) error {
	m.mu.Lock()
	s, ok := m.sessions[sessionID]
	m.mu.Unlock()
	if !ok {
		return errUnknownSession
	}
	<-s.done
	return nil
}

func (m *SessionManager) appendEventLocked(s *session, eventName string, data map[string]any) Frame {
	s.lastSeq++
	f := Frame{
		T:         "event",
		Event:     eventName,
		Seq:       s.lastSeq,
		SessionID: s.id,
		Data:      data,
	}
	s.events = append(s.events, f)
	if len(s.events) > MaxSessionEvents {
		s.events = append([]Frame(nil), s.events[len(s.events)-MaxSessionEvents:]...)
	}
	return f
}

func (m *SessionManager) matchingSubscribersLocked(deviceKey []byte) []func(Frame) {
	var out []func(Frame)
	for _, sub := range m.subscribers {
		if bytes.Equal(sub.deviceKey, deviceKey) {
			out = append(out, sub.fn)
		}
	}
	return out
}

func notifySubscribers(subs []func(Frame), f Frame) {
	for _, fn := range subs {
		fn(f)
	}
}

func newRandomToken(nBytes int) (string, error) {
	b := make([]byte, nBytes)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("random bytes: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
