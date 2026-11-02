package main

import (
	"context"
	"errors"
	"fmt"
	"testing"
)

type fakeCLIAdapter struct {
	name    string
	version string
	verErr  error
	runFn   func(ctx context.Context, cwd, prompt string, emit func(CLIEvent)) error
}

func (f *fakeCLIAdapter) Name() string { return f.name }
func (f *fakeCLIAdapter) Version(ctx context.Context) (string, error) {
	if f.verErr != nil {
		return "", f.verErr
	}
	if f.version != "" {
		return f.version, nil
	}
	return "1.0.0-test", nil
}
func (f *fakeCLIAdapter) Run(ctx context.Context, cwd, prompt string, emit func(CLIEvent)) error {
	return f.runFn(ctx, cwd, prompt, emit)
}

func TestSessionManager_ReplayAfterReconnectAndDeviceIsolation(t *testing.T) {
	devA := []byte("device-A-key-32-bytes-0000000001")
	devB := []byte("device-B-key-32-bytes-0000000002")

	gate := make(chan struct{})
	adapter := &fakeCLIAdapter{
		name: "claude",
		runFn: func(ctx context.Context, cwd, prompt string, emit func(CLIEvent)) error {
			emit(CLIEvent{Output: "chunk-1"})
			<-gate
			emit(CLIEvent{Output: "chunk-2"})
			code := 0
			emit(CLIEvent{Result: "final summary", ExitCode: &code})
			return nil
		},
	}

	mgr := NewSessionManager(nil, adapter)
	sid, err := mgr.Start(devA, "PhoneA", "claude", `C:\work`, "build it")
	if err != nil {
		t.Fatalf("Start failed: %v", err)
	}

	// Phone disconnects while session produces chunk-2 and finishes.
	close(gate)
	if err := mgr.Wait(sid); err != nil {
		t.Fatalf("Wait failed: %v", err)
	}

	// Device B must not see or access Device A's session.
	if got := mgr.List(devB); len(got) != 0 {
		t.Fatalf("Device B saw sessions: %+v", got)
	}
	if _, err := mgr.Events(devB, sid, 0); !errors.Is(err, errUnknownSession) {
		t.Fatalf("Device B Events error = %v, want errUnknownSession", err)
	}
	if err := mgr.Stop(devB, sid); !errors.Is(err, errUnknownSession) {
		t.Fatalf("Device B Stop error = %v, want errUnknownSession", err)
	}

	// Device A reconnects and replays all events (afterSeq = 0).
	allEvents, err := mgr.Events(devA, sid, 0)
	if err != nil {
		t.Fatalf("Events(0) failed: %v", err)
	}
	// Expected sequence:
	// 1: session.status (running)
	// 2: session.output (chunk-1)
	// 3: session.output (chunk-2)
	// 4: session.result (final summary, exitCode 0)
	// 5: session.status (done)
	if len(allEvents) != 5 {
		t.Fatalf("got %d events, want 5: %+v", len(allEvents), allEvents)
	}
	for i, ev := range allEvents {
		wantSeq := int64(i + 1)
		if ev.Seq != wantSeq {
			t.Fatalf("event[%d].Seq = %d, want %d", i, ev.Seq, wantSeq)
		}
	}

	// Replay only events after seq 2.
	missed, err := mgr.Events(devA, sid, 2)
	if err != nil {
		t.Fatalf("Events(2) failed: %v", err)
	}
	if len(missed) != 3 || missed[0].Seq != 3 || missed[0].Data["text"] != "chunk-2" {
		t.Fatalf("unexpected missed events: %+v", missed)
	}
	if missed[1].Event != "session.result" || missed[1].Data["summary"] != "final summary" {
		t.Fatalf("unexpected result event: %+v", missed[1])
	}
	if missed[2].Event != "session.status" || missed[2].Data["state"] != string(StateDone) {
		t.Fatalf("unexpected final status event: %+v", missed[2])
	}
}

func TestSessionManager_StopCancelsContext(t *testing.T) {
	devKey := []byte("device-stop-key-32-bytes-0000001")
	started := make(chan struct{})
	cancelled := make(chan struct{})

	adapter := &fakeCLIAdapter{
		name: "claude",
		runFn: func(ctx context.Context, cwd, prompt string, emit func(CLIEvent)) error {
			close(started)
			<-ctx.Done()
			close(cancelled)
			return ctx.Err()
		},
	}

	mgr := NewSessionManager(nil, adapter)
	sid, err := mgr.Start(devKey, "Phone", "claude", `C:\work`, "long task")
	if err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	<-started

	if err := mgr.Stop(devKey, sid); err != nil {
		t.Fatalf("Stop failed: %v", err)
	}
	<-cancelled
	_ = mgr.Wait(sid)

	list := mgr.List(devKey)
	if len(list) != 1 || list[0].State != StateStopped {
		t.Fatalf("expected session state %q, got %+v", StateStopped, list)
	}
}

func TestSessionManager_EventCap(t *testing.T) {
	devKey := []byte("device-cap-key-32-bytes-00000001")
	totalOutputs := MaxSessionEvents + 25

	adapter := &fakeCLIAdapter{
		name: "claude",
		runFn: func(ctx context.Context, cwd, prompt string, emit func(CLIEvent)) error {
			for i := 0; i < totalOutputs; i++ {
				emit(CLIEvent{Output: fmt.Sprintf("line-%d", i)})
			}
			code := 0
			emit(CLIEvent{Result: "done", ExitCode: &code})
			return nil
		},
	}

	mgr := NewSessionManager(nil, adapter)
	sid, err := mgr.Start(devKey, "Phone", "claude", `C:\work`, "spam")
	if err != nil {
		t.Fatalf("Start failed: %v", err)
	}
	_ = mgr.Wait(sid)

	events, err := mgr.Events(devKey, sid, 0)
	if err != nil {
		t.Fatalf("Events failed: %v", err)
	}
	if len(events) != MaxSessionEvents {
		t.Fatalf("got %d stored events, want cap %d", len(events), MaxSessionEvents)
	}
}
