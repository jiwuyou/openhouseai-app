#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
TARGET="$REPO_ROOT/app/src/main/assets/maintainer/start-smallphone.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

free_port() {
  python3 - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
}

write_fakes() {
  local bin_dir="$1"
  mkdir -p "$bin_dir"

  cat > "$bin_dir/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

config=""
url=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -K)
      config="$2"
      shift 2
      ;;
    http://*|https://*)
      url="$1"
      shift
      ;;
    *)
      shift
      ;;
  esac
done

[ -f "$TEST_STATE/running" ] || exit 7
case "$url" in
  */api/v1/health)
    exit 0
    ;;
  */api/v1/services)
    [ -n "$config" ] && grep -Fq "Authorization: Bearer $EXPECTED_CANONICAL_TOKEN" "$config" || exit 22
    printf 'canonical-auth\n' >> "$TEST_STATE/auth-events"
    exit 0
    ;;
  *)
    exit 22
    ;;
esac
EOF

  cat > "$bin_dir/service-manager" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

[ -z "${SERVICE_MANAGER_TOKEN+x}" ] || exit 51
[ -z "${SMALLPHONE_SERVICE_MANAGER_TOKEN+x}" ] || exit 52
printf '%s\n' "$@" > "$TEST_STATE/manager-argv"
printf '%s\n' "$$" > "$TEST_STATE/manager-pid"
touch "$TEST_STATE/running"
while :; do
  sleep 60
done
EOF

  cat > "$bin_dir/pgrep" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [ -f "$TEST_STATE/existing-manager-pids" ]; then
  cat "$TEST_STATE/existing-manager-pids"
  exit 0
fi
exit 1
EOF

  chmod 755 "$bin_dir/curl" "$bin_dir/service-manager" "$bin_dir/pgrep"
}

prepare_case() {
  local root="$1" port="$2"
  mkdir -p \
    "$root/home/.config/openhouseai/service-manager" \
    "$root/home/.config/service-manager" \
    "$root/home/.smallphoneai-bootstrap" \
    "$root/prefix/bin" \
    "$root/prefix/tmp" \
    "$root/state"

  cat > "$root/home/.config/openhouseai/service-manager/config.json" <<EOF
{
  "auth_token": "$EXPECTED_CANONICAL_TOKEN",
  "listen_addr": "127.0.0.1:$port"
}
EOF
  cat > "$root/home/.config/service-manager/config.json" <<EOF
{
  "auth_token": "$DEFAULT_CONFIG_TOKEN",
  "listen_addr": "127.0.0.1:$port"
}
EOF
  cat > "$root/home/.smallphoneai-bootstrap/bootstrap.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ -z "${SERVICE_MANAGER_TOKEN+x}" ] || exit 41
[ -z "${SMALLPHONE_SERVICE_MANAGER_TOKEN+x}" ] || exit 42
printf '%s\n' "$*" > "$TEST_STATE/bootstrap-call"
EOF
  chmod 755 "$root/home/.smallphoneai-bootstrap/bootstrap.sh"
  write_fakes "$root/bin"
}

run_target() {
  local root="$1" output="$2"
  set +e
  (
    export HOME="$root/home"
    export PREFIX="$root/prefix"
    export TMPDIR="$root/prefix/tmp"
    export TEST_STATE="$root/state"
    export PATH="$root/bin:$PREFIX/bin:/usr/bin:/bin"
    export SMALLPHONEAI_SERVICE_MANAGER_READY_ATTEMPTS=3
    export SERVICE_MANAGER_TOKEN="$DEFAULT_CONFIG_TOKEN"
    export SMALLPHONE_SERVICE_MANAGER_TOKEN="$DEFAULT_CONFIG_TOKEN"
    log() { printf '%s\n' "$*"; }
    run_logged() { "$@"; }
    require_ubuntu() { :; }
    run_ubuntu_logged() { "$@"; }
    source "$TARGET"
  ) > "$output" 2>&1
  local status=$?
  set -e
  return "$status"
}

cleanup_manager() {
  local root="$1" pid
  if [ -f "$root/state/manager-pid" ]; then
    pid="$(cat "$root/state/manager-pid")"
    kill "$pid" >/dev/null 2>&1 || true
  fi
}

EXPECTED_CANONICAL_TOKEN='canonical-secret-123456'
DEFAULT_CONFIG_TOKEN='default-secret-654321'
export EXPECTED_CANONICAL_TOKEN DEFAULT_CONFIG_TOKEN

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT INT HUP TERM

# Exercise the production ERE with the host's real pgrep implementation.
real_pgrep_dir="$work_dir/real-pgrep"
mkdir -p "$real_pgrep_dir"
cat > "$real_pgrep_dir/service-manager" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
while :; do
  sleep 60
