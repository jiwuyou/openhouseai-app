#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PAYLOAD_BUILDER="$REPO_ROOT/scripts/build-pi-node-payload.sh"
SETUP="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
PACKAGES="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/12-update-termux-packages.sh"
COMPONENTS="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/50-install-runtime-components.sh"
START="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/60-start-smallphone.sh"
REPAIR="$REPO_ROOT/app/src/main/assets/maintainer/repair-control-plane-termux-native.sh"
STATUS="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/scripts/65-smallphone-status.sh"
PI_SUBJECT="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/subjects.d/pi-agent.json"
SERVICE_SUBJECT="$REPO_ROOT/app/src/main/assets/smallphoneai/bootstrap/subjects.d/service-control.json"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

for script in "$PAYLOAD_BUILDER" "$SETUP" "$PACKAGES" "$COMPONENTS" "$START" "$REPAIR"; do
  bash -n "$script" || fail "shell syntax invalid: $script"
done

grep -Fq 'termux-services' "$PACKAGES" || fail 'formal post-tmux package stage does not install termux-services'
grep -Fq 'service-daemon start' "$PACKAGES" || fail 'formal package stage does not start service-daemon explicitly'
grep -Fq 'termux_runsvdir_active' "$PACKAGES" || fail 'formal package stage does not verify runsvdir'

for script in "$COMPONENTS" "$START"; do
  grep -Fq 'install-service --config' "$script" || fail "$script does not install the runit service"
  grep -Fq 'oh_service_manager_sv_up_with_retry service-manager' "$script" \
    || fail "$script does not use the bounded runit startup helper"
  grep -Fq 'env SVDIR="$service_root" sv status service-manager' "$script" \
    || fail "$script does not inspect service-manager in the Termux service root"
  if grep -Eq 'install-service[^\n]*\|\| true|sv up service-manager[^\n]*\|\| true' "$script"; then
    fail "$script hides a critical runit failure"
  fi
done

grep -Fq 'build-source-release.sh' "$PAYLOAD_BUILDER" \
  || fail 'runtime payload does not consume the official WuxianPi source release'
grep -Fq 'wuxianpi-install-arm64-' "$PAYLOAD_BUILDER" \
  || fail 'runtime payload does not consume the official ARM64 install artifact'
grep -Fq 'sourceRepo": "https://github.com/jiwuyou/wuxianpi.git"' "$PAYLOAD_BUILDER" \
  || fail 'runtime payload does not identify the WuxianPi source repository'
grep -Fq '"$script" --spec "$spec"' "$SETUP" || fail 'activation does not pass the complete OpenHouse ServiceSpec'
grep -Fq 'name:"runtime"' "$SETUP" || fail 'OpenHouse runtime port declaration is missing'
grep -Fq 'dynamic:true' "$SETUP" || fail 'OpenHouse runtime port is not dynamic'
grep -Fq '{{port:runtime}}/health' "$SETUP" || fail 'OpenHouse runtime health is not templated'

grep -Fq 'http://127.0.0.1:20765' "$SETUP" || fail 'setup verification still uses the old runtime port'
if grep -Fq 'SMALLPHONEAI_START_TARGETS=pi-agent run_bootstrap start' "$SETUP"; then
  fail 'first installation must register WuxianPi without starting it'
fi
grep -Fq 'register_installed_resources || activation_fail registry_file_failed' "$SETUP" \
  || fail 'activation does not register the installed WuxianPi resource'
grep -Fq 'wuxianpi_runtime_endpoint_ready' "$SETUP" || fail 'setup does not resolve the actual runtime endpoint'
grep -Fq 'wuxianpi-node-start' "$SETUP" || fail 'setup does not verify installed WuxianPi payload'
grep -Fq 'pi_runtime_port="${OPENHOUSE_PI_RUNTIME_PORT:-20765}"' "$STATUS" || fail 'status script still defaults to port 8765'
grep -Fq 'readiness_object "yuanshengwuxianpi"' "$STATUS" || fail 'status readiness uses the legacy service id'
grep -Fq 'yuanshengwuxianpi' "$PI_SUBJECT" || fail 'Pi subject still references the legacy service id'
grep -Fq '127.0.0.1:20765' "$PI_SUBJECT" || fail 'Pi subject still references port 8765'
grep -Fq 'yuanshengwuxianpi' "$SERVICE_SUBJECT" || fail 'service-control subject still references the legacy service id'
if grep -Fq -e 'pi-web' -e 'aionui-web' "$SERVICE_SUBJECT" "$PI_SUBJECT"; then
  fail 'lean WuxianPi subjects still reference standalone pi-web or AionUI'
