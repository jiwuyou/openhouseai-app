PORT="__PORT__"

find_cloudcli_control_script() {
  local dir
  for dir in \
    "${OPENHOUSEAI_MAINTAINER_DIR:-}" \
    "${SMALLPHONEAI_MAINTAINER_DIR:-}" \
    "$HOME/.smallphoneai-bootstrap/apk-assets/maintainer" \
    "$HOME/.smallphoneai-bootstrap/maintainer"; do
    [ -n "$dir" ] || continue
    if [ -f "$dir/control-cloudcli-service.sh" ]; then
      printf '%s\n' "$dir/control-cloudcli-service.sh"
      return 0
    fi
  done
  return 1
}

control_script="$(find_cloudcli_control_script || true)"
[ -n "$control_script" ] || { log "未找到 CloudCLI service-manager 控制脚本。"; exit 1; }
log "正在通过 service-manager 重启 CloudCLI，端口 $PORT。"
run_logged bash "$control_script" restart "$PORT"
