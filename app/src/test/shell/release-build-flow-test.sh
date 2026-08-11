#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../../../.." && pwd)"
native_script="$repo_dir/scripts/build-native.sh"
all_in_one_script="$repo_dir/scripts/build-all-in-one.sh"
apk_validator="$repo_dir/scripts/validate-openhouse-apk.sh"
alignment_gate='"$repo_dir/scripts/check-production-resource-alignment.sh"'

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

grep -Fq 'native_gradle_task="${NATIVE_GRADLE_TASK:-:native-app:assembleDebug}"' "$native_script" \
  || fail 'Native distribution task does not default to the root Gradle project path'
grep -Fq './gradlew "$native_gradle_task" "$@"' "$native_script" \
  || fail 'Native distribution build does not reuse the root Gradle invocation'
if grep -Fq './gradlew -p native-app' "$native_script"; then
  fail 'Native distribution build still isolates the native-app Gradle cache'
fi
grep -Fq 'native-app/build/outputs/apk/$build_type' "$native_script" \
  || fail 'Native distribution build does not restrict output discovery to the selected variant'
grep -Fq 'NATIVE_GRADLE_TASK=:native-app:assembleRelease' "$repo_dir/README.md" \
  || fail 'README does not document the explicit Native Release override'

grep -Fq 'gradle_task="${ALL_IN_ONE_GRADLE_TASK:-:app:assembleDebug}"' "$all_in_one_script" \
  || fail 'All-in-One distribution task does not default to Debug'
grep -Fq '"$repo_dir/scripts/validate-openhouse-apk.sh" "${distribution_apks[@]}"' "$all_in_one_script" \
  || fail 'All-in-One distribution outputs are not passed explicitly to APK validation'
grep -Fq 'ALL_IN_ONE_GRADLE_TASK=:app:assembleRelease' "$repo_dir/README.md" \
  || fail 'README does not document the explicit All-in-One Release override'
grep -Fq "$alignment_gate" "$all_in_one_script" \
  || fail 'All-in-One distribution build does not enforce production resource alignment'
if grep -Fq -- '-newermt' "$all_in_one_script"; then
  fail 'All-in-One distribution output discovery still depends on APK modification time'
fi
grep -Fq 'if [[ "$#" -gt 0 ]]; then' "$apk_validator" \
  || fail 'APK validator does not support explicit output paths'
grep -Fq 'max_debug_arm64_bytes' "$apk_validator" \
  || fail 'APK validator does not define a separate Debug size gate'
grep -Fq "$alignment_gate" "$native_script" \
  || fail 'Native distribution build does not enforce production resource alignment'

printf 'Debug distribution build flow contracts passed\n'
