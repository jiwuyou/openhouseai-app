set -euo pipefail

if ! declare -F log >/dev/null 2>&1; then
  log() {
    printf '[SmallPhoneAI control-plane] %s\n' "$*"
  }
fi

if ! declare -F warn >/dev/null 2>&1; then
  warn() {
    printf '[SmallPhoneAI control-plane] WARN: %s\n' "$*" >&2
  }
fi

log "正在轻量修复控制中枢：优先恢复 Termux native service-manager 与 OpenHouse 专用 token。"

if ! command -v is_termux >/dev/null 2>&1; then
  is_termux() {
    [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
  }
fi

if ! command -v is_current_ubuntu >/dev/null 2>&1; then
  is_current_ubuntu() {
    [ -r /etc/os-release ] && grep -qi 'ubuntu' /etc/os-release
  }
fi

openhouse_tmp_parent() {
  local dir="${TMPDIR:-}"
  while [ -n "$dir" ] && [ "$dir" != "/" ] && [ "${dir%/}" != "$dir" ]; do
    dir="${dir%/}"
  done
  if [ -z "$dir" ] || [ "$dir" = "/""tmp" ]; then
    if [ -n "${PREFIX:-}" ]; then
      dir="$PREFIX/tmp"
    else
      dir="${HOME:-.}/.tmp"
    fi
  fi
  mkdir -p "$dir" || {
    warn "无法创建临时目录：$dir"
    return 1
  }
  printf '%s\n' "$dir"
}

openhouse_mktemp_dir() {
  local template="$1"
  local parent
  parent="$(openhouse_tmp_parent)" || return 1
  mktemp -d "$parent/$template"
}

read_openhouse_service_manager_endpoint() {
  local config key value
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json"; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    for key in listen_addr listenAddr base_url baseUrl baseURL url; do
      value="$(sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$config" | head -n 1 || true)"
      if [ -n "$value" ]; then
        printf '%s\n' "$value"
        return 0
      fi
    done
  done
  return 1
}

normalize_service_manager_bind() {
  local value="${1:-}"
  case "$value" in
    http://*) value="${value#http://}" ;;
    https://*) value="${value#https://}" ;;
  esac
  value="${value%%/*}"
  case "$value" in
    "") return 1 ;;
    :*) printf '127.0.0.1%s\n' "$value"; return 0 ;;
    0.0.0.0) printf '127.0.0.1\n'; return 0 ;;
    0.0.0.0:*) printf '127.0.0.1:%s\n' "${value#0.0.0.0:}"; return 0 ;;
    "::"|"[::]") printf '127.0.0.1\n'; return 0 ;;
    "[::]:"*) printf '127.0.0.1:%s\n' "${value#"[::]:"}"; return 0 ;;
    :::*) printf '127.0.0.1:%s\n' "${value#:::}"; return 0 ;;
    *[!0-9]*) printf '%s\n' "$value"; return 0 ;;
    *) printf '127.0.0.1:%s\n' "$value"; return 0 ;;
  esac
}

configured_service_manager_bind() {
  local endpoint
  endpoint="$(read_openhouse_service_manager_endpoint || true)"
  if [ -n "$endpoint" ] && normalize_service_manager_bind "$endpoint"; then
    return
  fi
  if [ -n "${SERVICE_MANAGER_URL:-}" ] && normalize_service_manager_bind "$SERVICE_MANAGER_URL"; then
    return
  fi
  if [ -n "${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}" ]; then
    normalize_service_manager_bind "$SMALLPHONEAI_SERVICE_MANAGER_BIND"
    return
  fi
  printf '127.0.0.1:20087\n'
}

configured_service_manager_url() {
  local endpoint scheme bind
  endpoint="$(read_openhouse_service_manager_endpoint || true)"
  if [ -z "$endpoint" ]; then
    endpoint="${SERVICE_MANAGER_URL:-}"
  fi
  if [ -z "$endpoint" ] && [ -n "${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}" ]; then
    endpoint="$SMALLPHONEAI_SERVICE_MANAGER_BIND"
  fi
  case "$endpoint" in
    https://*) scheme="https" ;;
    *) scheme="http" ;;
  esac
  bind="$(normalize_service_manager_bind "${endpoint:-$(configured_service_manager_bind)}")" || bind="127.0.0.1:20087"
  printf '%s://%s\n' "$scheme" "$bind"
}

