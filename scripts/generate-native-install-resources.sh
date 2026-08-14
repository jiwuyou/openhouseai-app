#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bootstrap_dir="$repo_dir/app/src/main/assets/smallphoneai/bootstrap"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"
app_assets="$repo_dir/app/src/main/assets/wuxianpi-install"
native_assets="$repo_dir/native-app/src/main/assets/wuxianpi-install"
pre_tmux="$bootstrap_dir/scripts/wuxianpi-pre-tmux.sh"
bundle_name="openhouse-install-bundle.tar"
index_name="bundle-index.json"
canonical_bundle="$app_assets/$bundle_name"
canonical_index="$app_assets/$index_name"
stage="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-install-bundle.XXXXXX")"
trap 'rm -rf -- "$stage"' EXIT

"$repo_dir/scripts/sync-apk-resource-set.sh"

required=(
  "$pre_tmux"
  "$bootstrap_dir/bootstrap.sh"
  "$bootstrap_dir/scripts/wuxianpi-setup"
  "$bootstrap_dir/scripts/openhouse-resource-import"
  "$bootstrap_dir/scripts/openhouse-resource-manager"
)
for file in "${required[@]}"; do
  [[ -s "$file" ]] || { printf 'Missing install bundle input: %s\n' "$file" >&2; exit 1; }
done

mkdir -p "$stage/bootstrap" "$stage/resources" "$app_assets" "$native_assets"
cp -a "$bootstrap_dir/." "$stage/bootstrap/"
cp "$payload_dir/resource-set.json" "$stage/resources/resource-set.json"
while IFS=$'\t' read -r id version archive; do
  case "$id" in
    service-manager|wuyou|openhouse-web) source="$payload_dir/$archive" ;;
    *) source="$repo_dir/distribution/market-script-resources/resources/$id/$version/$archive" ;;
  esac
  [[ -s "$source" ]] || { printf 'Missing promoted resource archive: %s\n' "$source" >&2; exit 1; }
  gzip -t "$source"
  cp "$source" "$stage/resources/$archive"
done < <(jq -r '.resources[] | [.id, .version, .archive] | @tsv' "$payload_dir/resource-set.json")
chmod 755 \
  "$stage/bootstrap/scripts/wuxianpi-setup" \
  "$stage/bootstrap/scripts/wuxianpi-pre-tmux.sh" \
  "$stage/bootstrap/scripts/openhouse-resource-import" \
  "$stage/bootstrap/scripts/openhouse-resource-manager"

apk_version_code="$(sed -n 's/^openhouseVersionCode=//p' "$repo_dir/gradle.properties" | head -n 1)"
[[ "$apk_version_code" =~ ^[0-9]+$ ]] || { printf 'Invalid canonical APK versionCode\n' >&2; exit 1; }

python3 - "$payload_dir/resource-set.json" "$stage/bundle-manifest.json" "$apk_version_code" <<'PY'
import json
import pathlib
import sys

resource_set = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
manifest = {
    "schema": 2,
    "id": "openhouse-install-bundle",
    "bundleId": f"{resource_set['id']}-{resource_set['sequence']}",
    "apkVersionCode": int(sys.argv[3]),
    "format": "uncompressed-tar",
    "resourceSet": resource_set,
}
pathlib.Path(sys.argv[2]).write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY

temporary="$canonical_bundle.tmp"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -C "$stage" -cf "$temporary" .
mv -f "$temporary" "$canonical_bundle"
chmod 0644 "$canonical_bundle"
reproducible="$canonical_bundle.reproducible.$$"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -C "$stage" -cf "$reproducible" .
if ! cmp -s "$canonical_bundle" "$reproducible"; then
  rm -f "$reproducible"
  printf 'Install bundle generation is not reproducible\n' >&2
  exit 1
fi
rm -f "$reproducible"
bundle_sha="$(sha256sum "$canonical_bundle" | awk '{print $1}')"
bundle_size="$(wc -c < "$canonical_bundle" | tr -d '[:space:]')"

python3 - \
  "$payload_dir/resource-set.json" \
  "$canonical_index.tmp" \
  "$bundle_size" \
  "$apk_version_code" <<'PY'
import json
import pathlib
import sys

resource_set = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
metadata = {
    "schema": 2,
    "bundleId": f"{resource_set['id']}-{resource_set['sequence']}",
    "apkVersionCode": int(sys.argv[4]),
    "resourceSetVersion": resource_set["version"],
    "resourceSetSequence": resource_set["sequence"],
    "bundleAsset": "wuxianpi-install/openhouse-install-bundle.tar",
    "bundleSize": int(sys.argv[3]),
}
pathlib.Path(sys.argv[2]).write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY
mv -f "$canonical_index.tmp" "$canonical_index"
chmod 0644 "$canonical_index"

cp "$pre_tmux" "$native_assets/pre-tmux.sh"
chmod 755 "$native_assets/pre-tmux.sh"
install -m 0644 "$canonical_bundle" "$native_assets/$bundle_name"
install -m 0644 "$canonical_index" "$native_assets/$index_name"

printf 'OpenHouse install bundle generated: %s sha256=%s size=%s\n' \
  "$canonical_bundle" "$bundle_sha" "$bundle_size"
