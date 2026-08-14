#!/data/data/com.termux/files/usr/bin/bash
set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
SVDIR="${SVDIR:-$PREFIX/var/service}"
LOGDIR="${LOGDIR:-$PREFIX/var/log}"
export PREFIX SVDIR LOGDIR

mkdir -p "$PREFIX/var/lock" "$SVDIR" "$LOGDIR" || exit $?
exec 9>"$PREFIX/var/lock/openhouse-control-plane-start.lock"
flock 9 || exit $?

"$PREFIX/bin/service-daemon" start 9>&- || true

attempt=1
while [ "$attempt" -le 40 ]; do
  if env SVDIR="$SVDIR" "$PREFIX/bin/sv" status service-manager >/dev/null 2>&1; then
    break
  fi
  sleep 0.25
  attempt=$((attempt + 1))
done

attempt=1
while [ "$attempt" -le 10 ]; do
  printf 'sv_up_attempt=%s\n' "$attempt"
  if env SVDIR="$SVDIR" LOGDIR="$LOGDIR" "$PREFIX/bin/sv" up service-manager; then
    exit 0
  fi
  status=$?
  [ "$attempt" -lt 10 ] || exit "$status"
  sleep 1
  attempt=$((attempt + 1))
done
