#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
market_dir="$repo_dir/distribution/market-script-resources"
publish_manifest="$market_dir/publish-manifest.json"
target="$repo_dir/app/src/main/assets/openhouse/product-payloads/resource-set.json"
mode="${1:-sync}"

case "$mode" in
  sync|--check) ;;
  *) printf 'Usage: %s [sync|--check]\n' "$0" >&2; exit 2 ;;
esac

[[ -s "$publish_manifest" ]] || { printf 'Missing market publish manifest: %s\n' "$publish_manifest" >&2; exit 1; }

set_path="$(jq -r '.resourceSet.manifestPath // empty' "$publish_manifest")"
[[ -n "$set_path" && -s "$repo_dir/$set_path" ]] || {
  printf 'Market resource-set manifest is missing\n' >&2
  exit 1
}

python3 - "$repo_dir" "$publish_manifest" "$repo_dir/$set_path" "$target" "$mode" <<'PY'
import hashlib
import json
import pathlib
import sys

repo, publish_path, set_path, target_path = map(pathlib.Path, sys.argv[1:5])
mode = sys.argv[5]
publish = json.loads(publish_path.read_text(encoding="utf-8"))
resource_set = json.loads(set_path.read_text(encoding="utf-8"))
if publish.get("schema") != 2 or publish.get("market") != "rescue":
    raise SystemExit("market publish manifest is invalid")
if publish.get("resourceSet", {}).get("version") != resource_set.get("version"):
    raise SystemExit("market resource-set version does not match publish manifest")
releases = {item["id"]: item for item in publish.get("resources", [])}
members = {item["id"]: item for item in resource_set.get("resources", [])}
if not members or set(releases) != set(members):
    raise SystemExit("market resource-set and publish manifest members differ")
for resource_id, member in members.items():
    release = releases[resource_id]
    if release.get("version") != member.get("version"):
        raise SystemExit(f"market resource version differs for {resource_id}")
    archive = repo / release["archivePath"]
    metadata = repo / release["metadataPath"]
    if not archive.is_file() or not metadata.is_file():
        raise SystemExit(f"market archive or metadata is missing for {resource_id}")
    data = archive.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    meta = json.loads(metadata.read_text(encoding="utf-8"))
    if member.get("archive") != meta.get("archive") or member.get("size") != len(data) or member.get("sha256") != digest:
        raise SystemExit(f"market resource metadata does not match bytes for {resource_id}")
if mode == "--check":
    if not target_path.is_file() or target_path.read_bytes() != set_path.read_bytes():
        raise SystemExit("APK resource-set is stale; run scripts/sync-apk-resource-set.sh")
else:
    target_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = target_path.with_name(target_path.name + ".tmp")
    temporary.write_bytes(set_path.read_bytes())
    temporary.replace(target_path)
print(f"APK resource set is current: {resource_set['id']}@{resource_set['version']} sequence={resource_set['sequence']}")
PY
