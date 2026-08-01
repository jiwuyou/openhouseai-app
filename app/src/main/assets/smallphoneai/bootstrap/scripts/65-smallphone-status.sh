#!/usr/bin/env bash
set -euo pipefail

mode="${1:-status}"

is_termux() {
  [ -n "${PREFIX:-}" ] && [ -d "${PREFIX:-}/bin" ] && [ -d "/data/data/com.termux/files" ]
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

read_openhouse_service_manager_endpoint() {
  local config key value
  for config in \
    "${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
    "${HOME:+$HOME/.config/openhouseai/service-manager/config.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.config/openhouseai/service-manager/config.json}"; do
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

configured_service_manager_bind() {
  local endpoint
  endpoint="$(read_openhouse_service_manager_endpoint || true)"
  if [ -n "$endpoint" ] && normalize_service_manager_bind "$endpoint"; then
    return
  fi
  if [ -n "${SERVICE_MANAGER_URL:-}" ] && normalize_service_manager_bind "$SERVICE_MANAGER_URL"; then
    return
  fi
  if [ -n "${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}" ]; then
    normalize_service_manager_bind "$SMALLPHONEAI_SERVICE_MANAGER_BIND"
    return
  fi
  printf '127.0.0.1:20087\n'
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
  bind="$(normalize_service_manager_bind "${endpoint:-$(configured_service_manager_bind)}")" || bind="127.0.0.1:20087"
  printf '%s://%s\n' "$scheme" "$bind"
}

detect_runtime() {
  if is_current_ubuntu; then
    printf 'ubuntu'
  elif is_termux; then
    printf 'termux'
  else
    printf 'unknown'
  fi
}

json_escape() {
  printf '%s' "$1" | sed \
    -e ':a;N;$!ba' \
    -e 's/\\/\\\\/g' \
    -e 's/"/\\"/g' \
    -e 's/\n/\\n/g' \
    -e 's/\r/\\r/g' \
    -e 's/\t/\\t/g'
}

json_string() {
  printf '"%s"' "$(json_escape "$1")"
}

json_nullable_string() {
  if [ -n "${1:-}" ]; then
    json_string "$1"
  else
    printf 'null'
  fi
}

bool() {
  if [ "$1" = "1" ]; then
    printf 'true'
  else
    printf 'false'
  fi
}

is_truthy() {
  case "${1:-}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

have() {
  command -v "$1" >/dev/null 2>&1
}

now_iso() {
  date -u '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || printf ''
}

epoch_to_iso() {
  local epoch="${1:-}"
  case "$epoch" in
    ""|*[!0-9]*)
      printf ''
      return
      ;;
  esac
  date -u -d "@$epoch" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null \
    || date -u -r "$epoch" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null \
    || printf ''
}

probe_url() {
  local url="$1"
  if have curl && curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
    printf '1'
  else
    printf '0'
  fi
}

probe_tcp() {
  local host="${1:-}"
  local port="${2:-}"
  case "$host" in
    ""|*[!A-Za-z0-9_.-]*)
      printf '0'
      return
      ;;
  esac
  case "$port" in
    ""|*[!0-9]*)
      printf '0'
      return
      ;;
  esac
  if [ "$port" -lt 1 ] || [ "$port" -gt 65535 ] || ! command -v timeout >/dev/null 2>&1; then
    printf '0'
    return
  fi
  if timeout 2 bash -c ': >/dev/tcp/$1/$2' _ "$host" "$port" >/dev/null 2>&1; then
    printf '1'
  else
    printf '0'
  fi
}

positive_int_or_default() {
  local value="${1:-}"
  local default_value="${2:-7}"
  case "$value" in
    ""|*[!0-9]*)
      printf '%s\n' "$default_value"
      ;;
    *)
      if [ "$value" -gt 0 ]; then
        printf '%s\n' "$value"
      else
        printf '%s\n' "$default_value"
      fi
      ;;
  esac
}

health_signature_dir() {
  local candidate
  for candidate in \
    "${OPENHOUSE_HEALTH_SIGNATURE_DIR:-}" \
    "${SMALLPHONEAI_HEALTH_SIGNATURE_DIR:-}"; do
    [ -n "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  for candidate in \
    "${HOME:+$HOME/.openhouseai/health-signatures}" \
    "${HOME:+$HOME/.smallphoneai/health-signatures}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.openhouseai/health-signatures}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.smallphoneai/health-signatures}"; do
    [ -n "$candidate" ] || continue
    [ -d "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  printf '%s\n' "${HOME:-/root}/.openhouseai/health-signatures"
}

find_health_signature_file() {
  local stage="$1"
  local dir candidate
  dir="$(health_signature_dir)"
  case "$stage" in
    1|stage1|bootstrap-stage-1)
      for candidate in \
        "$dir/bootstrap-stage-1.json" \
        "$dir/stage1-bootstrap-ai.json" \
        "$dir/phase1-bootstrap-ai.json" \
        "$dir/first-stage-bootstrap-ai.json" \
        "$dir/stage-one-bootstrap-ai.json"; do
        [ -f "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
      done
      ;;
    2|stage2|bootstrap-stage-2)
      for candidate in \
        "$dir/bootstrap-stage-2.json" \
        "$dir/stage2-bootstrap-ai.json" \
        "$dir/phase2-bootstrap-ai.json" \
        "$dir/second-stage-bootstrap-ai.json" \
        "$dir/stage-two-bootstrap-ai.json"; do
        [ -f "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
      done
      ;;
  esac
  return 1
}

read_json_key() {
  local file="$1"
  local key="$2"
  [ -f "$file" ] || return 1
  sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$file" | head -n 1
}

