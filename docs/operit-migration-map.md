# Operit Migration Map

This document maps Operit modules and behaviors into the SmallPhoneAI host. It
is the working contract for parallel migration. The product target is full
Operit capability migration with SmallPhoneAI remaining the host, package,
runtime owner, and main menu.

## Status Legend

- Migrated skeleton: integration structure exists, but full capability is not
  complete.
- Next migration: should be migrated next without changing host ownership.
- Adapt migration: can migrate only through an adapter or contract rewrite.
- Forbidden direct copy: must not be copied as-is into SmallPhoneAI.

## Migrated Skeleton

| Area | Current SmallPhoneAI Location | Status | Contract |
| --- | --- | --- | --- |
| Host entry | `OpenHouseHomeActivity` and `operit_strings.xml` | Migrated skeleton | `AI朋友 Help` opens the internal Operit surface with hosted/help extras while default launch stays unchanged. |
| Return navigation | `OperitAssistantActivity`, `activity_operit_assistant.xml`, and `operit_page_strings.xml` | Migrated skeleton | `返回主菜单` returns with `Activity.finish()` and does not start a new Home, kill the app, or stop managed services. |
| Feature initializer | `app/src/main/java/com/termux/app/operit/init/**` | Migrated skeleton | SmallPhoneAI can initialize a host-owned Operit skeleton; the original Operit `Application` is not loaded. |
| Runtime bridge | `app/src/main/java/com/termux/app/operit/runtime/**` | Migrated skeleton | Short command execution and service-manager reads go through explicit runtime targets. |
| Assistant facade | `app/src/main/java/com/termux/app/operit/core/**` | Migrated skeleton | UI calls a core facade instead of directly touching Termux or service-manager implementation details. |
| Hosted AI entry | `OperitAssistantActivity` and matching resources | Migrated skeleton | SmallPhoneAI can open an internal Operit AI surface. |
| Integration mainline | `docs/operit-integration-mainline.md` | Migrated skeleton | Defines full migration as feature migration with host unchanged. |

The migrated skeleton is not a temporary demo. It is the permanent integration
spine where later Operit chat, tool, UI, MCP, and native features must attach.

## Next Migration

| Area | Source Concept | Target Shape | Owner |
| --- | --- | --- | --- |
| Chat service | Operit chat runtime and message manager | Move behind Operit core facade; keep storage and initialization explicit. | Core |
| LLM providers | Operit model/provider code | Migrate provider registry without changing SmallPhoneAI host lifecycle. | Core |
| Tool registry | Operit `AIToolHandler` and tool registration | Register tools through the hosted feature initializer and runtime bridge. | Core |
| Android tools | Operit Android shell/device tools | Preserve Android device-control domain; do not mix with Termux runtime commands. | Runtime/Core |
| Termux command tools | Operit terminal command tools | Route through SmallPhoneAI Termux runtime target. | Runtime |
| Ubuntu command tools | Operit Linux/proot tools | Route through SmallPhoneAI Ubuntu runtime target. | Runtime |
| Service controls | Operit MCP/bridge/service starts | Represent long-running services as service-manager actions/specs. | MCP |
| Hosted UI | Operit main chat and settings UI | Migrate into SmallPhoneAI hosted mode; add return-to-host contract. | UI/Build |

## Adapt Migration

| Operit Area | Why It Needs Adaptation | Required Adapter |
| --- | --- | --- |
| `OperitApplication` | SmallPhoneAI already owns `TermuxApplication`. | `OperitFeatureInitializer` with idempotent, on-demand initialization. |
| Terminal provider APIs | Operit terminal assumes its own local runtime. | `SmallPhoneAI` runtime bridge for `ANDROID`, `TERMUX`, `UBUNTU`, and `SERVICE_MANAGER`. |
| MCPBridge process startup | Direct Android-launched persistent Node processes bypass service-manager. | service-manager service specs, status APIs, logs, and repair actions. |
| Operit paths | Operit defaults such as `/sdcard/Download/Operit` do not match SmallPhoneAI runtime ownership. | Path mapping under SmallPhoneAI/OpenHouse registries and Termux home. |
| Compose UI | SmallPhoneAI app is a Termux-derived Android project with existing Java/View code. | Build-system integration for Kotlin/Compose, or hosted wrapper pages during migration. |
| Native ML modules | Native ABI, APK size, startup, and dependency risks are high. | Per-module ABI and startup review before enabling. |
| Plugin package lifecycle | Operit plugin activation may assume local bridge ownership. | service-manager-managed lifecycle plus core registry adapter. |
| Permissions and providers | Manifest/provider authority collisions are likely. | Lead-owned serialized Manifest/Gradle merge with Reviewer gate. |

## Forbidden Direct Copy

These items must not be copied wholesale into SmallPhoneAI:

