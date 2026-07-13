#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
MANIFEST="$REPO_ROOT/app/src/main/AndroidManifest.xml"
RUNTIME="$REPO_ROOT/app/src/main/java/com/termux/app/openhouse/webhost/OpenHouseWebHostRuntime.java"
CONTROLLER="$REPO_ROOT/app/src/main/java/com/termux/app/openhouse/webhost/OpenHouseWebHostController.java"
SUPERVISOR="$REPO_ROOT/app/src/main/java/com/termux/app/openhouse/OpenHouseRuntimeSupervisor.java"
SM_CLIENT="$REPO_ROOT/app/src/main/java/com/termux/app/openhouse/servicecontrol/ServiceManagerClient.java"
TERMUX_ACTIVITY="$REPO_ROOT/app/src/main/java/com/termux/app/TermuxActivity.java"
FIRST_LAUNCH_GATE="$REPO_ROOT/app/src/main/java/com/termux/app/smallphone/SmallPhoneFirstLaunchGate.java"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

python3 - "$MANIFEST" <<'PY'
import sys
import xml.etree.ElementTree as ET

android = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
application = root.find("application")
assert application is not None, "manifest application missing"
activities = {node.get(android + "name"): node for node in application.findall("activity")}
home = activities.get(".app.activities.OpenHouseHomeActivity")
web = activities.get(".app.activities.OpenHouseWebHostActivity")
assert home is not None, "native OpenHouse Home Activity missing"
assert web is not None, "WebHost Activity missing"

def categories(node):
    return {
        category.get(android + "name")
        for intent_filter in node.findall("intent-filter")
        for category in intent_filter.findall("category")
    }

home_categories = categories(home)
assert "android.intent.category.LAUNCHER" in home_categories, "native Home is not the phone launcher"
assert "android.intent.category.LEANBACK_LAUNCHER" in home_categories, "native Home is not the TV launcher"
web_categories = categories(web)
assert "android.intent.category.LAUNCHER" not in web_categories, "WebHost still owns the phone launcher"
assert "android.intent.category.LEANBACK_LAUNCHER" not in web_categories, "WebHost still owns the TV launcher"
assert web.get(android + "exported") == "false", "WebHost must be a private explicit Activity"

aliases = {node.get(android + "name"): node for node in application.findall("activity-alias")}
home_alias = aliases.get(".HomeActivity")
assert home_alias is not None, "Home alias missing"
assert home_alias.get(android + "targetActivity") == ".app.activities.OpenHouseHomeActivity", \
    "Home alias does not target native OpenHouse Home"
PY
grep -Fq 'new Intent(this, OpenHouseHomeActivity.class)' "$TERMUX_ACTIVITY" || fail 'Termux menu does not return to native Home'
if grep -Fq 'OpenHouseWebHostActivity' "$TERMUX_ACTIVITY"; then
  fail 'Termux menu still depends on WebHost'
fi
grep -Fq 'new Intent(context, OpenHouseHomeActivity.class)' "$FIRST_LAUNCH_GATE" || fail 'first-launch completion does not return to native Home'
grep -Fq 'ControlledBrowserContract.EXTRA_OPENHOUSE_PAGE, "smallphone"' "$FIRST_LAUNCH_GATE" || fail 'first-launch completion does not select SmallPhone'
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
