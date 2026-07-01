# OpenHouseAI 概览

OpenHouseAI 是一个基于 Termux fork 的移动端 AI 运行平台。它的目标不是只提供一个终端或聊天窗口，而是让用户和 AI 能在同一个手机环境里共同使用软件、终端、文件、服务、模型工具和插件能力。

## 产品定位

OpenHouseAI 面向人机协作：

- 用户通过 Android 界面、终端和服务控制页直接操作。
- AI 通过工具、API、MCP、文档和终端能力协助执行。
- 同一能力应尽量同时具备用户入口和 AI 入口。
- 用户始终应能查看状态、理解行为、接管任务和关闭后台运行。

## 默认核心

当前默认核心包括：

- Android App：入口、权限引导、状态展示、安装引导和可视化控制。
- Termux：Android 宿主层、终端底座、Ubuntu 启停和救援控制面。
- Ubuntu in Termux：Codex、Claude Code、CloudCLI、MCP、agent 和开发工具链的主要运行环境。
- service-manager：安装完成后的控制平面，负责服务启动、停止、状态和日志。
- SmallPhone：主交互入口和人机共用界面。
- Operit：模型配置、诊断、插件 UI、小白辅助和工具箱入口。
- Codex、Claude Code、CloudCLI：核心 AI 编程和远程交互能力。

OpenCode、Reasonix、Hermes 等退役外部工具不是 APK 默认核心能力。它们只作为可选外部工具保留文档参考。

## 文档结构

随 APK 内置的用户和 AI 参考文档位于：

```text
app/src/main/assets/openhouse/docs-public/
```

仓库级架构和集成文档位于：

```text
docs/
```

推荐从以下文档开始：

- 用户入口：`app/src/main/assets/openhouse/docs-public/START_HERE.md`
- 产品场景：`app/src/main/assets/openhouse/docs-public/PRODUCT_OVERVIEW.md`
- 架构设计：`docs/ARCHITECTURE.md`
- 运行分层：`docs/RUNTIME_LAYERING.md`
- AI 参考：`app/src/main/assets/openhouse/docs-public/AI_AGENT_REFERENCE.md`
- Operit 定位：`docs/OPERIT_ROLE.md`

## 安装链路与运行期

首次安装链路负责准备 Termux、Ubuntu、Node.js、Codex、Claude Code、CloudCLI、service-manager、OpenHouse Connect 和 SmallPhone runtime。安装链路完成后，service-manager 才成为运行期控制平面。

这意味着：

- 安装过程中不要求用户先配置默认模型和 API Key。
- 安装完成后再由用户按需登录 Codex、Claude Code 或 CloudCLI。
- 后台服务应通过 service-manager 明确启动、停止、查看状态和读取日志。

## 长期方向

OpenHouseAI 的长期方向是一个人机友好的移动端 AI 平台：

- 日常协作
- 人和 AI 都能使用各种 app/工具的智能操作系统
- 人生管理系统
- 个人知识库
- 人机编程平台

核心 agent 和开发工具链长期应位于 Ubuntu 层。Termux 保持为 Android 宿主和救援层。Operit 长期保留为配置、诊断、插件 UI 和小白入口，但不承担核心 agent 内核。
