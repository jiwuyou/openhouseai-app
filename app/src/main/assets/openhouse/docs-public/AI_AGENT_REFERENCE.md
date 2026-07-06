# AI Agent 操作参考

本文档给 AI agent 使用。目标是让 agent 在 OpenHouseAI / SmallPhoneAI 环境中安全判断运行层、选择终端、调用服务、排查问题和恢复系统。

## 基本定位

OpenHouseAI 是人和 AI 共用的软件平台。用户通过界面使用能力，AI 通过终端、API、插件和文档使用同一套能力。

运行层分工：

| 层级 | 角色 | AI 默认用途 |
| --- | --- | --- |
| Android App | 入口、权限、状态、显式开关 | 观察状态，请求用户确认，进入维护/控制页面 |
| Termux | Android 宿主、底座、救援层 | 修复 Termux/Ubuntu，调用 Android 桥，检查安装链路 |
| Ubuntu in Termux | 核心 Linux 工作区 | pi、pi-web、开发、后置 AI 工具、MCP、项目命令 |
| service-manager | 安装完成后的控制平面 | 管理后台服务的启动、停止、状态、日志和修复 |
| pi-agent / pi-web | 首次配置助手和背后的本地页面运行时 | 读取文档、迁移模型配置、调用插件、帮助用户理解系统 |

## 强制规则

1. 开发、AI CLI、项目构建、Node/Python/Rust 工具链默认使用 Ubuntu 终端。
2. Termux 终端只用于底座、Android 桥、Ubuntu 启停、安装引导和救援。
3. 后台服务必须优先通过 service-manager 管理，不要绕过它直接长期 `nohup` 或后台启动。
4. 后台能力必须可显式关闭。用户要求关闭时，要停止相关 service-manager 服务和 agent 子进程。
5. 不要默认清除 App 数据。
6. 不要默认删除 `/data/data/com.termux/files/home`。
7. 不要在没有备份和用户确认的情况下重装 Termux、Ubuntu 或核心运行栈。
8. 不要把 API key、token、cookie 写入仓库、APK 资源、公共文档、聊天内容、日志或截图。
9. 高风险操作必须先向用户确认，包括清数据、删除目录、重装系统、覆盖配置、停止大量服务、杀不明确的进程。
10. 遇到问题先诊断，再修复；先最小修复，再重启；最后才考虑重装。

注册新后台能力时，先写 service-manager `ServiceSpec`，再写 OpenHouseAI `components.d/*.json`
侧边栏入口。组件注册只允许描述 UI 入口和 service-manager 引用，不能包含 `command`、
`shell`、`script` 或 `args`。

OpenHouse 的“首页”应理解为默认入口策略，可设为桌面、某个桌面 App 或上次退出页。桌面替代默认菜单心智；旧首页/菜单功能保留为桌面里的“菜单总览”App。不要为了桌面化删除菜单总览，也不要把它和桌面路由写成同一个页面。

桌面 App 图标默认不显示状态角标。点击是打开；长按空白处或图标进入桌面编辑模式，可在稀疏槽位中拖动、跨屏拖动、拖到末尾新建屏、改名、改图标、隐藏、重置、设为默认入口或进入状态/详情；空位不应自动压紧。打开失败或 WebView 主帧加载失败时，应自动进入状态面板。桌面页隐藏顶部控制栏；App 页显示 `左侧栏 / 桌面 / 当前 App 名 / 刷新 / 收起 / 右侧控制栏`，且可收起为可拖动、吸附并持久化位置的白黑渐变悬浮球。桌面 App 的入口类型可以是 `webview`、`native-page`、`terminal`、`service-control` 或 `android-activity`。桌面壳是原生 UI，不是全 WebView 多窗口系统，不应预创建多个 WebView。

`pi-agent` 是首次配置助手、文档索引员和配置迁移执行者。用户侧一级入口名称是 `pi-agent`，与 `SmallPhone`、`cc/codex` 同级；pi-web 是 `pi-agent` 背后的本地页面运行时。不要把 `pi-agent` 写成唯一主工作台，也不要把 Operit 当作默认 agent、默认 UI 或默认插件体系。

Operit 是 Android 侧完整可选构建能力。只有 `withOperit` flavor 包含完整 Operit feature/module、宿主桥接和 Android 入口；`withoutOperit` 不依赖、不暴露 Operit。两个 flavor 的包名都保持 `com.termux`，不能共存，只能在同签名且 `versionCode` 单调递增时互相升级或替换。AI 不要把 Operit 当作 Ubuntu payload，也不要把它写成 OpenHouse/Pi/AionUi 的替代运行时。

