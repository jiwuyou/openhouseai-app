#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
MANIFEST="$REPO_ROOT/app/src/main/AndroidManifest.xml"
RUNTIME="$REPO_ROOT/app/src/main/java/com/termux/app/openhouse/webhost/OpenHouseWebHostRuntime.java"
CONTROLLER="$REPO_ROOT/app/src/main/java/com/termux/app/openhouse/webhost/OpenHouseWebHostController.java"
SUPERVISOR="$REPO_ROOT/app/src/main/java/com/termux/app/openhouse/OpenHouseRuntimeSupervisor.java"
SM_CLIENT="$REPO_ROOT/app/src/main/java/com/termux/app/openhouse/servicecontrol/ServiceManagerClient.java"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

grep -Fq '.app.activities.OpenHouseWebHostActivity' "$MANIFEST" || fail 'WebHost Activity missing from manifest'
grep -Fq 'android:targetActivity=".app.activities.OpenHouseWebHostActivity"' "$MANIFEST" || fail 'Home alias does not target WebHost'
grep -Fq 'OPENHOUSE_WEB_HEALTH' "$RUNTIME" || fail '22110 health gate missing'
grep -Fq 'SERVICE_MANAGER_HEALTH' "$RUNTIME" || fail '20087 health gate missing'
grep -Fq '#ticket=' "$RUNTIME" || fail 'OpenHouse Web one-time ticket fragment missing'
grep -Fq 'issueWebSessionPath' "$RUNTIME" || fail 'service-manager fallback session missing'
grep -Fq 'OpenHouseHomeActivity.class' "$CONTROLLER" || fail 'native Recovery fallback missing'
grep -Fq 'setAllowFileAccess(false)' "$CONTROLLER" || fail 'WebView file access must be disabled'
grep -Fq 'setAllowContentAccess(false)' "$CONTROLLER" || fail 'WebView content access must be disabled'
if grep -Eq 'addJavascriptInterface|evaluateJavascript\([^)]*(shell|command)' "$CONTROLLER"; then
  fail 'WebHost exposes a shell-capable JavaScript bridge'
fi
if grep -Eq 'local-stack|smallphone-frontend-beta|DEFAULT_LONG_RUNNING_SERVICES' "$SUPERVISOR"; then
  fail 'RuntimeSupervisor still starts business services or local-stack'
fi
grep -Fq '/api/v1/web-session-tickets' "$SM_CLIENT" || fail 'service-manager one-time session API missing'

printf 'openhouse-web fallback focused tests passed\n'
