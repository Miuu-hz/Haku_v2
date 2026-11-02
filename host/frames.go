package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"time"
)

const (
	ErrCodeBadRequest      = "bad_request"
	ErrCodeNotAllowed      = "not_allowed"
	ErrCodeUnknownSession  = "unknown_session"
	ErrCodeUnknownApproval = "unknown_approval"
	ErrCodeCLIMissing      = "cli_missing"
	ErrCodeInternal        = "internal"
)

func decodeFrame(raw []byte) (Frame, error) {
	if len(raw) == 0 || len(raw) > MaxPlaintext {
		return Frame{}, errors.New("invalid frame length")
	}
	var f Frame
	if err := json.Unmarshal(raw, &f); err != nil {
		return Frame{}, err
	}
	return f, nil
}

func encodeFrame(f Frame) ([]byte, error) {
	raw, err := json.Marshal(f)
	if err != nil {
		return nil, err
	}
	if len(raw) > MaxPlaintext {
		return nil, errors.New("encoded frame exceeds MaxPlaintext")
	}
	return raw, nil
}

func okFrame(id string, result any) Frame {
	t := true
	if result == nil {
		result = map[string]any{}
	}
	return Frame{
		T:      "res",
		ID:     id,
		OK:     &t,
		Result: result,
	}
}

func errFrame(id, code, msg string) Frame {
	f := false
	return Frame{
		T:  "res",
		ID: id,
		OK: &f,
		Error: &FrameError{
			Code:    code,
			Message: msg,
		},
	}
}

// checkPermission verifies that cli is in perms.CLIs and cwd resolves inside one of perms.Dirs.
// It returns the cleaned, resolved absolute cwd path when allowed.
func checkPermission(perms DevicePermissions, cli, cwd string) (string, error) {
	cli = strings.TrimSpace(cli)
	if cli == "" || !slices.Contains(perms.CLIs, cli) {
		return "", errors.New("cli not allowed")
	}
	return checkDirPermission(cwd, perms.Dirs)
}

func checkDirPermission(cwd string, allowedDirs []string) (string, error) {
	if strings.TrimSpace(cwd) == "" || len(allowedDirs) == 0 {
		return "", errors.New("directory not allowed")
	}
	resolvedCwd, err := resolveCanonicalPath(cwd)
	if err != nil {
		return "", err
	}
	for _, dir := range allowedDirs {
		if strings.TrimSpace(dir) == "" {
			continue
		}
		resolvedAllowed, err := resolveCanonicalPath(dir)
		if err != nil {
			continue
		}
		if isPathWithin(resolvedCwd, resolvedAllowed) {
			return resolvedCwd, nil
		}
	}
	return "", errors.New("directory not allowed")
}

// resolveCanonicalPath cleans p, converts it to an absolute path, and resolves symlinks via filepath.EvalSymlinks.
// If the leaf does not exist yet on disk, it resolves symlinks on the deepest existing ancestor and appends the cleaned remainder.
func resolveCanonicalPath(p string) (string, error) {
	if strings.TrimSpace(p) == "" {
		return "", errors.New("empty path")
	}
	abs, err := filepath.Abs(filepath.Clean(p))
	if err != nil {
		return "", err
	}
	if resolved, err := filepath.EvalSymlinks(abs); err == nil {
		return filepath.Clean(resolved), nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", err
	}

	cur := abs
	var tail []string
	for {
		parent := filepath.Dir(cur)
		base := filepath.Base(cur)
		if parent == cur {
			break
		}
		tail = append(tail, base)
		cur = parent
		if resolved, err := filepath.EvalSymlinks(cur); err == nil {
			slices.Reverse(tail)
			parts := append([]string{resolved}, tail...)
			return filepath.Clean(filepath.Join(parts...)), nil
		} else if !errors.Is(err, os.ErrNotExist) {
			return "", err
		}
	}
	return filepath.Clean(abs), nil
}

