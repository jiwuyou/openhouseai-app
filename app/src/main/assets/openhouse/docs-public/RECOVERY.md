# 分层恢复手册

本文档给 AI agent 使用。恢复目标是：保留用户数据，恢复最小可用能力，再恢复完整运行栈。

## 恢复原则

1. 先诊断，不直接重装。
2. 先恢复 service-manager，再恢复上层服务。
3. Ubuntu 坏了，用 Termux 修。
4. Termux 坏了，用 Android App 的维护/重建能力修。
5. 默认保留 `/data/data/com.termux/files/home`。
6. 默认保留用户项目、模型配置、API key、本地知识库和 agent 笔记。
7. 清数据、删除 home、重装 Ubuntu、重建 prefix 都需要用户确认。

## 关键路径

| 内容 | 路径 |
| --- | --- |
| Termux home | `/data/data/com.termux/files/home` |
| Termux prefix | `/data/data/com.termux/files/usr` |
| Bootstrap | `$HOME/.smallphoneai-bootstrap/bootstrap.sh` |
| 安装日志 | `$HOME/.maintainer-logs` |
| 运行日志 | `$HOME/.smallphoneai/logs` |
| OpenHouse 文档 | `$HOME/openhouseai-docs/official` |
| Agent 笔记 | `$HOME/openhouseai-docs/agent-notes` |
| OpenHouse service-manager 配置 | `$HOME/.config/openhouseai/service-manager/config.json` |
| Ubuntu rootfs | `$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu` |

## 快速分级

| 现象 | 优先判断 | 修复入口 |
| --- | --- | --- |
| pi-web、pi-agent 或 CloudCLI 不可访问 | service-manager 或服务未启动 | `bash bootstrap.sh start` 或 service-manager API |
| service-manager 不可访问 | 控制平面未启动或 token/config 问题 | `bash bootstrap.sh repair` |
| Ubuntu 命令失败 | proot/Ubuntu rootfs/apt 状态异常 | Termux 外层修 Ubuntu |
| Termux 命令缺失 | prefix 或 Termux 包状态异常 | Termux 修复包状态 |
| 终端打不开或 bash 缺失 | prefix 损坏 | App 侧重建基础环境，保留 home |
| App 闪退或白屏 | Android 层问题 | 读取 logcat，再决定是否修复运行栈 |

## 运行控制修复教学

面向用户的运行控制修复不要先展示命令和长日志。推荐固定说明模型：

1. 状态：当前控制中枢或服务处于什么状态。
2. 影响：这个问题会导致哪些入口不可用。
3. 推荐动作：用户现在应该点击启动、关闭、修复还是刷新。
4. 修复过程：用人话说明会检查什么，不承诺不会失败。
5. 结果：修复完成后告诉用户是否恢复，以及是否需要刷新页面。

例子：

```text
控制中枢未运行。
影响：pi-agent 和 cc/codex 可能无法启动或显示旧状态。
推荐动作：点击“修复运行控制”。
修复过程：系统会检查 service-manager 配置、重新注册服务，并启动必要服务。
结果：修复完成后请刷新页面。
```

只有用户点击查看详情、复制诊断信息，或 AI 正在排障时，才展开原始日志。

## 通用诊断

在 Termux 外层执行：

```bash
openhouseai-env-probe 2>/dev/null || smallphoneai-env-probe 2>/dev/null || true
cd "$HOME/.smallphoneai-bootstrap" && bash bootstrap.sh status
ls -la "$HOME/.maintainer-logs" "$HOME/.smallphoneai/logs" 2>/dev/null || true
tail -n 160 "$HOME/.maintainer-logs/manifest_full.log" 2>/dev/null || true
```

判断 Ubuntu 是否可用：

```bash
command -v proot-distro
proot-distro login ubuntu -- true
```

判断 service-manager 是否可用：

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
```

## 恢复 service-manager 和运行栈

如果 Termux 可用，但 service-manager、pi-agent 或 pi-web 不可用，先尝试启动：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh start
```

