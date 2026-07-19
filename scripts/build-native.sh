#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "${SKIP_RUNTIME_BUILD:-0}" == "1" ]] || "$repo_dir/scripts/build-runtime.sh"
asset="$repo_dir/native-app/src/main/assets/openhouse-runtime/runtime-aarch64.tgz"
[[ -s "$asset" ]] || { printf 'Missing Native ARM64 runtime asset: %s\n' "$asset" >&2; exit 1; }
for required in ./install.sh ./bin/pi ./bin/openhouse-pi-runtime ./extensions/openhouse-tools/extension.json ./metadata/build.json; do
  tar -tzf "$asset" | awk -v required="$required" '$0 == required { found = 1 } END { exit found ? 0 : 1 }' \
    || { printf 'Native runtime asset is missing %s\n' "$required" >&2; exit 1; }
done
"$repo_dir/scripts/validate-openhouse-payloads.sh"
cd "$repo_dir"
./gradlew -p native-app "${NATIVE_GRADLE_TASK:-assembleRelease}" "$@"
apk="$(find "$repo_dir/native-app/build/outputs/apk" -type f -name '*.apk' -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d ' ' -f 2-)"
[[ -n "$apk" && -f "$apk" ]] || { printf 'Native APK was not produced\n' >&2; exit 1; }
unzip -Z1 "$apk" | awk '$0 == "assets/openhouse-runtime/runtime-aarch64.tgz" { found = 1 } END { exit found ? 0 : 1 }' \
  || { printf 'Native APK is missing assets/openhouse-runtime/runtime-aarch64.tgz\n' >&2; exit 1; }
expected="$(sha256sum "$asset" | awk '{print $1}')"
actual="$(unzip -p "$apk" assets/openhouse-runtime/runtime-aarch64.tgz | sha256sum | awk '{print $1}')"
[[ "$expected" == "$actual" ]] || { printf 'Native APK runtime asset checksum mismatch\n' >&2; exit 1; }
printf 'Native APK verified: %s\n' "$apk"
