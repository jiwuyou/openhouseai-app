set -euo pipefail

bootstrap="${SMALLPHONEAI_BOOTSTRAP:-$HOME/.smallphoneai-bootstrap/bootstrap.sh}"
[ -f "$bootstrap" ] || { log "未找到 APK 内置 SmallPhoneAI bootstrap：$bootstrap"; exit 1; }
payload_dir="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}"
[ -d "$payload_dir" ] || { log "未找到 APK 内置 payload：$payload_dir"; exit 1; }

log "安装并启动 Termux native service-manager，等待健康与认证接口就绪。"
run_logged env \
  SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle \
  SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE=0 \
  SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$payload_dir" \
  SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="$payload_dir" \
  bash "$bootstrap" install-service-manager
