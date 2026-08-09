# WuxianPi dual-edition runtime

WuxianPi is built from one repository as two APKs with one OpenHouse desktop,
Apps surface, Compose AI UI, Web UI, transport contract, Pi SDK runtime and Pi
JSONL session format.

| Edition | Application id | Runtime location |
| --- | --- | --- |
| Native | `com.wuxianpi` | An existing official Termux installation |
| All-in-One | `com.termux` | The Termux environment bundled by this APK |

The active Agent implementation is `@earendil-works/pi-coding-agent@0.80.10`
running inside `wuxianpi-node`. The repository does not start `pi --mode rpc`,
does not ship a Rust gateway, and does not implement an Android Agent loop.

## Runtime boundary

```text
Compose UI / pi-web
        |
        | wuxianpi-sdk-v1 WebSocket
        v
127.0.0.1:8765/v1/ws
        |
        v
wuxianpi-node
        |
        | direct TypeScript calls
        v
Pi AgentSessionRuntime / tools / providers / extensions / JSONL
```

`wuxianpi-node` owns transport and SDK lifecycle only. Pi owns model calls,
tool execution, retries, compaction, queued continuation and session writes.
The host never adds continuation prompts or tool-iteration limits.

HTTP health is available at `/health` and `/admin/v1/health`. The WebSocket
request envelope is:

```json
{"id":"request-id","type":"session.prompt","sessionId":"pi-session-id","payload":{"message":"hello"}}
```

Responses are `{id,ok,result}` or `{id,ok:false,error}`. SDK events are emitted
as `{type:"agent.event",sessionId,sessionPath,sequence,payload}`. Only the Pi
`agent_settled` event means that retries, compaction retries and queued
continuations are all finished. `agent_end` must not clear the UI running state
or trigger Runtime reclamation.

## Sessions

The registry lazily creates one `AgentSessionRuntime` per active Pi session.
Canonical session paths are deduplicated so two clients cannot create two SDK
runtimes that write the same JSONL file. Commands for one session are ordered;
different sessions may run concurrently.

Listing and reading history uses `SessionManager` and the original JSONL files
without creating an active Runtime. Opening or sending a message loads the
Runtime. New, switch, fork and import operations replace the SDK session and
must atomically update registry keys, event subscriptions and extension
bindings.

The canonical data remains under `$HOME/.pi/agent`. Installation, repair and
upgrade scripts must never delete or replace `$HOME/.pi`. Copying the Pi JSONL
files allows conversation migration between Termux, the All-in-One edition and
desktop Pi installations.

## Extensions and tools

`DefaultResourceLoader` discovers Pi extensions, skills, prompts, themes and
packages from the normal Pi directories. `bindExtensions()` is called for every
new or replacement session. Interactive extension UI requests are forwarded
through the same WebSocket and answered by the client.

Phase one intentionally has no Android tool bridge. Built-in Pi tools and
extension tools execute in Termux and return their normal structured results.
A missing executable or failed tool remains a tool error visible to Pi; it does
not close the WebSocket or terminate the Android activity.

## Installation

The runtime payload contains the Node service, its exact npm lockfile,
production dependencies, install/check/register/start scripts and metadata.
Installation stages into a temporary directory and then replaces runtime code
without touching user Pi state. Re-running the installer repairs an interrupted
or partially extracted runtime.

Native and All-in-One embed the same five-resource `openhouse-core-stack` set.
Native stores the canonical archives under
`native-app/src/main/assets/openhouse-resources-v2/`; All-in-One stores the same
bytes under `app/src/main/assets/openhouse/product-payloads/`. The resource
updater compares receipts and SHA-256 values before reusing APK assets or
downloading a changed resource, then registers services through service-manager.

## Verification

```bash
cd runtime/wuxianpi-node
npm ci --ignore-scripts
npm run typecheck
npm test

cd ../../web/pi-web
node_modules/.bin/tsc --noEmit
npm run lint

cd ../..
./gradlew :pi-client:test :ai-feature:test
bash scripts/build-pi-node-payload.sh
bash scripts/validate-openhouse-payloads.sh
bash scripts/build-native.sh
bash scripts/build-all-in-one.sh
```

Device acceptance covers preserved `$HOME/.pi`, repair of a partial install,
history-only browsing, provider and tool failures, reconnect, long tool calls,
two concurrent sessions, fork/switch and extension loading.
