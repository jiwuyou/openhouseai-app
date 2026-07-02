# 从这里开始

OpenHouseAI 是一个装在手机里的人机协作平台。它不是单纯的聊天软件，也不是单纯的终端，而是让用户和 AI 在同一个环境里共同使用软件、终端、文件、服务和工具。

## 建议阅读顺序

安装完成后的官方文档目录是：

```text
/root/openhouse/docs
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

pi-web 默认提示词应优先引用 `/root/openhouse/docs` 下的稳定文档路径；`/root/openhouseai-docs/official` 是兼容旧路径。源码里的同名 Markdown 文件会被同步到运行期文档目录。

## 先认清当前在哪一层

OpenHouse 有三层常见路径，不要混用：

- Ubuntu 内路径：`/root`、`/root/openhouse/docs`、`/root/openhouseai-docs/official`、`/root/projects`。这是主要工作区，Claude Code、Codex、CloudCLI、pi、pi-web 和用户项目默认在这里。
- Termux 文件系统真实路径：`/data/data/com.termux/files/home` 和 `/data/data/com.termux/files/usr`。这是 Android 侧 Termux shell 的 home 和 prefix，负责底座、bootstrap、proot-distro 和 Ubuntu 修复。
- Ubuntu rootfs 在 Termux 中的真实位置：`/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu`。知道这个位置有助于排障，但普通操作不要直接改 rootfs 文件。

OpenHouse 菜单/终端页面中可进入 Termux 或 Ubuntu 终端，具体入口名称以当前 App 为准。Termux 终端不是 `/root`；它是 Android 侧 Termux shell。安装完成后，Termux 终端可能会自动进入 Ubuntu，因此 AI 和用户排障时应先判断当前层。

快速判断：

```bash
pwd
echo "$HOME"
cat /etc/os-release 2>/dev/null || true
command -v proot-distro 2>/dev/null || true
openhouseai-env-probe 2>/dev/null || smallphoneai-env-probe 2>/dev/null || true
```

如果 `pwd` 或 `$HOME` 是 `/root`，通常在 Ubuntu 内。如果 `$HOME` 是 `/data/data/com.termux/files/home`，通常在 Termux 外层。如果 `cat /etc/os-release` 显示 Ubuntu，则当前命令环境是 Ubuntu。

1. `PRODUCT_OVERVIEW.md`
   - 先了解这个产品是什么，以及为什么强调人和 AI 共同使用软件。
2. `CAPABILITIES_MAP.md`
   - 了解 OpenHouse 能使用哪些能力：Termux、Ubuntu、服务、模型、浏览器、文件、Shizuku 和网络检索。
3. `USER_SCENARIOS.md`
   - 了解日常协作、智能操作系统、人生管理、知识库和人机编程等场景。
4. `WORKBENCH_OPTIONS.md`
   - 了解为什么 pi-agent 不是唯一主工作台，以及 Claude Code、Codex、Hermes Web 和开源项目如何成为用户自己的工作台。
5. `ENVIRONMENT.md`
   - 确认当前 Android、Termux、Ubuntu 和默认安装范围。
6. `PI_AGENT_PLUGIN_SYSTEM.md`
   - 了解 pi、pi-web、插件目录和默认搜索插件。
7. `MODEL_API_SETUP.md`
   - 需要使用 Codex、Claude Code 或 CloudCLI 时，再配置登录或模型 API。
8. `OPENHOUSE_FIRST_CONFIGURATION.md`
   - pi-web 模型可用后，让 pi-agent 完成模型迁移、CloudCLI/Claude Code 配置和连通测试。
9. `CLOUDCLI_CLAUDE_CODE.md`
   - 需要让 pi-agent 配置 CloudCLI 中的 Claude Code 时阅读。
10. `HERMES_SETUP.md`
   - 需要安装 Hermes 时阅读。Hermes 是可选高级能力，安装和配置会花比较久。

## 给 AI agent 的入口

如果你是 AI agent，优先阅读：

1. `AI_AGENT_REFERENCE.md`
2. `TERMINAL_PROFILES.md`
3. `SERVICE_MANAGER.md`
4. `RECOVERY.md`

这些文档说明了默认终端选择、服务控制、故障诊断和禁止操作。

## 默认核心

默认核心能力包括 Termux、Ubuntu、service-manager、pi、pi-web、Codex、Claude Code 和 CloudCLI。

主菜单/侧边栏的一级服务入口至少包括：

- `SmallPhone`
- `pi-agent`
- `cc/codex`

`pi-agent` 和 `SmallPhone`、`cc/codex` 同级。`pi-agent` 是首次配置助手、文档索引员和配置迁移执行者。用户可以用它完成 OpenHouse 首次配置，也可以临时让它读取文档、使用插件和处理任务；pi-web 是 `pi-agent` 背后的本地页面运行时，由 service-manager 托管，默认本地地址是 `http://127.0.0.1:30141/`。

