# Runtime Layering

本文档说明 Android App、Termux、Termux 上的 Ubuntu、service-manager 和 AI 工具链之间的运行分层。它的目标是让开发者、维护者和 AI agent 都能判断任务应该在哪一层执行。

## Layer Model

```text
Layer 1: Android App
  - user interface
  - onboarding and maintenance screens
  - permissions and app lifecycle
  - explicit run/stop controls

Layer 2: Termux
  - Android-adjacent host environment
  - Termux package prefix
  - proot-distro host
  - Ubuntu lifecycle and rescue commands

Layer 3: Ubuntu in Termux
  - primary Linux runtime
  - development workspace
  - Codex, Claude Code, CloudCLI
  - MCP and openhouse-agent services

Layer 4: service-manager
  - post-install control plane
  - service status, lifecycle, logs, repair, registry
  - shared API for Android UI, SmallPhone, Operit, and AI agents
```

The layers are not equal. Android App and Termux are host layers. Ubuntu is the workbench. service-manager is the normal control plane after installation.

## Android App Layer

The Android App owns user-facing product lifecycle:

- first-run onboarding
- install progress UI
- permission guidance
- main menu and entry points
- AI朋友 Help hosted entry
- maintenance and recovery UI
- APK-bundled docs and assets
- explicit "open", "return", "close", and status controls

It should not own the primary long-running agent process. Android lifecycle can pause, destroy, or recreate Activities. WebView and Activity code should be treated as presentation and control surfaces, not the agent runtime.

## Termux Layer

Termux is the Android host and rescue layer. It is close to Android and close to the app sandbox, so it owns:

- `$PREFIX` package environment
- Termux shell and package manager
- `proot-distro`
- Ubuntu rootfs installation and removal
- Ubuntu start/stop/repair entry points
- Android bridge commands
- host-level logs and diagnostics
- emergency/failsafe operations when Ubuntu is broken

Termux commands are best for short, host-level operations:

```text
check Termux prefix
check proot-distro
install or repair Ubuntu
collect Android-adjacent logs
restart the Ubuntu supervisor
inspect ports or process trees from the host side
```

Termux should not become the main AI workbench. Keeping it thin makes it possible to repair Ubuntu and recover the product when the higher layers fail.

## Ubuntu Layer

Ubuntu is the primary AI and development runtime. It should own:

- user projects
- long-running agent core
- Codex
- Claude Code
- CloudCLI
- Node.js, Python, Rust, Git, build tools
- MCP servers
- service-manager runtime services
- knowledge indexing and developer workflows

Most commands that operate on projects, dependencies, models, MCP tools, or code should run in Ubuntu.

Ubuntu inside Termux is not a hardware virtual machine. It is a proot-managed userspace rootfs. CPU instructions run natively when architecture matches the phone CPU, while system calls and paths are translated by proot. This means frequent process startup and heavy file scanning can be expensive. Long-running services should be reused instead of starting a fresh `proot-distro login ubuntu -- command` for every small tool call.

## service-manager Layer

service-manager is the control plane after the first install is complete. It should provide machine-readable operations for:

- service list
- service status
- health checks
- start/stop/restart
- logs
- repair actions
- component registry sync

Android UI, SmallPhone, Operit, Codex, Claude Code, and future openhouse-agent should prefer service-manager APIs for managed services.

The first install chain creates service-manager. After that, service-manager manages the normal service lifecycle. If service-manager itself is unavailable, control falls back to Termux and Android recovery.

## Command Placement Rules

Use these defaults:

| Task | Preferred Layer |
| --- | --- |
| Ask for Android permission, open app page, show onboarding | Android App |
| Check whether Termux prefix exists | Termux |
| Install or repair Ubuntu rootfs | Termux |
| Repair `dpkg` or packages inside Ubuntu | Ubuntu, launched from Termux if needed |
| Run Codex or Claude Code | Ubuntu |
| Run project build/test commands | Ubuntu |
| Start or stop SmallPhone runtime | service-manager |
| Start or stop MCP servers | service-manager |
| Read managed service logs | service-manager |
| Recover from service-manager unreachable | Termux first, then Ubuntu diagnostics |
| Fully close AI runtime | service-manager stop, then Termux process cleanup if needed |

## Terminal Profiles

Termux terminal and Ubuntu terminal must remain distinct.

Termux terminal:

- repairs the base
- controls Ubuntu lifecycle
- interacts with Android-adjacent files and commands
- remains useful when Ubuntu is broken

Ubuntu terminal:

- is the everyday developer terminal
- runs Codex, Claude Code, CloudCLI, projects, package managers, and MCP tools
- is the primary place where the long-term agent core should execute

Custom terminal profiles can exist, but they should declare which layer they target and what environment variables, working directory, and permissions they assume.

## Lifecycle Ownership

Persistent processes should follow this ownership:

```text
Android foreground/background UI -> Android App
Termux host helper/supervisor     -> Termux
Ubuntu service processes          -> service-manager
Agent core                        -> Ubuntu service managed by service-manager
MCP servers                       -> Ubuntu service managed by service-manager
```

Directly launching persistent Node, MCP, bridge, or agent processes from Android UI code is not allowed in the long-term architecture. UI may request service-manager actions, and service-manager may start or stop the process.

## Recovery Order

When something fails, diagnose from the lowest layer that can still run:

1. Android App state and permissions
2. Termux prefix, shell, packages, `proot-distro`
3. Ubuntu rootfs, package state, process tree
4. service-manager health and logs
5. individual services such as SmallPhone, CloudCLI, MCP servers, openhouse-agent

Do not default to clearing app data or deleting Termux home. Those actions can destroy Ubuntu rootfs, user projects, credentials, and service configuration.

## Performance Rule

Avoid high-frequency one-shot Ubuntu commands through repeated proot login. For tools that the UI or AI calls often, prefer:

```text
Android/Operit/SmallPhone
  -> service-manager API
    -> long-running Ubuntu service
```

This reduces proot startup overhead and makes logs, health, and shutdown behavior easier to control.