- Operit app shell, launcher, or second `Application`.
- Operit `:terminal` as the primary runtime.
- Operit bundled proot scripts or bundled Ubuntu rootfs as the primary runtime.
- Direct long-running MCP, bridge, or agent daemon starts from Android UI code.
- `/sdcard/Download/Operit` as the primary workspace.
- Bulk Manifest replacement.
- Bulk Gradle replacement.
- Provider authority or permission blocks without conflict review.
- Native modules such as `mnn`, `llama`, `quickjs`, speech, avatar, or workflow
  modules without per-module migration ownership and ABI review.

## Parallel Ownership

| Stream | Owned Files / Modules | May Not Modify | Acceptance |
| --- | --- | --- | --- |
| Host | `OpenHouseHomeActivity`, host entry labels, hosted-mode intent extras | Runtime, core, MCP, Gradle, Manifest except Lead-serialized edits | `AI朋友 Help` appears and opens hosted AI surface without changing default launch. |
| Core | `app/src/main/java/com/termux/app/operit/core/**`, `app/src/main/java/com/termux/app/operit/init/**` | Host Activity, runtime implementation, UI layouts, Gradle, Manifest | `OperitFeatureInitializer` exists; facade exposes stable assistant/tool contracts. |
| Runtime | `app/src/main/java/com/termux/app/operit/runtime/**` | UI, host menu, core policy, Gradle, Manifest | `ANDROID`, `TERMUX`, `UBUNTU`, and `SERVICE_MANAGER` targets are explicit and tested. |
| UI | Hosted Operit Activity, UI layouts, UI strings, host navigation bridge usage | Host drawer unless assigned, runtime implementation, core policy | Hosted page shows `返回主菜单` and uses core facade only. |
| MCP | MCP adapter, plugin registry mapping, service-manager specs/docs | Direct process starts, host UI, Gradle, Manifest without Lead | MCP services are represented through service-manager lifecycle. |
| Build/Manifest | `settings.gradle`, app Gradle files, `AndroidManifest.xml`, provider authority, permissions, ABI, Compose/Kotlin setup | Worker-owned business code unless explicitly assigned | Shared build and manifest changes pass build/test gates. |
| Docs | `docs/operit-integration-mainline.md`, `docs/operit-migration-map.md` | Code, resources, Manifest, Gradle, payload, bootstrap | Contracts reflect current architecture and unblock parallel migration. |

Lead serializes shared files. Reviewer gates each stream before integration.

## Acceptance Checklist

Core product checks:

- SmallPhoneAI stays the only APK host and keeps `com.termux`.
- SmallPhoneAI default start and first-run flow are unchanged.
- `AI朋友 Help` is visible from the SmallPhoneAI main menu or drawer.
- `AI朋友 Help` opens the hosted Operit AI surface.
- Hosted Operit UI includes `返回主菜单`.
- Returning to the main menu does not restart bootstrap, duplicate the main
  Activity, stop Termux services, or kill managed tasks.

Runtime checks:

- `/termux <command>` executes through the Termux runtime target.
- `/ubuntu <command>` executes through the Ubuntu runtime target.
- `/service-manager health` uses service-manager health APIs.
- `/service-manager status <serviceId>` uses service-manager status APIs.
- Long-running service start/stop/restart/logs are delegated to
  service-manager.

Build and test checks:

- `./gradlew :app:assembleDebug` succeeds.
- `./gradlew :app:testDebugUnitTest` succeeds.
- End-to-end smoke testing verifies `AI朋友 Help`, `返回主菜单`, Termux command,
  Ubuntu command, service-manager health, and service-manager status.

Regression checks:

- No new primary proot/Ubuntu runtime is introduced.
- No direct persistent daemon start is added outside service-manager.
- No direct dependency from Operit core to SmallPhoneAI Activity classes.
- No bulk Manifest, Gradle, provider authority, or permission replacement.
- No changes are made to bootstrap or payload flows unless assigned to the host
  runtime owner and reviewed separately.

## Migration Batches

Batch 1 establishes the permanent integration spine. The current skeleton covers
these entry points and contracts, but does not mean full Operit functionality is
complete:

- `AI朋友 Help`
- `返回主菜单`
- `OperitFeatureInitializer`
- runtime bridge
- assistant facade
- migration map

Batch 2 migrates Operit assistant behavior:

- chat state
- LLM provider registry
- tool registry
- Android tools
- Termux and Ubuntu command tools
- permission UX

Batch 3 migrates managed extension behavior:

- MCP registry
- plugin package activation
- bridge/service mapping to service-manager
- logs, repair, restart, and update actions

Batch 4 migrates advanced user surfaces:

- full hosted Operit UI
- settings
- workflow
- speech
- avatar
- native/local model surfaces

Batch 5 enables selected native modules:

- ABI-reviewed native libraries
- APK size review
- startup and memory review
- device compatibility checks

Each batch must keep the host unchanged and must pass Reviewer gate before Lead
integrates shared files.
