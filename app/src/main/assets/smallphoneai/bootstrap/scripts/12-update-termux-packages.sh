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

repo_log() {
  printf '[SmallPhoneAI] %s\n' "$*" >&2
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

termux_main_repo_override() {
  printf '%s\n' "${OPENHOUSEAI_TERMUX_MAIN_REPO:-${SMALLPHONEAI_TERMUX_MAIN_REPO:-}}"
}

termux_main_repo_candidates() {
  if command -v smallphoneai_is_cn_retry >/dev/null 2>&1 && smallphoneai_is_cn_retry; then
    smallphoneai_cn_termux_main_repo_candidates
    return 0
  fi

  cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
https://mirrors.ustc.edu.cn/termux/apt/termux-main
https://mirrors.bfsu.edu.cn/termux/apt/termux-main
https://mirrors.nju.edu.cn/termux/apt/termux-main
https://packages-cf.termux.dev/apt/termux-main
https://packages.termux.dev/apt/termux-main
EOF
}

default_termux_main_repo() {
  termux_main_repo_candidates | awk 'NF { print; exit }'
}

termux_apt_arch() {
  local machine
  if command -v dpkg >/dev/null 2>&1; then
    dpkg --print-architecture 2>/dev/null && return 0
  fi

  machine="$(uname -m 2>/dev/null || true)"
  case "$machine" in
    aarch64|arm64) printf '%s\n' 'aarch64' ;;
    armv7*|armv8l|armhf) printf '%s\n' 'arm' ;;
    x86_64|amd64) printf '%s\n' 'x86_64' ;;
    i?86) printf '%s\n' 'i686' ;;
    *) printf '%s\n' 'aarch64' ;;
  esac
}

probe_termux_repo_url() {
  local repo="$1"
  local path="$2"
  local probe_url metrics http_code bytes total speed
  probe_url="$repo/$path"
  metrics="$(curl -fsSL --connect-timeout 6 --max-time 24 --speed-time 8 --speed-limit 4096 \
    -r 0-524287 -o /dev/null -w '%{http_code} %{size_download} %{time_total} %{speed_download}' \
    "$probe_url" 2>/dev/null || true)"
  http_code="$(printf '%s' "$metrics" | awk '{print $1}')"
  bytes="$(printf '%s' "$metrics" | awk '{print $2}')"
  total="$(printf '%s' "$metrics" | awk '{print $3}')"
  speed="$(printf '%s' "$metrics" | awk '{print $4}')"

  if { [ "$http_code" = "200" ] || [ "$http_code" = "206" ]; } && [ -n "$bytes" ] && [ -n "$total" ] && [ -n "$speed" ]; then
    repo_log "Termux 镜像吞吐探测：$repo/$path ${speed}B/s（$bytes bytes, ${total}s）"
    printf '%s\n' "$speed"
    return 0
  fi

  return 1
}

probe_termux_repo_throughput() {
  local repo="$1"
  local arch speed
  arch="$(termux_apt_arch)"

  if speed="$(probe_termux_repo_url "$repo" "dists/stable/main/binary-$arch/Packages")"; then
    printf '%s\n' "$speed"
    return 0
  fi

  if speed="$(probe_termux_repo_url "$repo" "dists/stable/main/binary-$arch/Packages.xz")"; then
    printf '%s\n' "$speed"
    return 0
  fi

  if curl -fsSL --connect-timeout 5 --max-time 10 -o /dev/null "$repo/dists/stable/InRelease" 2>/dev/null; then
    repo_log "Termux 镜像仅通过可用性兜底探测：$repo（未获得 Packages 吞吐）"
    printf '%s\n' "1"
    return 0
  fi

  repo_log "Termux 镜像不可用：$repo"
  return 1
}

select_stable_termux_main_repo() {
  local repo best_repo best_speed speed min_speed override
  override="$(termux_main_repo_override)"
  if [ -n "$override" ]; then
    repo_log "使用指定 Termux main 镜像源：$override"
    printf '%s\n' "$override"
    return 0
  fi

  best_repo=""
  best_speed="0"
  min_speed="${OPENHOUSEAI_TERMUX_MIN_REPO_SPEED_BPS:-${SMALLPHONEAI_TERMUX_MIN_REPO_SPEED_BPS:-65536}}"

  if ! command -v curl >/dev/null 2>&1; then
    repo="$(default_termux_main_repo)"
    repo_log "curl 不可用，无法做镜像吞吐探测，使用默认 Termux main 镜像源：$repo"
    printf '%s\n' "$repo"
    return 0
  fi

  for repo in $(termux_main_repo_candidates); do
    if speed="$(probe_termux_repo_throughput "$repo")"; then
      if awk "BEGIN { exit !($speed >= $min_speed) }"; then
        repo_log "选择 Termux main 镜像源：$repo（固定优先级内吞吐达标，最低 ${min_speed}B/s）"
        printf '%s\n' "$repo"
        return 0
      fi
      if awk "BEGIN { exit !($speed > $best_speed) }"; then
        best_speed="$speed"
        best_repo="$repo"
      fi
    fi
  done

  if [ -n "$best_repo" ]; then
    repo_log "没有镜像达到最低吞吐 ${min_speed}B/s，使用当前实测最优源：$best_repo（${best_speed}B/s）"
    printf '%s\n' "$best_repo"
  else
    repo="$(default_termux_main_repo)"
    repo_log "Termux 镜像吞吐探测全部失败，使用默认源：$repo"
    printf '%s\n' "$repo"
  fi
}

