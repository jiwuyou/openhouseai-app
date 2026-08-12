#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_bundle="$repo_dir/app/src/main/assets/wuxianpi-install/openhouse-install-bundle.tar"
app_metadata="$repo_dir/app/src/main/assets/wuxianpi-install/openhouse-install-bundle.json"
native_bundle="$repo_dir/native-app/src/main/assets/wuxianpi-install/openhouse-install-bundle.tar"
native_metadata="$repo_dir/native-app/src/main/assets/wuxianpi-install/openhouse-install-bundle.json"

for file in "$app_bundle" "$app_metadata" "$native_bundle" "$native_metadata"; do
  [[ -s "$file" ]] || { printf 'Missing install bundle artifact: %s\n' "$file" >&2; exit 1; }
done
cmp -s "$app_bundle" "$native_bundle" \
  || { printf 'All-in-One and Native install bundles differ\n' >&2; exit 1; }
cmp -s "$app_metadata" "$native_metadata" \
  || { printf 'All-in-One and Native install bundle metadata differ\n' >&2; exit 1; }

python3 - "$repo_dir" "$app_bundle" "$app_metadata" <<'PY'
import hashlib
import io
import json
import pathlib
import re
import sys
import tarfile
import tempfile

repo = pathlib.Path(sys.argv[1])
bundle = pathlib.Path(sys.argv[2])
metadata_path = pathlib.Path(sys.argv[3])
expected_resources = {
    "service-manager": "service-manager.tgz",
    "openhouse-control-plane": "openhouse-control-plane.tgz",
    "openhouse-runtime": "runtime-aarch64.tgz",
    "wuyou": "wuyou.tgz",
    "openhouse-web": "openhouse-web.tgz",
}

def digest(path):
    value = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()

metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
bundle_sha = digest(bundle)
if metadata.get("schema") != 1:
    raise SystemExit("install bundle metadata schema must be 1")
if metadata.get("bundleAsset") != "wuxianpi-install/openhouse-install-bundle.tar":
    raise SystemExit("install bundle asset path is not canonical")
if metadata.get("bundleSha256") != bundle_sha:
    raise SystemExit("install bundle metadata SHA-256 mismatch")
if metadata.get("bundleSize") != bundle.stat().st_size:
    raise SystemExit("install bundle metadata size mismatch")

