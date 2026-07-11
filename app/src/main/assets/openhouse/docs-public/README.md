# OpenHouseAI 文档

OpenHouseAI 是一个面向人机协作的移动端 AI 运行平台。它基于 Android、Termux 和 Ubuntu，让用户和 AI 可以共同使用终端、文件、服务、模型工具和插件能力。

这组文档随 APK 内置，供用户、维护者和 AI agent 共同参考。公开源仓库是：

```text
https://github.com/jiwuyou/openhouse-docs
```

APK 内的 `openhouse/docs-public` 是这个公开仓库的快照。只更新文档时，用户可以直接更新公开仓库并同步到本机运行期路径，不需要等待新 APK。

## 文档事实源和本地落点

长期维护时，`openhouse-docs` 是公开文档的单一事实源。正式内容先改这个仓库的 `docs/`，APK 内置的 `openhouse/docs-public` 只是打包时同步出来的快照，不作为长期手工编辑入口。

在已安装设备上，文档的物理落点优先放在 Termux native home：

```text
/data/data/com.termux/files/home/openhouseai-docs/official
```

同步脚本会把 Ubuntu 内的稳定入口做成软链接：

```text
/root/openhouse/docs
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

因此 AI 和用户平时应优先读取 `/root/openhouse/docs`。排查底层路径或从 Termux 外层维护时，再看 `/data/data/com.termux/files/home/openhouseai-docs/official`。AI 自己产生的笔记不要写进官方文档目录，使用 `openhouseai-docs/agent-notes`。

安装完成后，官方文档目录统一为：

```text
/root/openhouse/docs
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

`/root/openhouse/docs` 是面向用户和 AI 的推荐入口；`/root/openhouseai-docs/official` 是兼容旧路径。pi-web 默认提示词和 pi-agent 的首次配置任务应引用这些稳定路径，例如：

- `/root/openhouse/docs/openhouse-overview.md`
- `/root/openhouse/docs/ai-reference-index.md`
- `/root/openhouse/docs/implementation-acceptance-checklist.md`
- `/root/openhouse/docs/START_HERE.md`
- `/root/openhouse/docs/PRODUCT_OVERVIEW.md`
- `/root/openhouse/docs/CAPABILITIES_MAP.md`
- `/root/openhouse/docs/PATHS_AND_PORTS.md`
- `/root/openhouse/docs/openhouse-install-flow.md`
- `/root/openhouse/docs/openhouse-cn-network-retry.md`
- `/root/openhouse/docs/first-use-tutorial.md`
- `/root/openhouse/docs/pi-agent-first-use.md`
- `/root/openhouse/docs/model-config-migration.md`
- `/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md`
- `/root/openhouse/docs/codex-setup.md`
- `/root/openhouse/docs/GITHUB_CONFIG_HELPER.md`
- `/root/openhouse/docs/SERVICE_MANAGER.md`
- `/root/openhouse/docs/CUSTOM_FRONTEND_AND_APPS.md`
- `/root/openhouse/docs/OPENHOUSE_DESKTOP.md`
- `/root/openhouse/docs/openhouse-runtime-policy.md`
- `/root/openhouse/docs/openhouse-exit-all.md`
- `/root/openhouse/docs/TROUBLESHOOTING.md`

## 不等 APK 更新文档

如果只是文档有更新，可以在手机运行环境中执行：

```bash
git clone https://github.com/jiwuyou/openhouse-docs.git /root/openhouse-docs 2>/dev/null || true
cd /root/openhouse-docs
git pull --ff-only
scripts/sync-runtime-docs.sh
```

同步后，`/root/openhouse/docs` 和兼容路径会指向新的公开文档内容。

## 三层路径速览

OpenHouse 同时有 Android App、Termux 外层和 Ubuntu in Termux。看到路径时先判断它属于哪一层：

| 层级 | 常见路径 | 说明 |
| --- | --- | --- |
| Ubuntu 内 | `/root`, `/root/openhouse/docs`, `/root/openhouseai-docs/official`, `/root/projects` | 主要工作区。用户项目、大多数开发命令，以及后置安装完成后的 Claude Code、Codex、CloudCLI 默认在这里运行。pi-agent/pi-web 默认在 Termux native 层运行。 |
| Termux 文件系统 | `/data/data/com.termux/files/home`, `/data/data/com.termux/files/usr` | Android 侧 Termux shell 的真实 home 和 prefix。用于 bootstrap、Termux 包、proot-distro、Ubuntu 启停和底座修复。 |
| Ubuntu rootfs 真实位置 | `/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu` | Termux 文件系统中保存 Ubuntu 根文件系统的位置。排障时可知道数据在哪里，但不要直接改 rootfs 内部文件，优先通过 `proot-distro login ubuntu` 进入 Ubuntu。 |
| Android App 入口 | OpenHouse 桌面、菜单总览 App、终端 App 中的 Termux 或 Ubuntu 终端入口 | 具体入口名称以当前 App 为准。Termux 终端是 Android 侧底座终端；Ubuntu 终端是主要开发工作区。 |

