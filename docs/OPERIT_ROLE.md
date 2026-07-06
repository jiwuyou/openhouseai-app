# Operit Optional Android Build

本文档说明 Operit 在 OpenHouseAI 中的当前定位：它已经作为 Android 侧完整可选构建恢复，但仍不是默认 agent、默认 UI、默认插件体系或首次安装关键路径。

## Current Decision

OpenHouseAI 当前默认主线是：

```text
Android App
  -> pi-web WebView entry
  -> AionUi WebView entry
  -> service-manager controls

Ubuntu in Termux
  -> pi as the primary agent
  -> pi-web as the primary UI
  -> AionUi as an independent local web page
  -> pi extensions as the default plugin system
```

Operit 当前作为 Android 侧 flavor 能力存在：

```text
withOperit
  -> includes :operit-feature and all operit-* Android modules
  -> installs the real Android host bridge
  -> exposes the Operit Android entry where product UI enables it

withoutOperit
  -> does not depend on :operit-feature
  -> uses no-op host bridge/source-set integration
  -> does not expose Operit entry points
```

两个 flavor 的 `applicationId` 都保持 `com.termux`，不使用 `applicationIdSuffix`。

## Release Rule

`withOperit` 和 `withoutOperit` 是同包名 APK，不能在同一台设备上共存。发布时必须按 Android 更新规则处理：

- 两个 APK 必须使用同一签名，才能互相升级或替换。
- `versionCode` 必须单调递增，不能用较低版本覆盖较高版本。
- 用户从一个 flavor 切到另一个 flavor，本质上是同一应用的升级/替换，不是安装第二个应用。
- 测试渠道、正式渠道和回滚包都要遵守相同签名和版本序关系。

## Required Boundary

新实现、文档和安装链路必须保持以下边界：

- Operit 是 Android 侧完整模块，不是 Ubuntu payload。
- Operit 不替代 OpenHouse/Pi/AionUi 运行时。
- pi 仍是默认主 agent，pi-web 仍是默认主 UI，AionUi 仍是独立本地页面能力。
- Operit package/plugin 格式不成为默认 OpenHouseAI 插件标准。
- `withoutOperit` 不能依赖或硬引用 `com.ai.assistance.operit.*`。
- 首次安装链路不把 Operit 作为必需下载、初始化或启动阶段。

## Replacement Mapping

| Former default role | Current default owner |
| --- | --- |
| Hosted assistant surface | pi-web |
| Agent runtime | pi |
| Plugin/tool registration | pi extensions |
| Runtime lifecycle | service-manager |
| Android menu entry | pi-web and AionUi WebView entries |
| Tool and service state | service-manager APIs plus pi tool state |

Default service IDs:

```text
pi-agent
pi-web
aionui-web
```

Default pi extension directories:

```text
/root/.pi/extensions
/root/.pi/agent/extensions
```

## Allowed References

Operit may be mentioned in:

- Android flavor and release documentation
- withOperit host bridge and UI entry documentation
- migration rollback records
- compatibility research
- historical notes

Those references must state that Operit is optional Android-side functionality. They must not present Operit as the default OpenHouse/Pi/AionUi runtime.

## Rescue Assistant Boundary

The future Termux-side rescue assistant is a deferred capability. It is not implemented in this work, not installed by default, not a daemon, and not part of the first-install critical path.

If implemented later, it should reuse the pi/pi-web projects where practical, but run in a profile that can be installed and invoked independently from the Ubuntu main workspace.