fi
grep -Fq 'export SMALLPHONEAI_START_TARGETS="${SMALLPHONEAI_START_TARGETS:-pi-agent}"' "$START" \
  || fail 'start lifecycle does not default to pi-agent only'
if grep -Fq 'readiness_object "pi-web"' "$STATUS"; then
  fail 'status readiness still requires standalone pi-web'
fi

(
  status_root="$(mktemp -d)"
  status_pid=""
  cleanup_status_fixture() {
    [ -z "$status_pid" ] || kill "$status_pid" >/dev/null 2>&1 || true
    rm -rf "$status_root"
  }
  trap cleanup_status_fixture EXIT INT HUP TERM
  mkdir -p "$status_root/bin" "$status_root/home/.local/bin" \
    "$status_root/pi-runtime/runtime/dist" \
    "$status_root/pi-runtime/runtime/builtin-packages/task-manager" \
    "$status_root/pi-runtime/web" \
    "$status_root/pi-runtime/base/node_modules/@earendil-works/pi-coding-agent"
  cat > "$status_root/bin/curl" <<'EOF'
#!/usr/bin/env sh
exit 0
EOF
  chmod 755 "$status_root/bin/curl"
  for executable in wuxianpi wuxianpi-node wuxianpi-node-start; do
    printf '#!/usr/bin/env sh\nexit 0\n' > "$status_root/home/.local/bin/$executable"
    chmod 755 "$status_root/home/.local/bin/$executable"
  done
  : > "$status_root/pi-runtime/runtime/dist/index.js"
  : > "$status_root/pi-runtime/runtime/builtin-packages/task-manager/wuxianpi-package.json"
  : > "$status_root/pi-runtime/web/index.html"
  : > "$status_root/pi-runtime/base/node_modules/@earendil-works/pi-coding-agent/package.json"
  status_port="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"
  python3 -m http.server "$status_port" --bind 127.0.0.1 > "$status_root/http.log" 2>&1 &
  status_pid=$!
  for _ in $(seq 1 20); do
    bash -c ': >/dev/tcp/127.0.0.1/$1' _ "$status_port" >/dev/null 2>&1 && break
    sleep 0.1
  done
  env \
    HOME="$status_root/home" \
    PATH="$status_root/bin:/usr/bin:/bin" \
    OPENHOUSE_PI_AGENT_DIR="$status_root/pi-runtime" \
    OPENHOUSE_PI_RUNTIME_PORT="$status_port" \
    SERVICE_MANAGER_URL="http://127.0.0.1:20087" \
    bash "$STATUS" status > "$status_root/status.json"
  jq -e '
    .ready == true
    and ([.readiness.requirements[] | select(.required == true) | .id] == ["service-manager", "yuanshengwuxianpi"])
    and ([.readiness.requirements[].id] | index("pi-web") | not)
  ' "$status_root/status.json" >/dev/null \
    || fail 'status readiness is not limited to service-manager and WuxianPi 20765'
)

grep -Fq 'stable_service_id="yuanshengwuxianpi"' "$COMPONENTS" || fail 'component registration does not map to the stable service id'
grep -Fq 'stable_service_id="yuanshengwuxianpi"' "$START" || fail 'start registration does not map to the stable service id'
grep -Fq '不会启动脱离 runit 的临时进程' "$REPAIR" || fail 'repair still permits detached fallback'
grep -Fq '未获得 runit 常驻，修复失败' "$REPAIR" || fail 'repair does not fail without runit residency'
if grep -Fq 'setsid -f' "$REPAIR" || grep -Fq 'nohup "$sm_bin" serve' "$REPAIR"; then
  fail 'repair contains a detached service-manager fallback'
fi

self_copy_root="$(mktemp -d)"
trap 'rm -rf "$self_copy_root"' EXIT
cp "$SETUP" "$self_copy_root/wuxianpi-setup"
chmod 644 "$self_copy_root/wuxianpi-setup"
source <(awk '/^command="\$\{1:-\}"/ { exit } { print }' "$SETUP")
install_runtime_script "$self_copy_root/wuxianpi-setup" "$self_copy_root/wuxianpi-setup"
[ -x "$self_copy_root/wuxianpi-setup" ] || fail 'self-copy guard did not preserve executable permissions'

printf 'replacement\n' > "$self_copy_root/source-script"
install_runtime_script "$self_copy_root/source-script" "$self_copy_root/target-script"
cmp -s "$self_copy_root/source-script" "$self_copy_root/target-script" || fail 'runtime script copy no longer replaces a distinct target'
[ -x "$self_copy_root/target-script" ] || fail 'runtime script copy did not set executable permissions'

printf 'WuxianPi runtime and runit lifecycle contracts passed\n'
