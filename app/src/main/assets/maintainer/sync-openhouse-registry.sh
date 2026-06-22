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

log "正在同步 OpenHouseAI registry 到 Termux canonical。"
run_logged bash "$bootstrap" registry-sync
