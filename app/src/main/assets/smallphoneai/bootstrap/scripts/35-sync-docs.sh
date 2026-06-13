#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

run_logged() {
  log "+ $*"
  "$@"
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

detect_smallphoneai_runtime() {
  if is_current_ubuntu; then
    printf 'ubuntu'
    return 0
  fi

  if [ -x "${PREFIX:-/data/data/com.termux/files/usr}/bin/smallphoneai-env-probe" ]; then
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/smallphoneai-env-probe" 2>/dev/null \
      | awk -F= '$1=="SMALLPHONEAI_RUNTIME"{print $2; found=1} END{if(!found) exit 1}' \
      && return 0
  fi

  if is_termux; then
    printf 'termux'
    return 0
  fi

  printf 'unknown'
}

run_environment_probe() {
  local probe="${PREFIX:-/data/data/com.termux/files/usr}/bin/smallphoneai-env-probe"
  if [ -x "$probe" ]; then
    log "正在执行环境探测命令：$probe"
    run_logged "$probe" || true
  else
    log "环境探测命令不存在，使用内置探测逻辑。"
  fi
  log "当前运行环境：$(detect_smallphoneai_runtime)"
}

run_ubuntu_logged() {
  if is_current_ubuntu; then
    run_logged "$@"
  else
    run_logged proot-distro login ubuntu -- "$@"
  fi
}

run_environment_probe

if ! is_current_ubuntu && { ! command -v proot-distro >/dev/null 2>&1 || ! proot-distro login ubuntu -- true >/dev/null 2>&1; }; then
  log "Ubuntu 不可用，请先运行：bash bootstrap.sh ubuntu"
  exit 2
fi

TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
if is_current_ubuntu; then
  TERMUX_HOME="/data/data/com.termux/files/home"
fi

DOC_ROOT="$TERMUX_HOME/smallphoneai-docs"
OFFICIAL_DOC_DIR="$DOC_ROOT/official"
AGENT_NOTES_DIR="$DOC_ROOT/agent-notes"

log "正在同步 SmallPhoneAI 文档到 $OFFICIAL_DOC_DIR"
mkdir -p "$OFFICIAL_DOC_DIR" "$AGENT_NOTES_DIR"

cat > "$OFFICIAL_DOC_DIR/START_HERE.md" <<'EOF'
# 从这里开始

SmallPhoneAI 文档分为两部分：

1. `ENVIRONMENT.md`：说明当前 Android、Termux、Ubuntu、路径和安装范围。
2. `MODEL_API_SETUP.md`：说明 Codex、Claude Code 和 Reasonix 如何登录，或如何配置大模型 API。
3. `RUNTIME_COMPONENTS.md`：说明 SmallPhone、cc-connect/openhouse-connect 与 service-manager 的安装、启动和状态入口。

建议顺序：
- 先读 `ENVIRONMENT.md`，确认当前运行在哪里。
- 如果要使用 Codex、Claude Code 或 Reasonix，再读 `MODEL_API_SETUP.md`。
EOF

cat > "$OFFICIAL_DOC_DIR/ENVIRONMENT.md" <<'EOF'
# 运行环境说明

SmallPhoneAI 运行在 Android 手机上，结构如下：

- Android 是宿主系统。
- Termux 提供终端环境、包管理、Ubuntu 启动、bridge 和恢复兜底。
- Ubuntu 通过 `proot-distro` 安装在 Termux 内，是主要运行域。
- OpenCode、Codex CLI、Claude Code、Reasonix 安装在 Ubuntu 内。
- service-manager 默认运行在 Ubuntu/proot 内，是本机运行控制面。
- cc-connect/openhouse-connect 默认运行在 Ubuntu/proot 内，提供 Agent bridge 和 Web client。
- SmallPhone 默认运行在 Ubuntu/proot 内，是产品应用栈。

## 安装范围

SmallPhoneAI bootstrap 负责安装、检查、注册和启动：

- Ubuntu proot
- OpenCode
- Codex CLI
- Claude Code
- Reasonix
- service-manager
- cc-connect/openhouse-connect
- SmallPhone

Node.js 不作为单独可见阶段。Codex、Claude Code、Reasonix 和 SmallPhone 相关阶段会在内部检测并按需安装或使用 Node.js。

## 阶段顺序

维护中心的一键阶段顺序是：

1. 准备 Termux 路径、配置和文档。
2. 安装 Termux 基础包。
3. 测速并选择 Ubuntu rootfs 镜像源，然后安装 Ubuntu rootfs。
4. 同步 SmallPhoneAI 文档。
5. 安装 Ubuntu 基础包。
6. 设置打开 Termux 后默认进入 Ubuntu。
7. 安装 OpenCode。
8. 安装 Codex CLI。
9. 安装 Claude Code。
10. 安装 Reasonix。
11. 进入 Ubuntu/proot，调用 service-manager、cc-connect/openhouse-connect、SmallPhone 子仓库自己的 `scripts/install.sh`、`scripts/check.sh`、`scripts/register-service.sh`。
12. 在 Ubuntu/proot 内启动 service-manager，并通过 `group:local-stack` 启动已注册服务。
13. 输出最终状态 JSON，供 App Shell 做健康判断。

默认进入 Ubuntu 必须在安装 OpenCode、Codex CLI、Claude Code 和 Reasonix 之前完成。

Ubuntu rootfs 安装不会使用代理。安装脚本会先测试内置的 Ubuntu cloud image 镜像源，选择当前可达且较快的 rootfs URL，再执行 `proot-distro install -n ubuntu <rootfs-url>`。如需指定源，可在执行前设置 `SMALLPHONEAI_UBUNTU_ROOTFS_URL`。

## 路径

- Termux 主目录：`/data/data/com.termux/files/home`
- 工作区：`/data/data/com.termux/files/home/workspace`
- 官方文档：`/data/data/com.termux/files/home/smallphoneai-docs/official`
- Agent 笔记：`/data/data/com.termux/files/home/smallphoneai-docs/agent-notes`
- 启动入口配置：`/data/data/com.termux/files/home/.smallphoneai`
- 运行组件仓库根目录：`~/smallphoneai-repos`

Ubuntu 中如果存在以下短路径，优先使用短路径：

- `~/smallphoneai-docs/official`
- `~/smallphoneai-docs/agent-notes`
- `~/smallphoneai-links/docs-path.txt`
- `~/smallphoneai-links/workspace-path.txt`

## 环境检测

每个安装阶段都会先检测当前终端环境。

预期探测命令：

- Termux：`smallphoneai-env-probe`
- Ubuntu：`~/bin/smallphoneai-env-probe`

## App Shell 状态接口

以下命令输出 JSON，不包含普通日志前后缀：

```bash
bash bootstrap.sh status
bash bootstrap.sh check
bash bootstrap.sh hooks
```
EOF

cat > "$OFFICIAL_DOC_DIR/RUNTIME_COMPONENTS.md" <<'EOF'
# SmallPhoneAI 运行组件

SmallPhoneAI bootstrap 只编排组件，不复制组件内部安装逻辑。默认组件是：

- `service-manager`
- `cc-connect` / `openhouse-connect`
- `SmallPhone`
- `smallphone-likegirl` control test

每个组件仓库需要提供可重复执行的入口：

```text
scripts/install.sh
scripts/check.sh
scripts/register-service.sh
```

## 命令

```bash
bash bootstrap.sh components
bash bootstrap.sh start
bash bootstrap.sh repair
bash bootstrap.sh status
```

`components` 会安装、检查并刷新 service-manager 注册。`start` 会确保 service-manager 可访问，请求启动 `group:local-stack`，然后输出状态 JSON。`repair` 会先重新执行组件入口，再启动并输出状态 JSON。完整 `install`/`full` 最后同样会输出状态 JSON。

`hooks` 和维护清单会把 `install`、`full`、`start`、`repair` 标记为 `reportsFinalHealth: true`。这些命令可以先输出进度日志；最终健康结果是 stdout 上最后一个 JSON 对象。

## 默认端口

状态和启动健康检查使用当前 SmallPhoneAI 运行端口：

| 组件 | Endpoint |
| --- | --- |
| SmallPhone frontend | `http://127.0.0.1:22082/` |
| SmallPhone core API | `http://127.0.0.1:22000/` |
| cc-connect bridge | `tcp://127.0.0.1:21010` |
| cc-connect management | `tcp://127.0.0.1:21020` |
| cc-connect webhook/callback | `tcp://127.0.0.1:21040` |
| service-manager | `http://127.0.0.1:20087/` |

控制测试应用端口：

| 组件 | URL |
| --- | --- |
| smallphone-likegirl | `http://127.0.0.1:23003/` |
| smallphone-likegirl clone | `http://127.0.0.1:23008/` |

`cc-connect` 默认是 readiness 必需项。只有显式设置 `SMALLPHONEAI_CC_CONNECT_DISABLED=1` 或 `SMALLPHONEAI_DISABLE_CC_CONNECT=1` 时，状态 JSON 才会把 cc-connect 标记为 disabled 并允许跳过该项。

## 默认路径

如果开发路径存在，bootstrap 优先使用：

```text
/root/projects/service-manager
/root/openhouse-connect-fresh
/root/projects/smallphone/smallphone-active
```

否则会在 `~/smallphoneai-repos` 下按需 clone：

```text
~/smallphoneai-repos/service-manager
~/smallphoneai-repos/openhouse-connect
~/smallphoneai-repos/smallphone-active
```

可通过环境变量覆盖：

```bash
SMALLPHONEAI_SERVICE_MANAGER_DIR=/path/to/service-manager
SMALLPHONEAI_CC_CONNECT_DIR=/path/to/openhouse-connect
SMALLPHONEAI_SMALLPHONE_DIR=/path/to/smallphone-active
SMALLPHONEAI_COMPONENT_TARGETS=service-manager,cc-connect,smallphone
```
EOF

cat > "$OFFICIAL_DOC_DIR/MODEL_API_SETUP.md" <<'EOF'
# Codex、Claude Code 和 Reasonix 登录/API 配置

本文件说明安装完成后，如何让 Codex CLI、Claude Code 和 Reasonix 连接大模型服务。

不要把 API key 写入 git 仓库、共享文档、APK 资源、日志或截图。优先使用工具自带登录流程，或只在本机 shell 配置环境变量。

## Codex CLI

Codex CLI 通常有两种使用方式：

1. 使用官方登录流程。
2. 使用 OpenAI API key。

### 官方登录

在 Ubuntu 终端中运行：

```bash
codex login
```

按终端提示完成浏览器登录或设备授权。登录完成后，再运行：

```bash
codex --version
codex
```

### 使用 OpenAI API key

如果你使用 API key，可以在 Ubuntu 的 shell 配置中设置：

```bash
export OPENAI_API_KEY="你的 OpenAI API key"
```

如果需要长期保存，只写入本机的 `~/.bashrc` 或 `~/.profile`，不要写入项目仓库。

如果使用 OpenAI 兼容网关，先查看 Codex CLI 当前版本文档或 `codex --help`，确认支持的 base URL 环境变量后再配置。

## Claude Code

Claude Code 通常有两种使用方式：

1. 使用官方登录流程。
2. 使用 Anthropic API key。

### 官方登录

在 Ubuntu 终端中运行：

```bash
claude login
```

按终端提示完成登录。登录完成后检查：

```bash
claude --version
claude
```

### 使用 Anthropic API key

如果你使用 API key，可以在 Ubuntu 的 shell 配置中设置：

```bash
export ANTHROPIC_API_KEY="你的 Anthropic API key"
```

如果需要长期保存，只写入本机的 `~/.bashrc` 或 `~/.profile`。

### 一键配置 Claude Code 使用 DeepSeek

如果要让 Claude Code 使用 DeepSeek 的 Anthropic 兼容接口，可以在 Ubuntu 终端中创建一个本机配置工具：

```bash
mkdir -p "$HOME/bin"
cat > "$HOME/bin/smallphoneai-configure-claude-deepseek" <<'CONFIG_TOOL'
#!/usr/bin/env bash
set -euo pipefail

read -r -p "请输入 DeepSeek API Key: " deepseek_key
if [ -z "$deepseek_key" ]; then
  echo "API Key 不能为空。" >&2
  exit 1
fi

config_file="$HOME/.bashrc"
start_marker="# >>> SmallPhoneAI Claude Code DeepSeek >>>"
end_marker="# <<< SmallPhoneAI Claude Code DeepSeek <<<"
tmp_file="$(mktemp)"

awk -v start="$start_marker" -v end="$end_marker" '
  $0 == start { skip=1; next }
  $0 == end { skip=0; next }
  skip != 1 { print }
' "$config_file" 2>/dev/null > "$tmp_file" || true

cat >> "$tmp_file" <<CONFIG
$start_marker
export ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic
export ANTHROPIC_AUTH_TOKEN=$deepseek_key
export ANTHROPIC_MODEL=deepseek-v4-pro
export ANTHROPIC_DEFAULT_OPUS_MODEL=deepseek-v4-pro
export ANTHROPIC_DEFAULT_SONNET_MODEL=deepseek-v4-pro
export ANTHROPIC_DEFAULT_HAIKU_MODEL=deepseek-v4-flash
export CLAUDE_CODE_SUBAGENT_MODEL=deepseek-v4-flash
export CLAUDE_CODE_EFFORT_LEVEL=max
$end_marker
CONFIG

mv "$tmp_file" "$config_file"
chmod 600 "$config_file"
echo "Claude Code DeepSeek 配置已写入 $config_file。请执行：source ~/.bashrc"
CONFIG_TOOL
chmod +x "$HOME/bin/smallphoneai-configure-claude-deepseek"
```

之后运行：

```bash
smallphoneai-configure-claude-deepseek
source ~/.bashrc
claude
```

## OpenCode 使用 DeepSeek

DeepSeek API Key 获取地址：

```text
https://platform.deepseek.com/api_keys
```

在 OpenCode 网页启动后：

1. 在 OpenCode 输入框执行 `/connect`。
2. 选择 `DeepSeek`。
3. 粘贴 DeepSeek API Key。
4. 再执行 `/models`，选择要使用的 DeepSeek 模型。

如果还没有 API Key，先打开 DeepSeek 控制台，登录后创建 API Key。不要把 API Key 粘贴到聊天内容、日志、截图或仓库中。

## Reasonix 使用 DeepSeek

维护中心的“填写 DeepSeek Key 并配置”会写入：

```text
~/.reasonix/config.json
```

配置中的关键字段是：

```json
{
  "lang": "zh-CN",
  "apiKey": "你的 DeepSeek API Key",
  "model": "deepseek-v4-pro"
}
```

不要手动把真实 API Key 写进文档、截图或仓库。

## 配置检查

重新打开 Termux 后会默认进入 Ubuntu。进入后检查：

```bash
command -v codex
command -v claude
command -v reasonix
codex --version
claude --version
reasonix --version
```

检查环境变量是否存在：

```bash
printenv OPENAI_API_KEY
printenv ANTHROPIC_API_KEY
```
EOF

cat > "$OFFICIAL_DOC_DIR/README.md" <<'EOF'
# SmallPhoneAI 文档

本目录只保留两类说明：

1. 运行环境说明：见 `ENVIRONMENT.md`。
2. 运行组件安装、启动和状态：见 `RUNTIME_COMPONENTS.md`。
3. Codex、Claude Code 和 Reasonix 的登录/API 配置：见 `MODEL_API_SETUP.md`。
EOF

run_ubuntu_logged bash -lc 'set -euo pipefail; mkdir -p "$HOME/smallphoneai-docs"; ln -sfn /data/data/com.termux/files/home/smallphoneai-docs/official "$HOME/smallphoneai-docs/official"; ln -sfn /data/data/com.termux/files/home/smallphoneai-docs/agent-notes "$HOME/smallphoneai-docs/agent-notes"; printf "%s\n" "$HOME/smallphoneai-docs/official"'

log "SmallPhoneAI 文档同步阶段完成。"
