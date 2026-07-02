require_ubuntu

TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"
PRODUCT_DOC_DIR="$TERMUX_HOME/openhouseai-docs"
OFFICIAL_DOC_DIR="$PRODUCT_DOC_DIR/official"
AGENT_NOTES_DIR="$PRODUCT_DOC_DIR/agent-notes"

log "正在创建官方文档目录 $OFFICIAL_DOC_DIR"
mkdir -p "$OFFICIAL_DOC_DIR" "$AGENT_NOTES_DIR"

log "正在同步内置官方文档到 $OFFICIAL_DOC_DIR"
__BUNDLED_OFFICIAL_DOCS__

log "官方文档已同步完成。"
run_ubuntu_logged bash -lc 'set -euo pipefail; mkdir -p "$HOME/openhouseai-docs" "$HOME/smallphoneai-docs"; ln -sfn /data/data/com.termux/files/home/openhouseai-docs/official "$HOME/openhouseai-docs/official"; ln -sfn /data/data/com.termux/files/home/openhouseai-docs/agent-notes "$HOME/openhouseai-docs/agent-notes"; ln -sfn /data/data/com.termux/files/home/openhouseai-docs/official "$HOME/smallphoneai-docs/official"; ln -sfn /data/data/com.termux/files/home/openhouseai-docs/agent-notes "$HOME/smallphoneai-docs/agent-notes"; printf "%s\n" "$HOME/openhouseai-docs/official"'

log "官方文档同步阶段已完成。"
