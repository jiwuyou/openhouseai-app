# OpenHouseAI Architecture

本文档面向开发者和高级用户，说明 OpenHouseAI 的长期架构边界。它回答一个核心问题：这个产品里哪些组件负责 UI、哪些组件负责 Android 宿主、哪些组件负责 Linux 运行时、哪些组件负责 AI agent 和服务控制。

## Architecture Summary

OpenHouseAI 是一个运行在 Android 手机上的人机协作 AI 平台。它不是单纯的聊天应用，也不是单纯的 Termux 终端。产品目标是在同一个可见、可控、可恢复的运行环境里，让用户和 AI 都能使用终端、文件、服务、模型工具、插件和应用能力。

长期架构如下：

```text
Android App / com.termux
  - product shell, permissions, onboarding, status, entry points
  - Termux bootstrap, first install, maintenance, recovery UI
  - pi-web and AionUi WebView entries and service controls
  - optional withOperit Android-hosted Operit feature

Termux
  - Android host bridge
  - Termux packages, shell, proot-distro
  - Ubuntu install/start/stop/repair
  - host layer when Ubuntu or service-manager is unavailable

Ubuntu in Termux
  - primary Linux workspace
  - pi as the primary agent runtime
  - pi-web as the primary user interface
  - Codex, Claude Code, CloudCLI
  - MCP servers and developer toolchains
  - service-manager managed services: pi-agent, pi-web, aionui-web, CloudCLI and connectors

service-manager
  - post-install control plane
  - service lifecycle, health, logs, repair, restart
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
| Termux | Android sandbox bridge, package prefix, `proot-distro`, Ubuntu lifecycle, rescue commands, Android-adjacent tools | Primary AI brain, large development toolchains, long-running agent state |
| Ubuntu in Termux | Main Linux workspace, pi, pi-web, Codex, Claude Code, CloudCLI, Node/Python/Rust tooling, MCP servers | Android permissions, APK lifecycle, Termux bootstrap repair |
| service-manager | Post-install service control plane, status APIs, logs, lifecycle actions, service definitions, health checks | First install chain, Android permission UI, direct model reasoning |
| pi | Primary agent runtime, tool calling, extension loading, RPC/API surface, plugin execution contracts | Android shell, Termux bootstrap, service supervision |
| pi-web | Main product interaction surface, user-facing AI workspace, tool display, local web UI | Service lifecycle ownership, Termux/Ubuntu installation |
| Operit | Optional Android-side complete feature in the `withOperit` flavor, including hosted UI and host bridge | Default OpenHouse agent/runtime, Ubuntu payload, pi plugin standard, AionUi replacement |
| Codex | Core coding/reasoning agent capability, codebase work, terminal-backed development tasks | Product shell, runtime supervision |
| Claude Code | Core coding/reasoning agent capability, codebase work, terminal-backed development tasks | Product shell, runtime supervision |
| CloudCLI | Model/account connectivity and ClaudeCodeUI-related access path where applicable | Product shell, runtime supervision |

## Agent Core Placement

The long-term OpenHouse agent core should live in Ubuntu, not in Android UI code and not in the Termux base layer.

Reasons:

- Codex, Claude Code, CloudCLI, MCP servers, Node, Python, Rust, package managers, and project workspaces all fit the Ubuntu/Linux model.
- Ubuntu gives the agent a portable working environment that can later move to a server, desktop Linux, or container with fewer Android-specific assumptions.
- Android Activity and WebView lifecycle is not a reliable place for long-running reasoning loops.
- Termux should stay thin enough to repair Ubuntu and bridge Android even when the agent is broken.

The intended split is:

```text
Android App: show, authorize, start, stop, inspect
Termux: host, bridge, rescue, install, repair
Ubuntu: run pi, pi-web, AI CLIs, builds, tools, and projects
service-manager: supervise pi-agent, pi-web, aionui-web, connectors, and expose state
```

## Install Chain vs Control Plane

The first install chain exists to prepare the runtime. It is not the permanent control plane.

During first install, Android App and Termux coordinate:

1. prepare Termux paths and packages
2. install Ubuntu rootfs through `proot-distro`
3. install Ubuntu packages and Node runtime
4. install Codex, Claude Code, CloudCLI, service-manager assets, pi, and the bundled pi-web runtime
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
| Termux | Host checks, Ubuntu install/repair, Android bridge commands, emergency shell | Main coding agent workflow |
| Ubuntu | Development commands, pi, pi-web, Codex, Claude Code, CloudCLI, MCP | Android permission changes |
| service-manager | Long-running services, health, logs, restart, service definitions | One-off shell execution that belongs in Termux/Ubuntu |

Long-running services must be owned by service-manager or by a service-manager-supervised process. Android UI code and WebView code must not directly start persistent MCP, bridge, or agent daemons.

## pi / pi-web Position

pi is the default OpenHouseAI agent runtime. It owns the default tool calling model, extension loading, and agent-facing APIs in Ubuntu.

pi-web is the default OpenHouseAI UI. The Android App opens it through a local WebView at `http://127.0.0.1:30141/`, while service-manager owns its process lifecycle.

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

Current implementation keeps the pi/pi-web/AionUi direction as the default runtime while restoring Operit as an optional Android flavor. The stable spine remains: Termux-derived Android host, Ubuntu runtime ownership, first install flow, service-manager installation, CloudCLI, and service/component registration.

Long-term work should extend this spine instead of replacing it:

- make pi the default agent core in Ubuntu
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
