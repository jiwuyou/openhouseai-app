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

- Android App：入口、权限引导、状态展示、安装引导、原生桌面壳和可视化控制；默认入口可设为桌面、某个桌面 App 或上次退出页。
- Termux：Android 宿主层、终端底座、Ubuntu 启停和救援控制面。
- Ubuntu in Termux：pi、pi-web、Codex、Claude Code、CloudCLI、MCP、agent 和开发工具链的主要运行环境。
- service-manager：安装完成后的控制平面，负责服务启动、停止、状态和日志。
- pi：默认主 agent 和插件体系。
- pi-web：默认主 UI 和本地 Web 工作台，默认入口是 `http://127.0.0.1:30141/`。
- pi 插件目录：`/root/.pi/extensions` 和 `/root/.pi/agent/extensions`。
- Codex、Claude Code、CloudCLI：核心 AI 编程和远程交互能力。

Operit 是 Android 侧完整可选构建能力，不是默认核心运行时。`withOperit` 包含完整 Operit feature/module 和宿主桥接，`withoutOperit` 不依赖、不暴露 Operit；两个 flavor 的包名都保持 `com.termux`。OpenCode、Reasonix、Hermes 等仍作为可选外部或后置能力保留文档参考。

cc-switch 随 APK 内置固定 arm64 payload，作为 pi-agent 可调用的 provider
配置执行器。它只用于模型配置、检测和切换，不注册 service-manager，不作为
长期后台服务，也不替代 pi-agent 对用户的解释和引导。

## 文档结构

随 APK 内置的用户和 AI 参考文档位于：

```text
app/src/main/assets/openhouse/docs-public/
```

这套内置文档是公开文档仓库的随包快照：

```text
https://github.com/jiwuyou/openhouse-docs
```

发布 APK 前，应从 `/root/openhouse-docs/docs` 同步到
`app/src/main/assets/openhouse/docs-public/`。只更新文档时，用户可以拉取
`openhouse-docs` 并运行 `scripts/sync-runtime-docs.sh`，不需要等待新 APK。

仓库级架构和集成文档位于：

```text
docs/
```

推荐从以下文档开始：

- 用户入口：`app/src/main/assets/openhouse/docs-public/START_HERE.md`
- 产品场景：`app/src/main/assets/openhouse/docs-public/PRODUCT_OVERVIEW.md`
- 架构设计：`docs/ARCHITECTURE.md`
- 原生桌面壳：`docs/OPENHOUSE_DESKTOP.md`
- 运行分层：`docs/RUNTIME_LAYERING.md`
- AI 参考：`app/src/main/assets/openhouse/docs-public/AI_AGENT_REFERENCE.md`
- pi agent 与插件体系：`docs/PI_AGENT_PLUGIN_SYSTEM.md`
- Operit 可选 Android 构建说明：`docs/OPERIT_ROLE.md`

## 安装链路与运行期

首次安装链路负责准备 Termux、Ubuntu、Node.js、Codex、Claude Code、CloudCLI、service-manager、OpenHouse Connect、pi、pi-web 和默认 pi 扩展。安装链路完成后，service-manager 才成为运行期控制平面。

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动，不通过 `npm install -g` 安装 pi-web tgz。Codex、Claude Code、CloudCLI、Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络，所以整个首次安装不能被描述成完全离线流程。

这意味着：

- OpenHouse 的默认入口策略可以设为桌面、某个桌面 App 或上次退出页；旧菜单/首页功能保留为桌面里的“菜单总览”App。
- 桌面是原生横向分页稀疏槽位网格，支持编辑模式、跨屏拖动、拖到末尾新建屏、改名和轻量图标 override；空位不会被自动压紧。
- 桌面隐藏顶部控制栏；App 页显示 `左侧栏 / 桌面 / 当前 App 名 / 刷新 / 收起 / 右侧控制栏`，并可收起为可拖动、吸附、持久化位置的白黑渐变悬浮球。
- 桌面只保存入口元数据和布局，不预创建多个 WebView，也不要求多个 WebView 常驻。
- 安装过程中不要求用户先配置默认模型和 API Key。
- 安装完成后再由用户按需登录 Codex、Claude Code 或 CloudCLI。
- pi-web 是默认主入口，由 service-manager 管理 `pi-agent` 和 `pi-web`。
- 默认搜索插件是 `multi-platform-search.ts`，旧 pi-web 会话可能需要新建会话才能刷新工具列表。
- 后台服务应通过 service-manager 明确启动、停止、查看状态和读取日志。

## 长期方向

OpenHouseAI 的长期方向是一个人机友好的移动端 AI 平台：

- 日常协作
- 人和 AI 都能使用各种 app/工具的智能操作系统
- 人生管理系统
- 个人知识库
- 人机编程平台

核心 agent 和开发工具链长期应位于 Ubuntu 层。Termux 保持为 Android 宿主和救援层。当前主线中，核心 agent 是 pi，主 UI 是 pi-web，AionUi 是独立本地页面能力。Operit 作为 Android 侧可选完整 flavor 存在，不是 Ubuntu payload，也不替代 OpenHouse/Pi/AionUi 默认运行时。

Termux 侧救援助手是后置预留能力，本轮不默认安装、不常驻、不进入首次安装关键路径。
