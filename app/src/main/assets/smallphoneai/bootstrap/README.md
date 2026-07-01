# SmallPhoneAI Bootstrap

This repo is the online bootstrap source for SmallPhoneAI on Termux/Ubuntu.
It prepares the phone runtime, installs agent CLIs, coordinates SmallPhone
runtime components, and exposes machine-readable hooks for the app shell.

The intended runtime split is:

- Termux native: installer, Ubuntu/proot launcher, bridge, and recovery
  fallback.
- Ubuntu/proot: primary runtime for `service-manager`, Pi Agent/pi-web,
  SmallPhone, `cc-connect`, and app services.

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
env-check -> prepare -> termux-packages -> ubuntu -> sync-docs ->
ubuntu-packages -> entry-ubuntu -> node -> codex -> claude-code ->
claude-code-ui -> components -> registry-sync -> start -> status
```

Retired external tools are no longer bundled as built-in installer
stages or optional APK asset scripts.

`components` enters Ubuntu/proot by default, installs child repos from
APK-bundled package archives, and then delegates to child repo contracts:

- `service-manager`
- `cc-connect` / `openhouse-connect`
- `SmallPhone`
- `pi-agent`
- `pi-web`
- `smallphone-likegirl` control test, through the SmallPhone standalone app
  service registration

The required APK asset archives are:

| Component | APK asset path | Runtime target |
| --- | --- | --- |
| service-manager | `openhouse/product-payloads/service-manager.tar` | `$HOME/smallphoneai-repos/service-manager` |
| cc-connect/openhouse-connect | `openhouse/product-payloads/openhouse-connect.tar` | `$HOME/smallphoneai-repos/openhouse-connect` |
| SmallPhone | `openhouse/product-payloads/smallphone.tar` | `$HOME/smallphoneai-repos/smallphone-active` |
| pi-agent | `openhouse/product-payloads/pi-agent.tar` | `$HOME/smallphoneai-repos/pi-agent` |
| pi-web | `openhouse/product-payloads/pi-web.tar` | `$HOME/smallphoneai-repos/pi-web` |

The Android host or Gradle asset-copy step must extract/copy those packages to
`$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads` before
first run. `50-install-runtime-components.sh` defaults to
`SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle`; this prevents first-run source
clones from GitHub. It is not an air-gapped install: apt, npm, pip, model
providers, and ordinary network checks may still be used when a stage needs
operating-system or package dependencies. In particular, Pi Agent/pi-web use
APK-bundled npm package archives first, but npm may still contact the configured
registry while resolving transitive dependencies.

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

`cc-connect` is part of readiness unless `SMALLPHONEAI_CC_CONNECT_DISABLED=1`
or `SMALLPHONEAI_DISABLE_CC_CONNECT=1` is set.

The maintenance manifest remains at:

```text
https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main/openhouseai-manifest.json
```
