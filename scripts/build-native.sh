#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$repo_dir/scripts/validate-apk-version-contract.sh"
"$repo_dir/scripts/validate-product-baseline.sh"
[[ "${SKIP_RUNTIME_BUILD:-0}" == "1" ]] || "$repo_dir/scripts/build-pi-node-payload.sh"
"$repo_dir/scripts/generate-native-install-resources.sh"
asset="$repo_dir/native-app/src/main/assets/openhouse-resources-v2/runtime-aarch64.tgz"
[[ -s "$asset" ]] || { printf 'Missing Native ARM64 runtime asset: %s\n' "$asset" >&2; exit 1; }
for required in ./install.sh ./bin/wuxianpi ./bin/wuxianpi-node ./bin/wuxianpi-node-start ./node/dist/index.js ./metadata/build.json; do
  tar -tzf "$asset" | awk -v required="$required" '$0 == required { found = 1 } END { exit found ? 0 : 1 }' \
    || { printf 'Native runtime asset is missing %s\n' "$required" >&2; exit 1; }
done
"$repo_dir/scripts/validate-openhouse-payloads.sh"
"$repo_dir/scripts/validate-native-install-resources.sh"
"$repo_dir/scripts/check-production-resource-alignment.sh"
cd "$repo_dir"
native_gradle_task="${NATIVE_GRADLE_TASK:-:native-app:assembleDebug}"
case "$native_gradle_task" in
  :*) ;;
  native-app:*) native_gradle_task=":$native_gradle_task" ;;
  *) native_gradle_task=":native-app:$native_gradle_task" ;;
esac
case "${native_gradle_task##*:}" in
  *Debug) build_type=debug ;;
  *Release) build_type=release ;;
  *)
    printf 'Unsupported Native APK task: %s (expected an assembleDebug/assembleRelease task)\n' "$native_gradle_task" >&2
    exit 2
    ;;
esac
./gradlew "$native_gradle_task" "$@"
mapfile -t distribution_apks < <(find "$repo_dir/native-app/build/outputs/apk/$build_type" -type f -name '*.apk' | sort)
[[ "${#distribution_apks[@]}" -eq 1 ]] \
  || { printf 'Expected exactly one Native %s APK, found %s\n' "$build_type" "${#distribution_apks[@]}" >&2; exit 1; }
apk="${distribution_apks[0]}"
apk_entries="$(unzip -Z1 "$apk")"
for resource_name in resource-set.json service-manager.tgz openhouse-control-plane.tgz runtime-aarch64.tgz wuyou.tgz openhouse-web.tgz; do
  resource_path="assets/openhouse-resources-v2/$resource_name"
  awk -v required="$resource_path" '$0 == required { found = 1 } END { exit found ? 0 : 1 }' <<<"$apk_entries" \
    || { printf 'Native APK is missing %s\n' "$resource_path" >&2; exit 1; }
done
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
actual="$(unzip -p "$apk" assets/openhouse-resources-v2/runtime-aarch64.tgz | sha256sum | awk '{print $1}')"
[[ "$expected" == "$actual" ]] || { printf 'Native APK runtime asset checksum mismatch\n' >&2; exit 1; }
for asset_name in pre-tmux.sh resources.tar; do
  expected="$(sha256sum "$repo_dir/native-app/src/main/assets/wuxianpi-install/$asset_name" | awk '{print $1}')"
  actual="$(unzip -p "$apk" "assets/wuxianpi-install/$asset_name" | sha256sum | awk '{print $1}')"
  [[ "$expected" == "$actual" ]] || { printf 'Native APK install asset checksum mismatch: %s\n' "$asset_name" >&2; exit 1; }
done
printf 'Native APK verified: %s\n' "$apk"
"$repo_dir/scripts/report-apk-build.sh" "$apk"
