# OpenHouse 概览

OpenHouse 是一个面向人机协作的移动端 AI 运行平台。它把 Android、Termux、Ubuntu、服务控制、本地 Web 工作台、文件、浏览器、模型工具和 AI 文档放在同一个可管理环境里，让用户和 AI 可以围绕同一套软件能力协作。

最高产品目标很简单：用户安装 APK 后，不需要理解 Termux、Ubuntu、Node、service-manager，也能顺利完成首次安装，并成功使用 Claude 或 Codex 收到回复。

## 这个产品要解决什么

OpenHouse 不是单纯聊天软件，也不是把终端塞进手机。它要提供一个人和 AI 都能使用的软件环境：

- 用户通过桌面、菜单、按钮、页面、日志和教学使用系统。
- AI 通过文档、插件、终端、API、service-manager 和脚本使用同一套能力。
- 用户可以随时查看、接管、暂停、修复或全部退出。
- AI 可以在授权范围内帮助配置模型、安装工具、管理服务、整理文件、检索资料、运行项目和构建工作台。

这个设计的核心是“同一能力，双入口”。人看到的是界面，AI 看到的是工具和文档，但两者操作的是同一个环境。

## 首次可用闭环

OpenHouse 的首次可用闭环不是“脚本执行完成”，而是：

1. APK 安装完成。
2. Termux / Ubuntu 基础环境可用。
3. `/root/openhouse/docs` 已同步。
4. pi-agent / pi-web 可打开。
5. service-manager 可用。
6. SmallPhone 兼容服务可用。
7. CloudCLI 或后置 AI 工具入口可用。
8. 用户能配置模型。
9. Claude 或 Codex 至少一个能真实发消息并收到回复。
10. 用户知道如何重新进入教学、修复问题或全部退出。

文档和实现都应围绕这个闭环组织。没有通过真实 Claude/Codex 回复测试，就不能认为产品已经彻底可用。

## 默认核心服务

App 在前台时，默认应保持这些能力运行：

- `service-manager`
- `smallphone`
- `pi-agent`
- `cloudcli`

`service-manager` 是安装完成后的运行控制平面。Android 侧负责轻量检测 service-manager；service-manager 负责检查和修复长期服务。新增长期服务也应该注册进 service-manager，而不是绕过它直接后台运行。

前台默认可用会牺牲一部分资源，但换来普通用户无需学习服务控制。真正省资源的入口是“停止运行栈”和“全部退出 OpenHouse”：停止运行栈会停止 OpenHouse 管理的长期服务和由 OpenHouse 拉起的 Termux/Ubuntu 长期进程，保留当前界面，并暂停本次 App 会话的自动保活；全部退出 OpenHouse 会在此基础上关闭 OpenHouse 界面，并请求关闭 Termux 前台服务和终端会话。两者都必须保留用户数据、模型配置、日志和安装产物。高级设置可以关闭自动保持控制中枢运行，适合明确想手动管理服务的用户。

## 入口定位

OpenHouse 默认入口可以设为桌面、某个桌面 App 或上次退出页。桌面替代默认菜单心智；旧首页/菜单功能保留为桌面里的“菜单总览”App。桌面是原生横向分页稀疏槽位网格，不是全 WebView 多窗口；图标默认只显示图标和名称，点击打开，长按进入编辑模式，可跨屏拖动、拖到末尾新建屏、改名、改图标、隐藏、重置、设为默认入口或进入状态/详情，空位不会自动压紧。桌面隐藏顶部控制栏；App WebView 页壳不重复显示 App 名，App 名只用于桌面、列表、服务、状态和无障碍。页壳保留侧栏、桌面、刷新、收起、控制和“用浏览器打开”，并可收起为可拖动、吸附并持久化位置的白黑渐变悬浮球；桌面只保存元数据和布局，不预创建多个 WebView。WebView 默认保留最近 2 个，可配置 0-5，按同 App/URL 复用和 LRU 清理，App 必须能从持久化状态恢复。

桌面、菜单总览 App 或侧边栏的一级服务入口至少包括：

