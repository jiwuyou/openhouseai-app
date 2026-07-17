#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

die() {
  printf '[SmallPhoneAI] ERROR: %s\n' "$*" >&2
  exit 1
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

if is_termux && [ "${SMALLPHONEAI_CC_SWITCH_IN_UBUNTU:-1}" = "1" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    log "正在 Ubuntu 内安装 cc-switch。"
    SMALLPHONEAI_CC_SWITCH_IN_UBUNTU=0 \
      proot-distro login ubuntu -- env \
        SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}" \
        SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT="${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}" \
        bash -s < "$0"
    exit $?
  fi
  die "Ubuntu 尚不可用，无法安装 cc-switch。请先运行：bash bootstrap.sh ubuntu"
fi

is_current_ubuntu || die "cc-switch 应安装到 Ubuntu /root/.local/bin；请在 Ubuntu 中运行或从 Termux 通过 proot-distro 调用。"

archive_name="cc-switch-cli-5.9.0-linux-arm64.tgz"
expected_sha="46ce26be4c1eddfc7a3407eac8820395a2da42db4cb9bf11bf9d4a87b1cfb20e"
expected_size="6693477"
expected_binary_sha="5c59e8ea224d263c58f5665b64e54c9c334380e9e81210f3ee84708643d98cad"
expected_binary_size="15427096"
expected_version="5.9.0"

file_sha256() {
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

find_payload_archive() {
  local dir
  for dir in \
    "${OPENHOUSE_CC_SWITCH_PAYLOAD_DIR:-}" \
    "${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-}" \
    "${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-}" \
    "$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads" \
    "/data/data/com.termux/files/home/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads"; do
    [ -n "$dir" ] || continue
    if [ -f "$dir/$archive_name" ]; then
      printf '%s\n' "$dir/$archive_name"
      return 0
    fi
  done
  return 1
}

archive="$(find_payload_archive)" || die "找不到 APK 内置 cc-switch payload：$archive_name"
actual_size="$(wc -c < "$archive" | tr -d '[:space:]')"
[ "$actual_size" = "$expected_size" ] || die "cc-switch payload size 校验失败：expected=$expected_size actual=$actual_size"

actual_sha="$(file_sha256 "$archive")" || die "无法计算 cc-switch payload sha256"
[ "$actual_sha" = "$expected_sha" ] || die "cc-switch payload sha256 校验失败：expected=$expected_sha actual=$actual_sha"
log "cc-switch payload sha256 校验通过：$actual_sha"

if ! tar -tzf "$archive" | grep -Eq '^(\./)?cc-switch$'; then
  die "cc-switch payload 缺少根目录二进制：cc-switch"
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-cc-switch.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
tar -xzf "$archive" -C "$tmp_dir"
binary="$tmp_dir/cc-switch"
[ -f "$binary" ] || die "cc-switch payload 解包后缺少二进制"

binary_size="$(wc -c < "$binary" | tr -d '[:space:]')"
[ "$binary_size" = "$expected_binary_size" ] || die "cc-switch binary size 校验失败：expected=$expected_binary_size actual=$binary_size"

binary_sha="$(file_sha256 "$binary")" || die "无法计算 cc-switch binary sha256"
[ "$binary_sha" = "$expected_binary_sha" ] || die "cc-switch binary sha256 校验失败：expected=$expected_binary_sha actual=$binary_sha"

mkdir -p "$HOME/.local/bin"
cp "$binary" "$HOME/.local/bin/cc-switch"
chmod 0755 "$HOME/.local/bin/cc-switch"

export PATH="$HOME/.local/bin:$PATH"
version_output="$("$HOME/.local/bin/cc-switch" --version)"
case "$version_output" in
  *"$expected_version"*)
    log "cc-switch 安装完成：$version_output"
    ;;
  *)
    die "cc-switch 版本校验失败：$version_output"
    ;;
esac

cat <<'EOF'

cc-switch 已安装到 /root/.local/bin/cc-switch。
它是 provider 配置执行器，不是长期服务；不要注册到 service-manager。
使用前请阅读 /root/openhouse/docs/cc-switch.md，并在写入 key/token 前确认脱敏和备份策略。
EOF
