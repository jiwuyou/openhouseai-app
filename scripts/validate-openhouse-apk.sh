#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
apk_root="$repo_dir/app/build/outputs/apk"
max_arm64_bytes=$((220 * 1024 * 1024))
max_universal_bytes=$((300 * 1024 * 1024))
max_debug_arm64_bytes=$((512 * 1024 * 1024))
max_debug_universal_bytes=$((1536 * 1024 * 1024))
bundle_source="$repo_dir/app/src/main/assets/wuxianpi-install/openhouse-install-bundle.tar"
bundle_metadata_source="$repo_dir/app/src/main/assets/wuxianpi-install/bundle-index.json"
min_mtime="${OPENHOUSE_APK_MIN_MTIME:-0}"

command -v unzip >/dev/null 2>&1 || { printf 'unzip is required\n' >&2; exit 2; }
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
aapt="$(find_build_tool aapt)"
[[ -x "$aapt" ]] || { printf 'aapt is required for APK identity checks\n' >&2; exit 2; }
[[ -s "$bundle_source" && -s "$bundle_metadata_source" ]] \
  || { printf 'canonical install bundle assets are missing\n' >&2; exit 1; }
bundle_sha="$(sha256sum "$bundle_source" | awk '{print $1}')"
aio_icon_source="$repo_dir/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png"
native_icon_source="$repo_dir/native-app/src/main/res/drawable-nodpi/ic_launcher_native_foreground.png"
[[ -s "$aio_icon_source" && -s "$native_icon_source" ]] \
  || { printf 'edition-specific launcher icon sources are missing\n' >&2; exit 1; }
[[ "$(sha256sum "$aio_icon_source" | awk '{print $1}')" != "$(sha256sum "$native_icon_source" | awk '{print $1}')" ]] \
  || { printf 'All-in-One and Native launcher icon sources must differ\n' >&2; exit 1; }

if [[ "$#" -gt 0 ]]; then
  apks=("$@")
  for apk in "${apks[@]}"; do
    [[ -s "$apk" ]] || { printf 'APK output is missing or empty: %s\n' "$apk" >&2; exit 1; }
  done
else
  mapfile -t apks < <(find "$apk_root" -type f -name '*.apk' -printf '%T@ %p\n' 2>/dev/null \
    | awk -v min="$min_mtime" '$1 >= min { $1=""; sub(/^ /, ""); print }' | sort -r)
fi
[[ "${#apks[@]}" -gt 0 ]] || { printf 'no APK outputs found under %s\n' "$apk_root" >&2; exit 1; }

# The base APK only opens user-confirmed DIAL/SENDTO intents. It must not
# request dangerous call/SMS permissions; the permissions screen may still
# describe those permissions when inspecting another installed application.
base_operit_manifest="$repo_dir/operit-feature/src/main/AndroidManifest.xml"
if rg -n -q 'android\.permission\.(CALL_PHONE|SEND_SMS)' "$base_operit_manifest"; then
  printf 'Base Operit feature must not declare CALL_PHONE or SEND_SMS\n' >&2
  exit 1
fi

