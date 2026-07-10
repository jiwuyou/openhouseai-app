#!/usr/bin/env bash
set -eu

APP_ID="github-config-helper"
APP_TITLE="GitHub 配置助手"
APP_HOST="${GITHUB_CONFIG_HELPER_HOST:-127.0.0.1}"
APP_PORT="${GITHUB_CONFIG_HELPER_PORT:-23120}"
SM_URL="${SERVICE_MANAGER_URL:-http://127.0.0.1:20087}"
START_SERVICE="${START_SERVICE:-1}"

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
DATA_DIR="$SCRIPT_DIR/data"

log() {
  printf '[%s] %s\n' "$APP_ID" "$*"
}

fail() {
  printf '[%s] error: %s\n' "$APP_ID" "$*" >&2
  exit 1
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"
}

read_token_from_config() {
  local config
  local token

  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${HOME:+$HOME/.config/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json" \
    "/data/data/com.termux/files/home/.config/service-manager/config.json"; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    token="$(sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$config" | head -n 1 || true)"
    if [ -n "$token" ]; then
      printf '%s' "$token"
      return 0
    fi
  done

  return 1
}

resolve_token() {
  if [ -n "${SERVICE_MANAGER_TOKEN:-}" ]; then
    printf '%s' "$SERVICE_MANAGER_TOKEN"
    return 0
  fi
  if [ -n "${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}" ]; then
    printf '%s' "$SMALLPHONE_SERVICE_MANAGER_TOKEN"
    return 0
  fi
  if command -v service-manager >/dev/null 2>&1; then
    service-manager token show 2>/dev/null | head -n 1 | tr -d '\r\n' && return 0
  fi
  read_token_from_config
}

write_curl_config() {
  local target="$1"
  local token="$2"

  umask 077
  printf 'header = "Authorization: Bearer %s"\n' "$token" > "$target"
}

need_cmd curl
need_cmd node

mkdir -p "$DATA_DIR" "$SCRIPT_DIR/logs"
chmod 700 "$DATA_DIR" "$SCRIPT_DIR/logs" 2>/dev/null || true

TOKEN="$(resolve_token || true)"
[ -n "$TOKEN" ] || fail "service-manager token not found. Run: service-manager token show"

curl -fsS --max-time 3 "$SM_URL/api/v1/health" >/dev/null \
  || fail "service-manager is not reachable at $SM_URL"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/github-config-helper-register.XXXXXX")" || fail "cannot create temp dir"
cleanup() {
  rm -rf "$TMP_DIR" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

CURL_CFG="$TMP_DIR/curl.cfg"
PAYLOAD_FILE="$TMP_DIR/registry-apply.json"
write_curl_config "$CURL_CFG" "$TOKEN"

node - "$SCRIPT_DIR" "$DATA_DIR" "$APP_HOST" "$APP_PORT" "$PAYLOAD_FILE" <<'NODE'
const fs = require('fs');
const path = require('path');

const [scriptDir, dataDir, host, portText, payloadFile] = process.argv.slice(2);
const appId = 'github-config-helper';
const port = Number(portText);
const url = `http://${host}:${port}/`;
const healthUrl = `http://${host}:${port}/health`;
const ubuntuHome =
  process.env.SMALLPHONEAI_UBUNTU_HOME ||
  process.env.OPENHOUSEAI_UBUNTU_HOME ||
  process.env.HOME ||
  '/root';
const useExternalTermuxManager =
  process.env.SMALLPHONEAI_REQUIRE_EXTERNAL_SERVICE_MANAGER === '1' ||
  process.env.OPENHOUSE_SERVICE_MANAGER_RUNTIME === 'termux';

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(scriptDir, relativePath), 'utf8'));
}

const component = readJson('openhouse.component.json');
const service = readJson('service-manager.service.json');

component.shellMenu.entry.url = url;
component.shellMenu.controlEntry.serviceNames = [appId];
component.shellMenu.controlEntry.serviceRefs = [`service-manager://services/${appId}`];
component.smallphoneApp.entry.url = url;
component.smallphoneApp.controlEntry.serviceNames = [appId];
component.smallphoneApp.controlEntry.serviceRefs = [`service-manager://services/${appId}`];
component.serviceManager.services = [
  {
    name: appId,
    title: 'GitHub 配置助手',
    role: 'web',
    port,
    url,
    serviceRef: `service-manager://services/${appId}`,
    health: { type: 'http', url: healthUrl },
    controls: ['status', 'start', 'stop', 'restart', 'logs', 'repair'],
    repairActionRef: `service-manager://actions/${appId}.repair`
  }
];

service.id = appId;
service.service.name = appId;
service.service.working_dir = scriptDir;
service.service.command = ['node', 'src/server.js'];
service.service.provider = useExternalTermuxManager ? 'proot-distro' : (service.service.provider || 'process');
service.service.runtime = useExternalTermuxManager ? { distro: 'ubuntu', home: ubuntuHome } : (service.service.runtime || {});
service.service.env = {
  ...(service.service.env || {}),
  NODE_ENV: 'production',
  GITHUB_CONFIG_HELPER_HOST: host,
  GITHUB_CONFIG_HELPER_PORT: String(port),
  GITHUB_CONFIG_HELPER_DATA_DIR: dataDir
};
service.service.health = [
  {
    type: 'http',
    url: healthUrl,
    interval: '30s',
    timeout: '5s'
  }
];

for (const forbidden of ['command', 'shell', 'script', 'args']) {
  if (Object.prototype.hasOwnProperty.call(component, forbidden)) {
    throw new Error(`component manifest must not contain ${forbidden}`);
  }
}

const payload = {
  components: [component],
  services: [service],
  aiDocs: [
    {
      path: `${appId}/openhouse.ai.md`,
      content: fs.readFileSync(path.join(scriptDir, 'ai-docs', 'openhouse.ai.md'), 'utf8')
    },
    {
      path: `${appId}/capabilities.json`,
      content: fs.readFileSync(path.join(scriptDir, 'ai-docs', 'capabilities.json'), 'utf8')
    }
  ]
};

fs.writeFileSync(payloadFile, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
NODE

log "applying OpenHouse registry through service-manager"
curl -q -fsS --max-time 15 \
  -K "$CURL_CFG" \
  -H "Content-Type: application/json" \
  -X POST \
  --data-binary "@$PAYLOAD_FILE" \
  "$SM_URL/api/v1/registry/apply" >/dev/null \
  || fail "service-manager registry apply failed"

curl -q -fsS --max-time 10 \
  -K "$CURL_CFG" \
  -X POST \
  "$SM_URL/api/v1/services/$APP_ID/register" >/dev/null \
  || fail "service-manager service register failed"

if [ "$START_SERVICE" = "1" ]; then
  log "starting service $APP_ID"
  curl -q -fsS --max-time 10 \
    -K "$CURL_CFG" \
    -X POST \
    "$SM_URL/api/v1/services/$APP_ID/start" >/dev/null \
    || fail "service-manager service start failed"
fi

log "$APP_TITLE registered"
log "url: http://$APP_HOST:$APP_PORT/"