func isPathWithin(child, parent string) bool {
	c := filepath.Clean(child)
	p := filepath.Clean(parent)
	if runtime.GOOS == "windows" || hasWindowsVolume(c) || hasWindowsVolume(p) {
		c = strings.ToLower(c)
		p = strings.ToLower(p)
	}
	rel, err := filepath.Rel(p, c)
	if err != nil {
		return false
	}
	if rel == "." {
		return true
	}
	if rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) || filepath.IsAbs(rel) {
		return false
	}
	return true
}

func hasWindowsVolume(p string) bool {
	return len(p) >= 2 && p[1] == ':'
}

type cliInfo struct {
	Name      string `json:"name"`
	Installed bool   `json:"installed"`
	Version   string `json:"version"`
}

func dispatchRequest(ctx context.Context, dev Device, sessions *SessionManager, audit AuditLog, req Frame) Frame {
	if req.T != "req" || strings.TrimSpace(req.ID) == "" || strings.TrimSpace(req.Method) == "" {
		return errFrame(req.ID, ErrCodeBadRequest, "invalid request frame")
	}

	switch req.Method {
	case "host.info":
		host, err := os.Hostname()
		if err != nil || host == "" {
			host = "localhost"
		}
		perms := dev.Permissions
		if perms.CLIs == nil {
			perms.CLIs = []string{}
		}
		if perms.Dirs == nil {
			perms.Dirs = []string{}
		}
		return okFrame(req.ID, map[string]any{
			"host":        host,
			"version":     ProtocolVersion,
			"clis":        collectCLIInfo(ctx, sessions),
			"permissions": perms,
		})

	case "session.start":
		cli, _ := paramString(req.Params, "cli")
		cwd, _ := paramString(req.Params, "cwd")
		prompt, _ := paramString(req.Params, "prompt")
		if strings.TrimSpace(cli) == "" || strings.TrimSpace(cwd) == "" || strings.TrimSpace(prompt) == "" {
			return errFrame(req.ID, ErrCodeBadRequest, "cli, cwd, and prompt are required")
		}
		cleanCwd, err := checkPermission(dev.Permissions, cli, cwd)
		if err != nil {
			return errFrame(req.ID, ErrCodeNotAllowed, "cli or cwd not allowed for this device")
		}
		if sessions == nil {
			return errFrame(req.ID, ErrCodeInternal, "session manager unavailable")
		}
		sessionID, err := sessions.Start(dev.PublicKey, dev.Name, cli, cleanCwd, prompt)
		if err != nil {
			if errors.Is(err, errCLIMissing) {
				return errFrame(req.ID, ErrCodeCLIMissing, "requested CLI is not installed")
			}
			return errFrame(req.ID, ErrCodeInternal, "failed to start session")
		}
		if audit != nil {
			_ = audit.Append(dev.Name, "session.start", map[string]any{
				"sessionId": sessionID,
				"cli":       cli,
				"cwd":       cleanCwd,
			})
		}
		return okFrame(req.ID, map[string]any{"sessionId": sessionID})

	case "session.list":
		var list []SessionSummary
		if sessions != nil {
			list = sessions.List(dev.PublicKey)
		} else {
			list = []SessionSummary{}
		}
		return okFrame(req.ID, map[string]any{"sessions": list})

	case "session.events":
		sessionID, ok := paramString(req.Params, "sessionId")
		if !ok || strings.TrimSpace(sessionID) == "" {
			return errFrame(req.ID, ErrCodeBadRequest, "sessionId is required")
		}
		afterSeq, ok := paramInt64(req.Params, "afterSeq")
		if !ok || afterSeq < 0 {
			return errFrame(req.ID, ErrCodeBadRequest, "valid afterSeq is required")
		}
		if sessions == nil {
			return errFrame(req.ID, ErrCodeUnknownSession, "unknown session")
		}
		events, err := sessions.Events(dev.PublicKey, sessionID, afterSeq)
		if err != nil {
			if errors.Is(err, errUnknownSession) {
				return errFrame(req.ID, ErrCodeUnknownSession, "unknown session")
			}
			return errFrame(req.ID, ErrCodeInternal, "failed to read events")
		}
		return okFrame(req.ID, map[string]any{"events": events})

	case "session.stop":
		sessionID, ok := paramString(req.Params, "sessionId")
		if !ok || strings.TrimSpace(sessionID) == "" {
			return errFrame(req.ID, ErrCodeBadRequest, "sessionId is required")
		}
		if sessions == nil {
			return errFrame(req.ID, ErrCodeUnknownSession, "unknown session")
		}
		if err := sessions.Stop(dev.PublicKey, sessionID); err != nil {
			if errors.Is(err, errUnknownSession) {
				return errFrame(req.ID, ErrCodeUnknownSession, "unknown session")
			}
			return errFrame(req.ID, ErrCodeInternal, "failed to stop session")
		}
		if audit != nil {
			_ = audit.Append(dev.Name, "session.stop", map[string]any{"sessionId": sessionID})
		}
		return okFrame(req.ID, map[string]any{})

	case "approval.respond":
		if !dev.Permissions.CanApprove {
			return errFrame(req.ID, ErrCodeNotAllowed, "device is not permitted to approve")
		}
		approvalID, _ := paramString(req.Params, "approvalId")
		decision, _ := paramString(req.Params, "decision")
		if strings.TrimSpace(approvalID) == "" {
			return errFrame(req.ID, ErrCodeBadRequest, "approvalId is required")
		}
		switch decision {
		case "allow_once", "allow_always", "deny":
		default:
			return errFrame(req.ID, ErrCodeBadRequest, "invalid approval decision")
		}
		if sessions == nil {
			return errFrame(req.ID, ErrCodeUnknownApproval, "unknown approval")
		}
		if err := sessions.RespondApproval(dev.PublicKey, approvalID, decision); err != nil {
			if errors.Is(err, errUnknownApproval) {
				return errFrame(req.ID, ErrCodeUnknownApproval, "unknown approval")
			}
			return errFrame(req.ID, ErrCodeInternal, "failed to respond to approval")
		}
		if audit != nil {
			_ = audit.Append(dev.Name, "approval.respond", map[string]any{
				"approvalId": approvalID,
				"decision":   decision,
			})
		}
		return okFrame(req.ID, map[string]any{})

	default:
		return errFrame(req.ID, ErrCodeBadRequest, fmt.Sprintf("unknown method: %s", req.Method))
	}
}