read_json_number_key() {
  local file="$1"
  local key="$2"
  [ -f "$file" ] || return 1
  sed -n "s/.*\"$key\"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p" "$file" | head -n 1
}

read_signature_signer() {
  local file="$1"
  local key value
  for key in signerId signedBy signer ai agent model; do
    value="$(read_json_key "$file" "$key" || true)"
    if [ -n "$value" ]; then
      printf '%s\n' "$value"
      return 0
    fi
  done
  return 1
}

read_signature_signed_at() {
  local file="$1"
  local key value
  for key in signedAt completedAt checkedAt timestamp; do
    value="$(read_json_key "$file" "$key" || true)"
    if [ -n "$value" ]; then
      printf '%s\n' "$value"
      return 0
    fi
  done
  return 1
}

write_health_signature() {
  local stage="$1"
  local signer="${2:-${SMALLPHONEAI_BOOTSTRAP_AI_SIGNER:-}}"
  local dir file tmp slot stage_label signed_at

  if [ -z "$signer" ]; then
    printf 'missing signer for bootstrap-stage-%s\n' "$stage" >&2
    exit 2
  fi

  dir="$(health_signature_dir)"
  mkdir -p "$dir"
  signed_at="$(now_iso)"
  case "$stage" in
    1|stage1|bootstrap-stage-1)
      stage="1"
      slot="first_bootstrap_ai"
      stage_label="bootstrap-stage-1"
      file="$dir/bootstrap-stage-1.json"
      ;;
    2|stage2|bootstrap-stage-2)
      stage="2"
      slot="second_bootstrap_ai"
      stage_label="bootstrap-stage-2"
      file="$dir/bootstrap-stage-2.json"
      ;;
    *)
      printf 'unknown bootstrap signature stage: %s\n' "$stage" >&2
      exit 2
      ;;
  esac

  tmp="$file.$$"
  {
    printf '{"schema":1,"slot":'
    json_string "$slot"
    printf ',"stage":'
    json_string "$stage_label"
    printf ',"agent":'
    json_string "$signer"
    printf ',"signer":'
    json_string "$signer"
    printf ',"signedAt":'
    json_string "$signed_at"
    printf '}\n'
  } > "$tmp"
  chmod 600 "$tmp" 2>/dev/null || true
  mv "$tmp" "$file"
}

full_health_check_last_file() {
  local candidate
  for candidate in \
    "${OPENHOUSE_FULL_HEALTH_CHECK_LAST_FILE:-}" \
    "${SMALLPHONEAI_FULL_HEALTH_CHECK_LAST_FILE:-}"; do
    [ -n "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  for candidate in \
    "${HOME:+$HOME/.openhouseai/health-checks/last-full-check.json}" \
    "${HOME:+$HOME/.openhouseai/health-checks/last-full-check}" \
    "${HOME:+$HOME/.smallphoneai/health-checks/last-full-check.json}" \
    "${HOME:+$HOME/.smallphoneai/health-checks/last-full-check}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.openhouseai/health-checks/last-full-check.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.openhouseai/health-checks/last-full-check}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.smallphoneai/health-checks/last-full-check.json}" \
    "${SMALLPHONEAI_TERMUX_HOME:+$SMALLPHONEAI_TERMUX_HOME/.smallphoneai/health-checks/last-full-check}"; do
    [ -n "$candidate" ] || continue
    [ -f "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  return 1
}

full_health_check_record_file() {
  local candidate
  for candidate in \
    "${OPENHOUSE_FULL_HEALTH_CHECK_LAST_FILE:-}" \
    "${SMALLPHONEAI_FULL_HEALTH_CHECK_LAST_FILE:-}"; do
    [ -n "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  printf '%s\n' "${HOME:-/root}/.openhouseai/health-checks/last-full-check.json"
}

full_health_check_config_file() {
  local candidate
  for candidate in \
    "${OPENHOUSE_FULL_HEALTH_CHECK_CONFIG_FILE:-}" \
    "${SMALLPHONEAI_FULL_HEALTH_CHECK_CONFIG_FILE:-}"; do
    [ -n "$candidate" ] && { printf '%s\n' "$candidate"; return 0; }
  done
  printf '%s\n' "${HOME:-/root}/.openhouseai/health-checks/config.env"
}

read_full_health_check_interval_days() {
  local value config
  value="${OPENHOUSE_FULL_HEALTH_CHECK_INTERVAL_DAYS:-${SMALLPHONEAI_FULL_HEALTH_CHECK_INTERVAL_DAYS:-}}"
  if [ -n "$value" ]; then
    positive_int_or_default "$value" 7
    return
  fi

  config="$(full_health_check_config_file)"
  if [ -f "$config" ]; then
    value="$(sed -n 's/^FULL_HEALTH_CHECK_INTERVAL_DAYS=//p' "$config" | tail -n 1)"
    positive_int_or_default "$value" 7
    return
  fi

  printf '7\n'
}

write_full_health_check_interval_days() {
  local value="${1:-}"
  local config tmp
  case "$value" in
    ""|*[!0-9]*|0)
      printf 'invalid full health check interval days: %s\n' "$value" >&2
      exit 2
      ;;
  esac
  config="$(full_health_check_config_file)"
  mkdir -p "$(dirname "$config")"
  tmp="$config.$$"
  printf 'FULL_HEALTH_CHECK_INTERVAL_DAYS=%s\n' "$value" > "$tmp"
  chmod 600 "$tmp" 2>/dev/null || true
  mv "$tmp" "$config"
}

