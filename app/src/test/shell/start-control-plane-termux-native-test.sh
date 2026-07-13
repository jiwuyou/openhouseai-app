#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
START_SCRIPT="$REPO_ROOT/app/src/main/assets/maintainer/start-control-plane-termux-native.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

[ -f "$START_SCRIPT" ] || fail 'pure Termux native control-plane start script is missing'

grep -Fq '.config/openhouseai/service-manager/config.json' "$START_SCRIPT" \
  || fail 'canonical OpenHouse service-manager config is missing'
grep -Eq 'command -v service-manager|/bin/service-manager|/\.local/bin/service-manager' "$START_SCRIPT" \
  || fail 'installed Termux native service-manager binary lookup is missing'
grep -Eq 'serve[[:space:]]+--config[[:space:]]+"?\$\{?[A-Za-z_][A-Za-z0-9_]*\}?"?[[:space:]]+--bind' "$START_SCRIPT" \
  || fail 'service-manager must start directly with canonical --config and --bind'
grep -Fq '/api/v1/health' "$START_SCRIPT" \
  || fail 'bounded health verification is missing'
grep -Fq '/api/v1/services' "$START_SCRIPT" \
  || fail 'authenticated API verification is missing'
grep -Eq 'auth_token|Authorization: Bearer|bearer' "$START_SCRIPT" \
  || fail 'canonical token authentication is missing'
grep -Eq 'for[[:space:]].*(attempt|try)|while[[:space:]].*(attempt|try)|deadline|timeout' "$START_SCRIPT" \
  || fail 'finite readiness waiting is missing'
grep -Fq 'started_pid' "$START_SCRIPT" \
  || fail 'this-launch process ownership marker is missing'
grep -Eq 'kill[[:space:]]+"?\$\{?started_pid\}?"?' "$START_SCRIPT" \
  || fail 'failed launch must clean up only its started_pid'

if grep -Eqi 'service-manager\.tar|SMALLPHONEAI_OFFLINE_PAYLOAD|scripts/install\.sh|bash[^\n]*bootstrap|SMALLPHONEAI_START_TARGETS|migrate_legacy|migrate_service|provider[_-]migration|repair-control-plane\.sh' "$START_SCRIPT"; then
  fail 'start-only script references install, payload, migration, repair, or business-service logic'
fi
if grep -Eqi 'pkill|killall|sv[[:space:]]+down[[:space:]]+service-manager|stop_existing' "$START_SCRIPT"; then
  fail 'start-only script must not stop an existing service-manager process'
fi
while IFS= read -r kill_line; do
  printf '%s\n' "$kill_line" | grep -Fq 'started_pid' \
    || fail 'kill command may target only this launch started_pid'
done < <(grep -E '^[[:space:]]*kill[[:space:]]' "$START_SCRIPT" || true)

printf 'start-control-plane-termux-native focused contract passed\n'
