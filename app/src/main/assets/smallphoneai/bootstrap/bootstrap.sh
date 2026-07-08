#!/usr/bin/env bash
set -euo pipefail

SMALLPHONEAI_DIR="${SMALLPHONEAI_DIR:-$HOME/.smallphoneai-bootstrap}"
SMALLPHONEAI_RAW_BASE="${SMALLPHONEAI_RAW_BASE:-https://raw.githubusercontent.com/jiwuyou/openhouseai-bootstrap/main}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -f "$SCRIPT_DIR/scripts/_retry-profile.sh" ]; then
  # shellcheck source=scripts/_retry-profile.sh
  . "$SCRIPT_DIR/scripts/_retry-profile.sh"
fi

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
  local repo_url="${OPENHOUSEAI_TERMUX_MAIN_REPO:-${SMALLPHONEAI_TERMUX_MAIN_REPO:-}}"

  [ -n "${PREFIX:-}" ] || return 0
  [ -d "$(dirname "$sources_file")" ] || return 0

  if [ -z "$repo_url" ]; then
    repo_url="$(select_stable_termux_main_repo)"
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

write_termux_main_repo() {
  local repo_url="$1"
  local sources_file="${PREFIX:-/data/data/com.termux/files/usr}/etc/apt/sources.list"

  [ -n "${PREFIX:-}" ] || return 0
  [ -d "$(dirname "$sources_file")" ] || return 0

  if [ -f "$sources_file" ] && grep -Fq "$repo_url" "$sources_file"; then
    log "Termux main 镜像源已是：$repo_url"
    return 0
  fi

  log "切换 Termux main 镜像源：$repo_url"
  cp "$sources_file" "$sources_file.smallphoneai.bak" 2>/dev/null || true
  printf 'deb %s stable main\n' "$repo_url" > "$sources_file"
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
  metrics="$(curl -fsSL --connect-timeout 6 --max-time 24 --speed-time 8 --speed-limit 4096 -r 0-524287 -o /dev/null -w '%{http_code} %{size_download} %{time_total}' "$probe_url" 2>/dev/null || true)"
  http_code="$(printf '%s' "$metrics" | awk '{print $1}')"
  bytes="$(printf '%s' "$metrics" | awk '{print $2}')"
  total="$(printf '%s' "$metrics" | awk '{print $3}')"
  if { [ "$http_code" = "200" ] || [ "$http_code" = "206" ]; } && [ -n "$bytes" ] && [ -n "$total" ]; then
    speed="$(awk "BEGIN { if ($total > 0) printf \"%.0f\", $bytes / $total; else printf \"0\" }" 2>/dev/null || printf '0')"
    printf '[SmallPhoneAI] Termux 镜像吞吐探测：%s %sB/s（%s bytes, %ss）\n' "$repo" "$speed" "$bytes" "$total" >&2
    printf '%s\n' "$speed"
    return 0
  fi

  printf '[SmallPhoneAI] Termux 镜像不可用或吞吐探测失败：%s\n' "$repo" >&2
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
    printf '[SmallPhoneAI] Termux 镜像仅通过可用性兜底探测：%s（未获得 Packages 吞吐）\n' "$repo" >&2
    printf '%s\n' "1"
    return 0
  fi

  printf '[SmallPhoneAI] Termux 镜像不可用：%s\n' "$repo" >&2
  return 1
}

select_stable_termux_main_repo() {
  local repo best_repo best_speed speed min_speed
  best_repo=""
  best_speed="0"
  min_speed="${OPENHOUSEAI_TERMUX_MIN_REPO_SPEED_BPS:-${SMALLPHONEAI_TERMUX_MIN_REPO_SPEED_BPS:-65536}}"

  if ! command -v curl >/dev/null 2>&1; then
    repo="$(default_termux_main_repo)"
    printf '[SmallPhoneAI] curl 不可用，无法做镜像吞吐探测，使用默认 Termux main 镜像源：%s\n' "$repo" >&2
    printf '%s\n' "$repo"
    return 0
  fi

  for repo in $(termux_main_repo_candidates); do
    if speed="$(probe_termux_repo_throughput "$repo")"; then
      if awk "BEGIN { exit !($speed >= $min_speed) }"; then
        printf '[SmallPhoneAI] 选择 Termux main 镜像源：%s（固定优先级内吞吐达标）\n' "$repo" >&2
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
    printf '[SmallPhoneAI] 没有镜像达到最低吞吐 %sB/s，使用当前最优 Termux main 镜像源：%s（%sB/s）\n' "$min_speed" "$best_repo" "$best_speed" >&2
    printf '%s\n' "$best_repo"
  else
    repo="$(default_termux_main_repo)"
    printf '[SmallPhoneAI] Termux 镜像吞吐探测全部失败，使用默认源：%s\n' "$repo" >&2
    printf '%s\n' "$repo"
  fi
}

