# Operit Plugin Compatibility Archive

本文档是历史兼容性记录。OpenHouseAI 当前默认插件体系是 pi extensions，不是 Operit plugin compatibility runtime。Operit 已作为 Android 侧 `withOperit` 完整可选构建恢复，但这不改变默认插件体系。

## Current Plugin Direction

默认插件目录：

```text
/root/.pi/extensions
/root/.pi/agent/extensions
```

默认搜索插件：

```text
multi-platform-search.ts
```

默认 agent/UI：

```text
pi
pi-web
```

默认服务：

```text
pi-agent
pi-web
```

详见 `PI_AGENT_PLUGIN_SYSTEM.md`。

## Historical Scope

早期讨论过在 Ubuntu 侧兼容 Operit 包和工具格式。这个方向现在不是默认实现目标。本仓库默认文档、安装链路、侧边栏入口和 AI 操作参考都应以 pi extensions 为准。

`withOperit` 的 Android feature/module 是完整 Android 侧能力，不等于把 Operit package format 引入 Ubuntu，也不等于让 pi 或 pi-web 兼容 Operit 插件。

如果未来用户明确要求研究 Operit package compatibility，应作为可选兼容层重新立项，并满足以下边界：

- 不替代 pi extensions。
- 不进入 APK 默认安装链路。
- 不注册默认 Ubuntu 运行时入口。
- 不绕过 service-manager 启动长期进程。
- 不把 Android UI hook 当作 Ubuntu 可执行插件代码。
- 不要求 Codex、Claude Code、CloudCLI 或 pi 直接理解 Android-only internals。

## Migration Rule

发现旧文档、代码或配置仍引用 Operit 插件兼容路线时，应先判断它是否只是历史说明。

如果它影响默认产品行为，应迁移到：

```text
pi extension
  -> pi tool registration
  -> pi-web tool display
  -> service-manager managed service when long-running
```

旧 pi-web 会话可能不会自动看到新插件。更新扩展后，新建会话是最稳的刷新方式。
