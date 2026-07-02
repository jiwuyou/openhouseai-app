#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_openhouse-postinstall-common.sh
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"

oh_log "开始后置安装/检查 Claude Code。"
oh_run_bootstrap node
oh_run_bootstrap claude-code

oh_log "Claude Code 检查结果："
oh_check_tool_version claude "claude --version"

cat <<'EOF'

Claude Code 已完成后置安装入口检查。
使用官方登录请执行 claude login；使用 API key 或兼容端点时，请按 /root/openhouse/docs/MODEL_API_SETUP.md 配置。
如果目标是 CloudCLI 网页中的 Claude Code，请继续运行 /root/openhouse/scripts/install-cloudcli.sh。
EOF
oh_next_docs