done
EOF
chmod 755 "$real_pgrep_dir/service-manager"
"$real_pgrep_dir/service-manager" serve --config /tmp/openhouse-test-config --bind 127.0.0.1:29999 &
real_manager_pid=$!
sleep 1
source <(awk '/^termux_service_manager_serve_pids\(\)/ {emit=1} /^termux_service_manager_port_open\(\)/ {emit=0} emit' "$TARGET")
real_detected_pids="$(termux_service_manager_serve_pids)"
kill "$real_manager_pid" >/dev/null 2>&1 || true
printf '%s\n' "$real_detected_pids" | grep -Fxq "$real_manager_pid" \
  || fail "real pgrep did not match service-manager serve with single spaces"
unset -f termux_service_manager_serve_pids

# Already-running manager: authenticate and continue without spawning another process.
case_root="$work_dir/already-running"
port="$(free_port)"
prepare_case "$case_root" "$port"
touch "$case_root/state/running"
run_target "$case_root" "$case_root/output" || fail "already-running case failed"
[ ! -e "$case_root/state/manager-argv" ] || fail "already-running case spawned a duplicate manager"
[ -f "$case_root/state/bootstrap-call" ] || fail "already-running case did not continue to bootstrap"

# Installed but stopped: start with canonical config, wait, authenticate, and continue.
case_root="$work_dir/stopped"
port="$(free_port)"
prepare_case "$case_root" "$port"
run_target "$case_root" "$case_root/output" || fail "stopped manager was not started"
[ -f "$case_root/state/manager-argv" ] || fail "manager start argv was not recorded"
grep -Fxq -- "--config" "$case_root/state/manager-argv" || fail "canonical config flag missing"
grep -Fxq -- "$case_root/home/.config/openhouseai/service-manager/config.json" "$case_root/state/manager-argv" \
  || fail "manager did not use canonical config"
grep -Fxq -- "--bind" "$case_root/state/manager-argv" || fail "bind flag missing"
grep -Fxq -- "127.0.0.1:$port" "$case_root/state/manager-argv" || fail "configured bind missing"
[ -f "$case_root/state/auth-events" ] || fail "canonical authentication was not verified"
[ -f "$case_root/state/bootstrap-call" ] || fail "stopped case did not continue to bootstrap"
cleanup_manager "$case_root"
grep -Fq "$DEFAULT_CONFIG_TOKEN" "$case_root/home/.config/service-manager/config.json" \
  || fail "default service-manager config was modified"

# A different legacy/default token must never be selected, logged, or passed on argv.
if grep -Fq "$DEFAULT_CONFIG_TOKEN" "$case_root/output" "$case_root/state/manager-argv"; then
  fail "default service-manager config token leaked or was selected"
fi
if grep -Fq "$EXPECTED_CANONICAL_TOKEN" "$case_root/output" "$case_root/state/manager-argv"; then
  fail "canonical token appeared in output or manager argv"
fi
manager_log="$case_root/home/.smallphoneai/logs/service-manager.log"
if [ -f "$manager_log" ] \
  && grep -Fq -e "$EXPECTED_CANONICAL_TOKEN" -e "$DEFAULT_CONFIG_TOKEN" "$manager_log"; then
  fail "token appeared in service-manager log"
fi

# Reachable API with a token that does not match canonical config must fail without duplication.
case_root="$work_dir/auth-mismatch"
port="$(free_port)"
prepare_case "$case_root" "$port"
sed -i "s/$EXPECTED_CANONICAL_TOKEN/mismatched-canonical-token/" \
  "$case_root/home/.config/openhouseai/service-manager/config.json"
touch "$case_root/state/running"
if run_target "$case_root" "$case_root/output"; then
  fail "auth-mismatch case unexpectedly succeeded"
fi
grep -Fq "认证不匹配" "$case_root/output" || fail "auth mismatch was not explicit"
[ ! -e "$case_root/state/manager-argv" ] || fail "auth-mismatch case spawned a second manager"

# A non-manager listener on the canonical port must fail explicitly without spawning.
case_root="$work_dir/conflict"
port="$(free_port)"
prepare_case "$case_root" "$port"
python3 -m http.server "$port" --bind 127.0.0.1 > "$case_root/http.log" 2>&1 &
listener_pid=$!
sleep 1
if run_target "$case_root" "$case_root/output"; then
  kill "$listener_pid" >/dev/null 2>&1 || true
  fail "port-conflict case unexpectedly succeeded"
fi
kill "$listener_pid" >/dev/null 2>&1 || true
grep -Fq "端口已被非预期进程占用" "$case_root/output" || fail "port conflict was not explicit"
[ ! -e "$case_root/state/manager-argv" ] || fail "port-conflict case spawned a manager"

printf 'start-smallphone focused tests passed\n'