write_full_health_check_record() {
  local file tmp epoch completed_at
  file="$(full_health_check_record_file)"
  mkdir -p "$(dirname "$file")"
  epoch="$(date +%s 2>/dev/null || printf '0')"
  completed_at="$(epoch_to_iso "$epoch")"
  [ -n "$completed_at" ] || completed_at="$(now_iso)"
  tmp="$file.$$"
  {
    printf '{"schema":1,"completedAt":'
    json_string "$completed_at"
    printf ',"epochSeconds":%s,"source":"bootstrap-health-check"}\n' "$epoch"
  } > "$tmp"
  chmod 600 "$tmp" 2>/dev/null || true
  mv "$tmp" "$file"
}

file_mtime_epoch() {
  local file="$1"
  stat -c %Y "$file" 2>/dev/null || return 1
}

read_full_health_check_value() {
  local file="$1"
  local key value
  [ -f "$file" ] || return 1
  for key in completedAt checkedAt timestamp; do
    value="$(read_json_key "$file" "$key" || true)"
    if [ -n "$value" ]; then
      printf '%s\n' "$value"
      return 0
    fi
  done
  for key in epochSeconds epoch; do
    value="$(read_json_number_key "$file" "$key" || true)"
    if [ -n "$value" ]; then
      printf '%s\n' "$value"
      return 0
    fi
  done
  value="$(head -n 1 "$file" 2>/dev/null | tr -d '\r' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' || true)"
  [ -n "$value" ] && { printf '%s\n' "$value"; return 0; }
  return 1
}

value_to_epoch() {
  local value="$1"
  case "$value" in
    ""|*[!0-9]*)
      date -d "$value" +%s 2>/dev/null || return 1
      ;;
    *)
      printf '%s\n' "$value"
      ;;
  esac
}

signature_stage_object() {
  local id="$1"
  local label="$2"
  local file="$3"
  local present="$4"
  local signer="$5"
  local signed_at="$6"
  local valid="$7"
  local bootstrap_stage="${8:-}"
  printf '{"id":'
  json_string "$id"
  printf ',"label":'
  json_string "$label"
  printf ',"bootstrapStage":'
  json_nullable_string "$bootstrap_stage"
  printf ',"agent":'
  json_string "$signer"
  printf ',"file":'
  json_string "$file"
  printf ',"present":%s,"valid":%s,"signer":' "$(bool "$present")" "$(bool "$valid")"
  json_string "$signer"
  printf ',"signedAt":'
  json_string "$signed_at"
  printf '}'
}

health_signoff_docs_array() {
  printf '['
  json_string "/root/openhouse/docs/OPENHOUSE_HEALTH_SIGNOFF.md"
  printf ','
  json_string "/root/openhouse/docs/openhouse-install-flow.md"
  printf ','
  json_string "/root/openhouse/docs/SERVICE_MANAGER.md"
  printf ','
  json_string "/root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md"
  printf ','
  json_string "/root/openhouseai-docs/official/OPENHOUSE_HEALTH_SIGNOFF.md"
  printf ']'
}

second_bootstrap_ai_copy_prompt() {
  cat <<'EOF'
你是 OpenHouseAI / SmallPhoneAI 的二阶段引导 AI。请在用户设备上独立完成二阶段健康检查签名。

必须先阅读这些文档，按存在的路径优先：
- /root/openhouse/docs/OPENHOUSE_HEALTH_SIGNOFF.md
- /root/openhouse/docs/openhouse-install-flow.md
- /root/openhouse/docs/SERVICE_MANAGER.md
- /root/openhouse/docs/OPENHOUSE_FIRST_CONFIGURATION.md
- 如果 /root/openhouse/docs 不存在，使用 /root/openhouseai-docs/official 下的同名文档。

任务：
1. 在 bootstrap.sh 所在目录运行 `bash bootstrap.sh status`，读取健康检查 JSON，重点查看 `healthCheck`、`healthSignatures` 或 `bootstrapAiSignatures`、运行栈 readiness、fullHealthCheckDue。
2. 确认一阶段引导 AI 已签名，但二阶段签名缺失或不合格；不要把一阶段签名当作完整通过。
3. 参考文档独立复核 service-manager、pi-agent/pi-web 或 SmallPhone 兼容服务、OpenHouseAI/AionUI(smallhouseai)、cc/codex/Claude Code/Codex 或其它可用 AI 工作台。
4. 签名只需要明文 agent 名，例如 pi、claude-code、codex、openhouseai、hermes 或其它第二个 AI 的稳定名称。
5. 不要在回复、日志或签名记录中写入 API key、token、Authorization、cookie 或其它密钥。
6. 如果复核通过，在 bootstrap.sh 所在目录执行，例如：
   `bash bootstrap.sh sign-second-bootstrap-ai codex`
   或 `bash bootstrap.sh sign-second-bootstrap-ai claude-code`
   或 `bash bootstrap.sh sign-second-bootstrap-ai openhouseai`
7. 再次运行 `bash bootstrap.sh status`，确认完整签名状态为 true；如果仍未通过，向用户报告缺项和下一步。
EOF
}

health_signature_feedback_object() {
  local copy_prompt=""
  case "$health_signature_status" in
    needs_second_bootstrap_ai_signature|needs_distinct_bootstrap_ai_signers)
      copy_prompt="$(second_bootstrap_ai_copy_prompt)"
      ;;
  esac

  printf '{"code":'
  json_string "$health_signature_status"
  printf ',"message":'
  json_string "$health_signature_message"
  printf ',"copyPrompt":'
  if [ -n "$copy_prompt" ]; then
    json_string "$copy_prompt"
  else
    printf 'null'
  fi
  printf ',"documents":'
  health_signoff_docs_array
  printf '}'
}