run_with_optional_timeout() {
  local timeout_seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$timeout_seconds" "$@"
  else
    "$@"
  fi
}

run_termux_apt_update() {
  local timeout_seconds="${OPENHOUSEAI_TERMUX_APT_UPDATE_TIMEOUT_SECONDS:-${SMALLPHONEAI_TERMUX_APT_UPDATE_TIMEOUT_SECONDS:-300}}"
  if command -v apt >/dev/null 2>&1; then
    run_logged run_with_optional_timeout "$timeout_seconds" apt \
      -o Acquire::Retries=2 \
      -o Acquire::http::Timeout=30 \
      -o Acquire::https::Timeout=30 \
      update
  elif command -v pkg >/dev/null 2>&1; then
    run_logged run_with_optional_timeout "$timeout_seconds" pkg update -y
  else
    return 1
  fi
}

run_termux_apt_install() {
  local timeout_seconds="${OPENHOUSEAI_TERMUX_APT_INSTALL_TIMEOUT_SECONDS:-${SMALLPHONEAI_TERMUX_APT_INSTALL_TIMEOUT_SECONDS:-1800}}"
  if command -v apt >/dev/null 2>&1; then
    run_logged run_with_optional_timeout "$timeout_seconds" apt \
      -o Acquire::Retries=2 \
      -o Acquire::http::Timeout=30 \
      -o Acquire::https::Timeout=30 \
      install -y "$@"
  elif command -v pkg >/dev/null 2>&1; then
    run_logged run_with_optional_timeout "$timeout_seconds" pkg install -y "$@"
  else
    return 1
  fi
}

repair_termux_package_state() {
  local timeout_seconds="${OPENHOUSEAI_TERMUX_APT_REPAIR_TIMEOUT_SECONDS:-${SMALLPHONEAI_TERMUX_APT_REPAIR_TIMEOUT_SECONDS:-300}}"
  if command -v dpkg >/dev/null 2>&1; then
    log "尝试修复 dpkg 半配置状态。"
    run_with_optional_timeout "$timeout_seconds" dpkg --configure -a || true
  fi
  if command -v apt >/dev/null 2>&1; then
    run_with_optional_timeout "$timeout_seconds" apt \
      -o Acquire::Retries=1 \
      -o Acquire::http::Timeout=20 \
      -o Acquire::https::Timeout=20 \
      -f install -y || true
  fi
}

