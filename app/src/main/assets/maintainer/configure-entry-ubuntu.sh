OPENHOUSEAI_DIR="$HOME/.openhouseai"
ENTRY_MODE_FILE="$OPENHOUSEAI_DIR/entry-mode"
ENTRY_SCRIPT="$OPENHOUSEAI_DIR/entry.sh"

write_entry_script() {
  mkdir -p "$OPENHOUSEAI_DIR"
  cat > "$ENTRY_SCRIPT" <<'EOF'
# OpenHouseAI startup entry. This file is managed by OpenHouseAI.
if [ "${OPENHOUSEAI_ENTRY_STARTED:-0}" = "1" ]; then
  return 0 2>/dev/null || exit 0
fi
export OPENHOUSEAI_ENTRY_STARTED=1

case $- in
  *i*) ;;
  *) return 0 2>/dev/null || exit 0 ;;
esac

MODE_FILE="$HOME/.openhouseai/entry-mode"
MODE="termux"
if [ -f "$MODE_FILE" ]; then
  MODE="$(tr -d '[:space:]' < "$MODE_FILE")"
fi

if [ "$MODE" = "ubuntu" ] \
  && [ -z "${OPENHOUSEAI_NO_AUTO_UBUNTU:-}" ] \
  && [ -z "${TERMUX_NO_AUTO_UBUNTU:-}" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    printf '[OpenHouseAI] 正在进入 Ubuntu。退出 Ubuntu 后会回到 Termux。\n'
    OPENHOUSEAI_NO_AUTO_UBUNTU=1 proot-distro login ubuntu -- bash -l
  else
    printf '[OpenHouseAI] Ubuntu 尚不可用，已停留在 Termux。请先完成 Ubuntu 安装阶段。\n'
  fi
fi
EOF
  chmod 700 "$ENTRY_SCRIPT"
}

ensure_profile_hook() {
  local profile_file="$1"
  touch "$profile_file"
  if grep -Fq '# OpenHouseAI startup entry' "$profile_file"; then
    return 0
  fi
  {
    printf '\n# OpenHouseAI startup entry\n'
    printf 'if [ -f "$HOME/.openhouseai/entry.sh" ]; then . "$HOME/.openhouseai/entry.sh"; fi\n'
  } >> "$profile_file"
}

mkdir -p "$OPENHOUSEAI_DIR"
printf 'ubuntu\n' > "$ENTRY_MODE_FILE"
write_entry_script
ensure_profile_hook "$HOME/.bashrc"
ensure_profile_hook "$HOME/.profile"

log "启动入口已设置：打开 Termux 后直接进入 Ubuntu。"
