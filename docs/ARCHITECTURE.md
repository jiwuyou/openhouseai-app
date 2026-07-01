# OpenHouseAI Architecture

本文档面向开发者和高级用户，说明 OpenHouseAI 的长期架构边界。它回答一个核心问题：这个产品里哪些组件负责 UI、哪些组件负责 Android 宿主、哪些组件负责 Linux 运行时、哪些组件负责 AI agent 和服务控制。

## Architecture Summary

OpenHouseAI 是一个运行在 Android 手机上的人机协作 AI 平台。它不是单纯的聊天应用，也不是单纯的 Termux 终端。产品目标是在同一个可见、可控、可恢复的运行环境里，让用户和 AI 都能使用终端、文件、服务、模型工具、插件和应用能力。

长期架构如下：

```text
Android App / com.termux
  - product shell, permissions, onboarding, status, entry points
  - Termux bootstrap, first install, maintenance, recovery UI
  - SmallPhone and Operit hosted surfaces

Termux
  - Android host bridge
  - Termux packages, shell, proot-distro
  - Ubuntu install/start/stop/repair
  - rescue layer when Ubuntu or service-manager is unavailable

Ubuntu in Termux
  - primary Linux workspace
  - openhouse-agent long-term core location
  - Codex, Claude Code, CloudCLI
  - MCP servers and developer toolchains
  - service-manager managed services

service-manager
  - post-install control plane
  - service lifecycle, health, logs, repair, restart
  - machine-readable APIs for UI and AI

User and AI surfaces
  - SmallPhone as main human/AI interaction surface
  - Operit as configuration, diagnostics, plugin UI, beginner entry
  - Codex / Claude Code / CloudCLI as core AI and programming capabilities
```

## Component Boundaries

| Component | Owns | Does Not Own |
| --- | --- | --- |
| Android App | Product shell, first-run onboarding, permission guidance, visual status, explicit start/stop UI, APK assets, Termux bootstrap entry | Core agent loop, long-running Linux services, MCP daemon lifecycle |
| Termux | Android sandbox bridge, package prefix, `proot-distro`, Ubuntu lifecycle, rescue commands, Android-adjacent tools | Primary AI brain, large development toolchains, long-running agent state |
| Ubuntu in Termux | Main Linux workspace, agent runtime, Codex, Claude Code, CloudCLI, Node/Python/Rust tooling, MCP servers | Android permissions, APK lifecycle, Termux bootstrap repair |
| service-manager | Post-install service control plane, status APIs, logs, lifecycle actions, service definitions, health checks | First install chain, Android permission UI, direct model reasoning |
| SmallPhone | Main product interaction surface, user-facing AI workspace, status and workflow entry | Termux bootstrap ownership, Ubuntu rootfs ownership |
| Operit | AI configuration assistance, diagnostics, plugin UI, toolbox surfaces, beginner-friendly entry | Core agent kernel, primary runtime ownership, Android app shell replacement |
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
Ubuntu: reason, execute, build, run tools
service-manager: supervise and expose state
```

## Install Chain vs Control Plane

The first install chain exists to prepare the runtime. It is not the permanent control plane.

During first install, Android App and Termux coordinate:

1. prepare Termux paths and packages
2. install Ubuntu rootfs through `proot-distro`
3. install Ubuntu packages and Node runtime
4. install Codex, Claude Code, CloudCLI, SmallPhone runtime, and service-manager assets
5. sync service definitions and component registry
6. start the core services

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
| Ubuntu | Development commands, Codex, Claude Code, CloudCLI, MCP, openhouse-agent | Android permission changes |
| service-manager | Long-running services, health, logs, restart, service definitions | One-off shell execution that belongs in Termux/Ubuntu |

Long-running services must be owned by service-manager or by a service-manager-supervised process. Android UI code and Operit UI code must not directly start persistent MCP, bridge, or agent daemons.

## Operit Position

Operit is retained long term, but as a hosted capability inside the OpenHouseAI product shell. It should help users configure models, inspect tools, diagnose runtime issues, and use plugin/toolbox surfaces.

Operit is not the core agent kernel. It should call into the shared runtime bridge, service-manager, and future Ubuntu-side openhouse-agent rather than owning a second runtime.

See `OPERIT_ROLE.md` and `OPERIT_PLUGIN_COMPATIBILITY.md` for the detailed contract.

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

Current implementation contains the integration spine: SmallPhoneAI host, Termux/Ubuntu runtime ownership, first install flow, service-manager installation, SmallPhone runtime, CloudCLI, and a hosted Operit skeleton.

Long-term work should extend this spine instead of replacing it:

- build the openhouse-agent core in Ubuntu
- make service-manager the stable service API
- expose machine-readable state for AI agents
- keep Operit as UI/configuration/diagnostics/plugin surface
- keep Termux as host and rescue layer

## Non-Goals

- Do not make Operit the Android app shell.
- Do not place the primary agent loop inside an Android Activity or WebView.
- Do not place the primary agent loop in the Termux base layer.
- Do not start persistent daemons directly from UI code.
- Do not make OpenCode, Reasonix, or Hermes default APK core dependencies.
- Do not delete or rebuild Termux home as a normal repair action.
- Do not treat Ubuntu as a real VM; it is a proot-managed userspace environment.
