#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

pids="$(pgrep -f '(^|/)service-manager[[:space:]]+serve([[:space:]]|$)' || true)"
if [ -n "$pids" ]; then
  kill $pids
fi
