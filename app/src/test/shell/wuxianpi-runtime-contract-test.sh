#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PAYLOAD_BUILDER="$REPO_ROOT/scripts/build-pi-node-payload.sh"
SETUP="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
PACKAGES="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/12-update-termux-packages.sh"
COMPONENTS="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh"
START="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/60-start-smallphone.sh"
REPAIR="$REPO_ROOT/app/src/main/assets/maintainer/repair-control-plane-termux-native.sh"
STATUS="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/65-smallphone-status.sh"
PI_SUBJECT="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/subjects.d/pi-agent.json"
SERVICE_SUBJECT="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/subjects.d/service-control.json"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

for script in "$PAYLOAD_BUILDER" "$SETUP" "$PACKAGES" "$COMPONENTS" "$START" "$REPAIR"; do
  bash -n "$script" || fail "shell syntax invalid: $script"
done

grep -Fq 'termux-services' "$PACKAGES" || fail 'formal post-tmux package stage does not install termux-services'
grep -Fq 'service-daemon start' "$PACKAGES" || fail 'formal package stage does not start service-daemon explicitly'
grep -Fq 'termux_runsvdir_active' "$PACKAGES" || fail 'formal package stage does not verify runsvdir'

for script in "$COMPONENTS" "$START"; do
  grep -Fq 'install-service --config' "$script" || fail "$script does not install the runit service"
  grep -Fq 'SVDIR="$service_root" sv up service-manager' "$script" || fail "$script does not use the Termux service root"
  if grep -Eq 'install-service[^\n]*\|\| true|sv up service-manager[^\n]*\|\| true' "$script"; then
    fail "$script hides a critical runit failure"
  fi
done

grep -Fq 'yuanshengwuxianpi.json' "$PAYLOAD_BUILDER" || fail 'stable WuxianPi service filename missing'
grep -Fq '"name": "yuanshengwuxianpi"' "$PAYLOAD_BUILDER" || fail 'stable WuxianPi service id missing'
grep -Fq '127.0.0.1:20765' "$PAYLOAD_BUILDER" || fail 'system WuxianPi port 20765 missing'
grep -Fq '"residentByDefault": false' "$PAYLOAD_BUILDER" || fail 'WuxianPi must remain on demand'
grep -Fq '"restart": {"mode": "on-failure"' "$PAYLOAD_BUILDER" || fail 'WuxianPi restart policy must be on-failure'
if grep -Fq '"restart": {"mode": "always"' "$PAYLOAD_BUILDER"; then
  fail 'WuxianPi payload still declares restart always'
fi
if grep -Fq '"tags": ["group:local-stack"' "$PAYLOAD_BUILDER"; then
  fail 'on-demand WuxianPi service is still part of the automatically started local stack'
fi

grep -Fq 'http://127.0.0.1:20765' "$SETUP" || fail 'setup verification still uses the old runtime port'
if grep -Fq 'SMALLPHONEAI_START_TARGETS=pi-agent run_bootstrap start' "$SETUP"; then
  fail 'first installation must register WuxianPi without starting it'
fi
grep -Fq 'SMALLPHONEAI_COMPONENT_ACTION=install-register' "$SETUP" || fail 'first installation does not register WuxianPi'
grep -Fq 'Registered on-demand service-manager service (not started)' "$PAYLOAD_BUILDER" || fail 'WuxianPi payload does not document deferred startup'
grep -Fq 'wuxianpi-node-start' "$SETUP" || fail 'setup does not verify installed WuxianPi payload'
grep -Fq 'pi_runtime_port="${OPENHOUSE_PI_RUNTIME_PORT:-20765}"' "$STATUS" || fail 'status script still defaults to port 8765'
grep -Fq 'readiness_object "yuanshengwuxianpi"' "$STATUS" || fail 'status readiness uses the legacy service id'
grep -Fq 'yuanshengwuxianpi' "$PI_SUBJECT" || fail 'Pi subject still references the legacy service id'
grep -Fq '127.0.0.1:20765' "$PI_SUBJECT" || fail 'Pi subject still references port 8765'
grep -Fq 'yuanshengwuxianpi' "$SERVICE_SUBJECT" || fail 'service-control subject still references the legacy service id'
grep -Fq 'stable_service_id="yuanshengwuxianpi"' "$COMPONENTS" || fail 'component registration does not map to the stable service id'
grep -Fq 'stable_service_id="yuanshengwuxianpi"' "$START" || fail 'start registration does not map to the stable service id'
grep -Fq '不会启动脱离 runit 的临时进程' "$REPAIR" || fail 'repair still permits detached fallback'
grep -Fq '未获得 runit 常驻，修复失败' "$REPAIR" || fail 'repair does not fail without runit residency'
if grep -Fq 'setsid -f' "$REPAIR" || grep -Fq 'nohup "$sm_bin" serve' "$REPAIR"; then
  fail 'repair contains a detached service-manager fallback'
fi

printf 'WuxianPi runtime and runit lifecycle contracts passed\n'
