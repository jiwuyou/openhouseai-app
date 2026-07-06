# Operit Integration Archive

本文档是历史归档。原本的 Operit 合并主线已经停止作为默认方向。当前主线以 pi / pi-web / AionUi 为默认运行时，同时允许 Android 侧 `withOperit` flavor 完整包含 Operit。

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
  -> optional withOperit Android-hosted Operit feature
```

## Current Mainline

新的实现和审查应以这些文件为准：

- `docs/ARCHITECTURE.md`
- `docs/RUNTIME_LAYERING.md`
- `docs/PI_AGENT_PLUGIN_SYSTEM.md`
- `app/src/main/assets/openhouse/docs-public/AI_AGENT_REFERENCE.md`
- `app/src/main/assets/openhouse/docs-public/SERVICE_MANAGER.md`

## Default Runtime Gates

默认运行时应满足：

- `withoutOperit` APK 不依赖 Operit feature module。
- `withoutOperit` Android Manifest 不注册 hosted Operit Activity。
- `withoutOperit` 默认主菜单不暴露 hosted Operit entry。
- `withOperit` APK 可以包含完整 Operit feature module、宿主桥接、Intent 转发和 Android 入口。
- 首次安装链路不下载、不初始化、不启动 Operit。
- 产品文档不把 Operit 描述为默认能力。
- pi-web 是默认 UI，AionUi 是独立本地页面能力，`pi-agent`、`pi-web` 和 `aionui-web` 由 service-manager 管理。
- `withOperit` 和 `withoutOperit` 都保持包名 `com.termux`，发布时只能同签名、递增 `versionCode` 后互相升级/替换，不能共存。

## Historical Use Only

本文档只用于解释为什么旧的 Operit 路线被替换。它不再作为实现合同、Worker ownership 合同或验收标准来源。