read_config_token() {
  local config token
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "$HOME/.config/openhouseai/service-manager/config.json" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json"; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    token="$(sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$config" | head -n 1 || true)"
    if [ -n "$token" ]; then
      printf '%s\n' "$token"
      return 0
    fi
  done
  return 1
}

termux_service_manager_config() {
  printf '%s\n' "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-$HOME/.config/openhouseai/service-manager/config.json}"
}

termux_service_manager_log() {
  printf '%s\n' "${SMALLPHONEAI_TERMUX_LOG_DIR:-$HOME/.smallphoneai/logs}/service-manager.log"
}

service_manager_is_current() {
  local binary="$1"
  [ -x "$binary" ] || return 1
  [ "$("$binary" --version 2>/dev/null | tr -d '\r\n')" = "service-manager 0.3.4" ]
}

find_termux_service_manager() {
  local candidate
  for candidate in \
    "$(command -v service-manager 2>/dev/null || true)" \
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/service-manager" \
    "$HOME/.local/bin/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/release/service-manager"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] || continue
    if service_manager_is_current "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

prepare_termux_service_manager_repo() {
  local repo="$HOME/smallphoneai-repos/service-manager"
  local payload_root="${SMALLPHONEAI_OFFLINE_PAYLOAD_DIR:-${SMALLPHONEAI_BUNDLED_PAYLOAD_ROOT:-$HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads}}"
  local archive="$payload_root/service-manager.tgz"
  local work_dir payload_dir

  if [ -f "$repo/scripts/install.sh" ] && service_manager_is_current "$repo/service-manager"; then
    return 0
  fi
  [ -f "$archive" ] || return 1

  work_dir="$(openhouse_mktemp_dir "openhouse-sm-payload.XXXXXX")" || return 1
  if ! tar -xf "$archive" -C "$work_dir"; then
    rm -rf "$work_dir" >/dev/null 2>&1 || true
    return 1
  fi
  if [ -f "$work_dir/scripts/install.sh" ]; then
    payload_dir="$work_dir"
  else
    payload_dir="$(find "$work_dir" -mindepth 2 -maxdepth 3 -path '*/scripts/install.sh' -type f -print | sed 's#/scripts/install\.sh$##' | head -n 1)"
  fi
  if [ -z "$payload_dir" ] || [ ! -d "$payload_dir" ]; then
    rm -rf "$work_dir" >/dev/null 2>&1 || true
    return 1
  fi
  mkdir -p "$repo"
  cp -a "$payload_dir/." "$repo/"
  rm -rf "$work_dir" >/dev/null 2>&1 || true
}

install_termux_service_manager() {
  local repo="$HOME/smallphoneai-repos/service-manager"
  local bind config mode

  find_termux_service_manager >/dev/null 2>&1 && return 0
  prepare_termux_service_manager_repo || return 1
  [ -f "$repo/scripts/install.sh" ] || return 1

  bind="$(configured_service_manager_bind)"
  config="$(termux_service_manager_config)"
  mode="${SMALLPHONEAI_TERMUX_SERVICE_MANAGER_INSTALL_MODE:-local}"
  log "正在安装 Termux native service-manager：mode=$mode"
  (
    cd "$repo"
    BIND="$bind" CONFIG_PATH="$config" SERVICE_MANAGER_INSTALL_MODE="$mode" INSTALL_SERVICE=0 bash ./scripts/install.sh
  ) || return 1

  find_termux_service_manager >/dev/null 2>&1
}

service_manager_ready() {
  local sm_url
  sm_url="$(configured_service_manager_url)"
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 2 "$sm_url/api/v1/health" >/dev/null 2>&1
}

termux_service_manager_serve_pids() {
  local proc comm args
  for proc in /proc/[0-9]*; do
    [ -r "$proc/comm" ] && [ -r "$proc/cmdline" ] || continue
    comm="$(cat "$proc/comm" 2>/dev/null || true)"
    [ "$comm" = "service-manager" ] || continue
    args="$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null || true)"
    printf '%s\n' "$args" | grep -Fqx -- "serve" || continue
    printf '%s\n' "${proc##*/}"
  done
}

service_manager_instance_matches_openhouse() {
  local config bind sm_bin expected_exe pid args actual_exe total=0 matched=0
  config="$(termux_service_manager_config)"
  bind="$(configured_service_manager_bind)"
  sm_bin="$(find_termux_service_manager || true)"
  [ -n "$sm_bin" ] || return 1
  expected_exe="$(readlink -f "$sm_bin" 2>/dev/null || true)"
  [ -n "$expected_exe" ] || return 1
  for pid in $(termux_service_manager_serve_pids); do
    total=$((total + 1))
    args="$(tr '\000' '\n' < "/proc/$pid/cmdline" 2>/dev/null || true)"
    actual_exe="$(readlink "/proc/$pid/exe" 2>/dev/null || true)"
    if [ "$actual_exe" = "$expected_exe" ] \
      && printf '%s\n' "$args" | grep -Fqx -- "--config" \
      && printf '%s\n' "$args" | grep -Fqx -- "$config" \
      && printf '%s\n' "$args" | grep -Fqx -- "--bind" \
      && printf '%s\n' "$args" | grep -Fqx -- "$bind"; then
      matched=$((matched + 1))
    fi
  done
  [ "$total" -eq 1 ] && [ "$matched" -eq 1 ]
}

stop_termux_service_manager_instances() {
  local pid pids service_root
  service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  if command -v sv >/dev/null 2>&1; then
    env SVDIR="$service_root" sv down service-manager >/dev/null 2>&1 || true
  fi
  pids="$(termux_service_manager_serve_pids)"
  for pid in $pids; do
    kill "$pid" >/dev/null 2>&1 || true
  done
  for _ in $(seq 1 10); do
    [ -z "$(termux_service_manager_serve_pids)" ] && return 0
    sleep 1
  done
  for pid in $(termux_service_manager_serve_pids); do
    kill -9 "$pid" >/dev/null 2>&1 || true
  done
  sleep 1
  [ -z "$(termux_service_manager_serve_pids)" ]
}

termux_runsvdir_active() {
  local service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  local proc comm args
  for proc in /proc/[0-9]*; do
    [ -r "$proc/comm" ] && [ -r "$proc/cmdline" ] || continue
    comm="$(cat "$proc/comm" 2>/dev/null || true)"
    [ "$comm" = runsvdir ] || continue
    args="$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null || true)"
    printf '%s\n' "$args" | grep -Fqx -- "$service_root" && return 0
  done
  return 1
}

ensure_termux_services_daemon() {
  local service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  command -v service-daemon >/dev/null 2>&1 || return 1
  command -v sv >/dev/null 2>&1 || return 1
  [ -d "$service_root" ] || return 1
  service-daemon start >/dev/null 2>&1 || true
  for _ in $(seq 1 10); do
    termux_runsvdir_active && return 0
    sleep 1
  done
  return 1
}

service_manager_runit_ready() {
  local service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  local status
  termux_runsvdir_active || return 1
  [ -x "$service_root/service-manager/run" ] || return 1
  status="$(env SVDIR="$service_root" sv status service-manager 2>/dev/null || true)"
  case "$status" in run:*) return 0 ;; *) return 1 ;; esac
}

