PRIMARY_URL="__OPENCODE_INSTALL_PRIMARY_URL__"
PRIMARY_LABEL="__OPENCODE_INSTALL_PRIMARY_LABEL__"
SECONDARY_URL="__OPENCODE_INSTALL_SECONDARY_URL__"
SECONDARY_LABEL="__OPENCODE_INSTALL_SECONDARY_LABEL__"
ALLOW_FALLBACK="__OPENCODE_INSTALL_ALLOW_FALLBACK__"
OPENHOUSE_OPENCODE_VERSION="${OPENHOUSE_OPENCODE_VERSION:-0.0.55}"

require_ubuntu

resolve_opencode_install_url() {
  local default_url="$1"
  if [ -n "${OPENHOUSE_OPENCODE_INSTALL_URL:-}" ]; then
    printf '%s\n' "$OPENHOUSE_OPENCODE_INSTALL_URL"
    return 0
  fi
  if [ -n "${OPENCODE_INSTALL_URL:-}" ]; then
    printf '%s\n' "$OPENCODE_INSTALL_URL"
    return 0
  fi
  printf '%s\n' "$default_url"
}

install_opencode_from() {
  local source_label="$1"
  local source_url="$2"
  local install_url

  install_url="$(resolve_opencode_install_url "$source_url")"

log "正在通过 $source_label 安装 OpenCode（如尚未安装，安装源：$install_url）"
  run_ubuntu_logged env \
    OPENHOUSE_OPENCODE_INSTALL_URL="$install_url" \
    OPENCODE_INSTALL_URL="$install_url" \
    OPENHOUSE_OPENCODE_VERSION="$OPENHOUSE_OPENCODE_VERSION" \
    bash -lc 'set -euo pipefail; export PATH="$HOME/.opencode/bin:$HOME/.local/bin:$PATH"; INSTALL_URL="${OPENHOUSE_OPENCODE_INSTALL_URL:-${OPENCODE_INSTALL_URL:-https://opencode.ai/install}}"; if command -v opencode >/dev/null 2>&1 || test -x "$HOME/.opencode/bin/opencode"; then echo "OpenCode 已安装。"; else curl -fsSL "$INSTALL_URL" | VERSION="$OPENHOUSE_OPENCODE_VERSION" bash; fi; export PATH="$HOME/.opencode/bin:$HOME/.local/bin:$PATH"; if command -v opencode >/dev/null 2>&1; then command -v opencode; elif test -x "$HOME/.opencode/bin/opencode"; then echo "$HOME/.opencode/bin/opencode"; else echo "OpenCode 安装后仍未找到可执行文件。" >&2; exit 4; fi'
}

if install_opencode_from "$PRIMARY_LABEL" "$PRIMARY_URL"; then
  log "OpenCode 主下载源安装已完成。"
else
  primary_status=$?
  if [ "$ALLOW_FALLBACK" = "1" ] && [ "$SECONDARY_URL" != "$PRIMARY_URL" ]; then
    log "主下载源失败，正在切换到 $SECONDARY_LABEL 重试。"
    install_opencode_from "$SECONDARY_LABEL" "$SECONDARY_URL" || exit $?
  else
    exit "$primary_status"
  fi
fi

log "正在 Ubuntu 主目录内写入产品路径辅助文件"
run_ubuntu_logged bash -lc 'set -euo pipefail; mkdir -p "$HOME/openhouseai-links"; printf "%s\n" "/data/data/com.termux/files/home/openhouseai-docs" > "$HOME/openhouseai-links/docs-path.txt"; printf "%s\n" "/data/data/com.termux/files/home/workspace" > "$HOME/openhouseai-links/workspace-path.txt"; echo "文档路径：$(cat "$HOME/openhouseai-links/docs-path.txt")"; echo "工作区路径：$(cat "$HOME/openhouseai-links/workspace-path.txt")"'

log "OpenCode 安装阶段已完成。"
