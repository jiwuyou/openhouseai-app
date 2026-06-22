find_smallphoneai_bootstrap() {
  if [ -n "${SMALLPHONEAI_BOOTSTRAP:-}" ] && [ -f "$SMALLPHONEAI_BOOTSTRAP" ]; then
    printf '%s\n' "$SMALLPHONEAI_BOOTSTRAP"
    return 0
  fi

  for candidate in \
    "$HOME/.smallphoneai-bootstrap/bootstrap.sh" \
    "$HOME/smallphoneai-bootstrap/bootstrap.sh" \
    "$HOME/openhouseai-bootstrap/bootstrap.sh" \
    "/data/data/com.termux/files/home/.smallphoneai-bootstrap/bootstrap.sh"; do
    if [ -f "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

bootstrap="$(find_smallphoneai_bootstrap || true)"
if [ -z "$bootstrap" ]; then
  log "未找到 APK 内置 SmallPhoneAI bootstrap，请重新安装或修复应用。"
  exit 1
fi

payload_dir="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}"
if [ ! -d "$payload_dir" ]; then
  log "未找到 APK 内置 SmallPhoneAI payload：$payload_dir"
  exit 1
fi

log "正在从 APK 内置 payload 安装 Hermes。"
run_logged env \
  SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle \
  SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE=0 \
  SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$payload_dir" \
  SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="$payload_dir" \
  HERMES_WEBUI_HOST="${HERMES_WEBUI_HOST:-127.0.0.1}" \
  HERMES_WEBUI_PORT="${HERMES_WEBUI_PORT:-23084}" \
  bash "$bootstrap" hermes
