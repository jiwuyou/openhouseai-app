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

run_environment_probe

if ! is_termux; then
  log "默认进入 Ubuntu 需要写入 Termux shell 启动文件，请在 Termux 外层运行。当前运行环境：$(detect_smallphoneai_runtime)"
  exit 2
fi

if ! command -v proot-distro >/dev/null 2>&1 || ! proot-distro login ubuntu -- true >/dev/null 2>&1; then
  log "Ubuntu 不可用，请先运行：bash bootstrap.sh ubuntu"
  exit 3
fi

SMALLPHONEAI_DIR="$HOME/.smallphoneai"
ENTRY_MODE_FILE="$SMALLPHONEAI_DIR/entry-mode"
ENTRY_SCRIPT="$SMALLPHONEAI_DIR/entry.sh"

write_entry_script() {
  mkdir -p "$SMALLPHONEAI_DIR"
  cat > "$ENTRY_SCRIPT" <<'EOF'
# SmallPhoneAI startup entry. This file is managed by SmallPhoneAI.
if [ "${SMALLPHONEAI_ENTRY_STARTED:-0}" = "1" ]; then
  return 0 2>/dev/null || exit 0
fi
export SMALLPHONEAI_ENTRY_STARTED=1

case $- in
  *i*) ;;
  *) return 0 2>/dev/null || exit 0 ;;
esac

MODE_FILE="$HOME/.smallphoneai/entry-mode"
MODE="termux"
if [ -f "$MODE_FILE" ]; then
  MODE="$(tr -d '[:space:]' < "$MODE_FILE")"
fi

if [ "$MODE" = "ubuntu" ] && [ -z "${SMALLPHONEAI_NO_AUTO_UBUNTU:-}" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    printf '[SmallPhoneAI] 正在进入 Ubuntu。退出 Ubuntu 后会回到 Termux。\n'
    SMALLPHONEAI_NO_AUTO_UBUNTU=1 proot-distro login ubuntu -- bash -l
  else
    printf '[SmallPhoneAI] Ubuntu 尚不可用，已停留在 Termux。请先完成 Ubuntu 安装阶段。\n'
  fi
fi
EOF
  chmod 700 "$ENTRY_SCRIPT"
}

ensure_profile_hook() {
  local profile_file="$1"
  touch "$profile_file"
  if grep -Fq '# SmallPhoneAI startup entry' "$profile_file"; then
    return 0
  fi
  {
    printf '\n# SmallPhoneAI startup entry\n'
    printf 'if [ -f "$HOME/.smallphoneai/entry.sh" ]; then . "$HOME/.smallphoneai/entry.sh"; fi\n'
  } >> "$profile_file"
}

mkdir -p "$SMALLPHONEAI_DIR"
printf 'ubuntu\n' > "$ENTRY_MODE_FILE"
write_entry_script
ensure_profile_hook "$HOME/.bashrc"
ensure_profile_hook "$HOME/.profile"

log "启动入口已设置：打开 Termux 后直接进入 Ubuntu。"
