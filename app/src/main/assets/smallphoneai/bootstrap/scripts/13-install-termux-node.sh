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

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
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

run_with_optional_timeout() {
  local timeout_seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout --foreground "$timeout_seconds" "$@"
  else
    "$@"
  fi
}

install_termux_node_packages() {
  local timeout_seconds="${OPENHOUSEAI_TERMUX_APT_INSTALL_TIMEOUT_SECONDS:-${SMALLPHONEAI_TERMUX_APT_INSTALL_TIMEOUT_SECONDS:-1800}}"
  local -a installer
  local -a installer_options=()
  if command -v apt >/dev/null 2>&1; then
    installer=(env DEBIAN_FRONTEND=noninteractive DEBIAN_PRIORITY=critical apt
      -o Dpkg::Options::=--force-confdef
      -o Dpkg::Options::=--force-confold)
  elif command -v pkg >/dev/null 2>&1; then
    installer=(env DEBIAN_FRONTEND=noninteractive DEBIAN_PRIORITY=critical
      DPKG_OPTIONS="--force-confdef --force-confold" pkg)
    installer_options=(-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
  else
    warn "缺少 pkg/apt，无法安装 Termux Node/npm。"
    return 1
  fi

  run_logged run_with_optional_timeout "$timeout_seconds" "${installer[@]}" update -y "${installer_options[@]}"
  if run_logged run_with_optional_timeout "$timeout_seconds" "${installer[@]}" install -y "${installer_options[@]}" nodejs-lts python make clang pkg-config git; then
    return 0
  fi
  warn "nodejs-lts 安装失败，fallback 到 Termux nodejs；最终仍要求 Node.js major >= 24。"
  run_logged run_with_optional_timeout "$timeout_seconds" "${installer[@]}" install -y "${installer_options[@]}" nodejs python make clang pkg-config git
}

configure_npm_prefix() {
  local npm_prefix="${NPM_CONFIG_PREFIX:-$HOME/.npm-global}"
  local npm_registry="${NPM_CONFIG_REGISTRY:-${NPM_REGISTRY:-https://registry.npmjs.org/}}"
  local profile_file start_marker end_marker

  mkdir -p "$npm_prefix/bin" "$HOME/.local/bin"
  export NPM_CONFIG_PREFIX="$npm_prefix"
  export PATH="$npm_prefix/bin:$HOME/.local/bin:${PREFIX:-/data/data/com.termux/files/usr}/bin:$PATH"

  npm config set prefix "$npm_prefix" >/dev/null
  npm config set registry "$npm_registry" >/dev/null

  start_marker="# >>> openhouse termux node >>>"
  end_marker="# <<< openhouse termux node <<<"
  for profile_file in "$HOME/.profile" "$HOME/.bashrc"; do
    mkdir -p "$(dirname "$profile_file")"
    if [ -f "$profile_file" ] && grep -Fq "$start_marker" "$profile_file"; then
      continue
    fi
    {
      printf '\n%s\n' "$start_marker"
      printf 'export NPM_CONFIG_PREFIX="${NPM_CONFIG_PREFIX:-$HOME/.npm-global}"\n'
      printf 'export PATH="$NPM_CONFIG_PREFIX/bin:$HOME/.local/bin:${PREFIX:-/data/data/com.termux/files/usr}/bin:$PATH"\n'
      printf '%s\n' "$end_marker"
    } >> "$profile_file"
  done
}

verify_node_runtime() {
  local major
  command -v node >/dev/null 2>&1 || {
    warn "node 仍不可用，请检查 Termux nodejs-lts/nodejs 包安装。"
    return 1
  }
  command -v npm >/dev/null 2>&1 || {
    warn "npm 仍不可用，请检查 Termux nodejs-lts/nodejs 包安装。"
    return 1
  }
  major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || printf 0)"
  case "$major" in
    ''|*[!0-9]*)
      major=0
      ;;
  esac
  if [ "$major" -lt 24 ]; then
    warn "Termux Node.js 24 LTS 目标未满足：当前为 $(node -v 2>/dev/null || printf unknown)，需要 major >= 24。"
    return 1
  fi
  node -v
  npm -v
}

main() {
  if ! is_termux; then
    warn "Termux Node/npm 阶段只能在 Termux 外层运行。当前运行环境：$(detect_smallphoneai_runtime)"
    exit 2
  fi
  if command -v smallphoneai_log_retry_profile >/dev/null 2>&1; then
    smallphoneai_log_retry_profile '[SmallPhoneAI]'
  fi

  log "正在安装 Termux native Node.js 24 LTS/npm；Termux 使用 pkg/apt，Ubuntu 使用自己的 Node 24 LTS 安装阶段；不下载 nodejs.org Linux/glibc tarball。"
  install_termux_node_packages
  configure_npm_prefix
  verify_node_runtime
  log "Termux Node.js 24 LTS/npm 阶段已完成。"
}

main "$@"