required = {
    "bundle-manifest.json",
    "SHA256SUMS",
    "bootstrap/bootstrap.sh",
    "bootstrap/scripts/wuxianpi-setup",
    "bootstrap/scripts/openhouse-resource-import",
    "bootstrap/scripts/openhouse-resource-manager",
    "resources/resource-set.json",
    *{f"resources/{name}" for name in expected_resources.values()},
}
with tarfile.open(bundle, mode="r:") as archive:
    members = archive.getmembers()
    names = []
    for member in members:
        name = member.name.removeprefix("./")
        if not name:
            continue
        parts = pathlib.PurePosixPath(name).parts
        if member.name.startswith("/") or ".." in parts or "\\" in member.name:
            raise SystemExit(f"unsafe install bundle path: {member.name}")
        if not (member.isfile() or member.isdir()):
            raise SystemExit(f"unsupported install bundle entry type: {member.name}")
        names.append(name.rstrip("/"))
    missing = required - set(names)
    if missing:
        raise SystemExit(f"install bundle is missing: {sorted(missing)}")
    with tempfile.TemporaryDirectory(prefix="openhouse-install-bundle-") as temporary:
        root = pathlib.Path(temporary)
        archive.extractall(root)
        sums = {}
        for line in (root / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
            match = re.fullmatch(r"([a-f0-9]{64})  (.+)", line)
            if not match:
                raise SystemExit(f"invalid SHA256SUMS line: {line}")
            relative = match.group(2).removeprefix("./")
            sums[relative] = match.group(1)
        actual_files = {
            path.relative_to(root).as_posix()
            for path in root.rglob("*")
            if path.is_file() and path.name != "SHA256SUMS"
        }
        if set(sums) != actual_files:
            raise SystemExit("SHA256SUMS does not cover exactly every bundle file")
        for relative, expected in sums.items():
            if digest(root / relative) != expected:
                raise SystemExit(f"bundle member checksum mismatch: {relative}")

        bundle_manifest = json.loads((root / "bundle-manifest.json").read_text(encoding="utf-8"))
        resource_set = json.loads((root / "resources/resource-set.json").read_text(encoding="utf-8"))
        if bundle_manifest.get("schema") != 1 or bundle_manifest.get("id") != "openhouse-install-bundle":
            raise SystemExit("bundle-manifest.json is invalid")
        if bundle_manifest.get("resourceSet") != resource_set or metadata.get("resourceSet") != resource_set:
            raise SystemExit("resource-set copies in bundle and metadata differ")
        resources = resource_set.get("resources", [])
        if resource_set.get("schema") != 2 or resource_set.get("id") != "openhouse-core-stack":
            raise SystemExit("resource set contract is invalid")
        if resource_set.get("abi") != "arm64-v8a" or len(resources) != 5:
            raise SystemExit("resource set must contain exactly five ARM64 resources")
        by_id = {entry.get("id"): entry for entry in resources}
        if set(by_id) != set(expected_resources):
            raise SystemExit("resource set contains an unknown, duplicate, or missing resource")
        for resource_id, archive_name in expected_resources.items():
            path = root / "resources" / archive_name
            if digest(path) != by_id[resource_id].get("sha256"):
                raise SystemExit(f"resource SHA-256 mismatch: {resource_id}")
            with tarfile.open(path, mode="r:gz") as nested:
                if not nested.getmembers():
                    raise SystemExit(f"resource archive is empty: {resource_id}")

setup = (repo / "app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup").read_text()
if "--resource-inbox" not in setup or "openhouse-resource-import" not in setup:
    raise SystemExit("wuxianpi-setup does not use the install bundle importer")
if ".local/share/wuxianpi/plugins/wuxianpi.resource-update" in setup:
    raise SystemExit("first install still depends on wuxianpi.resource-update")
native_repository = (repo / "native-host-adapter/src/main/java/com/openhouse/host/nativeapp/NativeTermuxHomeRepository.kt").read_text()
if ".renameTo(" in native_repository:
    raise SystemExit("Native SAF install bundle staging must not call renameTo")
old_native = repo / "native-app/src/main/assets/openhouse-resources-v2"
if old_native.exists() and any(path.is_file() for path in old_native.rglob("*")):
    raise SystemExit("Native per-resource APK assets must be removed")
workflow = json.loads((repo / "operit-feature/src/main/assets/rescue-plugins/wuxianpi.first-install/workflows/install.json").read_text())
workflow_text = json.dumps(workflow, ensure_ascii=False)
if "wuxianpi.resource-update" in workflow_text:
    raise SystemExit("first-install workflow must not require the online resource updater")
manifest = json.loads((repo / "operit-feature/src/main/assets/rescue-plugins/wuxianpi.first-install/manifest.json").read_text())
if manifest.get("version") != "1.0.11":
    raise SystemExit("bundled first-install plugin must be 1.0.11")

manager = (repo / "app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-manager").read_text()
for forbidden in (
    "start_control_plane", "verify_live_stack", "register_resources",
    "/api/v1/services", "/api/v1/health", "20765", "service-daemon start", "sv up",
):
    if forbidden in manager:
        raise SystemExit(f"content resource manager still owns runtime activation: {forbidden}")
for required in (
    'CONFIG_PATH="$HOME/.config/openhouseai/service-manager/config.json"',
    'BIND="127.0.0.1:20087"',
    "INSTALL_SERVICE=0",
    '"$directory/scripts/install.sh"',
):
    if required not in manager:
        raise SystemExit(f"content resource manager is missing static contract: {required}")

importer = (repo / "app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-import").read_text()
if 'status:"satisfied"' in importer or '"$inbox/.consumed"' in importer:
    raise SystemExit("resource importer must leave APK offer pending until runtime activation")
if 'activation":"pending"' not in importer or '"$inbox/.imported"' not in importer:
    raise SystemExit("resource importer does not publish independent content/activation state")

for required in (
    "activate_runtime()", "canonical_auth_failed", "registry_sync_failed",
    "wuxianpi_health_failed", "serviceListReady", "registryApiReady",
):
    if required not in setup:
        raise SystemExit(f"wuxianpi-setup is missing activation/status contract: {required}")
print(f"Install bundle validated: sha256={bundle_sha} size={bundle.stat().st_size}")
PY

bash -n "$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-import"
bash -n "$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-manager"
bash -n "$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