write_termux_main_repo() {
  local repo_url="$1"
  local sources_file="${PREFIX:-/data/data/com.termux/files/usr}/etc/apt/sources.list"

  if [ ! -d "$(dirname "$sources_file")" ]; then
    log "未找到 apt 源目录，跳过 Termux 镜像源配置：$(dirname "$sources_file")"
    return 0
  fi

  if [ -f "$sources_file" ] && grep -Fq "$repo_url" "$sources_file"; then
    log "Termux main 镜像源已是：$repo_url"
    return 0
  fi

  log "切换 Termux main 镜像源：$repo_url"
  cp "$sources_file" "$sources_file.smallphoneai.bak" 2>/dev/null || true
  printf 'deb %s stable main\n' "$repo_url" > "$sources_file"
}

run_with_optional_timeout() {
  local timeout_seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout --foreground "$timeout_seconds" "$@"
  else
    "$@"
  fi
}

run_termux_apt_update() {
  local timeout_seconds="${OPENHOUSEAI_TERMUX_APT_UPDATE_TIMEOUT_SECONDS:-${SMALLPHONEAI_TERMUX_APT_UPDATE_TIMEOUT_SECONDS:-300}}"
  run_logged run_with_optional_timeout "$timeout_seconds" env \
    DEBIAN_FRONTEND=noninteractive DEBIAN_PRIORITY=critical \
    apt \
    -o Dpkg::Options::=--force-confdef \
    -o Dpkg::Options::=--force-confold \
    -o Acquire::Retries=2 \
    -o Acquire::http::Timeout=30 \
    -o Acquire::https::Timeout=30 \
    update
}

run_termux_apt_install() {
  local timeout_seconds="${OPENHOUSEAI_TERMUX_APT_INSTALL_TIMEOUT_SECONDS:-${SMALLPHONEAI_TERMUX_APT_INSTALL_TIMEOUT_SECONDS:-1800}}"
  run_logged run_with_optional_timeout "$timeout_seconds" env \
    DEBIAN_FRONTEND=noninteractive DEBIAN_PRIORITY=critical \
    apt \
    -o Dpkg::Options::=--force-confdef \
    -o Dpkg::Options::=--force-confold \
    -o Acquire::Retries=2 \
    -o Acquire::http::Timeout=30 \
    -o Acquire::https::Timeout=30 \
    install -y "$@"
}

repair_termux_package_state() {
  local timeout_seconds="${OPENHOUSEAI_TERMUX_APT_REPAIR_TIMEOUT_SECONDS:-${SMALLPHONEAI_TERMUX_APT_REPAIR_TIMEOUT_SECONDS:-300}}"
  if command -v dpkg >/dev/null 2>&1; then
    log "尝试修复 dpkg 半配置状态。"
    run_with_optional_timeout "$timeout_seconds" env \
      DEBIAN_FRONTEND=noninteractive DEBIAN_PRIORITY=critical \
      dpkg --force-confdef --force-confold --configure -a || true
  fi
  if command -v apt >/dev/null 2>&1; then
    run_with_optional_timeout "$timeout_seconds" env \
      DEBIAN_FRONTEND=noninteractive DEBIAN_PRIORITY=critical \
      apt \
      -o Dpkg::Options::=--force-confdef \
      -o Dpkg::Options::=--force-confold \
      -o Acquire::Retries=1 \
      -o Acquire::http::Timeout=20 \
      -o Acquire::https::Timeout=20 \
      -f install -y || true
  fi
}

termux_repo_retry_order() {
  local selected="$1"
  local override repo
  override="$(termux_main_repo_override)"
  if [ -n "$override" ]; then
    printf '%s\n' "$override"
    if command -v smallphoneai_is_cn_retry >/dev/null 2>&1 && smallphoneai_is_cn_retry; then
      for repo in $(termux_main_repo_candidates); do
        [ "$repo" = "$override" ] && continue
        printf '%s\n' "$repo"
      done
    fi
    return 0
  fi

  printf '%s\n' "$selected"
  for repo in $(termux_main_repo_candidates); do
    [ "$repo" = "$selected" ] && continue
    printf '%s\n' "$repo"
  done
}

