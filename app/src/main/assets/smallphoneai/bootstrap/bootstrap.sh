#!/usr/bin/env bash
set -euo pipefail

SMALLPHONEAI_DIR="${SMALLPHONEAI_DIR:-$HOME/.smallphoneai-bootstrap}"
SMALLPHONEAI_RAW_BASE="${SMALLPHONEAI_RAW_BASE:-https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

die() {
  log "ERROR: $*" >&2
  exit 1
}

run_logged() {
  log "+ $*"
  "$@"
}

configure_termux_main_repo() {
  local sources_file="${PREFIX:-/data/data/com.termux/files/usr}/etc/apt/sources.list"
  local repo_url="${SMALLPHONEAI_TERMUX_MAIN_REPO:-}"

  [ -n "${PREFIX:-}" ] || return 0
  [ -d "$(dirname "$sources_file")" ] || return 0

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

download_file() {
  local url="$1"
  local output="$2"
  local attempt
  for attempt in 1 2 3 4 5; do
    log "下载：$url（第 $attempt 次）"
    if curl -fL \
      --connect-timeout 10 \
      --max-time 25 \
      --speed-time 10 \
      --speed-limit 1024 \
      --retry 1 \
      --retry-delay 2 \
      --retry-all-errors \
      "$url" -o "$output"; then
      return 0
    fi
    sleep 2
  done
  return 1
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

ensure_supported_runtime() {
  is_termux || is_current_ubuntu || die "请在官方 Termux 内运行；Codex、Claude Code 等 agent 安装也可以在 Ubuntu 内运行。"
}

ensure_termux_curl() {
  if ! is_termux; then
    command -v curl >/dev/null 2>&1 && curl --version >/dev/null 2>&1 && return 0
    die "curl 不可用，且当前不是 Termux，无法自动修复。"
  fi

  command -v pkg >/dev/null 2>&1 || die "curl 不可用，且缺少 pkg，无法自动修复。"

  configure_termux_main_repo
  log "正在更新 Termux 包索引并修复 curl 网络依赖。"
  run_logged pkg update -y || true
  run_logged pkg install -y curl libcurl libngtcp2 libnghttp2 openssl ca-certificates || true

  curl --version >/dev/null 2>&1 || die "curl 修复失败，请先执行：pkg upgrade -y && pkg install -y curl libcurl libngtcp2 openssl ca-certificates"
}

required_stage_scripts() {
  case "${1:-full}" in
    env-check|termux-check)
      printf '%s\n' 00-check-termux.sh
      ;;
    prepare)
      printf '%s\n' 10-prepare-termux.sh
      ;;
    termux-packages)
      printf '%s\n' 12-update-termux-packages.sh
      ;;
    ubuntu)
      printf '%s\n' 20-install-ubuntu.sh
      ;;
    sync-docs)
      printf '%s\n' 35-sync-docs.sh
      ;;
    ubuntu-packages)
      printf '%s\n' 30-update-ubuntu-packages.sh
      ;;
    entry-ubuntu)
      printf '%s\n' 70-configure-entry-ubuntu.sh
      ;;
    node|install-node)
      printf '%s\n' 38-install-node.sh
      ;;
    opencode)
      printf '%s\n' 40-install-opencode.sh
      ;;
    codex)
      printf '%s\n' 42-install-codex.sh
      ;;
    claude-code)
      printf '%s\n' 44-install-claude-code.sh
      ;;
    claude-code-ui|cloudcli)
      printf '%s\n' 45-install-claude-code-ui.sh
      ;;
    hermes|hermes-webui)
      printf '%s\n' 47-install-hermes.sh
      ;;
    registry-sync|sync-registry)
      printf '%s\n' 48-sync-openhouse-registry.sh
      ;;
    reasonix)
      printf '%s\n' 46-install-reasonix.sh
      ;;
    components|runtime-components)
      printf '%s\n' 50-install-runtime-components.sh
      ;;
    start|restart)
      printf '%s\n' \
        60-start-smallphone.sh \
        65-smallphone-status.sh
      ;;
    status|hooks|check)
      printf '%s\n' 65-smallphone-status.sh
      ;;
    repair)
      printf '%s\n' \
        50-install-runtime-components.sh \
        47-install-hermes.sh \
        48-sync-openhouse-registry.sh \
        60-start-smallphone.sh \
        65-smallphone-status.sh
      ;;
    full|install|""|menu)
      printf '%s\n' \
        00-check-termux.sh \
        10-prepare-termux.sh \
        12-update-termux-packages.sh \
        20-install-ubuntu.sh \
        35-sync-docs.sh \
        30-update-ubuntu-packages.sh \
        70-configure-entry-ubuntu.sh \
        38-install-node.sh \
        40-install-opencode.sh \
        42-install-codex.sh \
        44-install-claude-code.sh \
        45-install-claude-code-ui.sh \
        46-install-reasonix.sh \
        50-install-runtime-components.sh \
        47-install-hermes.sh \
        48-sync-openhouse-registry.sh \
        60-start-smallphone.sh \
        65-smallphone-status.sh
      ;;
    *)
      return 1
      ;;
  esac
}

