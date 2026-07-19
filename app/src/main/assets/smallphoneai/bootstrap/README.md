# SmallPhoneAI Bootstrap

This repo is the online bootstrap source for SmallPhoneAI on Termux/Ubuntu.
It prepares the phone runtime, establishes the OpenHouse control plane,
coordinates SmallPhone runtime components, and exposes machine-readable hooks
for the app shell. Agent CLIs such as Codex, Claude Code, CloudCLI, and Hermes
are post-install capabilities that pi-agent can guide later.

The intended runtime split is:

- Termux native: installer, `service-manager`, Pi Agent/pi-web, Ubuntu/proot
  launcher, bridge, and recovery fallback.
- Ubuntu/proot: AI workbench tools and explicit compatibility components such
  as SmallPhone and optional `cc-connect` / `openhouse-connect`.

## Commands

```bash
bash bootstrap.sh install
bash bootstrap.sh full
bash bootstrap.sh check
bash bootstrap.sh status
bash bootstrap.sh hooks
bash bootstrap.sh components
bash bootstrap.sh start
bash bootstrap.sh repair
```

Supporting stages are still available:

```bash
bash bootstrap.sh env-check
bash bootstrap.sh prepare
bash bootstrap.sh termux-packages
bash bootstrap.sh termux-node
bash bootstrap.sh ubuntu
bash bootstrap.sh sync-docs
bash bootstrap.sh ubuntu-packages
bash bootstrap.sh entry-ubuntu
bash bootstrap.sh node
bash bootstrap.sh codex
bash bootstrap.sh claude-code
bash bootstrap.sh claude-code-ui
bash bootstrap.sh registry-sync
bash bootstrap.sh sync-core-stack
```

`install`/`full` is idempotent and runs:

```text
env-check -> prepare -> termux-packages -> termux-node ->
components(service-manager,pi-agent,pi-web) -> start(pi-agent,pi-web) ->
ubuntu -> ubuntu-packages -> node -> sync-docs ->
components(github-config-helper,cc-connect,smallphone) -> registry-sync ->
start -> status
```

New installs remain in Termux native when a terminal is opened. `entry-ubuntu`
is retained only as an explicit opt-in command for users who want interactive
Termux shells to enter Ubuntu automatically.

Codex, Claude Code, and ClaudeCodeUI / CloudCLI remain available as explicit
post-install commands (`codex`, `claude-code`, and `claude-code-ui`), but they
do not block the default first-run control plane installation. The CloudCLI
bootstrap command runs the Ubuntu Node stage, then Claude Code, then CloudCLI,
and registers the `cloudcli` service with service-manager when the control
plane is reachable. It has no dependency on `cc-connect`.

`components` installs child repos from APK-bundled package archives and then
delegates to child repo contracts. Termux-native targets stay in Termux;
Ubuntu/proot is used only for targets that explicitly require it:

- `pi-agent`
- `pi-web`
- `wuyou`
- `service-manager`
- `cc-connect` / `openhouse-connect`
- `SmallPhone`
- `smallphone-likegirl` control test, through the SmallPhone standalone app
  service registration

The default component order is:

```text
install service-manager -> install/check/register pi-agent/pi-web ->
install/check/register openhouse-connect -> install/check/register
SmallPhone compatibility service
```

The required APK asset archives are:

| Component | APK asset path | Runtime target |
| --- | --- | --- |
| service-manager | `openhouse/product-payloads/service-manager.tar` | `$HOME/smallphoneai-repos/service-manager` |
| cc-connect/openhouse-connect | `openhouse/product-payloads/openhouse-connect.tar` | `$HOME/smallphoneai-repos/openhouse-connect` |
| SmallPhone | `openhouse/product-payloads/smallphone.tar` | `$HOME/smallphoneai-repos/smallphone-active` |
| pi-agent (stable service ID) | `openhouse/product-payloads/pi-runtime.tar` | `$HOME/smallphoneai-repos/pi-runtime` |
| pi-web | `openhouse/product-payloads/pi-web.tar` | `$HOME/smallphoneai-repos/pi-web` |
| wuyou | `openhouse/product-payloads/wuyou.tar` | `$HOME/smallphoneai-repos/wuyou`; installs `$PREFIX/bin/wuyou` |

