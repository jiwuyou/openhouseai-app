#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bootstrap_dir="$repo_dir/app/src/main/assets/smallphoneai/bootstrap"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"
app_assets="$repo_dir/app/src/main/assets/wuxianpi-install"
native_assets="$repo_dir/native-app/src/main/assets/wuxianpi-install"
pre_tmux="$bootstrap_dir/scripts/wuxianpi-pre-tmux.sh"
bundle_name="openhouse-install-bundle.tar"
metadata_name="openhouse-install-bundle.json"
canonical_bundle="$app_assets/$bundle_name"
canonical_metadata="$app_assets/$metadata_name"
stage="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-install-bundle.XXXXXX")"
trap 'rm -rf -- "$stage"' EXIT

"$repo_dir/scripts/generate-resource-set-v2.sh"

resource_archives=(
  service-manager.tgz
  openhouse-control-plane.tgz
  runtime-aarch64.tgz
  wuyou.tgz
  openhouse-web.tgz
)
required=(
  "$pre_tmux"
  "$bootstrap_dir/bootstrap.sh"
  "$bootstrap_dir/scripts/wuxianpi-setup"
  "$bootstrap_dir/scripts/openhouse-resource-import"
  "$bootstrap_dir/scripts/openhouse-resource-manager"
  "$payload_dir/resource-set.json"
)
for archive in "${resource_archives[@]}"; do
  required+=("$payload_dir/$archive")
done
for file in "${required[@]}"; do
  [[ -s "$file" ]] || { printf 'Missing install bundle input: %s\n' "$file" >&2; exit 1; }
done

mkdir -p "$stage/bootstrap" "$stage/resources" "$app_assets" "$native_assets"
cp -a "$bootstrap_dir/." "$stage/bootstrap/"
cp "$payload_dir/resource-set.json" "$stage/resources/resource-set.json"
for archive in "${resource_archives[@]}"; do
  gzip -t "$payload_dir/$archive"
  cp "$payload_dir/$archive" "$stage/resources/$archive"
done
chmod 755 \
  "$stage/bootstrap/scripts/wuxianpi-setup" \
  "$stage/bootstrap/scripts/wuxianpi-pre-tmux.sh" \
  "$stage/bootstrap/scripts/openhouse-resource-import" \
  "$stage/bootstrap/scripts/openhouse-resource-manager"

python3 - "$payload_dir/resource-set.json" "$stage/bundle-manifest.json" <<'PY'
import json
import pathlib
import sys

resource_set = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
manifest = {
    "schema": 1,
    "id": "openhouse-install-bundle",
    "format": "uncompressed-tar",
    "resourceSet": resource_set,
    "contents": ["bootstrap", "resources"],
}
pathlib.Path(sys.argv[2]).write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY

(
  cd "$stage"
  find . -type f ! -name SHA256SUMS -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum > SHA256SUMS
)

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
  "$canonical_metadata.tmp" \
  "$bundle_name" \
  "$bundle_sha" \
  "$bundle_size" <<'PY'
import json
import pathlib
import sys

resource_set = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
metadata = {
    "schema": 1,
    "bundleAsset": f"wuxianpi-install/{sys.argv[3]}",
    "bundleFile": sys.argv[3],
    "bundleSha256": sys.argv[4],
    "bundleSize": int(sys.argv[5]),
    "resourceSet": resource_set,
}
pathlib.Path(sys.argv[2]).write_text(
    json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
PY
mv -f "$canonical_metadata.tmp" "$canonical_metadata"
chmod 0644 "$canonical_metadata"

cp "$pre_tmux" "$native_assets/pre-tmux.sh"
chmod 755 "$native_assets/pre-tmux.sh"
install -m 0644 "$canonical_bundle" "$native_assets/$bundle_name"
install -m 0644 "$canonical_metadata" "$native_assets/$metadata_name"

printf 'OpenHouse install bundle generated: %s sha256=%s size=%s\n' \
  "$canonical_bundle" "$bundle_sha" "$bundle_size"
