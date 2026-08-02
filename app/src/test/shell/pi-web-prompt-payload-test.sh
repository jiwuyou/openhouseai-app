#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PAYLOAD_DIR="$REPO_ROOT/app/src/main/assets/openhouse/product-payloads"
BUILD_ALL_IN_ONE="$REPO_ROOT/scripts/build-all-in-one.sh"
BUILD_NATIVE="$REPO_ROOT/scripts/build-native.sh"
ATTRIBUTES="$REPO_ROOT/.gitattributes"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

for forbidden in \
  "$PAYLOAD_DIR/pi-web.tar" \
  "$PAYLOAD_DIR/aionui-web-2.1.32-linux-arm64.tgz"; do
  [ ! -e "$forbidden" ] || fail "lean APK still embeds $(basename "$forbidden")"
done

for manifest in "$PAYLOAD_DIR/manifest.json" "$PAYLOAD_DIR/payload-manifest.json"; do
  python3 - "$manifest" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1], encoding="utf-8"))
items = document.get("components") or document.get("payloads") or []
ids = {item.get("id") for item in items}
for forbidden in ("pi-web", "aionui-web"):
    if forbidden in ids:
        raise SystemExit(f"FAIL: {sys.argv[1]} still declares {forbidden}")
PY
done

if grep -Fq -e 'product-payloads/pi-web.tar' -e 'product-payloads/aionui-web-' "$ATTRIBUTES"; then
  fail 'removed standalone payloads still have Git LFS rules'
fi
for build_script in "$BUILD_ALL_IN_ONE" "$BUILD_NATIVE"; do
  grep -Fq 'scripts/build-pi-node-payload.sh' "$build_script" \
    || fail "$(basename "$build_script") no longer rebuilds the WuxianPi Node payload"
  if grep -Fq 'scripts/build-runtime.sh' "$build_script"; then
    fail "$(basename "$build_script") still rebuilds standalone pi-web.tar"
  fi
done

printf 'lean APK payload exclusion contracts passed\n'
