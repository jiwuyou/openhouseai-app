#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
importer="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-import"
manager="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/openhouse-resource-manager"
setup="$repo_dir/app/src/main/assets/smallphoneai/bootstrap/scripts/wuxianpi-setup"
work="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-bundle-import-test.XXXXXX")"
trap 'rm -rf -- "$work"' EXIT

export HOME="$work/home"
export PREFIX="$work/prefix"
export PATH="$PREFIX/bin:$PATH"
export OPENHOUSEAI_DISABLE_NETWORK=1
export OPENHOUSEAI_SKIP_LIVE_HEALTH=1
mkdir -p "$HOME" "$PREFIX/bin" "$work/bundle/bootstrap/scripts" "$work/bundle/resources"
ln -s "$(command -v bash)" "$PREFIX/bin/bash"

make_archive() {
  local id="$1" archive="$2" root
  root="$work/resource-$id"
  rm -rf "$root"
  mkdir -p "$root/scripts"
  case "$id" in
    service-manager)
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/service-manager"
      cat >"$root/scripts/install.sh" <<'EOF'
#!/usr/bin/env sh
set -eu
install -m 755 "$1" "$PREFIX/bin/service-manager"
mkdir -p "$PREFIX/var/service/service-manager"
EOF
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/check.sh"
      ;;
    openhouse-control-plane)
      rm -rf "$root/scripts"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/start-control-plane-termux-native.sh"
      ;;
    openhouse-runtime)
      mkdir -p "$root/bin"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/install.sh"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/install.sh"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/bin/wuxianpi-node"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/check.sh"
      ;;
    wuyou)
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/wuyou"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/install.sh"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/check.sh"
      ;;
    openhouse-web)
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/install.sh"
      printf '#!/usr/bin/env sh\nexit 0\n' >"$root/scripts/check.sh"
      ;;
  esac
  chmod 755 "$root"/* 2>/dev/null || true
  chmod 755 "$root"/scripts/* 2>/dev/null || true
  tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -C "$root" -cf - . \
    | gzip -n >"$work/bundle/resources/$archive"
}

make_archive service-manager service-manager.tgz
make_archive openhouse-control-plane openhouse-control-plane.tgz
make_archive openhouse-runtime runtime-aarch64.tgz
make_archive wuyou wuyou.tgz
make_archive openhouse-web openhouse-web.tgz

jq -n \
  --arg sm "$(sha256sum "$work/bundle/resources/service-manager.tgz" | awk '{print $1}')" \
  --arg control "$(sha256sum "$work/bundle/resources/openhouse-control-plane.tgz" | awk '{print $1}')" \
  --arg runtime "$(sha256sum "$work/bundle/resources/runtime-aarch64.tgz" | awk '{print $1}')" \
  --arg wuyou "$(sha256sum "$work/bundle/resources/wuyou.tgz" | awk '{print $1}')" \
  --arg web "$(sha256sum "$work/bundle/resources/openhouse-web.tgz" | awk '{print $1}')" \
  '{schema:2,id:"openhouse-core-stack",version:"test.1",sequence:9999999999,abi:"arm64-v8a",minApkVersionCode:126,resources:[
    {id:"service-manager",version:"test.1",sha256:$sm},
    {id:"openhouse-control-plane",version:"test.1",sha256:$control},
    {id:"openhouse-runtime",version:"test.1",sha256:$runtime},
    {id:"wuyou",version:"test.1",sha256:$wuyou},
    {id:"openhouse-web",version:"test.1",sha256:$web}
  ]}' >"$work/bundle/resources/resource-set.json"

cp "$importer" "$work/bundle/bootstrap/scripts/openhouse-resource-import"
cp "$manager" "$work/bundle/bootstrap/scripts/openhouse-resource-manager"
cp "$setup" "$work/bundle/bootstrap/scripts/wuxianpi-setup"
printf '#!/usr/bin/env bash\nexit 0\n' >"$work/bundle/bootstrap/bootstrap.sh"
resource_set="$(cat "$work/bundle/resources/resource-set.json")"
jq -n --argjson resourceSet "$resource_set" \
  '{schema:1,id:"openhouse-install-bundle",format:"uncompressed-tar",resourceSet:$resourceSet,contents:["bootstrap","resources"]}' \
  >"$work/bundle/bundle-manifest.json"
(
  cd "$work/bundle"
  find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum >SHA256SUMS
)
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner -C "$work/bundle" \
  -cf "$work/openhouse-install-bundle.tar" .

offer_id=0123456789abcdef01234567
inbox="$HOME/.local/share/openhouseai/apk-resource-inbox/$offer_id"
mkdir -p "$inbox"
cp "$work/openhouse-install-bundle.tar" "$inbox/openhouse-install-bundle.tar"
bundle_sha="$(sha256sum "$inbox/openhouse-install-bundle.tar" | awk '{print $1}')"
bundle_size="$(stat -c '%s' "$inbox/openhouse-install-bundle.tar")"
jq -n --arg offerId "$offer_id" --arg bundleSha256 "$bundle_sha" --argjson bundleSize "$bundle_size" \
  --argjson resourceSet "$resource_set" \
  '{schema:1,offerId:$offerId,apkVersionCode:126,bundleFile:"openhouse-install-bundle.tar",bundleSha256:$bundleSha256,bundleSize:$bundleSize,resourceSet:$resourceSet}' \
  >"$inbox/offer.json"

if bash "$importer" "$inbox" >"$work/missing-ready.log" 2>&1; then
  printf 'Importer consumed an offer without .ready\n' >&2
  exit 1
fi
grep -Fq 'offer is not ready' "$work/missing-ready.log"

printf x >"$inbox/.ready"
if bash "$importer" "$inbox" >"$work/nonempty-ready.log" 2>&1; then
  printf 'Importer consumed an offer with a non-empty .ready marker\n' >&2
  exit 1
fi
grep -Fq 'offer is not ready' "$work/nonempty-ready.log"

: >"$inbox/.ready"
mv "$inbox/openhouse-install-bundle.tar" "$work/real-install-bundle.tar"
ln -s "$work/real-install-bundle.tar" "$inbox/openhouse-install-bundle.tar"
if bash "$importer" "$inbox" >"$work/symlink-bundle.log" 2>&1; then
  printf 'Importer consumed a symlinked install bundle\n' >&2
  exit 1
fi
grep -Fq 'offer is incomplete' "$work/symlink-bundle.log"
rm "$inbox/openhouse-install-bundle.tar"
cp "$work/real-install-bundle.tar" "$inbox/openhouse-install-bundle.tar"

printf x >>"$inbox/openhouse-install-bundle.tar"
if bash "$importer" "$inbox" >"$work/corrupt.log" 2>&1; then
  printf 'Importer consumed a corrupt bundle\n' >&2
  exit 1
fi
grep -Fq 'size verification failed' "$work/corrupt.log"

cp "$work/openhouse-install-bundle.tar" "$inbox/openhouse-install-bundle.tar"
jq '.resourceSet.sequence += 1' "$inbox/offer.json" >"$inbox/offer.json.tmp"
mv "$inbox/offer.json.tmp" "$inbox/offer.json"
if bash "$importer" "$inbox" >"$work/resource-set-mismatch.log" 2>&1; then
  printf 'Importer consumed an offer whose resource set does not match the bundle\n' >&2
  exit 1
fi
grep -Fq 'does not match the install bundle' "$work/resource-set-mismatch.log"
jq '.resourceSet.sequence -= 1' "$inbox/offer.json" >"$inbox/offer.json.tmp"
mv "$inbox/offer.json.tmp" "$inbox/offer.json"

bash "$importer" "$inbox" >"$work/import.log"
[[ -f "$HOME/.local/share/openhouseai/resource-manager/receipts/apk-offers/$offer_id.json" ]]
[[ -f "$HOME/.local/share/openhouseai/resource-manager/installed-set.json" ]]
[[ -f "$inbox/.imported" ]]
[[ ! -e "$inbox/.consumed" ]]
jq -e '.delivery == "ready" and .content == "installed" and .activation == "pending" and .status == "pending"' \
  "$HOME/.local/share/openhouseai/resource-manager/receipts/apk-offers/$offer_id.json" >/dev/null
[[ ! -e "$HOME/.local/share/wuxianpi/plugins/wuxianpi.resource-update" ]]
bash "$importer" "$inbox" >"$work/reimport.log"
grep -Fq 'offer content already installed' "$work/reimport.log"
bash "$PREFIX/bin/openhouse-resource-manager" verify >/dev/null

if rg -n 'start_control_plane|verify_live_stack|register_resources|/api/v1|20765|sv up|service-daemon' "$PREFIX/bin/openhouse-resource-manager"; then
  printf 'Content manager still owns runtime activation\n' >&2
  exit 1
fi

printf 'OpenHouse install bundle import contract passed\n'
