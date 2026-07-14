# 从这里开始

OpenHouseAI 是一个装在手机里的人机协作平台。它不是单纯的聊天软件，也不是单纯的终端，而是让用户和 AI 在同一个环境里共同使用软件、终端、文件、服务和工具。

## 建议阅读顺序

安装完成后的官方文档目录是：

```text
/data/data/com.termux/files/home/openhouse/docs
/root/openhouse/docs
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

Termux native pi-web 默认引用 `/data/data/com.termux/files/home/openhouse/docs`；Ubuntu 工作台使用 `/root/openhouse/docs`。它们指向同一份文档，`/root/openhouseai-docs/official` 是兼容旧路径。

## 首次配置一键入口

APK 会把产品自有的标准 pi 提示词安装到：

```text
/data/data/com.termux/files/home/.pi/prompts
```

打开 OpenHouse 适配版 pi-web 后，点击页面内的“OpenHouse 首次配置”入口即可运行 `/openhouse-first-config`。Android 只负责打开 pi-web，不注入 prompt URL。也可以在任意 pi-web 新会话中手动运行：

```text
/openhouse-first-config
/openhouse-docs
/openhouse-second-ai-handoff
```

pi-web 适配分支只负责入口和模板名；完整提示词、文档地址和交接要求由最终 APK payload 安装。第一阶段与第二阶段指两个 Agent 应用或工作台，不是两个大模型；`pi-web` 可以作为 Agent identity，两个 Agent 可以使用相同模型。identity 应填写 Agent 名称而不是模型名称。

## 文档更新通道

APK 会内置一份离线可用的公开文档快照。公开文档源仓库是：

```text
https://github.com/jiwuyou/openhouse-docs
```

如果只是文档更新，用户和 AI agent 可以不等待新 APK，直接更新公开文档仓库并同步到运行期路径：

```bash
git clone https://github.com/jiwuyou/openhouse-docs.git /root/openhouse-docs 2>/dev/null || true
cd /root/openhouse-docs
git pull --ff-only
scripts/sync-runtime-docs.sh
```

这只刷新 `/root/openhouse/docs`、`/root/openhouseai-docs/official` 等文档路径，不会重装 APK，也不会删除用户数据。

## 如果你只想开始使用

普通用户首次使用优先阅读：

1. `openhouse-overview.md`
   - 用最短路径理解 OpenHouse 是什么、默认核心服务是什么、pi-agent 和 cc/codex 分别做什么。
2. `first-use-tutorial.md`
   - 了解首次教学会指向哪里、哪些动作需要用户点击、哪些只是说明。
3. `pi-agent-first-use.md`
   - 按内测默认线从 pi-web 配置并测通 AionUI，再由 AionUI 完成第二阶段复核与签名。
4. `model-config-migration.md`
   - 准备 `base_url`、`key/token`、`model id` 和协议类型。
5. `CLOUDCLI_CLAUDE_CODE.md` 或 `codex-setup.md`
   - 双签完成后，如果选择配置 Claude Code 或 Codex，再完成对应后置配置和实测。
6. `GITHUB_CONFIG_HELPER.md`
   - 需要让 Codex / Claude Code 管理 GitHub 仓库、PR、Actions、issue 或组织资源时，先完成本机 `gh` 授权和 `git` credential helper 配置。
7. `OPENHOUSE_DESKTOP.md`
   - 了解默认入口、桌面横向分页、编辑模式、拖动、改名、改图标和 App 页控制栏。
8. `CUSTOM_FRONTEND_AND_APPS.md`
   - 需要让 AI 帮你做一个自定义前端、桌面 shell 或本地 Web App 时阅读，里面有完整可运行代码样例。
9. `PATHS_AND_PORTS.md`
   - 需要确认安装路径、服务端口、端口冲突或自定义 App 选端口时阅读。

普通用户不需要先学习终端。终端教学在 `terminal-guide.md`，需要时再看。

## 如果你是 AI agent

AI agent 优先阅读：

1. `ai-reference-index.md`
2. `openhouse-overview.md`
3. `AI_AGENT_REFERENCE.md`
4. `TERMUX_UBUNTU_BRIDGE.md`
5. `SERVICE_MANAGER.md`
6. `TROUBLESHOOTING.md`
7. `PATHS_AND_PORTS.md`

如果你要实现或审查下一轮代码改动，还必须阅读：

1. `implementation-acceptance-checklist.md`
2. `openhouse-install-flow.md`
3. `openhouse-cn-network-retry.md`
4. `openhouse-runtime-policy.md`
5. `openhouse-exit-all.md`

## 先认清当前在哪一层

OpenHouse 有三层常见路径，不要混用：

- Ubuntu 内路径：`/root`、`/root/openhouse/docs`、`/root/openhouseai-docs/official`、`/root/projects`。这是主要工作区；用户项目，以及后置安装完成后的 Claude Code、Codex、CloudCLI 默认在这里使用。pi-agent 和 pi-web 默认在 Termux native 层作为长期服务运行。
- Termux 文件系统真实路径：`/data/data/com.termux/files/home` 和 `/data/data/com.termux/files/usr`。这是 Android 侧 Termux shell 的 home 和 prefix，负责底座、bootstrap、proot-distro 和 Ubuntu 修复。
- Ubuntu rootfs 在 Termux 中的真实位置：`/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu`。知道这个位置有助于排障，但普通操作不要直接改 rootfs 文件。

OpenHouse 桌面、菜单总览 App 或终端 App 中可进入 Termux 或 Ubuntu 终端，具体入口名称以当前 App 为准。Termux 终端不是 `/root`；它是 Android 侧 Termux shell。安装完成后，Termux 终端可能会自动进入 Ubuntu，因此 AI 和用户排障时应先判断当前层。

快速判断：

```bash
pwd
echo "$HOME"
cat /etc/os-release 2>/dev/null || true
command -v proot-distro 2>/dev/null || true
openhouseai-env-probe 2>/dev/null || smallphoneai-env-probe 2>/dev/null || true
```

如果 `pwd` 或 `$HOME` 是 `/root`，通常在 Ubuntu 内。如果 `$HOME` 是 `/data/data/com.termux/files/home`，通常在 Termux 外层。如果 `cat /etc/os-release` 显示 Ubuntu，则当前命令环境是 Ubuntu。

跨层调用速记：

```bash
# Termux native -> Ubuntu/proot
oh-ubuntu-root -- bash -lc 'whoami; echo "$HOME"'

