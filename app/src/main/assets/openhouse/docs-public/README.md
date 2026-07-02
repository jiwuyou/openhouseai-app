# OpenHouseAI 文档

OpenHouseAI 是一个面向人机协作的移动端 AI 运行平台。它基于 Android、Termux 和 Ubuntu，让用户和 AI 可以共同使用终端、文件、服务、模型工具和插件能力。

这组文档随 APK 内置，供用户、维护者和 AI agent 共同参考。

安装完成后，官方文档目录统一为：

```text
/root/openhouse/docs
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

`/root/openhouse/docs` 是面向用户和 AI 的推荐入口；`/root/openhouseai-docs/official` 是兼容旧路径。pi-web 默认提示词和 pi-agent 的首次配置任务应引用这些稳定路径，例如：

- `/root/openhouse/docs/START_HERE.md`
- `/root/openhouse/docs/PRODUCT_OVERVIEW.md`
- `/root/openhouse/docs/CAPABILITIES_MAP.md`
- `/root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md`
- `/root/openhouse/docs/WORKBENCH_OPTIONS.md`
- `/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md`
- `/root/openhouse/docs/SERVICE_MANAGER.md`
- `/root/openhouse/docs/RECOVERY.md`
- `/root/openhouse/docs/AI_AGENT_REFERENCE.md`

## 三层路径速览

OpenHouse 同时有 Android App、Termux 外层和 Ubuntu in Termux。看到路径时先判断它属于哪一层：

| 层级 | 常见路径 | 说明 |
| --- | --- | --- |
| Ubuntu 内 | `/root`, `/root/openhouse/docs`, `/root/openhouseai-docs/official`, `/root/projects` | 主要工作区。Claude Code、Codex、CloudCLI、pi、pi-web、用户项目和大多数开发命令默认在这里运行。 |
| Termux 文件系统 | `/data/data/com.termux/files/home`, `/data/data/com.termux/files/usr` | Android 侧 Termux shell 的真实 home 和 prefix。用于 bootstrap、Termux 包、proot-distro、Ubuntu 启停和底座修复。 |
| Ubuntu rootfs 真实位置 | `/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu` | Termux 文件系统中保存 Ubuntu 根文件系统的位置。排障时可知道数据在哪里，但不要直接改 rootfs 内部文件，优先通过 `proot-distro login ubuntu` 进入 Ubuntu。 |
| Android App 入口 | OpenHouse 菜单/终端页面中的 Termux 或 Ubuntu 终端入口 | 具体入口名称以当前 App 为准。Termux 终端是 Android 侧底座终端；Ubuntu 终端是主要开发工作区。 |

`Termux 终端` 不是 `/root`。它是 Android App 沙箱内的 Termux shell，通常对应 `/data/data/com.termux/files/home`。安装完成后打开终端可能会自动进入 Ubuntu，所以排障时必须先识别当前层，再执行命令。

## 文档分类

### 1. 产品能做什么

- `PRODUCT_OVERVIEW.md`：面向用户的产品定位和核心原则。
- `CAPABILITIES_MAP.md`：完整能力地图，说明 Termux、Ubuntu、服务、浏览器、文件、模型、Shizuku 和网络检索。
- `USER_SCENARIOS.md`：日常协作、智能操作系统、人生管理、知识库和人机编程等使用场景。
- `WORKBENCH_OPTIONS.md`：主工作台选择说明。Claude Code、Codex、Hermes Web 或其它开源项目都可以成为用户自己的工作台。
- `START_HERE.md`：首次阅读入口。

### 2. 运行环境和配置

- `ENVIRONMENT.md`：Android、Termux、Ubuntu、路径和默认安装范围。
- `MODEL_API_SETUP.md`：Codex、Claude Code 和 CloudCLI 的登录/API 配置。
- `OPENHOUSE_FIRST_CONFIGURATION.md`：pi-web 模型可用后，由 pi-agent 完成首次 OpenHouse 配置的流程。
- `CLOUDCLI_CLAUDE_CODE.md`：让 pi-agent 配置并测通 CloudCLI 中的 Claude Code。
- `HERMES_SETUP.md`：Hermes 可选高级安装和 service-manager 注册说明。
- `PI_AGENT_PLUGIN_SYSTEM.md`：pi、pi-web、插件目录和默认搜索插件。
- `GITHUB_NETWORK_MIRRORS.md`：GitHub、镜像、代理、备用源和网络检索策略。
- `OPTIONAL_EXTERNAL_TOOLS.md`：退役外部工具的可选外部安装参考。

架构设计文档位于仓库级 `docs/` 目录，主要入口是 `docs/ARCHITECTURE.md`、`docs/RUNTIME_LAYERING.md` 和 `docs/PI_AGENT_PLUGIN_SYSTEM.md`。

### 3. AI 可以参考的操作手册

- `AI_AGENT_REFERENCE.md`：AI agent 的默认操作规则和安全边界。
- `TERMINAL_PROFILES.md`：Termux 终端、Ubuntu 终端和自定义终端的分工。
- `SERVICE_MANAGER.md`：安装完成后的服务控制平面说明。
- `BROWSER_AND_WEBVIEW.md`：内置浏览器、本地页面、WebView 差异和调试建议。
- `ANDROID_CONTROL_SHIZUKU.md`：Shizuku 可选授权后的 Android 侧增强能力和边界。
- `FILE_TRANSFER_STAGING.md`：文件中转站、共享路径和让 AI 处理文件的方式。
- `CLAUDE_CODE_HANDOFF.md`：完成首次配置后，交给 Claude Code 时应复制的文档阅读提示。
- `RECOVERY.md`：Termux、Ubuntu 和运行栈的分层恢复规则。

## 默认核心

默认核心能力是 Termux、Termux 上的 Ubuntu、service-manager、pi、pi-web、Codex、Claude Code 和 CloudCLI。

`pi-agent` 是首次配置助手、文档索引员和配置迁移执行者，不是唯一主工作台。用户侧一级入口是 `pi-agent`，与 `SmallPhone`、`cc/codex` 同级；pi-web 是 `pi-agent` 背后的本地页面运行时。service-manager 负责管理 `pi-agent` 和 `pi-web`。默认 pi 插件目录是 `/root/.pi/extensions` 和 `/root/.pi/agent/extensions`，默认搜索插件是 `multi-platform-search.ts`。

OpenHouse 的主工作台由用户决定。用户可以选择 Claude Code、Codex、Hermes Web，也可以让 AI 搜索、安装、注册和改造其它开源项目，把它变成自己的长期工作台。

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动，不通过 `npm install -g` 安装 pi-web tgz。Codex、Claude Code、CloudCLI、Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络；文档中不要把整个首次安装描述成完全离线或网络可选。

Operit、OpenCode、Reasonix 等退役或外部工具不是 APK 默认核心能力。Hermes 不进入 APK 默认核心 payload，但可以作为 pi-agent 默认新手提示词里的可选高级任务出现，必须引用 `/root/openhouse/docs/HERMES_SETUP.md` 并提示耗时较久；旧环境可回退到 `/root/openhouseai-docs/official/HERMES_SETUP.md`。
