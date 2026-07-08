#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_openhouse-postinstall-common.sh
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"

oh_log "开始安装/检查 cc-switch provider 配置工具。"
oh_run_bootstrap cc-switch

oh_log "cc-switch 检查结果："
oh_check_tool_version cc-switch "cc-switch --version"

cat <<'EOF'

cc-switch 已完成安装入口检查。
它只用于模型 provider 配置、检测和切换，不是长期服务，也不替代 pi-agent 或 service-manager。
调用前请阅读 /root/openhouse/docs/cc-switch.md；涉及 key/token 的输出必须脱敏。
EOF
oh_next_docs
