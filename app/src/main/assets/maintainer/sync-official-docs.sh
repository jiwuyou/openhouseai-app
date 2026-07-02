require_ubuntu

TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
if is_current_ubuntu; then
  TERMUX_HOME="/data/data/com.termux/files/home"
fi
PRODUCT_DOC_DIR="$TERMUX_HOME/openhouseai-docs"
LEGACY_DOC_DIR="$TERMUX_HOME/smallphoneai-docs"
OPENHOUSE_DOC_LINK_ROOT="$TERMUX_HOME/openhouse"
OFFICIAL_DOC_DIR="$PRODUCT_DOC_DIR/official"
AGENT_NOTES_DIR="$PRODUCT_DOC_DIR/agent-notes"

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

log "正在创建官方文档目录 $OFFICIAL_DOC_DIR"
mkdir -p "$OFFICIAL_DOC_DIR" "$AGENT_NOTES_DIR" "$LEGACY_DOC_DIR" "$OPENHOUSE_DOC_LINK_ROOT"

log "正在同步内置官方文档到 $OFFICIAL_DOC_DIR"
__BUNDLED_OFFICIAL_DOCS__

ensure_symlink "$OFFICIAL_DOC_DIR" "$OPENHOUSE_DOC_LINK_ROOT/docs"
ensure_symlink "$OFFICIAL_DOC_DIR" "$LEGACY_DOC_DIR/official"
ensure_symlink "$AGENT_NOTES_DIR" "$LEGACY_DOC_DIR/agent-notes"

log "官方文档已同步完成。"
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
mkdir -p "$HOME/openhouseai-docs" "$HOME/smallphoneai-docs" "$HOME/openhouse"
ensure_symlink "$termux_doc_root/official" "$HOME/openhouseai-docs/official"
ensure_symlink "$termux_doc_root/agent-notes" "$HOME/openhouseai-docs/agent-notes"
ensure_symlink "$termux_doc_root/official" "$HOME/smallphoneai-docs/official"
ensure_symlink "$termux_doc_root/agent-notes" "$HOME/smallphoneai-docs/agent-notes"
ensure_symlink "$termux_doc_root/official" "$HOME/openhouse/docs"
printf "%s\n" "$HOME/openhouse/docs"
printf "%s\n" "$HOME/openhouseai-docs/official"
'

log "官方文档同步阶段已完成。"