for apk in "${apks[@]}"; do
  if [[ "$apk" == */release/* ]]; then
    "$repo_dir/scripts/validate-quickjs-jni-contract.sh"
    break
  fi
done

checked=0
for apk in "${apks[@]}"; do
  name="$(basename "$apk")"
  case "$name" in
    *arm64-v8a.apk)
      if [[ "$apk" == */debug/* ]]; then
        limit="${OPENHOUSE_DEBUG_MAX_ARM64_BYTES:-$max_debug_arm64_bytes}"
      else
        limit="$max_arm64_bytes"
      fi
      label="arm64"
      ;;
    *_universal.apk)
      if [[ "$apk" == */debug/* ]]; then
        limit="${OPENHOUSE_DEBUG_MAX_UNIVERSAL_BYTES:-$max_debug_universal_bytes}"
      else
        limit="$max_universal_bytes"
      fi
      label="universal"
      ;;
    *native-app*.apk)
      limit="${OPENHOUSE_DEBUG_MAX_ARM64_BYTES:-$max_debug_arm64_bytes}"
      label="native-arm64"
      ;;
    *) printf 'APK retained for testing, not release-checked: %s\n' "$name"; continue ;;
  esac
  size="$(stat -c '%s' "$apk")"
  (( size <= limit )) || { printf '%s APK exceeds size gate: %s bytes\n' "$label" "$size" >&2; exit 1; }
  unzip -tqq "$apk" || { printf 'APK ZIP test failed: %s\n' "$apk" >&2; exit 1; }
  badging="$($aapt dump badging "$apk")"
  permissions="$($aapt dump permissions "$apk")"
  if grep -Eq "uses-permission: name='android\.permission\.(CALL_PHONE|SEND_SMS)'" <<<"$permissions"; then
    printf 'Base APK declares optional call/SMS permission: %s\n' "$apk" >&2
    exit 1
  fi
  package_name="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  application_label="$(sed -n "s/^application-label:'\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  version_code="$(sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  version_name="$(sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  case "$name" in
    *native-app*.apk)
      expected_package="com.wuxianpi"
      expected_icon_name="ic_launcher_native"
      ;;
    *)
      expected_package="com.termux"
      expected_icon_name="ic_launcher"
      ;;
  esac
  [[ "$package_name" == "$expected_package" ]] \
    || { printf 'Unexpected APK package: expected=%s actual=%s (%s)\n' "$expected_package" "$package_name" "$apk" >&2; exit 1; }
  [[ "$application_label" == "OpenHouse" ]] \
    || { printf 'APK application label must be OpenHouse: actual=%s (%s)\n' "$application_label" "$apk" >&2; exit 1; }
  expected_version_code="${OPENHOUSE_APK_VERSION_CODE:-$(sed -n 's/^openhouseVersionCode=//p' "$repo_dir/gradle.properties" | head -n 1)}"
  expected_version_name="${OPENHOUSE_APK_VERSION_NAME:-$(sed -n 's/^openhouseVersionName=//p' "$repo_dir/gradle.properties" | head -n 1)}"
  [[ "$version_code" == "$expected_version_code" && "$version_name" == "$expected_version_name" ]] \
    || { printf 'APK version mismatch: expected=%s/%s actual=%s/%s (%s)\n' \
      "$expected_version_code" "$expected_version_name" "$version_code" "$version_name" "$apk" >&2; exit 1; }
  icon_path="$(sed -n "s/^application:.* icon='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)"
  icon_file="${icon_path##*/}"
  [[ "${icon_file%.*}" == "$expected_icon_name" ]] \
    || { printf 'APK launcher icon mismatch: expected=%s actual=%s (%s)\n' \
      "$expected_icon_name" "$icon_path" "$apk" >&2; exit 1; }
  entries="$(unzip -Z1 "$apk")"
  grep -Fxq 'assets/wuxianpi-install/openhouse-install-bundle.tar' <<<"$entries" \
    || { printf '%s APK is missing openhouse-install-bundle.tar\n' "$label" >&2; exit 1; }
  grep -Fxq 'assets/wuxianpi-install/bundle-index.json' <<<"$entries" \
    || { printf '%s APK is missing install bundle index\n' "$label" >&2; exit 1; }
  if grep -Eq '^assets/openhouse-resources-v2/' <<<"$entries"; then
    printf '%s APK contains retired Native per-resource assets\n' "$label" >&2
    exit 1
  fi
  for retired in service-manager.tgz openhouse-control-plane.tgz runtime-aarch64.tgz wuyou.tgz openhouse-web.tgz resource-set.json; do
    if grep -Fxq "assets/openhouse/product-payloads/$retired" <<<"$entries"; then
      printf '%s APK duplicates bundled resource asset: %s\n' "$label" "$retired" >&2
      exit 1
    fi
  done
  if grep -Eq '(^|/)(pi-runtime\.tar|openhouse-connect\.(tar|tgz)|smallphone\.(tar|tgz)|github-config-helper\.(tar|tgz)|cc-switch-cli-.*\.tgz)$' <<<"$entries"; then
    printf '%s APK contains a non-core or legacy payload\n' "$label" >&2
    exit 1
  fi
  actual_sha="$(unzip -p "$apk" assets/wuxianpi-install/openhouse-install-bundle.tar | sha256sum | awk '{print $1}')"
  [[ "$actual_sha" = "$bundle_sha" ]] || { printf '%s APK install bundle checksum mismatch\n' "$label" >&2; exit 1; }
  cmp -s <(unzip -p "$apk" assets/wuxianpi-install/bundle-index.json) "$bundle_metadata_source" \
    || { printf '%s APK install bundle index mismatch\n' "$label" >&2; exit 1; }
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
