#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

die() {
  warn "$*"
  exit 1
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

service_manager_config_candidates() {
  local config
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH:-}" \
    "${SERVICE_MANAGER_CONFIG_PATH:-}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "${OPENHOUSEAI_TERMUX_HOME:+$OPENHOUSEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}"; do
    [ -n "$config" ] || continue
    printf '%s\n' "$config"
  done
}

configured_service_manager_config_path() {
  local config fallback=""
  while IFS= read -r config; do
    [ -n "$fallback" ] || fallback="$config"
    if [ -f "$config" ]; then
      printf '%s\n' "$config"
      return 0
    fi
  done <<EOF
$(service_manager_config_candidates)
EOF
  [ -n "$fallback" ] || return 1
  printf '%s\n' "$fallback"
}

read_openhouse_service_manager_endpoint() {
  local config key value
  while IFS= read -r config; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    for key in listen_addr listenAddr base_url baseUrl baseURL url; do
      value="$(sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$config" | head -n 1 || true)"
      if [ -n "$value" ]; then
        printf '%s\n' "$value"
        return 0
      fi
    done
  done <<EOF
$(service_manager_config_candidates)
EOF
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

find_python() {
  if command -v python3 >/dev/null 2>&1; then
    command -v python3
    return 0
  fi
  if command -v python >/dev/null 2>&1; then
    command -v python
    return 0
  fi
  return 1
}

require_python() {
  local py
  py="$(find_python || true)"
  [ -n "$py" ] || die "找不到 Python，无法校验 OpenHouseAI registry JSON。"
  printf '%s\n' "$py"
}

validate_component_manifests() {
  local source_dir="$1"
  local label="$2"
  local py
  [ -d "$source_dir" ] || return 0
  py="$(require_python)"
  "$py" - "$source_dir" "$label" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
label = sys.argv[2]
forbidden = {"command", "shell", "script", "args"}

def walk(value, path):
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}" if path else key
            if key.lower() in forbidden:
                raise SystemExit(
                    f"{label}: component manifest must not contain executable key {child_path!r}; "
                    "this rule only applies to components.d/*.json, not bootstrap manifests or service-manager ServiceSpec"
                )
            walk(child, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            walk(child, f"{path}[{index}]")

for path in sorted(root.glob("*.json")):
    with path.open("r", encoding="utf-8") as handle:
        doc = json.load(handle)
    missing = [key for key in ("shellMenu", "smallphoneApp", "serviceManager", "ai") if key not in doc]
    if missing:
        raise SystemExit(f"{label}: {path.name} missing component manifest layers: {', '.join(missing)}")
    walk(doc, "$")
PY
}

validate_json_dir() {
  local source_dir="$1"
  local label="$2"
  local py
  [ -d "$source_dir" ] || return 0
  py="$(require_python)"
  "$py" - "$source_dir" "$label" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
label = sys.argv[2]
for path in sorted(root.glob("*.json")):
    with path.open("r", encoding="utf-8") as handle:
        json.load(handle)
PY
}

write_registry_state() {
  local state_path="$1"
  local scan_root="$2"
  local status="$3"
  local mode="$4"
  local source_path="$5"
  local target_path="$6"
  local message="${7:-}"
  local py
  py="$(require_python)"
  mkdir -p "$(dirname "$state_path")"
  "$py" - "$state_path" "$scan_root" "$status" "$mode" "$source_path" "$target_path" "$message" <<'PY'
import datetime
import hashlib
import json
import pathlib
import sys

state_path = pathlib.Path(sys.argv[1])
scan_root = pathlib.Path(sys.argv[2])
status = sys.argv[3]
mode = sys.argv[4]
source_path = sys.argv[5]
target_path = sys.argv[6]
message = sys.argv[7]

files = []
if scan_root.exists():
    for path in sorted(p for p in scan_root.rglob("*") if p.is_file()):
        if path.name == "registry-state.json":
            continue
        rel = path.relative_to(scan_root).as_posix()
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        files.append({"path": rel, "sha256": digest, "size": path.stat().st_size})

doc = {
    "version": 1,
    "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
    "mode": mode,
    "status": status,
    "sourcePath": source_path,
    "targetPath": target_path,
    "files": files,
    "errors": [message] if message else [],
}
tmp_path = state_path.with_name(f".{state_path.name}.tmp")
with tmp_path.open("w", encoding="utf-8") as handle:
    json.dump(doc, handle, ensure_ascii=False, indent=2, sort_keys=True)
    handle.write("\n")
tmp_path.replace(state_path)
PY
}

