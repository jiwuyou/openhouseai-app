#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
TARGET="$REPO_ROOT/app/src/main/assets/maintainer/pi-web-rescue.sh"
NODE_BIN="$(command -v node)"
NODE_BIN_DIR="$(dirname "$NODE_BIN")"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

free_port() {
  node -e '
const server = require("net").createServer();
server.listen(0, "127.0.0.1", () => {
  console.log(server.address().port);
  server.close();
});
'
}

run_rescue() {
  local action="$1" port="$2" output="$3"
  set +e
  (
    export OPENHOUSE_PI_WEB_RESCUE_ACTION="$action"
    export OPENHOUSE_PI_WEB_RESCUE_PORT="$port"
    if [ "${RESCUE_USE_DEFAULT_BUNDLE:-0}" = "1" ]; then
      unset OPENHOUSE_PI_WEB_DIR SMALLPHONEAI_PI_WEB_DIR
    else
      export OPENHOUSE_PI_WEB_DIR="$TEST_ROOT/bundle"
    fi
    export OPENHOUSE_PI_WEB_RUNTIME_DIR="$TEST_ROOT/home/runtime"
    export SMALLPHONEAI_OFFLINE_PAYLOAD_DIR="$TEST_ROOT/payloads"
    export HOME="$TEST_ROOT/home"
    export PREFIX="$TEST_ROOT/prefix"
    export TMPDIR="$TEST_ROOT/tmp"
    export TEST_STATE="$TEST_ROOT/state"
    export PATH="$NODE_BIN_DIR:/usr/bin:/bin"
    log() { printf '[test] %s\n' "$*"; }
    source "$TARGET"
  ) > "$output" 2>&1
  local status=$?
  set -e
  return "$status"
}

