# 从这里开始

OpenHouseAI 是一个装在手机里的人机协作平台。它不是单纯的聊天软件，也不是单纯的终端，而是让用户和 AI 在同一个环境里共同使用软件、终端、文件、服务和工具。

## 建议阅读顺序

安装完成后的官方文档目录是：

```text
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

pi-web 默认提示词应引用 `/root/openhouseai-docs/official` 下的稳定文档路径。源码里的同名 Markdown 文件会被同步到这个目录。

1. `PRODUCT_OVERVIEW.md`
   - 先了解这个产品是什么，以及为什么强调人和 AI 共同使用软件。
2. `USER_SCENARIOS.md`
   - 了解日常协作、智能操作系统、人生管理、知识库和人机编程等场景。
3. `ENVIRONMENT.md`
   - 确认当前 Android、Termux、Ubuntu 和默认安装范围。
4. `PI_AGENT_PLUGIN_SYSTEM.md`
   - 了解 pi、pi-web、插件目录和默认搜索插件。
5. `MODEL_API_SETUP.md`
   - 需要使用 Codex、Claude Code 或 CloudCLI 时，再配置登录或模型 API。
6. `CLOUDCLI_CLAUDE_CODE.md`
   - 需要让 pi-agent 配置 CloudCLI 中的 Claude Code 时阅读。
7. `HERMES_SETUP.md`
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

`pi-agent` 和 `SmallPhone`、`cc/codex` 同级。用户进入 `pi-agent` 后使用 pi 的对话、工具和项目能力；pi-web 是 `pi-agent` 背后的本地页面运行时，由 service-manager 托管，默认本地地址是 `http://127.0.0.1:30141/`。

`cc/codex` 是统一入口，用于 CloudCLI / Claude Code / Codex 相关页面和控制。不要把 CloudCLI、Claude Code、Codex 拆成多个一级入口，除非后续产品注册策略明确改变。

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动，不通过 `npm install -g` 安装 pi-web tgz。Codex、Claude Code、CloudCLI、Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络，所以首次安装不应被理解为完全离线流程。

Operit、OpenCode、Reasonix 等退役或外部工具不是 APK 默认核心能力。Hermes 不进入 APK 默认核心 payload，但可以作为 `pi-agent` 新建会话里的可选高级提示词出现，详见 `HERMES_SETUP.md` 和 `OPTIONAL_EXTERNAL_TOOLS.md`。

## pi-agent 新手提示词

`pi-agent` 新建会话时，默认新手提示词应引用这些稳定文档路径：

| 提示词 | 参考文档 | 目标 |
| --- | --- | --- |
| 首次使用 | `/root/openhouseai-docs/official/START_HERE.md`, `/root/openhouseai-docs/official/AI_AGENT_REFERENCE.md` | 让用户知道先进入 `pi-agent`，选择项目，再配置模型和新建会话。 |
| 配置 Claude Code | `/root/openhouseai-docs/official/CLOUDCLI_CLAUDE_CODE.md`, `/root/openhouseai-docs/official/MODEL_API_SETUP.md` | 让 pi-agent 按文档配置并测通 CloudCLI 中的 Claude Code。 |
| 安装和配置 Hermes | `/root/openhouseai-docs/official/HERMES_SETUP.md`, `/root/openhouseai-docs/official/OPTIONAL_EXTERNAL_TOOLS.md`, `/root/openhouseai-docs/official/SERVICE_MANAGER.md` | 可选高级能力，耗时较久，使用独立 uv 环境。 |
| 熟悉 OpenHouse 整个系统 | `/root/openhouseai-docs/official/PRODUCT_OVERVIEW.md`, `/root/openhouseai-docs/official/SERVICE_MANAGER.md`, `/root/openhouseai-docs/official/RECOVERY.md`, `/root/openhouseai-docs/official/AI_AGENT_REFERENCE.md` | 理解系统入口、服务控制、修复和终端分层。 |

首次安装不要求配置默认模型或 API key。安装完成后，用户可以在 `pi-agent` 中点击侧边栏，先选择项目目录，默认建议 `/root`，再点击下方模型入口完成模型配置。
