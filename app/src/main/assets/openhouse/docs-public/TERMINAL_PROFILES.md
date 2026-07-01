# 终端配置和选择规则

OpenHouseAI 同时保留 Termux 终端和 Ubuntu 终端。它们不是同一种能力的简单别名。

## 总规则

1. 用户项目、开发命令、AI CLI 默认进入 Ubuntu。
2. Termux 负责 Android 宿主、Ubuntu 启停、底座修复和 Android 贴身能力。
3. 不要把长期后台服务直接挂在任意终端里；长期服务应由 service-manager 管。
4. 执行命令前先确认当前终端是哪一层。
5. 如果 Ubuntu 不可用，不要在 Termux 里硬跑 Ubuntu 侧开发任务；先修 Ubuntu。

## Termux 终端

Termux 是 Android App 沙箱内的 Linux-like 宿主层。

适合执行：

- 检查 Termux prefix。
- 安装或修复 `proot-distro`。
- 安装或修复 Ubuntu rootfs。
- 读取首次安装日志。
- 调用 Android 桥、intent、App 私有目录、wake lock、权限相关能力。
- 执行 bootstrap 的安装、状态、启动、修复命令。
- 在 Ubuntu 不存在或损坏时做救援。

常用命令：

```bash
openhouseai-env-probe 2>/dev/null || smallphoneai-env-probe 2>/dev/null || true
cd "$HOME/.smallphoneai-bootstrap" && bash bootstrap.sh status
cd "$HOME/.smallphoneai-bootstrap" && bash bootstrap.sh start
cd "$HOME/.smallphoneai-bootstrap" && bash bootstrap.sh repair
proot-distro list
proot-distro login ubuntu -- true
```

不要默认在 Termux 外层运行：

- Codex CLI。
- Claude Code。
- CloudCLI。
- 用户项目构建。
- npm/pnpm 大型安装。
- 长期 agent 主循环。

## Ubuntu 终端

Ubuntu 运行在 Termux 的 proot-distro 内，是主要工作区。

适合执行：

- Codex CLI。
- Claude Code。
- CloudCLI / ClaudeCodeUI 相关命令。
- pi 和 pi-web 的本地开发、启动脚本和插件检查。
- Node.js、Python、Rust、Git 项目开发。
- MCP server。
- pi 主 agent 和后续自研 agent 能力。
- 用户知识库、项目、脚本和长期任务的核心逻辑。

常用检查：

```bash
~/bin/openhouseai-env-probe 2>/dev/null || ~/bin/smallphoneai-env-probe 2>/dev/null || true
cat /etc/os-release
command -v node
command -v codex
command -v claude
command -v cloudcli
```

从 Termux 外层执行一次 Ubuntu 命令：

```bash
proot-distro login ubuntu -- bash -lc 'pwd && cat /etc/os-release'
```

注意：频繁用 `proot-distro login ubuntu -- command` 执行大量短命令会增加开销。高频任务应放进 Ubuntu 内的常驻服务，并由 service-manager 管理。

## 自定义终端

自定义终端用于特殊环境，例如项目专用 shell、指定目录、指定环境变量、远程主机或容器入口。

规则：

- 必须声明它基于 Termux、Ubuntu 还是远程环境。
- 必须声明工作目录。
- 必须声明环境变量来源。
- 必须声明是否允许长期运行。
- 长期运行仍应注册到 service-manager，而不是只靠终端会话。

## 命令路由

| 用户意图 | 终端选择 | 示例 |
| --- | --- | --- |
| “运行测试/编译项目” | Ubuntu | `npm test`、`pytest`、`cargo test` |
| “打开 Claude Code/Codex” | Ubuntu | `claude`、`codex` |
| “检查安装完成没有” | Termux | `bash bootstrap.sh status` |
| “修复 Ubuntu” | Termux | `proot-distro login ubuntu -- true` 后最小修复 |
| “修复 Termux” | Termux / App | 修复 prefix、pkg、proot-distro |
| “启动 pi-web/pi-agent/CloudCLI” | service-manager | `bash bootstrap.sh start` 或 service-manager API |
| “关闭后台服务” | service-manager | 对服务执行 stop |
| “查看 App 闪退原因” | Android / Termux | logcat、App 日志、维护日志 |

## Termux 与 Ubuntu 的差异

Ubuntu 通过 proot-distro 运行。CPU 指令通常原生执行，但系统调用会经过 proot 转换。影响：

- 大量小文件读写会慢。
- 频繁启动短命令会慢。
- npm/pnpm、解压、文件扫描比普通 Linux 更容易卡。
- 长期服务比反复进出 proot 更稳定。

因此 agent 应优先：

- 在 Ubuntu 中保持服务常驻。
- 通过 service-manager 调用长期服务。
- 避免每个工具调用都新开一次 Ubuntu 登录。

## 高风险命令

以下命令或等价操作必须先确认：

```text
rm -rf /data/data/com.termux/files/home
rm -rf /data/data/com.termux/files/usr
proot-distro reset ubuntu
proot-distro remove ubuntu
pm clear com.termux
killall ...
pkill -f ...
```

如果用户只是要求修复，不代表允许清数据或重装。

## 输出要求

AI 执行终端任务后，应告诉用户：

- 使用了哪个终端。
- 为什么选择该终端。
- 执行了哪些关键命令。
- 命令是否成功。
- 后续是否需要用户确认。
