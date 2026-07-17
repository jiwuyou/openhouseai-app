#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
TARGET="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

diagnostic_functions="$(awk '
  /^redact_service_manager_diagnostic_stream\(\) \{/ { capture=1 }
  /^resolve_termux_service_manager_token\(\) \{/ { capture=0 }
  capture { print }
' "$TARGET")"
[ -n "$diagnostic_functions" ] || fail 'diagnostic functions were not found'

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT INT HUP TERM
mkdir -p "$work_dir/bin" "$work_dir/home/.smallphoneai/logs" "$work_dir/tmp"

service_manager_bin="$work_dir/bin/service-manager"
cat > "$service_manager_bin" <<'EOF'
#!/usr/bin/env sh
if [ "${1:-}" = "--version" ]; then
  printf '%s\n' 'service-manager 0.3.1'
  exit 0
fi
exit 0
EOF
chmod 755 "$service_manager_bin"

cat > "$work_dir/bin/ss" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' 'State Recv-Q Send-Q Local Address:Port Peer Address:Port'
EOF
chmod 755 "$work_dir/bin/ss"

cat > "$work_dir/bin/curl" <<'EOF'
#!/usr/bin/env sh
printf '%s\n' "$*" >> "$TEST_CURL_ARGS"
case "$*" in
  *'/api/v1/services'*) printf '401' ;;
  *'/api/v1/health'*) printf '503' ;;
  *) printf '000' ;;
esac
EOF
chmod 755 "$work_dir/bin/curl"

config="$work_dir/home/.config/openhouseai/service-manager/config.json"
mkdir -p "$(dirname "$config")"
printf '%s\n' '{}' > "$config"
chmod 600 "$config"

cat > "$work_dir/home/.smallphoneai/logs/service-manager.log" <<'EOF'
Authorization: Bearer bearer-super-secret
auth_token="config-super-secret"
password=login-super-secret
api_key: sk-api-super-secret-12345678
standalone sk-standalone-super-secret-12345678
fatal: Address already in use
EOF

export HOME="$work_dir/home"
export TMPDIR="$work_dir/tmp"
export PATH="$work_dir/bin:/usr/bin:/bin"
export TEST_CURL_ARGS="$work_dir/curl-args"
export TEST_CONFIG="$config"
export TEST_SERVICE_MANAGER_BIN="$service_manager_bin"
export TEST_SERVICE_MANAGER_TOKEN='canonical-token-super-secret'

eval "$diagnostic_functions"

warn() {
  printf '[test] %s\n' "$*" >&2
}
termux_service_manager_config_path() {
  printf '%s\n' "$TEST_CONFIG"
}
find_termux_service_manager_binary() {
  printf '%s\n' "$TEST_SERVICE_MANAGER_BIN"
}
termux_service_manager_serve_pids() {
  return 0
}
termux_service_manager_config_token() {
  printf '%s\n' "$TEST_SERVICE_MANAGER_TOKEN"
}

output="$work_dir/diagnostics.log"
diagnose_termux_service_manager_readiness test > "$output" 2>&1

for expected in \
  "binary path=$service_manager_bin version=service-manager 0.3.1" \
  "canonical config path=$config exists=yes permissions=mode=600" \
  'process exited: service-manager serve PID unavailable' \
  'port not listening: configured bind=127.0.0.1:20087' \
  'health HTTP result=failed status=503' \
  'token mismatch: auth API rejected canonical token status=401' \
  'service-manager log tail path=' \
  'fatal: Address already in use' \
  'ready timeout: service-manager readiness check failed'; do
  grep -Fq "$expected" "$output" || { cat "$output" >&2; fail "missing diagnostic: $expected"; }
done

grep -Fq '***REDACTED***' "$output" || fail 'redaction marker missing'
for secret in \
  bearer-super-secret \
  config-super-secret \
  login-super-secret \
  sk-api-super-secret-12345678 \
  sk-standalone-super-secret-12345678 \
  canonical-token-super-secret; do
  if grep -Fq "$secret" "$output" "$TEST_CURL_ARGS"; then
    cat "$output" >&2
    fail "secret leaked: $secret"
  fi
done

printf 'service-manager readiness diagnostics focused test passed\n'
