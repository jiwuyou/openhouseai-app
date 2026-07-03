#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/_retry-profile.sh" ]; then
  # shellcheck source=_retry-profile.sh
  . "$SCRIPT_DIR/_retry-profile.sh"
fi

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

claude_install_program() {
  cat <<'EOF'
set -euo pipefail

export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"

require_node_24() {
  if command -v node >/dev/null 2>&1 && command -v npm >/dev/null 2>&1; then
    local major
    major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || printf 0)"
    if [ "${major:-0}" -ge 24 ]; then
      return 0
    fi
    echo "Node.js 版本过旧：$(node -v)，请先执行 Node.js 24 LTS 安装阶段。" >&2
    exit 4
  fi

  echo "Node.js 尚未安装，请先执行 Node.js 24 LTS 安装阶段。" >&2
  exit 3
}

configure_npm_network() {
  npm config set prefix "$HOME/.npm-global"
  npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org/}"
  npm config set fetch-retries "${SMALLPHONEAI_NPM_FETCH_RETRIES:-5}"
  npm config set fetch-retry-mintimeout "${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:-20000}"
  npm config set fetch-retry-maxtimeout "${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-120000}"
  npm config set fetch-timeout "${SMALLPHONEAI_NPM_FETCH_TIMEOUT:-600000}"
}

install_npm_global() {
  local package_name="$1"
  local attempt
  local install_timeout="${SMALLPHONEAI_NPM_INSTALL_TIMEOUT:-7200s}"

  for attempt in 1 2 3; do
    echo "正在安装 $package_name（第 $attempt 次，最长等待 $install_timeout）"
    if command -v timeout >/dev/null 2>&1; then
      if timeout -k 30s "$install_timeout" npm install -g "$package_name" --no-audit --no-fund --loglevel=verbose; then
        return 0
      fi
    elif npm install -g "$package_name" --no-audit --no-fund --loglevel=verbose; then
      return 0
    fi

    echo "$package_name 安装失败或超时，准备重试。"
    sleep $((attempt * 10))
  done

  echo "$package_name 安装失败，请检查网络或 npm registry。" >&2
  return 1
}

ensure_claude_native_path() {
  mkdir -p "$HOME/.local/bin"

  if [ -x "$HOME/.local/bin/claude" ] && "$HOME/.local/bin/claude" --version >/dev/null 2>&1; then
    echo "Claude Code native path 已就绪：$HOME/.local/bin/claude"
    "$HOME/.local/bin/claude" --version || true
    return 0
  fi

  rm -f "$HOME/.local/bin/claude"
  local candidate=""
  local path
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
    echo "Claude Code 命令不存在，无法创建 $HOME/.local/bin/claude。" >&2
    return 1
  fi

  ln -sf "$candidate" "$HOME/.local/bin/claude"
  echo "$HOME/.local/bin/claude -> $candidate"
  "$HOME/.local/bin/claude" --version
}

write_agent_tool_path() {
  local path_line
  path_line="export PATH=\"\$HOME/.local/node/bin:\$HOME/.local/bin:\$HOME/.npm-global/bin:\$PATH\""
  for PROFILE_FILE in "$HOME/.profile" "$HOME/.bashrc"; do
    touch "$PROFILE_FILE"
    if ! grep -Fq "$path_line" "$PROFILE_FILE"; then
      {
        printf "\n# SmallPhoneAI agent tools\n"
        printf "%s\n" "$path_line"
      } >> "$PROFILE_FILE"
    fi
  done
}

require_node_24

if command -v claude >/dev/null 2>&1; then
  echo "Claude Code 已安装：$(command -v claude)"
  claude --version || true
  ensure_claude_native_path
  write_agent_tool_path
  exit 0
fi

mkdir -p "$HOME/.npm-global/bin"
configure_npm_network
install_npm_global @anthropic-ai/claude-code

export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"
ensure_claude_native_path
command -v claude
claude --version || true

write_agent_tool_path
EOF
}

run_environment_probe
if command -v smallphoneai_log_retry_profile >/dev/null 2>&1; then
  smallphoneai_log_retry_profile '[SmallPhoneAI]'
fi

if is_current_ubuntu; then
  log "检测到当前已在 Ubuntu 内，直接安装或检查 Claude Code。"
  run_logged env \
    OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
    SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
    NPM_REGISTRY="${NPM_REGISTRY:-}" \
    NPM_CONFIG_REGISTRY="${NPM_CONFIG_REGISTRY:-${NPM_REGISTRY:-}}" \
    SMALLPHONEAI_NPM_FETCH_RETRIES="${SMALLPHONEAI_NPM_FETCH_RETRIES:-}" \
    SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:-}" \
    SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-}" \
    SMALLPHONEAI_NPM_FETCH_TIMEOUT="${SMALLPHONEAI_NPM_FETCH_TIMEOUT:-}" \
    bash -s <<<"$(claude_install_program)"
elif command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
  log "正在 Ubuntu 内安装或检查 Claude Code。"
  run_logged proot-distro login ubuntu -- env \
    OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
    SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
    NPM_REGISTRY="${NPM_REGISTRY:-}" \
    NPM_CONFIG_REGISTRY="${NPM_CONFIG_REGISTRY:-${NPM_REGISTRY:-}}" \
    SMALLPHONEAI_NPM_FETCH_RETRIES="${SMALLPHONEAI_NPM_FETCH_RETRIES:-}" \
    SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:-}" \
    SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-}" \
    SMALLPHONEAI_NPM_FETCH_TIMEOUT="${SMALLPHONEAI_NPM_FETCH_TIMEOUT:-}" \
    bash -s <<<"$(claude_install_program)"
else
  log "Ubuntu 不可用。请在 Termux 外层运行：bash bootstrap.sh ubuntu；或在 Ubuntu 内直接运行本脚本。"
  exit 2
fi

log "Claude Code 安装阶段完成。"
