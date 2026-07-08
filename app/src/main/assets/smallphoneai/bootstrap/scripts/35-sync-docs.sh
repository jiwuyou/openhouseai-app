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

DOC_ROOT="$TERMUX_HOME/openhouseai-docs"
LEGACY_DOC_ROOT="$TERMUX_HOME/smallphoneai-docs"
OPENHOUSE_DOC_ROOT="$TERMUX_HOME/openhouse"
OFFICIAL_DOC_DIR="$DOC_ROOT/official"
AGENT_NOTES_DIR="$DOC_ROOT/agent-notes"
OFFICIAL_SCRIPT_DIR="$TERMUX_HOME/openhouseai-scripts"

ensure_symlink() {
  local target="$1"
  local link_path="$2"
  local link_parent
  link_parent="$(dirname "$link_path")"
  mkdir -p "$link_parent"

  if [ -L "$link_path" ] || [ -f "$link_path" ]; then
    rm -f "$link_path"
  elif [ -e "$link_path" ]; then
    local backup_path="${link_path}.backup-$(date +%Y%m%d%H%M%S)"
    mv "$link_path" "$backup_path"
    log "已备份非符号链接路径：$link_path -> $backup_path"
  fi

  ln -sfn "$target" "$link_path"
}

write_public_script_fallbacks() {
  log "未检测到 APK scripts-public，写入最小后置安装脚本入口。"
  mkdir -p "$OFFICIAL_SCRIPT_DIR"

  cat > "$OFFICIAL_SCRIPT_DIR/_openhouse-postinstall-common.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
oh_log(){ printf '[OpenHouse postinstall] %s\n' "$*"; }
oh_die(){ printf '[OpenHouse postinstall] ERROR: %s\n' "$*" >&2; exit 1; }
oh_is_current_ubuntu(){ [ -r /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release; }
oh_termux_home(){ if oh_is_current_ubuntu; then printf '%s\n' /data/data/com.termux/files/home; else printf '%s\n' "${HOME:-/data/data/com.termux/files/home}"; fi; }
oh_bootstrap(){ local home; home="$(oh_termux_home)"; for p in "${OPENHOUSE_BOOTSTRAP:-}" "${SMALLPHONEAI_BOOTSTRAP:-}" "$home/.smallphoneai-bootstrap/bootstrap.sh" "$HOME/.smallphoneai-bootstrap/bootstrap.sh"; do [ -n "$p" ] && [ -f "$p" ] && { printf '%s\n' "$p"; return 0; }; done; return 1; }
oh_run_bootstrap(){ local b; b="$(oh_bootstrap)" || oh_die "找不到 bootstrap.sh，请先完成 OpenHouse 首次安装。"; chmod +x "$b" 2>/dev/null || true; oh_log "执行 bootstrap 阶段：$1"; bash "$b" "$1"; }
oh_run_ubuntu_bash(){ if oh_is_current_ubuntu; then bash -lc "$1"; else command -v proot-distro >/dev/null 2>&1 || oh_die "缺少 proot-distro。"; proot-distro login ubuntu -- bash -lc "$1"; fi; }
oh_ensure_claude_native_path(){ oh_run_ubuntu_bash 'set -euo pipefail; export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; mkdir -p "$HOME/.local/bin"; if [ -x "$HOME/.local/bin/claude" ] && "$HOME/.local/bin/claude" --version >/dev/null 2>&1; then "$HOME/.local/bin/claude" --version; exit 0; fi; rm -f "$HOME/.local/bin/claude"; candidate=""; for path in "$HOME/.npm-global/bin/claude" "$HOME/.local/node/bin/claude" "/usr/local/bin/claude"; do [ -x "$path" ] && { candidate="$path"; break; }; done; [ -n "$candidate" ] || candidate="$(command -v claude 2>/dev/null || true)"; [ -n "$candidate" ] || { echo "Claude Code command not found" >&2; exit 1; }; ln -sf "$candidate" "$HOME/.local/bin/claude"; "$HOME/.local/bin/claude" --version'; }
oh_next_docs(){ cat <<'DOCS'

下一步建议：
- /root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md
- /root/openhouse/docs/MODEL_API_SETUP.md
- /root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md
- /root/openhouse/docs/SERVICE_MANAGER.md
- /root/openhouse/docs/RECOVERY.md
DOCS
}
EOF

  cat > "$OFFICIAL_SCRIPT_DIR/install-codex.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"
oh_run_bootstrap node
oh_run_bootstrap codex
oh_run_ubuntu_bash 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; command -v codex; codex --version || true'
oh_next_docs
EOF

  cat > "$OFFICIAL_SCRIPT_DIR/install-claude-code.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"
oh_run_bootstrap node
oh_run_bootstrap claude-code
oh_ensure_claude_native_path
oh_run_ubuntu_bash 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; command -v claude; claude --version || true'
oh_next_docs
EOF

  cat > "$OFFICIAL_SCRIPT_DIR/install-cloudcli.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"
oh_run_bootstrap node
oh_run_bootstrap claude-code
oh_ensure_claude_native_path
oh_run_bootstrap cloudcli
oh_run_bootstrap sync-registry || true
oh_run_ubuntu_bash 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; command -v cloudcli; cloudcli version || cloudcli --version || true'
printf '%s\n' 'CloudCLI 默认本机账号密码：admin / 123456。请在 Android 运行控制页启动 cc/codex，或按 /root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md 测通 Claude Code。'
oh_next_docs
EOF

  cat > "$OFFICIAL_SCRIPT_DIR/install-cc-switch.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"
oh_run_bootstrap cc-switch
oh_check_tool_version cc-switch "cc-switch --version"
printf '%s\n' 'cc-switch 是 provider 配置执行器，不是长期服务。请阅读 /root/openhouse/docs/cc-switch.md。'
oh_next_docs
EOF

  cat > "$OFFICIAL_SCRIPT_DIR/install-hermes.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"
oh_run_ubuntu_bash 'set -euo pipefail; export PATH="$HOME/.local/bin:$PATH"; command -v git >/dev/null; command -v curl >/dev/null; command -v python3 >/dev/null; command -v uv >/dev/null 2>&1 || curl -LsSf https://astral.sh/uv/install.sh | sh; export PATH="$HOME/.local/bin:$PATH"; mkdir -p /root/.local/share/openhouseai; cd /root/.local/share/openhouseai; if [ -d hermes-webui/.git ]; then cd hermes-webui && git pull --ff-only || true; else git clone https://github.com/nesquena/hermes-webui.git hermes-webui && cd hermes-webui; fi; uv venv .venv; printf "%s\n" "Hermes prepared at /root/.local/share/openhouseai/hermes-webui"'
printf '%s\n' '请继续按 /root/openhouse/docs/HERMES_SETUP.md 前台测通并注册 service-manager。'
oh_next_docs
EOF

  cat > "$OFFICIAL_SCRIPT_DIR/check-ai-tools.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"
missing=0
check(){ label="$1"; cmd="$2"; if oh_run_ubuntu_bash "$cmd"; then printf '[ok] %s\n' "$label"; else printf '[missing] %s\n' "$label" >&2; missing=1; fi; }
check Node 'export PATH="$HOME/.local/node/bin:$PATH"; command -v node && node -v'
check Codex 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; command -v codex && codex --version'
check Claude-Code 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; command -v claude && claude --version'
check Claude-Code-native 'test -x "$HOME/.local/bin/claude" && "$HOME/.local/bin/claude" --version'
check CloudCLI 'export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"; command -v cloudcli && (cloudcli version || cloudcli --version)'
check cc-switch 'export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:/usr/local/bin:$PATH"; command -v cc-switch && cc-switch --version'
check Docs 'test -d /root/openhouse/docs && test -f /root/openhouse/docs/START_HERE.md'
check Scripts 'test -d /root/openhouse/scripts && test -f /root/openhouse/scripts/install-codex.sh && test -f /root/openhouse/scripts/install-cc-switch.sh'
oh_run_bootstrap status || missing=1
exit "$missing"
EOF

  chmod +x "$OFFICIAL_SCRIPT_DIR"/*.sh
}

sync_public_scripts() {
  local source_dir
  mkdir -p "$OFFICIAL_SCRIPT_DIR"
  for source_dir in \
    "${OPENHOUSE_SCRIPTS_PUBLIC_DIR:-}" \
    "$TERMUX_HOME/.smallphoneai-bootstrap/apk-assets/openhouse/scripts-public" \
    "$TERMUX_HOME/.smallphoneai-bootstrap/openhouse/scripts-public" \
    "$TERMUX_HOME/.smallphoneai-bootstrap/scripts-public"; do
    [ -n "$source_dir" ] || continue
    if [ -d "$source_dir" ]; then
      log "正在同步 OpenHouse 后置安装脚本：$source_dir -> $OFFICIAL_SCRIPT_DIR"
      rm -rf "$OFFICIAL_SCRIPT_DIR/.sync-tmp"
      mkdir -p "$OFFICIAL_SCRIPT_DIR/.sync-tmp"
      cp -a "$source_dir/." "$OFFICIAL_SCRIPT_DIR/.sync-tmp/"
      find "$OFFICIAL_SCRIPT_DIR/.sync-tmp" -type f -name '*.sh' -exec chmod +x {} +
      find "$OFFICIAL_SCRIPT_DIR" -mindepth 1 -maxdepth 1 ! -name '.sync-tmp' -exec rm -rf {} +
      cp -a "$OFFICIAL_SCRIPT_DIR/.sync-tmp/." "$OFFICIAL_SCRIPT_DIR/"
      rm -rf "$OFFICIAL_SCRIPT_DIR/.sync-tmp"
      return 0
    fi
  done

  write_public_script_fallbacks
}

log "正在同步 OpenHouse 官方文档路径到 $OFFICIAL_DOC_DIR"
mkdir -p "$OFFICIAL_DOC_DIR" "$AGENT_NOTES_DIR" "$LEGACY_DOC_ROOT" "$OPENHOUSE_DOC_ROOT" "$OFFICIAL_SCRIPT_DIR"

if [ -f "$OFFICIAL_DOC_DIR/PRODUCT_OVERVIEW.md" ] && [ -f "$OFFICIAL_DOC_DIR/AI_AGENT_REFERENCE.md" ]; then
  log "检测到 APK docs-public 官方文档已存在，跳过 bootstrap fallback 正文写入。"
else
  log "未检测到完整 docs-public 官方文档，写入 OpenHouse fallback 索引。"
  rm -f \
    "$OFFICIAL_DOC_DIR/START_HERE.md" \
    "$OFFICIAL_DOC_DIR/README.md" \
    "$OFFICIAL_DOC_DIR/ENVIRONMENT.md" \
    "$OFFICIAL_DOC_DIR/RUNTIME_COMPONENTS.md" \
    "$OFFICIAL_DOC_DIR/MODEL_API_SETUP.md"

  cat > "$OFFICIAL_DOC_DIR/START_HERE.md" <<'EOF'
# OpenHouse 文档入口

这是 bootstrap fallback 文档。完整官方文档应来自 APK 内置目录 `openhouse/docs-public`，并同步到以下路径：

- `/root/openhouse/docs`
- `/root/openhouseai-docs/official`
- `~/openhouseai-docs/official`

如果只看到本 fallback 文件，说明完整官方文档还没有完成同步。请优先运行官方文档同步阶段，或重新执行首次安装/修复流程。

OpenHouse 是一个让人和 AI 共同使用软件、构建个人工作台的平台。pi-agent 是首次配置助手和文档索引员，不是唯一主工作台。用户可以选择 Claude Code、Codex、Hermes Web，或让 AI 搜索、安装和改造其他开源项目作为自己的长期工作台。

AI 处理 OpenHouse 相关任务时，应优先查看 `/root/openhouse/docs`。如果本机文档没有覆盖当前问题，应主动联网检索，优先查官方文档、项目 README、issue 和 release。
EOF

  cat > "$OFFICIAL_DOC_DIR/README.md" <<'EOF'
# OpenHouse 官方文档

稳定文档路径：

- `/root/openhouse/docs`
- `/root/openhouseai-docs/official`
- `~/openhouseai-docs/official`

完整文档由 APK 内置 `openhouse/docs-public` 同步生成。此 README 是 fallback 索引，用于在完整文档尚未落地时给用户和 AI 一个正确入口。

请先阅读 `START_HERE.md`。当完整官方文档同步完成后，应能看到产品能力、架构、模型迁移、service-manager、Termux/Ubuntu、CloudCLI/Claude Code、Codex、Hermes Web、Shizuku、浏览器、GitHub 镜像和排障等文档。
EOF

  cat > "$OFFICIAL_DOC_DIR/OPENHOUSE_DOC_PATHS.md" <<'EOF'
# OpenHouse 文档路径

主要路径：

- `/root/openhouse/docs`
- `/root/openhouseai-docs/official`
- `~/openhouseai-docs/official`

兼容路径：

- `~/smallphoneai-docs/official`

Agent 笔记路径：

- `/root/openhouseai-docs/agent-notes`
- `~/openhouseai-docs/agent-notes`
EOF
fi

ensure_symlink "$OFFICIAL_DOC_DIR" "$OPENHOUSE_DOC_ROOT/docs"
sync_public_scripts
ensure_symlink "$OFFICIAL_SCRIPT_DIR" "$OPENHOUSE_DOC_ROOT/scripts"
ensure_symlink "$OFFICIAL_DOC_DIR" "$LEGACY_DOC_ROOT/official"
ensure_symlink "$AGENT_NOTES_DIR" "$LEGACY_DOC_ROOT/agent-notes"

: <<'OPENHOUSE_LEGACY_DOCS_DISABLED'

cat > "$OFFICIAL_DOC_DIR/START_HERE.md" <<'EOF'
# 从这里开始

SmallPhoneAI 文档分为两部分：

1. `ENVIRONMENT.md`：说明当前 Android、Termux、Ubuntu、路径和安装范围。
2. `MODEL_API_SETUP.md`：说明 Codex CLI、Claude Code 和 CloudCLI 如何登录，或如何配置大模型 API。
3. `RUNTIME_COMPONENTS.md`：说明 SmallPhone、service-manager 与可选 cc-connect/openhouse-connect 的安装、启动、修复和状态入口。

建议顺序：
- 先读 `ENVIRONMENT.md`，确认当前运行在哪里。
- 如果要使用 Codex CLI、Claude Code 或 CloudCLI，再读 `MODEL_API_SETUP.md`。
EOF

cat > "$OFFICIAL_DOC_DIR/ENVIRONMENT.md" <<'EOF'
# 运行环境说明

SmallPhoneAI 运行在 Android 手机上，结构如下：

- Android 是宿主系统。
- Termux 提供终端环境、包管理、Ubuntu 启动、bridge 和恢复兜底。
- Ubuntu 通过 `proot-distro` 安装在 Termux 内，是主要运行域。
- Node.js、Codex CLI、Claude Code、CloudCLI 安装在 Ubuntu 内。
- service-manager 默认运行在 Ubuntu/proot 内，是本机运行控制面。
- cc-connect/openhouse-connect 默认运行在 Ubuntu/proot 内，提供 Agent bridge 和 Web client；它是可修复/可选连接服务，不是首次 readiness 必需项。
- SmallPhone 默认运行在 Ubuntu/proot 内，是产品应用栈。

## 安装范围

SmallPhoneAI bootstrap 首装负责安装、检查、注册和启动：

- Ubuntu proot
- Node.js 24 LTS
- service-manager
- SmallPhone
- pi-agent / pi-web
- cc-connect/openhouse-connect（可选连接服务，失败时进入诊断/修复，不阻塞首次进入）

Codex CLI、Claude Code、ClaudeCodeUI / CloudCLI 和 Hermes 是后置能力，由 pi-agent 按 `/root/openhouse/scripts` 和 `/root/openhouse/docs` 引导安装。

Node.js 24 LTS 是单独可见阶段，后置 AI 工具只检查并使用该 Node.js runtime，不再各自隐式安装系统 Node.js。

## 阶段顺序

维护中心的一键阶段顺序是：

1. 准备 Termux 路径、配置和文档。
2. 安装 Termux 基础包。
3. 测速并选择 Ubuntu rootfs 镜像源，然后安装 Ubuntu rootfs。
4. 同步 SmallPhoneAI 文档。
5. 安装 Ubuntu 基础包。
6. 设置打开 Termux 后默认进入 Ubuntu。
7. 安装 Node.js 24 LTS。
8. 同步 OpenHouse 文档和后置脚本入口。
9. 解包 pi-agent / pi-web。
10. 安装并配置 service-manager。
11. 注册并启动 pi-agent / pi-web。
12. 安装 SmallPhone 兼容服务，并尝试安装 openhouse-connect 可选连接服务。
13. 同步 OpenHouseAI registry 和 service-manager 服务配置。
14. 在 Ubuntu/proot 内启动 service-manager，并通过 `group:local-stack` 启动已注册服务。
15. 输出最终状态 JSON，供 App Shell 做健康判断。

默认进入 Ubuntu 必须在后置 AI 工具安装之前完成。

Ubuntu rootfs 安装不会使用代理。安装脚本会先测试内置的 Ubuntu cloud image 镜像源，选择当前可达且较快的 rootfs URL，再执行 `proot-distro install -n ubuntu <rootfs-url>`。如需指定源，可在执行前设置 `SMALLPHONEAI_UBUNTU_ROOTFS_URL`。

## 路径

- Termux 主目录：`/data/data/com.termux/files/home`
- 工作区：`/data/data/com.termux/files/home/workspace`
- 官方文档：`/data/data/com.termux/files/home/openhouseai-docs/official`
- Agent 笔记：`/data/data/com.termux/files/home/openhouseai-docs/agent-notes`
- 启动入口配置：`/data/data/com.termux/files/home/.smallphoneai`
- 运行组件仓库根目录：`~/smallphoneai-repos`

Ubuntu 中如果存在以下短路径，优先使用短路径：

- `~/openhouseai-docs/official`
- `~/openhouseai-docs/agent-notes`
- `~/smallphoneai-docs/official` 兼容旧路径
- `~/smallphoneai-docs/agent-notes` 兼容旧路径
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

SmallPhoneAI bootstrap 只编排组件，不复制组件内部安装逻辑。默认核心组件是：

- `service-manager`
- `SmallPhone`
- `smallphone-likegirl` control test

`cc-connect` / `openhouse-connect` 会保留为可安装、可注册、可诊断、可修复的可选连接服务；它不参与首次 readiness 必需项。

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
| service-manager | 以 `$HOME/.config/openhouseai/service-manager/config.json` 中的监听地址为准，默认通常是 `http://127.0.0.1:20087/` |

控制测试应用端口：

| 组件 | URL |
| --- | --- |
| smallphone-likegirl | `http://127.0.0.1:23003/` |
| smallphone-likegirl clone | `http://127.0.0.1:23008/` |

首次 readiness 必需项是 service-manager、pi-agent、pi-web、SmallPhone frontend 和 SmallPhone core API。`cc-connect` / `openhouse-connect` 会在状态 JSON 中作为可选诊断项显示；不可达时应进入服务控制/修复流程，但不阻塞首次进入。

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
# Codex CLI、Claude Code 和 CloudCLI 登录/API 配置

首次安装只负责安装本地运行环境和核心工具，不会要求填写 API Key，也不会写入默认模型配置。安装完成后，按实际要使用的工具再登录或配置。

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

如果使用 OpenAI 兼容网关，先查看 Codex CLI 当前版本文档或 `codex --help`，确认当前版本支持的 base URL 环境变量后再配置。

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

## CloudCLI

CloudCLI / ClaudeCodeUI 是后置网页交互入口。首次安装不会要求登录或配置模型，也不会因为 CloudCLI 缺失阻塞 pi-agent。

常用命令：

```bash
cloudcli --help
cloudcli --port 23083
```

如果需要由 App 启动，使用维护中心的 CloudCLI 启动/重启入口即可。

## 配置检查

重新打开 Termux 后会默认进入 Ubuntu。进入后检查：

```bash
command -v codex
command -v claude
command -v cloudcli
codex --version
claude --version
cloudcli version || cloudcli --version
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
3. Codex CLI、Claude Code 和 CloudCLI 的登录/API 配置：见 `MODEL_API_SETUP.md`。
EOF

OPENHOUSE_LEGACY_DOCS_DISABLED

run_ubuntu_logged bash -lc '
set -euo pipefail

ensure_symlink() {
  local target="$1"
  local link_path="$2"
  local link_parent
  link_parent="$(dirname "$link_path")"
  mkdir -p "$link_parent"

  if [ -L "$link_path" ] || [ -f "$link_path" ]; then
    rm -f "$link_path"
  elif [ -e "$link_path" ]; then
    local backup_path="${link_path}.backup-$(date +%Y%m%d%H%M%S)"
    mv "$link_path" "$backup_path"
    printf "Backed up non-symlink docs path: %s -> %s\n" "$link_path" "$backup_path"
  fi

  ln -sfn "$target" "$link_path"
}

termux_doc_root="/data/data/com.termux/files/home/openhouseai-docs"
termux_script_root="/data/data/com.termux/files/home/openhouseai-scripts"
mkdir -p "$HOME/openhouseai-docs" "$HOME/smallphoneai-docs" "$HOME/openhouse"
ensure_symlink "$termux_doc_root/official" "$HOME/openhouseai-docs/official"
ensure_symlink "$termux_doc_root/agent-notes" "$HOME/openhouseai-docs/agent-notes"
ensure_symlink "$termux_doc_root/official" "$HOME/smallphoneai-docs/official"
ensure_symlink "$termux_doc_root/agent-notes" "$HOME/smallphoneai-docs/agent-notes"
ensure_symlink "$termux_doc_root/official" "$HOME/openhouse/docs"
ensure_symlink "$termux_script_root" "$HOME/openhouse/scripts"
printf "%s\n" "$HOME/openhouse/docs"
printf "%s\n" "$HOME/openhouse/scripts"
printf "%s\n" "$HOME/openhouseai-docs/official"
'

log "OpenHouse 官方文档同步阶段完成。"
