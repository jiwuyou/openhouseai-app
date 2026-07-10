set -euo pipefail

if ! declare -F log >/dev/null 2>&1; then
  log() {
    printf '[SmallPhoneAI control-plane] %s\n' "$*"
  }
fi

if ! declare -F warn >/dev/null 2>&1; then
  warn() {
    printf '[SmallPhoneAI control-plane] WARN: %s\n' "$*" >&2
  }
fi

if ! declare -F run_logged >/dev/null 2>&1; then
  run_logged() {
    "$@"
  }
fi

log "正在进入控制中枢修复入口。"

control_repair_config_dir="${OPENHOUSE_SERVICE_CONTROL_REPAIR_CONFIG_DIR:-${SMALLPHONEAI_SERVICE_CONTROL_REPAIR_CONFIG_DIR:-$HOME/.config/openhouseai/service-control}}"
control_repair_method_file="$control_repair_config_dir/repair-method"
control_repair_entry_file="$control_repair_config_dir/repair-entry"
maintainer_dir="${OPENHOUSEAI_MAINTAINER_DIR:-${SMALLPHONEAI_MAINTAINER_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/maintainer}}"

read_first_line() {
  local file="$1"
  [ -f "$file" ] || return 1
  sed -n '1{s/[[:space:]]*$//;p;q;}' "$file"
}

repair_entry="${OPENHOUSE_SERVICE_CONTROL_REPAIR_ENTRY:-${SMALLPHONEAI_SERVICE_CONTROL_REPAIR_ENTRY:-}}"
if [ -z "$repair_entry" ]; then
  repair_entry="$(read_first_line "$control_repair_entry_file" || true)"
fi

if [ -n "$repair_entry" ]; then
  if [ ! -f "$repair_entry" ]; then
    warn "配置的控制中枢修复入口不存在：$repair_entry"
    exit 2
  fi
  log "使用外部控制中枢修复入口：$repair_entry"
  run_logged bash "$repair_entry"
  exit $?
fi

repair_method="${OPENHOUSE_SERVICE_CONTROL_REPAIR_METHOD:-${SMALLPHONEAI_SERVICE_CONTROL_REPAIR_METHOD:-}}"
if [ -z "$repair_method" ]; then
  repair_method="$(read_first_line "$control_repair_method_file" || true)"
fi
repair_method="${repair_method:-termux-native}"

case "$repair_method" in
  termux-native|termux-native-provider-migration)
    strategy="$maintainer_dir/repair-control-plane-termux-native.sh"
    ;;
  *)
    warn "未知控制中枢修复方式：$repair_method"
    warn "可设置 OPENHOUSE_SERVICE_CONTROL_REPAIR_METHOD=termux-native，或在 $control_repair_entry_file 写入自定义脚本路径。"
    exit 2
    ;;
esac

if [ ! -f "$strategy" ]; then
  warn "控制中枢修复策略不存在：$strategy"
  warn "请先重新同步 APK 内置运行资源，或通过 OPENHOUSE_SERVICE_CONTROL_REPAIR_ENTRY 指定外部入口。"
  exit 2
fi

log "控制中枢修复方式：$repair_method"
run_logged bash "$strategy"
exit $?