install_termux_base_packages() {
  local selected_repo repo status
  selected_repo="$(select_stable_termux_main_repo)"

  for repo in $(termux_repo_retry_order "$selected_repo"); do
    write_termux_main_repo "$repo"

    log "正在执行 apt update（源：$repo）"
    if run_termux_apt_update; then
      :
    else
      status="$?"
      log "apt update 失败或超时（退出码：$status，源：$repo），准备尝试下一个镜像源。"
      repair_termux_package_state
      continue
    fi

    log "正在执行 apt install -y proot-distro openssh curl jq zstd libcurl libngtcp2 libnghttp2 openssl ca-certificates termux-services（源：$repo）"
    if run_termux_apt_install proot-distro openssh curl jq zstd libcurl libngtcp2 libnghttp2 openssl ca-certificates termux-services; then
      return 0
    fi

    status="$?"
    log "apt install 失败或超时（退出码：$status，源：$repo），准备修复状态并尝试下一个镜像源。"
    repair_termux_package_state
  done

  return 1
}

termux_runsvdir_active() {
  local service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  local proc comm args

  for proc in /proc/[0-9]*; do
    [ -r "$proc/comm" ] && [ -r "$proc/cmdline" ] || continue
    comm="$(cat "$proc/comm" 2>/dev/null || true)"
    [ "$comm" = "runsvdir" ] || continue
    args="$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null || true)"
    printf '%s\n' "$args" | grep -Fqx -- "$service_root" && return 0
  done
  return 1
}

ensure_termux_services_ready() {
  local service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"

  command -v service-daemon >/dev/null 2>&1 || {
    log "termux-services 已安装但 service-daemon 不可用。"
    return 1
  }
  command -v sv >/dev/null 2>&1 || {
    log "termux-services 已安装但 sv 不可用。"
    return 1
  }
  [ -d "$service_root" ] || {
    log "termux-services 服务目录不存在：$service_root"
    return 1
  }

  log "正在显式启动 termux-services 服务守护进程。"
  service-daemon start >/dev/null 2>&1 || true
  for _ in $(seq 1 10); do
    termux_runsvdir_active && {
      log "termux-services 已就绪：$service_root"
      return 0
    }
    sleep 1
  done
  log "termux-services 未能启动 runsvdir：$service_root"
  return 1
}

ensure_termux_bridge_sshd() {
  local ensure_command="${PREFIX:-/data/data/com.termux/files/usr}/bin/oh-termux-ensure-sshd"

  if [ ! -x "$ensure_command" ]; then
    log "跨层桥接 CLI 尚未注入，跳过 Termux sshd 准备：$ensure_command"
    return 0
  fi

  log "正在准备 Termux native sshd 回环桥。"
  if "$ensure_command" ensure; then
    log "Termux native sshd 回环桥已可用。"
    return 0
  fi

  log "Termux native sshd 回环桥准备失败；可稍后在 Termux 执行：oh-termux-ensure-sshd ensure"
  return 1
}

run_environment_probe
if command -v smallphoneai_log_retry_profile >/dev/null 2>&1; then
  smallphoneai_log_retry_profile '[SmallPhoneAI]'
fi

if ! is_termux; then
  log "Termux 基础包阶段只能在 Termux 外层运行。当前运行环境：$(detect_smallphoneai_runtime)"
  exit 2
fi

if ! command -v apt >/dev/null 2>&1; then
  log "缺少 apt，无法安装 Termux 基础包。"
  exit 1
fi

if ! install_termux_base_packages; then
  log "Termux 基础包安装失败：所有候选镜像源均未成功。"
  exit 1
fi

if ! ensure_termux_services_ready; then
  log "termux-services 安装或启动验证失败。"
  exit 1
fi

if ! curl --version >/dev/null 2>&1; then
  log "curl 仍不可用，尝试完整升级 Termux 依赖。"
  repair_termux_package_state
  run_termux_apt_install openssh curl jq zstd libcurl libngtcp2 libnghttp2 openssl ca-certificates termux-services || true
fi

if ! curl --version >/dev/null 2>&1; then
  log "curl 修复失败，请手动执行：apt update && apt install -y openssh curl jq libcurl libngtcp2 libnghttp2 openssl ca-certificates"
  exit 1
fi

if ! jq --version >/dev/null 2>&1; then
  log "jq 仍不可用，尝试单独修复 jq。"
  repair_termux_package_state
  run_termux_apt_install jq || true
fi

if ! jq --version >/dev/null 2>&1; then
  log "jq 修复失败，请手动执行：apt update && apt install -y jq"
  exit 1
fi

ensure_termux_bridge_sshd || true

log "Termux 软件包阶段已完成。"
