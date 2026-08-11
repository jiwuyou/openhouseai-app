#!/data/data/com.termux/files/usr/bin/bash
set -u

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
SVDIR="${SVDIR:-$PREFIX/var/service}"
LOGDIR="${LOGDIR:-$PREFIX/var/log}"
export PREFIX SVDIR LOGDIR

LOCK_FILE="$PREFIX/var/lock/openhouse-control-plane-start.lock"
mkdir -p "$PREFIX/var/lock" "$LOGDIR" || exit $?
exec 9>"$LOCK_FILE"
flock 9 || exit $?

printf 'PREFIX=%s\nSVDIR=%s\nLOGDIR=%s\n' "$PREFIX" "$SVDIR" "$LOGDIR"

runsvdir_ready() {
  local cmdline arg executable has_svdir first
  for cmdline in /proc/[0-9]*/cmdline; do
    [ -r "$cmdline" ] || continue
    executable=""
    has_svdir=0
    first=1
    while IFS= read -r -d '' arg; do
      if [ "$first" -eq 1 ]; then
        executable="$arg"
        first=0
      fi
      [ "$arg" != "$SVDIR" ] || has_svdir=1
    done < "$cmdline"
    case "$executable" in */runsvdir|runsvdir) ;; *) continue ;; esac
    if [ "$has_svdir" -eq 1 ]; then
      return 0
    fi
  done
  return 1
}

"$PREFIX/bin/service-daemon" start 9>&-
daemon_status=$?
if [ "$daemon_status" -ne 0 ]; then
  if runsvdir_ready; then
    printf 'service-daemon=already-running exit=%s\n' "$daemon_status"
  else
    exit "$daemon_status"
  fi
fi

attempt=1
while [ "$attempt" -le 40 ]; do
  if runsvdir_ready; then
    printf 'runsvdir=ready attempt=%s\n' "$attempt"
    break
  fi
  if [ "$attempt" -eq 40 ]; then
    printf 'runsvdir did not begin monitoring %s\n' "$SVDIR" >&2
    exit 1
  fi
  sleep 0.25
  attempt=$((attempt + 1))
done

attempt=1
while [ "$attempt" -le 10 ]; do
  printf 'sv_up_attempt=%s\n' "$attempt"
  if env SVDIR="$SVDIR" LOGDIR="$LOGDIR" "$PREFIX/bin/sv" up service-manager; then
    exit 0
  else
    status=$?
  fi
  if [ "$attempt" -eq 10 ]; then
    exit "$status"
  fi
  case "$attempt" in
    1) sleep 0.25 ;;
    2) sleep 0.5 ;;
    *) sleep 1 ;;
  esac
  attempt=$((attempt + 1))
done
