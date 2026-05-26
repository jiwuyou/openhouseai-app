BOOTSTRAP_URL="${BOOTSTRAP_URL:-__BOOTSTRAP_URL__}"
if [ "$BOOTSTRAP_URL" = "__BOOTSTRAP_URL__" ] || [ -z "$BOOTSTRAP_URL" ]; then
  BOOTSTRAP_URL="https://raw.githubusercontent.com/jiwuyou/openhouse-bootstrap/main/bootstrap.sh"
fi
REQUIRED_COMPONENT_TARGETS="${OPENHOUSE_REQUIRED_COMPONENT_TARGETS:-__REQUIRED_COMPONENT_TARGETS__}"
OPENHOUSE_WEB_PORT="${OPENHOUSE_WEB_PORT:-__LOCAL_MAINTENANCE_WEB_PORT__}"
if [ "$OPENHOUSE_WEB_PORT" = "__LOCAL_MAINTENANCE_WEB_PORT__" ] || [ -z "$OPENHOUSE_WEB_PORT" ]; then
  OPENHOUSE_WEB_PORT="38423"
fi

ensure_curl() {
  if command -v curl >/dev/null 2>&1 && curl --version >/dev/null 2>&1; then
    return 0
  fi

  log "正在更新 Termux 包索引并修复 curl 网络依赖。"
  if command -v pkg >/dev/null 2>&1; then
    run_logged pkg update -y || true
    run_logged pkg install -y curl libcurl libngtcp2 libnghttp2 openssl ca-certificates || true
  fi

  if ! curl --version >/dev/null 2>&1; then
    log "curl 修复失败，请先执行：pkg upgrade -y && pkg install -y curl libcurl libngtcp2 libnghttp2 openssl ca-certificates"
    exit 12
  fi
}

download_file() {
  local url="$1" output="$2" attempt=1
  while [ "$attempt" -le 5 ]; do
    log "下载：$url（第 $attempt 次）"
    if run_logged curl -fL --connect-timeout 20 --retry 3 --retry-delay 2 --retry-all-errors "$url" -o "$output"; then
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  return 1
}

ensure_curl
log "正在下载 OpenHouse bootstrap：$BOOTSTRAP_URL"
download_file "$BOOTSTRAP_URL" "$HOME/openhouse-bootstrap.sh"
chmod +x "$HOME/openhouse-bootstrap.sh"

if [ "$REQUIRED_COMPONENT_TARGETS" = "bootstrap" ]; then
  log "正在初始化 OpenHouse bootstrap 阶段脚本和 skills。"
  run_logged env \
    OPENHOUSE_PORT="__PORT__" \
    OPENHOUSE_WEB_PORT="$OPENHOUSE_WEB_PORT" \
    bash "$HOME/openhouse-bootstrap.sh" check
  exit 0
fi

if [ -n "$REQUIRED_COMPONENT_TARGETS" ]; then
  log "正在安装 OpenHouse 组件：$REQUIRED_COMPONENT_TARGETS"
else
  log "正在安装 OpenHouse 必要组件。"
fi

doc_components=0
case ",$REQUIRED_COMPONENT_TARGETS," in
  *,openhouse-app-guide-site,*|*,openhouse-docs,*)
    doc_components=1
    ;;
esac

run_ubuntu_logged env \
  OPENHOUSE_PORT="__PORT__" \
  OPENHOUSE_WEB_PORT="$OPENHOUSE_WEB_PORT" \
  OPENHOUSE_REQUIRED_COMPONENT_TARGETS="$REQUIRED_COMPONENT_TARGETS" \
  OPENHOUSE_INSTALL_DOC_COMPONENTS="${OPENHOUSE_INSTALL_DOC_COMPONENTS:-$doc_components}" \
  bash "$HOME/openhouse-bootstrap.sh" required-components
