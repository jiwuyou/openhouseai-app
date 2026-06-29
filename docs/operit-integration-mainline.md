# Operit Integration Mainline

本文档是 SmallPhoneAI 与 Operit 合并的主合同。合并目标是整体迁移 Operit
能力，但宿主身份保持不变：SmallPhoneAI 继续作为唯一 Android APK、唯一主菜单、
唯一 Termux/Ubuntu 运行时宿主，Operit 迁入为内部 AI 功能区。

## Product Direction

- 整体迁移定义为功能全迁移、宿主不变。
- SmallPhoneAI 保持单 APK 产品壳，包名和运行时身份继续是 `com.termux`。
- 默认首屏、维护抽屉、首次安装、修复、payload 同步、Termux bootstrap、
  Ubuntu/proot 和 `service-manager` 继续由 SmallPhoneAI 负责。
- Operit 迁入的是 AI/chat/tool/MCP/UI/native 能力，不迁入第二个 app shell。
- 用户入口统一命名为 `AI朋友 Help`，从 SmallPhoneAI 主菜单或抽屉进入。
- Operit hosted 页面必须提供 `返回主菜单`，返回优先使用当前 Activity 栈
  `finish()`，不得在通用 Operit 层硬编码 SmallPhoneAI Activity 类名。

## Architecture Contract

合并后的主结构：

```text
SmallPhoneAI APK / com.termux
├─ Host shell
│  ├─ OpenHouseHomeActivity
│  ├─ first-run install / maintenance / recovery
│  └─ TermuxApplication / TermuxService
├─ Operit feature
│  ├─ OperitFeatureInitializer
│  ├─ chat / LLM / tool registry / permissions
│  ├─ Android device tools
│  ├─ MCP adapter
│  └─ hosted UI
├─ Runtime bridge
│  ├─ Android device execution
│  ├─ Termux command execution
│  ├─ Ubuntu/proot command execution
│  └─ service-manager status/actions/logs
└─ Managed runtime
   └─ service-manager / SmallPhone services / cc-connect / agent services
```

Operit code must treat the SmallPhoneAI host as an integration environment, not
as an implementation detail. Shared contracts should be expressed through
feature initializers, runtime interfaces, host navigation interfaces, and
service-manager adapters.

## Current Implementation Snapshot

The current SmallPhoneAI tree contains the migration skeleton, not the complete
Operit product feature set.

Already present in the skeleton:

- `OpenHouseHomeActivity` exposes `AI朋友 Help` and opens the hosted Operit
  surface with hosted/help intent extras.
- `OperitAssistantActivity` is registered inside the SmallPhoneAI APK and
  provides `返回主菜单` through `Activity.finish()`.
- `OperitFeatureInitializer` records idempotent feature initialization inside
  the SmallPhoneAI host.
- `OperitAssistantFacade`, `OperitToolRouter`, and the runtime bridge provide
  the first host-callable adapter surface.
- Runtime targets for Android, Termux, Ubuntu, and service-manager are explicit.

Not yet complete in the skeleton:

- Full Operit chat state, LLM provider registry, MCP registry, plugin lifecycle,
  Compose UI, native inference, speech, avatar, workflow, and original Operit
  advanced settings are not fully migrated.
- The original Operit `Application`, launcher shell, primary terminal runtime,
  and bundled proot/Ubuntu runtime are intentionally not imported.

## Runtime Boundary

SmallPhoneAI owns the Linux runtime through Termux and Ubuntu/proot. Operit
terminal-dependent behavior must be adapted to the SmallPhoneAI runtime bridge.

Allowed runtime targets:

- `ANDROID`: Android/device shell and permission-gated device control.
- `TERMUX`: short commands inside the Termux app environment.
- `UBUNTU`: short commands inside the installed Ubuntu/proot environment.
- `SERVICE_MANAGER`: managed service status, lifecycle actions, logs, and
  health checks through service-manager APIs.

Long-running processes, MCP servers, bridges, agent daemons, service health, and
logs belong behind `service-manager`. Android UI code and Operit adapters must
not start persistent MCP, bridge, or agent daemons directly.

## Migration Mainline

The migration proceeds as one coordinated mainline with parallel work streams:

1. Host entry and navigation
   - SmallPhoneAI exposes `AI朋友 Help`.
   - Hosted Operit pages expose `返回主菜单`.
   - Default SmallPhoneAI launch and first-run behavior stay unchanged.

