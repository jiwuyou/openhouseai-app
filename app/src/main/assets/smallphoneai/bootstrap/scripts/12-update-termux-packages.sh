#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

run_logged() {
  log "+ $*"
  "$@"
}

configure_termux_main_repo() {
  local sources_file="${PREFIX:-/data/data/com.termux/files/usr}/etc/apt/sources.list"
  local repo_url="${SMALLPHONEAI_TERMUX_MAIN_REPO:-}"

  if [ ! -d "$(dirname "$sources_file")" ]; then
    log "未找到 apt 源目录，跳过 Termux 镜像源配置：$(dirname "$sources_file")"
    return 0
  fi

  if [ -z "$repo_url" ]; then
    repo_url="$(select_fastest_termux_main_repo)"
  else
    log "使用指定 Termux main 镜像源：$repo_url"
  fi

  if [ -f "$sources_file" ] && grep -Fq "$repo_url" "$sources_file"; then
    log "Termux main 镜像源已是：$repo_url"
    return 0
  fi

  log "切换 Termux main 镜像源：$repo_url"
  cp "$sources_file" "$sources_file.smallphoneai.bak" 2>/dev/null || true
  printf 'deb %s stable main\n' "$repo_url" > "$sources_file"
}

select_fastest_termux_main_repo() {
  local candidates="
https://packages-cf.termux.dev/apt/termux-main
https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
https://mirrors.ustc.edu.cn/termux/apt/termux-main
https://mirror.sunred.org/termux/termux-main
"
  local repo best_repo best_time repo_time probe_url metrics http_code
  best_repo=""
  best_time=""

  for repo in $candidates; do
    probe_url="$repo/dists/stable/InRelease"
    metrics="$(curl -fsSL --connect-timeout 5 --max-time 12 -o /dev/null -w '%{time_total} %{http_code}' "$probe_url" 2>/dev/null || true)"
    repo_time="${metrics%% *}"
    http_code="${metrics##* }"
    if [ "$http_code" = "200" ] && [ -n "$repo_time" ]; then
      printf '[SmallPhoneAI] Termux 镜像测速：%s %ss\n' "$repo" "$repo_time" >&2
      if [ -z "$best_time" ] || awk "BEGIN{exit !($repo_time < $best_time)}"; then
        best_time="$repo_time"
        best_repo="$repo"
      fi
    else
      printf '[SmallPhoneAI] Termux 镜像不可用：%s\n' "$repo" >&2
    fi
  done

  if [ -n "$best_repo" ]; then
    printf '[SmallPhoneAI] 选择最快 Termux main 镜像源：%s\n' "$best_repo" >&2
    printf '%s\n' "$best_repo"
  else
    printf '[SmallPhoneAI] Termux 镜像测速失败，回退到 packages-cf.termux.dev\n' >&2
    printf '%s\n' "https://packages-cf.termux.dev/apt/termux-main"
  fi
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

run_environment_probe

if ! is_termux; then
  log "Termux 基础包阶段只能在 Termux 外层运行。当前运行环境：$(detect_smallphoneai_runtime)"
  exit 2
fi

configure_termux_main_repo

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
