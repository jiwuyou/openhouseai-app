#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "${SKIP_RUNTIME_BUILD:-0}" == "1" ]] || "$repo_dir/scripts/build-runtime.sh"
runtime_payload="$repo_dir/app/src/main/assets/openhouse/product-payloads/pi-runtime.tar"
[[ -s "$runtime_payload" ]] || { printf 'Missing WuxianPi Node runtime payload: %s\n' "$runtime_payload" >&2; exit 1; }
for required in ./bin/wuxianpi-node ./bin/wuxianpi-node-start ./node/dist/index.js ./scripts/install.sh; do
  tar -tf "$runtime_payload" | awk -v required="$required" '$0 == required { found = 1 } END { exit found ? 0 : 1 }' \
    || { printf 'All-in-One runtime payload is missing %s\n' "$required" >&2; exit 1; }
done
"$repo_dir/scripts/validate-openhouse-payloads.sh"
cd "$repo_dir"
exec ./gradlew "${ALL_IN_ONE_GRADLE_TASK:-:app:assembleRelease}" "$@"
