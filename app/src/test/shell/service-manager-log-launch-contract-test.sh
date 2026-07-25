#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

targets=(
  "$REPO_ROOT/app/src/main/assets/maintainer/start-control-plane-termux-native.sh"
  "$REPO_ROOT/app/src/main/assets/maintainer/start-smallphone.sh"
  "$REPO_ROOT/app/src/main/assets/maintainer/repair-control-plane-termux-native.sh"
  "$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh"
  "$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/60-start-smallphone.sh"
)

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

for target in "${targets[@]}"; do
  grep -Fq -- '--log-file' "$target" || fail "$target does not pass the formal log path"
  if grep -Eq '(>|>>)[[:space:]]*"?[^[:space:]]*service-manager\.log"?' "$target"; then
    fail "$target still redirects stdout/stderr into the formal service-manager log"
  fi
done

for target in "${targets[@]}"; do
  if grep -Eq '(^|[[:space:]])(nohup|setsid)([[:space:]]|.*serve)' "$target"; then
    grep -Fq 'service-manager-bootstrap.log' "$target" \
      || fail "$target does not use the overwritten bootstrap log"
  fi
done

start_script="${targets[1]}"
grep -Fq '"max_bytes": 16777216' "$start_script" \
  || fail 'canonical config does not set the 16 MiB log limit'
grep -Fq '"retain_files": 2' "$start_script" \
  || fail 'canonical config does not retain two rotated logs'

printf 'service-manager launch logging contract passed\n'
