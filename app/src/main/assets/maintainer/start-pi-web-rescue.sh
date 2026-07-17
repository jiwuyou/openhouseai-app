set -euo pipefail

bootstrap="${SMALLPHONEAI_BOOTSTRAP:-$HOME/.smallphoneai-bootstrap/bootstrap.sh}"
[ -f "$bootstrap" ] || { log "未找到 APK 内置 SmallPhoneAI bootstrap：$bootstrap"; exit 1; }

log "启动并验证独立 pi-web 紧急救援入口 30142。"
run_logged env \
  OPENHOUSE_PI_WEB_RESCUE_PORT=30142 \
  bash "$bootstrap" start-pi-web-rescue
