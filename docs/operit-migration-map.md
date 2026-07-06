# Operit Migration Map Archive

本文档是历史归档。旧的 Operit migration map 不再指导默认运行时实现；当前实现只把 Operit 恢复为 Android 侧 `withOperit` 完整可选构建。

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

- pi-agent 是 Android App 的默认一级服务入口。
- pi 是 Ubuntu 侧默认主 agent。
- `pi-agent` 和 `pi-web` 注册到 service-manager。
- 默认插件目录是 `/root/.pi/extensions` 和 `/root/.pi/agent/extensions`。
- 默认搜索插件是 `multi-platform-search.ts`。
- 救援助手只作为后置预留能力，不进入首次安装关键路径。
- Android 发布提供 `withOperit` / `withoutOperit` 两个 flavor，二者包名都保持 `com.termux`，不能共存，只能同签名且 `versionCode` 单调递增时互相升级/替换。

## Compatibility Note

如需重新评估 Ubuntu 侧 Operit 兼容层，应新开设计文档和实现计划，不能直接恢复本归档中的旧 Worker ownership 或验收标准。Android 侧完整 Operit 模块属于 `withOperit` flavor，不代表 Operit 成为默认 OpenHouse/Pi/AionUi 运行时。