`Termux 终端` 不是 `/root`。它是 Android App 沙箱内的 Termux shell，通常对应 `/data/data/com.termux/files/home`。安装完成后打开终端可能会自动进入 Ubuntu，所以排障时必须先识别当前层，再执行命令。

## 文档分类

### 1. 产品能做什么

- `openhouse-overview.md`：给用户和 AI 的稳定小写概览，说明首次可用闭环、核心服务和文档入口。
- `PRODUCT_OVERVIEW.md`：面向用户的产品定位和核心原则。
- `CAPABILITIES_MAP.md`：完整能力地图，说明 Termux、Ubuntu、服务、浏览器、文件、模型、Shizuku 和网络检索。
- `USER_SCENARIOS.md`：日常协作、智能操作系统、人生管理、知识库和人机编程等使用场景。
- `WORKBENCH_OPTIONS.md`：主工作台选择说明。Claude Code、Codex、Hermes Web 或其它开源项目都可以成为用户自己的工作台。
- `OPENHOUSE_DESKTOP.md`：原生桌面、默认入口策略、菜单总览 App、横向分页稀疏槽位、跨屏拖动、编辑模式、改名/改图标、App 顶部栏、可拖动悬浮球和桌面 App 类型。
- `CUSTOM_FRONTEND_AND_APPS.md`：自定义前端 shell 和自定义 Web App 编程指南，包含可运行代码、service-manager 服务定义、组件 manifest 和 AI 更新流程。
- `START_HERE.md`：首次阅读入口。

### 2. 运行环境和配置

- `openhouse-install-flow.md`：首次安装状态机、阶段成功/失败条件、常规重试、强制重试和最终健康检查。
- `openhouse-cn-network-retry.md`：国内网络重试固定路径、镜像策略、payload fallback 和 sha256 校验。
- `first-use-tutorial.md`：首次教学脚本，明确界面、箭头、点击主体和 20 秒跳过规则；终端教学不进入首次教学。
- `ENVIRONMENT.md`：Android、Termux、Ubuntu、路径和默认安装范围。
- `PATHS_AND_PORTS.md`：正式路径和端口规范，说明稳定运行路径、端口分段、已用/保留端口和自定义 App 选端口规则。
- `MODEL_API_SETUP.md`：Codex、Claude Code 和 CloudCLI 的登录/API 配置。
- `model-config-migration.md`：pi-web 模型配置迁移到 Claude Code、Codex、CloudCLI 的规则。
- `pi-agent-first-use.md`：pi-agent 作为首次配置助手的目标、提示词和验收。
- `CLOUDCLI_CLAUDE_CODE.md`：CloudCLI / Claude Code 后置配置、端口、权限模式和实测标准；也是 pi-agent 配置并测通 CloudCLI 中 Claude Code 的主文档。
- `codex-setup.md`：Codex 后置配置和实测标准。
- `GITHUB_CONFIG_HELPER.md`：GitHub 本地配置助手，说明如何复用 GitHub CLI 官方 OAuth 流程完成 `gh` 登录、`git` credential helper 配置，以及让 Codex / Claude Code 后续直接调用 `git` 和 `gh`。
- `cc-switch.md`：cc-switch 作为 provider 配置执行器的定位和边界。
- `OPENHOUSE_FIRST_CONFIGURATION.md`：pi-web 模型可用后，由 pi-agent 完成首次 OpenHouse 配置的流程。
- `OPENHOUSE_HEALTH_SIGNOFF.md`：全面健康检查的一阶段/二阶段引导 AI 签名规则和二阶段复制提示词依据。
- `AI_TOOL_POSTINSTALL.md`：Codex、Claude Code、CloudCLI、Hermes 的后置安装脚本入口和检查规则。
- `HERMES_SETUP.md`：Hermes 可选高级安装和 service-manager 注册说明。
- `PI_AGENT_PLUGIN_SYSTEM.md`：pi、pi-web、插件目录和默认搜索插件。
- `GITHUB_NETWORK_MIRRORS.md`：GitHub、镜像、代理、备用源和网络检索策略。
- `OPTIONAL_EXTERNAL_TOOLS.md`：外部工具和可选构建能力的边界参考。

App 工程架构设计文档位于 `openhouseai-app/docs/`，主要入口是 `ARCHITECTURE.md`、`RUNTIME_LAYERING.md` 和 `PI_AGENT_PLUGIN_SYSTEM.md`。

### 3. AI 可以参考的操作手册

