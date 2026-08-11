#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"
control_dir="$repo_dir/app/src/main/assets/maintainer"
distribution_dir="$repo_dir/distribution/resources-v2"
mode="${1:-generate}"

resource_set_id="${OPENHOUSE_RESOURCE_SET_ID:-openhouse-core-stack}"
resource_set_version="${OPENHOUSE_RESOURCE_SET_VERSION:-2026.08.10.1}"
resource_set_sequence="${OPENHOUSE_RESOURCE_SET_SEQUENCE:-2026081001}"
min_apk_version_code="${OPENHOUSE_RESOURCE_MIN_APK_VERSION_CODE:-126}"

case "$mode" in
  generate) ;;
  --check) ;;
  *) printf 'Usage: %s [--check]\n' "$0" >&2; exit 2 ;;
esac

for command in python3 tar gzip sha256sum cmp install; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Missing required command: %s\n' "$command" >&2
    exit 1
  }
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-resource-set-v2.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
generated_payload="$work_dir/product-payloads"
generated_distribution="$work_dir/distribution"
mkdir -p "$generated_payload" "$generated_distribution"

control_stage="$work_dir/control-plane"
mkdir -p "$control_stage"
for name in \
  control-plane-manifest.json \
  _termux-services-env.sh \
  start-control-plane-termux-native.sh \
  repair-control-plane-termux-native.sh \
  inspect-control-plane-termux-native.sh; do
  source="$control_dir/$name"
  [[ -s "$source" ]] || { printf 'Missing control-plane source: %s\n' "$source" >&2; exit 1; }
  install -m "$([[ "$name" == *.sh ]] && printf 0755 || printf 0644)" "$source" "$control_stage/$name"
done

python3 - "$control_stage" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
manifest = json.loads((root / "control-plane-manifest.json").read_text(encoding="utf-8"))
if manifest.get("bundleId") != "openhouse-control-plane" or manifest.get("version") != "1.0.1":
    raise SystemExit("invalid canonical control-plane manifest")
expected = {item["name"]: item["sha256"] for item in manifest.get("files", [])}
if set(expected) != {
    "_termux-services-env.sh",
    "start-control-plane-termux-native.sh",
    "repair-control-plane-termux-native.sh",
    "inspect-control-plane-termux-native.sh",
}:
    raise SystemExit("control-plane manifest must list exactly the four canonical scripts")
for name, digest in expected.items():
    actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
    if actual != digest:
        raise SystemExit(f"control-plane checksum mismatch for {name}: {actual} != {digest}")
PY

tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -C "$control_stage" -cf - . \
  | gzip -n > "$generated_payload/openhouse-control-plane.tgz"

for archive in service-manager.tgz runtime-aarch64.tgz wuyou.tgz openhouse-web.tgz; do
  [[ -s "$payload_dir/$archive" ]] || { printf 'Missing canonical resource: %s\n' "$payload_dir/$archive" >&2; exit 1; }
  gzip -t "$payload_dir/$archive"
  install -m 0644 "$payload_dir/$archive" "$generated_payload/$archive"
done

python3 - \
  "$payload_dir/manifest.json" \
  "$generated_payload" \
  "$generated_distribution" \
  "$resource_set_id" \
  "$resource_set_version" \
  "$resource_set_sequence" \
  "$min_apk_version_code" <<'PY'
import hashlib
import json
import pathlib
import sys

manifest_path = pathlib.Path(sys.argv[1])
payload_dir = pathlib.Path(sys.argv[2])
distribution_dir = pathlib.Path(sys.argv[3])
set_id, set_version = sys.argv[4], sys.argv[5]
sequence, min_apk = int(sys.argv[6]), int(sys.argv[7])

manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
components = {item.get("id"): item for item in manifest.get("components", [])}
specs = [
    ("service-manager", "service-manager", "service-manager.tgz"),
    ("openhouse-control-plane", None, "openhouse-control-plane.tgz"),
    ("openhouse-runtime", "pi-agent", "runtime-aarch64.tgz"),
    ("wuyou", "wuyou", "wuyou.tgz"),
    ("openhouse-web", "openhouse-web", "openhouse-web.tgz"),
]
control_manifest = json.loads(
    (pathlib.Path(sys.argv[1]).parents[2] / "maintainer" / "control-plane-manifest.json").read_text(encoding="utf-8")
)

