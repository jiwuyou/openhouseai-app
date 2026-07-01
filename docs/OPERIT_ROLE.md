# Operit Role in OpenHouseAI

本文档定义 Operit 在 OpenHouseAI 中的长期角色。结论是：Operit 应长期保留，但它是配置、诊断、插件 UI、工具箱和小白入口，不是 OpenHouseAI 的核心 agent 内核。

## Product Role

Operit gives OpenHouseAI a friendly AI assistance surface:

- help users configure large-model access
- expose AI朋友 Help
- provide diagnostics and toolbox UI
- present plugin/package capabilities
- help beginners use advanced runtime features without learning every terminal command
- bridge Android-side tools and service-manager-backed capabilities into a user-facing assistant

This role is valuable and should remain. The boundary is that Operit should help users access the platform, not become the platform's runtime owner.

## What Operit Owns

Operit may own:

- hosted assistant UI inside the OpenHouseAI Android shell
- AI朋友 Help entry content and interaction model
- model configuration guidance and validation UI
- plugin/package list, enablement UI, and package metadata presentation
- toolbox surfaces for Termux, Ubuntu, Android, service-manager, and diagnostics
- Android-side permission explanation and user-friendly error messages
- short-lived tool calls through the shared runtime bridge
- adapters that call service-manager actions and read service-manager status

Operit can also keep its own assistant state where appropriate, as long as that state does not replace the shared runtime/service-manager source of truth.

## What Operit Must Not Own

Operit must not own:

- the Android app shell
- the primary launcher
- Termux bootstrap
- Ubuntu rootfs installation
- first install chain
- primary long-running agent loop
- service-manager lifecycle
- persistent MCP daemon startup
- persistent bridge daemon startup
- primary Linux workspace
- a second bundled Termux/proot/Ubuntu runtime

Operit UI code must not directly start persistent services. It should request service actions through service-manager or a stable runtime adapter.

## Agent Boundary

The core OpenHouse agent should live in Ubuntu and be managed by service-manager. Operit can talk to it, configure it, and expose user-friendly controls for it.

Recommended relationship:

```text
Operit hosted UI
  -> runtime bridge / service-manager API
    -> Ubuntu openhouse-agent
      -> Codex / Claude Code / CloudCLI / MCP tools
```

This keeps long-running reasoning and execution out of Android Activity lifecycle while preserving Operit's value as a user-facing AI surface.

## Navigation Contract

Operit is hosted inside the OpenHouseAI product shell. It must behave like an internal feature area:

- opened through `AI朋友 Help` or another explicit host entry
- provides `返回主菜单`
- returns by finishing the hosted Activity or using a host navigation bridge
- does not start a duplicate host shell
- does not kill Termux, Ubuntu, service-manager, or managed services when returning
- can provide an explicit `关闭 Operit` action that closes its UI and stops Operit-owned short-lived work

Returning to the main menu and closing the runtime are different operations. `返回主菜单` navigates. `关闭 Operit` closes the Operit feature surface. Service shutdown belongs to service-manager controls.

## Runtime Contract

Operit tool execution must target explicit runtime domains:

| Domain | Meaning |
| --- | --- |
| Android | Device and permission-gated Android operations |
| Termux | Host-level short commands and rescue actions |
| Ubuntu | Developer/runtime commands in the installed Ubuntu |
| service-manager | Managed service lifecycle, health, logs, repair |

Long-running tasks should go through service-manager. One-shot diagnostic commands may use Termux or Ubuntu through the runtime bridge.

## Relationship With SmallPhone

SmallPhone is the main user/AI workspace. Operit complements it:

- SmallPhone can be the main interaction and workflow surface.
- Operit can be the beginner-friendly assistant, configuration helper, toolbox, and plugin UI.
- Both should read shared service state instead of inventing separate runtime truth.
- Both should use service-manager for managed services.

## Relationship With Codex, Claude Code, and CloudCLI

Codex and Claude Code are core AI coding and execution capabilities. CloudCLI is important for model/account connectivity and ClaudeCodeUI-related workflows. They belong in Ubuntu, not inside Operit.

Operit can help configure, launch, or diagnose them through service-manager and Ubuntu runtime tools. It should not wrap them so tightly that they can only be used from the Operit UI.

## Keep Current and Future Claims Separate

Documentation and UI should distinguish:

- already integrated hosted Operit skeleton
- currently available toolbox/runtime adapters
- future full Operit chat/plugin/native capability migration
- future Ubuntu-side plugin compatibility layer

Avoid presenting future compatibility work as if it is already fully implemented.

## Design Rules

- Keep Operit as a feature under the OpenHouseAI host.
- Keep `com.termux`, Termux bootstrap, first install, and recovery owned by the host.
- Keep service lifecycle behind service-manager.
- Keep core agent execution in Ubuntu.
- Keep Operit plugin UI useful, but do not make Operit package runtime the only plugin standard.
- Prefer shared contracts over direct Activity or process coupling.
