require_ubuntu

ubuntu_mirror_policy_path() {
  local candidate
  for candidate in \
    "${SMALLPHONEAI_UBUNTU_MIRROR_POLICY:-}" \
    "${OPENHOUSEAI_UBUNTU_MIRROR_POLICY:-}" \
    "${SMALLPHONEAI_BOOTSTRAP_DIR:+$SMALLPHONEAI_BOOTSTRAP_DIR/scripts/_ubuntu-mirror-policy.sh}" \
    "${SMALLPHONEAI_BOOTSTRAP:-}" \
    "$HOME/.smallphoneai-bootstrap/scripts/_ubuntu-mirror-policy.sh" \
    "/data/data/com.termux/files/home/.smallphoneai-bootstrap/scripts/_ubuntu-mirror-policy.sh"; do
    [ -n "$candidate" ] || continue
    case "$candidate" in
      */bootstrap.sh) candidate="${candidate%/*}/scripts/_ubuntu-mirror-policy.sh" ;;
    esac
    if [ -f "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

load_ubuntu_mirror_policy() {
  local policy
  policy="$(ubuntu_mirror_policy_path || true)"
  if [ -z "$policy" ]; then
    log "缺少 canonical Ubuntu 镜像策略 helper：_ubuntu-mirror-policy.sh"
    return 1
  fi
  # shellcheck disable=SC1090
  . "$policy"
  if ! declare -F smallphoneai_resolve_ubuntu_apt_mirror >/dev/null 2>&1 \
    || ! declare -F smallphoneai_write_canonical_ubuntu_sources >/dev/null 2>&1; then
    log "canonical Ubuntu 镜像策略 helper API 不完整：$policy"
    return 1
  fi
  UBUNTU_MIRROR_POLICY_PATH="$policy"
  export UBUNTU_MIRROR_POLICY_PATH
}

ensure_ubuntu_mirror_run_environment() {
  local run_id
  if [ -n "${OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID:-}" ] \
    && [ -n "${SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID:-}" ] \
    && [ "$OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID" != "$SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID" ]; then
    log "Ubuntu 镜像运行锁 ID 的 OPENHOUSEAI/SMALLPHONEAI 配置冲突。"
    return 64
  fi
  if [ -n "${OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT:-}" ] \
    && [ -n "${SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT:-}" ] \
    && [ "$OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT" != "$SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT" ]; then
    log "Ubuntu 镜像运行锁目录的 OPENHOUSEAI/SMALLPHONEAI 配置冲突。"
    return 64
  fi
  run_id="${OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID:-${SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID:-${OPENHOUSE_RUN_STARTED_AT_MS:-}}}"
  if [ -z "$run_id" ]; then
    run_id="maintainer-${STAGE_SLUG:-ubuntu-packages}-$$-$(date +%s)"
  fi
  export OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID="$run_id"
  export SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID="$run_id"
  if [ -n "${OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT:-}" ]; then
    export SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT="$OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT"
  elif [ -n "${SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT:-}" ]; then
    export OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT="$SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT"
  fi
}

load_ubuntu_mirror_policy
ensure_ubuntu_mirror_run_environment

ubuntu_codename="$(run_ubuntu_logged bash -lc '. /etc/os-release; printf "%s\n" "${VERSION_CODENAME:-noble}"')"
ubuntu_codename="$(printf '%s\n' "$ubuntu_codename" | tail -n 1 | tr -d '\r')"
[ -n "$ubuntu_codename" ] || {
  log "无法读取 Ubuntu codename。"
  exit 1
}

log "正在按 canonical 有序故障转移策略解析 Ubuntu apt 镜像源"
selected_mirror="$(smallphoneai_resolve_ubuntu_apt_mirror "$ubuntu_codename")" || {
  log "canonical Ubuntu 镜像策略未找到可用 apt 来源。"
  exit 1
}
[ -n "$selected_mirror" ] || {
  log "canonical Ubuntu 镜像策略返回了空 apt mirror。"
  exit 1
}
export OPENHOUSEAI_UBUNTU_APT_MIRROR="$selected_mirror"
export SMALLPHONEAI_UBUNTU_APT_MIRROR="$selected_mirror"
log "本次运行锁定 Ubuntu apt 镜像源：$selected_mirror"

run_ubuntu_logged env \
  SMALLPHONEAI_UBUNTU_MIRROR_POLICY_LOADED=0 \
  OPENHOUSEAI_UBUNTU_MIRROR_POLICY="$UBUNTU_MIRROR_POLICY_PATH" \
  SMALLPHONEAI_UBUNTU_MIRROR_POLICY="$UBUNTU_MIRROR_POLICY_PATH" \
  OPENHOUSEAI_UBUNTU_APT_MIRROR="$selected_mirror" \
  SMALLPHONEAI_UBUNTU_APT_MIRROR="$selected_mirror" \
  OPENHOUSEAI_UBUNTU_CODENAME="$ubuntu_codename" \
  OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID="$OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID" \
  SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID="$SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID" \
  OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT="${OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT:-}" \
  SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT="${SMALLPHONEAI_UBUNTU_MIRROR_LOCK_ROOT:-${OPENHOUSEAI_UBUNTU_MIRROR_LOCK_ROOT:-}}" \
  OPENHOUSEAI_UBUNTU_APT_ROOT="${OPENHOUSEAI_UBUNTU_APT_ROOT:-}" \
  SMALLPHONEAI_UBUNTU_APT_ROOT="${SMALLPHONEAI_UBUNTU_APT_ROOT:-${OPENHOUSEAI_UBUNTU_APT_ROOT:-}}" \
  OPENHOUSEAI_UBUNTU_SOURCES_FILE="${OPENHOUSEAI_UBUNTU_SOURCES_FILE:-}" \
  SMALLPHONEAI_UBUNTU_SOURCES_FILE="${SMALLPHONEAI_UBUNTU_SOURCES_FILE:-${OPENHOUSEAI_UBUNTU_SOURCES_FILE:-}}" \
  OPENHOUSEAI_UBUNTU_SOURCES_BACKUP_DIR="${OPENHOUSEAI_UBUNTU_SOURCES_BACKUP_DIR:-}" \
  SMALLPHONEAI_UBUNTU_SOURCES_BACKUP_DIR="${SMALLPHONEAI_UBUNTU_SOURCES_BACKUP_DIR:-${OPENHOUSEAI_UBUNTU_SOURCES_BACKUP_DIR:-}}" \
  bash -lc 'set -euo pipefail
. "$OPENHOUSEAI_UBUNTU_MIRROR_POLICY"
smallphoneai_write_canonical_ubuntu_sources "$OPENHOUSEAI_UBUNTU_APT_MIRROR" "$OPENHOUSEAI_UBUNTU_CODENAME"'

log "正在 Ubuntu 内执行 apt update"
run_ubuntu_logged bash -lc 'set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
echo "正在修复可能被中断的 Ubuntu 软件包状态"
dpkg --configure -a
apt -f install -y
apt update'

log "正在 Ubuntu 内安装 curl、ca-certificates、git、procps、ripgrep 和 unzip"
run_ubuntu_logged bash -lc 'set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
echo "正在确认 Ubuntu 软件包状态可继续安装"
dpkg --configure -a
apt -f install -y
apt install -y curl ca-certificates git procps ripgrep unzip'

log "Ubuntu 软件包阶段已完成。"
