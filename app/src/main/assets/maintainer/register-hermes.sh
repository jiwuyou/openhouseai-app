require_ubuntu

log "正在刷新 Hermes service-manager 注册。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:$PATH"
dir="${SMALLPHONEAI_HERMES_DIR:-$HOME/smallphoneai-repos/hermes}"
if [ ! -f "$dir/scripts/register-service.sh" ]; then
  echo "Hermes 尚未安装或缺少注册脚本：$dir/scripts/register-service.sh" >&2
  exit 3
fi
cd "$dir"
HERMES_WEBUI_HOST="${HERMES_WEBUI_HOST:-127.0.0.1}" HERMES_WEBUI_PORT="${HERMES_WEBUI_PORT:-23084}" ./scripts/register-service.sh
'
