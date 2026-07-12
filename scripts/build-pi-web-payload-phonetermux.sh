#!/usr/bin/env bash
set -euo pipefail

app_root="$(cd "$(dirname "$0")/.." && pwd)"
source_dir="${PI_WEB_SOURCE_DIR:-/root/projects/pi-web}"
ssh_target="${PI_WEB_BUILD_SSH:-phonetermux}"
remote_dependencies="${PI_WEB_REMOTE_DEPENDENCIES:-\$HOME/projects/pi-web/node_modules}"
payload_dir="$app_root/app/src/main/assets/openhouse/product-payloads"
output="$payload_dir/pi-web.tar"
remote_build_dir=""
remote_build_owned=0
staging=""

log() { printf '[pi-web payload] %s\n' "$*"; }
die() { printf '[pi-web payload] ERROR: %s\n' "$*" >&2; exit 1; }

cleanup() {
  [ -z "$staging" ] || rm -rf "$staging"
  if [ "$remote_build_owned" = "1" ] && [ -n "$remote_build_dir" ] && [ "${PI_WEB_KEEP_REMOTE_BUILD:-0}" != "1" ]; then
    ssh "$ssh_target" "rm -rf '$remote_build_dir'" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

[ -f "$source_dir/package.json" ] || die "pi-web source missing: $source_dir"
[ -f "$source_dir/package-lock.json" ] || die "pi-web package-lock missing: $source_dir"
[ -f "$output" ] || die "existing pi-web.tar is required as the registration-script template"

if [ -n "${PI_WEB_REMOTE_BUILD_DIR:-}" ]; then
  remote_build_dir="$PI_WEB_REMOTE_BUILD_DIR"
  log "using verified remote build directory: $ssh_target:$remote_build_dir"
  ssh "$ssh_target" "test -f '$remote_build_dir/.next/BUILD_ID' && test -f '$remote_build_dir/.next/standalone/server.js'"
else
  remote_build_dir="$(ssh "$ssh_target" 'mkdir -p "$HOME/.cache"; mktemp -d "$HOME/.cache/openhouse-pi-web-build.XXXXXX"')"
  remote_build_owned=1
  [ -n "$remote_build_dir" ] || die "failed to create remote build directory"
  log "remote build directory: $ssh_target:$remote_build_dir"

  tar \
    --exclude='./.git' \
    --exclude='./node_modules' \
    --exclude='./.next' \
    --exclude='./.swc' \
    -cf - -C "$source_dir" . \
    | ssh "$ssh_target" "tar -xf - -C '$remote_build_dir'"

  local_lock_sha="$(sha256sum "$source_dir/package-lock.json" | awk '{print $1}')"
  remote_lock_sha="$(ssh "$ssh_target" "sha256sum '$remote_build_dir/package-lock.json' | cut -d ' ' -f 1")"
  [ "$local_lock_sha" = "$remote_lock_sha" ] || die "remote source lockfile mismatch"

  ssh "$ssh_target" "
    set -eu
    test -d $remote_dependencies
    cp -a $remote_dependencies '$remote_build_dir/node_modules'
    cd '$remote_build_dir'
    node -e 'if (process.platform !== \"android\" || process.arch !== \"arm64\") process.exit(1)'
    node_modules/.bin/tsc --noEmit
    npm run lint
    npm run build
    test -f .next/BUILD_ID
    test -f .next/standalone/server.js
  "
fi

staging="$(mktemp -d "${TMPDIR:-/tmp}/openhouse-pi-web-payload.XXXXXX")"
mkdir -p "$staging/bin" "$staging/runtime/pi-web/.next" "$staging/scripts"

ssh "$ssh_target" "tar -cf - -C '$remote_build_dir/.next/standalone' ." \
  | tar -xf - -C "$staging/runtime/pi-web"
ssh "$ssh_target" "tar -cf - -C '$remote_build_dir/.next' static" \
  | tar -xf - -C "$staging/runtime/pi-web/.next"
if ssh "$ssh_target" "test -d '$remote_build_dir/public'"; then
  ssh "$ssh_target" "tar -cf - -C '$remote_build_dir' public" \
    | tar -xf - -C "$staging/runtime/pi-web"
fi

tar -xOf "$output" ./scripts/register-service.sh \
  | sed 's#/root#${HOME}#g' > "$staging/scripts/register-service.sh"

cat > "$staging/bin/openhouse-pi-web-start" <<'EOF'
#!/data/data/com.termux/files/usr/bin/env sh
set -eu

: "${HOME:?HOME is required}"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
export PI_CODING_AGENT_DIR="${PI_CODING_AGENT_DIR:-$HOME/.pi}"
export OPENHOUSE_PI_WEB_DEFAULT_CWD="${OPENHOUSE_PI_WEB_DEFAULT_CWD:-$HOME}"
export PI_WEB_HOST="${PI_WEB_HOST:-127.0.0.1}"
export PORT="${PORT:-${PI_WEB_PORT:-30141}}"
export HOSTNAME="${HOSTNAME:-$PI_WEB_HOST}"
export OPENHOUSE_PI_WEB_RUNTIME_DIR="${OPENHOUSE_PI_WEB_RUNTIME_DIR:-$HOME/.local/share/openhouseai/pi-web}"
export PATH="$PREFIX/bin:$HOME/.local/node/bin:$HOME/.local/bin:${PATH:-/system/bin}"

command -v node >/dev/null 2>&1 || { printf 'node is not installed.\n' >&2; exit 127; }
[ -f "$OPENHOUSE_PI_WEB_RUNTIME_DIR/server.js" ] || {
  printf 'pi-web runtime server is missing: %s/server.js\n' "$OPENHOUSE_PI_WEB_RUNTIME_DIR" >&2
  exit 127
}
cd "$OPENHOUSE_PI_WEB_RUNTIME_DIR"
exec node server.js
EOF

cat > "$staging/bin/pi-web" <<'EOF'
#!/data/data/com.termux/files/usr/bin/env sh
set -eu

usage() {
  printf '%s\n' 'Usage: pi-web [--host HOST] [--port PORT]'
}

: "${HOME:?HOME is required}"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
PI_WEB_HOST="${PI_WEB_HOST:-127.0.0.1}"
PORT="${PORT:-${PI_WEB_PORT:-30141}}"
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help|help) usage; exit 0 ;;
    --host) [ "$#" -ge 2 ] || exit 2; PI_WEB_HOST="$2"; shift 2 ;;
    --host=*) PI_WEB_HOST="${1#--host=}"; shift ;;
    --port) [ "$#" -ge 2 ] || exit 2; PORT="$2"; shift 2 ;;
    --port=*) PORT="${1#--port=}"; shift ;;
    *) printf 'pi-web: unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
