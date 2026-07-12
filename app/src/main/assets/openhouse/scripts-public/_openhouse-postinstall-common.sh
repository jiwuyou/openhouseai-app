#!/usr/bin/env bash
set -euo pipefail

oh_log() {
  printf '[OpenHouse postinstall] %s\n' "$*"
}

oh_warn() {
  printf '[OpenHouse postinstall] WARN: %s\n' "$*" >&2
}

oh_die() {
  printf '[OpenHouse postinstall] ERROR: %s\n' "$*" >&2
  exit 1
}

oh_retry_mode() {
  local raw
  raw="${OPENHOUSE_RETRY_MODE:-${SMALLPHONEAI_RETRY_MODE:-normal}}"
  raw="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  case "$raw" in
    cn|china|mainland|domestic|china-mainland)
      printf 'cn'
      ;;
    general|normal|default|standard|'')
      printf 'normal'
      ;;
    *)
      printf 'normal'
      ;;
  esac
}

oh_unify_optional_environment_pair() {
  local openhouse_name="$1"
  local smallphone_name="$2"
  local label="$3"
  local openhouse_value="${!openhouse_name:-}"
  local smallphone_value="${!smallphone_name:-}"
  local selected=""

  if [ -n "$openhouse_value" ] && [ -n "$smallphone_value" ] \
    && [ "$openhouse_value" != "$smallphone_value" ]; then
    oh_die "$label 的 OPENHOUSEAI/SMALLPHONEAI 配置冲突，请只保留一个值或改为相同值。"
  fi
  selected="${openhouse_value:-$smallphone_value}"
  printf -v "$openhouse_name" '%s' "$selected"
  printf -v "$smallphone_name" '%s' "$selected"
  export "$openhouse_name" "$smallphone_name"
}

oh_unify_ubuntu_mirror_environment() {
  oh_unify_optional_environment_pair OPENHOUSEAI_UBUNTU_ROOTFS_URL SMALLPHONEAI_UBUNTU_ROOTFS_URL "Ubuntu rootfs 单源"
  oh_unify_optional_environment_pair OPENHOUSEAI_UBUNTU_ROOTFS_URLS SMALLPHONEAI_UBUNTU_ROOTFS_URLS "Ubuntu rootfs 候选列表"
  oh_unify_optional_environment_pair OPENHOUSEAI_UBUNTU_APT_MIRROR SMALLPHONEAI_UBUNTU_APT_MIRROR "Ubuntu apt 单源"
  oh_unify_optional_environment_pair OPENHOUSEAI_RESOLVED_UBUNTU_ROOTFS_URL SMALLPHONEAI_RESOLVED_UBUNTU_ROOTFS_URL "已解析 Ubuntu rootfs"
  oh_unify_optional_environment_pair OPENHOUSEAI_RESOLVED_UBUNTU_APT_MIRROR SMALLPHONEAI_RESOLVED_UBUNTU_APT_MIRROR "已解析 Ubuntu apt"
  oh_unify_optional_environment_pair OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID "Ubuntu 镜像运行锁 ID"
  oh_unify_optional_environment_pair OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT "Ubuntu 镜像运行锁目录"
  oh_unify_optional_environment_pair OPENHOUSEAI_UBUNTU_MIRROR_FIRST_PASS_TIMEOUT_SECONDS SMALLPHONEAI_UBUNTU_MIRROR_FIRST_PASS_TIMEOUT_SECONDS "Ubuntu 镜像第一轮超时"
  oh_unify_optional_environment_pair OPENHOUSEAI_UBUNTU_MIRROR_TRANSIENT_RETRY_TIMEOUT_SECONDS SMALLPHONEAI_UBUNTU_MIRROR_TRANSIENT_RETRY_TIMEOUT_SECONDS "Ubuntu 镜像第二轮超时"
}

oh_apply_retry_profile() {
  local mode
  mode="$(oh_retry_mode)"
  export OPENHOUSE_RETRY_MODE="$mode"
  export SMALLPHONEAI_RETRY_MODE="$mode"
  if [ "$mode" = "cn" ]; then
    : "${OPENHOUSEAI_TERMUX_MAIN_REPO:=https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main}"
    : "${SMALLPHONEAI_TERMUX_MAIN_REPO:=$OPENHOUSEAI_TERMUX_MAIN_REPO}"
    : "${SMALLPHONEAI_NODE_DIST_BASE:=https://cdn.npmmirror.com/binaries/node/latest-v24.x}"
    : "${NPM_REGISTRY:=https://registry.npmmirror.com}"
    : "${NPM_CONFIG_REGISTRY:=$NPM_REGISTRY}"
    : "${SMALLPHONEAI_NPM_FETCH_RETRIES:=8}"
    : "${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:=20000}"
    : "${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:=180000}"
    : "${SMALLPHONEAI_NPM_FETCH_TIMEOUT:=900000}"
    export OPENHOUSEAI_TERMUX_MAIN_REPO SMALLPHONEAI_TERMUX_MAIN_REPO
    export SMALLPHONEAI_NODE_DIST_BASE
    export NPM_REGISTRY NPM_CONFIG_REGISTRY
    export SMALLPHONEAI_NPM_FETCH_RETRIES SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT
    export SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT SMALLPHONEAI_NPM_FETCH_TIMEOUT
  fi
}

