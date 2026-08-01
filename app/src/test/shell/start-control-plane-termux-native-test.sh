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
grep -Fq 'service-daemon start' "$START_SCRIPT" \
  || fail 'termux-services daemon must be started explicitly'
grep -Fq 'install-service --config "$config" --bind "$bind"' "$START_SCRIPT" \
  || fail 'service-manager runit service must be installed with canonical config and bind'
grep -Fq 'SVDIR="$service_root" sv up service-manager' "$START_SCRIPT" \
  || fail 'service-manager must be started through the Termux runit directory'
grep -Fq 'service_manager_runit_ready' "$START_SCRIPT" \
  || fail 'runit status verification is missing'
grep -Fq 'service_manager_instance_matches_expected' "$START_SCRIPT" \
  || fail 'unique canonical process verification is missing'
grep -Fq '/api/v1/health' "$START_SCRIPT" \
  || fail 'bounded health verification is missing'
grep -Fq '/api/v1/services' "$START_SCRIPT" \
  || fail 'authenticated API verification is missing'
grep -Eq 'auth_token|Authorization: Bearer|bearer' "$START_SCRIPT" \
  || fail 'canonical token authentication is missing'
grep -Eq 'for[[:space:]].*(attempt|try)|while[[:space:]].*(attempt|try)|deadline|timeout' "$START_SCRIPT" \
  || fail 'finite readiness waiting is missing'
if grep -Eqi 'service-manager\.tar|SMALLPHONEAI_OFFLINE_PAYLOAD|scripts/install\.sh|bash[^\n]*bootstrap|SMALLPHONEAI_START_TARGETS|migrate_legacy|migrate_service|provider[_-]migration|repair-control-plane\.sh' "$START_SCRIPT"; then
  fail 'start-only script references install, payload, migration, repair, or business-service logic'
fi
if grep -Eqi 'pkill|killall|stop_existing' "$START_SCRIPT"; then
  fail 'start-only script must not stop an existing service-manager process'
fi
if grep -Eq '^[[:space:]]*(nohup|setsid[[:space:]]+-f)|^[[:space:]]*[^#]*service-manager[^#]*serve[[:space:]]+--config' "$START_SCRIPT"; then
  fail 'normal control-plane startup must not use a detached service-manager process'
fi

printf 'start-control-plane-termux-native focused contract passed\n'
