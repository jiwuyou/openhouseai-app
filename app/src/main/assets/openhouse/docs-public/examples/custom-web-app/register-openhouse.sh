#!/usr/bin/env bash
set -eu

APP_ID="memo-openhouse"
APP_TITLE="OpenHouse Memo"
APP_PORT="${APP_PORT:-23110}"
APP_HOST="${APP_HOST:-127.0.0.1}"
CLI_NAMESPACE="${OPENHOUSE_CLI_NAMESPACE:-demo}"
CLI_NAME="${OPENHOUSE_APP_CLI_NAME:-$CLI_NAMESPACE-$APP_ID}"

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
REPOS_ROOT="${OPENHOUSE_REPOS_DIR:-/root/smallphoneai-repos}"
SMALLPHONE_HOME="${SMALLPHONE_HOME:-$REPOS_ROOT/smallphone-home}"
INSTALL_DIR="${OPENHOUSE_APP_DIR:-$SMALLPHONE_HOME/apps/$APP_ID}"
DATA_DIR="$INSTALL_DIR/data"
SM_URL="${SERVICE_MANAGER_URL:-http://127.0.0.1:20087}"
SM_CONFIG="${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-$HOME/.config/openhouseai/service-manager/config.json}"

log() {
  printf '[memo-openhouse] %s\n' "$*"
}

