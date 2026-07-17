#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
MAINTAINER_NODE="$REPO_ROOT/app/src/main/assets/maintainer/install-node.sh"
BOOTSTRAP_NODE="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/38-install-node.sh"
INSTALL_SMALLPHONE="$REPO_ROOT/app/src/main/assets/maintainer/install-smallphone.sh"
BOOTSTRAP="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/bootstrap.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

for script in "$MAINTAINER_NODE" "$BOOTSTRAP_NODE" "$INSTALL_SMALLPHONE" "$BOOTSTRAP"; do
  bash -n "$script" || fail "shell syntax invalid: $script"
done

for script in "$MAINTAINER_NODE" "$BOOTSTRAP_NODE"; do
  grep -Fq 'NODE_ROOT=/root/.local/node' "$script" \
    || fail "$script does not pin Ubuntu Node to /root/.local/node"
  grep -Fq 'unset PREFIX LD_LIBRARY_PATH LD_PRELOAD' "$script" \
    || fail "$script does not isolate Ubuntu from Termux libraries"
  grep -Fq '[ "$node_path" = "$NODE_ROOT/bin/node" ]' "$script" \
    || fail "$script accepts an arbitrary inherited Node"
  grep -Fq '[ "$platform_arch" = "linux/arm64" ]' "$script" \
    || fail "$script does not verify linux/arm64"
done

if grep -Fq 'require_aionui_healthy' "$INSTALL_SMALLPHONE"; then
  fail 'SmallPhone install still treats AionUi health as a technical dependency'
fi
grep -Fq 'bash "$bootstrap" install-smallphone' "$INSTALL_SMALLPHONE" \
  || fail 'SmallPhone install does not call the versioned bootstrap route'
grep -Fq 'report_aionui_health' "$INSTALL_SMALLPHONE" \
  || fail 'SmallPhone install does not record the deferred AionUi final status'

grep -Fq '/api/v1/services/smallphone-core/start' "$INSTALL_SMALLPHONE" \
  || fail 'SmallPhone core is not started through service-manager REST'
grep -Fq 'wait_for_endpoint smallphone-core api' "$INSTALL_SMALLPHONE" \
  || fail 'SmallPhone core dynamic api endpoint is not verified'
grep -Fq '/api/v1/services/smallphone-frontend-beta/start' "$INSTALL_SMALLPHONE" \
  || fail 'SmallPhone Front Beta is not started through service-manager REST'
grep -Fq 'wait_for_endpoint smallphone-frontend-beta web' "$INSTALL_SMALLPHONE" \
  || fail 'SmallPhone Front Beta dynamic web endpoint is not verified'
if grep -Eq '22000|22082' "$INSTALL_SMALLPHONE"; then
  fail 'SmallPhone AI-stage installer still hard-codes legacy ports'
fi

grep -Fq 'install-smallphone|smallphone)' "$BOOTSTRAP" \
  || fail 'bootstrap install-smallphone route is missing'
grep -Fq 'SMALLPHONEAI_COMPONENT_TARGETS=smallphone' "$BOOTSTRAP" \
  || fail 'bootstrap install-smallphone route does not target SmallPhone'

printf 'AI install stage contract tests passed\n'
