#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
START_SCRIPT="$REPO_ROOT/../../wuxianpi-rescue/plugins/official/wuxianpi.first-install/scripts/start-service-manager.sh"
ENTRY_SCRIPT="$REPO_ROOT/../../wuxianpi-rescue/plugins/official/wuxianpi.first-install/scripts/openhouse-control-plane-start"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
[ -r "$START_SCRIPT" ] || fail 'Termux start implementation is missing'
[ -r "$ENTRY_SCRIPT" ] || fail 'fixed Android-Termux entry is missing'

grep -Fq 'exec "$PREFIX/libexec/openhouse/start-service-manager.sh"' "$ENTRY_SCRIPT" \
  || fail 'fixed entry does not unconditionally exec the second script'
for forbidden in authToken /api/v1/services resource-set registry sha256; do
  ! grep -Fqi "$forbidden" "$START_SCRIPT" \
    || fail "start implementation contains forbidden concern: $forbidden"
done

work_dir="$(mktemp -d)"
runsvdir_pid=""
cleanup() {
  [ -z "$runsvdir_pid" ] || kill "$runsvdir_pid" 2>/dev/null || true
  rm -rf "$work_dir"
}
trap cleanup EXIT
prefix="$work_dir/prefix"
mkdir -p "$prefix/bin" "$prefix/var/service/service-manager"

cat > "$prefix/bin/service-daemon" <<'EOF'
#!/usr/bin/env bash
setsid -f bash -c 'printf "%s\n" "$$" > "$3"; exec -a "$1/bin/runsvdir" bash -c "sleep 120; :" "$2"' \
  _ "$PREFIX" "$SVDIR" "$MOCK_RUNSVDIR_PID" >/dev/null 2>&1 </dev/null
EOF
cat > "$prefix/bin/sv" <<'EOF'
#!/usr/bin/env bash
set -u
count=0
[ ! -f "$MOCK_SV_STATE" ] || count="$(cat "$MOCK_SV_STATE")"
count=$((count + 1))
printf '%s\n' "$count" > "$MOCK_SV_STATE"
printf 'SVDIR=%s LOGDIR=%s\n' "$SVDIR" "$LOGDIR" >> "$MOCK_SV_ENV"
if [ "$count" -eq 1 ]; then
  printf 'unable to change to service directory: file does not exist\n' >&2
  exit 1
fi
printf 'ok: service-manager requested\n'
EOF
chmod 700 "$prefix/bin/service-daemon" "$prefix/bin/sv"

export PREFIX="$prefix"
unset SVDIR LOGDIR
export MOCK_RUNSVDIR_PID="$work_dir/runsvdir.pid"
export MOCK_SV_STATE="$work_dir/sv-state"
export MOCK_SV_ENV="$work_dir/sv-env"
export PATH="$prefix/bin:$PATH"

output="$(bash "$START_SCRIPT" 2>&1)" || fail "start implementation failed: $output"
[ -s "$MOCK_RUNSVDIR_PID" ] || fail 'service-daemon did not launch runsvdir'
runsvdir_pid="$(cat "$MOCK_RUNSVDIR_PID")"
[ "$(cat "$MOCK_SV_STATE")" -eq 2 ] || fail 'sv up was not retried exactly once'
grep -Fq "SVDIR=$prefix/var/service LOGDIR=$prefix/var/log" "$MOCK_SV_ENV" \
  || fail 'default SVDIR and LOGDIR were not exported to sv'
grep -Fq 'sv_up_attempt=2' <<< "$output" || fail 'retry output is missing'

cat > "$prefix/bin/service-daemon" <<'EOF'
#!/usr/bin/env bash
printf 'already running\n' >&2
exit 1
EOF
chmod 700 "$prefix/bin/service-daemon"

output="$(bash "$START_SCRIPT" 2>&1)" \
  || fail "already-running service-daemon was treated as a start failure: $output"
grep -Fq 'service-daemon=already-running exit=1' <<< "$output" \
  || fail 'already-running service-daemon result was not reported'
[ "$(cat "$MOCK_SV_STATE")" -eq 3 ] || fail 'sv up was not requested after runsvdir was already ready'

printf 'fixed control-plane runit race contract passed\n'
