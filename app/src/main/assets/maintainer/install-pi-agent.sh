set -euo pipefail

bootstrap="${SMALLPHONEAI_BOOTSTRAP:-$HOME/.smallphoneai-bootstrap/bootstrap.sh}"
[ -f "$bootstrap" ] || { log "未找到 APK 内置 SmallPhoneAI bootstrap：$bootstrap"; exit 1; }
payload_dir="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}"
[ -d "$payload_dir" ] || { log "未找到 APK 内置 payload：$payload_dir"; exit 1; }

log "安装并检查嵌入官方 Pi SDK 的 WuxianPi Node 服务；保留 $HOME/.pi，本阶段不注册或启动服务。"
run_logged env \
  OPENHOUSE_PI_RUNTIME=termux \
  SMALLPHONEAI_PI_RUNTIME=termux \
  SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle \
  SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE=0 \
  SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$payload_dir" \
  SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="$payload_dir" \
  bash "$bootstrap" install-pi-agent
