#!/usr/bin/env bash
set -euo pipefail

SERVICE_ID="cloudcli"
PORT="${1:-${OPENHOUSE_CLOUDCLI_PORT:-${CLAUDE_CODE_UI_PORT:-23083}}}"
TERMUX_PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
TERMUX_HOME="${HOME:-/data/data/com.termux/files/home}"

log() {
  printf '[OpenHouseAI][cloudcli] %s\n' "$*"
}

warn() {
  printf '[OpenHouseAI][cloudcli] WARN: %s\n' "$*" >&2
}

die() {
  printf '[OpenHouseAI][cloudcli] ERROR: %s\n' "$*" >&2
  exit 1
}

setup_writable_tmpdir() {
  local dir
  for dir in \
    "${TMPDIR:-}" \
    "$TERMUX_PREFIX/tmp" \
    "$TERMUX_HOME/.tmp" \
    "$TERMUX_HOME/.cache/tmp"; do
    [ -n "$dir" ] || continue
    mkdir -p "$dir" 2>/dev/null || continue
    if [ -d "$dir" ] && [ -w "$dir" ]; then
      TMPDIR="$dir"
      export TMPDIR
      return 0
    fi
  done
  die "no writable temp directory is available for CloudCLI registration."
}

case "$PORT" in
  ''|*[!0-9]*)
    die "invalid CloudCLI port: $PORT"
    ;;
esac

setup_writable_tmpdir

is_current_ubuntu() {
  [ -r /etc/os-release ] && grep -qi 'ubuntu' /etc/os-release
}

require_ubuntu_runtime() {
  if is_current_ubuntu; then
    return 0
  fi
  command -v proot-distro >/dev/null 2>&1 || die "proot-distro not found; cannot register Ubuntu CloudCLI service."
  proot-distro login ubuntu -- true >/dev/null 2>&1 || die "Ubuntu runtime is not available; cannot register CloudCLI service."
}

install_ubuntu_service_wrapper() {
  if is_current_ubuntu; then
    OPENHOUSE_CLOUDCLI_PORT="$PORT" sh -s <<'UBUNTU'
set -eu
mkdir -p "$HOME/.local/bin" "$HOME/.cloudcli" "$HOME/.config/openhouseai" "$HOME/workspace"
cat > "$HOME/.local/bin/openhouse-cloudcli-service" <<'SCRIPT'
#!/usr/bin/env sh
set -eu
port="${1:-${OPENHOUSE_CLOUDCLI_PORT:-23083}}"
case "$port" in
  ''|*[!0-9]*)
    echo "invalid CloudCLI port: $port" >&2
    exit 64
    ;;
esac

export HOME="${HOME:-/root}"
export PWD="${PWD:-$HOME}"
cd "$HOME"
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"

if [ -f "$HOME/.config/openhouseai/claude-code-env" ]; then
  . "$HOME/.config/openhouseai/claude-code-env"
fi

if ! command -v cloudcli >/dev/null 2>&1; then
  echo "CloudCLI is not installed. Run the CloudCLI install stage first." >&2
  exit 3
fi

mkdir -p "$HOME/.cloudcli" "$HOME/.config/openhouseai" "$HOME/workspace"
printf '%s\n' "$port" > "$HOME/.config/openhouseai/claude-code-ui-port"
printf 'http://127.0.0.1:%s\n' "$port" > "$HOME/.config/openhouseai/claude-code-ui-url"

export HOST="${HOST:-127.0.0.1}"
export SERVER_PORT="$port"
export PORT="$port"
export BROWSER="${BROWSER:-/bin/true}"
export DATABASE_PATH="${DATABASE_PATH:-$HOME/.cloudcli/openhouse-auth.db}"
exec cloudcli --port "$port"
SCRIPT
chmod +x "$HOME/.local/bin/openhouse-cloudcli-service"
UBUNTU
  else
    proot-distro login ubuntu -- env OPENHOUSE_CLOUDCLI_PORT="$PORT" sh -s <<'UBUNTU'
set -eu
mkdir -p "$HOME/.local/bin" "$HOME/.cloudcli" "$HOME/.config/openhouseai" "$HOME/workspace"
cat > "$HOME/.local/bin/openhouse-cloudcli-service" <<'SCRIPT'
#!/usr/bin/env sh
set -eu
port="${1:-${OPENHOUSE_CLOUDCLI_PORT:-23083}}"
case "$port" in
  ''|*[!0-9]*)
    echo "invalid CloudCLI port: $port" >&2
    exit 64
    ;;
