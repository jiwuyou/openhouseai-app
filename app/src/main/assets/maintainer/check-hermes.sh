require_ubuntu

log "正在检查 Hermes / Hermes WebUI。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:$PATH"
dir="${SMALLPHONEAI_HERMES_DIR:-$HOME/smallphoneai-repos/hermes}"
if [ ! -f "$dir/scripts/check.sh" ]; then
  echo "Hermes 尚未安装或缺少检查脚本：$dir/scripts/check.sh" >&2
  exit 3
fi
cd "$dir"
HERMES_WEBUI_HOST="${HERMES_WEBUI_HOST:-127.0.0.1}" HERMES_WEBUI_PORT="${HERMES_WEBUI_PORT:-23084}" ./scripts/check.sh
'
