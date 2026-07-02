#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_openhouse-postinstall-common.sh
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"

oh_log "开始后置安装/检查 CloudCLI / ClaudeCodeUI。"
oh_run_bootstrap node
oh_run_bootstrap cloudcli

oh_log "CloudCLI 检查结果："
oh_check_tool_version cloudcli "cloudcli version || cloudcli --version"

if oh_run_bootstrap sync-registry; then
  oh_log "服务注册表已同步。"
else
  oh_warn "服务注册表同步失败。CloudCLI 命令可能已安装，但 cc/codex 入口可能仍需修复。"
fi

if oh_run_bootstrap start; then
  oh_log "OpenHouse 基础运行栈已启动或刷新。"
else
  oh_warn "启动基础运行栈失败。请查看 service-manager 和 bootstrap 日志。"
fi

cat <<'EOF'

CloudCLI / ClaudeCodeUI 已完成后置安装入口检查。
默认本机账号密码是 admin / 123456，仅限本机首次使用，后续可修改。
请让 pi-agent 按 /root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md 配置 URL、key/token 和 model id，并在 cc/codex 页面中测通 Claude Code。
EOF
oh_next_docs
