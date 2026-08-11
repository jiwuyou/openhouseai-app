#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$repo_dir/scripts/validate-apk-version-contract.sh"
"$repo_dir/scripts/validate-product-baseline.sh"
[[ "${SKIP_RUNTIME_BUILD:-0}" == "1" ]] || "$repo_dir/scripts/build-pi-node-payload.sh"
runtime_payload="$repo_dir/app/src/main/assets/openhouse/product-payloads/runtime-aarch64.tgz"
[[ -s "$runtime_payload" ]] || { printf 'Missing WuxianPi Node runtime payload: %s\n' "$runtime_payload" >&2; exit 1; }
for required in ./bin/wuxianpi-node ./bin/wuxianpi-node-start ./node/dist/index.js ./scripts/install.sh; do
  tar -tzf "$runtime_payload" | awk -v required="$required" '$0 == required || $0 == "./" required { found = 1 } END { exit found ? 0 : 1 }' \
    || { printf 'All-in-One runtime payload is missing %s\n' "$required" >&2; exit 1; }
done
[[ ! -e "$repo_dir/app/src/main/assets/openhouse/product-payloads/pi-runtime.tar" ]] \
  || { printf 'Legacy pi-runtime.tar must not be bundled\n' >&2; exit 1; }
"$repo_dir/scripts/validate-openhouse-payloads.sh"
"$repo_dir/scripts/check-production-resource-alignment.sh"
cd "$repo_dir"
gradle_task="${ALL_IN_ONE_GRADLE_TASK:-:app:assembleDebug}"
case "${gradle_task##*:}" in
  *Debug) build_type=debug ;;
  *Release) build_type=release ;;
  *)
    printf 'Unsupported All-in-One APK task: %s (expected an assembleDebug/assembleRelease task)\n' "$gradle_task" >&2
    exit 2
    ;;
esac
./gradlew "$gradle_task" "$@"
mapfile -t distribution_apks < <(find "$repo_dir/app/build/outputs/apk/$build_type" -type f \
  \( -name '*arm64-v8a.apk' -o -name '*_universal.apk' \) | sort)
[[ "${#distribution_apks[@]}" -eq 2 ]] \
  || { printf 'Expected arm64 and universal All-in-One %s APKs, found %s\n' "$build_type" "${#distribution_apks[@]}" >&2; exit 1; }
if [[ "${SKIP_APK_VALIDATION:-0}" != "1" ]]; then
  "$repo_dir/scripts/validate-openhouse-apk.sh" "${distribution_apks[@]}"
fi
"$repo_dir/scripts/report-apk-build.sh" "${distribution_apks[@]}"