record_failed_state() {
  local message="$1"
  if [ ! -d "$termux_home" ]; then
    warn "无法写 registry-state.json，Termux home 不存在：$termux_home"
    return 0
  fi
  mkdir -p "$termux_config_dir" 2>/dev/null || true
  if [ -d "$termux_config_dir" ]; then
    write_registry_state "$termux_config_dir/registry-state.json" "$termux_config_dir" "failed" "bootstrap-fallback" "$config_dir" "$termux_config_dir" "$message" || true
  fi
}

find_service_manager_binary() {
  local candidate
  if command -v service-manager >/dev/null 2>&1; then
    command -v service-manager
    return 0
  fi
  for candidate in \
    "$HOME/.local/bin/service-manager" \
    "$service_manager_dir/service-manager" \
    "$service_manager_dir/target/release/service-manager" \
    "$service_manager_dir/target/debug/service-manager"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

resolve_service_manager_token() {
  local token=""
  local sm_bin config
  config="$(configured_service_manager_config_path || true)"
  if [ -n "$config" ] && [ -f "$config" ]; then
    token="$(sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$config" | head -n 1 || true)"
  fi
  if [ -z "$token" ]; then
    sm_bin="$(find_service_manager_binary || true)"
    if [ -n "$sm_bin" ] && [ -n "$config" ]; then
      token="$("$sm_bin" token show --config "$config" 2>/dev/null | head -n 1 | tr -d '\r\n' || true)"
    fi
  fi
  [ -n "$token" ] || token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  printf '%s\n' "$token"
}

service_manager_health_ready() {
  command -v curl >/dev/null 2>&1 || return 1
  curl -fsS --max-time 2 "$service_manager_url/api/v1/health" >/dev/null 2>&1
}

sync_with_service_manager_api() {
  local token
  local curl_cfg
  [ "${SMALLPHONEAI_REGISTRY_SYNC_USE_API:-1}" = "1" ] || return 1
  command -v curl >/dev/null 2>&1 || {
    warn "缺少 curl，无法调用 service-manager registry sync API。"
    return 1
  }
  service_manager_health_ready || {
    warn "service-manager 不可访问，进入 bootstrap fallback registry 同步：$service_manager_url"
    return 1
  }
  token="$(resolve_service_manager_token)"
  if [ -z "$token" ]; then
    warn "无法获取 service-manager token，进入 bootstrap fallback registry 同步。"
    return 1
  fi
  curl_cfg="$(mktemp "${TMPDIR:-/tmp}/smallphoneai-registry-sync-curl.XXXXXX")"
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$curl_cfg"
  if curl -q -fsS --max-time 10 -X POST -K "$curl_cfg" "$service_manager_url/api/v1/registry/sync" >/dev/null; then
    rm -f "$curl_cfg"
    log "OpenHouseAI registry 已通过 service-manager API 同步：$service_manager_url/api/v1/registry/sync"
    return 0
  fi
  rm -f "$curl_cfg"
  warn "service-manager registry sync API 调用失败，进入 bootstrap fallback registry 同步。"
  return 1
}

copy_dir_contents() {
  local source_dir="$1"
  local target_dir="$2"
  mkdir -p "$target_dir"
  if [ -d "$source_dir" ]; then
    cp -a "$source_dir/." "$target_dir/"
  fi
}

