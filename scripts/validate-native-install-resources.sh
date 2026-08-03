#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assets_dir="$repo_dir/native-app/src/main/assets/wuxianpi-install"
archive="$assets_dir/resources.tar"
pre_tmux="$assets_dir/pre-tmux.sh"

[[ -s "$archive" ]] || { printf 'Missing Native install archive: %s\n' "$archive" >&2; exit 1; }
[[ -s "$pre_tmux" ]] || { printf 'Missing Native pre-tmux asset: %s\n' "$pre_tmux" >&2; exit 1; }

members="$(tar -tf "$archive")"
for required in \
  ./install.sh \
  ./install-manifest.json \
  ./SHA256SUMS \
  ./bootstrap/bootstrap.sh \
  ./bootstrap/wuxianpi-setup \
  ./bootstrap/wuxianpi-pre-tmux.sh \
  ./bootstrap/scripts/wuxianpi-setup \
  ./bootstrap/scripts/wuxianpi-pre-tmux.sh \
  ./product-payloads/manifest.json \
  ./product-payloads/payload-manifest.json \
  ./product-payloads/service-manager.tar; do
  grep -Fxq "$required" <<<"$members" \
    || { printf 'Native install archive is missing %s\n' "$required" >&2; exit 1; }
done
if grep -Eiq 'aionui|pi-web\.tar|wuxianpi-native-install\.tar' <<<"$members"; then
  printf 'Native install archive contains an excluded optional payload\n' >&2
  exit 1
fi
if grep -Eq '(^|/)pi-runtime\.tar$' <<<"$members"; then
  printf 'Native install archive must not duplicate the separately staged WuxianPi runtime\n' >&2
  exit 1
fi

tmp="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-native-validate.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
tar -xf "$archive" -C "$tmp"
(cd "$tmp" && sha256sum -c SHA256SUMS)
bash -n "$pre_tmux"
bash -n "$tmp/bootstrap/wuxianpi-setup"
bash -n "$tmp/bootstrap/wuxianpi-pre-tmux.sh"
if grep -Fq 'tmux new-session' "$tmp/bootstrap/wuxianpi-setup"; then
  printf 'wuxianpi-setup must run in the termux_exec_command session, not create nested tmux\n' >&2
  exit 1
fi

printf 'Native install resources validated: %s (%s bytes)\n' "$archive" "$(wc -c < "$archive")"
