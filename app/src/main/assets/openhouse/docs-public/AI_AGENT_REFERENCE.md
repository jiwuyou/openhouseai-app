# AI Agent 操作参考

本文档给 AI agent 使用。目标是让 agent 在 OpenHouseAI / SmallPhoneAI 环境中安全判断运行层、选择终端、调用服务、排查问题和恢复系统。

## 基本定位

OpenHouseAI 是人和 AI 共用的软件平台。用户通过界面使用能力，AI 通过终端、API、插件和文档使用同一套能力。

运行层分工：

| 层级 | 角色 | AI 默认用途 |
| --- | --- | --- |
| Android App | 入口、权限、状态、显式开关 | 观察状态，请求用户确认，进入维护/控制页面 |
| Termux | Android 宿主、底座、救援层 | 修复 Termux/Ubuntu，调用 Android 桥，检查安装链路 |
| Ubuntu in Termux | 核心 Linux 工作区 | pi、pi-web、开发、Codex、Claude Code、CloudCLI、MCP、项目命令 |
| service-manager | 安装完成后的控制平面 | 管理后台服务的启动、停止、状态、日志和修复 |
| pi / pi-web | 默认主 agent 和默认主 UI | 调用插件、展示工具、承载用户和 AI 的主要工作台 |

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

默认主 agent 是 pi，默认主 UI 是 pi-web。不要把 Operit 当作默认 agent、默认 UI 或默认插件体系。

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
curl -fsS --max-time 2 http://127.0.0.1:20087/api/v1/health
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
8. 安装 Codex CLI。
9. 安装 Claude Code。
10. 安装 ClaudeCodeUI / CloudCLI。
11. 安装 service-manager、openhouse-connect、pi 和 pi-web。
12. 同步默认 pi 扩展、service-manager 服务定义和 OpenHouseAI 组件注册。
13. 启动 `pi-agent` 和 `pi-web`。

首次安装完成后，service-manager 成为运行期控制平面。首次安装时不要要求用户配置默认模型或 API key。

## 安全确认门槛

以下操作必须先请求用户确认：

- 删除、清空或重建 `/data/data/com.termux/files/home`。
- 删除、清空或重建 `/data/data/com.termux/files/usr`。
- `proot-distro reset/remove ubuntu` 或等价重装 Ubuntu。
- 覆盖 `~/.bashrc`、`~/.profile`、`~/.config` 中已有用户配置。
- 停止全部后台服务或杀掉大量进程。
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