wait_http() {
  local port="$1"
  for _ in $(seq 1 30); do
    if curl -fsS --max-time 1 "http://127.0.0.1:$port/" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

assert_http_down() {
  local port="$1"
  if curl -fsS --max-time 1 "http://127.0.0.1:$port/" >/dev/null 2>&1; then
    fail "port remains reachable: $port"
  fi
}

work_dir="$(mktemp -d)"
export TEST_ROOT="$work_dir"

cleanup() {
  local port
  for port in "${port_one:-}" "${port_two:-}" "${port_three:-}"; do
    [ -n "$port" ] || continue
    run_rescue stop "$port" "$work_dir/cleanup-$port.log" || true
  done
  if [ -n "${unrelated_pid:-}" ]; then
    kill "$unrelated_pid" >/dev/null 2>&1 || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT INT HUP TERM

mkdir -p \
  "$work_dir/bundle/bin" \
  "$work_dir/bundle/runtime/pi-web/node_modules" \
  "$work_dir/bundle/runtime/pi-web/.next/static" \
  "$work_dir/home" \
  "$work_dir/prefix/bin" \
  "$work_dir/payloads" \
  "$work_dir/state" \
  "$work_dir/tmp"

cat > "$work_dir/bundle/runtime/pi-web/package.json" <<'EOF'
{"name":"pi-web-rescue-test","version":"1.0.0"}
EOF
cat > "$work_dir/bundle/runtime/pi-web/server.js" <<'EOF'
const fs = require("fs");
const http = require("http");
const port = Number(process.env.PORT);
const host = process.env.HOSTNAME;
fs.writeFileSync(`${process.env.TEST_STATE}/env-${port}`, [
  `HOME=${process.env.HOME}`,
  `PREFIX=${process.env.PREFIX}`,
  `PI_CODING_AGENT_DIR=${process.env.PI_CODING_AGENT_DIR}`,
  `OPENHOUSE_PI_WEB_DEFAULT_CWD=${process.env.OPENHOUSE_PI_WEB_DEFAULT_CWD}`,
  `OPENHOUSE_PI_WEB_RUNTIME_DIR=${process.env.OPENHOUSE_PI_WEB_RUNTIME_DIR}`,
  `HOST=${process.env.HOST}`,
  `PI_WEB_HOST=${process.env.PI_WEB_HOST}`,
  `HOSTNAME=${process.env.HOSTNAME}`,
].join("\n") + "\n");
http.createServer((_request, response) => response.end("pi-web rescue test\n")).listen(port, host);
EOF
cat > "$work_dir/bundle/bin/openhouse-pi-web-start" <<'EOF'
#!/usr/bin/env sh
set -eu
cd "$OPENHOUSE_PI_WEB_RUNTIME_DIR"
exec node server.js
EOF
cat > "$work_dir/bundle/bin/pi-web" <<'EOF'
#!/usr/bin/env sh
exec "$HOME/.local/bin/openhouse-pi-web-start" "$@"
EOF
chmod 755 "$work_dir/bundle/bin/openhouse-pi-web-start" "$work_dir/bundle/bin/pi-web"

# The rescue asset itself must not enter Ubuntu/proot or invoke the normal
# service control plane.
if rg -n 'require_ubuntu|run_ubuntu_logged|proot-distro|service-manager[[:space:]]+(start|stop|restart|repair)|registry' "$TARGET"; then
  fail 'Termux-native rescue contains an Ubuntu, service-manager, or registry operation'
fi
grep -Fq 'host="127.0.0.1"' "$TARGET" || fail 'rescue host is not fixed to loopback'
grep -Fq 'OPENHOUSE_PI_WEB_RESCUE_PORT:-__PORT__' "$TARGET" || fail 'runner/default port placeholder is missing'

port_one="$(free_port)"
port_two="$(free_port)"
while [ "$port_two" = "$port_one" ]; do port_two="$(free_port)"; done

run_rescue start "$port_one" "$work_dir/start-one.log" || fail 'first rescue instance failed to start'
wait_http "$port_one" || fail 'first rescue instance is not reachable'

pid_one_file="$work_dir/home/.smallphoneai/rescue/pi-web-$port_one.pid"
marker_one_file="$work_dir/home/.smallphoneai/rescue/pi-web-$port_one.marker"
log_one_file="$work_dir/home/.smallphoneai/logs/pi-web-rescue-$port_one.log"
[ -s "$pid_one_file" ] || fail 'first port PID file is missing'
[ -s "$marker_one_file" ] || fail 'first port marker file is missing'
[ -f "$log_one_file" ] || fail 'first port log file is missing'
pid_one="$(cat "$pid_one_file")"
kill -0 "$pid_one" >/dev/null 2>&1 || fail 'first rescue PID is not alive'
grep -Fq "marker=openhouse-pi-web-rescue:$port_one:" "$marker_one_file" || fail 'first marker does not include its port'

env_one="$work_dir/state/env-$port_one"
[ -s "$env_one" ] || fail 'first rescue environment was not recorded'
grep -Fxq "HOME=$work_dir/home" "$env_one" || fail 'native HOME was not passed'
grep -Fxq "PREFIX=$work_dir/prefix" "$env_one" || fail 'native PREFIX was not passed'
grep -Fxq "PI_CODING_AGENT_DIR=$work_dir/home/.pi" "$env_one" || fail 'native pi config directory was not passed'
grep -Fxq "OPENHOUSE_PI_WEB_DEFAULT_CWD=$work_dir/home" "$env_one" || fail 'native default cwd was not passed'
grep -Fxq "OPENHOUSE_PI_WEB_RUNTIME_DIR=$work_dir/home/runtime" "$env_one" || fail 'native runtime directory was not passed'
grep -Fxq 'HOST=127.0.0.1' "$env_one" || fail 'HOST is not loopback'
grep -Fxq 'PI_WEB_HOST=127.0.0.1' "$env_one" || fail 'PI_WEB_HOST is not loopback'
grep -Fxq 'HOSTNAME=127.0.0.1' "$env_one" || fail 'HOSTNAME is not loopback'

# A second custom port has independent PID/log/marker ownership and shares only
# the immutable installed runtime.
run_rescue start "$port_two" "$work_dir/start-two.log" || fail 'second rescue instance failed to start'
wait_http "$port_two" || fail 'second rescue instance is not reachable'
pid_two_file="$work_dir/home/.smallphoneai/rescue/pi-web-$port_two.pid"
marker_two_file="$work_dir/home/.smallphoneai/rescue/pi-web-$port_two.marker"
log_two_file="$work_dir/home/.smallphoneai/logs/pi-web-rescue-$port_two.log"
[ -s "$pid_two_file" ] || fail 'second port PID file is missing'
[ -s "$marker_two_file" ] || fail 'second port marker file is missing'
[ -f "$log_two_file" ] || fail 'second port log file is missing'
pid_two="$(cat "$pid_two_file")"
[ "$pid_one" != "$pid_two" ] || fail 'custom ports reused the same PID'

run_rescue stop "$port_one" "$work_dir/stop-one.log" || fail 'first rescue instance failed to stop'
assert_http_down "$port_one"
wait_http "$port_two" || fail 'stopping first port also stopped second port'
kill -0 "$pid_two" >/dev/null 2>&1 || fail 'second port PID died when first port stopped'
[ ! -e "$pid_one_file" ] || fail 'first PID file remains after stop'
[ ! -e "$marker_one_file" ] || fail 'first marker file remains after stop'

# A stale/malicious PID file and an occupied non-HTTP port must never cause the
# rescue action to kill an unrelated process.
occupied_port="$(free_port)"
node -e '
require("net").createServer(() => {}).listen(Number(process.argv[1]), "127.0.0.1");
' "$occupied_port" &
unrelated_pid=$!
sleep 0.2
printf '%s\n' "$unrelated_pid" > "$work_dir/home/.smallphoneai/rescue/pi-web-$occupied_port.pid"
if run_rescue start "$occupied_port" "$work_dir/occupied.log"; then
  fail 'rescue started on an occupied non-HTTP port'
fi
kill -0 "$unrelated_pid" >/dev/null 2>&1 || fail 'rescue killed the unrelated port owner'
grep -Fq '不会启动或停止该进程' "$work_dir/occupied.log" || fail 'occupied port safety diagnostic is missing'

if run_rescue start 1023 "$work_dir/invalid-port.log"; then
  fail 'rescue accepted a privileged/invalid port'
fi
grep -Fq '允许 1024-65535' "$work_dir/invalid-port.log" || fail 'invalid port diagnostic is missing'

run_rescue stop "$port_two" "$work_dir/stop-two.log" || fail 'second rescue instance failed to stop'
assert_http_down "$port_two"

# The default APK payload path is stamped. A changed synchronized payload must
# replace a previously installed native runtime instead of silently retaining
# stale pi-web code across APK updates.
port_three="$(free_port)"
(cd "$work_dir/bundle" && tar -cf "$work_dir/payloads/pi-web.tar" .)
RESCUE_USE_DEFAULT_BUNDLE=1 run_rescue start "$port_three" "$work_dir/payload-start.log" \
  || fail 'rescue failed to install from the default APK payload path'
wait_http "$port_three" || fail 'payload-installed rescue is not reachable'
bundle_stamp_file="$work_dir/home/.local/share/openhouseai/pi-web-bundle/.openhouse-payload.cksum"
runtime_stamp_file="$work_dir/home/runtime/.openhouse-payload.cksum"
[ -s "$bundle_stamp_file" ] || fail 'payload bundle stamp is missing'
[ -s "$runtime_stamp_file" ] || fail 'payload runtime stamp is missing'
[ "$(cat "$bundle_stamp_file")" = "$(cat "$runtime_stamp_file")" ] || fail 'payload stamps disagree'
run_rescue stop "$port_three" "$work_dir/payload-stop.log" || fail 'payload-installed rescue failed to stop'

printf '\n// refreshed payload\n' >> "$work_dir/bundle/runtime/pi-web/server.js"
rm -f "$work_dir/payloads/pi-web.tar"
(cd "$work_dir/bundle" && tar -cf "$work_dir/payloads/pi-web.tar" .)
old_stamp="$(cat "$runtime_stamp_file")"
RESCUE_USE_DEFAULT_BUNDLE=1 run_rescue start "$port_three" "$work_dir/payload-refresh.log" \
  || fail 'rescue failed to refresh a changed APK payload'
wait_http "$port_three" || fail 'payload-refreshed rescue is not reachable'
new_stamp="$(cat "$runtime_stamp_file")"
[ "$new_stamp" != "$old_stamp" ] || fail 'changed APK payload did not update the runtime stamp'
grep -Fq '// refreshed payload' "$work_dir/home/runtime/server.js" || fail 'changed APK payload did not replace runtime code'
run_rescue stop "$port_three" "$work_dir/payload-refresh-stop.log" || fail 'refreshed rescue failed to stop'

printf 'pi-web Termux-native rescue focused tests passed\n'
