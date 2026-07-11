#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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
      SMALLPHONEAI_UBUNTU_APT_MIRROR="${SMALLPHONEAI_UBUNTU_APT_MIRROR:-}" \
      "$@"
  else
    run_logged proot-distro login ubuntu -- env \
      OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
      SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
      SMALLPHONEAI_UBUNTU_APT_MIRROR="${SMALLPHONEAI_UBUNTU_APT_MIRROR:-}" \
      "$@"
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

log "正在 Ubuntu 内测速并选择 apt 镜像源。"
run_ubuntu_logged bash -lc 'set -euo pipefail
. /etc/os-release
codename="${VERSION_CODENAME:-noble}"
if [ -n "${SMALLPHONEAI_UBUNTU_APT_MIRROR:-}" ]; then
  selected_mirror="$SMALLPHONEAI_UBUNTU_APT_MIRROR"
  echo "使用指定 Ubuntu apt 镜像源：$selected_mirror"
elif [ "${OPENHOUSE_RETRY_MODE:-normal}" = "cn" ]; then
  selected_mirror="https://mirrors.ustc.edu.cn/ubuntu-ports"
  echo "国内网络重试：使用固定 Ubuntu apt 镜像源：$selected_mirror"
else
  selected_mirror=""
  best_time=""
  candidates="
https://mirrors.ustc.edu.cn/ubuntu-ports
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
https://mirror.nju.edu.cn/ubuntu-ports
http://ports.ubuntu.com/ubuntu-ports
"
  for mirror in $candidates; do
    probe_url="$mirror/dists/$codename/InRelease"
    metrics="$(curl -fsSL --connect-timeout 5 --max-time 12 -o /dev/null -w "%{time_total} %{http_code}" "$probe_url" 2>/dev/null || true)"
    probe_time="${metrics%% *}"
    http_code="${metrics##* }"
    if [ "$http_code" = "200" ] && [ -n "$probe_time" ]; then
      echo "Ubuntu apt 镜像测速：$mirror ${probe_time}s"
      if [ -z "$best_time" ] || awk "BEGIN{exit !($probe_time < $best_time)}"; then
        best_time="$probe_time"
        selected_mirror="$mirror"
      fi
    else
      echo "Ubuntu apt 镜像不可用：$mirror"
    fi
  done
fi
if [ -z "${selected_mirror:-}" ]; then
  echo "未找到可用 Ubuntu apt 镜像源，保留系统默认源。"
  exit 0
fi
echo "选择 Ubuntu apt 镜像源：$selected_mirror"
mkdir -p /etc/apt/sources.list.d /etc/apt/smallphoneai-backup
if [ -f /etc/apt/sources.list ]; then
  mv /etc/apt/sources.list /etc/apt/smallphoneai-backup/sources.list.bak 2>/dev/null || true
fi
if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
  mv /etc/apt/sources.list.d/ubuntu.sources /etc/apt/smallphoneai-backup/ubuntu.sources.bak 2>/dev/null || true
fi
cat > /etc/apt/sources.list.d/smallphoneai-ubuntu.sources <<EOF
Types: deb
URIs: $selected_mirror
Suites: $codename $codename-updates $codename-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: $selected_mirror
Suites: $codename-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF'

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
