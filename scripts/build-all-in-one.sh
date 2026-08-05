#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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
cd "$repo_dir"
build_started_at="$(date +%s)"
./gradlew "${ALL_IN_ONE_GRADLE_TASK:-:app:assembleRelease}" "$@"
if [[ "${SKIP_APK_VALIDATION:-0}" != "1" ]]; then
  OPENHOUSE_APK_MIN_MTIME="$build_started_at" "$repo_dir/scripts/validate-openhouse-apk.sh"
fi
mapfile -t release_apks < <(find "$repo_dir/app/build/outputs/apk/release" -type f \
  \( -name '*arm64-v8a.apk' -o -name '*_universal.apk' \) -newermt "@$build_started_at" | sort)
[[ "${#release_apks[@]}" -gt 0 ]] || { printf 'No release arm64 or universal APK was produced\n' >&2; exit 1; }
"$repo_dir/scripts/report-apk-build.sh" "${release_apks[@]}"
