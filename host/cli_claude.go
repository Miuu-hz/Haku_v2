package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
)

// ClaudeAdapter implements CLIAdapter for the `claude` CLI (PROTOCOL.md §8).
type ClaudeAdapter struct {
	bin string
}

func NewClaudeAdapter() *ClaudeAdapter {
	return &ClaudeAdapter{bin: "claude"}
}

func (a *ClaudeAdapter) Name() string {
	return "claude"
}

func (a *ClaudeAdapter) Version(ctx context.Context) (string, error) {
	bin := a.bin
	if bin == "" {
		bin = "claude"
	}
	out, err := exec.CommandContext(ctx, bin, "--version").Output()
	if err != nil {
		return "", fmt.Errorf("claude --version: %w", err)
	}
	ver := strings.TrimSpace(string(out))
	if ver == "" {
		return "", errors.New("claude --version returned empty output")
	}
	return ver, nil
}

func (a *ClaudeAdapter) Run(ctx context.Context, cwd, prompt string, emit func(CLIEvent)) error {
	bin := a.bin
	if bin == "" {
		bin = "claude"
	}
	// Never pass through a shell, never use skip-permissions flags (PROTOCOL.md §8).
	cmd := exec.CommandContext(ctx, bin, "-p", prompt, "--output-format", "stream-json", "--verbose")
	cmd.Dir = cwd
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

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("claude stdout pipe: %w", err)
	}
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start claude: %w", err)
	}

	var finalResult string
	parseClaudeStream(stdout, func(ev CLIEvent) {
		if ev.Output != "" {
			emit(CLIEvent{Output: ev.Output})
		}
		if ev.Result != "" {
			finalResult = ev.Result
		}
	})

	waitErr := cmd.Wait()
	if ctx.Err() != nil {
		return ctx.Err()
	}

	exitCode := 0
	if waitErr != nil {
		var exitErr *exec.ExitError
		if errors.As(waitErr, &exitErr) {
			exitCode = exitErr.ExitCode()
		} else {
			exitCode = 1
		}
	}
	emit(CLIEvent{
		Result:   finalResult,
		ExitCode: &exitCode,
	})
	return waitErr
}

type claudeStreamEnvelope struct {
	Type    string          `json:"type"`
	Message json.RawMessage `json:"message"`
	Content json.RawMessage `json:"content"`
	Text    string          `json:"text"`
	Result  string          `json:"result"`
	IsError bool            `json:"is_error"`
	Code    *int            `json:"exit_code"`
}

type claudeAssistantMessage struct {
	Content json.RawMessage `json:"content"`
	Text    string          `json:"text"`
}

type claudeContentBlock struct {
	Type string `json:"type"`
	Text string `json:"text"`
}

// parseClaudeStream reads stream-json lines from r and emits parsed CLIEvents. Unknown lines are ignored.
func parseClaudeStream(r io.Reader, emit func(CLIEvent)) {
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 64*1024), 4*1024*1024)
	for scanner.Scan() {
		if ev, ok := parseClaudeLine(scanner.Bytes()); ok {
			emit(ev)
		}
	}
}

// parseClaudeLine parses a single stream-json line from `claude --output-format stream-json --verbose`.
func parseClaudeLine(line []byte) (CLIEvent, bool) {
	trimmed := bytes.TrimSpace(line)
	if len(trimmed) == 0 || trimmed[0] != '{' {
		return CLIEvent{}, false
	}
	var env claudeStreamEnvelope
	if err := json.Unmarshal(trimmed, &env); err != nil {
		return CLIEvent{}, false
	}

	switch env.Type {
	case "assistant":
		text := extractAssistantText(env)
		if text == "" {
			return CLIEvent{}, false
		}
		return CLIEvent{Output: text}, true

	case "result":
		code := 0
		if env.Code != nil {
			code = *env.Code
		} else if env.IsError {
			code = 1
		}
		return CLIEvent{
			Result:   env.Result,
			ExitCode: &code,
		}, true

	default:
		return CLIEvent{}, false
	}
}

func extractAssistantText(env claudeStreamEnvelope) string {
	if len(env.Message) > 0 {
		var msg claudeAssistantMessage
		if err := json.Unmarshal(env.Message, &msg); err == nil {
			if s := extractContentBlocks(msg.Content); s != "" {
				return s
			}
			if msg.Text != "" {
				return msg.Text
			}
		}
	}
	if s := extractContentBlocks(env.Content); s != "" {
		return s
	}
	return env.Text
}

func extractContentBlocks(raw json.RawMessage) string {
	raw = bytes.TrimSpace(raw)
	if len(raw) == 0 {
		return ""
	}
	if raw[0] == '"' {
		var s string
		if err := json.Unmarshal(raw, &s); err == nil {
			return s
		}
		return ""
	}
	if raw[0] == '[' {
		var blocks []claudeContentBlock
		if err := json.Unmarshal(raw, &blocks); err == nil {
			var b strings.Builder
			for _, blk := range blocks {
				if (blk.Type == "" || blk.Type == "text") && blk.Text != "" {
					b.WriteString(blk.Text)
				}
			}
			return b.String()
		}
	}
	return ""
}
