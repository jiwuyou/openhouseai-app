#!/usr/bin/env bash
set -euo pipefail

TERMUX_BASH="/data/data/com.termux/files/usr/bin/bash"

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/device-run-first-install.sh <adb-device-id>

Runs the synced OpenHouse first-install flow on the device through
adb run-as com.termux. Output is written to:
  $HOME/.maintainer-logs/manifest_full.log
EOF
}

log() {
  printf '[device-run-first-install] %s\n' "$*"
}

die() {
  log "ERROR: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

device="${1:-}"
if [ "$device" = "-h" ] || [ "$device" = "--help" ]; then
  usage
  exit 0
fi
if [ -z "$device" ]; then
  usage
  exit 2
fi

need_cmd adb

adb_cmd() {
  adb -s "$device" "$@"
}

if ! adb_cmd get-state >/dev/null 2>&1; then
  die "adb device is not available: $device"
fi

log "starting first install flow on $device"
adb_cmd shell run-as com.termux "$TERMUX_BASH" -s <<'REMOTE'
set -euo pipefail

export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
if [ -d "/data/data/com.termux/files/home" ]; then
  export HOME="/data/data/com.termux/files/home"
else
  export HOME="${HOME:-/data/data/com.termux/files/home}"
fi
export PATH="$HOME/.npm-global/bin:$PREFIX/bin:/system/bin:${PATH:-}"
export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
export TERM="${TERM:-xterm-256color}"
export OPENHOUSEAI_NO_AUTO_UBUNTU=1
export TERMUX_NO_AUTO_UBUNTU=1
export OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}"
export OPENHOUSEAI_RETRY_MODE="${OPENHOUSEAI_RETRY_MODE:-$OPENHOUSE_RETRY_MODE}"
export SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-$OPENHOUSE_RETRY_MODE}"
export OPENHOUSE_INSTALL_ATTEMPT="${OPENHOUSE_INSTALL_ATTEMPT:-1}"
export OPENHOUSE_INSTALL_TASK_SCOPE="${OPENHOUSE_INSTALL_TASK_SCOPE:-full}"
export SMALLPHONEAI_BOOTSTRAP="${SMALLPHONEAI_BOOTSTRAP:-$HOME/.smallphoneai-bootstrap/bootstrap.sh}"
export SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}"
export SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-$SMALLPHONEAI_OFFLINE_PAYLOAD_DIR}"
export SMALLPHONEAI_COMPONENT_SOURCE_MODE="${SMALLPHONEAI_COMPONENT_SOURCE_MODE:-bundle}"
export SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE="${SMALLPHONEAI_COMPONENTS_ALLOW_GIT_UPDATE:-0}"
export SMALLPHONEAI_COMPONENTS_AUTO_CLONE="${SMALLPHONEAI_COMPONENTS_AUTO_CLONE:-0}"
export SMALLPHONEAI_COMPONENTS_STRICT="${SMALLPHONEAI_COMPONENTS_STRICT:-1}"
export AIONUI_PORT="${AIONUI_PORT:-25808}"

unset http_proxy https_proxy ftp_proxy all_proxy no_proxy HTTP_PROXY HTTPS_PROXY FTP_PROXY ALL_PROXY NO_PROXY

LOG_DIR="$HOME/.maintainer-logs"
LOG_FILE="$LOG_DIR/manifest_full.log"
RUNNING_FILE="$LOG_DIR/manifest_full.running"
RUN_SCRIPT="$LOG_DIR/run-manifest_full.sh"
mkdir -p "$TMPDIR" "$LOG_DIR"
: > "$LOG_FILE"
export OPENHOUSE_INSTALL_LOG_PATH="$LOG_FILE"
export LOG_DIR LOG_FILE RUNNING_FILE RUN_SCRIPT

if [ ! -f "$SMALLPHONEAI_BOOTSTRAP" ]; then
  printf '[device-run-first-install] missing bootstrap: %s\n' "$SMALLPHONEAI_BOOTSTRAP" | tee -a "$LOG_FILE" >&2
  exit 1