warning_array() {
  local first=1
  printf '['
  if [ "$health_signatures_complete" != "1" ]; then
    [ "$first" = "1" ] || printf ','
    json_string "$health_signature_message"
    first=0
  fi
  if [ "$full_health_check_due" = "1" ]; then
    [ "$first" = "1" ] || printf ','
    json_string "$full_health_check_message"
  fi
  printf ']'
}

if [ "$mode" = "hooks" ]; then
  cat <<'EOF'
{"schema":1,"product":"SmallPhoneAI","hooks":[{"id":"install","command":["bash","bootstrap.sh","install"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"full","command":["bash","bootstrap.sh","full"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"check","command":["bash","bootstrap.sh","check"],"output":"json","idempotent":true},{"id":"status","command":["bash","bootstrap.sh","status"],"output":"json","idempotent":true},{"id":"hooks","command":["bash","bootstrap.sh","hooks"],"output":"json","idempotent":true},{"id":"start","command":["bash","bootstrap.sh","start"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"repair","command":["bash","bootstrap.sh","repair"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"components","command":["bash","bootstrap.sh","components"],"idempotent":true},{"id":"sync-core-stack","command":["bash","bootstrap.sh","sync-core-stack"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}},{"id":"post-apk-update","command":["bash","bootstrap.sh","post-apk-update"],"idempotent":true,"reportsFinalHealth":true,"finalHealth":{"format":"json","source":"final stdout object"}}]}
EOF
  exit 0
fi

if is_termux && [ "${SMALLPHONEAI_STATUS_IN_UBUNTU:-1}" = "1" ]; then
  if command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- true >/dev/null 2>&1; then
    SMALLPHONEAI_STATUS_IN_UBUNTU=0 \
      proot-distro login ubuntu -- env \
        SMALLPHONEAI_COMPONENT_REPO_ROOT="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-/root/smallphoneai-repos}" \
        SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS="${SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS:-}" \
        SMALLPHONEAI_SERVICE_MANAGER_DIR="${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" \
        SMALLPHONEAI_CC_CONNECT_DIR="${SMALLPHONEAI_CC_CONNECT_DIR:-}" \
        SMALLPHONEAI_CC_CONNECT_DISABLED="${SMALLPHONEAI_CC_CONNECT_DISABLED:-}" \
        SMALLPHONEAI_DISABLE_CC_CONNECT="${SMALLPHONEAI_DISABLE_CC_CONNECT:-}" \
        SMALLPHONEAI_CC_CONNECT_HOST="${SMALLPHONEAI_CC_CONNECT_HOST:-}" \
        SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT="${SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT:-}" \
        SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT="${SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT:-}" \
        SMALLPHONEAI_SMALLPHONE_DIR="${SMALLPHONEAI_SMALLPHONE_DIR:-}" \
        OPENHOUSE_PI_AGENT_DIR="${OPENHOUSE_PI_AGENT_DIR:-${SMALLPHONEAI_PI_AGENT_DIR:-}}" \
        OPENHOUSE_PI_WEB_DIR="${OPENHOUSE_PI_WEB_DIR:-${SMALLPHONEAI_PI_WEB_DIR:-}}" \
        OPENHOUSE_PI_WEB_URL="${OPENHOUSE_PI_WEB_URL:-${PI_WEB_URL:-}}" \
        PI_WEB_URL="${PI_WEB_URL:-}" \
        SMALLPHONEAI_SERVICE_MANAGER_BIND="${SMALLPHONEAI_SERVICE_MANAGER_BIND:-}" \
        SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG="${SMALLPHONEAI_OPENHOUSE_SERVICE_MANAGER_CONFIG:-}" \
        SMALLPHONEAI_TERMUX_HOME="${SMALLPHONEAI_TERMUX_HOME:-$HOME}" \
        OPENHOUSE_HEALTH_SIGNATURE_DIR="${OPENHOUSE_HEALTH_SIGNATURE_DIR:-}" \
        SMALLPHONEAI_HEALTH_SIGNATURE_DIR="${SMALLPHONEAI_HEALTH_SIGNATURE_DIR:-}" \
        OPENHOUSE_FULL_HEALTH_CHECK_INTERVAL_DAYS="${OPENHOUSE_FULL_HEALTH_CHECK_INTERVAL_DAYS:-}" \
        SMALLPHONEAI_FULL_HEALTH_CHECK_INTERVAL_DAYS="${SMALLPHONEAI_FULL_HEALTH_CHECK_INTERVAL_DAYS:-}" \
        OPENHOUSE_FULL_HEALTH_CHECK_LAST_FILE="${OPENHOUSE_FULL_HEALTH_CHECK_LAST_FILE:-}" \
        SMALLPHONEAI_FULL_HEALTH_CHECK_LAST_FILE="${SMALLPHONEAI_FULL_HEALTH_CHECK_LAST_FILE:-}" \
        OPENHOUSE_FULL_HEALTH_CHECK_CONFIG_FILE="${OPENHOUSE_FULL_HEALTH_CHECK_CONFIG_FILE:-}" \
        SMALLPHONEAI_FULL_HEALTH_CHECK_CONFIG_FILE="${SMALLPHONEAI_FULL_HEALTH_CHECK_CONFIG_FILE:-}" \
        SMALLPHONEAI_BOOTSTRAP_AI_SIGNER="${SMALLPHONEAI_BOOTSTRAP_AI_SIGNER:-}" \
        SERVICE_MANAGER_URL="${SERVICE_MANAGER_URL:-}" \
        bash -s "$@" < "$0"
    exit $?
  fi
fi

case "$mode" in
  status|"")
    ;;
  check)
    write_full_health_check_record
    ;;
  record-full-health-check|mark-full-health-check|full-health-check-complete)
    write_full_health_check_record
    mode="status"
    ;;
  set-full-health-check-interval|configure-full-health-check-interval)
    write_full_health_check_interval_days "${2:-}"
    mode="status"
    ;;
  sign-first-bootstrap-ai|sign-first-bootstrap|first-bootstrap-ai-sign)
    write_health_signature 1 "${2:-}" "${3:-}" "${4:-}"
    mode="status"
    ;;
  sign-second-bootstrap-ai|sign-second-bootstrap|second-bootstrap-ai-sign)
    write_health_signature 2 "${2:-}" "${3:-}" "${4:-}"
    mode="status"
    ;;
  *)
    printf 'unknown health status mode: %s\n' "$mode" >&2
    exit 2
    ;;
