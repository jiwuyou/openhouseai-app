# OpenHouseAI Architecture

本文档面向开发者和高级用户，说明 OpenHouseAI 的长期架构边界。它回答一个核心问题：这个产品里哪些组件负责 UI、哪些组件负责 Android 宿主、哪些组件负责 Linux 运行时、哪些组件负责 AI agent 和服务控制。

## Architecture Summary

OpenHouseAI 是一个运行在 Android 手机上的人机协作 AI 平台。它不是单纯的聊天应用，也不是单纯的 Termux 终端。产品目标是在同一个可见、可控、可恢复的运行环境里，让用户和 AI 都能使用终端、文件、服务、模型工具、插件和应用能力。

长期架构如下：

```text
Android App / com.termux
  - product shell, permissions, onboarding, status, entry points
  - native desktop page and existing home function page
  - Termux bootstrap, first install, maintenance, recovery UI
  - pi-web and AionUi WebView entries and service controls
  - optional withOperit Android-hosted Operit feature

Termux
  - Android host bridge
  - Termux packages, shell, proot-distro
  - Ubuntu install/start/stop/repair
  - native Node runtime for long-running pi-agent and pi-web
  - host layer when Ubuntu is unavailable

Ubuntu in Termux
  - primary Linux workspace
  - Codex, Claude Code, CloudCLI
  - MCP servers and developer toolchains
  - service-manager managed Ubuntu/proot services: aionui-web, CloudCLI and connectors

service-manager
  - post-install control plane
  - service lifecycle, health, logs, repair, restart across Termux and Ubuntu/proot providers
  - machine-readable APIs for UI and AI

User and AI surfaces
  - pi-web as the default human/AI interaction surface
  - pi extensions as the default plugin system
  - Codex / Claude Code / CloudCLI as core AI and programming capabilities
```

## Component Boundaries

| Component | Owns | Does Not Own |
| --- | --- | --- |
| Android App | Product shell, first-run onboarding, permission guidance, visual status, explicit start/stop UI, APK assets, Termux bootstrap entry | Core agent loop, long-running Linux services, MCP daemon lifecycle |
| Termux | Android sandbox bridge, package prefix, `proot-distro`, Ubuntu lifecycle, rescue commands, Android-adjacent tools, native Node runtime for `pi-agent` and `pi-web` | Large development toolchains that belong in Ubuntu |
| Ubuntu in Termux | Main Linux workspace, Codex, Claude Code, CloudCLI, Node/Python/Rust tooling, MCP servers, AionUi and user services | Android permissions, APK lifecycle, Termux bootstrap repair, Termux-native pi/pi-web service ownership |
| service-manager | Post-install service control plane, status APIs, logs, lifecycle actions, service definitions, health checks | First install chain, Android permission UI, direct model reasoning |
| pi | Primary agent runtime, tool calling, extension loading, RPC/API surface, plugin execution contracts | Android shell, Termux bootstrap, service supervision |
| pi-web | Main product interaction surface, user-facing AI workspace, tool display, local web UI | Service lifecycle ownership, Termux/Ubuntu installation |
| Operit | Optional Android-side complete feature in the `withOperit` flavor, including hosted UI and host bridge | Default OpenHouse agent/runtime, Ubuntu payload, pi plugin standard, AionUi replacement |
| Codex | Core coding/reasoning agent capability, codebase work, terminal-backed development tasks | Product shell, runtime supervision |
| Claude Code | Core coding/reasoning agent capability, codebase work, terminal-backed development tasks | Product shell, runtime supervision |
| CloudCLI | Model/account connectivity and ClaudeCodeUI-related access path where applicable | Product shell, runtime supervision |

## Agent Core Placement

The long-term OpenHouse agent core must not live in Android UI code. The current default places `pi-agent` and `pi-web` in Termux native Node so they can stay available while Ubuntu is being installed or repaired; Ubuntu remains the main workbench for projects and heavier AI tools.

Reasons:

