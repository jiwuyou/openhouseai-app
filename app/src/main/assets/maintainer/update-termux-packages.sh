log "正在执行 pkg update -y"
run_logged pkg update -y

log "正在执行 pkg install -y proot-distro curl libcurl libngtcp2 libnghttp2 openssl ca-certificates"
run_logged pkg install -y proot-distro curl libcurl libngtcp2 libnghttp2 openssl ca-certificates

if ! curl --version >/dev/null 2>&1; then
  log "curl 仍不可用，尝试完整升级 Termux 依赖。"
  run_logged pkg upgrade -y
  run_logged pkg install -y curl libcurl libngtcp2 libnghttp2 openssl ca-certificates
fi

if ! curl --version >/dev/null 2>&1; then
  log "curl 修复失败，请手动执行：pkg upgrade -y && pkg install -y curl libcurl libngtcp2 libnghttp2 openssl ca-certificates"
  exit 1
fi

log "Termux 软件包阶段已完成。"