esac

repo_root="${SMALLPHONEAI_COMPONENT_REPO_ROOT:-/root/smallphoneai-repos}"
allow_dev_component_paths() {
  case "${SMALLPHONEAI_ALLOW_DEV_COMPONENT_PATHS:-0}" in
    1|true|TRUE|True|yes|YES|Yes|on|ON|On)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

normalize_component_path() {
  local path="${1:-}"
  while [ "${path%/}" != "$path" ] && [ "$path" != "/" ]; do
    path="${path%/}"
  done
  printf '%s\n' "$path"
}

is_known_dev_component_path() {
  local path
  path="$(normalize_component_path "$1")"
  case "$path" in
    /root/projects/service-manager|/root/openhouse-connect-fresh|/root/cc-connect-fresh|/root/projects/smallphone/smallphone-active)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

default_path() {
  local repo_name="$1"
  shift
  local product_path="$repo_root/$repo_name"
  if allow_dev_component_paths; then
    local dev_path
    for dev_path in "$@"; do
      if [ -d "$dev_path" ]; then
        printf '%s\n' "$dev_path"
        return
      fi
    done
  fi
  printf '%s\n' "$product_path"
}

component_dir_from_env() {
  local env_value="$1"
  local repo_name="$2"
  shift 2
  local product_path="$repo_root/$repo_name"
  if [ -n "$env_value" ]; then
    if is_known_dev_component_path "$env_value" && ! allow_dev_component_paths; then
      printf '%s\n' "$product_path"
    else
      printf '%s\n' "$env_value"
    fi
    return
  fi
  default_path "$repo_name" "$@"
}

component_object() {
  local id="$1"
  local label="$2"
  local dir="$3"
  local enabled="${4:-1}"
  local present=0 install=0 check=0 register=0
  [ -d "$dir" ] && present=1
  [ -f "$dir/scripts/install.sh" ] && install=1
  [ -f "$dir/scripts/check.sh" ] && check=1
  [ -f "$dir/scripts/register-service.sh" ] && register=1
  printf '{"id":'
  json_string "$id"
  printf ',"label":'
  json_string "$label"
  printf ',"path":'
  json_string "$dir"
  printf ',"enabled":%s,"repoPresent":%s,"installScript":%s,"checkScript":%s,"registerServiceScript":%s}' \
    "$(bool "$enabled")" \
    "$(bool "$present")" "$(bool "$install")" "$(bool "$check")" "$(bool "$register")"
}

port_object() {
  local id="$1"
  local url="$2"
  local reachable="$3"
  local enabled="${4:-1}"
  printf '{"id":'
  json_string "$id"
  printf ',"url":'
  json_string "$url"
  printf ',"enabled":%s,"reachable":%s}' "$(bool "$enabled")" "$(bool "$reachable")"
}

control_test_object() {
  local id="$1"
  local label="$2"
  local service_id="$3"
  local url="$4"
  local reachable="$5"
  printf '{"id":'
  json_string "$id"
  printf ',"label":'
  json_string "$label"
  printf ',"serviceId":'
  json_string "$service_id"
  printf ',"url":'
  json_string "$url"
  printf ',"required":false,"reachable":%s}' "$(bool "$reachable")"
}

readiness_object() {
  local id="$1"
  local label="$2"
  local url="$3"
  local reachable="$4"
  local required="$5"
  local disabled="$6"
  local satisfied=0

  if [ "$disabled" = "1" ] || [ "$reachable" = "1" ]; then
    satisfied=1
  fi

  printf '{"id":'
  json_string "$id"
  printf ',"label":'
  json_string "$label"
  printf ',"url":'
  json_string "$url"
  printf ',"required":%s,"disabled":%s,"reachable":%s,"satisfied":%s}' \
    "$(bool "$required")" "$(bool "$disabled")" "$(bool "$reachable")" "$(bool "$satisfied")"
}

service_manager_dir="$(component_dir_from_env "${SMALLPHONEAI_SERVICE_MANAGER_DIR:-}" service-manager /root/projects/service-manager)"
cc_connect_dir="$(component_dir_from_env "${SMALLPHONEAI_CC_CONNECT_DIR:-}" openhouse-connect /root/openhouse-connect-fresh /root/cc-connect-fresh)"
smallphone_dir="$(component_dir_from_env "${SMALLPHONEAI_SMALLPHONE_DIR:-}" smallphone-active /root/projects/smallphone/smallphone-active)"
pi_agent_dir="$(component_dir_from_env "${OPENHOUSE_PI_AGENT_DIR:-${SMALLPHONEAI_PI_AGENT_DIR:-}}" pi-runtime /root/projects/pi)"
pi_web_dir="$(component_dir_from_env "${OPENHOUSE_PI_WEB_DIR:-${SMALLPHONEAI_PI_WEB_DIR:-}}" pi-web /root/projects/pi-web)"

