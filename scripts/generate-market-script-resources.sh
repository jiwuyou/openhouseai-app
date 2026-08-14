#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"
maintainer_dir="$repo_dir/app/src/main/assets/maintainer"
bootstrap_dir="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts"
runtime_register_source="$repo_dir/resources/market-sources/openhouse-runtime-register-service.sh"
output_dir="$repo_dir/distribution/market-script-resources"
guide_path="$repo_dir/docs/resource-sets/openhouse-core-stack-2026.08.14.1.md"
mode="${1:-generate}"

set_id="${OPENHOUSE_MARKET_RESOURCE_SET_ID:-openhouse-core-stack}"
set_version="${OPENHOUSE_MARKET_RESOURCE_SET_VERSION:-2026.08.14.2}"
set_sequence="${OPENHOUSE_MARKET_RESOURCE_SET_SEQUENCE:-2026081402}"
script_version="${OPENHOUSE_MARKET_SCRIPT_VERSION:-1.0.1}"
runtime_version="${OPENHOUSE_MARKET_RUNTIME_VERSION:-0.2.0+registry.1}"
min_apk_version_code="${OPENHOUSE_RESOURCE_MIN_APK_VERSION_CODE:-126}"

case "$mode" in
  generate) ;;
  --check) ;;
  *) printf 'Usage: %s [--check]\n' "$0" >&2; exit 2 ;;
esac

for command in cmp diff gzip install jq python3 sha256sum tar; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Missing required command: %s\n' "$command" >&2
    exit 1
  }
done
[[ -s "$guide_path" ]] || { printf 'Missing resource-set guide: %s\n' "$guide_path" >&2; exit 1; }

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-market-resources.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
generated="$work_dir/generated"
specs="$work_dir/resources.tsv"
fixed_sources="$work_dir/fixed-sources"
mkdir -p "$generated/resources" "$generated/resource-sets/$set_id/$set_version"
mkdir -p "$fixed_sources"
: >"$specs"

termux_bash_source() {
  local source="$1" target="$2"
  {
    printf '%s\n' '#!/data/data/com.termux/files/usr/bin/bash'
    sed '1{/^#!/d;}' "$source"
  } >"$target"
  chmod 0755 "$target"
}

termux_repair_source() {
  local source="$1" target="$2"
  {
    printf '%s\n' '#!/data/data/com.termux/files/usr/bin/bash'
    awk '
      { print }
      /^  for candidate in \\$/ {
        print "    \"${PREFIX:-/data/data/com.termux/files/usr}/libexec/openhouse/_termux-services-env.sh\" " sprintf("%c", 92)
      }
    ' "$source"
  } >"$target"
  chmod 0755 "$target"
}

termux_bash_source "$maintainer_dir/_termux-services-env.sh" "$fixed_sources/_termux-services-env.sh"
termux_bash_source "$maintainer_dir/inspect-control-plane-termux-native.sh" "$fixed_sources/inspect-control-plane.sh"
termux_repair_source "$maintainer_dir/repair-control-plane-termux-native.sh" "$fixed_sources/repair-control-plane.sh"

add_script_resource() {
  local id="$1" archive="$2" member="$3" source="$4" stage target
  [[ -s "$source" ]] || { printf 'Missing script resource source: %s\n' "$source" >&2; exit 1; }
  [[ "$(head -n 1 "$source")" == '#!/data/data/com.termux/files/usr/bin/bash' ]] || {
    printf 'Termux script must use the absolute Bash shebang: %s\n' "$source" >&2
    exit 1
  }
  stage="$work_dir/stage-$id"
  target="$generated/resources/$id/$script_version/$archive"
  mkdir -p "$stage" "$(dirname "$target")"
  install -m 0755 "$source" "$stage/$member"
  tar --format=ustar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
    -C "$stage" -cf - "$member" | gzip -n >"$target"
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$id" "$script_version" "$archive" "$target" \
    "distribution/market-script-resources/resources/$id/$script_version/$archive" >>"$specs"
}

add_script_resource openhouse-resource-manager openhouse-resource-manager.tgz \
  openhouse-resource-manager "$bootstrap_dir/openhouse-resource-manager"
add_script_resource openhouse-resource-import openhouse-resource-import.tgz \
  openhouse-resource-import "$bootstrap_dir/openhouse-resource-import"
add_script_resource wuxianpi-setup wuxianpi-setup.tgz \
  wuxianpi-setup "$bootstrap_dir/wuxianpi-setup"
add_script_resource openhouse-install-runtime-components openhouse-install-runtime-components.tgz \
  50-install-runtime-components.sh "$bootstrap_dir/50-install-runtime-components.sh"
add_script_resource openhouse-start-smallphone openhouse-start-smallphone.tgz \
  60-start-smallphone.sh "$bootstrap_dir/60-start-smallphone.sh"
add_script_resource openhouse-register-component openhouse-register-component.tgz \
  register-openhouse-component.sh "$bootstrap_dir/register-openhouse-component.sh"
add_script_resource openhouse-control-plane-start openhouse-control-plane-start.tgz \
  openhouse-control-plane-start "$maintainer_dir/openhouse-control-plane-start"
add_script_resource openhouse-termux-services-env openhouse-termux-services-env.tgz \
  _termux-services-env.sh "$fixed_sources/_termux-services-env.sh"
