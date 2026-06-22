require_ubuntu

log "正在通过 service-manager 启动 Hermes WebUI。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/bin:$HOME/.local/node/bin:$HOME/.npm-global/bin:$PATH"

action="start"
svc_name="hermes-webui"
repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$HOME/smallphoneai-repos}"
service_manager_dir="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-$repo_root/service-manager}"
hermes_dir="${SMALLPHONEAI_HERMES_DIR:-$repo_root/hermes}"
bind="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-127.0.0.1:20087}"
sm_url="${SERVICE_MANAGER_URL:-http://$bind}"
log_dir="${SMALLPHONEAI_LOG_DIR:-$HOME/.smallphoneai/logs}"

find_service_manager() {
  if command -v service-manager >/dev/null 2>&1; then
    command -v service-manager
    return 0
  fi
  for candidate in "$service_manager_dir/service-manager" "$service_manager_dir/target/release/service-manager" "$service_manager_dir/target/debug/service-manager"; do
    [ -x "$candidate" ] && { printf "%s\n" "$candidate"; return 0; }
  done
  return 1
}

service_manager_ready() {
  command -v curl >/dev/null 2>&1 && curl -fsS --max-time 2 "$sm_url/api/v1/health" >/dev/null 2>&1
}

service_manager_bin="$(find_service_manager || true)"
[ -n "$service_manager_bin" ] || { echo "找不到 service-manager。" >&2; exit 2; }
export PATH="$(dirname "$service_manager_bin"):$PATH"
mkdir -p "$log_dir"

if ! service_manager_ready; then
  nohup "$service_manager_bin" serve --bind "$bind" > "$log_dir/service-manager.log" 2>&1 < /dev/null &
  for _ in $(seq 1 30); do
    service_manager_ready && break
    sleep 1
  done
fi
service_manager_ready || { echo "service-manager 不可访问：$sm_url" >&2; exit 1; }

if [ -f "$hermes_dir/scripts/register-service.sh" ]; then
  (cd "$hermes_dir" && ./scripts/register-service.sh) || true
fi

token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
[ -n "$token" ] || token="$("$service_manager_bin" token show 2>/dev/null | tr -d "\r\n" || true)"
[ -n "$token" ] || { echo "无法获取 service-manager token。" >&2; exit 1; }

py=""
if command -v python3 >/dev/null 2>&1; then py="python3"; elif command -v python >/dev/null 2>&1; then py="python"; fi
[ -n "$py" ] || { echo "缺少 python，无法解析 service-manager 服务列表。" >&2; exit 1; }

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/hermes-control.XXXXXX")"
trap "rm -rf \"$work_dir\"" EXIT INT HUP TERM
printf "header = \"Authorization: Bearer %s\"\n" "$token" > "$work_dir/curl.cfg"
services_json="$(curl -q -fsS --max-time 5 -K "$work_dir/curl.cfg" "$sm_url/api/v1/services")"
svc_id="$(printf "%s" "$services_json" | "$py" -c '"'"'
import json, sys
name = sys.argv[1]
data = json.loads(sys.stdin.read() or "[]")
for svc in data if isinstance(data, list) else []:
    spec = svc.get("spec") if isinstance(svc, dict) else None
    if isinstance(spec, dict) and spec.get("name") == name:
        sid = svc.get("id")
        if isinstance(sid, str) and sid:
            print(sid)
            break
'"'"' "$svc_name")"
[ -n "$svc_id" ] || svc_id="$svc_name"

curl -q -fsS --max-time 10 -X POST -K "$work_dir/curl.cfg" "$sm_url/api/v1/services/$svc_id/$action" >/dev/null
echo "Hermes WebUI start requested through service-manager: $svc_id"
'