sm_url="$(configured_service_manager_url)"
cc_host="${SMALLPHONEAI_CC_CONNECT_HOST:-127.0.0.1}"
cc_bridge_port="${SMALLPHONEAI_CC_CONNECT_BRIDGE_PORT:-21010}"
cc_management_port="${SMALLPHONEAI_CC_CONNECT_MANAGEMENT_PORT:-21020}"
cc_url="bridge=${cc_host}:${cc_bridge_port}, management=${cc_host}:${cc_management_port}"
smallphone_core_url="${SMALLPHONEAI_SMALLPHONE_CORE_URL:-http://127.0.0.1:22000/}"
smallphone_url="${SMALLPHONEAI_SMALLPHONE_URL:-http://127.0.0.1:22082/}"
pi_web_url="${OPENHOUSE_PI_WEB_URL:-${PI_WEB_URL:-http://127.0.0.1:30141/}}"
pi_runtime_host="${OPENHOUSE_PI_RUNTIME_HOST:-127.0.0.1}"
pi_runtime_port="${OPENHOUSE_PI_RUNTIME_PORT:-20765}"
pi_runtime_url="tcp://${pi_runtime_host}:${pi_runtime_port}"
likegirl_url="${SMALLPHONEAI_LIKEGIRL_URL:-http://127.0.0.1:23003/}"
likegirl_clone_url="${SMALLPHONEAI_LIKEGIRL_CLONE_URL:-http://127.0.0.1:23008/}"
health_signature_dir_value="$(health_signature_dir)"
health_signature_stage1_file="$(find_health_signature_file 1 || true)"
health_signature_stage2_file="$(find_health_signature_file 2 || true)"
health_signature_stage1_present=0
health_signature_stage2_present=0
health_signature_stage1_valid=0
health_signature_stage2_valid=0
health_signature_stage1_signer=""
health_signature_stage2_signer=""
health_signature_stage1_signed_at=""
health_signature_stage2_signed_at=""
if [ -n "$health_signature_stage1_file" ]; then
  health_signature_stage1_present=1
  health_signature_stage1_signer="$(read_signature_signer "$health_signature_stage1_file" || true)"
  health_signature_stage1_signed_at="$(read_signature_signed_at "$health_signature_stage1_file" || true)"
  [ -n "$health_signature_stage1_signer" ] && health_signature_stage1_valid=1
fi
if [ -n "$health_signature_stage2_file" ]; then
  health_signature_stage2_present=1
  health_signature_stage2_signer="$(read_signature_signer "$health_signature_stage2_file" || true)"
  health_signature_stage2_signed_at="$(read_signature_signed_at "$health_signature_stage2_file" || true)"
  [ -n "$health_signature_stage2_signer" ] && health_signature_stage2_valid=1
fi
health_signatures_complete=0
health_signature_status="missing"
if [ "$health_signature_stage1_valid" = "1" ] && [ "$health_signature_stage2_valid" = "1" ]; then
  if [ "$health_signature_stage1_signer" = "$health_signature_stage2_signer" ]; then
    health_signature_status="needs_distinct_bootstrap_ai_signers"
  else
    health_signature_status="complete"
    health_signatures_complete=1
  fi
elif [ "$health_signature_stage1_valid" = "1" ] && [ "$health_signature_stage2_valid" != "1" ]; then
  health_signature_status="needs_second_bootstrap_ai_signature"
elif [ "$health_signature_stage1_valid" != "1" ] && [ "$health_signature_stage2_valid" = "1" ]; then
  health_signature_status="needs_first_bootstrap_ai_signature"
elif [ "$health_signature_stage1_present" = "1" ] || [ "$health_signature_stage2_present" = "1" ]; then
  health_signature_status="invalid"
fi
case "$health_signature_status" in
  complete)
    health_signature_message="一阶段和二阶段引导 AI 签名均已完成，且签名 AI 不同。"
    ;;
  needs_second_bootstrap_ai_signature)
    health_signature_message="仅完成一阶段引导 AI 签名，不能视作完整签名通过；一阶段 AI 需要帮助用户启用一个可用的第二个 AI，并把可复制提示词交给第二个 AI 完成二阶段引导签名。"
    ;;
  needs_first_bootstrap_ai_signature)
    health_signature_message="检测到二阶段引导 AI 签名，但缺少一阶段引导 AI 签名；不能视作完整签名通过。"
    ;;
  needs_distinct_bootstrap_ai_signers)
    health_signature_message="一阶段和二阶段引导签名来自同一个 AI；请找第二个 AI 重新完成二阶段引导签名，当前不能视作完整签名通过。"
    ;;
  invalid)
    health_signature_message="检测到引导签名文件，但缺少可识别的 signerId/signedBy/signer/ai 字段；不能视作完整签名通过。"
    ;;
  *)
    health_signature_message="尚未完成一阶段和二阶段引导 AI 签名；完整健康检查需要两个不同 AI 分别签名。"
    ;;
esac