- Codex, Claude Code, CloudCLI, MCP servers, Python, Rust, package managers, and project workspaces all fit the Ubuntu/Linux model.
- Ubuntu gives the agent a portable working environment that can later move to a server, desktop Linux, or container with fewer Android-specific assumptions.
- Android Activity and WebView lifecycle is not a reliable place for long-running reasoning loops.
- Termux owns the host control layer, service-manager, and the native pi/pi-web services that must survive Ubuntu repair.

The intended split is:

```text
Android App: show, authorize, start, stop, inspect
Termux: host, bridge, rescue, install, repair, run pi-agent and pi-web
Ubuntu: run AI CLIs, builds, tools, projects, AionUi, and user services
service-manager: supervise Termux-native and Ubuntu/proot services, and expose state
```

## Install Chain vs Control Plane

The first install chain exists to prepare the runtime. It is not the permanent control plane.

During first install, Android App and Termux coordinate:

1. prepare Termux paths and packages
2. install Ubuntu rootfs through `proot-distro`
3. install Ubuntu packages and Node runtime
4. install Termux Node, service-manager assets, pi, and the bundled pi-web runtime
5. sync pi extensions, service definitions, and component registry
6. start `pi-agent`, `pi-web`, and `aionui-web` through service-manager

The pi-web first install path uses the complete runtime bundled in the APK. It should extract, verify, register, and start that runtime instead of installing pi-web through `npm install -g` from a tgz package. Other first-install stages, including Node.js, Codex, Claude Code, CloudCLI, Ubuntu packages, and missing dependencies, may still require network access.

After first install succeeds, service-manager becomes the control plane for runtime services:

```text
status -> service-manager
start  -> service-manager
stop   -> service-manager
logs   -> service-manager
repair -> service-manager where possible
```

The Android App may still own emergency repair actions, especially when Termux, Ubuntu, or service-manager cannot start.

## Runtime Authority

Runtime operations must use explicit targets:

| Target | Use For | Avoid |
| --- | --- | --- |
| Android | Permissions, intents, APK state, app private files, UI navigation | Linux development tasks |
| Termux | Host checks, Ubuntu install/repair, Android bridge commands, emergency shell, pi-agent/pi-web runtime | Large project builds that belong in Ubuntu |
| Ubuntu | Development commands, Codex, Claude Code, CloudCLI, MCP, AionUi and user services | Android permission changes, Termux-native pi/pi-web service ownership |
| service-manager | Long-running services, health, logs, restart, service definitions | One-off shell execution that belongs in Termux/Ubuntu |

Long-running services must be owned by service-manager or by a service-manager-supervised process. Android UI code and WebView code must not directly start persistent MCP, bridge, or agent daemons.

## pi / pi-web Position

pi is the default OpenHouseAI agent runtime. It owns the default tool calling model, extension loading, and agent-facing APIs. In the current mobile runtime it runs in Termux native Node and can call into Ubuntu for workbench tasks.

pi-web is the default OpenHouseAI UI. The Android App opens it through a local WebView at `http://127.0.0.1:30141/`, while service-manager owns its long-running process lifecycle. It also installs a Termux global `pi-web` command, so a user or AI can run `pi-web --port 30142` directly without service-manager for debugging or temporary sessions.

wuyou is a bundled Termux-first Rust/Axum web coding agent. It installs as the Termux global `wuyou` command and is not registered as a service by default.

Default service IDs:

- `pi-agent`
- `pi-web`

Default pi environment:

```text
PI_CODING_AGENT_DIR=/root/.pi
/root/.pi/extensions
/root/.pi/agent/extensions
```

The default search extension is `multi-platform-search.ts`. Existing pi-web sessions may need a new conversation after extension changes because tool lists can be captured when the session starts.

See `PI_AGENT_PLUGIN_SYSTEM.md` for the detailed contract.

## Native Desktop Position

OpenHouse has a native desktop route and app routes. The word "home" should be treated as a default-entry strategy, not as the old menu page: users may choose to open the native desktop, a specific desktop app, or the last page from the previous session. The previous home/menu overview remains available as a normal desktop app and fallback route.

