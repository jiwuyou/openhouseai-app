#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$repo_dir/runtime/wuxianpi-node"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"
output="$payload_dir/pi-runtime.tar"
native_output="$payload_dir/wuxianpi-native-install.tar"
native_asset_dir="$repo_dir/native-app/src/main/assets/openhouse-runtime"
native_asset="$native_asset_dir/runtime-aarch64.tgz"
stage="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-node-payload.XXXXXX")"
build="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-node-build.XXXXXX")"
trap 'rm -rf -- "$stage" "$build"' EXIT

log() { printf '[wuxianpi-node payload] %s\n' "$*"; }
die() { printf '[wuxianpi-node payload] ERROR: %s\n' "$*" >&2; exit 1; }

[[ -f "$source_dir/package-lock.json" ]] || die "missing committed package-lock.json"
grep -Fq '"@earendil-works/pi-coding-agent": "0.80.10"' "$source_dir/package.json" \
  || die "Pi SDK must be pinned exactly to 0.80.10"

cp -a "$source_dir/package.json" "$source_dir/package-lock.json" "$source_dir/tsconfig.json" "$source_dir/src" "$source_dir/test" "$build/"
if [[ -d "${WUXIANPI_NODE_MODULES_DIR:-$source_dir/node_modules}" ]]; then
  cp -a "${WUXIANPI_NODE_MODULES_DIR:-$source_dir/node_modules}" "$build/node_modules"
else
  (cd "$build" && npm ci --include=dev)
fi
(
  cd "$build"
  npm run typecheck
  npm test
  node -e 'const p=require("./node_modules/@earendil-works/pi-coding-agent/package.json");if(p.version!=="0.80.10")process.exit(1)'
)
rm -rf "$build/node_modules/@types" "$build/node_modules/typescript" "$build/node_modules/undici-types"
rm -f "$build/node_modules/.bin/tsc" "$build/node_modules/.bin/tsserver"

mkdir -p "$stage/bin" "$stage/node" "$stage/scripts" "$stage/metadata"
cp -a "$build/package.json" "$build/package-lock.json" "$build/dist" "$build/node_modules" "$stage/node/"

cat > "$stage/bin/wuxianpi-node" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
exec node "$HOME/.local/share/openhouseai/runtime/node/dist/index.js" "$@"
EOF

cat > "$stage/bin/wuxianpi-node-start" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
mkdir -p "$HOME/.pi/agent/sessions" "$HOME/workspace"
exec "$HOME/.local/bin/wuxianpi-node" \
  --listen "${OPENHOUSE_PI_LISTEN:-127.0.0.1:8765}" \
  --agent-dir "${PI_CODING_AGENT_DIR:-$HOME/.pi/agent}" \
  --idle-timeout-ms "${OPENHOUSE_PI_IDLE_TIMEOUT_MS:-300000}"
EOF

cat > "$stage/bin/wuxianpi" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
exec node "$HOME/.local/share/openhouseai/runtime/node/node_modules/@earendil-works/pi-coding-agent/dist/cli.js" "$@"
EOF

cat > "$stage/scripts/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
root=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
runtime="$HOME/.local/share/openhouseai/runtime"
local_bin="$HOME/.local/bin"
new_node="$runtime/node.new.$$"
mkdir -p "$runtime" "$runtime/bin" "$runtime/state" "$local_bin" "$HOME/.pi/agent/sessions" "$HOME/workspace"
rm -rf "$new_node"
mkdir -p "$new_node"
(cd "$root/node" && tar -cf - .) | (cd "$new_node" && tar -xf -)
node -e 'const [major,minor]=process.versions.node.split(".").map(Number);if(major<22||(major===22&&minor<19))process.exit(1)'
node "$new_node/dist/index.js" --help >/dev/null
rm -rf "$runtime/node.old.$$"
if [ -d "$runtime/node" ]; then mv "$runtime/node" "$runtime/node.old.$$"; fi
mv "$new_node" "$runtime/node"
rm -rf "$runtime/node.old.$$"
for name in wuxianpi wuxianpi-node wuxianpi-node-start; do
  install -m 0755 "$root/bin/$name" "$runtime/bin/$name"
  install -m 0755 "$root/bin/$name" "$local_bin/$name"
done
printf 'Installed WuxianPi Node SDK runtime under %s\n' "$runtime"
EOF

cat > "$stage/scripts/check.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
runtime="$HOME/.local/share/openhouseai/runtime"
command -v node >/dev/null
node -e 'const [major,minor]=process.versions.node.split(".").map(Number);if(major<22||(major===22&&minor<19))process.exit(1)'
test -x "$HOME/.local/bin/wuxianpi-node"
test -x "$HOME/.local/bin/wuxianpi-node-start"
test -f "$runtime/node/dist/index.js"
test -f "$runtime/node/node_modules/@earendil-works/pi-coding-agent/package.json"
node -e 'const p=require(process.env.HOME+"/.local/share/openhouseai/runtime/node/node_modules/@earendil-works/pi-coding-agent/package.json");if(p.version!=="0.80.10")process.exit(1)'
printf 'ok: WuxianPi Node SDK runtime is installed\n'
EOF

