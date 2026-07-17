#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

find_rescue_script() {
  local bootstrap_root candidate
  bootstrap_root="${SMALLPHONEAI_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
  for candidate in \
    "${OPENHOUSEAI_MAINTAINER_DIR:-}/pi-web-rescue.sh" \
    "${SMALLPHONEAI_MAINTAINER_DIR:-}/pi-web-rescue.sh" \
    "$bootstrap_root/../maintainer/pi-web-rescue.sh" \
    "$HOME/.smallphoneai-bootstrap/maintainer/pi-web-rescue.sh" \
    "$HOME/.smallphoneai-bootstrap/apk-assets/maintainer/pi-web-rescue.sh"; do
    [ "$candidate" != "/pi-web-rescue.sh" ] || continue
    if [ -f "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

run_rescue_action() (
  local action="$1"
  local rescue_script="$2"
  export OPENHOUSE_PI_WEB_RESCUE_ACTION="$action"
  export OPENHOUSE_PI_WEB_RESCUE_PORT="${OPENHOUSE_PI_WEB_RESCUE_PORT:-30142}"
  export OPENHOUSE_PI_WEB_DIR="${OPENHOUSE_PI_WEB_DIR:-$HOME/smallphoneai-repos/pi-web}"
  # pi-web-rescue.sh is also embedded by Android and intentionally reuses the
  # caller's log function.
  # shellcheck source=/dev/null
  . "$rescue_script"
)

rescue_script="$(find_rescue_script || true)"
if [ -z "$rescue_script" ]; then
  warn "未找到 APK 内置 pi-web-rescue.sh。"
  exit 1
fi

log "启动不依赖 service-manager 的 pi-web 紧急救援入口：127.0.0.1:${OPENHOUSE_PI_WEB_RESCUE_PORT:-30142}"
run_rescue_action start "$rescue_script"
run_rescue_action check "$rescue_script"
log "pi-web 紧急救援入口已通过 HTTP 检查。"