The native desktop is not an all-WebView multi-window shell. It renders an Android-native, horizontally paged sparse slot grid, with icons and names only by default. Empty slots are allowed and are not automatically compacted; dragging an icon past the last page may create a new page. Status badges are intentionally omitted from app icons; app state is shown through app details, explicit status surfaces, or launch failure recovery.

Desktop app entries are registry-backed and may point to:

- `webview`
- `native-page`
- `terminal`
- `service-control`
- `android-activity`

Short press opens the app through the launcher. Long press enters desktop edit mode, where the user can drag icons within sparse slots or across pages, rename entries, change lightweight icon keys, hide/reset entries, set a default entry, or open app details/status. If launch or WebView main-frame loading fails, the status sheet is shown automatically so the user can retry, restart, inspect logs, open service control, or go to maintenance.

The desktop route hides the top app control bar. App routes show a bar structured as `left sidebar / desktop / current app name / refresh / collapse / right control sidebar`. The left sidebar opens global navigation, the desktop button returns to the native desktop, and the right control sidebar opens the current app's status, logs, service controls, and recovery actions. The user can collapse the bar into a white-to-black gradient floating ball; the ball is draggable, snaps to the left or right edge, persists its position, and restores the bar when tapped. The existing menu overview remains available as a native app entry. Desktop work must not remove it, collapse it into the desktop route, or require multiple WebViews to stay resident.

## Operit Optional Build Position

Operit is available as a complete Android-side optional build, not as the default OpenHouseAI agent, UI, plugin system, or Ubuntu payload. The build matrix is:

```text
withOperit
  - applicationId: com.termux
  - includes :operit-feature and operit-* Android modules
  - installs the real Operit host bridge and Android entry points

withoutOperit
  - applicationId: com.termux
  - excludes :operit-feature
  - uses no-op Operit source-set integration
```

Because both APKs use the same package name, they cannot coexist. Release channels must use the same signing key and monotonically increasing `versionCode` values so one flavor can upgrade or replace the other.

New architecture, first-install stages, and product copy must still target pi, pi-web, AionUi, and service-manager as the default runtime. Operit must not be described as replacing OpenHouse/Pi/AionUi.

## Termux Compatibility Constraint

The Android host remains a Termux-derived APK with package identity `com.termux`. Classic Termux compatibility depends on keeping the Android target SDK at API 28. Architecture work must not accidentally raise the target SDK or import platform assumptions that break the Termux execution model.

## Failure Domains

The architecture assumes that every layer can fail:

- Ubuntu can be half-installed, locked by `dpkg`, missing packages, or blocked by stale processes.
- service-manager can be unreachable even when Ubuntu exists.
- Termux can have a damaged prefix or package database.
- Android can kill background processes or revoke permissions.

Therefore recovery is layered:

```text
Android App repairs Termux or starts failsafe paths.
Termux repairs Ubuntu and starts service-manager.
service-manager repairs and supervises normal services.
Ubuntu hosts agent and developer workflows.
```

## Current vs Long-Term State

Current implementation keeps the pi/pi-web/AionUi direction as the default runtime while restoring Operit as an optional Android flavor. The stable spine remains: Termux-derived Android host, Termux-native pi/pi-web services, Ubuntu workbench ownership, first install flow, service-manager installation, CloudCLI, and service/component registration.

Long-term work should extend this spine instead of replacing it:

- make pi the default agent core in Termux native Node, with Ubuntu as its main workbench
- make pi-web the default UI
- make service-manager the stable service API
- expose machine-readable state for AI agents
- keep Termux as host and rescue layer

## Non-Goals

- Do not make Operit the default Android app shell.
- Do not make Operit the default OpenHouseAI agent, UI, or plugin system.
- Do not make Operit a Ubuntu payload or a prerequisite for pi, pi-web, or AionUi.
- Do not place the primary agent loop inside an Android Activity or WebView.
- Do not place the primary agent loop in the Termux base layer.
- Do not start persistent daemons directly from UI code.
- Do not make OpenCode, Reasonix, or Hermes default APK core dependencies.
- Do not delete or rebuild Termux home as a normal repair action.
- Do not treat Ubuntu as a real VM; it is a proot-managed userspace environment.
