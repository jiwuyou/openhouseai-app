#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
importer="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-import"
manager="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-manager"
setup="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
work="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-bundle-import-test.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT

full_home="$work/full-home"
full_prefix="$work/full-prefix"
full_bundle="$work/full-bundle"
mkdir -p "$full_home" "$full_prefix/bin" "$full_bundle"
ln -s "$(command -v bash)" "$full_prefix/bin/bash"
tar -xf "$repo_dir/app/src/main/assets/wuxianpi-install/openhouse-install-bundle.tar" -C "$full_bundle"
printf '%s\n' '#!/data/data/com.termux/files/usr/bin/bash' 'exit 0' \
  >"$full_bundle/bootstrap/scripts/openhouse-resource-manager"
chmod 700 "$full_bundle/bootstrap/scripts/openhouse-resource-manager"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  -C "$full_bundle" -cf "$work/full-install.tar" .
full_offer="full-$(jq -r '.apkVersionCode' "$full_bundle/bundle-manifest.json")-$(jq -r '.resourceSet.sequence' "$full_bundle/bundle-manifest.json")"
full_inbox="$full_home/.local/share/openhouseai/apk-resource-inbox/$full_offer"
mkdir -p "$full_inbox"
cp "$work/full-install.tar" "$full_inbox/openhouse-install-bundle.tar"
: >"$full_inbox/.ready"
HOME="$full_home" PREFIX="$full_prefix" bash "$importer" "$full_inbox" >"$work/full-import.log" 2>&1
grep -Fq 'local delivery resources available: 20/20' "$work/full-import.log"
! grep -Fq 'Cannot index boolean with string' "$work/full-import.log"
jq -e '.offerId == $offer and .content == "installed"' --arg offer "$full_offer" \
  "$full_home/.local/share/openhouseai/resource-manager/receipts/apk-offers/$full_offer.json" >/dev/null

export HOME="$work/home"
export PREFIX="$work/prefix"
export PATH="$PREFIX/bin:$PATH"
mkdir -p "$HOME" "$PREFIX/bin" "$PREFIX/tmp" "$work/bundle/bootstrap/scripts" "$work/bundle/resources"
ln -s "$(command -v bash)" "$PREFIX/bin/bash"

make_resource() {
  local id="$1" archive="$2" root
  root="$work/$id"
  mkdir -p "$root/scripts"
  printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/check.sh"
  printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/install.sh"
  printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/register-service.sh"
  case "$id" in
    service-manager)
      printf '#!/usr/bin/env sh\n[ "${1:-}" = --version ] && exit 0\nexit 0\n' >"$root/service-manager"
      printf '#!/usr/bin/env sh\nset -eu\ninstall -m 755 "$1" "$PREFIX/bin/service-manager"\n' >"$root/scripts/install.sh" ;;
    openhouse-control-plane)
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/start-control-plane-termux-native.sh" ;;
    openhouse-runtime)
      mkdir -p "$root/bin"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/install.sh"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/bin/wuxianpi-node" ;;
    wuyou) printf '#!/usr/bin/env sh\nexit 0\n' >"$root/wuyou" ;;
    openhouse-web) : ;;
  esac
  find "$root" -type f -exec chmod 755 {} +
  tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -C "$root" -cf - . | gzip -n >"$work/bundle/resources/$archive"
}

make_resource service-manager service-manager.tgz
make_resource openhouse-control-plane openhouse-control-plane.tgz
make_resource openhouse-runtime runtime-aarch64.tgz
make_resource wuyou wuyou.tgz
make_resource openhouse-web openhouse-web.tgz

jq -n \
  --argjson sm "$(stat -c '%s' "$work/bundle/resources/service-manager.tgz")" \
  --argjson control "$(stat -c '%s' "$work/bundle/resources/openhouse-control-plane.tgz")" \
  --argjson runtime "$(stat -c '%s' "$work/bundle/resources/runtime-aarch64.tgz")" \
  --argjson wuyou "$(stat -c '%s' "$work/bundle/resources/wuyou.tgz")" \
  --argjson web "$(stat -c '%s' "$work/bundle/resources/openhouse-web.tgz")" \
  '{schema:2,id:"openhouse-core-stack",version:"test.1",sequence:2026081201,abi:"arm64-v8a",minApkVersionCode:126,resources:[
    {id:"service-manager",version:"test.1",archive:"service-manager.tgz",size:$sm,sha256:("0"*64)},
    {id:"openhouse-control-plane",version:"test.1",archive:"openhouse-control-plane.tgz",size:$control,sha256:("0"*64)},
    {id:"openhouse-runtime",version:"test.1",archive:"runtime-aarch64.tgz",size:$runtime,sha256:("0"*64)},
    {id:"wuyou",version:"test.1",archive:"wuyou.tgz",size:$wuyou,sha256:("0"*64)},
    {id:"openhouse-web",version:"test.1",archive:"openhouse-web.tgz",size:$web,sha256:("0"*64)},
    {id:"openhouse-resource-manager",version:"test.1",archive:"openhouse-resource-manager.tgz",size:123,sha256:("1"*64)}]}' \
  >"$work/bundle/resources/resource-set.json"
