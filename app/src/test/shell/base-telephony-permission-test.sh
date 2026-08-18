#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
manifest="$repo_dir/operit-feature/src/main/AndroidManifest.xml"

if rg -n -q 'android\.permission\.(CALL_PHONE|SEND_SMS)' "$manifest"; then
  printf 'Base Operit feature declares optional call/SMS permissions\n' >&2
  exit 1
fi

if [[ "$#" -gt 0 ]]; then
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
  [[ -x "$aapt" ]] || { printf 'aapt is required for APK permission checks\n' >&2; exit 2; }
  for apk in "$@"; do
    permissions="$($aapt dump permissions "$apk")"
    if grep -Eq "uses-permission: name='android\.permission\.(CALL_PHONE|SEND_SMS)'" <<<"$permissions"; then
      printf 'APK declares optional call/SMS permission: %s\n' "$apk" >&2
      exit 1
    fi
  done
fi

printf 'Base telephony permission contract passed\n'