# Ubuntu/proot -> Termux native
openhouse-termux status --json
openhouse-termux exec -- 'id; echo "$HOME"; echo "$PREFIX"'
```

不要在 Ubuntu 内直接执行 Termux prefix 里的 `pkg`、`npm` 或 `bash` 作为官方路径。详细边界见 `TERMUX_UBUNTU_BRIDGE.md`。

1. `openhouse-overview.md`
   - 给用户和 AI 的稳定小写概览，说明首次可用闭环和默认核心服务。
2. `PRODUCT_OVERVIEW.md`
   - 先了解这个产品是什么，以及为什么强调人和 AI 共同使用软件。
3. `CAPABILITIES_MAP.md`
   - 了解 OpenHouse 能使用哪些能力：Termux、Ubuntu、服务、模型、浏览器、文件、Shizuku 和网络检索。
4. `USER_SCENARIOS.md`
   - 了解日常协作、智能操作系统、人生管理、知识库和人机编程等场景。
5. `WORKBENCH_OPTIONS.md`
   - 了解为什么 pi-agent 不是唯一主工作台，以及 Claude Code、Codex、Hermes Web 和开源项目如何成为用户自己的工作台。
6. `first-use-tutorial.md`
   - 了解首次教学脚本，终端教学为什么后置。
7. `ENVIRONMENT.md`
   - 确认当前 Android、Termux、Ubuntu 和默认安装范围。
8. `PATHS_AND_PORTS.md`
   - 确认稳定运行路径、service-manager endpoint、已用端口和自定义 App 端口范围。
9. `PI_AGENT_PLUGIN_SYSTEM.md`
   - 了解 pi、pi-web、插件目录和默认搜索插件。
10. `AI_TOOL_POSTINSTALL.md`
   - 需要后置安装 Codex、Claude Code、CloudCLI 或 Hermes 时，先看这里的脚本入口。
11. `MODEL_API_SETUP.md`
   - 需要使用 Codex、Claude Code 或 CloudCLI 时，再配置登录或模型 API。
12. `GITHUB_CONFIG_HELPER.md`
   - 需要让 Codex / Claude Code 后续直接使用 `git` 和 `gh` 管理 GitHub 时阅读。
13. `OPENHOUSE_FIRST_CONFIGURATION.md`
   - 按内测默认线由 pi-web 检查系统、真实测通 AionUI，再由 AionUI 完成第二阶段接力。
14. `SECOND_AI_HANDOFF.md`
   - 了解 pi-web 与 AionUI 的默认接力，以及其它 Agent 的高级/备用接力方式。
15. `CLOUDCLI_CLAUDE_CODE.md`
   - 需要让 pi-agent 配置 CloudCLI 中的 Claude Code 时阅读。
16. `HERMES_SETUP.md`
   - 需要安装 Hermes 时阅读。Hermes 是可选高级能力，安装和配置会花比较久。

## 给 AI agent 的入口

如果你是 AI agent，优先阅读：

1. `ai-reference-index.md`
2. `AI_AGENT_REFERENCE.md`
3. `TERMUX_UBUNTU_BRIDGE.md`
4. `terminal-guide.md`
5. `SERVICE_MANAGER.md`
6. `TROUBLESHOOTING.md`

这些文档说明了默认终端选择、服务控制、故障诊断和禁止操作。

## 默认核心

默认核心能力包括 Termux、Ubuntu、Node、OpenHouse 文档、service-manager、pi-agent、pi-web、openhouse-connect 和 SmallPhone 兼容服务。

Codex、Claude Code、CloudCLI 和 Hermes 是后置 AI 工作能力。用户先进入 pi-agent 完成首次配置；pi-agent 再按 `/root/openhouse/scripts` 中的脚本和本文档引导安装这些能力。

OpenHouse 默认入口可以设为桌面、某个桌面 App 或上次退出页。桌面替代默认菜单心智；旧首页/菜单功能保留为桌面里的“菜单总览”App。桌面是原生横向分页稀疏槽位网格，图标默认只显示图标和名称；点击打开 App，长按空白处或图标进入编辑模式，可拖动、跨屏拖动、拖到末尾新建屏、改名、改图标、隐藏、重置、设为默认入口或进入状态/详情。桌面空位不会自动压紧。打开失败时会自动弹出状态面板。

桌面页不显示顶部控制栏。进入某个 App 后，外层页壳保留 `侧栏 / 桌面 / 刷新 / 收起 / 控制 / 用浏览器打开` 等必要动作，但不重复显示 App 名；App 名只用于桌面、列表、服务/状态和无障碍。控制栏可以收起为白色到黑色渐变的悬浮球。悬浮球可拖动、吸附左右边并保存位置，点击悬浮球可恢复控制栏。桌面只保存入口元数据和布局，不预创建多个 WebView，也不要求多个 WebView 常驻；WebView 默认最多保留最近 2 个，配置范围是 0-5，超出后按 LRU 清理，App 必须能从持久化状态恢复。

桌面或菜单/侧边栏的一级服务入口至少包括：

- `SmallPhone`
- `pi-agent`
- `cc/codex`

`pi-agent` 和 `SmallPhone`、`cc/codex` 同级。`pi-agent` 是可选的配置助手、文档索引员和配置迁移执行者。用户可以用它承载 OpenHouse 首次配置，也可以选择其它实际 AI；pi-web 是本地页面运行时和推荐入口，由 service-manager 托管，默认本地地址是 `http://127.0.0.1:30141/`。

