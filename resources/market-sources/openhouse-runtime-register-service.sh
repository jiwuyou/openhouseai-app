#!/data/data/com.termux/files/usr/bin/sh
set -eu

: "${HOME:?HOME is required}"
product_root=${WUXIANPI_INSTALL_ROOT:-$HOME/.local/share/wuxianpi}
service_dir=${OPENHOUSEAI_CONFIG_DIR:-$HOME/.config/openhouseai}/service-manager/services.d
mkdir -p "$service_dir" "$HOME/workspace" "$HOME/.pi/agent/sessions"

if [ "$#" -gt 0 ]; then
  [ "$#" -eq 2 ] && [ "$1" = "--spec" ] || {
    printf 'Usage: %s [--spec FILE|-]\n' "$0" >&2
    exit 2
  }
  input=$2
  incoming=$(mktemp "$service_dir/.wuxianpi-service.XXXXXX")
  trap 'rm -f "$incoming"' EXIT HUP INT TERM
  if [ "$input" = "-" ]; then
    cat >"$incoming"
  else
    [ -f "$input" ] || { printf 'Service spec not found: %s\n' "$input" >&2; exit 2; }
    cp "$input" "$incoming"
  fi
  service_name=$(node - "$incoming" <<'NODE'
const fs = require("node:fs");
const value = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
if (!value || Array.isArray(value) || typeof value !== "object") throw new Error("service spec must be an object");
if (typeof value.name !== "string" || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/.test(value.name) || value.name.includes("..")) throw new Error("invalid service name");
if (typeof value.provider !== "string" || !value.provider) throw new Error("service provider is required");
if (!(typeof value.command === "string" && value.command) && !(Array.isArray(value.command) && value.command.length && value.command.every(item => typeof item === "string" && item))) throw new Error("service command is required");
process.stdout.write(value.name);
NODE
  ) || { printf 'Invalid service spec\n' >&2; exit 3; }
  spec=$service_dir/$service_name.json
  chmod 600 "$incoming"
  mv "$incoming" "$spec"
  trap - EXIT HUP INT TERM
  printf 'Registered service-manager service from spec: %s\n' "$spec"
  exit 0
fi

spec=$service_dir/yuanshengwuxianpi.json
tmp=$(mktemp "$service_dir/.yuanshengwuxianpi.json.XXXXXX")

cat >"$tmp" <<JSON
{
  "name": "yuanshengwuxianpi",
  "description": "WuxianPi Web and Node Runtime",
  "provider": "termux-process",
  "command": ["sh", "-lc", "wuxianpi-node-start & child=\$!; trap 'kill -TERM \$child 2>/dev/null; wait \$child 2>/dev/null || true' TERM INT HUP; wait \$child"],
  "working_dir": "$HOME/workspace",
  "env": {
    "HOME": "$HOME",
    "PATH": "$HOME/.local/bin:${PREFIX:-/data/data/com.termux/files/usr}/bin:/system/bin",
    "PI_CODING_AGENT_DIR": "$HOME/.pi/agent",
    "OPENHOUSE_PI_LISTEN": "127.0.0.1:{{port:runtime}}",
    "OPENHOUSE_PI_RUNTIME_ORIGIN": "http://127.0.0.1:{{port:runtime}}",
    "WUXIANPI_INSTALL_ROOT": "$product_root",
    "WUXIANPI_WEB_ROOT": "$product_root/web"
  },
  "runtime": {"strategy": "termux-process", "runtime": "termux", "home": "$HOME"},
  "ports": [{"name": "runtime", "host": "127.0.0.1", "preferred": 20765, "dynamic": true, "pool": "local-web", "protocol": "tcp", "envVar": "OPENHOUSE_PI_PORT", "endpoint": {"scheme": "http", "path": "/"}}],
  "restart": {"mode": "on-failure", "max_retries": 5},
  "health": [{"type": "http", "url": "http://127.0.0.1:{{port:runtime}}/health", "interval": "15s", "timeout": "3s"}],
  "enabled": true,
  "residentByDefault": false,
  "tags": ["wuxianpi", "agent", "openhouse-component:yuanshengwuxianpi"]
}
JSON

node -e 'JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"))' "$tmp"
chmod 600 "$tmp"
mv "$tmp" "$spec"
printf 'Registered on-demand service-manager service (not started): %s\n' "$spec"