新手教学和面向用户文案可以写“进入 pi-agent 完成首次配置”，不要把 pi-web 写成需要用户单独理解的一级服务。需要说明技术实现时，可以说 pi-web 由 service-manager 托管，默认本地地址是 `http://127.0.0.1:30141/`。

OpenHouse 的主工作台由用户选择。用户可能继续使用 Claude Code、Codex、Hermes Web，也可能要求 AI 搜索、安装和改造其它开源项目。AI 应根据用户目标推荐工作台，而不是默认把所有任务都拉回 `pi-agent`。

`cc/codex` 是 CloudCLI / Claude Code / Codex 的统一入口。除非组件注册策略改变，不要把这三者拆成多个一级菜单项。

Termux 侧救援助手是后置预留能力。本轮不安装、不常驻、不进入首次安装关键路径。

## pi 插件规则

默认 pi 运行目录：

```text
PI_CODING_AGENT_DIR=/root/.pi
```

默认扩展目录：

```text
/root/.pi/extensions
/root/.pi/agent/extensions
```

默认搜索插件：

```text
multi-platform-search.ts
```

默认工具名：

```text
multi_platform_search
web_search
search_web
search
```

旧 pi-web 会话可能不会自动刷新工具列表。安装或更新扩展后，如果工具不可见，先新建 pi-web 会话再判断。

## 运行环境判断

先判断自己在哪一层，再执行任务。

三层路径速记：

| 层 | 典型路径 | 用途 |
| --- | --- | --- |
| Ubuntu 内 | `/root`, `/root/openhouse/docs`, `/root/openhouseai-docs/official`, `/root/projects` | 开发、pi、用户项目，以及后置安装完成后的 AI CLI、CloudCLI、Claude Code。 |
| Termux 外层 | `/data/data/com.termux/files/home`, `/data/data/com.termux/files/usr` | bootstrap、Termux 包、proot-distro、Ubuntu 启停、底座修复。 |
| Ubuntu rootfs 真实路径 | `/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu` | Ubuntu 数据在 Termux 文件系统中的位置；排障时识别，不要默认直接修改。 |

OpenHouse 桌面、菜单总览 App 或终端 App 中可进入 Termux 或 Ubuntu 终端，具体入口名称以当前 App 为准。Termux 终端不是 `/root`，它是 Android 侧 Termux shell；安装完成后可能自动进入 Ubuntu，所以必须用命令确认当前层。

在 Termux 外层优先运行：

```bash
openhouseai-env-probe 2>/dev/null || smallphoneai-env-probe 2>/dev/null || true
```

在 Ubuntu 内优先运行：

```bash
~/bin/openhouseai-env-probe 2>/dev/null || ~/bin/smallphoneai-env-probe 2>/dev/null || true
cat /etc/os-release
```

如果不确定 Ubuntu 是否可用，在 Termux 外层检查：

```bash
command -v proot-distro
proot-distro login ubuntu -- true
```

通用快速判断：

```bash
pwd
echo "$HOME"
cat /etc/os-release 2>/dev/null || true
command -v proot-distro 2>/dev/null || true
test -d /data/data/com.termux/files/usr && echo termux-prefix-visible || true
test -d /root/openhouse/docs && echo openhouse-docs-visible || true
```

判断规则：

- `$HOME=/root` 且 `/etc/os-release` 显示 Ubuntu：当前在 Ubuntu 内。
- `$HOME=/data/data/com.termux/files/home`：当前在 Termux 外层。
- `proot-distro` 可用且当前 home 是 Termux 路径：可以管理 Ubuntu。
- `/root/openhouse/docs` 可见：通常说明已经在 Ubuntu 视角或 rootfs 视角。
- 不确定时先报告当前层，不要继续执行有副作用的命令。

## 跨层调用规则

### Termux -> Ubuntu

当前在 Termux 外层，需要执行 Ubuntu 命令时，使用：

```bash
proot-distro login ubuntu -- bash -lc 'pwd; node -v'
```

更多示例：

```bash
proot-distro login ubuntu -- bash -lc 'cd /root/projects && ls -la'
proot-distro login ubuntu -- bash -lc 'cd /root && cat /etc/os-release'
```

如果已经在 Ubuntu 内，不要嵌套调用 `proot-distro login ubuntu -- ...`。嵌套调用会增加复杂度，也容易让路径和环境判断变乱。

### Ubuntu -> Termux