In the APK source tree these packages live under
`app/src/main/assets/openhouse/product-payloads`. The Android host or Gradle
asset-copy step must extract/copy those packages to
`$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads` before
first run. The `service-manager` payload is
`openhouse/product-payloads/service-manager.tar` and must contain the child
repo install/check contract plus a current-environment `service-manager`
binary. `50-install-runtime-components.sh` defaults to
`SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle`; this prevents first-run source
clones from GitHub. It is not an air-gapped install: apt, npm, pip, model
providers, and ordinary network checks may still be used when a stage needs
operating-system or package dependencies. The stable `pi-agent` component ID
installs the Node/Pi SDK payload from `pi-runtime.tar`; runtime code is
installed under `$HOME/.local/share/openhouseai/runtime`, Pi conversations and
extensions remain under `$HOME/.pi`, and the tokenless service listens on
`127.0.0.1:8765` using `wuxianpi-sdk-v1`. It requires Termux Node.js 22.19 or
newer. pi-web and wuyou also use APK-bundled payloads first; pi-web is shipped
as a complete runtime and should not run `npm install` during first-run setup.
The `pi-web` and `wuyou` commands are installed as Termux global commands and
can be run without service-manager.

GitHub source updates are a separate path through
`SMALLPHONEAI_COMPONENT_SOURCE_MODE=git-update` and
`SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE=1`.

Each child repo owns its own `scripts/install.sh`, `scripts/check.sh`, and
`scripts/register-service.sh`. This bootstrap only orchestrates those entry
points.

## App Shell Hooks

`bash bootstrap.sh status` and `bash bootstrap.sh check` print JSON only.
`bash bootstrap.sh hooks` prints the app-shell hook contract as JSON.
`install`/`full`, `start`, and `repair` run startup work and then print the
final status JSON for health gating.
The hook and manifest metadata mark those commands with
`reportsFinalHealth: true`; callers should parse the final stdout JSON object
after any progress logs.

Default readiness ports:

| Component | Endpoint |
| --- | --- |
| WuxianPi Node/Pi SDK runtime (`pi-agent` service ID) | `http://127.0.0.1:8765/health` |
| pi-web main agent UI | `http://127.0.0.1:30141/` |
| cc-connect bridge | `tcp://127.0.0.1:21010` |
| cc-connect management | `tcp://127.0.0.1:21020` |
| cc-connect webhook/callback | `tcp://127.0.0.1:21040` |
| service-manager | `http://127.0.0.1:20087/` |

SmallPhone frontend (`http://127.0.0.1:22082/`) and SmallPhone core
(`http://127.0.0.1:22000/`) are compatibility signals. They are reported in
status output but do not block pi/pi-web readiness.

OpenHouseAI component manifests live under `components.d/*.json` and use the
four-layer schema: `shellMenu`, `smallphoneApp`, `serviceManager`, and `ai`.
Only component manifests forbid embedded executable fields named `command`,
`shell`, `script`, or `args`. Bootstrap manifests and service-manager
ServiceSpec files are different contracts and may use `args` or `command`
where those fields are part of their own schema.

Control-test app ports:

| Component | URL |
| --- | --- |
| smallphone-likegirl | `http://127.0.0.1:23003/` |
| smallphone-likegirl clone | `http://127.0.0.1:23008/` |

`cc-connect` / `openhouse-connect` is an optional diagnostic and repairable
connection service. It is not required by the CloudCLI service and should not
block first-run pi-agent readiness.

The maintenance manifest remains at:

```text
https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main/openhouseai-manifest.json
```