esac

export HOME="${HOME:-/root}"
export PWD="${PWD:-$HOME}"
cd "$HOME"
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"

if [ -f "$HOME/.config/openhouseai/claude-code-env" ]; then
  . "$HOME/.config/openhouseai/claude-code-env"
fi

if ! command -v cloudcli >/dev/null 2>&1; then
  echo "CloudCLI is not installed. Run the CloudCLI install stage first." >&2
  exit 3
fi

mkdir -p "$HOME/.cloudcli" "$HOME/.config/openhouseai" "$HOME/workspace"
printf '%s\n' "$port" > "$HOME/.config/openhouseai/claude-code-ui-port"
printf 'http://127.0.0.1:%s\n' "$port" > "$HOME/.config/openhouseai/claude-code-ui-url"

export HOST="${HOST:-127.0.0.1}"
export SERVER_PORT="$port"
export PORT="$port"
export BROWSER="${BROWSER:-/bin/true}"
export DATABASE_PATH="${DATABASE_PATH:-$HOME/.cloudcli/openhouse-auth.db}"
exec cloudcli --port "$port"
SCRIPT
chmod +x "$HOME/.local/bin/openhouse-cloudcli-service"
UBUNTU
  fi
}

read_openhouse_service_manager_endpoint() {
  local config key value
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json"; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    for key in listen_addr listenAddr base_url baseUrl baseURL url; do
      value="$(sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$config" | head -n 1 || true)"
      if [ -n "$value" ]; then
        printf '%s\n' "$value"
        return 0
      fi
    done
  done
  return 1
}

normalize_service_manager_bind() {
  local value="${1:-}"
  case "$value" in
    http://*) value="${value#http://}" ;;
    https://*) value="${value#https://}" ;;
  esac
  value="${value%%/*}"
  case "$value" in
    "") return 1 ;;
    :*) printf '127.0.0.1%s\n' "$value"; return 0 ;;
    0.0.0.0) printf '127.0.0.1\n'; return 0 ;;
    0.0.0.0:*) printf '127.0.0.1:%s\n' "${value#0.0.0.0:}"; return 0 ;;
    "::"|"[::]") printf '127.0.0.1\n'; return 0 ;;
    "[::]:"*) printf '127.0.0.1:%s\n' "${value#"[::]:"}"; return 0 ;;
    :::*) printf '127.0.0.1:%s\n' "${value#:::}"; return 0 ;;
    *[!0-9]*) printf '%s\n' "$value"; return 0 ;;
    *) printf '127.0.0.1:%s\n' "$value"; return 0 ;;
  esac
}

configured_service_manager_url() {
  local endpoint scheme bind
  endpoint="$(read_openhouse_service_manager_endpoint || true)"
  if [ -z "$endpoint" ]; then
    endpoint="${SERVICE_MANAGER_URL:-}"
  fi
  if [ -z "$endpoint" ] && [ -n "${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}" ]; then
    endpoint="$SMALLPHONEAI_SERVICE_MANAGER_BIND"
  fi
  case "$endpoint" in
    https://*) scheme="https" ;;
    *) scheme="http" ;;
  esac
  bind="$(normalize_service_manager_bind "${endpoint:-127.0.0.1:20087}")" || bind="127.0.0.1:20087"
  printf '%s://%s\n' "$scheme" "$bind"
}

read_config_token() {
  local config token
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}" \
    "/data/data/com.termux/files/home/.config/openhouseai/service-manager/config.json"; do
    [ -n "$config" ] && [ -f "$config" ] || continue
    token="$(sed -n 's/.*"auth_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$config" | head -n 1 || true)"
    if [ -n "$token" ]; then
      printf '%s\n' "$token"
      return 0
    fi
  done
  return 1
}

