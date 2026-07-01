# Operit Plugin Compatibility

本文档说明如何在 Termux 上的 Ubuntu 侧兼容 Operit 插件体系。目标是复用 Operit 的工具能力模型，而不是把 Operit Android 宿主完整搬到 Ubuntu。

## Compatibility Goal

OpenHouseAI can support an Operit-compatible tool runtime in Ubuntu so that Codex, Claude Code, CloudCLI, SmallPhone, Operit, and the future openhouse-agent can share plugin/tool capabilities.

The target is:

```text
Operit package/tool format
  -> Ubuntu compatibility runtime
    -> service-manager managed process
      -> MCP/API/tool calls available to agents and UI
```

This is a compatibility layer, not a second Operit app.

## Operit Plugin Layers

Operit plugin behavior can be separated into two layers.

Tool capability layer:

- package metadata
- enabled/imported package state
- `use_package`
- `packageName:toolName`
- JavaScript tool execution
- MCP tool forwarding
- environment variable requirements
- state/condition-based tool availability
- resource files used by tools

Android host layer:

- Activity lifecycle
- Compose/View UI hooks
- chat view hooks
- toolbox UI modules
- input menu hooks
- Android permission prompts
- Android-specific JS/runtime bridge
- app-local navigation and process assumptions

The Ubuntu compatibility runtime should focus on the tool capability layer. The Android host layer should stay in Android/Operit.

## Supported Long-Term Shape

The Ubuntu side can provide:

```text
list_packages
enable_package
disable_package
use_package
list_tools
call_tool(packageName:toolName, params)
read_package_metadata
check_package_env
```

It can expose these through:

- MCP server
- local HTTP API
- Unix socket where available
- service-manager action/status bridge

The preferred control path is:

```text
Android Operit UI / SmallPhone / AI agent
  -> service-manager
    -> operit-compat runtime in Ubuntu
      -> JS tool, MCP tool, or native command adapter
```

## What Can Be Compatible

Compatible targets:

- old-style Operit JavaScript packages
- package metadata and tool declarations
- required environment variables
- `use_package` activation semantics
- `packageName:toolName` naming convention
- MCP-backed tools
- `.toolpkg` subpackage metadata and tool resources where they do not require Android UI hooks
- command tools targeting Termux, Ubuntu, Android bridge, or service-manager

For `.toolpkg`, the container should not automatically become a callable package. The compatibility runtime should identify callable subpackages and expose those as tools, matching the Operit concept that the container can hold multiple package units.

## What Should Not Be Moved to Ubuntu

Do not move these into the Ubuntu compatibility runtime:

- Operit Android Activity shell
- Compose UI plugin rendering
- chat view hooks
- input menu hooks
- Android permission dialogs
- Android lifecycle callbacks
- WebView/Activity-specific state
- direct Android UI navigation
- persistent daemon startup from plugin UI code

These remain Android host responsibilities. Ubuntu may preserve their metadata for display, but it should not try to execute them as Linux plugin behavior.

## Relationship With MCP

MCP is the best bridge between Operit-compatible tools and other agents.

Recommended direction:

```text
Operit-compatible package runtime
  -> exposes package tools as MCP tools

Codex / Claude Code / openhouse-agent
  -> call those tools through MCP

Operit Android UI
  -> can also call them through service-manager or MCP bridge
```

This avoids making Claude Code or Codex understand Android-only Operit internals. They only need a stable tool protocol.

## Relationship With service-manager

The compatibility runtime itself should be managed by service-manager:

- start
- stop
- restart
- health
- logs
- package registry sync
- repair actions

Plugin tools that start long-running services should declare service-manager actions instead of launching daemons directly.

## Runtime Targets for Terminal Packages

Terminal-related packages should not collapse into one generic shell. They should expose distinct targets:

| Package Type | Intended Target |
| --- | --- |
| Termux terminal package | Host maintenance, Android-adjacent commands, Ubuntu lifecycle |
| Ubuntu terminal package | Development commands, Codex/Claude Code/CloudCLI, projects, MCP tools |
| Custom terminal package | User-declared environment, working directory, command wrapper, risk policy |

This distinction matters because Termux and Ubuntu have different filesystems, package managers, environment variables, and failure modes.

## Implementation Phases

Phase 1: Protocol compatibility

- define package registry storage
- implement package listing and enable/disable state
- implement `use_package`
- implement `packageName:toolName` lookup
- return normalized tool results

Phase 2: JavaScript package execution

- run JS tools in a Node-based runtime
- validate required environment variables
- restrict filesystem and command access through declared tool permissions
- capture logs and errors

Phase 3: MCP bridge

- expose compatible package tools as MCP tools
- allow Codex, Claude Code, and openhouse-agent to call them
- route lifecycle through service-manager

Phase 4: `.toolpkg` metadata and subpackages

- parse container metadata
- expose callable subpackages
- ignore or preserve Android UI hook metadata without executing it in Ubuntu

Phase 5: Android UI integration

- Operit Android UI displays packages, status, env requirements, and diagnostics
- package execution still goes through the Ubuntu compatibility runtime where appropriate

## Safety Rules

- Do not execute package code directly from Android UI.
- Do not let a plugin start persistent processes outside service-manager.
- Do not treat Android UI hooks as Ubuntu-executable plugin code.
- Do not give packages unrestricted access to Termux home by default.
- Do not hide whether a tool runs in Termux, Ubuntu, Android, or service-manager.
- High-risk tools should require explicit user confirmation or a higher trust level.

## Current vs Future

Current OpenHouseAI integration already has a hosted Operit skeleton and runtime bridge concepts. Full Ubuntu-side Operit plugin compatibility is a future architecture direction unless the implementation document for that runtime says otherwise.

Until that runtime exists, documentation and UI should describe this as a planned compatibility layer, not as a completed feature.
