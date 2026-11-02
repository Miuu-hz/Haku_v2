package main

import (
	"strings"
	"testing"
)

func TestParseClaudeLine(t *testing.T) {
	intPtr := func(v int) *int { return &v }

	cases := []struct {
		name     string
		line     string
		wantOK   bool
		wantOut  string
		wantRes  string
		wantExit *int
	}{
		{
			name:    "assistant content blocks",
			line:    `{"type":"assistant","message":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"text","text":"สวัสดีครับ "},{"type":"text","text":"พร้อมช่วยงาน"}]}}`,
			wantOK:  true,
			wantOut: "สวัสดีครับ พร้อมช่วยงาน",
		},
		{
			name:    "assistant string content",
			line:    `{"type":"assistant","message":{"content":"direct string content"}}`,
			wantOK:  true,
			wantOut: "direct string content",
		},
		{
			name:   "assistant tool_use only ignored",
			line:   `{"type":"assistant","message":{"content":[{"type":"tool_use","id":"tu_1","name":"Bash"}]}}`,
			wantOK: false,
		},
		{
			name:     "result success",
			line:     `{"type":"result","subtype":"success","is_error":false,"result":"All tests pass.","cost_usd":0.01}`,
			wantOK:   true,
			wantRes:  "All tests pass.",
			wantExit: intPtr(0),
		},
		{
			name:     "result error flag",
			line:     `{"type":"result","subtype":"error","is_error":true,"result":"Command failed"}`,
			wantOK:   true,
			wantRes:  "Command failed",
			wantExit: intPtr(1),
		},
		{
			name:   "system init line ignored",
			line:   `{"type":"system","subtype":"init","session_id":"abc"}`,
			wantOK: false,
		},
		{
			name:   "malformed json ignored",
			line:   `not json at all`,
			wantOK: false,
		},
		{
			name:   "empty line ignored",
			line:   `   `,
			wantOK: false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			ev, ok := parseClaudeLine([]byte(tc.line))
			if ok != tc.wantOK {
				t.Fatalf("parseClaudeLine ok = %v, want %v (ev=%+v)", ok, tc.wantOK, ev)
			}
			if !ok {
				return
			}
			if ev.Output != tc.wantOut {
				t.Fatalf("Output = %q, want %q", ev.Output, tc.wantOut)
			}
			if ev.Result != tc.wantRes {
				t.Fatalf("Result = %q, want %q", ev.Result, tc.wantRes)
			}
			if (ev.ExitCode == nil) != (tc.wantExit == nil) {
				t.Fatalf("ExitCode = %v, want %v", ev.ExitCode, tc.wantExit)
			}
			if tc.wantExit != nil && *ev.ExitCode != *tc.wantExit {
				t.Fatalf("ExitCode = %d, want %d", *ev.ExitCode, *tc.wantExit)
			}
		})
	}
}

func TestParseClaudeStream(t *testing.T) {
	stream := strings.Join([]string{
		`{"type":"system","subtype":"init"}`,
		`{"type":"assistant","message":{"content":[{"type":"text","text":"Step 1 done."}]}}`,
		`{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}`,
		`{"type":"assistant","message":{"content":[{"type":"text","text":"Step 2 done."}]}}`,
		`{"type":"result","subtype":"success","is_error":false,"result":"Summary of work."}`,
	}, "\n")

	var events []CLIEvent
	parseClaudeStream(strings.NewReader(stream), func(ev CLIEvent) {
		events = append(events, ev)
	})

	if len(events) != 3 {
		t.Fatalf("got %d events, want 3: %+v", len(events), events)
	}
	if events[0].Output != "Step 1 done." || events[1].Output != "Step 2 done." {
		t.Fatalf("unexpected output events: %+v", events[:2])
	}
	if events[2].Result != "Summary of work." || events[2].ExitCode == nil || *events[2].ExitCode != 0 {
		t.Fatalf("unexpected result event: %+v", events[2])
	}
}
