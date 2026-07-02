#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_openhouse-postinstall-common.sh
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"

oh_log "开始后置安装/检查 Codex CLI。"
oh_run_bootstrap node
oh_run_bootstrap codex

oh_log "Codex CLI 检查结果："
oh_check_tool_version codex "codex --version"

cat <<'EOF'

Codex CLI 已完成后置安装入口检查。
使用前请根据需要执行 codex login，或按 /root/openhouse/docs/MODEL_API_SETUP.md 配置本机 API key。
EOF
oh_next_docs