当前在 Ubuntu 内，需要 Termux 外层能力时，不要假设能直接调用外层 Termux shell。Ubuntu 是 proot 内层；即使能看到 `/data/data/com.termux/files/usr/bin` 或 `/data/data/com.termux/files/home`，也不代表直接执行 Termux binary 或修改 Termux prefix 是安全的。

优先选择：

1. 通过 OpenHouse 的 Termux 终端入口执行底座命令。
2. 通过 Android App 维护入口执行启动、修复和日志收集。
3. 使用项目已经暴露的 bridge、bootstrap 脚本或 service-manager 动作。
4. 先查 `/root/openhouse/docs`、`SERVICE_MANAGER.md`、`RECOVERY.md` 和 bootstrap 文档，确认是否有受支持的跨层命令。

不要硬猜桥接命令。没有明确文档时，向用户说明需要回到 Termux 外层执行。

### 决策规则

- 开发、构建、测试、用户项目、Codex、Claude Code、CloudCLI、pi、pi-web：优先 Ubuntu。
- service-manager 管理的长期服务：优先 service-manager。
- proot-distro、Termux 包、Android 权限、安装日志、底座修复：优先 Termux 外层。
- 从 Termux 调 Ubuntu：使用 `proot-distro login ubuntu -- <command>`。
- 从 Ubuntu 需要 Termux 能力：先判断是否必须回外层；优先使用 bridge/App 维护入口；不要在 Ubuntu 内盲目改 Termux prefix。

## 默认终端选择

| 任务 | 默认终端 | 原因 |
| --- | --- | --- |
| 编程、构建、测试、运行项目 | Ubuntu | 工具链和用户项目在 Ubuntu 内 |
| Codex CLI、Claude Code、CloudCLI | Ubuntu | AI CLI 默认安装在 Ubuntu 内 |
| pi、pi-web、MCP server、agent server | Ubuntu | 应由 service-manager 管理为长期服务 |
| 检查 proot-distro、安装 Ubuntu | Termux | Ubuntu 不存在或不可用时仍需要修复入口 |
| Android intent、App 私有目录、wake lock、权限桥 | Termux / Android App | 这些能力贴近 Android 沙箱 |
| 修复 Termux prefix | Termux / Android App | 这是 Ubuntu 的下层底座 |
| 查看首装日志 | Termux | 日志位于 Termux home 下 |

详见 `TERMINAL_PROFILES.md`。

## 标准诊断顺序

遇到报错、卡住、白屏、无响应、无法回复、安装不过去时，按以下顺序执行。

1. 确认用户当前看到的页面、步骤、按钮和错误文案。
2. 确认当前前台组件或入口：主菜单、首次引导、维护中心、pi-web、终端。
3. 读取安装/维护日志：

```bash
tail -n 160 "$HOME/.maintainer-logs/manifest_full.log" 2>/dev/null || true
ls -la "$HOME/.maintainer-logs" 2>/dev/null || true
```

4. 读取机器可读运行状态：

```bash
cd "$HOME/.smallphoneai-bootstrap" && bash bootstrap.sh status
```

5. 检查 service-manager：

```bash
SM_CONFIG="$HOME/.config/openhouseai/service-manager/config.json"
SM_ADDR="$(sed -n 's/.*"\(listen_addr\|listenAddr\|base_url\|baseUrl\|url\)"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\2/p' "$SM_CONFIG" | head -n 1)"
case "$SM_ADDR" in
  http://0.0.0.0*) SM_URL="http://127.0.0.1${SM_ADDR#http://0.0.0.0}" ;;
  http://*|https://*) SM_URL="$SM_ADDR" ;;
  :*) SM_URL="http://127.0.0.1$SM_ADDR" ;;
  0.0.0.0:*) SM_URL="http://127.0.0.1:${SM_ADDR#0.0.0.0:}" ;;
  *) SM_URL="http://$SM_ADDR" ;;
esac
curl -fsS --max-time 2 "${SM_URL%/}/api/v1/health"
service-manager list 2>/dev/null || true
service-manager status 2>/dev/null || true
```

6. 检查核心端口：

```bash
curl -fsS --max-time 2 http://127.0.0.1:30141/ >/dev/null && echo pi-web-ok
```

7. 检查 Ubuntu 可用性：

```bash
proot-distro login ubuntu -- true
```

8. 如果是 Android App 闪退、白屏、无响应，再读取 Android logcat。
9. 根据诊断结果选择 `SERVICE_MANAGER.md` 或 `RECOVERY.md` 中的最小修复步骤。