replace_directory_from_stage() {
  local stage_dir="$1"
  local target_dir="$2"
  local label="$3"
  local parent_dir
  local base_name
  local old_dir

  parent_dir="$(dirname "$target_dir")"
  base_name="$(basename "$target_dir")"
  old_dir="$parent_dir/.$base_name.old.$$"
  rm -rf "$old_dir"
  mkdir -p "$parent_dir"

  if [ -e "$target_dir" ] || [ -L "$target_dir" ]; then
    if ! mv "$target_dir" "$old_dir"; then
      warn "无法保存旧 $label registry 目录：$target_dir"
      return 1
    fi
  fi

  if mv "$stage_dir" "$target_dir"; then
    rm -rf "$old_dir"
    return 0
  fi

  warn "无法替换 $label registry 目录：$target_dir"
  if [ -e "$old_dir" ] || [ -L "$old_dir" ]; then
    mv "$old_dir" "$target_dir" 2>/dev/null || true
  fi
  return 1
}

fallback_sync_registry() {
  local component_stage_dir
  local ai_docs_stage_dir
  local service_manager_target_dir
  local services_stage_dir

  if [ "$config_dir" = "$termux_config_dir" ]; then
    if ! validate_component_manifests "$termux_config_dir/components.d" "Termux canonical"; then
      record_failed_state "Termux canonical component manifest 校验失败：$termux_config_dir/components.d"
      return 1
    fi
    if ! validate_json_dir "$termux_config_dir/service-manager/services.d" "Termux service-manager registry"; then
      record_failed_state "Termux service-manager registry JSON 校验失败：$termux_config_dir/service-manager/services.d"
      return 1
    fi
    write_registry_state "$termux_config_dir/registry-state.json" "$termux_config_dir" "success" "bootstrap-fallback" "$config_dir" "$termux_config_dir" "source and target are the same"
    log "当前已经是 Termux canonical registry：$termux_config_dir"
    return 0
  fi

  if [ ! -d "$config_dir" ]; then
    record_failed_state "Ubuntu mirror registry 不存在：$config_dir"
    die "Ubuntu mirror registry 不存在：$config_dir"
  fi

  if ! validate_component_manifests "$config_dir/components.d" "Ubuntu mirror"; then
    record_failed_state "Ubuntu mirror component manifest 校验失败：$config_dir/components.d"
    return 1
  fi
  if ! validate_json_dir "$config_dir/service-manager/services.d" "service-manager registry"; then
    record_failed_state "service-manager registry JSON 校验失败：$config_dir/service-manager/services.d"
    return 1
  fi

  if [ ! -d "$termux_home" ]; then
    record_failed_state "Termux home 不存在：$termux_home"
    die "Termux home 不存在，无法同步 canonical registry：$termux_home"
  fi

  service_manager_target_dir="$termux_config_dir/service-manager"
  component_stage_dir="$termux_config_dir/.components.d.tmp.$$"
  ai_docs_stage_dir="$termux_config_dir/.ai-docs.tmp.$$"
  services_stage_dir="$service_manager_target_dir/.services.d.tmp.$$"

  mkdir -p "$termux_config_dir" "$service_manager_target_dir"
  rm -rf "$component_stage_dir" "$ai_docs_stage_dir" "$services_stage_dir"
  mkdir -p "$component_stage_dir" "$ai_docs_stage_dir" "$services_stage_dir"

  copy_dir_contents "$config_dir/components.d" "$component_stage_dir"
  copy_dir_contents "$config_dir/ai-docs" "$ai_docs_stage_dir"
  copy_dir_contents "$config_dir/service-manager/services.d" "$services_stage_dir"

  if ! validate_component_manifests "$component_stage_dir" "Termux canonical staging"; then
    record_failed_state "Termux canonical staging component manifest 校验失败：$component_stage_dir"
    rm -rf "$component_stage_dir" "$ai_docs_stage_dir" "$services_stage_dir"
    return 1
  fi
  if ! validate_json_dir "$services_stage_dir" "Termux service-manager registry staging"; then
    record_failed_state "Termux service-manager staging registry JSON 校验失败：$services_stage_dir"
    rm -rf "$component_stage_dir" "$ai_docs_stage_dir" "$services_stage_dir"
    return 1
  fi

  if ! replace_directory_from_stage "$component_stage_dir" "$termux_config_dir/components.d" "component"; then
    record_failed_state "无法替换 Termux component registry 目录：$termux_config_dir/components.d"
    rm -rf "$component_stage_dir" "$ai_docs_stage_dir" "$services_stage_dir"
    return 1
  fi
  if ! replace_directory_from_stage "$ai_docs_stage_dir" "$termux_config_dir/ai-docs" "AI docs"; then
    record_failed_state "无法替换 Termux AI docs 目录：$termux_config_dir/ai-docs"
    rm -rf "$ai_docs_stage_dir" "$services_stage_dir"
    return 1
  fi
  if ! replace_directory_from_stage "$services_stage_dir" "$termux_config_dir/service-manager/services.d" "service-manager"; then
    record_failed_state "无法替换 Termux service-manager registry 目录：$termux_config_dir/service-manager/services.d"
    rm -rf "$services_stage_dir"
    return 1
  fi

  validate_component_manifests "$termux_config_dir/components.d" "Termux canonical"
  validate_json_dir "$termux_config_dir/service-manager/services.d" "Termux service-manager registry"
  write_registry_state "$termux_config_dir/registry-state.json" "$termux_config_dir" "success" "bootstrap-fallback" "$config_dir" "$termux_config_dir" ""
  validate_json_dir "$termux_config_dir" "Termux registry state"

  log "OpenHouseAI registry 已通过 bootstrap fallback 同步：$config_dir -> $termux_config_dir"
}