- `ai-reference-index.md`：AI 任务索引，说明遇到不同任务应先读哪份文档。
- `implementation-acceptance-checklist.md`：下一轮代码实现的唯一放行清单。
- `AI_AGENT_REFERENCE.md`：AI agent 的默认操作规则和安全边界。
- `terminal-guide.md`：独立终端教学，说明 Termux、Ubuntu、路径和跨层调用。
- `PATHS_AND_PORTS.md`：AI 创建或排障本地服务前必须参考的路径和端口规范。
- `TERMINAL_PROFILES.md`：Termux 终端、Ubuntu 终端和自定义终端的分工。
- `SERVICE_MANAGER.md`：service-manager 的操作手册，强调安装完成后的运行控制平面。
- `openhouse-runtime-policy.md`：App 前台自动保活和默认长期服务策略。
- `openhouse-exit-all.md`：停止运行栈和全部退出 OpenHouse 的停止范围、界面行为、保留范围和恢复行为。
- `openhouse-runtime-repair.md`：修复运行控制、注册表、端口健康和重复服务的规则。
- `GITHUB_CONFIG_HELPER.md`：给 AI 和维护者的 GitHub 授权配置手册，强调不需要 OAuth App、不接触 token、只执行白名单 `gh` 命令，并说明 scopes、权限边界、同 `$HOME` 限制和 APK 内置方式。
- `TROUBLESHOOTING.md`：排障入口，覆盖安装、运行、模型配置、安全日志和失败边界。
- `permissions.md`：后台、通知、存储、Shizuku、无障碍等权限和降级行为。
- `failure-boundaries.md`：自动修复、需用户确认、建议重置或重装的边界。
- `CUSTOM_FRONTEND_AND_APPS.md`：给 AI 和开发者的自定义前端/App 实作手册，说明如何把真实代码注册到桌面和 service-manager。
- `BROWSER_AND_WEBVIEW.md`：内置浏览器、本地页面、WebView 页壳、窗口保留策略、受控浏览器命令和调试建议。
- `ANDROID_CONTROL_SHIZUKU.md`：Shizuku 可选授权后的 Android 侧增强能力和边界。
- `FILE_TRANSFER_STAGING.md`：文件中转站、共享路径和让 AI 处理文件的方式。
- `CLAUDE_CODE_HANDOFF.md`：完成首次配置后，交给 Claude Code 时应复制的文档阅读提示。
- `RECOVERY.md`：Termux、Ubuntu 和运行栈的分层恢复规则。

## 默认核心

默认核心能力是 Termux、Termux 上的 Ubuntu、Node、OpenHouse 文档、service-manager、pi-agent、pi-web、openhouse-connect 和 SmallPhone 兼容服务。

Codex、Claude Code、CloudCLI 和 Hermes 不再是首次安装必经项。它们是后置 AI 工作能力，由 pi-agent 结合 `/root/openhouse/docs` 和 `/root/openhouse/scripts` 引导安装、配置、检查和修复。

`pi-agent` 是首次配置助手、文档索引员和配置迁移执行者，不是唯一主工作台。用户侧一级入口是 `pi-agent`，与 `SmallPhone`、`cc/codex` 同级；pi-web 是 `pi-agent` 背后的本地页面运行时。service-manager 负责管理 `pi-agent` 和 `pi-web`。默认 pi 插件目录是 `/data/data/com.termux/files/home/.pi/extensions` 和 `/data/data/com.termux/files/home/.pi/agent/extensions`，默认搜索插件是 `multi-platform-search.ts`。

OpenHouse 的主工作台由用户决定。用户可以选择 Claude Code、Codex、Hermes Web，也可以让 AI 搜索、安装、注册和改造其它开源项目，把它变成自己的长期工作台。

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动，不通过 `npm install -g` 安装 pi-web tgz。Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络；Codex、Claude Code、CloudCLI 和 Hermes 的网络安装放到 pi-agent 后置引导阶段。

Operit 是 Android 侧完整可选构建能力，不是 APK 默认核心运行时。`withOperit` 包含完整 Operit feature/module 和宿主桥接，`withoutOperit` 不依赖、不暴露 Operit；两个 flavor 的包名都保持 `com.termux`，不能共存，只能同签名且 `versionCode` 单调递增时互相升级或替换。Operit 不是 Ubuntu payload，也不替代 OpenHouse/Pi/AionUi。

OpenCode、Reasonix 等外部工具不是 APK 默认核心能力。Hermes 不进入 APK 默认核心 payload，但可以作为 pi-agent 默认新手提示词里的可选高级任务出现，必须引用 `/root/openhouse/docs/HERMES_SETUP.md` 并提示耗时较久；旧环境可回退到 `/root/openhouseai-docs/official/HERMES_SETUP.md`。
