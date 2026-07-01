# Operit Integration Archive

本文档是历史归档。原本的 Operit 合并主线已经停止作为默认方向。当前主线是移除 Operit 默认集成，并集成 pi / pi-web。

## Superseded Direction

旧方向：

```text
SmallPhoneAI host
  -> hosted Operit feature
  -> Operit tools and plugin UI
```

当前方向：

```text
OpenHouseAI Android host
  -> pi-web WebView entry
  -> service-manager controls
  -> Ubuntu pi / pi-web runtime
```

## Current Mainline

新的实现和审查应以这些文件为准：

- `docs/ARCHITECTURE.md`
- `docs/RUNTIME_LAYERING.md`
- `docs/PI_AGENT_PLUGIN_SYSTEM.md`
- `app/src/main/assets/openhouse/docs-public/AI_AGENT_REFERENCE.md`
- `app/src/main/assets/openhouse/docs-public/SERVICE_MANAGER.md`

## Removal Gates

完成去 Operit 主线时，应满足：

- APK 不再依赖 Operit feature module。
- Android Manifest 不再注册 hosted Operit Activity。
- 默认主菜单不再暴露 old hosted Operit entry。
- 首次安装链路不下载、不初始化、不启动 Operit。
- 产品文档不把 Operit 描述为默认能力。
- pi-web 是默认 UI，`pi-agent` 和 `pi-web` 由 service-manager 管理。

## Historical Use Only

本文档只用于解释为什么旧的 Operit 路线被替换。它不再作为实现合同、Worker ownership 合同或验收标准来源。