## 首次安装阶段

首次安装的核心顺序是：

1. 准备 Termux 路径、配置和文档。
2. 安装 Termux 基础包。
3. 下载并安装 Ubuntu rootfs。
4. 同步 OpenHouseAI 文档。
5. 安装 Ubuntu 基础包。
6. 设置打开 Termux 后默认进入 Ubuntu。
7. 安装 Node.js 24 LTS。
8. 同步 `/root/openhouse/docs` 和 `/root/openhouse/scripts`。
9. 解包 pi-agent / pi-web。
10. 安装并配置 service-manager。
11. 注册并启动 pi-agent / pi-web。
12. 安装 openhouse-connect 和 SmallPhone 兼容服务。
13. 注册 openhouse-connect / SmallPhone 到 service-manager。
14. 同步默认 pi 扩展、service-manager 服务定义和 OpenHouseAI 组件注册。
15. 启动 OpenHouse 基础运行栈。

首次安装完成后，service-manager 成为运行期控制平面。首次安装时不要要求用户配置默认模型或 API key，也不要强制安装 Codex、Claude Code、CloudCLI 或 Hermes。

后置 AI 工具入口：

```text
/root/openhouse/scripts/install-codex.sh
/root/openhouse/scripts/install-claude-code.sh
/root/openhouse/scripts/install-cloudcli.sh
/root/openhouse/scripts/install-hermes.sh
/root/openhouse/scripts/check-ai-tools.sh
```

## pi-agent 首次会话任务

pi-agent 的新手提示词必须能直接引用安装后的稳定文档路径。官方文档目录是：

```text
/root/openhouse/docs
/root/openhouseai-docs/official
~/openhouseai-docs/official
```

默认提示词引用：

- 首次 OpenHouse 配置：读 `/root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md`、`/root/openhouse/docs/MODEL_API_SETUP.md`、`/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md`。
- 首次使用：读 `/root/openhouse/docs/START_HERE.md`、`/root/openhouse/docs/PRODUCT_OVERVIEW.md`、`/root/openhouse/docs/CAPABILITIES_MAP.md`、`/root/openhouse/docs/TERMINAL_PROFILES.md`。
- 配置 Claude Code：读 `/root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md`、`/root/openhouse/docs/MODEL_API_SETUP.md`、`/root/openhouse/docs/GITHUB_NETWORK_MIRRORS.md`。
- 安装和配置 Hermes：读 `/root/openhouse/docs/HERMES_SETUP.md`、`/root/openhouse/docs/OPTIONAL_EXTERNAL_TOOLS.md`、`/root/openhouse/docs/SERVICE_MANAGER.md`。
- 选择主工作台：读 `/root/openhouse/docs/WORKBENCH_OPTIONS.md`、`/root/openhouse/docs/SERVICE_MANAGER.md`、`/root/openhouse/docs/BROWSER_AND_WEBVIEW.md`。
- 熟悉 OpenHouse 整个系统：读 `/root/openhouse/docs/SERVICE_MANAGER.md`、`/root/openhouse/docs/RECOVERY.md`、`/root/openhouse/docs/AI_AGENT_REFERENCE.md`。

默认项目目录建议是 `/root`。这是 Ubuntu root 用户目录，不是 Android 系统根目录。执行文件修改前，应先确认目标路径和用户意图。

默认工具策略是全部开启。只有用户明确要求低风险、只读或关闭工具时，才切换到受限工具模式。

如果内置文档没有覆盖当前版本、provider、安装方式或开源项目状态，必须联网检索。优先查官方文档、GitHub README、release、issue 和示例配置；GitHub 访问慢或失败时阅读 `GITHUB_NETWORK_MIRRORS.md`。

## 安全确认门槛

以下操作必须先请求用户确认：

- 删除、清空或重建 `/data/data/com.termux/files/home`。
- 删除、清空或重建 `/data/data/com.termux/files/usr`。
- `proot-distro reset/remove ubuntu` 或等价重装 Ubuntu。
- 覆盖 `~/.bashrc`、`~/.profile`、`~/.config` 中已有用户配置。
- 停止 OpenHouse 之外的服务，或批量终止大量进程。
- 上传日志、截图、token、API key 或私有项目文件。
- 对用户项目执行不可逆 Git 操作。

## 处理结果报告

向用户报告时只说：

- 做了哪些检查。
- 发现的直接原因。
- 修改或执行了哪些命令。
- 当前状态是否恢复。
- 还剩什么风险。

不要把长日志原样贴给用户；只摘取关键错误行。
