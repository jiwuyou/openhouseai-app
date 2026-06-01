TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_BIN_DIR="$TERMUX_PREFIX/bin"
ENV_PROBE_COMMAND="$TERMUX_BIN_DIR/openhouseai-env-probe"
DOC_DIR="$TERMUX_HOME/openhouseai-docs"
WORKSPACE_DIR="$TERMUX_HOME/workspace"
TERMUX_CONFIG_DIR="$TERMUX_HOME/.termux"
TERMUX_PROPERTIES_FILE="$TERMUX_CONFIG_DIR/termux.properties"

install_env_probe_cli() {
  if [ -x "$ENV_PROBE_COMMAND" ]; then
    log "环境探测 CLI 已存在：$ENV_PROBE_COMMAND"
    return 0
  fi

  mkdir -p "$TERMUX_BIN_DIR"
  cat > "$ENV_PROBE_COMMAND" <<'EOF'
#!/data/data/com.termux/files/usr/bin/env bash
set -euo pipefail

INSTALL_SIDE="termux"

detect_runtime() {
  if [ -r /etc/os-release ] && grep -qi 'ubuntu' /etc/os-release; then
    printf 'ubuntu'
    return 0
  fi

  if [ -n "${TERMUX_VERSION:-}" ] || [ "${PREFIX:-}" = "/data/data/com.termux/files/usr" ]; then
    printf 'termux'
    return 0
  fi

  printf 'unknown'
}

detect_ubuntu_rootfs() {
  case "$(detect_runtime)" in
    ubuntu)
      printf 'installed'
      ;;
    termux)
      if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
        printf 'installed'
      else
        printf 'missing'
      fi
      ;;
    *)
      printf 'unknown'
      ;;
  esac
}

main() {
  printf 'OPENHOUSEAI_INSTALL_SIDE=%s\n' "$INSTALL_SIDE"
  printf 'OPENHOUSEAI_RUNTIME=%s\n' "$(detect_runtime)"
  printf 'OPENHOUSEAI_UBUNTU_ROOTFS=%s\n' "$(detect_ubuntu_rootfs)"
}

main "$@"
EOF
  chmod 755 "$ENV_PROBE_COMMAND"
  log "已注入环境探测 CLI：$ENV_PROBE_COMMAND"
}

log "正在确保 Termux 配置目录存在。"
mkdir -p "$DOC_DIR" "$WORKSPACE_DIR" "$TERMUX_CONFIG_DIR"
chmod 700 "$DOC_DIR" "$WORKSPACE_DIR" "$TERMUX_CONFIG_DIR" || true

log "正在在 $TERMUX_PROPERTIES_FILE 中启用 allow-external-apps"
touch "$TERMUX_PROPERTIES_FILE"
if grep -q '^[[:space:]]*allow-external-apps' "$TERMUX_PROPERTIES_FILE"; then
  sed -i 's/^[[:space:]]*allow-external-apps[[:space:]]*=.*/allow-external-apps = true/' "$TERMUX_PROPERTIES_FILE"
else
  printf '\nallow-external-apps = true\n' >> "$TERMUX_PROPERTIES_FILE"
fi

log "正在将 OpenHouseAI 文档写入 $DOC_DIR"
cat > "$DOC_DIR/README.md" <<'EOF'
# OpenHouseAI 文档

本目录用于保存 OpenHouseAI 文档和本机笔记。

正式说明会由“同步官方文档”阶段写入 `official/`：
- `official/ENVIRONMENT.md`
- `official/MODEL_API_SETUP.md`
EOF

cat > "$DOC_DIR/ENVIRONMENT.md" <<'EOF'
# 运行环境说明

OpenHouseAI 运行在 Android Termux 中，并通过 `proot-distro` 提供 Ubuntu。OpenCode、Codex CLI、Claude Code 和 Reasonix 安装在 Ubuntu 内。

工作区路径：`/data/data/com.termux/files/home/workspace`
EOF

cat > "$DOC_DIR/MODEL_API_SETUP.md" <<'EOF'
# Codex、Claude Code 和 Reasonix 登录/API 配置

正式配置说明会由“同步官方文档”阶段写入 `official/MODEL_API_SETUP.md`。

不要把 API key 写入 git 仓库、共享文档、APK 资源、日志或截图。
EOF

install_env_probe_cli

log "文档路径：$DOC_DIR"
log "工作区路径：$WORKSPACE_DIR"
log "配置文件：$TERMUX_PROPERTIES_FILE"
