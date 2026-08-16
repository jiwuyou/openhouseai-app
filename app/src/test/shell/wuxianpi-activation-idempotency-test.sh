#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
setup="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
work="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-activation-test.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT

function_body="$(sed -n '/^ensure_wuxianpi_started()/,/^}/p' "$setup")"
[[ -n "$function_body" ]] || { printf 'missing ensure_wuxianpi_started\n' >&2; exit 1; }
! grep -Eq '(^|[[:space:]])stop([[:space:]]|$)' <<<"$function_body"

install_function_body="$(sed -n '/^install_canonical_service_manager_runit()/,/^}/p' "$setup")"
[[ -n "$install_function_body" ]] || { printf 'missing install_canonical_service_manager_runit\n' >&2; exit 1; }
activate_function_body="$(sed -n '/^activate_runtime()/,/^}/p' "$setup")"
grep -Fq 'install_canonical_service_manager_runit || activation_fail runit_install_failed' \
  <<<"$activate_function_body"
grep -Fq '"$control_script" || activation_fail service_manager_start_failed' \
  <<<"$activate_function_body"

run_install_case() {
  local name="$1" install_status="$2" create_run="$3" expected_status="$4" expected_log="$5"
  local root="$work/install-$name" status=0
  mkdir -p "$root/prefix/var/service/service-manager"
  (
    TERMUX_PREFIX="$root/prefix"
    CANONICAL_SM_CONFIG="$root/config.json"
    CANONICAL_SM_BIND='127.0.0.1:20087'
    TEST_INSTALL_STATUS="$install_status"
    TEST_CREATE_RUN="$create_run"
    TEST_LOG="$root/setup.log"
    log() { printf '%s\n' "$*" >>"$TEST_LOG"; }
    service-manager() {
      if [ "$TEST_CREATE_RUN" = 1 ]; then
        printf '%s\n' '#!/data/data/com.termux/files/usr/bin/bash' 'exit 0' \
          >"$TERMUX_PREFIX/var/service/service-manager/run"
        chmod 700 "$TERMUX_PREFIX/var/service/service-manager/run"
      fi
      return "$TEST_INSTALL_STATUS"
    }
    eval "$install_function_body"
    install_canonical_service_manager_runit
  ) || status=$?
  [ "$status" -eq "$expected_status" ] || {
    printf 'unexpected install status for %s: %s\n' "$name" "$status" >&2
    exit 1
  }
  touch "$root/setup.log"
  if [ -n "$expected_log" ]; then
    grep -Fq "$expected_log" "$root/setup.log" || {
      printf 'missing install log for %s: %s\n' "$name" "$(cat "$root/setup.log")" >&2
      exit 1
    }
  fi
}

run_install_case success 0 1 0 ''
run_install_case deferred 1 1 0 'install-service 启动验证延迟'
run_install_case missing-run 1 0 1 'install-service 失败且未生成可执行 run 文件'

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
