OPENHOUSE_DIR="$HOME/.openhouse"
ENTRY_MODE_FILE="$OPENHOUSE_DIR/entry-mode"
ENTRY_SCRIPT="$OPENHOUSE_DIR/entry.sh"

write_entry_script() {
  mkdir -p "$OPENHOUSE_DIR"
  cat > "$ENTRY_SCRIPT" <<'EOF'
# OpenHouse startup entry. This file is managed by OpenHouse.
if [ "${OPENHOUSE_ENTRY_STARTED:-0}" = "1" ]; then
  return 0 2>/dev/null || exit 0
fi
export OPENHOUSE_ENTRY_STARTED=1

case $- in
  *i*) ;;
  *) return 0 2>/dev/null || exit 0 ;;
esac

MODE_FILE="$HOME/.openhouse/entry-mode"
MODE="termux"
if [ -f "$MODE_FILE" ]; then
  MODE="$(tr -d '[:space:]' < "$MODE_FILE")"
fi

if [ "$MODE" = "ubuntu" ] && [ -z "${OPENHOUSE_NO_AUTO_UBUNTU:-}" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    printf '[OpenHouse] 正在进入 Ubuntu。退出 Ubuntu 后会回到 Termux。\n'
    OPENHOUSE_NO_AUTO_UBUNTU=1 proot-distro login ubuntu -- bash -l
  else
    printf '[OpenHouse] Ubuntu 尚不可用，已停留在 Termux。请先完成 Ubuntu 安装阶段。\n'
  fi
fi
EOF
  chmod 700 "$ENTRY_SCRIPT"
}

ensure_profile_hook() {
  local profile_file="$1"
  touch "$profile_file"
  if grep -Fq '# OpenHouse startup entry' "$profile_file"; then
    return 0
  fi
  {
    printf '\n# OpenHouse startup entry\n'
    printf 'if [ -f "$HOME/.openhouse/entry.sh" ]; then . "$HOME/.openhouse/entry.sh"; fi\n'
  } >> "$profile_file"
}

mkdir -p "$OPENHOUSE_DIR"
printf 'ubuntu\n' > "$ENTRY_MODE_FILE"
write_entry_script
ensure_profile_hook "$HOME/.bashrc"
ensure_profile_hook "$HOME/.profile"

log "启动入口已设置：打开 Termux 后直接进入 Ubuntu。"