OpenHouse 的主工作台由用户自己选择。用户可以继续使用 Claude Code、Codex、Hermes Web，也可以让 AI 搜索、安装和改造其它开源项目，并注册到 service-manager 和侧边栏中。

`cc/codex` 是统一入口，用于 CloudCLI / Claude Code / Codex 相关页面和控制。不要把 CloudCLI、Claude Code、Codex 拆成多个一级入口，除非后续产品注册策略明确改变。

pi-web 首装使用 APK 内置完整 runtime 包，只做解压、校验、注册和启动，不通过 `npm install -g` 安装 pi-web tgz。Node.js、Ubuntu 基础包和其它缺失依赖仍可能需要网络；Codex、Claude Code、CloudCLI 和 Hermes 的网络安装放到 pi-agent 后置引导阶段。

Operit 是 Android 侧完整可选构建能力，不是 APK 默认核心运行时。`withOperit` 包含完整 Operit feature/module 和宿主桥接，`withoutOperit` 不依赖、不暴露 Operit；两个 flavor 的包名都保持 `com.termux`，不能共存，只能同签名且 `versionCode` 单调递增时互相升级或替换。Operit 不是 Ubuntu payload，也不替代 OpenHouse/Pi/AionUi。

OpenCode、Reasonix 等外部工具不是 APK 默认核心能力。Hermes 不进入 APK 默认核心 payload，但可以作为 `pi-agent` 新建会话里的可选高级提示词出现，详见 `HERMES_SETUP.md` 和 `OPTIONAL_EXTERNAL_TOOLS.md`。

