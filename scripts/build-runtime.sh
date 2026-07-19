#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
web_source="$repo_dir/web/pi-web"
payload_dir="$repo_dir/app/src/main/assets/openhouse/product-payloads"
prompt_dir="$repo_dir/app/src/main/assets/openhouse/pi-prompts"
build_host="${PI_WEB_BUILD_SSH:-phonetermux}"
remote_dir=""
stage=""

cleanup() {
  [[ -z "$stage" ]] || rm -rf -- "$stage"
  if [[ -n "$remote_dir" && "${PI_WEB_KEEP_REMOTE_BUILD:-0}" != "1" ]]; then
    ssh "$build_host" "rm -rf -- '$remote_dir'" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "${SKIP_PI_NODE_PAYLOAD:-0}" != "1" ]]; then
  "$repo_dir/scripts/build-pi-node-payload.sh"
fi

[[ -f "$web_source/package-lock.json" ]] || { printf 'missing web/pi-web source\n' >&2; exit 1; }
for prompt in openhouse-first-config openhouse-docs openhouse-second-ai-handoff; do
  [[ -s "$prompt_dir/$prompt.md" ]] || { printf 'missing prompt: %s\n' "$prompt" >&2; exit 1; }
done

remote_dir="$(ssh "$build_host" 'base="${TMPDIR:-$HOME/.cache}"; mkdir -p "$base"; mktemp -d "$base/wuxianpi-web.XXXXXX"')"
tar --exclude='./node_modules' --exclude='./.next' -cf - -C "$web_source" . \
  | ssh "$build_host" "tar -xf - -C '$remote_dir'"
ssh "$build_host" "
  set -eu
  cd '$remote_dir'
  npm ci --prefer-offline
  node_modules/.bin/tsc --noEmit
  npm run lint
  npm run build
  test -f .next/standalone/server.js
  test -f server/openhouse-server.mjs
  test -d node_modules/ws
"

stage="$(mktemp -d "${TMPDIR:-/tmp}/wuxianpi-web-payload.XXXXXX")"
mkdir -p "$stage/bin" "$stage/scripts" "$stage/prompts" "$stage/runtime/pi-web/.next" "$stage/runtime/pi-web/node_modules"
ssh "$build_host" "tar -cf - -C '$remote_dir/.next/standalone' ." | tar -xf - -C "$stage/runtime/pi-web"
ssh "$build_host" "tar -cf - -C '$remote_dir/.next' static" | tar -xf - -C "$stage/runtime/pi-web/.next"
ssh "$build_host" "tar -cf - -C '$remote_dir/node_modules' ws" | tar -xf - -C "$stage/runtime/pi-web/node_modules"
ssh "$build_host" "tar -cf - -C '$remote_dir/server' openhouse-server.mjs" | tar -xf - -C "$stage/runtime/pi-web"
if ssh "$build_host" "test -d '$remote_dir/public'"; then
  ssh "$build_host" "tar -cf - -C '$remote_dir' public" | tar -xf - -C "$stage/runtime/pi-web"
fi
cp "$prompt_dir/"*.md "$stage/prompts/"

cat > "$stage/bin/openhouse-pi-web-start" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
export PI_CODING_AGENT_DIR="${PI_CODING_AGENT_DIR:-$HOME/.pi/agent}"
export PI_WEB_DEFAULT_CWD="${PI_WEB_DEFAULT_CWD:-$HOME}"
export PI_WEB_HOST="${PI_WEB_HOST:-127.0.0.1}"
export HOSTNAME="${HOSTNAME:-$PI_WEB_HOST}"
export PORT="${PORT:-${PI_WEB_PORT:-30141}}"
export OPENHOUSE_PI_RUNTIME_ORIGIN="${OPENHOUSE_PI_RUNTIME_ORIGIN:-http://127.0.0.1:8765}"
runtime="${OPENHOUSE_PI_WEB_RUNTIME_DIR:-$HOME/.local/share/openhouseai/pi-web}"
cd "$runtime"
exec node openhouse-server.mjs
EOF

cat > "$stage/bin/pi-web" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
host="${PI_WEB_HOST:-127.0.0.1}"
port="${PI_WEB_PORT:-30141}"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --host) host="$2"; shift 2 ;;
    --port) port="$2"; shift 2 ;;
    -h|--help|help) printf 'Usage: pi-web [--host HOST] [--port PORT]\n'; exit 0 ;;
    *) printf 'unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
PI_WEB_HOST="$host" PI_WEB_PORT="$port" exec "$HOME/.local/bin/openhouse-pi-web-start"
EOF

