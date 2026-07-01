# Operit Historical Removal Note

本文档只保留 Operit 在 OpenHouseAI 中的历史背景和移除边界。当前主线不再把 Operit 作为默认 agent、默认 UI、默认插件体系或首次安装关键路径。

## Current Decision

OpenHouseAI 当前主线是：

```text
Android App
  -> pi-web WebView entry
  -> service-manager controls

Ubuntu in Termux
  -> pi as the primary agent
  -> pi-web as the primary UI
  -> pi extensions as the default plugin system
```

Operit 可以作为历史迁移尝试被记录，但不应继续作为产品默认能力出现。

## What Must Not Be Reintroduced

新实现、文档和安装链路不得重新引入以下默认路径：

- Operit as the default agent kernel.
- Operit as the default Android-hosted AI surface.
- Operit package format as the default OpenHouseAI plugin standard.
- Operit-specific background processes started outside service-manager.
- A default menu entry that opens the old hosted Operit surface.
- First-install stages that download, initialize, or start Operit as a required component.

## Replacement Mapping

| Former role | Current replacement |
| --- | --- |
| Hosted assistant surface | pi-web |
| Agent runtime | pi |
| Plugin/tool registration | pi extensions |
| Runtime lifecycle | service-manager |
| Android menu entry | pi-web WebView entry |
| Tool and service state | service-manager APIs plus pi tool state |

Default service IDs:

```text
pi-agent
pi-web
```

Default pi extension directories:

```text
/root/.pi/extensions
/root/.pi/agent/extensions
```

## Allowed Historical References

Operit may still be mentioned in:

- code removal notes
- migration rollback records
- compatibility research
- user-requested analysis of removed functionality

Those references must state that Operit is historical or removed. They must not present Operit as the current default core.

## Rescue Assistant Boundary

The future Termux-side rescue assistant is a deferred capability. It is not implemented in this work, not installed by default, not a daemon, and not part of the first-install critical path.

If implemented later, it should reuse the pi/pi-web projects where practical, but run in a profile that can be installed and invoked independently from the Ubuntu main workspace.
