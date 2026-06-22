#!/usr/bin/env bash
set -euo pipefail

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

warn() {
  printf '[SmallPhoneAI] WARN: %s\n' "$*" >&2
}

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
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

validate_component_manifests() {
  local source_dir="$1"
  local label="$2"
  local py
  [ -d "$source_dir" ] || return 0
  py="$(find_python || true)"
  if [ -z "$py" ]; then
    warn "找不到 Python，跳过 $label component manifest 结构校验。"
    return 0
  fi
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
  py="$(find_python || true)"
  if [ -z "$py" ]; then
    warn "找不到 Python，跳过 $label JSON 校验。"
    return 0
  fi
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

validate_synced_component_manifests() {
  local source_dir="$1"
  local target_dir="$2"
  local label="$3"
  local py
  [ -d "$source_dir" ] || return 0
  py="$(find_python || true)"
  if [ -z "$py" ]; then
    warn "找不到 Python，跳过 $label 同步结果校验。"
    return 0
  fi
  "$py" - "$source_dir" "$target_dir" "$label" <<'PY'
import json
import pathlib
import sys

source_root = pathlib.Path(sys.argv[1])
target_root = pathlib.Path(sys.argv[2])
label = sys.argv[3]
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

for source in sorted(source_root.glob("*.json")):
    target = target_root / source.name
    if not target.exists():
        raise SystemExit(f"{label}: missing synced component manifest {target}")
    with target.open("r", encoding="utf-8") as handle:
        doc = json.load(handle)
    missing = [key for key in ("shellMenu", "smallphoneApp", "serviceManager", "ai") if key not in doc]
    if missing:
        raise SystemExit(f"{label}: {target.name} missing component manifest layers: {', '.join(missing)}")
    walk(doc, "$")
PY
}

validate_synced_json_files() {
  local source_dir="$1"
  local target_dir="$2"
  local label="$3"
  local py
  [ -d "$source_dir" ] || return 0
  py="$(find_python || true)"
  if [ -z "$py" ]; then
    warn "找不到 Python，跳过 $label 同步 JSON 校验。"
    return 0
  fi
  "$py" - "$source_dir" "$target_dir" "$label" <<'PY'
import json
import pathlib
import sys

source_root = pathlib.Path(sys.argv[1])
target_root = pathlib.Path(sys.argv[2])
label = sys.argv[3]
for source in sorted(source_root.glob("*.json")):
    target = target_root / source.name
    if not target.exists():
        raise SystemExit(f"{label}: missing synced JSON file {target}")
    with target.open("r", encoding="utf-8") as handle:
        json.load(handle)
PY
}

if is_termux && [ "${SMALLPHONEAI_SYNC_REGISTRY_IN_UBUNTU:-1}" = "1" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    log "正在 Ubuntu 内同步 OpenHouseAI registry 到 Termux canonical。"
    SMALLPHONEAI_SYNC_REGISTRY_IN_UBUNTU=0 \
      proot-distro login ubuntu -- env \
        OPENHOUSEAI_CONFIG_DIR="${OPENHOUSEAI_CONFIG_DIR:-}" \
        OPENHOUSEAI_TERMUX_HOME="${OPENHOUSEAI_TERMUX_HOME:-/data/data/com.termux/files/home}" \
        OPENHOUSEAI_TERMUX_CONFIG_DIR="${OPENHOUSEAI_TERMUX_CONFIG_DIR:-}" \
        bash -s < "$0"
    exit $?
  fi
  warn "Ubuntu 尚不可用，将在当前 Termux 环境同步 registry。"
fi

home_dir="${HOME:-/root}"
config_dir="${OPENHOUSEAI_CONFIG_DIR:-$home_dir/.config/openhouseai}"
termux_home="${OPENHOUSEAI_TERMUX_HOME:-/data/data/com.termux/files/home}"
termux_config_dir="${OPENHOUSEAI_TERMUX_CONFIG_DIR:-$termux_home/.config/openhouseai}"

if [ "$config_dir" = "$termux_config_dir" ]; then
  log "当前已经是 Termux canonical registry：$termux_config_dir"
  exit 0
fi

if [ ! -d "$config_dir" ]; then
  warn "Ubuntu mirror registry 不存在：$config_dir"
  exit 0
fi

validate_component_manifests "$config_dir/components.d" "Ubuntu mirror"
validate_json_dir "$config_dir/service-manager/services.d" "service-manager registry"

if [ ! -d "$termux_home" ]; then
  warn "Termux home 不存在，跳过 canonical 同步：$termux_home"
  exit 0
fi

mkdir -p "$termux_config_dir/components.d" \
  "$termux_config_dir/ai-docs" \
  "$termux_config_dir/service-manager/services.d"

if [ -d "$config_dir/components.d" ]; then
  cp -a "$config_dir/components.d/." "$termux_config_dir/components.d/" 2>/dev/null || true
fi
if [ -d "$config_dir/ai-docs" ]; then
  cp -a "$config_dir/ai-docs/." "$termux_config_dir/ai-docs/" 2>/dev/null || true
fi
if [ -d "$config_dir/service-manager/services.d" ]; then
  cp -a "$config_dir/service-manager/services.d/." "$termux_config_dir/service-manager/services.d/" 2>/dev/null || true
fi

validate_synced_component_manifests "$config_dir/components.d" "$termux_config_dir/components.d" "Termux canonical"
validate_synced_json_files "$config_dir/service-manager/services.d" "$termux_config_dir/service-manager/services.d" "Termux service-manager registry"

log "OpenHouseAI registry 已同步：$config_dir -> $termux_config_dir"
