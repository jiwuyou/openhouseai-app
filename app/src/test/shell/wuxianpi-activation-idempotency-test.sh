#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
setup="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
work="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-activation-test.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT

function_body="$(sed -n '/^ensure_wuxianpi_started()/,/^}/p' "$setup")"
[[ -n "$function_body" ]] || { printf 'missing ensure_wuxianpi_started\n' >&2; exit 1; }
! grep -Eq '(^|[[:space:]])stop([[:space:]]|$)' <<<"$function_body"

run_case() {
  local name="$1" health="$2" state="$3" expected="$4" calls
  calls="$work/$name.calls"
  (
    TEST_HEALTH="$health"
    TEST_SERVICE_STATE="$state"
    TEST_CALLS="$calls"
    log() { :; }
    pi_ready() { [[ "$TEST_HEALTH" == ready ]]; }
    wuxianpi_service_state() { [[ "$TEST_SERVICE_STATE" != error ]] || return 1; printf '%s\n' "$TEST_SERVICE_STATE"; }
    wait_wuxianpi_health() { printf 'wait\n' >>"$TEST_CALLS"; }
    authenticated_service_manager_request() { printf '%s %s\n' "$1" "$2" >>"$TEST_CALLS"; }
    eval "$function_body"
    ensure_wuxianpi_started
  )
  touch "$calls"
  [[ "$(cat "$calls")" == "$expected" ]] || {
    printf 'unexpected calls for %s: %s\n' "$name" "$(cat "$calls")" >&2
    exit 1
  }
}

run_case healthy ready error ''
run_case running missing running 'wait'
run_case stopped missing stopped $'POST /api/v1/services/yuanshengwuxianpi/start\nwait'

eval "$(sed -n '/^offer_id_from_inbox()/,/^}/p' "$setup")"
eval "$(sed -n '/^update_offer_activation()/,/^}/p' "$setup")"
RESOURCE_MANAGER_ROOT="$work/resource-manager"
SETUP_OFFER_ID=''
SETUP_RESOURCE_INBOX="$work/inbox/test-offer-127-2026081504"
receipt="$RESOURCE_MANAGER_ROOT/receipts/apk-offers/test-offer-127-2026081504.json"
mkdir -p "$(dirname "$receipt")"
printf '%s\n' '{"schema":3,"offerId":"test-offer-127-2026081504","activation":"pending","activationFailure":"old"}' >"$receipt"
update_offer_activation ready ''
jq -e '.offerId == "test-offer-127-2026081504" and .activation == "ready" and .activationFailure == null' \
  "$receipt" >/dev/null

printf 'WuxianPi activation idempotency contract passed\n'