add_script_resource openhouse-start-service-manager openhouse-start-service-manager.tgz \
  start-service-manager.sh "$maintainer_dir/start-service-manager.sh"
add_script_resource openhouse-repair-control-plane openhouse-repair-control-plane.tgz \
  repair-control-plane.sh "$fixed_sources/repair-control-plane.sh"
add_script_resource openhouse-inspect-control-plane openhouse-inspect-control-plane.tgz \
  inspect-control-plane.sh "$fixed_sources/inspect-control-plane.sh"

runtime_stage="$work_dir/runtime"
runtime_archive="$generated/resources/openhouse-runtime/$runtime_version/runtime-aarch64.tgz"
mkdir -p "$runtime_stage" "$(dirname "$runtime_archive")"
tar -xzf "$payload_dir/runtime-aarch64.tgz" -C "$runtime_stage" --no-same-owner
install -m 0755 "$runtime_register_source" \
  "$runtime_stage/scripts/register-service.sh"
tar --format=ustar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -C "$runtime_stage" -cf - . | gzip -n >"$runtime_archive"
printf '%s\t%s\t%s\t%s\t%s\n' \
  openhouse-runtime "$runtime_version" runtime-aarch64.tgz "$runtime_archive" \
  "distribution/market-script-resources/resources/openhouse-runtime/$runtime_version/runtime-aarch64.tgz" >>"$specs"

for component in service-manager wuyou openhouse-web; do
  component_id="$component"
  archive="$component.tgz"
  manifest_id="$component"
  [[ "$component" != openhouse-web ]] || manifest_id=openhouse-web
  version="$(jq -r --arg id "$manifest_id" '.components[] | select(.id == $id) | .version' "$payload_dir/manifest.json")"
  [[ -n "$version" && "$version" != null ]] || { printf 'Missing version for %s\n' "$component" >&2; exit 1; }
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$component_id" "$version" "$archive" "$payload_dir/$archive" \
    "app/src/main/assets/openhouse/product-payloads/$archive" >>"$specs"
done

python3 - "$specs" "$generated" "$guide_path" "$set_id" "$set_version" \
  "$set_sequence" "$min_apk_version_code" <<'PY'
import hashlib
import json
import pathlib
import sys

spec_path, generated_path, guide_path = map(pathlib.Path, sys.argv[1:4])
set_id, set_version = sys.argv[4:6]
sequence, min_apk = map(int, sys.argv[6:8])
resources = []
publication = []
for raw in spec_path.read_text(encoding="utf-8").splitlines():
    resource_id, version, archive, source, final_ref = raw.split("\t")
    archive_path = pathlib.Path(source)
    data = archive_path.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    member = {
        "id": resource_id,
        "version": version,
        "archive": archive,
        "size": len(data),
        "sha256": digest,
    }
    resources.append(member)
    metadata = {
        **member,
        "compression": "gzip",
        "abi": "arm64-v8a",
        "url": f"/resources-v2/{resource_id}/{version}/{archive}",
        "mirrors": [],
        "minApkVersionCode": min_apk,
    }
    metadata_path = generated_path / "resources" / resource_id / version / "metadata.json"
    metadata_path.parent.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    publication.append({
        "id": resource_id,
        "version": version,
        "archivePath": final_ref,
        "metadataPath": (
            f"distribution/market-script-resources/resources/{resource_id}/{version}/metadata.json"
        ),
    })

resource_set = {
    "schema": 2,
    "id": set_id,
    "version": set_version,
    "sequence": sequence,
    "abi": "arm64-v8a",
    "minApkVersionCode": min_apk,
    "guide": {
        "title": "OpenHouse Core Stack 使用指南",
        "markdown": guide_path.read_text(encoding="utf-8"),
    },
    "resources": resources,
}
set_path = generated_path / "resource-sets" / set_id / set_version / "manifest.json"
set_path.write_text(json.dumps(resource_set, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
(generated_path / "publish-manifest.json").write_text(json.dumps({
    "schema": 2,
    "market": "rescue",
    "resources": publication,
    "resourceSet": {
        "id": set_id,
        "version": set_version,
        "manifestPath": (
            f"distribution/market-script-resources/resource-sets/{set_id}/{set_version}/manifest.json"
        ),
    },
}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

if [[ "$mode" == --check ]]; then
  while IFS= read -r source; do
    relative="${source#$generated/}"
    target="$output_dir/$relative"
    [[ -f "$target" ]] && cmp -s "$source" "$target" || {
      printf 'Market script resources are stale; run scripts/generate-market-script-resources.sh\n' >&2
      exit 1
    }
  done < <(find "$generated" -type f | LC_ALL=C sort)
  printf 'Market script resources are current: %s@%s sequence=%s\n' \
    "$set_id" "$set_version" "$set_sequence"
  exit 0
fi

mkdir -p "$output_dir"
while IFS= read -r source; do
  relative="${source#$generated/}"
  target="$output_dir/$relative"
  mkdir -p "$(dirname "$target")"
  if [[ ! -f "$target" ]] || ! cmp -s "$source" "$target"; then
    install -m 0644 "$source" "$target.tmp.$$"
    mv -f "$target.tmp.$$" "$target"
  fi
done < <(find "$generated" -type f | LC_ALL=C sort)

printf 'Generated market resources: %s@%s sequence=%s\n' \
  "$set_id" "$set_version" "$set_sequence"
