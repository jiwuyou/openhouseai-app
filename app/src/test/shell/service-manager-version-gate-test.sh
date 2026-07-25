#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"

targets=(
  "$REPO_ROOT/app/src/main/assets/maintainer/repair-control-plane-termux-native.sh"
  "$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh"
  "$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/60-start-smallphone.sh"
)

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

extract_function() {
  local target="$1"
  local function_name="$2"
  awk -v signature="$function_name() {" '
    $0 == signature { capture = 1 }
    capture { print }
    capture && $0 == "}" { exit }
  ' "$target"
}

expected_counts=(1 2 4)

for index in "${!targets[@]}"; do
  target="${targets[$index]}"
  expected_count="${expected_counts[$index]}"

  if grep -Eq 'service-manager 0\.(2\.1|3\.[012])' "$target"; then
    fail "legacy service-manager gate remains in $target"
  fi

  actual_count="$(grep -Fc 'service-manager 0.3.3' "$target" || true)"
  if [ "$actual_count" -ne "$expected_count" ]; then
    fail "$target has $actual_count service-manager 0.3.3 gates; expected $expected_count"
  fi
done

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT INT HUP TERM
mkdir -p "$work_dir/bin" "$work_dir/home" "$work_dir/prefix/bin"
cat > "$work_dir/bin/service-manager" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$FAKE_SERVICE_MANAGER_VERSION"
EOF
cat > "$work_dir/bin/auth-probe" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[ "$SERVICE_MANAGER_TOKEN" = "$EXPECTED_SERVICE_MANAGER_TOKEN" ]
[ "$SMALLPHONE_SERVICE_MANAGER_TOKEN" = "$EXPECTED_COMPATIBILITY_TOKEN" ]
tr '\000' '\n' < "/proc/$$/cmdline" > "$AUTH_ARGV_FILE"
printf 'auth probe completed\n'
EOF
cat > "$work_dir/bin/proot-distro" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
tr '\000' '\n' < "/proc/$$/cmdline" > "$PROOT_ARGV_FILE"
[ "${1:-}" = login ]
[ "${2:-}" = ubuntu ]
[ "${3:-}" = -- ]
shift 3
exec "$@"
EOF
chmod 755 "$work_dir/bin/service-manager" "$work_dir/bin/auth-probe" "$work_dir/bin/proot-distro"

repair_target="${targets[0]}"
install_target="${targets[1]}"
start_target="${targets[2]}"

(
  eval "$(extract_function "$repair_target" service_manager_is_current)"
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.2.1'
  if service_manager_is_current "$work_dir/bin/service-manager"; then
    fail 'repair gate accepted legacy 0.2.1'
  fi
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.3.1'
  if service_manager_is_current "$work_dir/bin/service-manager"; then
    fail 'repair gate accepted previous 0.3.1'
  fi
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.3.2'
  if service_manager_is_current "$work_dir/bin/service-manager"; then
    fail 'repair gate accepted previous 0.3.2'
  fi
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.3.3'
  service_manager_is_current "$work_dir/bin/service-manager" \
    || fail 'repair gate rejected required 0.3.3'
)

(
  eval "$(extract_function "$install_target" component_binary_current_env_executable)"
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.2.1'
  if component_binary_current_env_executable service-manager "$work_dir/bin/service-manager"; then
    fail 'install gate accepted legacy 0.2.1'
  fi
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.3.1'
  if component_binary_current_env_executable service-manager "$work_dir/bin/service-manager"; then
    fail 'install gate accepted previous 0.3.1'
  fi
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.3.2'
  if component_binary_current_env_executable service-manager "$work_dir/bin/service-manager"; then
    fail 'install gate accepted previous 0.3.2'
  fi
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.3.3'
  component_binary_current_env_executable service-manager "$work_dir/bin/service-manager" \
    || fail 'install gate rejected required 0.3.3'
)

(
  eval "$(extract_function "$start_target" find_termux_service_manager_binary)"
  export PATH="$work_dir/bin:/usr/bin:/bin"
  export HOME="$work_dir/home"
  export PREFIX="$work_dir/prefix"
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.2.1'
  if find_termux_service_manager_binary >/dev/null; then
    fail 'start gate selected legacy 0.2.1'
  fi
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.3.1'
  if find_termux_service_manager_binary >/dev/null; then
    fail 'start gate selected previous 0.3.1'
  fi
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.3.2'
  if find_termux_service_manager_binary >/dev/null; then
    fail 'start gate selected previous 0.3.2'
  fi
  export FAKE_SERVICE_MANAGER_VERSION='service-manager 0.3.3'
  [ "$(find_termux_service_manager_binary)" = "$work_dir/bin/service-manager" ] \
    || fail 'start gate did not select required 0.3.3'
)

