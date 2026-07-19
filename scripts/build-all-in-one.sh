#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "${SKIP_RUNTIME_BUILD:-0}" == "1" ]] || "$repo_dir/scripts/build-runtime.sh"
cd "$repo_dir"
exec ./gradlew "${ALL_IN_ONE_GRADLE_TASK:-:app:assembleRelease}" "$@"