OpenHouse 的主工作台由用户自己选择。用户可以继续使用 Claude Code、Codex、Hermes Web，也可以让 AI 搜索、安装和改造其它开源项目，并注册到 service-manager 和侧边栏中。

`cc/codex` 是统一入口，用于 CloudCLI / Claude Code / Codex 相关页面和控制。不要把 CloudCLI、Claude Code、Codex 拆成多个一级入口，除非后续产品注册策略明确改变。

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动，不通过 `npm install -g` 安装 pi-web tgz。Codex、Claude Code、CloudCLI、Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络，所以首次安装不应被理解为完全离线流程。

Operit、OpenCode、Reasonix 等退役或外部工具不是 APK 默认核心能力。Hermes 不进入 APK 默认核心 payload，但可以作为 `pi-agent` 新建会话里的可选高级提示词出现，详见 `HERMES_SETUP.md` 和 `OPTIONAL_EXTERNAL_TOOLS.md`。

## pi-agent 首次配置和新手提示词

`pi-agent` 首次配置任务和新建会话默认提示词应引用这些稳定文档路径：

| 提示词 | 参考文档 | 目标 |
| --- | --- | --- |
| 首次 OpenHouse 配置 | `/root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md`, `/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md`, `/root/openhouse/docs/MODEL_API_SETUP.md` | 让 pi-agent 迁移 pi-web 模型配置，测通 CloudCLI 中的 Claude Code。 |
| 首次使用 | `/root/openhouse/docs/START_HERE.md`, `/root/openhouse/docs/CAPABILITIES_MAP.md`, `/root/openhouse/docs/AI_AGENT_REFERENCE.md` | 让用户理解 OpenHouse 能力、入口、文档和安全边界。 |
| 配置 Claude Code | `/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md`, `/root/openhouse/docs/MODEL_API_SETUP.md`, `/root/openhouse/docs/GITHUB_NETWORK_MIRRORS.md` | 按文档配置并测通 CloudCLI 中的 Claude Code；不确定时联网检索。 |
| 选择主工作台 | `/root/openhouse/docs/WORKBENCH_OPTIONS.md`, `/root/openhouse/docs/SERVICE_MANAGER.md` | 让用户选择 Claude Code、Codex、Hermes Web 或其它开源项目作为长期工作台。 |
| 安装和配置 Hermes | `/root/openhouse/docs/HERMES_SETUP.md`, `/root/openhouse/docs/OPTIONAL_EXTERNAL_TOOLS.md`, `/root/openhouse/docs/SERVICE_MANAGER.md` | 可选高级能力，耗时较久，使用独立 uv 环境。 |
| 熟悉 OpenHouse 整个系统 | `/root/openhouse/docs/PRODUCT_OVERVIEW.md`, `/root/openhouse/docs/SERVICE_MANAGER.md`, `/root/openhouse/docs/RECOVERY.md`, `/root/openhouse/docs/AI_AGENT_REFERENCE.md` | 理解系统入口、服务控制、修复和终端分层。 |

首次安装不要求配置默认模型或 API key。安装完成后，用户先在 pi-web 完成模型配置；检测到模型可用后，再点击首次 OpenHouse 配置入口，让 `pi-agent` 根据文档完成模型迁移和 CloudCLI/Claude Code 连通测试。