fi
if [ ! -d "$SMALLPHONEAI_OFFLINE_PAYLOAD_DIR" ]; then
  printf '[device-run-first-install] missing payload dir: %s\n' "$SMALLPHONEAI_OFFLINE_PAYLOAD_DIR" | tee -a "$LOG_FILE" >&2
  exit 1
fi

cat > "$RUN_SCRIPT" <<'RUNNER'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

current_epoch_ms() {
  local seconds
  seconds="$(date +%s 2>/dev/null || true)"
  if [ -n "$seconds" ]; then
    printf '%s000' "$seconds"
  else
    printf '0'
  fi
}

RUN_STARTED_AT_MS="${OPENHOUSE_RUN_STARTED_AT_MS:-$(current_epoch_ms)}"
STAGE_SLUG="manifest_full"

finish() {
  local exit_code=$?
  printf '__TERMUX_MAINT_DONE__:manifest_full:%s\n' "$exit_code" | tee -a "$LOG_FILE"
  rm -f "$RUNNING_FILE" >/dev/null 2>&1 || true
  exit "$exit_code"
}
trap finish EXIT

mark_stage_marker() {
  case "$1" in
    __OPENHOUSE_INSTALL_STAGE__:*)
      local payload stage_slug now_ms
      payload="${1#__OPENHOUSE_INSTALL_STAGE__:}"
      stage_slug="${payload%%:*}"
      now_ms="$(current_epoch_ms)"
      {
        printf 'started_at_ms=%s\n' "$RUN_STARTED_AT_MS"
        printf 'stage_slug=%s\n' "$stage_slug"
        printf 'stage_started_at_ms=%s\n' "$now_ms"
        printf 'remote_schedule=0\n'
        printf 'retry_mode=%s\n' "${OPENHOUSE_RETRY_MODE:-normal}"
        printf 'task_scope=%s\n' "${OPENHOUSE_INSTALL_TASK_SCOPE:-full}"
        printf 'attempt=%s\n' "${OPENHOUSE_INSTALL_ATTEMPT:-1}"
        printf 'log_path=%s\n' "$LOG_FILE"
      } > "$RUNNING_FILE" || true
      ;;
  esac
}

log() {
  printf '%s\n' "$*" | tee -a "$LOG_FILE"
  mark_stage_marker "$*"
}

run_logged() {
  local status=0
  set +e
  "$@" 2>&1 | tee -a "$LOG_FILE"
  status=${PIPESTATUS[0]}
  set -e
  return "$status"
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -r /etc/os-release ] && grep -qi 'ubuntu' /etc/os-release
}

detect_openhouseai_runtime() {
  local probe prefix
  prefix="${PREFIX:-/data/data/com.termux/files/usr}"
  if is_current_ubuntu; then
    printf 'ubuntu'
    return 0
  fi
  for probe in "$prefix/bin/smallphoneai-env-probe" "$prefix/bin/openhouseai-env-probe"; do
    if [ -x "$probe" ]; then
      "$probe" 2>/dev/null | awk -F= '$1=="SMALLPHONEAI_RUNTIME" || $1=="OPENHOUSEAI_RUNTIME"{print $2; found=1} END{if(!found) exit 1}' && return 0
    fi
  done
  if is_termux; then
    printf 'termux'
    return 0
  fi
  printf 'unknown'
}

run_environment_probe() {
  local probe prefix
  prefix="${PREFIX:-/data/data/com.termux/files/usr}"
  for probe in "$prefix/bin/smallphoneai-env-probe" "$prefix/bin/openhouseai-env-probe"; do
    if [ -x "$probe" ]; then
      log "environment probe: $probe"
      run_logged "$probe" || true
      CURRENT_RUNTIME="$(detect_openhouseai_runtime)"
      log "current runtime: $CURRENT_RUNTIME"
      return 0
    fi
  done
  CURRENT_RUNTIME="$(detect_openhouseai_runtime)"
  log "current runtime: $CURRENT_RUNTIME"
}

run_ubuntu_logged() {
  if is_current_ubuntu; then
    run_logged "$@"
  else
    run_logged proot-distro login ubuntu -- "$@"
  fi
}