termux_repo_retry_order() {
  local selected="$1"
  local override repo
  override="${OPENHOUSEAI_TERMUX_MAIN_REPO:-${SMALLPHONEAI_TERMUX_MAIN_REPO:-}}"
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

install_termux_curl_dependencies() {
  local selected_repo repo status
  selected_repo="${OPENHOUSEAI_TERMUX_MAIN_REPO:-${SMALLPHONEAI_TERMUX_MAIN_REPO:-}}"
  if [ -z "$selected_repo" ]; then
    selected_repo="$(select_stable_termux_main_repo)"
  fi

  for repo in $(termux_repo_retry_order "$selected_repo"); do
    write_termux_main_repo "$repo"
    log "正在更新 Termux 包索引并修复 curl 网络依赖（源：$repo）。"

    if run_termux_apt_update; then
      :
    else
      status="$?"
      log "Termux 包索引更新失败或超时（退出码：$status，源：$repo），准备尝试下一个镜像源。"
      repair_termux_package_state
      continue
    fi

    if run_termux_apt_install curl libcurl libngtcp2 libnghttp2 openssl ca-certificates; then
      return 0
    fi

    status="$?"
    log "curl 网络依赖安装失败或超时（退出码：$status，源：$repo），准备修复状态并尝试下一个镜像源。"
    repair_termux_package_state
  done

  return 1
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

  command -v apt >/dev/null 2>&1 || command -v pkg >/dev/null 2>&1 || die "curl 不可用，且缺少 apt/pkg，无法自动修复。"

  install_termux_curl_dependencies || true

  curl --version >/dev/null 2>&1 || die "curl 修复失败，请先执行：apt update && apt install -y curl libcurl libngtcp2 openssl ca-certificates"
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
    node)
      printf '%s\n' 38-install-node.sh
      ;;
    codex)
      printf '%s\n' 42-install-codex.sh
      ;;
    cc-switch)
      printf '%s\n' 43-install-cc-switch.sh
      ;;
    claude-code)
      printf '%s\n' 44-install-claude-code.sh
      ;;
    claude-code-ui|cloudcli)
      printf '%s\n' 45-install-claude-code-ui.sh
      ;;
    components|runtime-components)
      printf '%s\n' 50-install-runtime-components.sh
      ;;
    registry-sync|sync-registry)
      printf '%s\n' 48-sync-openhouse-registry.sh
      ;;
    sync-core-stack|post-apk-update|apk-update)
      printf '%s\n' \
        50-install-runtime-components.sh \
        48-sync-openhouse-registry.sh \
        60-start-smallphone.sh \
        65-smallphone-status.sh
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
        30-update-ubuntu-packages.sh \
        70-configure-entry-ubuntu.sh \
        38-install-node.sh \
        35-sync-docs.sh \
        50-install-runtime-components.sh \
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
  download_file "$SMALLPHONEAI_RAW_BASE/scripts/_retry-profile.sh" "$SMALLPHONEAI_DIR/scripts/_retry-profile.sh" || true
  chmod +x "$SMALLPHONEAI_DIR/scripts/_retry-profile.sh" 2>/dev/null || true
  if ! command -v smallphoneai_apply_retry_profile >/dev/null 2>&1 && [ -f "$SMALLPHONEAI_DIR/scripts/_retry-profile.sh" ]; then
    # shellcheck source=scripts/_retry-profile.sh
    . "$SMALLPHONEAI_DIR/scripts/_retry-profile.sh"
  fi
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
  cleanup_stage_retry_temp "$name"
  SMALLPHONEAI_ROOT="$root" \
    OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
    SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
    SMALLPHONEAI_FORCE_PAYLOAD_REFRESH="${SMALLPHONEAI_FORCE_PAYLOAD_REFRESH:-0}" \
    bash "$path" "$@"
  log "完成：$name"
}

cleanup_stage_retry_temp() {
  local name="$1"
  local requested="${OPENHOUSE_FORCE_RETRY_STAGE:-${SMALLPHONEAI_FORCE_RETRY_STAGE:-}}"
  local force="${OPENHOUSE_FORCE_RETRY:-${SMALLPHONEAI_FORCE_RETRY:-0}}"

  [ "$force" = "1" ] || return 0
  if [ -n "$requested" ] && [ "$requested" != "$name" ] && [ "$requested" != "${name%.sh}" ]; then
    return 0
  fi

  log "强制重试当前阶段：只清理 $name 的临时文件，不删除用户数据、配置、日志或已完成 payload。"
  case "$name" in
    20-install-ubuntu.sh)
      rm -f "$HOME/.smallphoneai-bootstrap/smallphoneai-env-probe-ubuntu.sh" 2>/dev/null || true
      ;;
    38-install-node.sh)
      rm -rf "$HOME/.local/node-download" 2>/dev/null || true
      ;;
    50-install-runtime-components.sh)
      find "${TMPDIR:-/tmp}" -maxdepth 1 -type d -name 'smallphoneai-payload.*' -exec rm -rf {} + 2>/dev/null || true
      ;;
  esac
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
  SMALLPHONEAI_ROOT="$root" \
    OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
    SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
    bash "$path" "$@"
}