如果启动失败，执行运行栈修复：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh repair
```

完成后重新读取状态：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh status
```

期望最终 JSON 中 `ready` 为 `true`。如果只有某个服务失败，不要重装系统，先查看该服务日志。

## 恢复 Ubuntu

Ubuntu 问题通常表现为 `proot-distro login ubuntu -- true` 失败、apt/dpkg 半中断、Node/Codex/Claude Code 命令缺失。

先确认 Termux 的 proot-distro 可用：

```bash
command -v proot-distro
proot-distro list
```

如果 Ubuntu 可以进入但 apt/dpkg 半中断：

```bash
proot-distro login ubuntu -- bash -lc 'dpkg --configure -a'
proot-distro login ubuntu -- bash -lc 'apt --fix-broken install -y'
proot-distro login ubuntu -- bash -lc 'apt update'
```

如果 Ubuntu 可以进入但核心工具缺失，按缺失项执行最小阶段：

```bash
cd "$HOME/.smallphoneai-bootstrap"
bash bootstrap.sh ubuntu-packages
bash bootstrap.sh node
bash bootstrap.sh codex
bash bootstrap.sh claude-code
bash bootstrap.sh claude-code-ui
bash bootstrap.sh components
bash bootstrap.sh start
```

如果 Ubuntu 无法进入，不要直接重装。先收集：

```bash
proot-distro list
ls -la "$PREFIX/var/lib/proot-distro/installed-rootfs" 2>/dev/null || true
tail -n 160 "$HOME/.maintainer-logs/manifest_full.log" 2>/dev/null || true
```

只有在用户确认后，才可以执行重装或 reset 类操作。执行前必须说明会影响 Ubuntu 内工具和配置，并尽量备份可读数据。

## 恢复 Termux 包状态

如果 Termux shell 还能运行，先重建基础环境变量：

```bash
export PREFIX=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH="$PREFIX/bin:/system/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TMPDIR="$PREFIX/tmp"
```

修复 dpkg/apt：

```bash
dpkg --configure -a
apt --fix-broken install -y
apt update
apt install -y bash coreutils curl ca-certificates proot proot-distro tar gzip xz-utils
```

然后检查：

```bash
bash --version
proot-distro list
proot-distro login ubuntu -- true
```

如果 apt lock 明确来自已退出进程，才清理锁文件；清理前先确认没有 apt/dpkg 正在运行：

```bash
pgrep -af 'apt|dpkg' || true
```

清理锁属于高风险维护动作，执行前需要用户确认。

## prefix 损坏或 bash 缺失

如果 `$PREFIX/bin/bash` 不存在或动态库损坏，shell 内修复可能不可行。此时应使用 Android App 的维护/底座修复入口重新解压 Termux bootstrap。

规则：

- 优先重建 `/data/data/com.termux/files/usr`。
- 默认保留 `/data/data/com.termux/files/home`。
- 不要删除 home。
- 不要清除 App 数据。
- 重建后重新安装 Termux 基础包、proot-distro，再挂回或检查 Ubuntu rootfs。

如果必须清除 App 数据，必须先告诉用户这会删除 Termux、Ubuntu、项目、配置和日志，并取得明确确认。

## 后台完全关闭

用户要求“完全关闭”时，目标是 CPU 接近空闲，后台服务不再保留运行任务。

执行顺序：

1. 通过 service-manager 停止相关服务。
2. 确认 pi-web、CloudCLI、cc-connect、pi-agent、MCP 服务都停止。
3. 关闭 Android App 中打开的 pi-web WebView 页面。
4. 检查残留进程。
5. 只杀明确属于本产品且无法正常停止的进程。

不要用不加筛选的 `killall` 或大范围 `pkill`。

## 最后手段

以下是最后手段，不是常规修复：

- 重装 Ubuntu。
- 重建 Termux prefix。
- 清除 App 数据。
- 删除 home。

执行前必须：

1. 向用户说明影响范围。
2. 尽量备份可读目录。
3. 记录当前错误和已尝试步骤。
4. 得到用户明确确认。
