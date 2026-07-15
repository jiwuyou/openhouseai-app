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

export PATH="$HOME/.npm-global/bin:${PREFIX:-/data/data/com.termux/files/usr}/bin:/system/bin:${PATH:-}"
if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  log "Termux Node.js 24 LTS/npm 尚未就绪，请先执行固定的“安装 Termux Node.js 24 LTS”阶段。"
  exit 2
fi
node_major="$(node -p "process.versions.node.split('.')[0]" 2>/dev/null || printf 0)"
if [ "${node_major:-0}" -lt 24 ]; then
  log "Termux Node.js 版本过旧：$(node -v 2>/dev/null || printf unknown)，pi-agent/pi-web 要求 Node.js 24 LTS。"
  exit 2
fi

run_bootstrap_component_target() {
  target="$1"
  label="$2"

  log "$label"
  run_logged env \
    OPENHOUSE_PI_RUNTIME="${OPENHOUSE_PI_RUNTIME:-termux}" \
    SMALLPHONEAI_PI_RUNTIME="${SMALLPHONEAI_PI_RUNTIME:-termux}" \
    OPENHOUSE_PI_NODE_RUNTIME="${OPENHOUSE_PI_NODE_RUNTIME:-termux}" \
    SMALLPHONEAI_RUNTIME_COMPONENTS_IN_UBUNTU=1 \
    SMALLPHONEAI_COMPONENT_SOURCE_MODE=bundle \
    SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE=0 \
    SMALLPHONEAI_COMPONENTS_AUTO_CLONE=0 \
    SMALLPHONEAI_COMPONENTS_STRICT="${SMALLPHONEAI_COMPONENTS_STRICT:-1}" \
    SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$payload_dir" \
    SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="$payload_dir" \
    SMALLPHONEAI_COMPONENT_TARGETS="$target" \
    SMALLPHONEAI_FORCE_PAYLOAD_REFRESH="${SMALLPHONEAI_FORCE_PAYLOAD_REFRESH:-0}" \
    bash "$bootstrap" components
}

normalize_component_target() {
  case "$1" in
    pi|pi-agent)
      printf 'pi-agent'
      ;;
    openhouse-pi-web|pi-web)
      printf 'pi-web'
      ;;
    openhouse-web|web-shell|openhouse-shell)
      printf 'openhouse-web'
      ;;
    sm|service-manager)
      printf 'service-manager'
      ;;
    *)
      printf '%s' "$1"
      ;;
  esac
}

component_install_label() {
  case "$1" in
    service-manager)
      printf '正在优先确保 Termux native service-manager 已安装、可运行，并可接受服务注册。'
      ;;
    pi-agent)
      printf '正在安装并立即注册 pi-agent；完成后再处理 pi-web。'
      ;;
    pi-web)
      printf '正在安装并立即注册 pi-web。'
      ;;
    openhouse-web)
      printf '正在安装并注册 OpenHouse Web 系统壳。'
      ;;
    *)
      printf '正在安装并注册组件：%s' "$1"
      ;;
  esac
}

log "正在从 APK 内置 payload 安装 Termux pi 主线运行组件。"
log "默认顺序：wuyou -> service-manager -> pi-agent -> pi-web -> openhouse-web；OpenHouse Web 最后安装。"

targets="${SMALLPHONEAI_COMPONENT_TARGETS:-wuyou,service-manager,pi-agent,pi-web,openhouse-web}"
while [ -n "$targets" ]; do
  case "$targets" in
    *,*)
      raw_target="${targets%%,*}"
      targets="${targets#*,}"
      ;;
    *)
      raw_target="$targets"
      targets=""
      ;;
  esac
  normalized_target="$(normalize_component_target "$(printf '%s' "$raw_target" | tr -d '[:space:]')")"
  [ -n "$normalized_target" ] || continue
  run_bootstrap_component_target "$normalized_target" "$(component_install_label "$normalized_target")"
done
