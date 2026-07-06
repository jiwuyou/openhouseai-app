# OpenHouse 能力地图

OpenHouse 是一个让用户和 AI 共同使用软件的平台。它不是固定的聊天窗口，也不是单一终端，而是把 Android App、Termux、Ubuntu、网页工作台、模型配置、服务控制、文件和网络能力组织成一个可管理的个人计算环境。

本文给用户和 AI 一起阅读。用户可以用它了解 OpenHouse 能做什么；AI 可以用它判断应该调用哪个能力、查哪份文档、在哪一层执行命令。

## 一句话定位

OpenHouse 帮用户在手机上构建自己的 AI 工作环境。用户可以直接操作，也可以用自然语言提出目标，让 AI 帮忙搜索工具、安装服务、配置模型、注册入口、运行测试，并逐步把开源项目改造成自己的工作台。

## 核心能力总览

| 能力 | 给用户的价值 | 给 AI 的用法 | 主要文档 |
| --- | --- | --- | --- |
| 原生桌面 | 用横向分页稀疏槽位桌面打开 OpenHouse App，在编辑模式中跨屏拖动、改名、改图标和设置默认入口 | 根据组件 registry 判断入口类型、服务引用、槽位布局 override 和状态面板动作 | `OPENHOUSE_DESKTOP.md` |
| 首次配置 | 配好 pi-web 后继续完成 OpenHouse 初始化 | 读取文档，检查后置工具，迁移模型，测通 CloudCLI/Claude Code | `OPENHOUSE_FIRST_CONFIGURATION.md`, `AI_TOOL_POSTINSTALL.md` |
| 工作台选择 | 不被锁定到单一 UI | 推荐 Claude Code、Codex、Hermes Web 或其它开源项目 | `WORKBENCH_OPTIONS.md` |
| Termux | Android 侧 Linux 底座和救援入口 | 修复 Ubuntu、调用 Android 侧桥接能力 | `TERMINAL_PROFILES.md`, `RECOVERY.md` |
| Ubuntu in Termux | 主要开发环境 | 运行 Node/Python/uv/npm/git/pi，以及后置安装完成后的 Codex/Claude Code | `ENVIRONMENT.md` |
| service-manager | 统一管理后台服务 | 启动、停止、修复、查日志和注册服务 | `SERVICE_MANAGER.md` |
| CloudCLI / Claude Code | 后置编程工作台和网页入口 | 通过脚本安装，配置模型、测试连通、交给用户继续使用 | `AI_TOOL_POSTINSTALL.md`, `CLOUDCLI_CLAUDE_CODE.md` |
| Codex | 后置命令行编程能力 | 通过脚本安装，在 Ubuntu 项目目录中执行开发任务 | `AI_TOOL_POSTINSTALL.md`, `MODEL_API_SETUP.md` |
| Hermes Web | 可选高级工作台 | 在独立 uv 环境安装并注册服务 | `HERMES_SETUP.md` |
| pi / pi-web | 首次配置助手和插件运行入口 | 读取文档、使用搜索插件、迁移配置 | `PI_AGENT_PLUGIN_SYSTEM.md` |
| 内置浏览器/WebView | 打开本地网页服务 | 检查本地 UI、端口和 WebView 差异 | `BROWSER_AND_WEBVIEW.md` |
| 文件系统/中转站 | 让文件在用户和 AI 之间流转 | 读取、整理、生成文件并返回路径提示 | `FILE_TRANSFER_STAGING.md` |
| Shizuku | 可选 Android 增强授权 | 在授权后辅助真机操作，先确认风险 | `ANDROID_CONTROL_SHIZUKU.md` |
| 网络检索/GitHub | 获取最新安装和配置方法 | 查官方文档、README、issue、release 和镜像 | `GITHUB_NETWORK_MIRRORS.md` |
| 排障和恢复 | 出错时知道查哪里 | 分层诊断，先修 service-manager，再修上层 | `RECOVERY.md` |

## OpenHouse 可以做什么

OpenHouse 的能力应该被理解为一个开放平台，而不是固定功能列表。

用户可以用它：

- 让 AI 帮自己配置开发环境和大模型。
- 在手机上运行 pi-agent，并按需后置安装 Claude Code、Codex、CloudCLI、Hermes 等工具。
- 用 service-manager 启动、停止、修复和托管后台服务。
- 打开内置浏览器查看本地 Web 工作台。
- 让 AI 使用 Termux 或 Ubuntu 命令完成文件、项目、构建和排障任务。
- 把资料、项目、笔记和下载文件组织成知识库。
- 构建个人工作台、写作系统、项目管理系统、知识库、人机编程平台或自动化流程。
- 让 AI 搜索合适的开源项目，安装到 Ubuntu，注册成服务，再接入桌面或侧边栏。
- 在获得 Shizuku 等授权后，探索更强的 Android 侧操作能力。

AI 可以协助：

- 阅读 OpenHouse 内置文档，形成当前系统地图。
- 判断应该使用 Termux、Ubuntu、service-manager、浏览器还是文件能力。
- 搜索外部文档，确认最新安装、配置和 API 格式。
- 迁移 pi-web 模型配置到 CloudCLI / Claude Code。
- 测通服务和模型。
- 为用户创建项目、整理文档、运行测试、修复错误。
- 在用户确认后执行高风险操作。

## 不承诺的能力

文档中出现的能力分为三类：

1. 已内置并默认安装：例如 Termux、Ubuntu、Node、service-manager、pi-agent、pi-web、openhouse-connect、SmallPhone 兼容服务。
2. 后置安装或后续扩展：例如 Codex CLI、Claude Code、CloudCLI、Hermes Web、文件中转站增强、其它开源工作台。
3. 授权后可能增强：例如 Shizuku 相关真机操控能力。

AI 不应把可选能力说成已经可用，也不应承诺所有 Android 设备都支持同样的系统控制能力。执行前先检查状态、读取文档、必要时联网检索。

## 文档路径

优先使用：

```text
/root/openhouse/docs
```

兼容旧路径：

```text
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

如果 `/root/openhouse/docs` 不存在，使用兼容路径继续工作，并提醒用户或维护者同步文档路径。
