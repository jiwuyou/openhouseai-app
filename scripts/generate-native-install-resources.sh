#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assets_dir="$repo_dir/native-app/src/main/assets/wuxianpi-install"
bootstrap_dir="$repo_dir/app/src/main/assets/smallphoneai/bootstrap"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"
pre_tmux="$bootstrap_dir/scripts/wuxianpi-pre-tmux.sh"
resource_assets="$repo_dir/native-app/src/main/assets/openhouse-resources-v2"
runtime_asset="$resource_assets/runtime-aarch64.tgz"
output="$assets_dir/resources.tar"
stage="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-native-resources.XXXXXX")"
trap 'rm -rf "$stage"' EXIT

"$repo_dir/scripts/generate-resource-set-v2.sh"

required=(
  "$pre_tmux"
  "$bootstrap_dir/bootstrap.sh"
  "$bootstrap_dir/scripts/wuxianpi-setup"
  "$payload_dir/manifest.json"
  "$payload_dir/payload-manifest.json"
  "$resource_assets/resource-set.json"
  "$runtime_asset"
)
for file in "${required[@]}"; do
  [[ -s "$file" ]] || { printf 'Missing Native install input: %s\n' "$file" >&2; exit 1; }
done

tar -tzf "$runtime_asset" >/dev/null \
  || { printf 'Native Runtime asset is not a readable gzip tar: %s\n' "$runtime_asset" >&2; exit 1; }
runtime_sha="$(sha256sum "$runtime_asset" | awk '{print $1}')"
runtime_size="$(wc -c < "$runtime_asset" | tr -d '[:space:]')"

mkdir -p "$assets_dir" "$stage/bootstrap" "$stage/product-payloads"
cp -a "$bootstrap_dir/." "$stage/bootstrap/"
cp "$bootstrap_dir/scripts/wuxianpi-setup" "$stage/bootstrap/wuxianpi-setup"
cp "$pre_tmux" "$stage/bootstrap/wuxianpi-pre-tmux.sh"
cp "$payload_dir/manifest.json" "$stage/product-payloads/manifest.json"
cp "$payload_dir/payload-manifest.json" "$stage/product-payloads/payload-manifest.json"
cp "$payload_dir/AI_UPDATE_GUIDE.md" "$stage/product-payloads/AI_UPDATE_GUIDE.md"

cp "$pre_tmux" "$assets_dir/pre-tmux.sh"
chmod 755 "$assets_dir/pre-tmux.sh"

cat > "$stage/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
RESOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
install -m 755 "$RESOURCE_DIR/bootstrap/scripts/wuxianpi-setup" "$PREFIX/bin/wuxianpi-setup"
install -m 755 "$RESOURCE_DIR/bootstrap/scripts/wuxianpi-pre-tmux.sh" "$PREFIX/bin/wuxianpi-pre-tmux.sh"
printf 'WuxianPi installer installed: %s/bin/wuxianpi-setup\n' "$PREFIX"
EOF
chmod 755 "$stage/install.sh"
chmod 755 "$stage/bootstrap/wuxianpi-setup" "$stage/bootstrap/wuxianpi-pre-tmux.sh"

bootstrap_sha="$(sha256sum "$stage/bootstrap/scripts/wuxianpi-setup" | awk '{print $1}')"
pre_tmux_sha="$(sha256sum "$stage/bootstrap/scripts/wuxianpi-pre-tmux.sh" | awk '{print $1}')"
printf '%s  %s\n' \
  "$bootstrap_sha" bootstrap/wuxianpi-setup \
  "$pre_tmux_sha" bootstrap/wuxianpi-pre-tmux.sh \
  > "$stage/SHA256SUMS"

printf '{"schema":4,"contents":["bootstrap","product-payloads"],"bundledPayloads":[],"resourceSetAsset":"openhouse-resources-v2/resource-set.json","runtimeAsset":"openhouse-resources-v2/runtime-aarch64.tgz","runtimeSha256":"%s","runtimeSize":%s,"excluded":["aionui","pi-web","market-payloads"]}\n' \
  "$runtime_sha" "$runtime_size" \
  > "$stage/install-manifest.json"
printf '{"nativeInstallResources":true}\n' > "$stage/.complete"

tmp_output="$output.tmp"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -C "$stage" -czf "$tmp_output" .
mv "$tmp_output" "$output"

printf 'Native install resources generated: %s (%s bytes)\n' "$output" "$(wc -c < "$output")"