oh_maybe_rewrite_github_url() {
  local url="$1"
  local prefix="${SMALLPHONEAI_GITHUB_PROXY_PREFIX:-${OPENHOUSE_GITHUB_PROXY_PREFIX:-}}"
  if [ "$(oh_retry_mode)" = "cn" ] && [ -n "$prefix" ]; then
    case "$url" in
      https://github.com/*|https://raw.githubusercontent.com/*)
        printf '%s%s\n' "$prefix" "$url"
        return 0
        ;;
    esac
  fi
  printf '%s\n' "$url"
}

oh_apply_retry_profile
oh_unify_ubuntu_mirror_environment

oh_is_current_ubuntu() {
  [ -r /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

oh_is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

oh_termux_home() {
  if oh_is_current_ubuntu; then
    printf '%s\n' "/data/data/com.termux/files/home"
  else
    printf '%s\n' "${HOME:-/data/data/com.termux/files/home}"
  fi
}

oh_bootstrap_path() {
  local termux_home candidate
  termux_home="$(oh_termux_home)"
  for candidate in \
    "${OPENHOUSE_BOOTSTRAP:-}" \
    "${SMALLPHONEAI_BOOTSTRAP:-}" \
    "$termux_home/.smallphoneai-bootstrap/bootstrap.sh" \
    "$HOME/.smallphoneai-bootstrap/bootstrap.sh"; do
    [ -n "$candidate" ] || continue
    [ -f "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  return 1
}

oh_require_bootstrap() {
  local bootstrap
  bootstrap="$(oh_bootstrap_path)" || oh_die "找不到 bootstrap.sh。请先完成 OpenHouse 首次安装，或设置 SMALLPHONEAI_BOOTSTRAP。"
  printf '%s\n' "$bootstrap"
}

oh_run_bootstrap() {
  local command_name="$1"
  local bootstrap
  bootstrap="$(oh_require_bootstrap)"
  chmod +x "$bootstrap" 2>/dev/null || true
  oh_log "执行 bootstrap 阶段：$command_name"
  env \
    OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
    SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
    OPENHOUSEAI_TERMUX_MAIN_REPO="${OPENHOUSEAI_TERMUX_MAIN_REPO:-}" \
    SMALLPHONEAI_TERMUX_MAIN_REPO="${SMALLPHONEAI_TERMUX_MAIN_REPO:-}" \
    OPENHOUSEAI_UBUNTU_ROOTFS_URL="${OPENHOUSEAI_UBUNTU_ROOTFS_URL:-}" \
    SMALLPHONEAI_UBUNTU_ROOTFS_URL="${SMALLPHONEAI_UBUNTU_ROOTFS_URL:-}" \
    OPENHOUSEAI_UBUNTU_ROOTFS_URLS="${OPENHOUSEAI_UBUNTU_ROOTFS_URLS:-}" \
    SMALLPHONEAI_UBUNTU_ROOTFS_URLS="${SMALLPHONEAI_UBUNTU_ROOTFS_URLS:-}" \
    OPENHOUSEAI_UBUNTU_APT_MIRROR="${OPENHOUSEAI_UBUNTU_APT_MIRROR:-}" \
    SMALLPHONEAI_UBUNTU_APT_MIRROR="${SMALLPHONEAI_UBUNTU_APT_MIRROR:-}" \
    OPENHOUSEAI_RESOLVED_UBUNTU_ROOTFS_URL="${OPENHOUSEAI_RESOLVED_UBUNTU_ROOTFS_URL:-}" \
    SMALLPHONEAI_RESOLVED_UBUNTU_ROOTFS_URL="${SMALLPHONEAI_RESOLVED_UBUNTU_ROOTFS_URL:-}" \
    OPENHOUSEAI_RESOLVED_UBUNTU_APT_MIRROR="${OPENHOUSEAI_RESOLVED_UBUNTU_APT_MIRROR:-}" \
    SMALLPHONEAI_RESOLVED_UBUNTU_APT_MIRROR="${SMALLPHONEAI_RESOLVED_UBUNTU_APT_MIRROR:-}" \
    OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID="${OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID:-}" \
    SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID="${SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID:-}" \
    OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT="${OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT:-}" \
    SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT="${SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT:-}" \
    OPENHOUSEAI_UBUNTU_MIRROR_FIRST_PASS_TIMEOUT_SECONDS="${OPENHOUSEAI_UBUNTU_MIRROR_FIRST_PASS_TIMEOUT_SECONDS:-}" \
    SMALLPHONEAI_UBUNTU_MIRROR_FIRST_PASS_TIMEOUT_SECONDS="${SMALLPHONEAI_UBUNTU_MIRROR_FIRST_PASS_TIMEOUT_SECONDS:-}" \
    OPENHOUSEAI_UBUNTU_MIRROR_TRANSIENT_RETRY_TIMEOUT_SECONDS="${OPENHOUSEAI_UBUNTU_MIRROR_TRANSIENT_RETRY_TIMEOUT_SECONDS:-}" \
    SMALLPHONEAI_UBUNTU_MIRROR_TRANSIENT_RETRY_TIMEOUT_SECONDS="${SMALLPHONEAI_UBUNTU_MIRROR_TRANSIENT_RETRY_TIMEOUT_SECONDS:-}" \
    SMALLPHONEAI_NODE_DIST_BASE="${SMALLPHONEAI_NODE_DIST_BASE:-}" \
    NPM_REGISTRY="${NPM_REGISTRY:-}" \
    NPM_CONFIG_REGISTRY="${NPM_CONFIG_REGISTRY:-${NPM_REGISTRY:-}}" \
    SMALLPHONEAI_NPM_FETCH_RETRIES="${SMALLPHONEAI_NPM_FETCH_RETRIES:-}" \
    SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:-}" \
    SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-}" \
    SMALLPHONEAI_NPM_FETCH_TIMEOUT="${SMALLPHONEAI_NPM_FETCH_TIMEOUT:-}" \
    SMALLPHONEAI_GITHUB_PROXY_PREFIX="${SMALLPHONEAI_GITHUB_PROXY_PREFIX:-}" \
    OPENHOUSE_GITHUB_PROXY_PREFIX="${OPENHOUSE_GITHUB_PROXY_PREFIX:-}" \
    bash "$bootstrap" "$command_name"
}

oh_run_ubuntu_bash() {
  local program="$1"
  if oh_is_current_ubuntu; then
    bash -lc "$program"
    return
  fi
  command -v proot-distro >/dev/null 2>&1 || oh_die "当前不在 Ubuntu，且 Termux 外层缺少 proot-distro。"
  proot-distro login ubuntu -- bash -lc "$program"
}

oh_print_tool_path() {
  local tool_name="$1"
  oh_run_ubuntu_bash "export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.local/bin:/usr/local/bin:\$PATH\"; command -v $tool_name"
}

oh_check_tool_version() {
  local tool_name="$1"
  local version_command="${2:-$1 --version}"
  oh_run_ubuntu_bash "set -e; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.local/bin:/usr/local/bin:\$PATH\"; command -v $tool_name; $version_command || true"
}

oh_ensure_claude_native_path() {
  oh_run_ubuntu_bash 'set -euo pipefail
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"
mkdir -p "$HOME/.local/bin"

if [ -x "$HOME/.local/bin/claude" ] && "$HOME/.local/bin/claude" --version >/dev/null 2>&1; then
  printf "%s\n" "$HOME/.local/bin/claude"
  "$HOME/.local/bin/claude" --version || true
  exit 0
fi

rm -f "$HOME/.local/bin/claude"
candidate=""
for path in \
  "$HOME/.npm-global/bin/claude" \
  "$HOME/.local/node/bin/claude" \
  "/usr/local/bin/claude"; do
  if [ -x "$path" ]; then
    candidate="$path"
    break
  fi
done

if [ -z "$candidate" ]; then
  candidate="$(command -v claude 2>/dev/null || true)"
fi

if [ -z "$candidate" ]; then
  echo "Claude Code command not found. Run /root/openhouse/scripts/install-claude-code.sh first." >&2
  exit 1
fi

ln -sf "$candidate" "$HOME/.local/bin/claude"
printf "%s -> %s\n" "$HOME/.local/bin/claude" "$candidate"
"$HOME/.local/bin/claude" --version'
}

oh_next_docs() {
  cat <<'EOF'

下一步建议：
- 让 pi-agent 阅读 /root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md。
- 配置 Claude Code / CloudCLI 时阅读 /root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md。
- 配置模型迁移时阅读 /root/openhouse/docs/MODEL_API_SETUP.md。
- 服务启动、停止、注册和侧边栏入口阅读 /root/openhouse/docs/SERVICE_MANAGER.md。
- 出错时先运行 /root/openhouse/scripts/check-ai-tools.sh，再阅读 /root/openhouse/docs/RECOVERY.md。
EOF
}