cat > "$stage/scripts/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
root=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
runtime="$HOME/.local/share/openhouseai/pi-web"
tmp="$runtime.tmp.$$"
mkdir -p "$(dirname "$runtime")" "$HOME/.local/bin" "$HOME/.pi/prompts"
mkdir -p "$tmp"
(cd "$root/runtime/pi-web" && tar -cf - .) | (cd "$tmp" && tar -xf -)
rm -rf "$runtime"
mv "$tmp" "$runtime"
install -m 0755 "$root/bin/openhouse-pi-web-start" "$HOME/.local/bin/openhouse-pi-web-start"
install -m 0755 "$root/bin/pi-web" "$HOME/.local/bin/pi-web"
for prompt_name in openhouse-first-config openhouse-docs openhouse-second-ai-handoff; do
  install -m 600 "$root/prompts/$prompt_name.md" "$HOME/.pi/prompts/$prompt_name.md"
done
test -f "$runtime/openhouse-server.mjs"
test -d "$runtime/.next/static"
EOF

cat > "$stage/scripts/check.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
runtime="$HOME/.local/share/openhouseai/pi-web"
command -v node >/dev/null
test -x "$HOME/.local/bin/pi-web"
test -f "$runtime/openhouse-server.mjs"
test -d "$runtime/node_modules/ws"
test -d "$runtime/.next/static"
printf 'ok: Pi WebSocket UI installed\n'
EOF

cat > "$stage/scripts/register-service.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
: "${HOME:?HOME is required}"
service_dir="${OPENHOUSEAI_CONFIG_DIR:-$HOME/.config/openhouseai}/service-manager/services.d"
spec="$service_dir/pi-web.json"
mkdir -p "$service_dir"
tmp=$(mktemp "${TMPDIR:-$PREFIX/tmp}/pi-web.json.XXXXXX")
cat > "$tmp" <<JSON
{
  "name": "pi-web",
  "description": "Web UI using the WuxianPi Node SDK WebSocket service",
  "provider": "termux-process",
  "command": ["sh", "-lc", "pi-web --host 127.0.0.1 --port 30141 & child=\$!; trap 'kill -TERM \$child 2>/dev/null; wait \$child 2>/dev/null || true' TERM INT HUP; wait \$child"],
  "working_dir": "$HOME",
  "env": {
    "HOME": "$HOME",
    "PATH": "$HOME/.local/bin:${PREFIX:-/data/data/com.termux/files/usr}/bin:/system/bin",
    "PI_WEB_HOST": "127.0.0.1",
    "HOSTNAME": "127.0.0.1",
    "PI_WEB_DEFAULT_CWD": "$HOME",
    "OPENHOUSE_PI_RUNTIME_ORIGIN": "http://127.0.0.1:8765"
  },
  "runtime": {"strategy": "termux-process", "runtime": "termux", "platform": "android-arm64"},
  "restart": {"mode": "on-failure", "max_retries": 5},
  "health": [{"type": "tcp", "address": "127.0.0.1:30141", "interval": "15s", "timeout": "3s"}],
  "enabled": true,
  "tags": ["openhouseai", "pi-web", "openhouse-component:pi-web"]
}
JSON
python3 -m json.tool "$tmp" >/dev/null
mv "$tmp" "$spec"
EOF

chmod 0755 "$stage/bin/"* "$stage/scripts/"*.sh
web_output="$payload_dir/pi-web.tar"
tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner -cf "$web_output.tmp" -C "$stage" .
mv "$web_output.tmp" "$web_output"
chmod 0644 "$web_output"

source_branch="$(git -C "$repo_dir" branch --show-current)"
source_commit="$(git -C "$repo_dir" rev-parse HEAD)"
source_tree_sha="$(tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner --exclude='./node_modules' --exclude='./.next' -cf - -C "$web_source" . | sha256sum | awk '{print $1}')"
python3 - "$payload_dir/manifest.json" "$payload_dir/payload-manifest.json" "$web_output" "$source_branch" "$source_commit" "$source_tree_sha" <<'PY'
import hashlib, json, pathlib, sys
manifest_path, payload_manifest_path, archive = map(pathlib.Path, sys.argv[1:4])
branch, commit, tree_sha = sys.argv[4:]
data = archive.read_bytes()
for path, key in ((manifest_path, "components"), (payload_manifest_path, "payloads")):
    doc = json.loads(path.read_text(encoding="utf-8"))
    entry = next(item for item in doc[key] if item.get("id") == "pi-web")
    entry.update({
        "archive": archive.name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data),
        "sourceRepo": "https://github.com/jiwuyou/openhouseai-app.git",
        "sourceBranch": branch, "sourceCommit": commit, "sourceTreeSha256": tree_sha,
        "transport": "wuxianpi-sdk-v1",
    })
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

if [[ "${SKIP_PAYLOAD_VALIDATION:-0}" != "1" ]]; then
  "$repo_dir/scripts/validate-openhouse-payloads.sh"
fi
