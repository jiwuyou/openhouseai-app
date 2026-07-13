#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PAYLOAD_DIR="$REPO_ROOT/app/src/main/assets/openhouse/product-payloads"
BOOTSTRAP="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh"
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
assert entry.get("version") == "1.0.0"
assert entry.get("requires", {}).get("serviceManager") == ">=0.3.0"
PY
done

for required in \
  src/server.mjs src/auth.mjs public/index.html \
  config/openhouse-web.service.json config/openhouse.component.json \
  scripts/install.sh scripts/check.sh scripts/register-service.sh; do
  tar -tf "$PAYLOAD_DIR/openhouse-web.tar" | sed 's#^\./##' | grep -Fxq "$required" \
    || fail "openhouse-web payload missing $required"
done

service_json="$(tar -xOf "$PAYLOAD_DIR/openhouse-web.tar" ./config/openhouse-web.service.json)"
printf '%s' "$service_json" | grep -Fq '"residentByDefault": true' || fail 'residentByDefault=true missing'
printf '%s' "$service_json" | grep -Fq '"preferred": 22110' || fail 'fixed port 22110 missing'

grep -Fq 'service-manager,openhouse-web,pi-agent,pi-web' "$BOOTSTRAP" || fail 'bootstrap default order missing openhouse-web'
grep -Fq 'run_component "OpenHouse Web"' "$BOOTSTRAP" || fail 'bootstrap does not install openhouse-web'
grep -Fq 'service-manager,openhouse-web,pi-agent,pi-web' "$INSTALLER" || fail 'maintainer default order missing openhouse-web'

printf 'openhouse-web payload focused tests passed\n'
