#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_bundle="$repo_dir/app/src/main/assets/wuxianpi-install/openhouse-install-bundle.tar"
app_index="$repo_dir/app/src/main/assets/wuxianpi-install/bundle-index.json"
native_bundle="$repo_dir/native-app/src/main/assets/wuxianpi-install/openhouse-install-bundle.tar"
native_index="$repo_dir/native-app/src/main/assets/wuxianpi-install/bundle-index.json"

for file in "$app_bundle" "$app_index" "$native_bundle" "$native_index"; do
  [[ -s "$file" ]] || { printf 'Missing install bundle artifact: %s\n' "$file" >&2; exit 1; }
done
cmp -s "$app_bundle" "$native_bundle" || { printf 'APK install bundles differ\n' >&2; exit 1; }
cmp -s "$app_index" "$native_index" || { printf 'APK bundle indexes differ\n' >&2; exit 1; }

python3 - "$repo_dir" "$app_bundle" "$app_index" <<'PY'
import hashlib, json, pathlib, posixpath, sys, tarfile, tempfile

repo, bundle, index_path = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
archives = {
    "service-manager": "service-manager.tgz",
    "openhouse-control-plane": "openhouse-control-plane.tgz",
    "openhouse-runtime": "runtime-aarch64.tgz",
    "wuyou": "wuyou.tgz",
    "openhouse-web": "openhouse-web.tgz",
}
index = json.loads(index_path.read_text())
if index.get("schema") != 2 or index.get("bundleAsset") != "wuxianpi-install/openhouse-install-bundle.tar":
    raise SystemExit("bundle index contract is invalid")
if index.get("bundleSize") != bundle.stat().st_size:
    raise SystemExit("bundle index size mismatch")
required = {
    "bundle-manifest.json", "bootstrap/bootstrap.sh", "bootstrap/scripts/wuxianpi-setup",
    "bootstrap/scripts/openhouse-resource-import", "bootstrap/scripts/openhouse-resource-manager",
    "resources/resource-set.json", *{f"resources/{name}" for name in archives.values()},
}
with tarfile.open(bundle, "r:") as outer:
    names = set()
    for member in outer.getmembers():
        name = member.name.removeprefix("./").rstrip("/")
        if not name: continue
        if member.name.startswith("/") or ".." in pathlib.PurePosixPath(name).parts or "\\" in name:
            raise SystemExit(f"unsafe bundle path: {name}")
        if not (member.isfile() or member.isdir()): raise SystemExit(f"unsupported bundle member: {name}")
        names.add(name)
    if "SHA256SUMS" in names or "offer.json" in names: raise SystemExit("runtime checksum/offer metadata leaked into bundle")
    missing = required - names
    if missing: raise SystemExit(f"bundle is missing {sorted(missing)}")
    with tempfile.TemporaryDirectory() as temporary:
        root = pathlib.Path(temporary); outer.extractall(root)
        manifest = json.loads((root / "bundle-manifest.json").read_text())
        resource_set = json.loads((root / "resources/resource-set.json").read_text())
        if manifest.get("schema") != 2 or manifest.get("id") != "openhouse-install-bundle":
            raise SystemExit("bundle manifest contract is invalid")
        if manifest.get("resourceSet") != resource_set: raise SystemExit("manifest and resource set differ")
        if manifest.get("bundleId") != f"{resource_set['id']}-{resource_set['sequence']}":
            raise SystemExit("bundle id is invalid")
        for field in ("bundleId", "apkVersionCode"):
            if index.get(field) != manifest.get(field): raise SystemExit(f"bundle index mismatch: {field}")
        if index.get("resourceSetVersion") != resource_set.get("version") or index.get("resourceSetSequence") != resource_set.get("sequence"):
            raise SystemExit("bundle index resource set mismatch")
        by_id = {item["id"]: item for item in resource_set.get("resources", [])}
        if set(by_id) != set(archives) or len(resource_set.get("resources", [])) != 5:
            raise SystemExit("resource set allowlist is invalid")
        for resource_id, archive_name in archives.items():
            entry = by_id[resource_id]; path = root / "resources" / archive_name
            if entry.get("archive") != archive_name or entry.get("size") != path.stat().st_size:
                raise SystemExit(f"resource archive metadata mismatch: {resource_id}")
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            if entry.get("sha256") != digest: raise SystemExit(f"resource build SHA mismatch: {resource_id}")
            with tarfile.open(path, "r:gz") as nested:
                members = nested.getmembers()
                if not members:
                    raise SystemExit(f"resource archive structure invalid: {resource_id}")
                for member in members:
                    if member.isfile() or member.isdir():
                        continue
                    if member.issym():
                        target = posixpath.normpath(posixpath.join(posixpath.dirname(member.name), member.linkname))
                        if member.linkname.startswith("/") or "\\" in member.linkname or target == ".." or target.startswith("../"):
                            raise SystemExit(f"resource archive contains unsafe symlink: {resource_id}")
                        continue
                    raise SystemExit(f"resource archive contains unsupported member: {resource_id}")

manager = (repo / "app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-manager").read_text()
importer = (repo / "app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-import").read_text()
runtime_forbidden = ("sha256sum", "tree_sha", "installedManifestSha256", "archiveSha256", "offer.json")
for token in runtime_forbidden:
    if token in manager or token in importer: raise SystemExit(f"runtime checksum/offer logic remains: {token}")
for token in ("curl", "/api/v1/", "service-daemon", "sv up", "token show", "registry/sync"):
    if token in manager or token in importer: raise SystemExit(f"content layer owns activation/network logic: {token}")
setup = (repo / "app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup").read_text()
for token in ("activation.lock", "canonical_auth_failed", "registry_sync_failed", "wuxianpi_health_failed"):
    if token not in setup: raise SystemExit(f"activation contract is missing: {token}")
manifest = json.loads((repo / "operit-feature/src/main/assets/rescue-plugins/wuxianpi.first-install/manifest.json").read_text())
if manifest.get("version") != "1.0.13": raise SystemExit("bundled first-install version must be 1.0.13")
print(f"Install bundle validated: sha256={hashlib.sha256(bundle.read_bytes()).hexdigest()} size={bundle.stat().st_size}")
PY

bash -n "$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-import"
bash -n "$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-manager"
bash -n "$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