## 标准 pi 模板和新手提示词

当前会话中的 AI 通过标准 pi 模板工作；模板应引用这些稳定文档路径：

| 提示词 | 参考文档 | 目标 |
| --- | --- | --- |
| `/openhouse-first-config` | `OPENHOUSE_FIRST_CONFIGURATION.md`, `SECOND_AI_HANDOFF.md`, `OPENHOUSE_HEALTH_SIGNOFF.md` | 内测默认由 pi-web 第一阶段检查并测通 AionUI，再由 AionUI 第二阶段独立复核与签名。 |
| 首次使用 | `/root/openhouse/docs/START_HERE.md`, `/root/openhouse/docs/CAPABILITIES_MAP.md`, `/root/openhouse/docs/AI_AGENT_REFERENCE.md` | 让用户理解 OpenHouse 能力、入口、文档和安全边界。 |
| 后置安装 AI 工具 | `/root/openhouse/docs/AI_TOOL_POSTINSTALL.md`, `/root/openhouse/scripts/check-ai-tools.sh` | 检查并安装 Codex、Claude Code、CloudCLI、Hermes。 |
| 授权 GitHub 本地访问 | `/root/openhouse/docs/GITHUB_CONFIG_HELPER.md`, `/root/openhouse/docs/GITHUB_NETWORK_MIRRORS.md` | 复用 GitHub CLI 官方 OAuth 流程，配置 `gh auth login` 和 `gh auth setup-git`，让 Codex / Claude Code 后续直接调用 `git` 和 `gh`。 |
| 配置 Claude Code | `/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md`, `/root/openhouse/docs/MODEL_API_SETUP.md`, `/root/openhouse/docs/GITHUB_NETWORK_MIRRORS.md` | 按文档配置并测通 CloudCLI 中的 Claude Code；不确定时联网检索。 |
| 配置 Codex | `/root/openhouse/docs/codex-setup.md`, `/root/openhouse/docs/MODEL_API_SETUP.md` | 按文档配置并真实测通 Codex。 |
| 选择主工作台 | `/root/openhouse/docs/WORKBENCH_OPTIONS.md`, `/root/openhouse/docs/SERVICE_MANAGER.md` | 让用户选择 Claude Code、Codex、Hermes Web 或其它开源项目作为长期工作台。 |
| 安装和配置 Hermes | `/root/openhouse/docs/HERMES_SETUP.md`, `/root/openhouse/docs/OPTIONAL_EXTERNAL_TOOLS.md`, `/root/openhouse/docs/SERVICE_MANAGER.md` | 可选高级能力，耗时较久，使用独立 uv 环境。 |
| 熟悉 OpenHouse 整个系统 | `/root/openhouse/docs/PRODUCT_OVERVIEW.md`, `/root/openhouse/docs/SERVICE_MANAGER.md`, `/root/openhouse/docs/RECOVERY.md`, `/root/openhouse/docs/AI_AGENT_REFERENCE.md` | 理解系统入口、服务控制、修复和终端分层。 |
| 编写自定义前端或 App | `/root/openhouse/docs/CUSTOM_FRONTEND_AND_APPS.md`, `/root/openhouse/docs/PATHS_AND_PORTS.md`, `/root/openhouse/docs/SERVICE_MANAGER.md`, `/root/openhouse/docs/OPENHOUSE_DESKTOP.md` | 让 AI 生成真实代码，先按规范选择路径和端口，再注册组件和服务，并支持后续 AI 更新。 |

首次安装不要求配置默认模型或 API key。安装完成后的内测默认线是：pi-web 第一阶段检查系统，配置并真实测通 AionUI，再由 AionUI 第二阶段独立复核和签名。双签完成后提供配置 Claude Code、配置 Codex、创建小型 Web App、跳过四个非阻断选项；通用任选 Agent 路径保留为高级或备用。
