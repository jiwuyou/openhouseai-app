find_smallphoneai_bootstrap() {
  local maintainer_dir candidate

  if [ -n "${SMALLPHONEAI_BOOTSTRAP:-}" ] && [ -f "$SMALLPHONEAI_BOOTSTRAP" ]; then
    printf '%s\n' "$SMALLPHONEAI_BOOTSTRAP"
    return 0
  fi

  for maintainer_dir in \
    "${OPENHOUSEAI_MAINTAINER_DIR:-}" \
    "${SMALLPHONEAI_MAINTAINER_DIR:-}"; do
    [ -n "$maintainer_dir" ] || continue
    candidate="${maintainer_dir%/}/../bootstrap/bootstrap.sh"
    if [ -f "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

bootstrap="$(find_smallphoneai_bootstrap || true)"
if [ -z "$bootstrap" ]; then
  log "未找到 APK staged bootstrap，无法准备 Termux 基础包。"
  exit 1
fi

log "正在使用统一 bootstrap 准备 Termux 基础包：$bootstrap"
exec bash "$bootstrap" termux-packages
