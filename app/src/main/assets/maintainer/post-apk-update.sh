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

if ! declare -F warn >/dev/null 2>&1; then
  warn() {
    printf '[SmallPhoneAI post-update] WARN: %s\n' "$*" >&2
  }
fi

find_maintainer_script() {
  local name="$1"
  local dir
  for dir in \
    "${OPENHOUSEAI_MAINTAINER_DIR:-}" \
    "${SMALLPHONEAI_MAINTAINER_DIR:-}" \
    "$HOME/.smallphoneai-bootstrap/apk-assets/maintainer" \
    "$HOME/.smallphoneai-bootstrap/maintainer"; do
    [ -n "$dir" ] || continue
    if [ -f "$dir/$name" ]; then
      printf '%s\n' "$dir/$name"
      return 0
    fi
  done
  return 1
}

run_termux_control_plane_repair() {
  local script
  script="$(find_maintainer_script repair-control-plane-termux-native.sh || true)"
  if [ -z "$script" ]; then
    warn "未找到 repair-control-plane-termux-native.sh，跳过控制中枢更新后修复。"
    return 0
  fi
  log "APK 更新后先修复 Termux native service-manager：$script"
  run_logged bash "$script"
}

run_runtime_repair() {
  local script
  script="$(find_maintainer_script repair-smallphone.sh || true)"
  if [ -z "$script" ]; then
    warn "未找到 repair-smallphone.sh，跳过运行栈更新后修复。"
    return 0
  fi
  log "APK 更新后复用运行栈修复入口：$script"
  (
    # shellcheck disable=SC1090
    . "$script"
  )
}

set +e
run_termux_control_plane_repair
control_status=$?
set -e

bootstrap="$(find_smallphoneai_bootstrap || true)"
if [ -z "$bootstrap" ]; then
  log "未找到 SmallPhoneAI bootstrap.sh，无法执行 APK 更新后同步。"
  bootstrap_status=2
else
  log "正在执行 APK 更新后核心运行栈同步：$bootstrap sync-core-stack"
  set +e
  run_logged bash "$bootstrap" sync-core-stack
  bootstrap_status=$?
  set -e
fi

set +e
run_runtime_repair
repair_status=$?
set -e

if [ "$control_status" -ne 0 ]; then
  exit "$control_status"
fi
if [ "$bootstrap_status" -ne 0 ]; then
  exit "$bootstrap_status"
fi
exit "$repair_status"
