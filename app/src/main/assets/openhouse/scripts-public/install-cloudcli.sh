#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_openhouse-postinstall-common.sh
. "$SCRIPT_DIR/_openhouse-postinstall-common.sh"

oh_log "开始后置安装/检查 CloudCLI / ClaudeCodeUI。"
oh_run_bootstrap node
oh_run_bootstrap claude-code
oh_log "检查 CloudCLI 依赖的 Claude Code native path。"
oh_ensure_claude_native_path
oh_run_bootstrap cloudcli

oh_log "CloudCLI 检查结果："
oh_check_tool_version cloudcli "cloudcli version || cloudcli --version"
oh_log "Claude Code native path 检查结果："
oh_ensure_claude_native_path

start_cloudcli_service() {
  oh_run_ubuntu_bash 'set -euo pipefail
SM_CONFIG="$HOME/.config/openhouseai/service-manager/config.json"
[ -r "$SM_CONFIG" ] || { echo "service-manager config not found: $SM_CONFIG" >&2; exit 1; }
SM_TOKEN="$(sed -n '\''s/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'\'' "$SM_CONFIG" | head -n 1)"
[ -n "$SM_TOKEN" ] || { echo "service-manager token not found" >&2; exit 1; }
SM_ADDR="$(sed -n '\''s/.*"\(listen_addr\|listenAddr\|base_url\|baseUrl\|url\)"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\2/p'\'' "$SM_CONFIG" | head -n 1)"
[ -n "$SM_ADDR" ] || { echo "service-manager listen address not found" >&2; exit 1; }
case "$SM_ADDR" in
  http://0.0.0.0*) SM_URL="http://127.0.0.1${SM_ADDR#http://0.0.0.0}" ;;
  https://0.0.0.0*) SM_URL="https://127.0.0.1${SM_ADDR#https://0.0.0.0}" ;;
  http://*|https://*) SM_URL="$SM_ADDR" ;;
  :*) SM_URL="http://127.0.0.1$SM_ADDR" ;;
  0.0.0.0:*) SM_URL="http://127.0.0.1:${SM_ADDR#0.0.0.0:}" ;;
  *) SM_URL="http://$SM_ADDR" ;;
esac
SM_URL="${SM_URL%/}"
curl_cfg="$(mktemp)"
trap '\''rm -f "$curl_cfg"'\'' EXIT
printf '\''header = "Authorization: Bearer %s"\n'\'' "$SM_TOKEN" > "$curl_cfg"
curl -q -fsS --max-time 5 -K "$curl_cfg" "$SM_URL/api/v1/services/cloudcli/status" >/dev/null
curl -q -fsS --max-time 10 -X POST -K "$curl_cfg" "$SM_URL/api/v1/services/cloudcli/start" >/dev/null
'
}

if oh_run_bootstrap sync-registry; then
  oh_log "服务注册表已同步。"
else
  oh_warn "服务注册表同步失败。CloudCLI 命令可能已安装，但 cc/codex 入口可能仍需修复。"
fi

if start_cloudcli_service; then
  oh_log "CloudCLI 服务启动请求已提交。"
else
  oh_warn "CloudCLI 已安装，但未能通过 service-manager 启动。请在 Android 运行控制页启动 cc/codex，或查看 /root/openhouse/docs/SERVICE_MANAGER.md。"
fi

cat <<'EOF'

CloudCLI / ClaudeCodeUI 已完成后置安装入口检查。
默认本机账号密码是 admin / 123456，仅限本机首次使用，后续可修改。
请让 pi-agent 按 /root/openhouse/docs/CLOUDCLI_CLAUDE_CODE.md 配置 URL、key/token 和 model id，并在 cc/codex 页面中测通 Claude Code。
EOF
oh_next_docs