full_health_check_interval_days="$(read_full_health_check_interval_days)"
full_health_check_interval_seconds=$((full_health_check_interval_days * 86400))
full_health_check_file="$(full_health_check_last_file || true)"
full_health_check_value=""
full_health_check_epoch=0
full_health_check_at=""
full_health_check_due=1
if [ -n "$full_health_check_file" ]; then
  full_health_check_value="$(read_full_health_check_value "$full_health_check_file" || true)"
  if [ -n "$full_health_check_value" ]; then
    full_health_check_epoch="$(value_to_epoch "$full_health_check_value" || printf '0')"
  fi
  if [ "$full_health_check_epoch" = "0" ]; then
    full_health_check_epoch="$(file_mtime_epoch "$full_health_check_file" || printf '0')"
  fi
fi
full_health_check_at="$full_health_check_value"
case "$full_health_check_at" in
  ""|*[!0-9]*)
    ;;
  *)
    full_health_check_at="$(epoch_to_iso "$full_health_check_at")"
    ;;
esac
if [ -z "$full_health_check_at" ] && [ "$full_health_check_epoch" -gt 0 ]; then
  full_health_check_at="$(epoch_to_iso "$full_health_check_epoch")"
fi
now_epoch="$(date +%s 2>/dev/null || printf '0')"
if [ "$full_health_check_epoch" -gt 0 ] && [ "$now_epoch" -gt 0 ]; then
  full_health_check_age_seconds=$((now_epoch - full_health_check_epoch))
  [ "$full_health_check_age_seconds" -lt 0 ] && full_health_check_age_seconds=0
  if [ "$full_health_check_age_seconds" -lt "$full_health_check_interval_seconds" ]; then
    full_health_check_due=0
  fi
else
  full_health_check_age_seconds=0
fi
if [ "$full_health_check_due" = "1" ]; then
  if [ "$full_health_check_epoch" -gt 0 ]; then
    full_health_check_message="距离上次全面健康检查已超过 ${full_health_check_interval_days} 天；建议重新执行全面检查并刷新两阶段引导 AI 签名。"
  else
    full_health_check_message="尚未记录上次全面健康检查时间；建议执行全面检查并完成两阶段引导 AI 签名。"
  fi
else
  full_health_check_message="全面健康检查仍在 ${full_health_check_interval_days} 天提醒周期内。"
fi
cc_connect_disabled=0
if is_truthy "${SMALLPHONEAI_CC_CONNECT_DISABLED:-${SMALLPHONEAI_DISABLE_CC_CONNECT:-0}}"; then
  cc_connect_disabled=1
fi
cc_connect_enabled=1
cc_connect_required=0
if [ "$cc_connect_disabled" = "1" ]; then
  cc_connect_enabled=0
fi

sm_reachable="$(probe_url "$sm_url/api/v1/health")"
if [ "$sm_reachable" != "1" ]; then
  sm_reachable="$(probe_url "$sm_url/")"
fi
cc_bridge_reachable="$(probe_tcp "$cc_host" "$cc_bridge_port")"
cc_management_reachable="$(probe_tcp "$cc_host" "$cc_management_port")"
cc_reachable=0
if [ "$cc_bridge_reachable" = "1" ] && [ "$cc_management_reachable" = "1" ]; then
  cc_reachable=1
fi
smallphone_core_reachable="$(probe_url "$smallphone_core_url")"
smallphone_reachable="$(probe_url "$smallphone_url")"
pi_web_reachable="$(probe_url "$pi_web_url")"
pi_runtime_reachable="$(probe_tcp "$pi_runtime_host" "$pi_runtime_port")"
likegirl_reachable="$(probe_url "$likegirl_url")"
likegirl_clone_reachable="$(probe_url "$likegirl_clone_url")"
pi_agent_satisfied=0
if [ -d "$pi_agent_dir" ] \
  && [ -x "$pi_agent_dir/bin/wuxianpi" ] \
  && [ -x "$pi_agent_dir/bin/wuxianpi-node" ] \
  && [ -x "$pi_agent_dir/bin/wuxianpi-node-start" ] \
  && [ -f "$pi_agent_dir/node/dist/index.js" ] \
  && [ -f "$pi_agent_dir/scripts/install.sh" ] \
  && [ -f "$pi_agent_dir/scripts/check.sh" ] \
  && [ -f "$pi_agent_dir/scripts/register-service.sh" ]; then
  pi_agent_satisfied=1
fi
ready=0
if [ "$sm_reachable" = "1" ] \
  && [ "$pi_agent_satisfied" = "1" ] \
  && [ "$pi_web_reachable" = "1" ] \
  && [ "$smallphone_reachable" = "1" ] \
  && [ "$smallphone_core_reachable" = "1" ]; then
  ready=1
fi

state="missing"
if [ "$ready" = "1" ]; then
  state="ready"
elif [ "$sm_reachable" = "1" ] || [ -d "$service_manager_dir" ] || [ -d "$cc_connect_dir" ] || [ -d "$pi_agent_dir" ] || [ -d "$pi_web_dir" ] || [ -d "$smallphone_dir" ]; then
  state="partial"
fi

