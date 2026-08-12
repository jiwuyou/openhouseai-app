#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"

"$repo_dir/scripts/generate-resource-set-v2.sh" --check
jq -e '
  .schema == 2 and .id == "openhouse-core-stack" and
  .version == "2026.08.12.1" and .sequence == 2026081201 and
  .abi == "arm64-v8a" and .minApkVersionCode == 126 and
  ([.resources[].id] | sort) == [
    "openhouse-control-plane", "openhouse-runtime", "openhouse-web", "service-manager", "wuyou"
  ]
' "$payload_dir/resource-set.json" >/dev/null

while IFS=$'\t' read -r id digest archive; do
  actual="$(sha256sum "$payload_dir/$archive" | awk '{print $1}')"
  [[ "$actual" == "$digest" ]] || { printf 'Resource set digest mismatch: %s\n' "$id" >&2; exit 1; }
done < <(jq -r '.resources[] | [.id,.sha256,(if .id == "openhouse-runtime" then "runtime-aarch64.tgz" else (.id + ".tgz") end)] | @tsv' "$payload_dir/resource-set.json")

"$repo_dir/scripts/generate-native-install-resources.sh"
"$repo_dir/scripts/validate-openhouse-install-bundle.sh"

members="$(tar -tzf "$payload_dir/openhouse-control-plane.tgz" | sed 's#^\./##')"
for required in control-plane-manifest.json _termux-services-env.sh start-control-plane-termux-native.sh repair-control-plane-termux-native.sh inspect-control-plane-termux-native.sh; do
  grep -Fxq "$required" <<<"$members" || { printf 'Control-plane archive is missing %s\n' "$required" >&2; exit 1; }
done

printf 'Resource set V2 contract passed\n'