ensure_local_layout() {
  local command="${1:-full}"
  local name

  if [ -d "$SCRIPT_DIR/scripts" ]; then
    return 0
  fi

  mkdir -p "$SMALLPHONEAI_DIR/scripts"

  ensure_termux_curl

  log "正在从 $SMALLPHONEAI_RAW_BASE 下载当前动作需要的阶段脚本"
  for name in $(required_stage_scripts "$command"); do
    download_file "$SMALLPHONEAI_RAW_BASE/scripts/$name" "$SMALLPHONEAI_DIR/scripts/$name"
    chmod +x "$SMALLPHONEAI_DIR/scripts/$name"
  done
}

script_path() {
  local name="$1"
  if [ -f "$SCRIPT_DIR/scripts/$name" ]; then
    printf '%s/scripts/%s\n' "$SCRIPT_DIR" "$name"
  else
    printf '%s/scripts/%s\n' "$SMALLPHONEAI_DIR" "$name"
  fi
}

root_path() {
  if [ -d "$SCRIPT_DIR/scripts" ]; then
    printf '%s\n' "$SCRIPT_DIR"
  else
    printf '%s\n' "$SMALLPHONEAI_DIR"
  fi
}

run_stage() {
  local name="$1"
  shift || true
  local path
  local root
  path="$(script_path "$name")"
  root="$(root_path)"
  [ -f "$path" ] || die "缺少阶段脚本：$path"
  chmod +x "$path"
  log "开始：$name"
  SMALLPHONEAI_ROOT="$root" bash "$path" "$@"
  log "完成：$name"
}

run_machine_stage() {
  local name="$1"
  shift || true
  local path
  local root
  path="$(script_path "$name")"
  root="$(root_path)"
  [ -f "$path" ] || die "缺少阶段脚本：$path"
  chmod +x "$path"
  SMALLPHONEAI_ROOT="$root" bash "$path" "$@"
}

run_full_install() {
  run_stage 00-check-termux.sh
  run_stage 10-prepare-termux.sh
  run_stage 12-update-termux-packages.sh
  run_stage 20-install-ubuntu.sh
  run_stage 35-sync-docs.sh
  run_stage 30-update-ubuntu-packages.sh
  run_stage 70-configure-entry-ubuntu.sh
  run_stage 38-install-node.sh
  run_stage 40-install-opencode.sh
  run_stage 42-install-codex.sh
  run_stage 44-install-claude-code.sh
  run_stage 45-install-claude-code-ui.sh
  run_stage 46-install-reasonix.sh
  run_stage 50-install-runtime-components.sh
  run_stage 47-install-hermes.sh
  run_stage 48-sync-openhouse-registry.sh
  run_stage 60-start-smallphone.sh
  run_machine_stage 65-smallphone-status.sh status
}

show_menu() {
  cat <<EOF
SmallPhoneAI Installer

1. 完整安装并启动 SmallPhone
2. 查看 SmallPhoneAI 机器可读状态
3. 只准备 Termux 路径、配置和文档
4. 只安装 Termux 基础包
5. 只安装 Ubuntu
6. 只同步 SmallPhoneAI 文档
7. 只更新 Ubuntu 软件包
8. 设置默认进入 Ubuntu
9. 只安装 Node.js 24 LTS
10. 只安装 OpenCode
11. 只安装 Codex
12. 只安装 Claude Code
13. 只安装 ClaudeCodeUI / CloudCLI
14. 只安装 Reasonix
15. 安装/注册 SmallPhone 运行组件
16. 只安装/注册 Hermes
17. 同步 OpenHouseAI registry
18. 启动 SmallPhone 运行栈
19. 修复 SmallPhone 运行栈
20. 查看 App Shell hooks
21. 只检查 Termux 环境
22. 退出
EOF
}