case "$PORT" in ''|*[!0-9]*) printf 'pi-web: invalid port: %s\n' "$PORT" >&2; exit 2 ;; esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || { printf 'pi-web: invalid port: %s\n' "$PORT" >&2; exit 2; }
export PI_WEB_HOST HOSTNAME="$PI_WEB_HOST" PORT PI_WEB_PORT="$PORT"
export PATH="$PREFIX/bin:$HOME/.local/bin:${PATH:-/system/bin}"
exec "$HOME/.local/bin/openhouse-pi-web-start"
EOF

cat > "$staging/scripts/install.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/env sh
set -eu

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
: "${HOME:?HOME is required}"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd "$SCRIPT_DIR/.." && pwd)
PI_AGENT_DIR="${PI_CODING_AGENT_DIR:-$HOME/.pi}"
RUNTIME_SRC="$ROOT_DIR/runtime/pi-web"
RUNTIME_DST="${OPENHOUSE_PI_WEB_RUNTIME_DIR:-$HOME/.local/share/openhouseai/pi-web}"
LOCAL_BIN="$HOME/.local/bin"
GLOBAL_BIN="${OPENHOUSE_PI_WEB_GLOBAL_BIN:-$PREFIX/bin}"

command -v node >/dev/null 2>&1 || die "Missing required command: node"
command -v tar >/dev/null 2>&1 || die "Missing required command: tar"
node -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit(a>22||(a===22&&b>=19)?0:1)' \
  || die "Node >= 22.19 is required"
[ -f "$RUNTIME_SRC/server.js" ] || die "Bundled pi-web runtime is incomplete"
[ -d "$RUNTIME_SRC/node_modules" ] || die "Bundled pi-web node_modules are missing"
[ -d "$RUNTIME_SRC/.next/static" ] || die "Bundled pi-web static assets are missing"

tmp="$RUNTIME_DST.tmp.$$"
mkdir -p "$(dirname "$RUNTIME_DST")" "$LOCAL_BIN" "$GLOBAL_BIN" \
  "$PI_AGENT_DIR/extensions" "$PI_AGENT_DIR/agent/extensions" "$HOME/workspace"
rm -rf "$tmp"
mkdir -p "$tmp"
(cd "$RUNTIME_SRC" && tar -cf - .) | (cd "$tmp" && tar -xf -)
rm -rf "$RUNTIME_DST"
mv "$tmp" "$RUNTIME_DST"
install -m 755 "$ROOT_DIR/bin/openhouse-pi-web-start" "$LOCAL_BIN/openhouse-pi-web-start"
install -m 755 "$ROOT_DIR/bin/pi-web" "$GLOBAL_BIN/pi-web"
printf 'done: pi-web runtime installed; default URL http://127.0.0.1:%s/\n' "${PI_WEB_PORT:-30141}"
EOF

cat > "$staging/scripts/check.sh" <<'EOF'
#!/data/data/com.termux/files/usr/bin/env sh
set -eu

die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
: "${HOME:?HOME is required}"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
RUNTIME_DIR="${OPENHOUSE_PI_WEB_RUNTIME_DIR:-$HOME/.local/share/openhouseai/pi-web}"
export PATH="$PREFIX/bin:$HOME/.local/node/bin:$HOME/.local/bin:${PATH:-/system/bin}"
command -v node >/dev/null 2>&1 || die "node is missing"
node -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit(a>22||(a===22&&b>=19)?0:1)' \
  || die "Node >= 22.19 is required"
[ -x "$HOME/.local/bin/openhouse-pi-web-start" ] || die "openhouse-pi-web-start is missing"
command -v pi-web >/dev/null 2>&1 || die "pi-web global command is missing"
pi-web --help >/dev/null 2>&1 || die "pi-web global command failed"
[ -f "$RUNTIME_DIR/package.json" ] || die "pi-web runtime package.json is missing"
[ -f "$RUNTIME_DIR/server.js" ] || die "pi-web standalone server is missing"
[ -d "$RUNTIME_DIR/node_modules" ] || die "pi-web runtime node_modules are missing"
[ -d "$RUNTIME_DIR/.next/static" ] || die "pi-web static assets are missing"
printf 'ok: pi-web standalone runtime is installed\n'
EOF

chmod 0755 "$staging/bin/"* "$staging/scripts/"*.sh

tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner \
  -cf "$output.tmp" -C "$staging" .
mv "$output.tmp" "$output"

log "built on $ssh_target: $(ssh "$ssh_target" 'node -p "process.platform+\"/\"+process.arch+\" \"+process.version"')"
log "source commit: $(git -C "$source_dir" rev-parse HEAD)"
log "sha256: $(sha256sum "$output" | awk '{print $1}')"
log "size: $(wc -c < "$output" | tr -d ' ')"