run_full_install() {
  run_stage 00-check-termux.sh
  run_stage 10-prepare-termux.sh
  run_stage 12-update-termux-packages.sh
  run_stage 20-install-ubuntu.sh
  run_stage 30-update-ubuntu-packages.sh
  run_stage 70-configure-entry-ubuntu.sh
  run_stage 38-install-node.sh
  run_stage 35-sync-docs.sh
  run_stage 50-install-runtime-components.sh
  run_stage 48-sync-openhouse-registry.sh
  run_stage 60-start-smallphone.sh
  run_machine_stage 65-smallphone-status.sh status
}

show_menu() {
  cat <<EOF
SmallPhoneAI Installer

1. 完整安装并启动 OpenHouse 控制平面
2. 查看 SmallPhoneAI 机器可读状态
3. 只准备 Termux 路径、配置和文档
4. 只安装 Termux 基础包
5. 只安装 Ubuntu
6. 只同步 SmallPhoneAI 文档
7. 只更新 Ubuntu 软件包
8. 设置默认进入 Ubuntu
9. 只安装 Node.js 24 LTS
10. 后置安装 Codex
11. 后置安装 cc-switch
12. 后置安装 Claude Code
13. 后置安装 ClaudeCodeUI / CloudCLI
14. 安装/注册 SmallPhone 运行组件
15. 同步 OpenHouseAI registry
16. 启动 SmallPhone 运行栈
17. 修复 SmallPhone 运行栈
18. APK 更新后同步核心运行栈
19. 查看 App Shell hooks
20. 只检查 Termux 环境
21. 退出
EOF
}

main() {
  if [ $# -ge 2 ]; then
    export OPENHOUSE_RETRY_MODE="$2"
    export SMALLPHONEAI_RETRY_MODE="$2"
  fi
  if command -v smallphoneai_log_retry_profile >/dev/null 2>&1; then
    smallphoneai_log_retry_profile '[SmallPhoneAI]'
  else
    export OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}"
    export SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-$OPENHOUSE_RETRY_MODE}"
  fi
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
    node)
      run_stage 38-install-node.sh
      return
      ;;
    codex)
      run_stage 42-install-codex.sh
      return
      ;;
    cc-switch)
      run_stage 43-install-cc-switch.sh
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
    components|runtime-components)
      run_stage 50-install-runtime-components.sh
      return
      ;;
    registry-sync|sync-registry)
      run_stage 48-sync-openhouse-registry.sh
      return
      ;;
    start|restart)
      run_stage 60-start-smallphone.sh
      run_machine_stage 65-smallphone-status.sh status
      return
      ;;
    sync-core-stack|post-apk-update|apk-update)
      SMALLPHONEAI_FORCE_PAYLOAD_REFRESH=1 run_stage 50-install-runtime-components.sh
      run_stage 48-sync-openhouse-registry.sh
      run_stage 60-start-smallphone.sh
      run_machine_stage 65-smallphone-status.sh status
      return
      ;;
    repair)
      run_stage 50-install-runtime-components.sh
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
    printf '请选择 [1-21]: '
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
      10) run_stage 42-install-codex.sh ;;
      11) run_stage 43-install-cc-switch.sh ;;
      12) run_stage 44-install-claude-code.sh ;;
      13) run_stage 45-install-claude-code-ui.sh ;;
      14) run_stage 50-install-runtime-components.sh ;;
      15) run_stage 48-sync-openhouse-registry.sh ;;
      16) run_stage 60-start-smallphone.sh; run_machine_stage 65-smallphone-status.sh status ;;
      17) run_stage 50-install-runtime-components.sh; run_stage 48-sync-openhouse-registry.sh; run_stage 60-start-smallphone.sh; run_machine_stage 65-smallphone-status.sh status ;;
      18) SMALLPHONEAI_FORCE_PAYLOAD_REFRESH=1 run_stage 50-install-runtime-components.sh; run_stage 48-sync-openhouse-registry.sh; run_stage 60-start-smallphone.sh; run_machine_stage 65-smallphone-status.sh status ;;
      19) run_machine_stage 65-smallphone-status.sh hooks ;;
      20) run_stage 00-check-termux.sh ;;
      21) exit 0 ;;
      *) log "请输入 1 到 21。" ;;
    esac
  done
}

main "$@"
