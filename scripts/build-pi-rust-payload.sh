#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pi_source="${PI_RUST_SOURCE_DIR:-/root/projects/pi_agent_rust}"
pi_commit="${PI_RUST_COMMIT:-ad719ad3d42173be9293a020492b7d10f85c95fe}"
runtime_source="$repo_dir/runtime"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"
build_host="${PI_RUST_BUILD_SSH:-phonetermux}"
output="$payload_dir/pi-runtime.tar"
native_output="$payload_dir/wuxianpi-native-install.tar"
native_asset_dir="$repo_dir/native-app/src/main/assets/openhouse-runtime"
native_asset="$native_asset_dir/runtime-aarch64.tgz"
staging="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-pi-runtime.XXXXXX")"
remote_root=""

log() { printf '[pi-rust payload] %s\n' "$*"; }
die() { printf '[pi-rust payload] ERROR: %s\n' "$*" >&2; exit 1; }

cleanup() {
  rm -rf -- "$staging"
  if [[ -n "$remote_root" && "${PI_RUST_KEEP_REMOTE_BUILD:-0}" != "1" ]]; then
    ssh "$build_host" "rm -rf -- '$remote_root'" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

[[ -f "$pi_source/Cargo.lock" ]] || die "pi_agent_rust source missing: $pi_source"
[[ -f "$runtime_source/Cargo.lock" ]] || die "runtime workspace missing: $runtime_source"
[[ "$(git -C "$pi_source" rev-parse HEAD)" == "$pi_commit" ]] || die "pi_agent_rust HEAD must be pinned to $pi_commit"
[[ -z "$(git -C "$pi_source" status --porcelain --untracked-files=all)" ]] || die "pi_agent_rust source must be clean"
[[ -f "$runtime_source/pi-gateway/src/main.rs" ]] || die "openhouse-pi-runtime source is incomplete"
[[ -d "$runtime_source/extensions" ]] || die "runtime/extensions is required"
find "$runtime_source/extensions" -type f \( -name '*.js' -o -name '*.mjs' -o -name '*.ts' \) -print -quit | grep -q . \
  || die "runtime/extensions must contain at least one Pi extension"

mkdir -p "$staging/bin" "$staging/scripts" "$staging/extensions" "$staging/metadata"

pi_cache_dir="${PI_RUST_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/openhouseai-build}"
pi_binary_input="${PI_RUST_BINARY:-${PI_BINARY:-}}"
pi_cache_binary="$pi_cache_dir/pi-$pi_commit-android-arm64"
if [[ -z "$pi_binary_input" && -x "$pi_cache_binary" ]]; then
  pi_binary_input="$pi_cache_binary"
  log "reusing cached Pi ARM64 binary: $pi_cache_binary"
fi
gateway_binary_input="${PI_GATEWAY_BINARY:-}"

if [[ -n "$pi_binary_input" ]]; then
  [[ -x "$pi_binary_input" ]] || die "PI_BINARY/PI_RUST_BINARY must point to an executable"
  install -m 0755 "$pi_binary_input" "$staging/bin/pi"
fi
if [[ -n "$gateway_binary_input" ]]; then
  [[ -x "$gateway_binary_input" ]] || die "PI_GATEWAY_BINARY must point to an executable"
  install -m 0755 "$gateway_binary_input" "$staging/bin/openhouse-pi-runtime"
fi

if [[ -z "$pi_binary_input" || -z "$gateway_binary_input" ]]; then
  remote_root="$(ssh "$build_host" 'base="${TMPDIR:-$HOME/.cache}"; mkdir -p "$base"; mktemp -d "$base/openhouse-pi-rust.XXXXXX"')"
  [[ -n "$remote_root" ]] || die "failed to allocate remote build directory"
  log "building ARM64 Android binaries on $build_host"
  ssh "$build_host" "mkdir -p '$remote_root/pi' '$remote_root/runtime'"
  if [[ -z "$pi_binary_input" ]]; then
    tar --exclude='./.git' --exclude='./target' --exclude='./.beads' -cf - -C "$pi_source" . \
      | ssh "$build_host" "tar -xf - -C '$remote_root/pi'"
    remote_home="$(ssh "$build_host" 'printf %s "$HOME"')"
    remote_pi_target="$remote_home/.cache/openhouseai-build/pi-$pi_commit-target"
    ssh "$build_host" "mkdir -p '$remote_pi_target'; cd '$remote_root/pi' && \
      CARGO_TARGET_DIR='$remote_pi_target' \
      CARGO_PROFILE_RELEASE_LTO=false \
      CARGO_PROFILE_RELEASE_CODEGEN_UNITS=8 \
      CARGO_PROFILE_RELEASE_OPT_LEVEL=2 \
      RUSTC_BOOTSTRAP=1 cargo build --locked --release --bin pi && \
      test -x '$remote_pi_target/release/pi'"
    scp -q "$build_host:$remote_pi_target/release/pi" "$staging/bin/pi"
    mkdir -p "$pi_cache_dir"
    install -m 0755 "$staging/bin/pi" "$pi_cache_binary"
  fi
  if [[ -z "$gateway_binary_input" ]]; then
    tar --exclude='./target' -cf - -C "$runtime_source" . \
      | ssh "$build_host" "tar -xf - -C '$remote_root/runtime'"
    ssh "$build_host" "cd '$remote_root/runtime' && RUSTC_BOOTSTRAP=1 cargo build --locked --release --package openhouse-pi-runtime --bin openhouse-pi-runtime && test -x target/release/openhouse-pi-runtime"
    scp -q "$build_host:$remote_root/runtime/target/release/openhouse-pi-runtime" "$staging/bin/openhouse-pi-runtime"
  fi
  chmod 0755 "$staging/bin/pi" "$staging/bin/openhouse-pi-runtime"
fi

file "$staging/bin/pi" | grep -Eqi 'ELF 64-bit.*(ARM aarch64|aarch64)' || die "pi is not an ARM64 ELF binary"
file "$staging/bin/openhouse-pi-runtime" | grep -Eqi 'ELF 64-bit.*(ARM aarch64|aarch64)' || die "gateway is not an ARM64 ELF binary"

cp -a "$runtime_source/extensions/." "$staging/extensions/"

cat > "$staging/bin/openhouse-pi" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
exec "$HOME/.local/share/openhouseai/runtime/bin/pi" "$@"
EOF

cat > "$staging/bin/openhouse-pi-runtime-start" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
runtime="$HOME/.local/share/openhouseai/runtime"
mkdir -p "$runtime/state" "$HOME/.pi/agent/sessions" "$HOME/workspace"
exec "$runtime/bin/openhouse-pi-runtime" \
  --listen "${OPENHOUSE_PI_LISTEN:-127.0.0.1:8765}" \
  --pi-bin "$runtime/bin/pi" \
  --sessions-dir "${OPENHOUSE_PI_SESSIONS_DIR:-$HOME/.pi/agent/sessions}" \
  --state-dir "${OPENHOUSE_PI_STATE_DIR:-$runtime/state}" \
  --pi-working-dir "${OPENHOUSE_PI_WORKING_DIR:-$HOME/workspace}" \
  --extension "${OPENHOUSE_PI_EXTENSION:-$runtime/extensions/openhouse-tools}" \
  --token-file "${OPENHOUSE_PI_TOKEN_FILE:-$runtime/state/token}"
EOF

cat > "$staging/scripts/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
root=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
runtime="$HOME/.local/share/openhouseai/runtime"
local_bin="$HOME/.local/bin"
mkdir -p "$runtime/bin" "$runtime/extensions" "$runtime/state" "$local_bin" "$HOME/.pi/agent/sessions" "$HOME/workspace"
install -m 0755 "$root/bin/pi" "$runtime/bin/pi"
install -m 0755 "$root/bin/openhouse-pi-runtime" "$runtime/bin/openhouse-pi-runtime"
install -m 0755 "$root/bin/openhouse-pi" "$local_bin/openhouse-pi"
install -m 0755 "$root/bin/openhouse-pi-runtime-start" "$local_bin/openhouse-pi-runtime-start"
if [ -d "$root/extensions" ]; then
  (cd "$root/extensions" && tar -cf - .) | (cd "$runtime/extensions" && tar -xf -)
fi
"$runtime/bin/pi" --help >/dev/null
"$runtime/bin/openhouse-pi-runtime" --help >/dev/null
printf 'Installed WuxianPi runtime under %s\n' "$runtime"
EOF

cat > "$staging/scripts/check.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
runtime="$HOME/.local/share/openhouseai/runtime"
test -x "$runtime/bin/pi"
test -x "$runtime/bin/openhouse-pi-runtime"
test -x "$HOME/.local/bin/openhouse-pi"
test -x "$HOME/.local/bin/openhouse-pi-runtime-start"
"$runtime/bin/pi" --help >/dev/null
"$runtime/bin/openhouse-pi-runtime" --help >/dev/null
printf 'ok: Pi Rust and OpenHouse Pi runtime are installed\n'
EOF

cat > "$staging/scripts/register-service.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
config_dir="${OPENHOUSEAI_CONFIG_DIR:-$HOME/.config/openhouseai}"
service_dir="$config_dir/service-manager/services.d"
spec="$service_dir/pi-agent.json"
mkdir -p "$service_dir"
tmp=$(mktemp "${TMPDIR:-$PREFIX/tmp}/pi-agent.json.XXXXXX")
cat > "$tmp" <<JSON
{
  "name": "pi-agent",
  "description": "Pi Rust JSONL RPC runtime for OpenHouseAI and WuxianPi",
  "provider": "termux-process",
  "command": ["sh", "-lc", "openhouse-pi-runtime-start & child=\$!; trap 'kill -TERM \$child 2>/dev/null; wait \$child 2>/dev/null || true' TERM INT HUP; wait \$child"],
  "working_dir": "$HOME/workspace",
  "env": {
    "HOME": "$HOME",
    "PATH": "$HOME/.local/bin:${PREFIX:-/data/data/com.termux/files/usr}/bin:/system/bin",
    "OPENHOUSE_PI_RUNTIME_ORIGIN": "http://127.0.0.1:8765",
    "OPENHOUSE_PI_RUNTIME_TOKEN_FILE": "$HOME/.local/share/openhouseai/runtime/state/token"
  },
  "runtime": {"strategy": "termux-process", "runtime": "termux", "platform": "android-arm64"},
  "restart": {"mode": "always", "max_retries": 0},
  "health": [{"type": "tcp", "address": "127.0.0.1:8765", "interval": "15s", "timeout": "3s"}],
  "enabled": true,
  "tags": ["group:local-stack", "openhouseai", "agent", "pi-rust", "openhouse-component:pi-agent"]
}
JSON
python3 -m json.tool "$tmp" >/dev/null
mv "$tmp" "$spec"
printf 'registered: %s\n' "$spec"
EOF

cat > "$staging/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
root=$(CDPATH= cd "$(dirname "$0")" && pwd)
"$root/scripts/install.sh"
"$root/scripts/register-service.sh"
EOF

chmod 0755 "$staging/install.sh" "$staging/bin/"* "$staging/scripts/"*.sh
python3 - "$staging/metadata/build.json" "$pi_commit" "$staging/bin/pi" "$staging/bin/openhouse-pi-runtime" <<'PY'
import hashlib, json, pathlib, sys
out, commit, pi, gateway = map(pathlib.Path, sys.argv[1:])
def info(path):
    data = path.read_bytes()
    return {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
out.write_text(json.dumps({
    "schema": 1,
    "platform": "termux-android-arm64",
    "piAgentRustCommit": str(commit),
    "pi": info(pi),
    "openhousePiRuntime": info(gateway),
}, indent=2) + "\n", encoding="utf-8")
PY

tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner -cf "$output.tmp" -C "$staging" .
mv "$output.tmp" "$output"
chmod 0644 "$output"

native_stage="$staging/native-bundle"
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
stage=$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/wuxianpi-install.XXXXXX")
tar -xf "$root/payload/pi-runtime.tar" -C "$stage"
"$stage/scripts/install.sh"
"$stage/scripts/register-service.sh"
printf 'WuxianPi runtime deployment completed.\n'
EOF
chmod 0755 "$native_stage/install.sh"
tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner -cf "$native_output.tmp" -C "$native_stage" .
mv "$native_output.tmp" "$native_output"
chmod 0644 "$native_output"

mkdir -p "$native_asset_dir"
tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner --exclude='./native-bundle' -cf - -C "$staging" . \
  | gzip -n > "$native_asset.tmp"
mv "$native_asset.tmp" "$native_asset"
chmod 0644 "$native_asset"

python3 - "$payload_dir/manifest.json" "$payload_dir/payload-manifest.json" "$output" "$native_output" "$native_asset" "$pi_commit" "$staging/bin/pi" "$staging/bin/openhouse-pi-runtime" <<'PY'
import hashlib, json, pathlib, sys
manifest_path, payload_manifest_path, archive, native_archive, native_asset, commit, pi, gateway = map(pathlib.Path, sys.argv[1:])
def digest(path):
    data = path.read_bytes()
    return len(data), hashlib.sha256(data).hexdigest()
size, sha = digest(archive)
native_size, native_sha = digest(native_archive)
asset_size, asset_sha = digest(native_asset)
pi_size, pi_sha = digest(pi)
gateway_size, gateway_sha = digest(gateway)
for path, key in ((manifest_path, "components"), (payload_manifest_path, "payloads")):
    doc = json.loads(path.read_text(encoding="utf-8"))
    entry = next(item for item in doc[key] if item.get("id") == "pi-agent")
    entry.clear()
    entry.update({
        "id": "pi-agent", "archive": archive.name, "sha256": sha, "size": size,
        "platform": "termux-android-arm64", "version": f"0.1.20+{str(commit)[:12]}",
        "sourceRepo": "https://github.com/Dicklesworthstone/pi_agent_rust.git",
        "sourceCommit": str(commit), "binarySha256": pi_sha, "binarySize": pi_size,
        "gatewaySha256": gateway_sha, "gatewaySize": gateway_size,
        "registrationRequires": {"serviceManager": ">=0.3.1", "registryApiVersion": 2},
        "provides": {"piJsonlRpc": True, "webSocketGateway": True, "multiSessionLeases": True},
    })
    if key == "components": entry["targetDir"] = "pi-runtime"
    doc["nativeInstallBundle"] = {
        "id": "wuxianpi-native-install", "archive": native_archive.name,
        "sha256": native_sha, "size": native_size, "applicationId": "com.wuxianpi",
    }
    doc["nativeRuntimeAsset"] = {
        "archive": native_asset.name, "sha256": asset_sha, "size": asset_size,
        "abi": "arm64-v8a", "applicationId": "com.wuxianpi",
    }
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

log "Pi commit: $pi_commit"
log "runtime payload: $(sha256sum "$output")"
log "WuxianPi native bundle: $(sha256sum "$native_output")"
log "WuxianPi APK runtime asset: $(sha256sum "$native_asset")"
