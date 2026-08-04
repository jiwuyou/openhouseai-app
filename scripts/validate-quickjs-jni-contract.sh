#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mapping_file="${1:-$repo_dir/app/build/outputs/mapping/release/mapping.txt}"
usage_file="${2:-$repo_dir/app/build/outputs/mapping/release/usage.txt}"
seeds_file="${3:-$repo_dir/app/build/outputs/mapping/release/seeds.txt}"
dispatcher="com.ai.assistance.operit.core.tools.javascript.QuickJsNativeHostDispatcher"
bridge="com.ai.assistance.operit.core.tools.javascript.QuickJsNativeRuntime\$HostBridge"
method_pattern='java\.lang\.String onCall\(java\.lang\.String,java\.lang\.String\) -> onCall$'

[[ -s "$mapping_file" ]] || {
  printf 'QuickJS JNI gate requires an R8 mapping file: %s\n' "$mapping_file" >&2
  exit 1
}
[[ -s "$seeds_file" ]] || {
  printf 'QuickJS JNI gate requires an R8 seeds file: %s\n' "$seeds_file" >&2
  exit 1
}

mapping_block() {
  local class_name="$1"
  awk -v prefix="$class_name -> " '
    index($0, prefix) == 1 { active = 1; print; next }
    active && $0 !~ /^[[:space:]#]/ { exit }
    active { print }
  ' "$mapping_file"
}

dispatcher_block="$(mapping_block "$dispatcher")"
bridge_block="$(mapping_block "$bridge")"

[[ -n "$dispatcher_block" ]] || {
  printf 'R8 removed the QuickJS JNI dispatcher class: %s\n' "$dispatcher" >&2
  exit 1
}
[[ -n "$bridge_block" ]] || {
  printf 'R8 removed the QuickJS JNI bridge interface: %s\n' "$bridge" >&2
  exit 1
}
grep -Eq "$method_pattern" <<<"$dispatcher_block" || {
  printf 'R8 removed or renamed %s.onCall(String, String)\n' "$dispatcher" >&2
  exit 1
}
grep -Fxq "$bridge: java.lang.String onCall(java.lang.String,java.lang.String)" "$seeds_file" || {
  printf 'R8 did not seed %s.onCall(String, String) for retention\n' "$bridge" >&2
  exit 1
}

if [[ -s "$usage_file" ]]; then
  usage_block="$({
    awk -v target="$dispatcher:" '
      $0 == target { active = 1; print; next }
      active && $0 !~ /^[[:space:]]/ { exit }
      active { print }
    ' "$usage_file"
  })"
  if grep -Eq '^[[:space:]]+public java\.lang\.String onCall\(java\.lang\.String,java\.lang\.String\)$' <<<"$usage_block"; then
    printf 'R8 usage report still lists %s.onCall(String, String) as removed\n' "$dispatcher" >&2
    exit 1
  fi
fi

printf 'QuickJS JNI R8 contract gate passed: %s\n' "$mapping_file"
