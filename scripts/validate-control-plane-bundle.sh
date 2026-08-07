#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="$REPO_DIR/app/src/main/assets/maintainer"
NATIVE_DIR="$REPO_DIR/native-host-adapter/src/main/assets/openhouse-host/control-plane"
WRAPPER="$REPO_DIR/native-host-adapter/src/main/assets/openhouse-host/start-control-plane.sh"

python3 - "$SOURCE_DIR" "$NATIVE_DIR" "$WRAPPER" <<'PY'
import hashlib
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
native = pathlib.Path(sys.argv[2])
wrapper = pathlib.Path(sys.argv[3])
names = (
    "start-control-plane-termux-native.sh",
    "repair-control-plane-termux-native.sh",
    "inspect-control-plane-termux-native.sh",
)

manifest_path = source / "control-plane-manifest.json"
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("schemaVersion") != 1 or manifest.get("bundleId") != "openhouse-control-plane":
    raise SystemExit("invalid OpenHouse control-plane manifest")
entries = {entry.get("name"): entry.get("sha256") for entry in manifest.get("files", []) if isinstance(entry, dict)}
if set(entries) != set(names):
    raise SystemExit("control-plane manifest must enumerate exactly the three managed scripts")

for name in names:
    source_file = source / name
    native_file = native / name
    if not source_file.is_file() or not native_file.is_file():
        raise SystemExit(f"missing control-plane script: {name}")
    source_bytes = source_file.read_bytes()
    if native_file.read_bytes() != source_bytes:
        raise SystemExit(f"Native control-plane script diverged from maintainer source: {name}")
    digest = hashlib.sha256(source_bytes).hexdigest()
    if entries[name] != digest:
        raise SystemExit(f"control-plane manifest checksum mismatch: {name}")

native_manifest = native / "control-plane-manifest.json"
if native_manifest.read_bytes() != manifest_path.read_bytes():
    raise SystemExit("Native control-plane manifest diverged from maintainer source")

wrapper_text = wrapper.read_text(encoding="utf-8")
required = '$HOME/.local/share/openhouseai/control-plane/current/start-control-plane-termux-native.sh'
if required not in wrapper_text:
    raise SystemExit("Native entrypoint does not prefer the staged control-plane bundle")

print(f"control-plane-bundle-ok version={manifest.get('version')} files={len(names)}")
PY
