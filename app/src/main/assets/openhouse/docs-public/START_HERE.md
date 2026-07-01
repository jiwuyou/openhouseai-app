# 从这里开始

OpenHouseAI 是一个装在手机里的 AI 工作台。它不是单纯的聊天软件，也不是单纯的终端，而是让用户和 AI 在同一个环境里共同使用软件、终端、文件、服务和工具。

## 建议阅读顺序

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

## 给 AI agent 的入口

如果你是 AI agent，优先阅读：

1. `AI_AGENT_REFERENCE.md`
2. `TERMINAL_PROFILES.md`
3. `SERVICE_MANAGER.md`
4. `RECOVERY.md`

这些文档说明了默认终端选择、服务控制、故障诊断和禁止操作。

## 默认核心

默认核心能力包括 Termux、Ubuntu、service-manager、pi、pi-web、Codex、Claude Code 和 CloudCLI。

pi 是默认主 agent，pi-web 是默认主 UI。`pi-agent` 和 `pi-web` 由 service-manager 管理，默认本地入口是 `http://127.0.0.1:30141/`。

pi 和 pi-web 的默认项目包可以随 APK 提供，但 npm 依赖解析、缺失依赖安装和 registry 访问仍可能需要网络。首次安装不应被理解为完全离线流程。

Operit、OpenCode、Reasonix、Hermes 等退役或外部工具不是 APK 默认核心能力。只有用户明确要求这些外部工具时，才阅读历史说明或 `OPTIONAL_EXTERNAL_TOOLS.md`。