members = []
publication_resources = []
for resource_id, component_id, archive_name in specs:
    archive_path = payload_dir / archive_name
    data = archive_path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    if component_id is None:
        version = control_manifest["version"]
    else:
        component = components.get(component_id)
        if not component:
            raise SystemExit(f"manifest is missing component {component_id}")
        if component.get("archive") != archive_name:
            raise SystemExit(f"manifest archive mismatch for {component_id}")
        if component.get("sha256") != digest or int(component.get("size", -1)) != len(data):
            raise SystemExit(f"manifest checksum or size mismatch for {component_id}")
        version = component.get("version")
    if not isinstance(version, str) or not version:
        raise SystemExit(f"missing version for {resource_id}")
    members.append({"id": resource_id, "version": version, "sha256": digest})
    metadata = {
        "id": resource_id,
        "version": version,
        "archive": archive_name,
        "compression": "gzip",
        "abi": "arm64-v8a",
        "size": len(data),
        "sha256": digest,
        "url": f"/resources-v2/{resource_id}/{version}/{archive_name}",
        "mirrors": [],
        "minApkVersionCode": min_apk,
    }
    metadata_path = distribution_dir / "resources" / resource_id / version / "metadata.json"
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    publication_resources.append({
        "id": resource_id,
        "version": version,
        "archivePath": f"app/src/main/assets/openhouse/product-payloads/{archive_name}",
        "metadataPath": f"distribution/resources-v2/resources/{resource_id}/{version}/metadata.json",
    })

resource_set = {
    "schema": 2,
    "id": set_id,
    "version": set_version,
    "sequence": sequence,
    "abi": "arm64-v8a",
    "minApkVersionCode": min_apk,
    "resources": members,
}
(payload_dir / "resource-set.json").write_text(
    json.dumps(resource_set, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
set_path = distribution_dir / "resource-sets" / set_id / set_version / "manifest.json"
set_path.parent.mkdir(parents=True, exist_ok=True)
set_path.write_text(json.dumps(resource_set, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(distribution_dir / "publish-manifest.json").write_text(json.dumps({
    "schema": 2,
    "market": "rescue",
    "resources": publication_resources,
    "resourceSet": {
        "id": set_id,
        "version": set_version,
        "manifestPath": f"distribution/resources-v2/resource-sets/{set_id}/{set_version}/manifest.json",
    },
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

sync_file() {
  local source="$1" target="$2" temporary
  mkdir -p "$(dirname "$target")"
  if [[ -f "$target" ]] && cmp -s "$source" "$target"; then
    return 0
  fi
  temporary="${target}.tmp.$$"
  install -m 0644 "$source" "$temporary"
  mv -f "$temporary" "$target"
}

check_file() {
  local expected="$1" actual="$2"
  [[ -f "$actual" ]] || { printf 'Generated resource is missing: %s\n' "$actual" >&2; return 1; }
  cmp -s "$expected" "$actual" || {
    printf 'Generated resource is stale: %s (run scripts/generate-resource-set-v2.sh)\n' "$actual" >&2
    return 1
  }
}

payload_outputs=(
  openhouse-control-plane.tgz
  resource-set.json
)
if [[ "$mode" == --check ]]; then
  for name in "${payload_outputs[@]}"; do
    check_file "$generated_payload/$name" "$payload_dir/$name"
  done
  while IFS= read -r generated; do
    relative="${generated#$generated_distribution/}"
    check_file "$generated" "$distribution_dir/$relative"
  done < <(find "$generated_distribution" -type f | LC_ALL=C sort)
  printf 'OpenHouse resource set V2 is current: %s@%s sequence=%s\n' \
    "$resource_set_id" "$resource_set_version" "$resource_set_sequence"
  exit 0
fi

for name in "${payload_outputs[@]}"; do
  sync_file "$generated_payload/$name" "$payload_dir/$name"
done
while IFS= read -r generated; do
  relative="${generated#$generated_distribution/}"
  sync_file "$generated" "$distribution_dir/$relative"
done < <(find "$generated_distribution" -type f | LC_ALL=C sort)

printf 'Generated OpenHouse resource set V2: %s@%s sequence=%s\n' \
  "$resource_set_id" "$resource_set_version" "$resource_set_sequence"