2. Operit feature lifecycle
   - Extract Operit initialization into `OperitFeatureInitializer`.
   - The feature initializer is called by SmallPhoneAI on demand.
   - No second `Application` is registered.

3. Core assistant capability
   - Migrate chat, LLM providers, tool registry, permissions, message state,
     and tool routing behind SmallPhoneAI-owned entry points.
   - Keep Operit core independent from SmallPhoneAI Activity classes.

4. Runtime unification
   - Replace Operit terminal/proot assumptions with the SmallPhoneAI runtime
     bridge.
   - Keep Android device-control tools separate from Linux runtime execution.
   - Route managed lifecycle through service-manager.

5. UI migration
   - Migrate Operit hosted UI under the SmallPhoneAI product shell.
   - Hosted mode controls `返回主菜单`.
   - Compose/Kotlin migration is a build-system task and must be integrated
     deliberately, not by copying modules wholesale.

6. MCP and plugin migration
   - Adapt Operit MCP registry and plugin configuration.
   - Move managed process lifecycle to service-manager.
   - Avoid `/sdcard/Download/Operit` as the primary runtime workspace.

7. Native and advanced capabilities
   - Migrate native ML, speech, avatar, workflow, QuickJS, and local model
     features one capability at a time.
   - Each native module needs ABI, size, permission, and startup impact review.

## Explicit Non-Goals

- Do not replace SmallPhoneAI as the Android host.
- Do not change `com.termux`, `TermuxApplication`, `TermuxService`, default
  launcher, bootstrap, payload generation, first-run install, or repair
  ownership as part of Operit migration.
- Do not import Operit's `:terminal`, generated proot scripts, bundled Ubuntu
  rootfs, or local terminal runtime as the primary runtime.
- Do not make `/sdcard/Download/Operit` the primary workspace.
- Do not run persistent services outside `service-manager`.
- Do not merge Manifest, Gradle, provider authority, permission, native ABI, or
  resource changes by bulk copy.

## Parallel Ownership

- Host ownership:
  - `OpenHouseHomeActivity`
  - host navigation extras
  - host-level labels for `AI朋友 Help`
  - no runtime or core changes

- Core ownership:
  - `app/src/main/java/com/termux/app/operit/core/**`
  - `app/src/main/java/com/termux/app/operit/init/**`
  - no UI, Manifest, Gradle, runtime implementation changes

- Runtime ownership:
  - `app/src/main/java/com/termux/app/operit/runtime/**`
  - no UI, Manifest, Gradle, host menu, or core policy changes

- UI ownership:
  - hosted Operit Activity, layouts, UI strings, and navigation bridge usage
  - no host drawer edits unless serialized by Lead

- MCP ownership:
  - MCP adapters, plugin registry mapping, service-manager specs, and logs
  - no direct long-running process starts

- Build/Manifest ownership:
  - `settings.gradle`
  - app Gradle files
  - `AndroidManifest.xml`
  - provider authorities, permissions, native ABI, Compose/Kotlin setup
  - serialized by Lead only

No two Workers may modify the same file concurrently. Shared files are Lead-owned
and must be integrated serially after Reviewer approval.

## Acceptance Gates

Required gates for the integrated migration mainline:

- SmallPhoneAI default launch flow remains unchanged.
- The main menu or drawer exposes `AI朋友 Help`.
- `AI朋友 Help` opens the hosted Operit AI surface.
- Hosted Operit UI shows `返回主菜单`.
- `返回主菜单` returns to the SmallPhoneAI main surface without duplicate
  Activity stacks or bootstrap restart.
- Operit feature initialization is available through `OperitFeatureInitializer`.
- Runtime bridge routes Termux, Ubuntu, Android, and service-manager operations
  through explicit targets.
- service-manager owns long-running lifecycle, status, logs, repair, and restart.
- No second primary proot/Ubuntu terminal runtime is introduced.
- Build passes with `./gradlew :app:assembleDebug`.
- Unit tests pass with `./gradlew :app:testDebugUnitTest`.
- End-to-end smoke checks cover `AI朋友 Help`, `返回主菜单`, `/termux`,
  `/ubuntu`, `/service-manager health`, and `/service-manager status`.

## Related Document

The detailed migration map is maintained in
[`operit-migration-map.md`](operit-migration-map.md). It is the source of truth
for module status, forbidden direct copies, required adapters, Worker ownership,
and follow-up migration batches.
