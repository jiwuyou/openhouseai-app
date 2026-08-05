#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "$#" -gt 0 ]] || { printf 'Usage: %s APK...\n' "$0" >&2; exit 2; }

find_build_tool() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return
  fi
  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  [[ -n "$sdk_root" ]] || return 1
  find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name "$name" 2>/dev/null \
    | sort -V | tail -n 1
}

apksigner="$(find_build_tool apksigner)"
zipalign="$(find_build_tool zipalign)"
aapt="$(find_build_tool aapt)"
[[ -x "$apksigner" && -x "$zipalign" && -x "$aapt" ]] \
  || { printf 'Android build tools aapt/apksigner/zipalign are required\n' >&2; exit 2; }

cd "$repo_dir"
git_commit="$(git rev-parse HEAD)"
[[ -z "$(git status --porcelain --untracked-files=normal)" ]] \
  || { printf 'Refusing to report APK from a dirty worktree\n' >&2; exit 1; }

expected_cert="$(keytool -list -v -keystore app/testkey_untrusted.jks \
  -storepass xrj45yWGLbsO7W0v -alias alias 2>/dev/null \
  | awk '/SHA256:/{gsub(":", "", $2); print tolower($2); exit}')"

report_dir="$repo_dir/build/reports/apk-build"
mkdir -p "$report_dir"

for apk_arg in "$@"; do
  apk="$(realpath "$apk_arg")"
  [[ -s "$apk" ]] || { printf 'APK is missing or empty: %s\n' "$apk_arg" >&2; exit 1; }
  unzip -tqq "$apk"
  "$zipalign" -c -P 16 -v 4 "$apk" >/dev/null
  signer_output="$("$apksigner" verify --verbose --print-certs "$apk")"
  grep -Fq 'Verified using v2 scheme (APK Signature Scheme v2): true' <<<"$signer_output" \
    || { printf 'APK v2 signature verification failed: %s\n' "$apk" >&2; exit 1; }
  cert_sha="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/{print tolower($2); exit}' <<<"$signer_output")"
  [[ "$cert_sha" == "$expected_cert" ]] \
    || { printf 'APK is not signed with app/testkey_untrusted.jks: %s\n' "$apk" >&2; exit 1; }

  entries="$(unzip -Z1 "$apk")"
  if grep -Fxq 'assets/openhouse-runtime/runtime-aarch64.tgz' <<<"$entries"; then
    runtime_path='assets/openhouse-runtime/runtime-aarch64.tgz'
  elif grep -Fxq 'assets/openhouse/product-payloads/runtime-aarch64.tgz' <<<"$entries"; then
    runtime_path='assets/openhouse/product-payloads/runtime-aarch64.tgz'
  else
    printf 'APK is missing the canonical runtime-aarch64.tgz asset: %s\n' "$apk" >&2
    exit 1
  fi
  if grep -Eq '(^|/)(pi-runtime\.tar|runtime-aarch64\.tar|runtime-aarch64\.tar\.gz)$' <<<"$entries"; then
    printf 'APK contains a legacy Runtime archive: %s\n' "$apk" >&2
    exit 1
  fi

  abi="$(awk -F/ '/^lib\//{print $2}' <<<"$entries" | sort -u | paste -sd, -)"
  [[ -n "$abi" ]] || abi='none'
  package_name="$("$aapt" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n 1)"
  size_bytes="$(stat -c '%s' "$apk")"
  apk_sha="$(sha256sum "$apk" | awk '{print $1}')"
  report="$report_dir/$(basename "$apk").txt"

  {
    printf 'git_commit=%s\n' "$git_commit"
    printf 'dirty=false\n'
    printf 'apk_path=%s\n' "$apk"
    printf 'size_bytes=%s\n' "$size_bytes"
    printf 'sha256=%s\n' "$apk_sha"
    printf 'package_name=%s\n' "$package_name"
    printf 'abi=%s\n' "$abi"
    printf 'signature_v2=true\n'
    printf 'signing_certificate_sha256=%s\n' "$cert_sha"
    printf 'signing_key=app/testkey_untrusted.jks\n'
    printf 'zipalign=passed\n'
    printf 'asset_check=passed:%s\n' "$runtime_path"
  } | tee "$report"
done