main() {
  ensure_supported_runtime
  ensure_local_layout "${1:-full}" || die "未知命令：${1:-}"

  case "${1:-}" in
    full|install)
      run_full_install
      return
      ;;
    check|status)
      run_machine_stage 65-smallphone-status.sh status
      return
      ;;
    hooks)
      run_machine_stage 65-smallphone-status.sh hooks
      return
      ;;
    env-check|termux-check)
      run_stage 00-check-termux.sh
      return
      ;;
    prepare)
      run_stage 10-prepare-termux.sh
      return
      ;;
    termux-packages)
      run_stage 12-update-termux-packages.sh
      return
      ;;
    ubuntu)
      run_stage 20-install-ubuntu.sh
      return
      ;;
    sync-docs)
      run_stage 35-sync-docs.sh
      return
      ;;
    ubuntu-packages)
      run_stage 30-update-ubuntu-packages.sh
      return
      ;;
    entry-ubuntu)
      run_stage 70-configure-entry-ubuntu.sh
      return
      ;;
    node|install-node)
      run_stage 38-install-node.sh
      return
      ;;
    opencode)
      run_stage 40-install-opencode.sh
      return
      ;;
    codex)
      run_stage 42-install-codex.sh
      return
      ;;
    claude-code)
      run_stage 44-install-claude-code.sh
      return
      ;;
    claude-code-ui|cloudcli)
      run_stage 45-install-claude-code-ui.sh
      return
      ;;
    hermes|hermes-webui)
      run_stage 47-install-hermes.sh
      return
      ;;
    registry-sync|sync-registry)
      run_stage 48-sync-openhouse-registry.sh
      return
      ;;
    reasonix)
      run_stage 46-install-reasonix.sh
      return
      ;;
    components|runtime-components)
      run_stage 50-install-runtime-components.sh
      return
      ;;
    start|restart)
      run_stage 60-start-smallphone.sh
      run_machine_stage 65-smallphone-status.sh status
      return
      ;;
    repair)
      run_stage 50-install-runtime-components.sh
      run_stage 47-install-hermes.sh
      run_stage 48-sync-openhouse-registry.sh
      run_stage 60-start-smallphone.sh
      run_machine_stage 65-smallphone-status.sh status
      return
      ;;
    ""|menu)
      ;;
    *)
      die "未知命令：$1"
      ;;
  esac

  while true; do
    show_menu
    printf '请选择 [1-22]: '
    read -r choice
    case "$choice" in
      1) run_full_install ;;
      2) run_machine_stage 65-smallphone-status.sh status ;;
      3) run_stage 10-prepare-termux.sh ;;
      4) run_stage 12-update-termux-packages.sh ;;
      5) run_stage 20-install-ubuntu.sh ;;
      6) run_stage 35-sync-docs.sh ;;
      7) run_stage 30-update-ubuntu-packages.sh ;;
      8) run_stage 70-configure-entry-ubuntu.sh ;;
      9) run_stage 38-install-node.sh ;;
      10) run_stage 40-install-opencode.sh ;;
      11) run_stage 42-install-codex.sh ;;
      12) run_stage 44-install-claude-code.sh ;;
      13) run_stage 45-install-claude-code-ui.sh ;;
      14) run_stage 46-install-reasonix.sh ;;
      15) run_stage 50-install-runtime-components.sh ;;
      16) run_stage 47-install-hermes.sh ;;
      17) run_stage 48-sync-openhouse-registry.sh ;;
      18) run_stage 60-start-smallphone.sh; run_machine_stage 65-smallphone-status.sh status ;;
      19) run_stage 50-install-runtime-components.sh; run_stage 47-install-hermes.sh; run_stage 48-sync-openhouse-registry.sh; run_stage 60-start-smallphone.sh; run_machine_stage 65-smallphone-status.sh status ;;
      20) run_machine_stage 65-smallphone-status.sh hooks ;;
      21) run_stage 00-check-termux.sh ;;
      22) exit 0 ;;
      *) log "请输入 1 到 22。" ;;
    esac
  done
}

main "$@"