cat > "$stage/scripts/register-service.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
service_dir="${OPENHOUSEAI_CONFIG_DIR:-$HOME/.config/openhouseai}/service-manager/services.d"
spec="$service_dir/pi-agent.json"
mkdir -p "$service_dir"
tmp=$(mktemp "${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/pi-agent.json.XXXXXX")
cat > "$tmp" <<JSON
{
  "name": "pi-agent",
  "description": "WuxianPi Node service embedding the official Pi SDK",
  "provider": "termux-process",
  "command": ["sh", "-lc", "wuxianpi-node-start & child=\$!; trap 'kill -TERM \$child 2>/dev/null; wait \$child 2>/dev/null || true' TERM INT HUP; wait \$child"],
  "working_dir": "$HOME/workspace",
  "env": {
    "HOME": "$HOME",
    "PATH": "$HOME/.local/bin:${PREFIX:-/data/data/com.termux/files/usr}/bin:/system/bin",
    "PI_CODING_AGENT_DIR": "$HOME/.pi/agent",
    "OPENHOUSE_PI_RUNTIME_ORIGIN": "http://127.0.0.1:8765"
  },
  "runtime": {"strategy": "termux-process", "runtime": "termux", "platform": "android-arm64"},
  "restart": {"mode": "always", "max_retries": 0},
  "health": [{"type": "http", "url": "http://127.0.0.1:8765/health", "interval": "15s", "timeout": "3s"}],
  "enabled": true,
  "tags": ["group:local-stack", "openhouseai", "agent", "pi-node-sdk", "openhouse-component:pi-agent"]
}
JSON
node -e 'JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"))' "$tmp"
mv "$tmp" "$spec"
printf 'registered: %s\n' "$spec"
EOF

cat > "$stage/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
root=$(CDPATH= cd "$(dirname "$0")" && pwd)
"$root/scripts/install.sh"
"$root/scripts/register-service.sh"
EOF

chmod 0755 "$stage/install.sh" "$stage/bin/"* "$stage/scripts/"*.sh
python3 - "$stage/metadata/build.json" <<'PY'
import json, pathlib
path = pathlib.Path(__import__('sys').argv[1])
path.write_text(json.dumps({
    "schema": 2,
    "runtime": "wuxianpi-node",
    "protocol": "wuxianpi-sdk-v1",
    "piSdkPackage": "@earendil-works/pi-coding-agent",
    "piSdkVersion": "0.80.10",
    "node": ">=22.19.0",
}, indent=2) + "\n", encoding="utf-8")
PY

tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner -cf "$output.tmp" -C "$stage" .
mv "$output.tmp" "$output"
chmod 0644 "$output"

native_stage="$stage/native-bundle"
mkdir -p "$native_stage/payload"
cp "$output" "$native_stage/payload/pi-runtime.tar"
sha256sum "$output" | awk '{print $1 "  pi-runtime.tar"}' > "$native_stage/payload/pi-runtime.tar.sha256"
cat > "$native_stage/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
root=$(CDPATH= cd "$(dirname "$0")" && pwd)
expected=$(cut -d ' ' -f 1 "$root/payload/pi-runtime.tar.sha256")
actual=$(sha256sum "$root/payload/pi-runtime.tar" | cut -d ' ' -f 1)
[ "$expected" = "$actual" ] || { printf 'WuxianPi payload checksum mismatch\n' >&2; exit 1; }
stage=$(mktemp -d "${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}/wuxianpi-install.XXXXXX")
trap 'rm -rf "$stage"' EXIT
tar -xf "$root/payload/pi-runtime.tar" -C "$stage"
"$stage/scripts/install.sh"
"$stage/scripts/register-service.sh"
printf 'WuxianPi Node runtime deployment completed.\n'
EOF
chmod 0755 "$native_stage/install.sh"
tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner -cf "$native_output.tmp" -C "$native_stage" .
mv "$native_output.tmp" "$native_output"
chmod 0644 "$native_output"

mkdir -p "$native_asset_dir"
tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner --exclude='./native-bundle' -cf - -C "$stage" . | gzip -n > "$native_asset.tmp"
mv "$native_asset.tmp" "$native_asset"
chmod 0644 "$native_asset"

python3 - "$payload_dir/manifest.json" "$payload_dir/payload-manifest.json" "$output" "$native_output" "$native_asset" <<'PY'
import hashlib, json, pathlib, sys
manifest_path, payload_manifest_path, archive, native_archive, native_asset = map(pathlib.Path, sys.argv[1:])
def digest(path):
    data = path.read_bytes(); return len(data), hashlib.sha256(data).hexdigest()
size, sha = digest(archive); native_size, native_sha = digest(native_archive); asset_size, asset_sha = digest(native_asset)
for path, key in ((manifest_path, "components"), (payload_manifest_path, "payloads")):
    doc = json.loads(path.read_text(encoding="utf-8"))
    entry = next(item for item in doc[key] if item.get("id") == "pi-agent")
    entry.clear(); entry.update({
        "id": "pi-agent", "archive": archive.name, "sha256": sha, "size": size,
        "platform": "termux-android-arm64", "version": "0.1.0+pi.0.80.10",
        "sourceRepo": "https://github.com/earendil-works/pi.git",
        "sdkPackage": "@earendil-works/pi-coding-agent", "sdkVersion": "0.80.10",
        "nodeVersion": ">=22.19.0", "transport": "wuxianpi-sdk-v1",
        "registrationRequires": {"serviceManager": ">=0.3.1", "registryApiVersion": 2},
        "provides": {"piSdkEmbedded": True, "webSocket": True, "nativeJsonlSessions": True},
    })
    if key == "components": entry["targetDir"] = "pi-runtime"
    doc["nativeInstallBundle"] = {"id": "wuxianpi-native-install", "archive": native_archive.name,
        "sha256": native_sha, "size": native_size, "applicationId": "com.wuxianpi"}
    doc["nativeRuntimeAsset"] = {"archive": native_asset.name, "sha256": asset_sha,
        "size": asset_size, "abi": "arm64-v8a", "applicationId": "com.wuxianpi"}
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

log "runtime payload: $(sha256sum "$output")"
log "native install: $(sha256sum "$native_output")"
log "native asset: $(sha256sum "$native_asset")"