func collectCLIInfo(ctx context.Context, sessions *SessionManager) []cliInfo {
	known := []string{"claude", "codex", "agy"}
	byName := make(map[string]CLIAdapter)
	if sessions != nil {
		for _, a := range sessions.Adapters() {
			byName[a.Name()] = a
			if !slices.Contains(known, a.Name()) {
				known = append(known, a.Name())
			}
		}
	}
	out := make([]cliInfo, 0, len(known))
	for _, name := range known {
		info := cliInfo{Name: name}
		if a, ok := byName[name]; ok {
			vctx, cancel := context.WithTimeout(ctx, 2*time.Second)
			ver, err := a.Version(vctx)
			cancel()
			if err == nil && ver != "" {
				info.Installed = true
				info.Version = ver
			}
		}
		out = append(out, info)
	}
	return out
}

func paramString(params map[string]any, key string) (string, bool) {
	if params == nil {
		return "", false
	}
	v, ok := params[key]
	if !ok {
		return "", false
	}
	s, ok := v.(string)
	return s, ok
}

func paramInt64(params map[string]any, key string) (int64, bool) {
	if params == nil {
		return 0, false
	}
	v, ok := params[key]
	if !ok {
		return 0, false
	}
	switch n := v.(type) {
	case float64:
		if float64(int64(n)) != n {
			return 0, false
		}
		return int64(n), true
	case int64:
		return n, true
	case int:
		return int64(n), true
	case json.Number:
		i, err := n.Int64()
		return i, err == nil
	default:
		return 0, false
	}
}