assert_auth_wrapper_safe() {
  local target="$1"
  local label="$2"
  local output_file="$work_dir/$label-output.log"
  local auth_log="$work_dir/$label-runtime.log"
  local argv_file="$work_dir/$label-argv.log"
  local token='canonical-token-must-not-leak-2517'
  local compatibility_token='compatibility-token-must-not-leak-2517'

  (
    eval "$(extract_function "$target" run_logged)"
    eval "$(extract_function "$target" run_with_service_manager_auth)"
    log() {
      printf '[test] %s\n' "$*" | tee -a "$auth_log"
    }
    export AUTH_ARGV_FILE="$argv_file"
    export EXPECTED_SERVICE_MANAGER_TOKEN="$token"
    export EXPECTED_COMPATIBILITY_TOKEN="$compatibility_token"
    export SMALLPHONE_SERVICE_MANAGER_TOKEN="$compatibility_token"
    run_with_service_manager_auth "$token" run_logged "$work_dir/bin/auth-probe" public-argument
  ) > "$output_file" 2>&1

  [ -s "$argv_file" ] || fail "$label auth probe did not record argv"
  for inspected_file in "$argv_file" "$output_file" "$auth_log"; do
    if grep -Fq -e "$token" -e "$compatibility_token" "$inspected_file"; then
      fail "$label leaked a service-manager token through $inspected_file"
    fi
  done
}

assert_token_file_handoff_safe() {
  local target="$1"
  local label="$2"
  local token='ubuntu-token-must-not-leak-2517'
  local proot_argv="$work_dir/$label-proot-argv.log"
  local output_file="$work_dir/$label-token-file-output.log"
  local token_file mode

  (
    eval "$(extract_function "$target" create_ubuntu_service_manager_token_file)"
    eval "$(extract_function "$target" load_service_manager_token_file)"
    export PATH="$work_dir/bin:/usr/bin:/bin"
    export PROOT_ARGV_FILE="$proot_argv"
    token_file="$(create_ubuntu_service_manager_token_file "$token")"
    [ -f "$token_file" ] || fail "$label did not create the Ubuntu token file"
    mode="$(stat -c '%a' "$token_file")"
    [ "$mode" = 600 ] || fail "$label token file mode is $mode, expected 600"
    [ "$(cat "$token_file")" = "$token" ] || fail "$label token file content changed"

    unset SERVICE_MANAGER_TOKEN SMALLPHONE_SERVICE_MANAGER_TOKEN
    export SMALLPHONEAI_SERVICE_MANAGER_TOKEN_FILE="$token_file"
    load_service_manager_token_file
    [ "$SERVICE_MANAGER_TOKEN" = "$token" ] || fail "$label did not load the canonical token"
    [ "$SMALLPHONE_SERVICE_MANAGER_TOKEN" = "$token" ] || fail "$label did not load the compatibility token"
    [ ! -e "$token_file" ] || fail "$label did not remove the consumed token file"
  ) > "$output_file" 2>&1

  [ -s "$proot_argv" ] || fail "$label did not record proot argv"
  for inspected_file in "$proot_argv" "$output_file"; do
    if grep -Fq "$token" "$inspected_file"; then
      fail "$label leaked the Ubuntu token through $inspected_file"
    fi
  done
}

for target in "$install_target" "$start_target"; do
  label="$(basename "$target" .sh)"
  assert_auth_wrapper_safe "$target" "$label"
  assert_token_file_handoff_safe "$target" "$label"
  grep -Fq "trap cleanup_auth_file EXIT" "$target" \
    || fail "$label is missing the token-file creation cleanup trap"
  grep -Fq "trap 'cleanup_ubuntu_service_manager_token_file \"\$ubuntu_token_file\"' EXIT" "$target" \
    || fail "$label is missing the cross-runtime cleanup trap"
done

printf 'service-manager version and token safety focused tests passed\n'
