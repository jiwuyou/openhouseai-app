#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
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
    "$probe" || true
  else
    log "环境探测命令不存在，使用内置探测逻辑。"
  fi
  log "当前运行环境：$(detect_smallphoneai_runtime)"
}

run_environment_probe

if [ -z "${PREFIX:-}" ] || [ ! -d "${PREFIX:-}/bin" ] || [ ! -d "/data/data/com.termux/files" ]; then
  log "请在官方 Termux 内运行。"
  exit 1
fi

log "Termux PREFIX: $PREFIX"
log "HOME: $HOME"

if [ ! -d "$HOME/storage" ]; then
  log "提示：如需访问共享存储，请手动运行 termux-setup-storage 并在系统弹窗中授权。"
else
  log "共享存储入口已存在：$HOME/storage"
fi

if command -v termux-info >/dev/null 2>&1; then
  termux-info || true
else
  log "termux-info 不存在，稍后准备阶段会安装基础包。"
fi

log "环境检查完成。"