service_manager_auth_ready() {
  local token="$1"
  local sm_url work_dir curl_cfg status
  [ -n "$token" ] || return 1
  command -v curl >/dev/null 2>&1 || return 1
  sm_url="$(configured_service_manager_url)"
  work_dir="$(openhouse_mktemp_dir "openhouse-sm-auth.XXXXXX")" || return 1
  curl_cfg="$work_dir/curl.cfg"
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$curl_cfg"
  curl -q -fsS --max-time 3 -K "$curl_cfg" "$sm_url/api/v1/services" >/dev/null 2>&1
  status=$?
  rm -rf "$work_dir" >/dev/null 2>&1 || true
  return "$status"
}

migrate_legacy_service_manager_specs() {
  local token="$1"
  local sm_url backup_dir
  [ -n "$token" ] || return 0
  command -v curl >/dev/null 2>&1 || return 0
  command -v python3 >/dev/null 2>&1 || {
    warn "缺少 python3，跳过旧 service-manager 声明迁移。"
    return 0
  }

  sm_url="$(configured_service_manager_url)"
  backup_dir="${SMALLPHONEAI_SERVICE_MANAGER_REPAIR_BACKUP_DIR:-$HOME/openhouse-backups/service-manager-repair-$(date +%Y%m%d-%H%M%S)}"
  mkdir -p "$backup_dir" || {
    warn "无法创建 service-manager 修复备份目录：$backup_dir"
    return 0
  }

  SERVICE_MANAGER_URL="$sm_url" \
  SERVICE_MANAGER_TOKEN="$token" \
  SERVICE_MANAGER_REPAIR_BACKUP_DIR="$backup_dir" \
  python3 <<'PY'
import copy
import json
import os
import re
import secrets
import shlex
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

base = os.environ["SERVICE_MANAGER_URL"].rstrip("/")
token = os.environ["SERVICE_MANAGER_TOKEN"]
backup_dir = os.environ["SERVICE_MANAGER_REPAIR_BACKUP_DIR"]
headers = {"Authorization": f"Bearer {token}"}


def log(message):
    print(f"[SmallPhoneAI control-plane] {message}", flush=True)


def request(method, path, payload=None, timeout=12):
    data = None
    req_headers = dict(headers)
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        req_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(f"{base}{path}", data=data, headers=req_headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read()
        if not body:
            return None
        return json.loads(body)


def post_no_body(path):
    req = urllib.request.Request(f"{base}{path}", headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=20) as resp:
        resp.read()


def is_legacy_ubuntu_wrapper(spec):
    runtime = spec.get("runtime") or {}
    return spec.get("provider") == "process" and runtime.get("strategy") == "termux-process-wrapper-proot-distro"


def is_legacy_pi_web(spec):
    return spec.get("name") == "pi-web" and spec.get("provider") == "process"


def active_services_from_export(snapshot):
    return {
        service_id: service
        for service_id, service in (snapshot.get("services") or {}).items()
        if service.get("deleted_at") is None
    }


def parse_guest_exports(spec):
    command = spec.get("command") or []
    if len(command) < 3:
        return {}
    script = command[2]
    marker = "proot-distro login ubuntu -- sh -lc '"
    if marker in script:
        script = script.split(marker, 1)[1].split("' & child=", 1)[0]
    out = {}
    for match in re.finditer(r"(?:^|; )export ([A-Za-z_][A-Za-z0-9_]*)=([^;]*)", script):
        key = match.group(1)
        value = match.group(2).strip()
        if (value.startswith("'") and value.endswith("'")) or (value.startswith('"') and value.endswith('"')):
            value = value[1:-1]
        out[key] = value
    return out


def shell_export_command(env):
    parts = ["set -eu"]
    for key in sorted(env):
        if re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", key):
            parts.append(f"export {key}={shlex.quote(str(env[key]))}")
    parts.append('exec "$@"')
    return "; ".join(parts)


def migrate_service(service):
    spec = service["spec"]
    runtime = spec.setdefault("runtime", {})
    if is_legacy_ubuntu_wrapper(spec):
        guest_command = runtime.get("guest_command") or []
        guest_working_dir = runtime.get("guest_working_dir") or runtime.get("home") or "/root"
        if not guest_command:
            raise RuntimeError(f"{spec.get('name')}: missing runtime.guest_command")
        guest_env = parse_guest_exports(spec)
        old_env = spec.get("env") or {}
        spec["provider"] = "proot-distro"
        spec["working_dir"] = guest_working_dir
        spec["command"] = ["sh", "-lc", shell_export_command(guest_env), "service-manager-guest"] + list(guest_command)
        spec["env"] = {key: value for key, value in old_env.items() if key in ("HOME", "PATH", "PREFIX", "LD_LIBRARY_PATH")}
        runtime["strategy"] = "proot-distro"
        runtime.setdefault("distro", "ubuntu")
        runtime.setdefault("home", "/root")
        runtime["guest_command"] = guest_command
        runtime["guest_working_dir"] = guest_working_dir
        return True
    if is_legacy_pi_web(spec):
        spec["provider"] = "termux-process"
        spec["command"] = [
            "sh",
            "-lc",
            "node node_modules/next/dist/bin/next start -p 30141 -H 127.0.0.1 & child=$!; "
            "trap 'kill -TERM $child 2>/dev/null; wait $child 2>/dev/null || true' TERM INT HUP; "
            "wait $child",
        ]
        runtime["strategy"] = "termux-process"
        runtime.setdefault("runtime", "termux")
        return True
    return False


def wait_state(service_id, wanted, attempts=50):
    last = None
    for _ in range(attempts):
        last = request("GET", f"/api/v1/services/{service_id}/status")
        if (last or {}).get("state") == wanted:
            return last
        time.sleep(0.4)
    raise RuntimeError(f"{service_id}: wanted {wanted}, got {last}")


def main():
    providers = request("GET", "/api/v1/providers") or []
    detected = {p.get("id") for p in providers if p.get("detected")}
    if not {"proot-distro", "termux-process"}.issubset(detected):
        log("新版 provider 未全部可用，跳过旧服务声明迁移。")
        return 0

    services = request("GET", "/api/v1/services") or []
    legacy_ids = [
        service["id"]
        for service in services
        if is_legacy_ubuntu_wrapper(service.get("spec") or {}) or is_legacy_pi_web(service.get("spec") or {})
    ]
    if not legacy_ids:
        log("未发现旧 process wrapper 服务声明，无需迁移。")
        return 0

    snapshot = request("GET", "/api/v1/export")
    with open(os.path.join(backup_dir, "export-before-provider-migration.json"), "w", encoding="utf-8") as f:
        json.dump(snapshot, f, ensure_ascii=False, indent=2)

    statuses = {}
    for service_id in legacy_ids:
        try:
            statuses[service_id] = request("GET", f"/api/v1/services/{service_id}/status")
        except Exception as exc:
            statuses[service_id] = {"state": "unknown", "message": str(exc)}
    with open(os.path.join(backup_dir, "status-before-provider-migration.json"), "w", encoding="utf-8") as f:
        json.dump(statuses, f, ensure_ascii=False, indent=2)

    migrated = copy.deepcopy(snapshot)
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    changed = []
    for service_id, service in active_services_from_export(migrated).items():
        if migrate_service(service):
            service["updated_at"] = now
            changed.append(service_id)
        spec = service.get("spec") or {}
        tags = spec.get("tags") or []
        name = spec.get("name") or ""
        if name.startswith("smoke-") or any(str(tag).startswith("smoke") for tag in tags):
            service["deleted_at"] = now
            service["updated_at"] = now

    if not changed:
        log("旧服务声明检查完成，无需迁移。")
        return 0

    with open(os.path.join(backup_dir, "export-migrated-provider-specs.json"), "w", encoding="utf-8") as f:
        json.dump(migrated, f, ensure_ascii=False, indent=2)

    running_ids = [service_id for service_id in changed if (statuses.get(service_id) or {}).get("state") == "running"]
    for service_id in running_ids:
        log(f"停止旧声明服务：{service_id}")
        post_no_body(f"/api/v1/services/{service_id}/stop")
        wait_state(service_id, "stopped")

    log(f"导入 provider 迁移后的服务声明：{len(changed)} 个")
    req = urllib.request.Request(
        f"{base}/api/v1/import",
        data=json.dumps(migrated, ensure_ascii=False).encode("utf-8"),
        headers={**headers, "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        resp.read()

    failed = []
    for service_id in running_ids:
        log(f"恢复启动迁移服务：{service_id}")
        try:
            post_no_body(f"/api/v1/services/{service_id}/start")
            wait_state(service_id, "running")
        except Exception as exc:
            failed.append({"id": service_id, "error": str(exc)})

    final_statuses = {}
    for service_id in changed:
        try:
            final_statuses[service_id] = request("GET", f"/api/v1/services/{service_id}/status")
        except Exception as exc:
            final_statuses[service_id] = {"state": "unknown", "message": str(exc)}
    with open(os.path.join(backup_dir, "status-after-provider-migration.json"), "w", encoding="utf-8") as f:
        json.dump(final_statuses, f, ensure_ascii=False, indent=2)

    if failed:
        log("部分迁移服务启动失败，请查看备份目录中的 status-after-provider-migration.json。")
        return 1
    log(f"旧服务声明迁移完成，备份目录：{backup_dir}")
    return 0


try:
    sys.exit(main())
except urllib.error.HTTPError as exc:
    body = exc.read().decode("utf-8", "replace")
    log(f"旧服务声明迁移跳过/失败：HTTP {exc.code} {body[:300]}")
    sys.exit(1)
except Exception as exc:
    log(f"旧服务声明迁移跳过/失败：{exc}")
    sys.exit(1)
PY
}

openhouse_short_hash() {
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$1" | sha256sum | awk '{print substr($1, 1, 12)}'
    return 0
  fi
  if command -v cksum >/dev/null 2>&1; then
    printf '%s' "$1" | cksum | awk '{print $1}'
    return 0
  fi
  printf '%s' "$1" | tr -cd 'A-Za-z0-9' | cut -c 1-12
}

unique_backup_path() {
  local backup_dir="$1"
  local source_dir="$2"
  local source_file="$3"
  local hash
  local base
  local stem
  local ext
  local candidate
  local suffix=1

  hash="$(openhouse_short_hash "$source_dir")"
  [ -n "$hash" ] || hash="unknown"
  base="${hash}-$(basename "$source_file")"
  stem="$base"
  ext=""
  case "$base" in
    *.*)
      stem="${base%.*}"
      ext=".${base##*.}"
      ;;
  esac
  candidate="$backup_dir/$base"
  while [ -e "$candidate" ]; do
    candidate="$backup_dir/$stem.$suffix$ext"
    suffix=$((suffix + 1))
  done
  printf '%s\n' "$candidate"
}

quarantine_empty_service_specs() {
  local dir
  local file
  local backup_dir
  local dest
  local moved=0

  for dir in \
    "$HOME/.config/openhouseai/service-manager/services.d" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/services.d}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/services.d"; do
    [ -n "$dir" ] && [ -d "$dir" ] || continue
    while IFS= read -r file; do
      [ -n "$file" ] || continue
      backup_dir="$HOME/openhouse-backups/empty-service-specs-$(date +%Y%m%d-%H%M%S)"
      mkdir -p "$backup_dir" || {
        warn "无法创建 0 字节 service spec 备份目录：$backup_dir"
        continue
      }
      dest="$(unique_backup_path "$backup_dir" "$dir" "$file")"
      if mv "$file" "$dest"; then
        moved=$((moved + 1))
        warn "已隔离 0 字节 service spec：source_dir=$dir file=$file -> $dest"
      else
        warn "无法隔离 0 字节 service spec：$file"
      fi
    done <<EOF
$(find "$dir" -maxdepth 1 -type f -name '*.json' -size 0 -print 2>/dev/null)
EOF
  done

  if [ "$moved" -gt 0 ]; then
    log "已隔离 $moved 个 0 字节 service spec；后续组件修复会重写 OpenHouse 管理的声明。"
  fi
}

repair_termux_native_control_plane() {
  local bind sm_url sm_bin config log_file bootstrap_log token service_root
  local persistent=0

  is_termux || {
    log "当前不是 Termux；拒绝在 Ubuntu/proot 内拉起长期 service-manager。"
    return 2
  }

  bind="$(configured_service_manager_bind)"
  sm_url="$(configured_service_manager_url)"
  config="$(termux_service_manager_config)"
  log_file="$(termux_service_manager_log)"
  bootstrap_log="$(dirname "$log_file")/service-manager-bootstrap.log"
  service_root="${PREFIX:-/data/data/com.termux/files/usr}/var/service"
  state_dir="$HOME/.smallphoneai/state"

  quarantine_empty_service_specs

  sm_bin="$(find_termux_service_manager || true)"
  if [ -z "$sm_bin" ]; then
    install_termux_service_manager || true
    sm_bin="$(find_termux_service_manager || true)"
  fi
  if [ -z "$sm_bin" ]; then
    log "未找到可执行的 Termux native service-manager。请先安装 bionic/Termux 版本，当前不会回退到 Ubuntu/proot 长跑控制面。"
    return 2
  fi

  mkdir -p "$(dirname "$config")" "$(dirname "$log_file")"
  umask 077
  : > "$bootstrap_log"
  chmod 600 "$bootstrap_log" >/dev/null 2>&1 || true
  if [ -n "$(termux_service_manager_serve_pids)" ] || service_manager_ready; then
    log "正在停止旧 service-manager，并统一切换到 OpenHouse 专用 config。"
    stop_termux_service_manager_instances || return 1
  fi

  if ensure_termux_services_daemon; then
    log "正在通过 termux-services 修复并启动 service-manager：$bind"
    if "$sm_bin" install-service --config "$config" --bind "$bind" --log-file "$log_file" \
      && [ -x "$service_root/service-manager/run" ] \
      && env SVDIR="$service_root" sv up service-manager; then
      for _ in $(seq 1 30); do
        if service_manager_ready \
          && service_manager_instance_matches_openhouse \
          && service_manager_runit_ready; then
          persistent=1
          break
        fi
        sleep 1
      done
    else
      warn "service-manager install-service 或 sv up 失败；不会启动脱离 runit 的临时进程。"
    fi
  else
    warn "termux-services/runsvdir 不可用；不会启动脱离 runit 的临时进程。"
  fi

  if [ "$persistent" != "1" ]; then
    env SVDIR="$service_root" sv down service-manager >/dev/null 2>&1 || true
    stop_termux_service_manager_instances || return 1
    log "Termux native service-manager 未获得 runit 常驻，修复失败。"
    return 2
  fi

  if ! service_manager_ready || ! service_manager_instance_matches_openhouse; then
    log "Termux native service-manager health 检查失败：$sm_url/api/v1/health"
    [ -f "$log_file" ] && tail -n 80 "$log_file" | while IFS= read -r line; do log "$line"; done
    [ -f "$bootstrap_log" ] && tail -n 80 "$bootstrap_log" | while IFS= read -r line; do log "$line"; done
    return 1
  fi

  token="$(read_config_token || true)"
  [ -n "$token" ] || token="$("$sm_bin" token show --config "$config" 2>/dev/null | tr -d '\r\n' || true)"
  [ -n "$token" ] || token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if ! service_manager_auth_ready "$token"; then
    log "Termux native service-manager 已启动，但 token 未通过 /api/v1/services 验证。"
    return 1
  fi

  migrate_legacy_service_manager_specs "$token" || warn "旧服务声明迁移未完全成功；控制中枢本体仍已恢复。"

  log "控制中枢 runit 常驻修复完成：Termux native service-manager=$sm_url"
}

if is_current_ubuntu; then
  warn "控制中枢只允许在 Termux native 层修复；Ubuntu/proot 只是被管理运行时。请从 Android 运行控制或 Termux 原生 shell 执行修复。"
  exit 2
fi

repair_termux_native_control_plane
exit $?
