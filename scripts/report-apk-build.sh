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
"$repo_dir/scripts/validate-apk-version-contract.sh"
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
    artifact_type='native'
    expected_package='com.wuxianpi'
  elif grep -Fxq 'assets/openhouse/product-payloads/runtime-aarch64.tgz' <<<"$entries"; then
    runtime_path='assets/openhouse/product-payloads/runtime-aarch64.tgz'
    artifact_type='all-in-one'
    expected_package='com.termux'
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
  badging="$("$aapt" dump badging "$apk")"
  package_name="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  version_code="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  version_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  [[ "$package_name" == "$expected_package" ]] \
    || { printf 'Unexpected package name for %s APK: expected=%s actual=%s\n' "$artifact_type" "$expected_package" "$package_name" >&2; exit 1; }
  if grep -Fxq 'application-debuggable' <<<"$badging"; then
    debuggable=true
  else
    debuggable=false
  fi
  case "$apk" in
    */debug/*)
      build_type=debug
      [[ "$debuggable" == true ]] \
        || { printf 'Debug distribution APK is not debuggable: %s\n' "$apk" >&2; exit 1; }
      ;;
    */release/*)
      build_type=release
      ;;
    *)
      build_type=unknown
      ;;
  esac
  size_bytes="$(stat -c '%s' "$apk")"
  apk_sha="$(sha256sum "$apk" | awk '{print $1}')"
  report="$report_dir/$(basename "$apk").txt"
  report_json="$report_dir/$(basename "$apk").json"

  {
    printf 'git_commit=%s\n' "$git_commit"
    printf 'dirty=false\n'
    printf 'apk_path=%s\n' "$apk"
    printf 'size_bytes=%s\n' "$size_bytes"
    printf 'sha256=%s\n' "$apk_sha"
    printf 'artifact_type=%s\n' "$artifact_type"
    printf 'build_type=%s\n' "$build_type"
    printf 'debuggable=%s\n' "$debuggable"
    printf 'package_name=%s\n' "$package_name"
    printf 'version_code=%s\n' "$version_code"
    printf 'version_name=%s\n' "$version_name"
    printf 'abi=%s\n' "$abi"
    printf 'signature_v2=true\n'
    printf 'signing_certificate_sha256=%s\n' "$cert_sha"
    printf 'signing_key=app/testkey_untrusted.jks\n'
    printf 'zipalign=passed\n'
    printf 'asset_check=passed:%s\n' "$runtime_path"
  } | tee "$report"

  python3 - \
    "$report_json" "$git_commit" "$apk" "$size_bytes" "$apk_sha" \
    "$artifact_type" "$build_type" "$debuggable" "$package_name" \
    "$version_code" "$version_name" "$abi" "$cert_sha" "$runtime_path" <<'PY'
import json
import sys

(
    output,
    git_commit,
    apk_path,
    size_bytes,
    sha256,
    artifact_type,
    build_type,
    debuggable,
    package_name,
    version_code,
    version_name,
    abi,
    certificate_sha256,
    runtime_asset,
) = sys.argv[1:]

document = {
    "schema": 1,
    "gitCommit": git_commit,
    "dirty": False,
    "artifactType": artifact_type,
    "buildType": build_type,
    "debuggable": debuggable == "true",
    "apkPath": apk_path,
    "fileName": apk_path.rsplit("/", 1)[-1],
    "size": int(size_bytes),
    "sha256": sha256,
    "packageName": package_name,
    "versionCode": int(version_code),
    "versionName": version_name,
    "abis": [value for value in abi.split(",") if value and value != "none"],
    "signingCertificateSha256": certificate_sha256,
    "signingKey": "app/testkey_untrusted.jks",
    "signatureV2": True,
    "zipalign": "passed",
    "runtimeAsset": runtime_asset,
}
with open(output, "w", encoding="utf-8") as handle:
    json.dump(document, handle, ensure_ascii=True, indent=2, sort_keys=True)
    handle.write("\n")
PY
done
