#!/usr/bin/env bash

_smallphoneai_retry_profile_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$_smallphoneai_retry_profile_dir/_ubuntu-mirror-policy.sh" ]; then
  # shellcheck source=_ubuntu-mirror-policy.sh
  . "$_smallphoneai_retry_profile_dir/_ubuntu-mirror-policy.sh"
fi

smallphoneai_retry_mode() {
  local raw
  raw="${OPENHOUSE_RETRY_MODE:-${SMALLPHONEAI_RETRY_MODE:-normal}}"
  raw="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  case "$raw" in
    cn|china|mainland|domestic|china-mainland)
      printf 'cn'
      ;;
    general|normal|default|standard|'')
      printf 'normal'
      ;;
    *)
      printf 'normal'
      ;;
  esac
}

smallphoneai_is_cn_retry() {
  [ "$(smallphoneai_retry_mode)" = "cn" ]
}

smallphoneai_cn_termux_main_repo_candidates() {
  cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main
https://mirrors.ustc.edu.cn/termux/apt/termux-main
https://mirrors.bfsu.edu.cn/termux/apt/termux-main
https://mirrors.nju.edu.cn/termux/apt/termux-main
EOF
}

smallphoneai_cn_ubuntu_rootfs_candidates() {
  if command -v smallphoneai_ubuntu_rootfs_candidates >/dev/null 2>&1; then
    smallphoneai_ubuntu_rootfs_candidates arm64
    return 0
  fi
  cat <<'EOF'
https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.nju.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://cloud-images.ubuntu.com/noble/current/noble-server-cloudimg-arm64-root.tar.xz
https://mirrors.ustc.edu.cn/ubuntu-cloud-images/noble/current/noble-server-cloudimg-arm64-root.tar.xz
EOF
}

smallphoneai_apply_retry_profile() {
  local mode
  mode="$(smallphoneai_retry_mode)"
  export OPENHOUSE_RETRY_MODE="$mode"
  export SMALLPHONEAI_RETRY_MODE="$mode"

  if [ "$mode" = "cn" ]; then
    : "${OPENHOUSEAI_TERMUX_MAIN_REPO:=https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main}"
    : "${SMALLPHONEAI_TERMUX_MAIN_REPO:=$OPENHOUSEAI_TERMUX_MAIN_REPO}"
    : "${SMALLPHONEAI_NODE_DIST_BASE:=https://cdn.npmmirror.com/binaries/node/latest-v24.x}"
    : "${NPM_REGISTRY:=https://registry.npmmirror.com}"
    : "${NPM_CONFIG_REGISTRY:=$NPM_REGISTRY}"
    : "${SMALLPHONEAI_NPM_FETCH_RETRIES:=8}"
    : "${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:=20000}"
    : "${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:=180000}"
    : "${SMALLPHONEAI_NPM_FETCH_TIMEOUT:=900000}"
    export OPENHOUSEAI_TERMUX_MAIN_REPO
    export SMALLPHONEAI_TERMUX_MAIN_REPO
    export SMALLPHONEAI_NODE_DIST_BASE
    export NPM_REGISTRY
    export NPM_CONFIG_REGISTRY
    export SMALLPHONEAI_NPM_FETCH_RETRIES
    export SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT
    export SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT
    export SMALLPHONEAI_NPM_FETCH_TIMEOUT
  fi

  export SMALLPHONEAI_RETRY_PROFILE_APPLIED="$mode"
}

smallphoneai_log_retry_profile() {
  local prefix="${1:-[SmallPhoneAI]}"
  smallphoneai_apply_retry_profile
  if smallphoneai_is_cn_retry; then
    printf '%s 网络重试模式：cn；使用 canonical 国内优先策略（Termux=%s, Ubuntu=TUNA->NJU->official->USTC, Node=%s, npm=%s）。\n' \
      "$prefix" \
      "${OPENHOUSEAI_TERMUX_MAIN_REPO:-}" \
      "${SMALLPHONEAI_NODE_DIST_BASE:-}" \
      "${NPM_REGISTRY:-}" >&2
  else
    printf '%s 网络重试模式：normal；使用默认源和已有缓存。\n' "$prefix" >&2
  fi
}

smallphoneai_maybe_rewrite_github_url() {
  local url="$1"
  local prefix="${SMALLPHONEAI_GITHUB_PROXY_PREFIX:-${OPENHOUSE_GITHUB_PROXY_PREFIX:-}}"

  if smallphoneai_is_cn_retry && [ -n "$prefix" ]; then
    case "$url" in
      https://github.com/*|https://raw.githubusercontent.com/*)
        printf '%s%s\n' "$prefix" "$url"
        return 0
        ;;
    esac
  fi

  printf '%s\n' "$url"
}

smallphoneai_sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
    return 0
  fi
  if command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$path" | awk '{print $NF}'
    return 0
  fi
  return 1
}

smallphoneai_verify_sha256() {
  local path="$1"
  local expected="$2"
  local label="${3:-$1}"
  local actual

  [ -n "$expected" ] || return 0
  actual="$(smallphoneai_sha256_file "$path")" || {
    printf '[SmallPhoneAI] 无法计算 sha256：%s\n' "$label" >&2
    return 1
  }

  if [ "$actual" != "$expected" ]; then
    printf '[SmallPhoneAI] sha256 校验失败：%s\nexpected=%s\nactual=%s\n' "$label" "$expected" "$actual" >&2
    return 1
  fi

  printf '[SmallPhoneAI] sha256 校验通过：%s %s\n' "$label" "$actual" >&2
}

smallphoneai_apply_retry_profile
