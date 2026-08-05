#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/../../../.." && pwd)"
native_script="$repo_dir/scripts/build-native.sh"
all_in_one_script="$repo_dir/scripts/build-all-in-one.sh"
apk_validator="$repo_dir/scripts/validate-openhouse-apk.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

grep -Fq 'native_gradle_task="${NATIVE_GRADLE_TASK:-:native-app:assembleRelease}"' "$native_script" \
  || fail 'Native release task does not default to the root Gradle project path'
grep -Fq './gradlew "$native_gradle_task" "$@"' "$native_script" \
  || fail 'Native release build does not reuse the root Gradle invocation'
if grep -Fq './gradlew -p native-app' "$native_script"; then
  fail 'Native release build still isolates the native-app Gradle cache'
fi
grep -Fq 'native-app/build/outputs/apk/release' "$native_script" \
  || fail 'Native release build does not restrict output discovery to the release variant'

grep -Fq '"$repo_dir/scripts/validate-openhouse-apk.sh" "${release_apks[@]}"' "$all_in_one_script" \
  || fail 'All-in-One release outputs are not passed explicitly to APK validation'
if grep -Fq -- '-newermt' "$all_in_one_script"; then
  fail 'All-in-One release output discovery still depends on APK modification time'
fi
grep -Fq 'if [[ "$#" -gt 0 ]]; then' "$apk_validator" \
  || fail 'APK validator does not support explicit output paths'

printf 'release build flow contracts passed\n'
