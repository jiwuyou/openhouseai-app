# Operit Migration Map Archive

本文档是历史归档。旧的 Operit migration map 不再指导默认实现。

## Replacement Map

| Old migration target | Current target |
| --- | --- |
| Hosted Operit assistant | pi-web |
| Operit core facade | pi agent runtime |
| Operit plugin bridge | pi extensions |
| Operit runtime bridge | explicit Termux/Ubuntu routing plus service-manager |
| Operit service controls | service-manager services for `pi-agent` and `pi-web` |

## Current Acceptance

当前实现主线的验收标准是：

- pi-web 是 Android App 的默认 AI 工作台入口。
- pi 是 Ubuntu 侧默认主 agent。
- `pi-agent` 和 `pi-web` 注册到 service-manager。
- 默认插件目录是 `/root/.pi/extensions` 和 `/root/.pi/agent/extensions`。
- 默认搜索插件是 `multi-platform-search.ts`。
- 救援助手只作为后置预留能力，不进入首次安装关键路径。

## Compatibility Note

如需重新评估 Operit 兼容层，应新开设计文档和实现计划，不能直接恢复本归档中的旧 Worker ownership 或验收标准。
