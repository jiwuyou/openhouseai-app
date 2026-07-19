# openhouse-pi-runtime

`openhouse-pi-runtime` is the loopback-only process and transport layer between
WuxianPi clients and `pi_agent_rust`. It deliberately does not implement an
agent loop, tool iteration limit, retry policy, prompt mutation, or conversation
format conversion.

Each managed conversation has its own `pi --mode rpc` child process. WebSocket
text frames are forwarded one-for-one to Pi stdin, and each Pi stdout JSONL line
is forwarded unchanged as one text frame.

## Run

```bash
cargo build --release -p openhouse-pi-runtime
OPENHOUSE_PI_TOKEN='replace-with-a-random-32-byte-token' \
  target/release/openhouse-pi-runtime \
  --listen 127.0.0.1:8765 \
  --pi-bin "$HOME/.local/bin/pi" \
  --extension "$HOME/.local/share/openhouseai/extensions/openhouse-tools" \
  --sessions-dir "$HOME/.pi/agent/sessions"
```

When neither `OPENHOUSE_PI_TOKEN` nor `--token-file` supplies a token, a random
token is created at
`$HOME/.local/share/openhouseai/runtime/access-token` with mode `0600`.
Non-loopback listen addresses are rejected.
Runtime bearer variables such as `OPENHOUSE_PI_TOKEN` are explicitly removed
from every Pi child environment, even when the gateway itself received its
credential through the environment.

The Pi binary, session root, state root, default working directory, listen
address, token source, disconnect grace, idle timeout, shutdown timeout, and
backup size limit are configurable through CLI options or `OPENHOUSE_PI_*`
environment variables. Run `openhouse-pi-runtime --help` for the full list.

## Client sequence

All HTTP requests and the WebSocket upgrade require:

```text
Authorization: Bearer <runtime-token>
```

Create a logical conversation. `sessionPath` is optional and, when present,
must identify an existing `.jsonl` beneath the configured Pi session root.
`cwd` is optional and defaults to the configured Pi working directory.
The returned ID is a stable gateway ID, distinct from Pi's internal session ID.
On the first Pi output (including `agent_start`) the gateway privately queries
`get_state`, stores the real `sessionFile` with a short persistence debounce,
and always resumes that file after idle or full runtime restart. This happens
while a long first turn is still active; it does not wait for `agent_end`.
Attaching an already managed `sessionPath` adopts its existing gateway ID
instead of creating a duplicate mapping.

```http
POST /admin/v1/sessions
{"cwd":"/data/data/com.termux/files/home/project"}
```

The response is `201 {"sessionId":"..."}`. Acquire its single controller
lease:

```http
POST /admin/v1/leases
{"sessionId":"...","clientId":"native-ui","takeover":false}
```

The response contains `leaseId` and `wsPath`. A different controller receives
HTTP `409` with error code `lease_conflict`. Explicit takeover uses the same
request with `takeover:true`; the previous WebSocket is closed. Re-requesting a
lease with the same `clientId` during the disconnect grace returns the same
lease, allowing short network interruptions to reconnect.

Connect to `GET /ws/rpc/{leaseId}`. Every text frame must contain exactly one
JSON object. The gateway never adds gateway-specific text frames. Process exit,
lease revocation, invalid framing, and output overflow are represented as
WebSocket close frames and are observable in the session/health management API.

## Management API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/admin/v1/health` | Runtime health and degraded process count |
| `GET/PATCH` | `/admin/v1/config` | Read redacted config/update safe timeouts and size limits |
| `GET/POST` | `/admin/v1/sessions` | List disk/managed sessions or create/attach one |
| `DELETE` | `/admin/v1/sessions/{sessionId}` | Stop and forget runtime mapping; never deletes conversation data |
| `GET/POST` | `/admin/v1/leases` | List or acquire/take over controller leases |
| `DELETE` | `/admin/v1/leases/{leaseId}` | Explicitly release a lease |
| `GET` | `/admin/v1/backup` | Download `openhouse-pi-conversations-v1.zip` |
| `POST` | `/admin/v1/restore` | Restore that ZIP, preserving conflicting files under new names |
| `GET/POST/DELETE` | `/admin/v1/android-bridge` | Inspect/register/unregister the loopback Android tool bridge |

Android Bridge registration accepts:

```json
{"clientId":"wuxianpi-native","port":39123,"token":"32-or-more-random-characters"}
```

The token is held only in gateway memory and is never returned, persisted, or
injected into Pi. Pi receives an uncredentialed, unguessable loopback proxy URL;
the gateway authenticates upstream requests. Re-registering the Bridge takes
effect for existing Pi processes immediately because the proxy resolves the
current registration on every call.

Backup contains only `.jsonl` conversation files plus a format manifest. It
does not include runtime tokens, Android Bridge tokens, provider credentials,
Pi authentication files, configuration, indexes, or lock files. Restore rejects
absolute paths, traversal, non-JSONL entries, duplicate names, and oversized
archives. It validates manifest format/version/count, refuses symlink parents,
and creates restored files with mode `0600`.

An unattached process is idle-reaped only while Pi is outside an
`agent_start`/`agent_end` interval. A long-running tool therefore survives UI
disconnect and idle timeout without host-side continuation or retries.

## Verification

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --all-features
```

The integration test launches the included `fake-pi` executable and verifies
Bearer authentication, independent concurrent sessions, lease conflict and
takeover, byte-preserving JSON frame forwarding, and observable abnormal exit.
Additional invariant tests cover Bridge credential isolation/dynamic
registration, session-file persistence across restart, active-agent idle
protection, failed-upgrade lease rollback, safe restore, and extension shell
syntax.
