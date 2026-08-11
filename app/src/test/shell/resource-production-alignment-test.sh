#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
checker="$repo_dir/scripts/check-production-resource-alignment.sh"
server="$repo_dir/app/src/test/fixtures/resource-alignment-fixture-server.mjs"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/resource-production-alignment-test.XXXXXX")"
server_pid=""

cleanup() {
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  rm -rf -- "$test_root"
}
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

start_server() {
  local mode="$1" fixture_root="$test_root/$mode" port_file="$test_root/$mode.port"
  mkdir -p "$fixture_root"
  rm -f -- "$port_file"
  node "$server" "$fixture_root" "$mode" "$port_file" >"$test_root/$mode.server.log" 2>&1 &
  server_pid=$!
  for _ in $(seq 1 100); do
    [[ -s "$port_file" ]] && break
    kill -0 "$server_pid" 2>/dev/null || {
      cat "$test_root/$mode.server.log" >&2
      fail "fixture server exited for $mode"
    }
    sleep 0.05
  done
  [[ -s "$port_file" ]] || fail "fixture server did not start for $mode"
  fixture_dir="$fixture_root"
  market_url="http://127.0.0.1:$(cat "$port_file")"
}

stop_server() {
  kill "$server_pid" 2>/dev/null || true
  wait "$server_pid" 2>/dev/null || true
  server_pid=""
}

run_case() {
  local mode="$1" expected="$2"
  local report="$test_root/$mode-report.json" output="$test_root/$mode.output"
  start_server "$mode"
  set +e
  "$checker" \
    --market-url "$market_url" \
    --repository-root "$fixture_dir" \
    --resource-set "$fixture_dir/resource-set.json" \
    --publish-manifest "$fixture_dir/publish-manifest.json" \
    --report "$report" \
    --timeout 5 \
    --attempts 2 >"$output" 2>&1
  status=$?
  set -e
  stop_server
  if [[ "$expected" == pass ]]; then
    [[ "$status" -eq 0 ]] || { cat "$output" >&2; fail "$mode should pass"; }
    jq -e '
      .status == "passed" and
      .resourceSet.id == "openhouse-core-stack" and
      .resourceSet.sequence == 2026081101 and
      (.resources | length) == 5 and
      .resourceCatalogRevisionBefore == .resourceCatalogRevisionAfter and
      .resourceCatalogSnapshotSha256Before == .resourceCatalogSnapshotSha256After and
      .resourceSetSnapshotSha256Before == .resourceSetSnapshotSha256After
    ' "$report" >/dev/null || fail "$mode wrote an invalid report"
  else
    [[ "$status" -ne 0 ]] || fail "$mode should fail"
    [[ ! -e "$report" ]] || fail "$mode left a success report after failure"
  fi
}

for command in node jq; do
  command -v "$command" >/dev/null 2>&1 || fail "$command is required"
done
[[ -x "$checker" ]] || fail "alignment checker is not executable"

run_case aligned pass
run_case transient-catalog pass
run_case remote-newer fail
run_case remote-older fail
run_case set-version-mismatch fail
run_case set-sha-mismatch fail
run_case missing-resource fail
run_case corrupt-archive fail
run_case changing-catalog fail
run_case changing-set fail
run_case untrusted-origin fail

printf 'Production resource alignment gate tests passed\n'