if is_termux && [ "${SMALLPHONEAI_SYNC_REGISTRY_IN_UBUNTU:-1}" = "1" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    canonical_service_manager_config="$(configured_service_manager_config_path || true)"
    if [ -z "$canonical_service_manager_config" ] || [ ! -f "$canonical_service_manager_config" ]; then
      warn "找不到 Termux canonical service-manager config，无法安全地把 registry sync 分发到 Ubuntu。"
      exit 1
    fi
    log "正在 Ubuntu 内同步 OpenHouseAI registry 到 Termux canonical。"
    SMALLPHONEAI_SYNC_REGISTRY_IN_UBUNTU=0 \
      proot-distro login ubuntu -- env \
        -u SERVICE_MANAGER_TOKEN \
        -u SMALLPHONE_SERVICE_MANAGER_TOKEN \
        OPENHOUSEAI_CONFIG_DIR="${OPENHOUSEAI_CONFIG_DIR:-}" \
        OPENHOUSEAI_TERMUX_HOME="${OPENHOUSEAI_TERMUX_HOME:-/data/data/com.termux/files/home}" \
        OPENHOUSEAI_TERMUX_CONFIG_DIR="${OPENHOUSEAI_TERMUX_CONFIG_DIR:-}" \
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-/root/smallphoneai-repos}" \
        SMALLPHONEAI_SERVICE_MANAGER_DIR="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" \
        SMALLPHONEAI_SERVICE_MANAGER_BIND="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}" \
        SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG="$canonical_service_manager_config" \
        SMALLPHONEAI_SERVICE_MANAGER_CONFIG_PATH="$canonical_service_manager_config" \
        SERVICE_MANAGER_CONFIG_PATH="$canonical_service_manager_config" \
        SMALLPHONEAI_TERMUX_HOME="${SMALLPHONEAI_TERMUX_HOME:-${OPENHOUSEAI_TERMUX_HOME:-/data/data/com.termux/files/home}}" \
        SMALLPHONEAI_REGISTRY_SYNC_USE_API="${SMALLPHONEAI_REGISTRY_SYNC_USE_API:-1}" \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-}" \
        bash -s < "$0"
    exit $?
  fi
  warn "Ubuntu 尚不可用，将在当前 Termux 环境同步 registry。"
fi

home_dir="${HOME:-/root}"
repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-$home_dir/smallphoneai-repos}"
service_manager_dir="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-$repo_root/service-manager}"
service_manager_bind="$(configured_service_manager_bind)"
service_manager_url="$(configured_service_manager_url)"
config_dir="${OPENHOUSEAI_CONFIG_DIR:-$home_dir/.config/openhouseai}"
termux_home="${OPENHOUSEAI_TERMUX_HOME:-/data/data/com.termux/files/home}"
termux_config_dir="${OPENHOUSEAI_TERMUX_CONFIG_DIR:-$termux_home/.config/openhouseai}"

if sync_with_service_manager_api; then
  exit 0
fi

fallback_sync_registry
