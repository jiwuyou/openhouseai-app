set -euo pipefail

bootstrap="${SMALLPHONEAI_BOOTSTRAP:-$HOME/.smallphoneai-bootstrap/bootstrap.sh}"
[ -f "$bootstrap" ] || { log "未找到 APK 内置 SmallPhoneAI bootstrap：$bootstrap"; exit 1; }
payload_dir="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}"
[ -d "$payload_dir" ] || { log "未找到 APK 内置 payload：$payload_dir"; exit 1; }
if [ ! -f "$payload_dir/pi-web.tar" ]; then
  log "当前精简 APK 不内置 pi-web，跳过离线安装。"
  exit 0
fi

log "安装并检查 pi-web standalone runtime；本阶段不启动或依赖 service-manager，也不注册服务。"
run_logged env \
  OPENHOUSE_PI_RUNTIME=termux \
  SMALLPHONEAI_PI_RUNTIME=termux \
  SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle \
  SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE=0 \
  SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$payload_dir" \
  SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="$payload_dir" \
  bash "$bootstrap" install-pi-web
