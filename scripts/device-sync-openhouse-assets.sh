#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSET_DIR="$REPO_DIR/app/src/main/assets"
TERMUX_BASH="/data/data/com.termux/files/usr/bin/bash"

usage() {
  cat >&2 <<'EOF'
Usage:
  scripts/device-sync-openhouse-assets.sh <adb-device-id>

Copies local OpenHouse runtime assets into the installed Termux app:
  $HOME/.smallphoneai-bootstrap
  $HOME/.smallphoneai-bootstrap/apk-assets/openhouse/product-payloads
  $HOME/.smallphoneai-bootstrap/apk-assets/openhouse/scripts-public
  $HOME/.smallphoneai-bootstrap/apk-assets/maintainer
EOF
}

log() {
  printf '[device-sync-assets] %s\n' "$*"
}

die() {
  log "ERROR: $*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || die "missing required command: $1"
}

device="${1:-}"
if [ "$device" = "-h" ] || [ "$device" = "--help" ]; then
  usage
  exit 0
fi
if [ -z "$device" ]; then
  usage
  exit 2
fi

need_cmd adb
need_cmd tar

[ -d "$ASSET_DIR/smallphoneai/bootstrap" ] || die "missing bootstrap assets: $ASSET_DIR/smallphoneai/bootstrap"
[ -d "$ASSET_DIR/openhouse/product-payloads" ] || die "missing product payload assets: $ASSET_DIR/openhouse/product-payloads"
[ -d "$ASSET_DIR/openhouse/scripts-public" ] || die "missing scripts-public assets: $ASSET_DIR/openhouse/scripts-public"
[ -d "$ASSET_DIR/maintainer" ] || die "missing maintainer assets: $ASSET_DIR/maintainer"

adb_cmd() {
  adb -s "$device" "$@"
}

if ! adb_cmd get-state >/dev/null 2>&1; then
  die "adb device is not available: $device"
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-assets.XXXXXX")"
remote_tmp="/data/local/tmp/openhouse-assets-$(date +%s)-$$"

cleanup() {
  rm -rf "$tmp_dir" >/dev/null 2>&1 || true
  adb -s "$device" shell rm -rf "$remote_tmp" >/dev/null 2>&1 || true
}
trap cleanup EXIT

make_archive() {
  local name="$1"
  local source_dir="$2"
  local target="$tmp_dir/$name.tar"
  log "packing $source_dir"
  tar -C "$source_dir" -cf "$target" .
}

make_archive bootstrap "$ASSET_DIR/smallphoneai/bootstrap"
make_archive product-payloads "$ASSET_DIR/openhouse/product-payloads"
make_archive scripts-public "$ASSET_DIR/openhouse/scripts-public"
make_archive maintainer "$ASSET_DIR/maintainer"

log "pushing archives to $device:$remote_tmp"
adb_cmd shell mkdir -p "$remote_tmp"
adb_cmd push "$tmp_dir/bootstrap.tar" "$remote_tmp/bootstrap.tar" >/dev/null
adb_cmd push "$tmp_dir/product-payloads.tar" "$remote_tmp/product-payloads.tar" >/dev/null
adb_cmd push "$tmp_dir/scripts-public.tar" "$remote_tmp/scripts-public.tar" >/dev/null
adb_cmd push "$tmp_dir/maintainer.tar" "$remote_tmp/maintainer.tar" >/dev/null
adb_cmd shell chmod -R a+rX "$remote_tmp"

log "extracting as com.termux"
adb_cmd shell run-as com.termux "$TERMUX_BASH" -s -- "$remote_tmp" <<'REMOTE'
set -euo pipefail

remote_tmp="${1:?missing remote tmp}"
export PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
if [ -d "/data/data/com.termux/files/home" ]; then
  export HOME="/data/data/com.termux/files/home"
else
  export HOME="${HOME:-/data/data/com.termux/files/home}"
fi
export PATH="$PREFIX/bin:/system/bin:${PATH:-}"
export LD_LIBRARY_PATH="$PREFIX/lib:${LD_LIBRARY_PATH:-}"
case "${TMPDIR:-}" in
  ""|/tmp|/tmp/*|/var/tmp|/var/tmp/*)
    export TMPDIR="$PREFIX/tmp"
    ;;
  *)
    export TMPDIR
    ;;
esac

bootstrap="$HOME/.smallphoneai-bootstrap"
payload_dir="$bootstrap/apk-assets/openhouse/product-payloads"
scripts_public_dir="$bootstrap/apk-assets/openhouse/scripts-public"
maintainer_dir="$bootstrap/apk-assets/maintainer"

if ! mkdir -p "$TMPDIR" 2>/dev/null; then
  export TMPDIR="$HOME/.tmp"
  mkdir -p "$TMPDIR"
fi
mkdir -p "$bootstrap" "$payload_dir" "$scripts_public_dir" "$maintainer_dir"

rm -rf "$bootstrap/scripts" "$bootstrap/schemas" "$bootstrap/subjects.d"
rm -f "$bootstrap/bootstrap.sh" "$bootstrap/README.md" "$bootstrap/openhouseai-manifest.json" "$bootstrap/.gitignore"
tar -xf "$remote_tmp/bootstrap.tar" -C "$bootstrap"

rm -rf "$payload_dir" "$scripts_public_dir" "$maintainer_dir"
mkdir -p "$payload_dir" "$scripts_public_dir" "$maintainer_dir"
tar -xf "$remote_tmp/product-payloads.tar" -C "$payload_dir"
tar -xf "$remote_tmp/scripts-public.tar" -C "$scripts_public_dir"
tar -xf "$remote_tmp/maintainer.tar" -C "$maintainer_dir"

chmod 755 "$bootstrap/bootstrap.sh" 2>/dev/null || true
find "$bootstrap/scripts" "$maintainer_dir" -type f -name '*.sh' -exec chmod 755 {} + 2>/dev/null || true

{
  printf 'source=desktop-sync\n'
  printf 'synced_at_epoch=%s\n' "$(date +%s 2>/dev/null || printf 0)"
  printf 'bootstrap=%s\n' "$bootstrap"
  printf 'payload_dir=%s\n' "$payload_dir"
} > "$bootstrap/.desktop-sync-marker"

printf '[device-sync-assets] synced bootstrap: %s\n' "$bootstrap"
printf '[device-sync-assets] synced payloads: %s\n' "$payload_dir"
REMOTE

log "done"