fail() {
  printf '[memo-openhouse] %s\n' "$*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

resolve_token() {
  if [ -n "${SERVICE_MANAGER_TOKEN:-}" ]; then
    printf '%s' "$SERVICE_MANAGER_TOKEN"
    return
  fi
  if [ -n "${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}" ]; then
    printf '%s' "$SMALLPHONE_SERVICE_MANAGER_TOKEN"
    return
  fi
  if command -v service-manager >/dev/null 2>&1; then
    service-manager token show --config "$SM_CONFIG" 2>/dev/null | head -n1 | tr -d '\r\n'
  fi
}

need_cmd curl
need_cmd python3
need_cmd node

if [[ ! "$CLI_NAMESPACE" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  fail "invalid OPENHOUSE_CLI_NAMESPACE: $CLI_NAMESPACE"
fi

if [[ ! "$CLI_NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  fail "invalid CLI command name: $CLI_NAME"
fi

case "$CLI_NAME" in
  *-"$APP_ID") ;;
  *)
    fail "CLI command name must follow <namespace>-$APP_ID, got: $CLI_NAME"
    ;;
esac

TOKEN="$(resolve_token || true)"
if [ -z "$TOKEN" ]; then
  printf 'service-manager token not found in OpenHouse canonical config: %s\n' "$SM_CONFIG" >&2
  exit 1
fi

if ! curl -fsS --max-time 2 "$SM_URL/api/v1/health" >/dev/null; then
  printf 'service-manager is not reachable at %s\n' "$SM_URL" >&2
  exit 1
fi

log "installing app code to $INSTALL_DIR"
mkdir -p "$INSTALL_DIR" "$DATA_DIR" "$INSTALL_DIR/public" "$INSTALL_DIR/src" "$INSTALL_DIR/bin" "$HOME/.local/bin"
cp "$SCRIPT_DIR/package.json" "$INSTALL_DIR/package.json"
cp "$SCRIPT_DIR/src/server.js" "$INSTALL_DIR/src/server.js"
cp "$SCRIPT_DIR/src/state.js" "$INSTALL_DIR/src/state.js"
cp "$SCRIPT_DIR/src/mcp-server.js" "$INSTALL_DIR/src/mcp-server.js"
cp "$SCRIPT_DIR/bin/memo-openhouse.js" "$INSTALL_DIR/bin/memo-openhouse.js"
cp "$SCRIPT_DIR/public/index.html" "$INSTALL_DIR/public/index.html"
cp "$SCRIPT_DIR/public/styles.css" "$INSTALL_DIR/public/styles.css"
cp "$SCRIPT_DIR/public/app.js" "$INSTALL_DIR/public/app.js"
chmod +x "$INSTALL_DIR/bin/memo-openhouse.js" "$INSTALL_DIR/src/mcp-server.js"
ln -sf "$INSTALL_DIR/bin/memo-openhouse.js" "$HOME/.local/bin/$CLI_NAME"

PAYLOAD_FILE="$(mktemp "${TMPDIR:-/tmp}/memo-openhouse-registry.XXXXXX.json")"
cleanup() {
  rm -f "$PAYLOAD_FILE"
}
trap cleanup EXIT INT TERM

python3 - "$PAYLOAD_FILE" "$SCRIPT_DIR" "$INSTALL_DIR" "$DATA_DIR" "$APP_HOST" "$APP_PORT" "$CLI_NAME" <<'PY'
import json
import pathlib
import sys

payload_file = pathlib.Path(sys.argv[1])
script_dir = pathlib.Path(sys.argv[2])
install_dir = pathlib.Path(sys.argv[3])
data_dir = pathlib.Path(sys.argv[4])
host = sys.argv[5]
port = int(sys.argv[6])
cli_name = sys.argv[7]
app_id = "memo-openhouse"
url = f"http://{host}:{port}/"
health_url = f"http://{host}:{port}/health"

component = {
    "schemaVersion": 1,
    "id": app_id,
    "title": "OpenHouse Memo",
    "description": "最小自定义 Web App 示例。",
    "kind": "app",
    "shellMenu": {
        "visible": True,
        "section": "apps",
        "order": 120,
        "entry": {"type": "webview", "url": url},
        "controlEntry": {
            "type": "service-control",
            "serviceNames": [app_id],
            "serviceRefs": [f"service-manager://services/{app_id}"],
        },
    },
    "smallphoneApp": {
        "visible": True,
        "section": "apps",
        "order": 120,
        "icon": "sparkles",
        "entry": {"type": "webview", "url": url},
        "controlEntry": {
            "type": "service-control",
            "serviceNames": [app_id],
            "serviceRefs": [f"service-manager://services/{app_id}"],
        },
    },
    "serviceManager": {
        "required": True,
        "services": [
            {
                "name": app_id,
                "title": "OpenHouse Memo",
                "role": "web",
                "port": port,
                "url": url,
                "serviceRef": f"service-manager://services/{app_id}",
                "health": {"type": "http", "url": health_url},
                "controls": ["status", "start", "stop", "restart", "logs", "repair"],
                "repairActionRef": f"service-manager://actions/{app_id}.repair",
            }
        ],
    },
    "ai": {
        "visible": True,
        "summaryDoc": f"/root/.config/openhouseai/ai-docs/{app_id}/openhouse.ai.md",
        "capabilities": f"/root/.config/openhouseai/ai-docs/{app_id}/capabilities.json",
        "intents": [
            {"name": "open", "target": "smallphoneApp.entry"},
            {"name": "control", "target": "smallphoneApp.controlEntry"},
            {"name": "cli", "target": "ai.capabilities"},
            {"name": "mcp", "target": "ai.capabilities"},
        ],
    },
}

service = {
    "schemaVersion": 1,
    "id": app_id,
    "service": {
        "name": app_id,
        "description": "OpenHouse Memo custom app example",
        "provider": "process",
        "command": ["node", "src/server.js"],
        "working_dir": str(install_dir),
        "env": {
            "HOST": host,
            "PORT": str(port),
            "OPENHOUSE_CUSTOM_APP_DATA_DIR": str(data_dir),
        },
        "runtime": {},
        "restart": {"mode": "always", "max_retries": 0},
        "health": [
            {
                "type": "http",
                "url": health_url,
                "interval": "30s",
                "timeout": "5s",
            }
        ],
        "enabled": True,
        "tags": [
            "openhouseai",
            "smallphone",
            "group:local-stack",
            f"openhouse-component:{app_id}",
            f"smallphone-app:{app_id}",
        ],
    },
}

payload = {
    "components": [component],
    "services": [service],
    "aiDocs": [
        {
            "path": f"{app_id}/openhouse.ai.md",
            "content": (script_dir / "ai-docs" / "openhouse.ai.md").read_text(encoding="utf-8").replace("demo-memo-openhouse", cli_name),
        },
        {
            "path": f"{app_id}/capabilities.json",
            "content": (script_dir / "ai-docs" / "capabilities.json").read_text(encoding="utf-8").replace("demo-memo-openhouse", cli_name),
        },
    ],
}

payload_file.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY

log "applying OpenHouse registry"
curl -fsS \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -X POST \
  --data-binary "@$PAYLOAD_FILE" \
  "$SM_URL/api/v1/registry/apply" >/dev/null

log "starting service $APP_ID"
curl -fsS \
  -H "Authorization: Bearer $TOKEN" \
  -X POST \
  "$SM_URL/api/v1/services/$APP_ID/start" >/dev/null || true

log "$APP_TITLE registered"
log "app url: http://$APP_HOST:$APP_PORT/"
log "cli: $CLI_NAME"
log "service status: $SM_URL/api/v1/services/$APP_ID/status"
