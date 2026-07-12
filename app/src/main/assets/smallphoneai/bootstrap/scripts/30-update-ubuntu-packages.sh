#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/_ubuntu-mirror-policy.sh" ]; then
  # shellcheck source=_ubuntu-mirror-policy.sh
  . "$SCRIPT_DIR/_ubuntu-mirror-policy.sh"
fi
if [ -f "$SCRIPT_DIR/_retry-profile.sh" ]; then
  # shellcheck source=_retry-profile.sh
  . "$SCRIPT_DIR/_retry-profile.sh"
fi

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

run_logged() {
  log "+ $*"
  "$@"
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

detect_smallphoneai_runtime() {
  if is_current_ubuntu; then
    printf 'ubuntu'
    return 0
  fi

  if [ -x "${PREFIX:-/data/data/com.termux/files/usr}/bin/smallphoneai-env-probe" ]; then
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/smallphoneai-env-probe" 2>/dev/null \
      | awk -F= '$1=="SMALLPHONEAI_RUNTIME"{print $2; found=1} END{if(!found) exit 1}' \
      && return 0
  fi

  if is_termux; then
    printf 'termux'
    return 0
  fi

  printf 'unknown'
}

run_environment_probe() {
  local probe="${PREFIX:-/data/data/com.termux/files/usr}/bin/smallphoneai-env-probe"
  if [ -x "$probe" ]; then
    log "正在执行环境探测命令：$probe"
    run_logged "$probe" || true
  else
    log "环境探测命令不存在，使用内置探测逻辑。"
  fi
  log "当前运行环境：$(detect_smallphoneai_runtime)"
}

run_ubuntu_logged() {
  if is_current_ubuntu; then
    run_logged env \
      OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
      SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
      OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID="${OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID:-${SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID:-}}" \
      SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID="${SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID:-${OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID:-}}" \
      SMALLPHONEAI_UBUNTU_APT_MIRROR="${SMALLPHONEAI_UBUNTU_APT_MIRROR:-}" \
      "$@"
  else
    run_logged proot-distro login ubuntu -- env \
      OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
      SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
      OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID="${OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID:-${SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID:-}}" \
      SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID="${SMALLPHONEAI_UBUNTU_MIRROR_RUN_ID:-${OPENHOUSEAI_UBUNTU_MIRROR_RUN_ID:-}}" \
      SMALLPHONEAI_UBUNTU_APT_MIRROR="${SMALLPHONEAI_UBUNTU_APT_MIRROR:-}" \
      "$@"
  fi
}

ubuntu_codename() {
  if is_current_ubuntu; then
    bash -lc '. /etc/os-release; printf "%s\n" "${VERSION_CODENAME:-noble}"'
  else
    proot-distro login ubuntu -- bash -lc '. /etc/os-release; printf "%s\n" "${VERSION_CODENAME:-noble}"'
  fi
}

run_environment_probe
if command -v smallphoneai_log_retry_profile >/dev/null 2>&1; then
  smallphoneai_log_retry_profile '[SmallPhoneAI]'
fi

if ! is_current_ubuntu && { ! command -v proot-distro >/dev/null 2>&1 || ! proot-distro login ubuntu -- true >/dev/null 2>&1; }; then
  log "Ubuntu 不可用，请先运行：bash bootstrap.sh ubuntu"
  exit 2
fi

if ! command -v smallphoneai_resolve_ubuntu_apt_mirror >/dev/null 2>&1; then
  log "canonical Ubuntu apt mirror resolver 不可用：$SCRIPT_DIR/_ubuntu-mirror-policy.sh"
  exit 1
fi
codename="$(ubuntu_codename)"
log "正在按 canonical 顺序解析 Ubuntu apt mirror：TUNA -> NJU -> official -> USTC。"
selected_mirror="$(smallphoneai_resolve_ubuntu_apt_mirror "$codename")" || {
  log "未找到可用 Ubuntu apt mirror。"
  exit 1
}
export OPENHOUSEAI_UBUNTU_APT_MIRROR="$selected_mirror"
export SMALLPHONEAI_UBUNTU_APT_MIRROR="$selected_mirror"
export OPENHOUSEAI_RESOLVED_UBUNTU_APT_MIRROR="$selected_mirror"
export SMALLPHONEAI_RESOLVED_UBUNTU_APT_MIRROR="$selected_mirror"
log "选择 Ubuntu apt mirror：$selected_mirror"
run_ubuntu_logged env \
  SMALLPHONEAI_UBUNTU_MIRROR_POLICY_PATH="$SCRIPT_DIR/_ubuntu-mirror-policy.sh" \
  SMALLPHONEAI_SELECTED_UBUNTU_APT_MIRROR="$selected_mirror" \
  SMALLPHONEAI_SELECTED_UBUNTU_CODENAME="$codename" \
  bash -lc 'set -euo pipefail
. "$SMALLPHONEAI_UBUNTU_MIRROR_POLICY_PATH"
smallphoneai_write_canonical_ubuntu_sources \
  "$SMALLPHONEAI_SELECTED_UBUNTU_APT_MIRROR" \
  "$SMALLPHONEAI_SELECTED_UBUNTU_CODENAME"'

log "正在 Ubuntu 内更新 apt 索引。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
echo "正在修复可能被中断的 Ubuntu 软件包状态"
dpkg --configure -a
apt -f install -y
apt update'

log "正在 Ubuntu 内安装基础依赖。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
echo "正在确认 Ubuntu 软件包状态可继续安装"
dpkg --configure -a
apt -f install -y
apt install -y curl ca-certificates git gh jq openssh-client procps ripgrep unzip'

log "Ubuntu 软件包阶段完成。"
