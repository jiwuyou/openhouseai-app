# 终端配置和选择规则

OpenHouseAI 同时保留 Termux 终端和 Ubuntu 终端。它们不是同一种能力的简单别名。

普通用户一般不需要直接使用终端。终端是高级入口，适合排障、开发、手动检查和 AI 执行细粒度任务。新手教学应先让用户理解菜单、`pi-agent`、`cc/codex` 和运行控制；终端详细教学可以后置。

## 总规则

1. 用户项目、开发命令、AI CLI 默认进入 Ubuntu。
2. Termux 负责 Android 宿主、Ubuntu 启停、底座修复和 Android 贴身能力。
3. 不要把长期后台服务直接挂在任意终端里；长期服务应由 service-manager 管。
4. 执行命令前先确认当前终端是哪一层。
5. 如果 Ubuntu 不可用，不要在 Termux 里硬跑 Ubuntu 侧开发任务；先修 Ubuntu。

## Termux 终端

Termux 是 Android App 沙箱内的 Linux-like 宿主层。它不是 `/root`，也不是 Ubuntu；它的 home 通常是：

```text
/data/data/com.termux/files/home
```

Termux prefix 通常是：

```text
/data/data/com.termux/files/usr
```

OpenHouse 菜单/终端页面中可进入 Termux 或 Ubuntu 终端，具体入口名称以当前 App 为准。安装完成后，打开 Termux 终端可能会自动进入 Ubuntu，因此排障时必须先用命令识别当前层。

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

Ubuntu 内的 home 通常是：

```text
/root
```

常用 Ubuntu 内路径：

```text
/root/openhouse/docs
/root/openhouseai-docs/official
/root/projects
```

pi-agent/pi-web 默认在 Termux native 层运行。默认 pi 数据和插件目录不在 Ubuntu `/root` 下，而是在 `/data/data/com.termux/files/home/.pi`。

Ubuntu rootfs 在 Termux 文件系统中的常见真实位置是：

```text
/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu
```

知道 rootfs 位置是为了排障和备份判断。普通命令应进入 Ubuntu 后执行，不要直接在 Termux 外层改 rootfs 内部文件。

适合执行：

- Codex CLI。
- Claude Code。
- CloudCLI / ClaudeCodeUI 相关命令。
- pi 和 pi-web 的本地开发、启动脚本和插件检查。
- Node.js、Python、Rust、Git 项目开发。
- MCP server。
- pi-agent 首次配置能力和后续自研 agent 能力。
- 用户知识库、项目、脚本和长期任务的核心逻辑。

常用检查：

```bash
~/bin/openhouseai-env-probe 2>/dev/null || ~/bin/smallphoneai-env-probe 2>/dev/null || true
pwd
echo "$HOME"
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

## 跨层调用

### Termux -> Ubuntu

如果当前在 Termux 外层，需要调用 Ubuntu 侧命令，使用：

```bash
proot-distro login ubuntu -- <command>
```

常见示例：

```bash
proot-distro login ubuntu -- bash -lc 'pwd; node -v'
proot-distro login ubuntu -- bash -lc 'cd /root/projects && git status --short'
proot-distro login ubuntu -- bash -lc 'cd /root && service-manager status 2>/dev/null || true'
```

规则：

- 已经在 Ubuntu 时，不要嵌套调用 `proot-distro login ubuntu -- ...`。
- 单次检查可以从 Termux 调 Ubuntu。
- 高频命令、长任务、开发服务应在 Ubuntu 内或通过 service-manager 常驻执行。
- 如果 `proot-distro login ubuntu -- true` 失败，先修 Ubuntu，不要继续跑开发命令。

### Ubuntu -> Termux

如果当前在 Ubuntu 内，需要 Termux 外层能力，不要默认直接调用外层 Termux shell。Ubuntu 是 proot 内层，直接执行外层 Termux binary 或改 Termux prefix 可能绕过预期环境。

优先方案：

1. 让用户或维护流程进入 OpenHouse 的 Termux 终端入口执行底座命令。
2. 使用 Android App 维护入口执行安装、启动、修复、日志收集。
3. 使用 OpenHouse 已提供的 bridge、bootstrap 脚本或 service-manager 动作。
4. 先查 `/root/openhouse/docs`、`SERVICE_MANAGER.md`、`RECOVERY.md` 和 bootstrap 文档，确认项目支持的跨层调用方式。

可用于识别 Termux 外层的真实路径：

```text
/data/data/com.termux/files/usr/bin
/data/data/com.termux/files/home
```

风险提示：

- 不要在 Ubuntu 内盲目运行 `/data/data/com.termux/files/usr/bin/pkg`。
- 不要在 Ubuntu 内直接修改 `/data/data/com.termux/files/usr`。
- 不要从 Ubuntu 里直接重置或删除 Termux home/prefix。
- proot、apt、Android 权限、底座修复优先回 Termux 外层处理。

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
| “当前在 Termux，执行 Ubuntu 命令” | Termux -> Ubuntu | `proot-distro login ubuntu -- bash -lc 'pwd; node -v'` |
| “当前在 Ubuntu，需要 Termux 底座能力” | 回 Termux / bridge / App 维护入口 | 先查文档和受支持脚本，不要在 Ubuntu 内盲目改 Termux prefix |

## 快速判断当前终端层

AI 执行命令前可以先跑：

```bash
pwd
echo "$HOME"
cat /etc/os-release 2>/dev/null || true
command -v proot-distro 2>/dev/null || true
test -d /data/data/com.termux/files/home && echo has-termux-home || true
test -d /root && echo has-root-dir || true
```

判断规则：

- `$HOME` 是 `/root`，且 `/etc/os-release` 显示 Ubuntu：当前在 Ubuntu 终端。
- `$HOME` 是 `/data/data/com.termux/files/home`：当前在 Termux 外层。
- 能看到 `proot-distro` 并且 home 是 Termux 路径：适合管理或修复 Ubuntu。
- 能看到 `/root/openhouse/docs`：通常说明当前已经进入 Ubuntu。
- 只看到 `/data/data/com.termux/files/...`：通常说明还在 Termux 外层。

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