jq -n --slurpfile set "$work/bundle/resources/resource-set.json" \
  '{schema:2,id:"openhouse-install-bundle",bundleId:"openhouse-core-stack-2026081201",apkVersionCode:126,format:"uncompressed-tar",resourceSet:$set[0]}' \
  >"$work/bundle/bundle-manifest.json"
cp "$importer" "$manager" "$setup" "$work/bundle/bootstrap/scripts/"
printf '#!/usr/bin/env bash\nexit 0\n' >"$work/bundle/bootstrap/bootstrap.sh"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -C "$work/bundle" -cf "$work/install.tar" .

offer_id="com.wuxianpi-126-2026081201"
inbox="$HOME/.local/share/openhouseai/apk-resource-inbox/$offer_id"
mkdir -p "$inbox"
cp "$work/install.tar" "$inbox/openhouse-install-bundle.tar"
if bash "$importer" "$inbox" >"$work/not-ready.log" 2>&1; then
  printf 'Importer consumed a bundle without .ready\n' >&2; exit 1
fi
grep -Fq 'not ready' "$work/not-ready.log"
: >"$inbox/.ready"

fakebin="$work/no-sha-bin"
mkdir -p "$fakebin"
for command in bash sh jq tar gzip find awk readlink stat flock sort sed install mv cp mkdir chmod date basename dirname cat tee wc tr tac ln npm node uname head mktemp rm; do
  path="$(command -v "$command" 2>/dev/null || true)"
  [[ -z "$path" ]] || ln -s "$path" "$fakebin/$command"
done
PATH="$fakebin" "$fakebin/bash" "$importer" "$inbox" >"$work/import.log"
grep -Fq 'local delivery resources available: 5/6' "$work/import.log"

receipt="$HOME/.local/share/openhouseai/resource-manager/receipts/apk-offers/$offer_id.json"
[[ -f "$receipt" && ! -e "$inbox/offer.json" ]]
[[ ! -e "$inbox/.imported" ]]
jq -e '.schema == 3 and .delivery == "ready" and .content == "installed" and .activation == "pending"' "$receipt" >/dev/null
jq -e '.schema == 4 and .sequence == 2026081201 and (.resources | length) == 5' \
  "$HOME/.local/share/openhouseai/resource-manager/installed-set.json" >/dev/null
jq -e 'all(.resources[]; .id != "openhouse-resource-manager")' \
  "$HOME/.local/share/openhouseai/resource-manager/installed-set.json" >/dev/null
for id in service-manager openhouse-control-plane openhouse-runtime wuyou openhouse-web; do
  jq -e '.schema == 4 and .content == "installed" and (.archiveSha256 | length) == 64' \
    "$HOME/.local/share/openhouseai/resource-manager/receipts/resources/$id.json" >/dev/null
  [[ -L "$HOME/.local/share/openhouseai/resources/$id/current" ]]
done
jq '.version = "test.partial" | .sequence = 2026081202 |
    .resources |= map(select(.id == "service-manager"))' \
  "$work/bundle/resources/resource-set.json" >"$work/partial-set.json"
PATH="$fakebin" \
OPENHOUSEAI_RESOURCE_SET_FILE="$work/partial-set.json" \
OPENHOUSEAI_RESOURCE_SOURCE_DIR="$work/bundle/resources" \
  "$fakebin/bash" "$PREFIX/bin/openhouse-resource-manager" apply >"$work/partial-apply.log"
jq -e '.schema == 4 and .sequence == 2026081202 and (.resources | length) == 5 and
  any(.resources[]; .id == "openhouse-web")' \
  "$HOME/.local/share/openhouseai/resource-manager/installed-set.json" >/dev/null
PATH="$fakebin" "$fakebin/bash" "$PREFIX/bin/openhouse-resource-import" "$inbox" >"$work/reimport.log"
grep -Fq 'content already installed' "$work/reimport.log"
! rg -n 'sha256sum|SHA256SUMS|offer\.json|tree_sha|installedManifestSha256|archiveSha256' \
  "$PREFIX/bin/openhouse-resource-import"
grep -Fq 'fetch_market_resources()' "$PREFIX/bin/openhouse-resource-manager"
grep -Fq 'sha256sum "$temporary"' "$PREFIX/bin/openhouse-resource-manager"
printf 'OpenHouse install bundle import contract passed\n'
