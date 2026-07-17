#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PAYLOAD_DIR="$REPO_ROOT/app/src/main/assets/openhouse/product-payloads"
BOOTSTRAP="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh"
FULL_INSTALL="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/bootstrap.sh"
INSTALLER="$REPO_ROOT/app/src/main/assets/maintainer/install-runtime-components.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

for manifest in "$PAYLOAD_DIR/manifest.json" "$PAYLOAD_DIR/payload-manifest.json"; do
  python3 - "$manifest" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
items = doc.get("components") or doc.get("payloads") or []
entry = next((item for item in items if item.get("id") == "openhouse-web"), None)
assert entry, "openhouse-web entry missing"
assert entry.get("archive") == "openhouse-web.tar"
assert entry.get("version") == "1.1.2"
assert entry.get("requires", {}).get("serviceManager") == ">=0.3.1"
PY
done

for required in \
  README.md package.json \
  src/server.mjs src/auth.mjs src/password-store.mjs public/index.html \
  config/openhouse-web.service.json config/openhouse.component.json \
  scripts/build.mjs scripts/check.mjs \
  scripts/install.sh scripts/check.sh scripts/register-service.sh; do
  tar -tf "$PAYLOAD_DIR/openhouse-web.tar" | sed 's#^\./##' | grep -Fxq "$required" \
    || fail "openhouse-web payload missing $required"
done

for executable in scripts/install.sh scripts/check.sh scripts/register-service.sh; do
  mode="$(tar -tvf "$PAYLOAD_DIR/openhouse-web.tar" "./$executable" | awk '{print $1}')"
  case "$mode" in
    *x*x*x) ;;
    *) fail "openhouse-web payload script is not executable: $executable ($mode)" ;;
  esac
done

package_json="$(tar -xOf "$PAYLOAD_DIR/openhouse-web.tar" ./package.json)"
printf '%s' "$package_json" | grep -Fq '"version": "1.1.2"' || fail 'package version 1.1.2 missing'

password_store="$(tar -xOf "$PAYLOAD_DIR/openhouse-web.tar" ./src/password-store.mjs)"
for required in \
  "DEFAULT_PASSWORD = '123456'" \
  'MIN_PASSWORD_LENGTH = 6' \
  'MAX_PASSWORD_LENGTH = 128' \
  '0o700' '0o600'; do
  printf '%s' "$password_store" | grep -Fq "$required" \
    || fail "password store contract missing: $required"
done

server_source="$(tar -xOf "$PAYLOAD_DIR/openhouse-web.tar" ./src/server.mjs)"
for required in \
  '/api/v1/session/password' '/api/v1/password' \
  'auth.revokeSessions()' 'auth.issueSession()'; do
  printf '%s' "$server_source" | grep -Fq "$required" \
    || fail "password auth server contract missing: $required"
done

service_json="$(tar -xOf "$PAYLOAD_DIR/openhouse-web.tar" ./config/openhouse-web.service.json)"
printf '%s' "$service_json" | grep -Fq '"residentByDefault": true' || fail 'residentByDefault=true missing'
printf '%s' "$service_json" | grep -Fq '"preferred": 22110' || fail 'fixed port 22110 missing'

grep -Fq 'wuyou,service-manager,pi-agent,pi-web,github-config-helper,cc-connect,smallphone,hermes,openhouse-web' "$BOOTSTRAP" \
  || fail 'bootstrap default order does not install openhouse-web last'
grep -Fq 'run_component "OpenHouse Web"' "$BOOTSTRAP" || fail 'bootstrap does not install openhouse-web'
python3 - "$FULL_INSTALL" <<'PY'
import sys

source = open(sys.argv[1], encoding="utf-8").read()
markers = [
    "SMALLPHONEAI_COMPONENT_TARGETS=wuyou",
    "run_stage 13-install-termux-node.sh",
    "SMALLPHONEAI_COMPONENT_TARGETS=pi-agent",
    "SMALLPHONEAI_COMPONENT_TARGETS=pi-web",
    "run_stage start-pi-web-rescue.sh",
    "SMALLPHONEAI_COMPONENT_TARGETS=service-manager",
    "SMALLPHONEAI_COMPONENT_ACTION=register-only",
    "SMALLPHONEAI_START_TARGETS=pi-agent,pi-web",
    "SMALLPHONEAI_COMPONENT_TARGETS=openhouse-web",
]
positions = [source.index(marker) for marker in markers]
assert positions == sorted(positions), "first-install component order is incorrect"
PY
grep -Fq 'install-check|install-only|defer-registration' "$BOOTSTRAP" \
  || fail 'component installer does not expose deferred pi registration mode'
grep -Fq 'SMALLPHONEAI_COMPONENT_ACTION=register-only' "$FULL_INSTALL" \
  || fail 'full first install does not register pi after service-manager'
grep -Fq 'wuyou,service-manager,pi-agent,pi-web,openhouse-web' "$INSTALLER" \
  || fail 'maintainer default order does not install openhouse-web last'

printf 'openhouse-web payload focused tests passed\n'
