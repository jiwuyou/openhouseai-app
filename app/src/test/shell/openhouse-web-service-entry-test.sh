#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
CONTROL="$REPO_ROOT/app/src/main/java/com/termux/app/activities/OpenHouseServiceControlActivity.java"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

grep -Fq '"打开 OpenHouse Web"' "$CONTROL" || fail 'native OpenHouse Web action label missing'
grep -Fq '"openhouse-web".equals(ServiceManagerClient.sanitizeServiceId(serviceId))' "$CONTROL" \
  || fail 'stable openhouse-web service routing missing'
grep -Fq 'startActivity(new Intent(this, OpenHouseWebHostActivity.class))' "$CONTROL" \
  || fail 'OpenHouse Web does not use the private ticket-aware WebHost'
grep -Fq 'openServiceEntry(snapshot.id, currentServiceUrl(snapshot.id))' "$CONTROL" \
  || fail 'service card does not use the service-aware opening path'
grep -Fq 'openServiceEntry(componentId, componentUrl)' "$CONTROL" \
  || fail 'component header does not use the service-aware opening path'

python3 - "$CONTROL" <<'PY'
import sys

source = open(sys.argv[1], encoding="utf-8").read()
start = source.index("private void openServiceEntry(")
end = source.index("private static String openActionLabel(", start)
method = source[start:end]
assert "isOpenHouseWebService(serviceId)" in method
assert "OpenHouseWebHostActivity.class" in method
assert "openBrowserUrl(url)" in method
assert method.index("OpenHouseWebHostActivity.class") < method.index("openBrowserUrl(url)"), \
    "OpenHouse Web must be intercepted before generic browser opening"
assert "REPAIR_CONTROL_PLANE" not in method
assert "START_CONTROL_PLANE" not in method
PY

printf 'openhouse-web service entry focused tests passed\n'
