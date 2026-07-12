#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
TARGET="$REPO_ROOT/app/src/main/assets/maintainer/repair-control-plane-termux-native.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

extract_install_function() {
  awk '
    /^install_termux_service_manager\(\) \{/ { found = 1 }
    found { print }
    found && /^}$/ { exit }
  ' "$TARGET"
}

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT INT HUP TERM

export TEST_ROOT="$work_dir"
export HOME="$work_dir/termux home"
export TEST_CAPTURE="$work_dir/install-environment"
export TEST_INSTALLED_MARKER="$work_dir/service-manager-installed"
export SMALLPHONEAI_TERMUX_SERVICE_MANAGER_INSTALL_MODE="termux-native-test"

repo="$HOME/smallphoneai-repos/service-manager"
mkdir -p "$repo/scripts"

# The executable bit makes a direct invocation possible in principle, while
# the deliberately missing interpreter makes `./scripts/install.sh` fail.
# Passing the file explicitly to bash must ignore this shebang and succeed.
cat > "$repo/scripts/install.sh" <<'EOF'
#!/definitely/missing/openhouse-test-shell
set -euo pipefail
{
  printf 'BIND=%s\n' "${BIND-}"
  printf 'CONFIG_PATH=%s\n' "${CONFIG_PATH-}"
  printf 'SERVICE_MANAGER_INSTALL_MODE=%s\n' "${SERVICE_MANAGER_INSTALL_MODE-}"
  printf 'INSTALL_SERVICE=%s\n' "${INSTALL_SERVICE-}"
  printf 'PWD=%s\n' "$PWD"
  printf 'ARGV0=%s\n' "$0"
  printf 'ARGC=%s\n' "$#"
} > "$TEST_CAPTURE"
: > "$TEST_INSTALLED_MARKER"
EOF
chmod 755 "$repo/scripts/install.sh"

[ ! -e /definitely/missing/openhouse-test-shell ] \
  || fail 'the deliberately missing install.sh interpreter unexpectedly exists'

expected_bind="127.0.0.1:28991"
expected_config="$HOME/.config/openhouse ai/service-manager/config.json"

find_termux_service_manager() {
  [ -f "$TEST_INSTALLED_MARKER" ]
}

prepare_termux_service_manager_repo() {
  return 0
}

configured_service_manager_bind() {
  printf '%s\n' "$expected_bind"
}

termux_service_manager_config() {
  printf '%s\n' "$expected_config"
}

log() {
  :
}

install_function="$(extract_install_function)"
[ -n "$install_function" ] || fail 'install_termux_service_manager was not found'
eval "$install_function"

install_termux_service_manager \
  || fail 'Termux-native service-manager install did not use an explicit bash interpreter'

[ -s "$TEST_CAPTURE" ] || fail 'mock install.sh did not record its environment'
grep -Fxq "BIND=$expected_bind" "$TEST_CAPTURE" || fail 'BIND was not passed correctly'
grep -Fxq "CONFIG_PATH=$expected_config" "$TEST_CAPTURE" || fail 'CONFIG_PATH was not passed correctly'
grep -Fxq 'SERVICE_MANAGER_INSTALL_MODE=termux-native-test' "$TEST_CAPTURE" \
  || fail 'SERVICE_MANAGER_INSTALL_MODE was not passed correctly'
grep -Fxq 'INSTALL_SERVICE=0' "$TEST_CAPTURE" || fail 'INSTALL_SERVICE=0 was not passed'
grep -Fxq "PWD=$repo" "$TEST_CAPTURE" || fail 'install.sh did not run from the service-manager repo'
grep -Fxq 'ARGV0=./scripts/install.sh' "$TEST_CAPTURE" || fail 'unexpected install.sh invocation path'
grep -Fxq 'ARGC=0' "$TEST_CAPTURE" || fail 'unexpected positional arguments were passed to install.sh'

printf 'Termux-native control-plane install regression test passed\n'
