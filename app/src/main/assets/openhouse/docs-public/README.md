# OpenHouseAI 文档

OpenHouseAI 是一个面向人机协作的移动端 AI 运行平台。它基于 Android、Termux 和 Ubuntu，让用户和 AI 可以共同使用终端、文件、服务、模型工具和插件能力。

这组文档随 APK 内置，供用户、维护者和 AI agent 共同参考。

## 文档分类

### 1. 产品能做什么

- `PRODUCT_OVERVIEW.md`：面向用户的产品定位和核心原则。
- `USER_SCENARIOS.md`：日常协作、智能操作系统、人生管理、知识库和人机编程等使用场景。
- `START_HERE.md`：首次阅读入口。

### 2. 运行环境和配置

- `ENVIRONMENT.md`：Android、Termux、Ubuntu、路径和默认安装范围。
- `MODEL_API_SETUP.md`：Codex、Claude Code 和 CloudCLI 的登录/API 配置。
- `PI_AGENT_PLUGIN_SYSTEM.md`：pi、pi-web、插件目录和默认搜索插件。
- `OPTIONAL_EXTERNAL_TOOLS.md`：退役外部工具的可选外部安装参考。

架构设计文档位于仓库级 `docs/` 目录，主要入口是 `docs/ARCHITECTURE.md`、`docs/RUNTIME_LAYERING.md` 和 `docs/PI_AGENT_PLUGIN_SYSTEM.md`。

### 3. AI 可以参考的操作手册

- `AI_AGENT_REFERENCE.md`：AI agent 的默认操作规则和安全边界。
- `TERMINAL_PROFILES.md`：Termux 终端、Ubuntu 终端和自定义终端的分工。
- `SERVICE_MANAGER.md`：安装完成后的服务控制平面说明。
- `RECOVERY.md`：Termux、Ubuntu 和运行栈的分层恢复规则。

## 默认核心

默认核心能力是 Termux、Termux 上的 Ubuntu、service-manager、pi、pi-web、Codex、Claude Code 和 CloudCLI。

pi 是默认主 agent，pi-web 是默认主 UI。service-manager 负责管理 `pi-agent` 和 `pi-web`。默认 pi 插件目录是 `/root/.pi/extensions` 和 `/root/.pi/agent/extensions`，默认搜索插件是 `multi-platform-search.ts`。

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动，不通过 `npm install -g` 安装 pi-web tgz。Codex、Claude Code、CloudCLI、Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络；文档中不要把整个首次安装描述成完全离线或网络可选。

Operit、OpenCode、Reasonix、Hermes 等退役或外部工具不是 APK 默认核心能力；只有用户明确需要时，才参考历史说明或 `OPTIONAL_EXTERNAL_TOOLS.md`。