find_service_manager_binary() {
  local candidate
  if command -v service-manager >/dev/null 2>&1; then
    command -v service-manager
    return 0
  fi
  for candidate in \
    "${PREFIX:-/data/data/com.termux/files/usr}/bin/service-manager" \
    "$HOME/.local/bin/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/target/release/service-manager" \
    "$HOME/smallphoneai-repos/service-manager/service-manager"; do
    if [ -x "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

resolve_service_manager_token() {
  local token sm_bin
  token="${SERVICE_MANAGER_TOKEN:-${SMALLPHONE_SERVICE_MANAGER_TOKEN:-}}"
  if [ -n "$token" ]; then
    printf '%s\n' "$token"
    return 0
  fi
  token="$(read_config_token || true)"
  if [ -n "$token" ]; then
    printf '%s\n' "$token"
    return 0
  fi
  sm_bin="$(find_service_manager_binary || true)"
  if [ -n "$sm_bin" ]; then
    "$sm_bin" token show 2>/dev/null | head -n 1 | tr -d '\r\n'
    return 0
  fi
  return 1
}

curl_with_auth() {
  local method="$1"
  local path="$2"
  local body_file="${3:-}"
  local curl_cfg
  curl_cfg="$(mktemp "${TMPDIR:-/tmp}/cloudcli-service-curl.XXXXXX")"
  {
    printf 'header = "Authorization: Bearer %s"\n' "$SERVICE_MANAGER_TOKEN_RESOLVED"
    printf 'header = "Content-Type: application/json"\n'
  } > "$curl_cfg"
  if [ -n "$body_file" ]; then
    curl -q -fsS --max-time 20 -X "$method" -K "$curl_cfg" --data-binary "@$body_file" "$SERVICE_MANAGER_URL_RESOLVED$path"
  else
    curl -q -fsS --max-time 20 -X "$method" -K "$curl_cfg" "$SERVICE_MANAGER_URL_RESOLVED$path"
  fi
  local status=$?
  rm -f "$curl_cfg"
  return "$status"
}

write_registry_payload() {
  local payload_file="$1"
  cat > "$payload_file" <<JSON
{
  "services": [
    {
      "schemaVersion": 1,
      "id": "$SERVICE_ID",
      "service": {
        "name": "$SERVICE_ID",
        "description": "CloudCLI web service for the cc/codex entry",
        "provider": "proot-distro",
        "command": [
          "/root/.local/bin/openhouse-cloudcli-service",
          "$PORT"
        ],
        "working_dir": "/root",
        "env": {},
        "runtime": {
          "distro": "ubuntu",
          "home": "/root"
        },
        "restart": {
          "mode": "always",
          "max_retries": 0
        },
        "health": [
          {
            "type": "http",
            "url": "http://127.0.0.1:$PORT/health",
            "interval": "30s",
            "timeout": "5s"
          }
        ],
        "enabled": true,
        "tags": [
          "openhouseai",
          "cloudcli",
          "cc-codex",
          "group:ai-workbench",
          "openhouse-component:cloudcli"
        ]
      }
    }
  ]
}
JSON
}

apply_registry_payload() {
  local payload_file="$1"
  if curl_with_auth POST "/api/v1/registry/apply" "$payload_file" >/dev/null; then
    return 0
  fi

  warn "service-manager registry apply failed; trying to recreate cloudcli service if provider changed."
  curl_with_auth DELETE "/api/v1/services/$SERVICE_ID" >/dev/null 2>&1 || true
  curl_with_auth POST "/api/v1/registry/apply" "$payload_file" >/dev/null
}

require_ubuntu_runtime
install_ubuntu_service_wrapper

command -v curl >/dev/null 2>&1 || die "curl is required to register CloudCLI service."
SERVICE_MANAGER_URL_RESOLVED="$(configured_service_manager_url)"
SERVICE_MANAGER_TOKEN_RESOLVED="$(resolve_service_manager_token || true)"
[ -n "$SERVICE_MANAGER_TOKEN_RESOLVED" ] || die "service-manager token is unavailable."

if ! curl -fsS --max-time 3 "$SERVICE_MANAGER_URL_RESOLVED/api/v1/health" >/dev/null; then
  die "service-manager is not reachable at $SERVICE_MANAGER_URL_RESOLVED."
fi

payload_file="$(mktemp "${TMPDIR:-/tmp}/cloudcli-service-registry.XXXXXX.json")"
trap 'rm -f "$payload_file"' EXIT INT TERM
write_registry_payload "$payload_file"
apply_registry_payload "$payload_file"

log "CloudCLI service is registered: service-manager://services/$SERVICE_ID (port $PORT)"