printf '{"schema":1,"product":"SmallPhoneAI","state":'
json_string "$state"
printf ',"runtime":'
json_string "$(detect_runtime)"
printf ',"ready":%s' "$(bool "$ready")"
printf ',"completeSignaturePass":%s' "$(bool "$health_signatures_complete")"
printf ',"fullHealthCheckDue":%s' "$(bool "$full_health_check_due")"
printf ',"full_health_check_due":%s' "$(bool "$full_health_check_due")"
printf ',"bootstrap_ai_signature_complete":%s,"bootstrap_ai_signature_status":' "$(bool "$health_signatures_complete")"
json_string "$health_signature_status"
printf ',"readiness":{"ready":%s,"requirements":[' "$(bool "$ready")"
readiness_object "service-manager" "service-manager API" "$sm_url" "$sm_reachable" "1" "0"
printf ','
readiness_object "yuanshengwuxianpi" "WuxianPi Node runtime" "$pi_runtime_url" "$pi_runtime_reachable" "0" "0"
printf ','
readiness_object "pi-web" "Pi Web main agent UI" "$pi_web_url" "$pi_web_reachable" "1" "0"
printf ','
readiness_object "cc-connect-bridge" "cc-connect/openhouse-connect bridge and management" "$cc_url" "$cc_reachable" "$cc_connect_required" "$cc_connect_disabled"
printf ','
readiness_object "smallphone" "SmallPhone frontend compatibility service" "$smallphone_url" "$smallphone_reachable" "1" "0"
printf ','
readiness_object "smallphone-core" "SmallPhone core compatibility API" "$smallphone_core_url" "$smallphone_core_reachable" "1" "0"
printf ']}'
printf ',"components":['
component_object "service-manager" "service-manager" "$service_manager_dir"
printf ','
component_object "cc-connect" "cc-connect/openhouse-connect" "$cc_connect_dir" "$cc_connect_enabled"
printf ','
component_object "smallphone" "SmallPhone" "$smallphone_dir"
printf ','
component_object "yuanshengwuxianpi" "pi-agent" "$pi_agent_dir"
printf ','
component_object "pi-web" "pi-web" "$pi_web_dir"
printf '],"ports":['
port_object "service-manager" "$sm_url" "$sm_reachable"
printf ','
port_object "yuanshengwuxianpi" "$pi_runtime_url" "$pi_runtime_reachable"
printf ','
port_object "cc-connect-bridge" "tcp://${cc_host}:${cc_bridge_port}" "$cc_bridge_reachable" "$cc_connect_enabled"
printf ','
port_object "cc-connect-management" "tcp://${cc_host}:${cc_management_port}" "$cc_management_reachable" "$cc_connect_enabled"
printf ','
port_object "smallphone-core" "$smallphone_core_url" "$smallphone_core_reachable"
printf ','
port_object "smallphone" "$smallphone_url" "$smallphone_reachable"
printf ','
port_object "pi-web" "$pi_web_url" "$pi_web_reachable"
printf ','
port_object "smallphone-likegirl" "$likegirl_url" "$likegirl_reachable"
printf ','
port_object "smallphone-likegirl-clone" "$likegirl_clone_url" "$likegirl_clone_reachable"
printf '],"controlTests":['
control_test_object "smallphone-likegirl" "smallphone-likegirl control test" "smallphone-standalone-like-girl" "$likegirl_url" "$likegirl_reachable"
printf ','
control_test_object "smallphone-likegirl-clone" "smallphone-likegirl clone control test" "smallphone-standalone-like-girl-clone" "$likegirl_clone_url" "$likegirl_clone_reachable"
printf '],"healthSignatures":{"schema":1,"scope":"bootstrap-guidance","complete":%s,"status":' "$(bool "$health_signatures_complete")"
json_string "$health_signature_status"
printf ',"message":'
json_string "$health_signature_message"
printf ',"feedback":'
health_signature_feedback_object
printf ',"directory":'
json_string "$health_signature_dir_value"
printf ',"policy":{"requiredSlots":["first_bootstrap_ai","second_bootstrap_ai"],"requiredStages":["bootstrap-stage-1","bootstrap-stage-2"],"requiresDistinctAi":true,"stage1Label":"一阶段引导 AI 签名","stage2Label":"二阶段引导 AI 签名","partialStage1BlocksCompletePass":true},"stages":['
signature_stage_object "first_bootstrap_ai" "一阶段引导 AI 签名" "$health_signature_stage1_file" "$health_signature_stage1_present" "$health_signature_stage1_signer" "$health_signature_stage1_signed_at" "$health_signature_stage1_valid" "bootstrap-stage-1"
printf ','
signature_stage_object "second_bootstrap_ai" "二阶段引导 AI 签名" "$health_signature_stage2_file" "$health_signature_stage2_present" "$health_signature_stage2_signer" "$health_signature_stage2_signed_at" "$health_signature_stage2_valid" "bootstrap-stage-2"
printf ']}'
printf ',"fullHealthCheck":{"schema":1,"due":%s,"full_health_check_due":%s,"intervalDays":%s,"full_health_check_interval_days":%s,"intervalSeconds":%s,"lastFile":' "$(bool "$full_health_check_due")" "$(bool "$full_health_check_due")" "$full_health_check_interval_days" "$full_health_check_interval_days" "$full_health_check_interval_seconds"
json_string "$full_health_check_file"
printf ',"lastCompletedAt":'
json_nullable_string "$full_health_check_at"
printf ',"last_full_health_check_at":'
json_nullable_string "$full_health_check_at"
printf ',"lastCompletedEpoch":%s,"ageSeconds":%s,"message":' "$full_health_check_epoch" "$full_health_check_age_seconds"
json_string "$full_health_check_message"
printf '},"warnings":'
warning_array
printf ',"actions":{"install":["bash","bootstrap.sh","install"],"check":["bash","bootstrap.sh","check"],"status":["bash","bootstrap.sh","status"],"start":["bash","bootstrap.sh","start"],"repair":["bash","bootstrap.sh","repair"],"recordFullHealthCheck":["bash","bootstrap.sh","record-full-health-check"],"setFullHealthCheckInterval":["bash","bootstrap.sh","set-full-health-check-interval"],"signFirstBootstrapAi":["bash","bootstrap.sh","sign-first-bootstrap-ai"],"signSecondBootstrapAi":["bash","bootstrap.sh","sign-second-bootstrap-ai"]}}\n'
