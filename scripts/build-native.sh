#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$repo_dir/scripts/validate-product-baseline.sh"
[[ "${SKIP_RUNTIME_BUILD:-0}" == "1" ]] || "$repo_dir/scripts/build-pi-node-payload.sh"
"$repo_dir/scripts/generate-native-install-resources.sh"
asset="$repo_dir/native-app/src/main/assets/openhouse-runtime/runtime-aarch64.tgz"
[[ -s "$asset" ]] || { printf 'Missing Native ARM64 runtime asset: %s\n' "$asset" >&2; exit 1; }
for required in ./install.sh ./bin/wuxianpi ./bin/wuxianpi-node ./bin/wuxianpi-node-start ./node/dist/index.js ./metadata/build.json; do
  tar -tzf "$asset" | awk -v required="$required" '$0 == required { found = 1 } END { exit found ? 0 : 1 }' \
    || { printf 'Native runtime asset is missing %s\n' "$required" >&2; exit 1; }
done
"$repo_dir/scripts/validate-openhouse-payloads.sh"
"$repo_dir/scripts/validate-native-install-resources.sh"
cd "$repo_dir"
./gradlew -p native-app "${NATIVE_GRADLE_TASK:-assembleRelease}" "$@"
apk="$(find "$repo_dir/native-app/build/outputs/apk" -type f -name '*.apk' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d ' ' -f 2-)"
[[ -n "$apk" && -f "$apk" ]] || { printf 'Native APK was not produced\n' >&2; exit 1; }
apk_entries="$(unzip -Z1 "$apk")"
awk '$0 == "assets/openhouse-runtime/runtime-aarch64.tgz" { found = 1 } END { exit found ? 0 : 1 }' <<<"$apk_entries" \
  || { printf 'Native APK is missing assets/openhouse-runtime/runtime-aarch64.tgz\n' >&2; exit 1; }
if grep -Eq '^lib/(armeabi-v7a|x86|x86_64)/' <<<"$apk_entries"; then
  printf 'Native APK must contain only arm64-v8a native libraries\n' >&2
  exit 1
fi
grep -Eq '^lib/arm64-v8a/' <<<"$apk_entries" \
  || { printf 'Native APK is missing arm64-v8a native libraries\n' >&2; exit 1; }
for required_asset in assets/wuxianpi-install/pre-tmux.sh assets/wuxianpi-install/resources.tar; do
  awk -v required="$required_asset" '$0 == required { found = 1 } END { exit found ? 0 : 1 }' <<<"$apk_entries" \
    || { printf 'Native APK is missing %s\n' "$required_asset" >&2; exit 1; }
done
expected="$(sha256sum "$asset" | awk '{print $1}')"
actual="$(unzip -p "$apk" assets/openhouse-runtime/runtime-aarch64.tgz | sha256sum | awk '{print $1}')"
[[ "$expected" == "$actual" ]] || { printf 'Native APK runtime asset checksum mismatch\n' >&2; exit 1; }
for asset_name in pre-tmux.sh resources.tar; do
  expected="$(sha256sum "$repo_dir/native-app/src/main/assets/wuxianpi-install/$asset_name" | awk '{print $1}')"
  actual="$(unzip -p "$apk" "assets/wuxianpi-install/$asset_name" | sha256sum | awk '{print $1}')"
  [[ "$expected" == "$actual" ]] || { printf 'Native APK install asset checksum mismatch: %s\n' "$asset_name" >&2; exit 1; }
done
printf 'Native APK verified: %s\n' "$apk"
"$repo_dir/scripts/report-apk-build.sh" "$apk"