| 入口 | 定位 |
| --- | --- |
| `SmallPhone` | 手机侧能力、基础入口和兼容服务。 |
| `pi-agent` | 首次配置助手、文档索引员和配置迁移执行者。 |
| `cc/codex` | CloudCLI / Claude Code / Codex 的统一入口。 |

`pi-agent` 不是唯一主工作台。它首先负责把系统配置好，让用户能开始使用 Claude 或 Codex。用户后续可以选择 Claude Code、Codex、Hermes Web、pi-web，或者让 AI 搜索和改造其它开源项目作为长期工作台。

`cc/codex` 是统一入口，不应在新手阶段把 CloudCLI、Claude Code、Codex 拆成多个需要理解的一级概念。

## 分层环境

OpenHouse 同时存在 Android App、Termux 外层和 Ubuntu in Termux：

| 层级 | 常见路径 | 用途 |
| --- | --- | --- |
| Android App | 桌面、菜单、教学、维护中心、WebView | 权限、入口、状态展示、显式控制。 |
| Termux 外层 | `/data/data/com.termux/files/home`, `/data/data/com.termux/files/usr` | 底座、bootstrap、Termux 包、proot-distro、Ubuntu 修复。 |
| Ubuntu 内 | `/root`, `/root/openhouse/docs`, `/root/projects` | 主要工作区、开发工具、Claude/Codex/CloudCLI。pi-agent/pi-web 默认在 Termux native 层运行。 |

普通用户首次使用不需要理解这些层级。AI 和高级排障文档必须写清楚层级，避免在 Ubuntu 内误改 Termux prefix，或在 Termux 外层误以为自己位于 `/root`。

## 教学策略

首次教学只讲最短使用闭环：

- 菜单在哪里。
- pi-agent 是配置助手。
- cc/codex 是主要 AI 工具入口。
- 核心服务会在前台自动运行。
- 一般不需要使用终端。
- 需要时可以单独打开终端教学。
- 可以通过“停止运行栈”停止 OpenHouse 服务，也可以通过“全部退出 OpenHouse”关闭 OpenHouse 界面并请求关闭 Termux 前台服务和终端会话。
- 高级设置可以关闭自动保持控制中枢运行；关闭后，App 前台不再自动拉起 service-manager。

终端教学必须单独入口，不进入首次教学。需要用户真实点击的教学动作，首次 20 秒内不允许跳过；不需要点击的步骤只显示“下一步”。

## 模型和工具配置

用户只需要提供这些信息：

- `base_url`
- `key` 或 `token`
- `model id`
- 协议类型

协议必须按目标工具判断，不能只按 provider 品牌判断。DeepSeek 等 provider 可能同一个密钥对应不同协议或 endpoint；迁移到 Claude Code、Codex、CloudCLI 时必须分别确认。

可选内置 `cc-switch` arm64 预编译二进制时，它的定位是模型配置工具箱和 provider 配置执行器。它不是长期服务，不替代 service-manager，不安装 Claude Code 本体，也不替代 pi-agent 的解释和引导职责。

## 文档入口

运行期推荐文档目录：

```text
/root/openhouse/docs
```

AI 应优先阅读：

- `ai-reference-index.md`
- `implementation-acceptance-checklist.md`
- `openhouse-install-flow.md`
- `openhouse-cn-network-retry.md`
- `first-use-tutorial.md`
- `pi-agent-first-use.md`
- `model-config-migration.md`
- `cloudcli-claude-code-setup.md`
- `codex-setup.md`
- `service-manager.md`
- `openhouse-runtime-policy.md`
- `openhouse-exit-all.md`
- `troubleshooting.md`

完整产品说明仍可参考同目录的大写文档：

- `PRODUCT_OVERVIEW.md`
- `CAPABILITIES_MAP.md`
- `USER_SCENARIOS.md`
- `WORKBENCH_OPTIONS.md`
- `AI_AGENT_REFERENCE.md`

## 安全底线

任何 UI、日志、诊断报告、截图、文档和聊天内容都不得输出完整 key/token。需要展示时只能脱敏，例如 `sk-****abcd`。自动诊断和复制日志前必须先脱敏，不能直接打印 service JSON 的 `env` 字段。
