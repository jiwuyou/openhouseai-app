#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$repo_dir/scripts/validate-openhouse-install-bundle.sh"
[[ -s "$repo_dir/native-app/src/main/assets/wuxianpi-install/pre-tmux.sh" ]] \
  || { printf 'Native pre-tmux asset is missing\n' >&2; exit 1; }
bash -n "$repo_dir/native-app/src/main/assets/wuxianpi-install/pre-tmux.sh"
printf 'Native install resources validated\n'
