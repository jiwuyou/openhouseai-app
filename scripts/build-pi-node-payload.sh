#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
wuxianpi_source="${WUXIANPI_SOURCE_DIR:-$repo_dir/../../wuxianpi}"
build_host="${WUXIANPI_RELEASE_BUILD_SSH:-phonetermux}"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"
output="$payload_dir/runtime-aarch64.tgz"
stage="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-wuxianpi-payload.XXXXXX")"
remote_dir=""

cleanup() {
  rm -rf -- "$stage"
  if [[ -n "$remote_dir" && "${WUXIANPI_KEEP_REMOTE_BUILD:-0}" != 1 ]]; then
    ssh "$build_host" "rm -rf -- '$remote_dir'" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

log() { printf '[wuxianpi payload] %s\n' "$*"; }
die() { printf '[wuxianpi payload] ERROR: %s\n' "$*" >&2; exit 1; }

[[ -x "$wuxianpi_source/packaging/termux/build-source-release.sh" ]] \
  || die 'missing WuxianPi source release builder'
[[ -f "$wuxianpi_source/runtime/wuxianpi-node/package-lock.json" ]] \
  || die 'missing WuxianPi Runtime package-lock.json'
[[ -f "$wuxianpi_source/apps/web/package-lock.json" ]] \
  || die 'missing WuxianPi Web package-lock.json'

runtime_version="${WUXIANPI_RUNTIME_VERSION:-0.2.0}"
remote_dir="$(ssh "$build_host" 'base="${TMPDIR:-$HOME/.cache}"; mkdir -p "$base"; mktemp -d "$base/openhouse-wuxianpi-release.XXXXXX"')"

log "copying WuxianPi source to ARM64 Termux builder"
tar --exclude='.git' --exclude='node_modules' --exclude='dist' --exclude='.next' \
  --exclude='release/dist' --exclude='coverage' --exclude='*.tsbuildinfo' \
  -cf - -C "$wuxianpi_source" . \
  | ssh "$build_host" "mkdir -p '$remote_dir/source'; tar -xf - -C '$remote_dir/source'"

log "building the official minimal ARM64 release"
ssh "$build_host" "
  set -eu
  chmod 755 '$remote_dir/source/packaging/termux/'*.sh '$remote_dir/source/packaging/termux/bundle/'*.sh
  '$remote_dir/source/packaging/termux/build-source-release.sh' \
    --version '$runtime_version' \
    --output '$remote_dir/release'
"

install_archive="wuxianpi-install-arm64-$runtime_version.tar.zst"
ssh "$build_host" "test -s '$remote_dir/release/$install_archive'"
mkdir -p "$stage/resource" "$stage/resource/scripts" "$stage/resource/metadata"
ssh "$build_host" "tar -cf - -C '$remote_dir/release' runtime-manifest.json -C '$remote_dir/source' packaging/termux/README.md" \
  | tar -xf - -C "$stage/resource/metadata"
ssh "$build_host" "zstd -q -dc '$remote_dir/release/$install_archive'" \
  | tar -xf - -C "$stage/resource"

cat >"$stage/resource/scripts/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
root=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
command -v zstd >/dev/null
WUXIANPI_INSTALL_ROOT="${WUXIANPI_INSTALL_ROOT:-$HOME/.local/share/wuxianpi}" \
  "$root/scripts/install-release.sh" "$root"
EOF

cat >"$stage/resource/scripts/check.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
product_root=${WUXIANPI_INSTALL_ROOT:-$HOME/.local/share/wuxianpi}
command -v node >/dev/null
command -v zstd >/dev/null
test -x "$HOME/.local/bin/wuxianpi"
test -x "$HOME/.local/bin/wuxianpi-node"
test -x "$HOME/.local/bin/wuxianpi-node-start"
test -s "$product_root/runtime/dist/index.js"
test -s "$product_root/runtime/builtin-packages/task-manager/wuxianpi-package.json"
test -s "$product_root/web/index.html"
test -s "$product_root/base/node_modules/@earendil-works/pi-coding-agent/package.json"
node -e 'const p=require(process.argv[1]);if(p.version!=="0.80.10")process.exit(1)' \
  "$product_root/base/node_modules/@earendil-works/pi-coding-agent/package.json"
printf 'ok: official WuxianPi ARM64 release is installed\n'
EOF

cat >"$stage/resource/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
root=$(CDPATH= cd "$(dirname "$0")" && pwd)
"$root/scripts/install.sh"
"$root/scripts/register-service.sh"
EOF

chmod 0755 "$stage/resource/install.sh" "$stage/resource/bin/"* "$stage/resource/scripts/"*.sh
python3 - "$stage/resource/metadata/build.json" "$wuxianpi_source" "$runtime_version" <<'PY'
import json
import pathlib
import subprocess
import sys

path = pathlib.Path(sys.argv[1])
source = pathlib.Path(sys.argv[2])
version = sys.argv[3]
commit = subprocess.check_output(["git", "-C", source, "rev-parse", "HEAD"], text=True).strip()
dirty = bool(subprocess.check_output(
    ["git", "-C", source, "status", "--porcelain", "--untracked-files=all"],
    text=True,
).strip())
path.write_text(json.dumps({
    "schema": 3,
    "runtime": "wuxianpi-node",
    "version": version,
    "sourceCommit": commit,
    "sourceDirty": dirty,
    "sourceRepo": "https://github.com/jiwuyou/wuxianpi.git",
    "releaseFormat": "wuxianpi-install-arm64",
    "protocol": "wuxianpi-sdk-v1",
    "piSdkPackage": "@earendil-works/pi-coding-agent",
    "piSdkVersion": "0.80.10",
    "node": ">=22.19.0",
}, indent=2) + "\n", encoding="utf-8")
PY

gzip -n < <(tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner \
  -cf - -C "$stage/resource" .) >"$output.tmp"
mv "$output.tmp" "$output"
chmod 0644 "$output"
rm -f "$payload_dir/pi-runtime.tar"

python3 - "$payload_dir/manifest.json" "$payload_dir/payload-manifest.json" "$output" "$runtime_version" "$wuxianpi_source" <<'PY'
import hashlib
import json
import pathlib
import subprocess
import sys

manifest_path, payload_manifest_path, archive = map(pathlib.Path, sys.argv[1:4])
version = sys.argv[4]
source = pathlib.Path(sys.argv[5])
source_commit = subprocess.check_output(["git", "-C", source, "rev-parse", "HEAD"], text=True).strip()
source_dirty = bool(subprocess.check_output(
    ["git", "-C", source, "status", "--porcelain", "--untracked-files=all"],
    text=True,
).strip())
data = archive.read_bytes()
for path, key in ((manifest_path, "components"), (payload_manifest_path, "payloads")):
    doc = json.loads(path.read_text(encoding="utf-8"))
    entry = next(item for item in doc[key] if item.get("id") == "pi-agent")
    entry.clear()
    entry.update({
        "id": "pi-agent", "archive": archive.name, "targetDir": "pi-runtime",
        "compression": "gzip", "abi": "arm64-v8a",
        "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
        "platform": "termux-android-arm64", "version": version,
        "sourceRepo": "https://github.com/jiwuyou/wuxianpi.git",
        "sourceCommit": source_commit, "sourceDirty": source_dirty,
        "sdkPackage": "@earendil-works/pi-coding-agent", "sdkVersion": "0.80.10",
        "nodeVersion": ">=22.19.0", "transport": "wuxianpi-sdk-v1",
        "registrationRequires": {"serviceManager": ">=0.3.1", "registryApiVersion": 2},
        "provides": {"piSdkEmbedded": True, "webSocket": True, "nativeJsonlSessions": True,
                     "staticWebUi": True, "uiMetadata": True},
    })
    doc.pop("nativeRuntimeAsset", None)
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

log "runtime payload: $(sha256sum "$output")"
"$repo_dir/scripts/generate-resource-set-v2.sh"
