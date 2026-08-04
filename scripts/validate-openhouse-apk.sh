#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk_root="$repo_dir/app/build/outputs/apk"
max_arm64_bytes=$((220 * 1024 * 1024))
max_universal_bytes=$((300 * 1024 * 1024))
runtime_source="$repo_dir/app/src/main/assets/openhouse/product-payloads/runtime-aarch64.tgz"
native_source="$repo_dir/native-app/src/main/assets/openhouse-runtime/runtime-aarch64.tgz"
min_mtime="${OPENHOUSE_APK_MIN_MTIME:-0}"

command -v unzip >/dev/null 2>&1 || { printf 'unzip is required\n' >&2; exit 2; }
[[ -s "$runtime_source" && -s "$native_source" ]] || { printf 'shared runtime assets are missing\n' >&2; exit 1; }
runtime_sha="$(sha256sum "$runtime_source" | awk '{print $1}')"
native_sha="$(sha256sum "$native_source" | awk '{print $1}')"
[[ "$runtime_sha" = "$native_sha" ]] || { printf 'shared runtime source checksum mismatch\n' >&2; exit 1; }

mapfile -t apks < <(find "$apk_root" -type f -name '*.apk' -printf '%T@ %p\n' 2>/dev/null \
  | awk -v min="$min_mtime" '$1 >= min { $1=""; sub(/^ /, ""); print }' | sort -r)
[[ "${#apks[@]}" -gt 0 ]] || { printf 'no APK outputs found under %s\n' "$apk_root" >&2; exit 1; }

checked=0
for apk in "${apks[@]}"; do
  name="$(basename "$apk")"
  case "$name" in
    *arm64-v8a.apk)
      limit="$max_arm64_bytes"
      label="arm64"
      ;;
    *_universal.apk)
      limit="$max_universal_bytes"
      label="universal"
      ;;
    *)
      printf 'APK retained for testing, not release-checked: %s\n' "$name"
      continue
      ;;
  esac
  size="$(stat -c '%s' "$apk")"
  (( size <= limit )) || { printf '%s APK exceeds size gate: %s bytes\n' "$label" "$size" >&2; exit 1; }
  unzip -tqq "$apk" || { printf 'APK ZIP test failed: %s\n' "$apk" >&2; exit 1; }
  entries="$(unzip -Z1 "$apk")"
  grep -Fxq 'assets/openhouse/product-payloads/runtime-aarch64.tgz' <<<"$entries" \
    || { printf '%s APK is missing runtime-aarch64.tgz\n' "$label" >&2; exit 1; }
  if grep -Eq '(^|/)(pi-runtime\.tar|openhouse-connect\.(tar|tgz)|smallphone\.(tar|tgz)|github-config-helper\.(tar|tgz)|cc-switch-cli-.*\.tgz)$' <<<"$entries"; then
    printf '%s APK contains a non-core or legacy payload\n' "$label" >&2
    exit 1
  fi
  actual_sha="$(unzip -p "$apk" assets/openhouse/product-payloads/runtime-aarch64.tgz | sha256sum | awk '{print $1}')"
  [[ "$actual_sha" = "$runtime_sha" ]] || { printf '%s APK runtime checksum mismatch\n' "$label" >&2; exit 1; }
  if command -v apksigner >/dev/null 2>&1; then
    apksigner verify --verbose "$apk" >/dev/null || { printf 'APK signature verification failed: %s\n' "$apk" >&2; exit 1; }
  fi
  if command -v zipalign >/dev/null 2>&1; then
    zipalign -c -p 4 "$apk" >/dev/null || { printf 'APK zipalign verification failed: %s\n' "$apk" >&2; exit 1; }
  fi
  printf 'APK gate passed: %s (%s bytes, %s)\n' "$apk" "$size" "$label"
  checked=$((checked + 1))
done

(( checked > 0 )) || { printf 'no arm64 or universal APK was found\n' >&2; exit 1; }