require_ubuntu() {
  if is_current_ubuntu; then
    return 0
  fi
  if ! command -v proot-distro >/dev/null 2>&1; then
    log "proot-distro is missing; run the Termux packages stage first."
    exit 2
  fi
  if ! proot-distro login ubuntu -- true >/dev/null 2>&1; then
    log "Ubuntu is not installed yet."
    exit 3
  fi
}

stage_begin() {
  local slug="$1"
  local label="$2"
  STAGE_SLUG="$slug"
  log "__OPENHOUSE_INSTALL_STAGE__:$slug:$label"
  log "==> $label"
  run_environment_probe
}

run_bootstrap_stage() {
  local slug="$1"
  local label="$2"
  shift 2
  stage_begin "$slug" "$label"
  run_logged "$@"
}

run_aionui_stage() {
  local script="$HOME/.smallphoneai-bootstrap/apk-assets/maintainer/install-aionui.sh"
  stage_begin "install_aionui" "Install AionUi workspace"
  if [ ! -f "$script" ]; then
    log "missing AionUi maintainer script: $script"
    exit 1
  fi
  # shellcheck source=/dev/null
  . "$script"
}

log "__OPENHOUSE_INSTALL_TASK__:full"
log "==> OpenHouse first install from synced assets"
log "bootstrap=$SMALLPHONEAI_BOOTSTRAP"
log "payload_dir=$SMALLPHONEAI_OFFLINE_PAYLOAD_DIR"

run_bootstrap_stage "prepare" "Prepare local directories" \
  bash "$SMALLPHONEAI_BOOTSTRAP" prepare

run_bootstrap_stage "termux_packages" "Prepare Termux packages" \
  bash "$SMALLPHONEAI_BOOTSTRAP" termux-packages

run_bootstrap_stage "install_termux_node" "Install Termux Node.js" \
  bash "$SMALLPHONEAI_BOOTSTRAP" termux-node

run_bootstrap_stage "runtime_components" "Install Termux runtime components" \
  env \
    OPENHOUSE_PI_RUNTIME=termux \
    SMALLPHONEAI_PI_RUNTIME=termux \
    OPENHOUSE_PI_NODE_RUNTIME=termux \
    SMALLPHONEAI_RUNTIME_COMPONENTS_IN_UBUNTU=1 \
    SMALLPHONEAI_COMPONENT_TARGETS=service-manager,pi-agent,pi-web,wuyou \
    SMALLPHONEAI_FORCE_PAYLOAD_REFRESH="${SMALLPHONEAI_FORCE_PAYLOAD_REFRESH:-1}" \
    bash "$SMALLPHONEAI_BOOTSTRAP" components

run_bootstrap_stage "start_smallphone" "Start pi-agent and pi-web" \
  env \
    SMALLPHONEAI_START_TARGETS=pi-agent,pi-web \
    SMALLPHONEAI_START_READY_TIMEOUT="${SMALLPHONEAI_EARLY_PI_START_READY_TIMEOUT:-45}" \
    bash "$SMALLPHONEAI_BOOTSTRAP" start

run_bootstrap_stage "install_ubuntu" "Install Ubuntu" \
  bash "$SMALLPHONEAI_BOOTSTRAP" ubuntu

run_bootstrap_stage "ubuntu_packages" "Install Ubuntu packages" \
  bash "$SMALLPHONEAI_BOOTSTRAP" ubuntu-packages

run_bootstrap_stage "entry_ubuntu" "Configure Ubuntu entry" \
  bash "$SMALLPHONEAI_BOOTSTRAP" entry-ubuntu

run_bootstrap_stage "install_node" "Install Ubuntu Node.js" \
  bash "$SMALLPHONEAI_BOOTSTRAP" node

run_bootstrap_stage "sync_official_docs" "Sync official docs" \
  bash "$SMALLPHONEAI_BOOTSTRAP" sync-docs

run_aionui_stage

run_bootstrap_stage "sync_openhouse_registry" "Sync OpenHouse registry" \
  bash "$SMALLPHONEAI_BOOTSTRAP" registry-sync

log "OpenHouse first install flow finished."
RUNNER

chmod 755 "$RUN_SCRIPT"
exec "$RUN_SCRIPT"
REMOTE

log "done"
