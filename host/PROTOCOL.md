# haku-host protocol v1 (P7.0 contract — owned by Claude, agents do not change it)

Phone (Haku app) ⇄ untrusted pipe (Cloudflare Quick Tunnel / ngrok / Tailscale / LAN) ⇄ haku-host on the user's PC ⇄ AI CLIs.
Security lives in our own Noise layer, never in the pipe. Plan: `F:\Haku Project\Haku-v2-Plan\02-แผนพัฒนารายเฟส.md` § P7.0.

## 1. Transport
- haku-host listens on `127.0.0.1:<port>` only (default 47321). One WebSocket endpoint: `GET /ws`. Binary messages only.
- Every WebSocket binary message is exactly one Noise message (handshake or transport). Max 64 KiB plaintext per message.
- Anything else on any path → 404, no body. No HTTP endpoint ever returns data.

## 2. Noise
- Pattern `Noise_IK_25519_ChaChaPoly_BLAKE2s`, prologue `haku-host/1`. Phone = initiator (knows host static key from the QR), host = responder.
- Handshake message 1 payload (phone → host): JSON `{"type":"hello","deviceName":"<name>"}` or, during pairing,
  `{"type":"pair","otp":"<otp from QR>","deviceName":"<name>"}`.
- After reading message 1 the host knows the phone static key:
  - paired key → reply handshake message 2 (payload `{"type":"welcome","host":"<hostname>","version":"1"}`), session open.
  - unpaired key and not a valid pairing attempt → **close the socket without writing anything** (silent drop, rule 5).
- Rekey not needed in v1 (sessions are short); reconnect = new handshake.

## 3. Pairing (`haku-host pair`)
- Host creates a one-time pairing: `otp` (16 random bytes, base64url), expires in 5 minutes, single use.
- Terminal shows a QR of JSON `{"v":1,"hostKey":"<base64 X25519 public>","url":"<wss://…/ws or empty>","topic":"<ntfy topic>","otp":"<otp>","exp":<unix s>}`.
- Phone connects, sends a `pair` handshake. Host accepts message 1 if otp matches and is unexpired, replies message 2 with
  payload `{"type":"pairing","sas":"<6 digits>"}`.
- SAS = `bigEndianUint32(BLAKE2s-256(handshakeHash)[0:4]) % 1_000_000`, zero-padded to 6 digits; both sides compute it from
  their own handshake hash and show it. The user confirms on the PC terminal (y/N); only then the phone key is stored as a device
  (with default permissions: nothing allowed) and the otp burns. Phone then reconnects with `hello`.
- `topic` = 16 random bytes base64url, per pairing; `announceKey` = BLAKE2s-256(handshakeHash || "haku-announce"), stored on both sides.

## 4. Frames (JSON inside Noise transport messages, after the handshake)
```
{"t":"req","id":"<string>","method":"<name>","params":{...}}
{"t":"res","id":"<same id>","ok":true,"result":{...}}      | {"t":"res","id":"…","ok":false,"error":{"code":"<code>","message":"<en>"}}
{"t":"event","event":"<name>","seq":<int, per session, from 1>,"sessionId":"<id>","data":{...}}
```
Error codes: `bad_request`, `not_allowed` (device permission), `unknown_session`, `unknown_approval`, `cli_missing`, `internal`.

### Methods (phone → host)
| method | params | result |
|---|---|---|
| `host.info` | — | `{"host","version","clis":[{"name":"claude"\|"codex"\|"agy","installed":bool,"version":"…"}],"permissions":<this device's DevicePermissions>}` |
| `session.start` | `{"cli","cwd","prompt"}` | `{"sessionId"}` — `not_allowed` unless cli ∈ permissions.clis and cwd is inside one of permissions.dirs |
| `session.list` | — | `{"sessions":[SessionSummary]}` (this device's sessions only) |
| `session.events` | `{"sessionId","afterSeq"}` | `{"events":[event frames with seq > afterSeq]}` — replay after the phone was offline |
| `session.stop` | `{"sessionId"}` | `{}` — kills the CLI process tree; status `stopped` |
| `approval.respond` | `{"approvalId","decision":"allow_once"\|"allow_always"\|"deny"}` | `{}` — `not_allowed` unless permissions.canApprove |

### Events (host → phone, also kept for replay)
| event | data |
|---|---|
| `session.status` | `{"state":"running"\|"waiting_approval"\|"done"\|"failed"\|"stopped"}` |
| `session.output` | `{"text":"<assistant text chunk>"}` |
| `approval.request` | `{"approvalId","tool","command":"<exact full command/argument as the CLI will run it>"}` |
| `session.result` | `{"summary":"<final assistant message>","exitCode":<int>}` |

## 5. Devices and permissions
`DevicePermissions` = `{"clis":[…],"dirs":[absolute paths],"canApprove":bool}`; a new device starts with `{"clis":[],"dirs":[],"canApprove":false}`.
Edited only on the PC: `haku-host devices`, `haku-host allow <device> --cli claude --dir <path> [--approve]`, `haku-host revoke <device>`.

## 6. Secrets at rest
Host static private key + device list (keys, permissions, topic, announceKey) = one JSON blob, encrypted with Windows DPAPI
(CurrentUser) at `%APPDATA%\haku-host\state.bin`. Non-secret settings (port) in `config.json` next to it. Other OSes: later (Keychain / libsecret).

## 7. Audit log
`%APPDATA%\haku-host\audit.jsonl`, append-only, one line per: pairing, connect, session.start, approval.request, approval.respond,
session.stop, session end. Line = `{"at":<unix ms>,"device":"<name>","kind":"…","detail":{…},"prev":"<hex sha256 of previous line>","hash":"<hex sha256 of this line without hash>"}`.
`haku-host audit verify` checks the chain.

## 8. CLIs (v1 adapters)
Run as the normal user, never with skip-permissions/yolo flags. Check flags against the installed version at startup (`<cli> --version`).
- claude: `claude -p <prompt> --output-format stream-json --verbose` in `cwd`; parse stream-json lines into `session.output` / `session.result`.
  Approvals via `--permission-prompt-tool` → week 3; until then CLIs run in their default permission mode (tools needing approval are denied).
- codex, agy: week 3.

## 9. URL announce (ntfy)
On every new tunnel URL: `POST https://ntfy.sh/<topic>` body = base64url(nonce(12) || ChaCha20-Poly1305(announceKey, nonce, url, aad="haku-announce/1")).
The same topic may carry a wake-up with empty plaintext `""` (no content). Phone decrypts; failures are ignored.
