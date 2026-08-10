#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_DIR="$REPO_DIR/app/src/main/assets/maintainer"
ARCHIVE="$REPO_DIR/app/src/main/assets/openhouse/product-payloads/openhouse-control-plane.tgz"
NATIVE_ARCHIVE="$REPO_DIR/native-app/src/main/assets/openhouse-resources-v2/openhouse-control-plane.tgz"
WRAPPER="$REPO_DIR/native-host-adapter/src/main/assets/openhouse-host/start-control-plane.sh"

python3 - "$SOURCE_DIR" "$ARCHIVE" "$NATIVE_ARCHIVE" "$WRAPPER" <<'PY'
import hashlib
import json
import pathlib
import sys
import tarfile

source = pathlib.Path(sys.argv[1])
archive = pathlib.Path(sys.argv[2])
native_archive = pathlib.Path(sys.argv[3])
wrapper = pathlib.Path(sys.argv[4])
names = (
    "_termux-services-env.sh",
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
    raise SystemExit("control-plane manifest must enumerate exactly the four managed scripts")

for name in names:
    source_file = source / name
    if not source_file.is_file():
        raise SystemExit(f"missing control-plane script: {name}")
    source_bytes = source_file.read_bytes()
    digest = hashlib.sha256(source_bytes).hexdigest()
    if entries[name] != digest:
        raise SystemExit(f"control-plane manifest checksum mismatch: {name}")

if archive.read_bytes() != native_archive.read_bytes():
    raise SystemExit("All-in-One and Native control-plane archives differ")
with tarfile.open(archive, "r:gz") as bundle:
    members = {member.name.lstrip("./"): member for member in bundle.getmembers() if member.isfile()}
    expected_names = set(names) | {"control-plane-manifest.json"}
    if set(members) != expected_names:
        raise SystemExit("control-plane archive must contain exactly its manifest and four scripts")
    for name in names:
        extracted = bundle.extractfile(members[name])
        if extracted is None or extracted.read() != (source / name).read_bytes():
            raise SystemExit(f"control-plane archive diverged from source: {name}")
    extracted_manifest = bundle.extractfile(members["control-plane-manifest.json"])
    if extracted_manifest is None or extracted_manifest.read() != manifest_path.read_bytes():
        raise SystemExit("control-plane archive manifest diverged from source")

wrapper_text = wrapper.read_text(encoding="utf-8")
required = '$HOME/.local/share/openhouseai/control-plane/current/start-control-plane-termux-native.sh'
if required not in wrapper_text:
    raise SystemExit("Native entrypoint does not prefer the installed resource control plane")

print(f"control-plane-bundle-ok version={manifest.get('version')} files={len(names)}")
PY
