#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
HELPER="$REPO_ROOT/app/src/main/assets/maintainer/_termux-services-env.sh"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
[ -r "$HELPER" ] || fail 'shared Termux service helper is missing'

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
bin_dir="$work_dir/bin"
prefix="$work_dir/prefix"
mkdir -p "$bin_dir" "$prefix/bin"

cat > "$bin_dir/service-daemon" <<'EOF'
#!/usr/bin/env bash
touch "$MOCK_RUNSVDIR_READY"
EOF
cat > "$bin_dir/sv" <<'EOF'
#!/usr/bin/env bash
set -eu
state="$MOCK_SV_STATE"
case "${1:-}" in
  up)
    count=0
    [ -f "$state" ] && count="$(cat "$state")"
    count=$((count + 1))
    printf '%s\n' "$count" > "$state"
    if [ "$count" -eq 1 ]; then
      printf 'unable to change to service directory: file does not exist\n' >&2
      exit 1
    fi
    ;;
  status)
    count=0
    [ -f "$state" ] && count="$(cat "$state")"
    if [ "$count" -ge 2 ]; then
      printf 'run: service-manager: (pid 123) 1s\n'
    else
      printf 'down: service-manager: 0s\n'
      exit 1
    fi
    ;;
  *) exit 1 ;;
esac
EOF
chmod 700 "$bin_dir/service-daemon" "$bin_dir/sv"

export HOME="$work_dir/home"
export PREFIX="$prefix"
export SVDIR="$prefix/var/service"
export LOGDIR="$prefix/var/log"
export MOCK_RUNSVDIR_READY="$work_dir/runsvdir-ready"
export MOCK_SV_STATE="$work_dir/sv-state"
export PATH="$bin_dir:$PATH"
mkdir -p "$HOME" "$SVDIR/service-manager"

source "$HELPER"
log() { :; }
warn() { :; }

# Simulate the delayed service-daemon/runsvdir handoff without touching /proc.
oh_termux_runsvdir_active() { [ -f "$MOCK_RUNSVDIR_READY" ]; }
oh_termux_services_environment
oh_start_termux_services_daemon || fail 'service-daemon readiness did not complete'
touch "$SVDIR/service-manager/run"
chmod 700 "$SVDIR/service-manager/run"
oh_service_manager_sv_up_with_retry service-manager 3 \
  || fail 'sv up retry did not recover from the first file-not-found result'

[ "$(cat "$MOCK_SV_STATE")" -ge 2 ] || fail 'sv up was not retried'
printf 'start-control-plane runit race contract passed\n'
