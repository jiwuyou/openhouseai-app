#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
PROMPT_ASSETS="$REPO_ROOT/app/src/main/assets/openhouse/pi-prompts"
PAYLOAD="$REPO_ROOT/app/src/main/assets/openhouse/product-payloads/pi-web.tar"
BUILD_SCRIPT="$REPO_ROOT/scripts/build-pi-web-payload-phonetermux.sh"
MANIFEST="$REPO_ROOT/app/src/main/assets/openhouse/product-payloads/manifest.json"
PAYLOAD_MANIFEST="$REPO_ROOT/app/src/main/assets/openhouse/product-payloads/payload-manifest.json"
REQUIRED_BRANCH="${PI_WEB_REQUIRED_BRANCH:-openhouse}"
REQUIRED_COMMIT="${PI_WEB_REQUIRED_COMMIT:-82a025dbc98cf522f42e36d97972485f02712be8}"

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

prompt_names=(openhouse-first-config openhouse-docs openhouse-second-ai-handoff)
payload_members="$(tar -tf "$PAYLOAD" | sed 's#^\./##')"

for prompt_name in "${prompt_names[@]}"; do
  source_prompt="$PROMPT_ASSETS/$prompt_name.md"
  [ -s "$source_prompt" ] || fail "missing App-owned prompt asset: $source_prompt"
  printf '%s\n' "$payload_members" | grep -Fx "prompts/$prompt_name.md" >/dev/null \
    || fail "pi-web payload missing prompts/$prompt_name.md"
  payload_prompt="$(tar -xOf "$PAYLOAD" "./prompts/$prompt_name.md")"
  [ "$payload_prompt" = "$(cat "$source_prompt")" ] \
    || fail "payload prompt differs from App-owned source: $prompt_name"
  [ "$(sha256sum "$source_prompt" | awk '{print $1}')" = \
    "$(tar -xOf "$PAYLOAD" "./prompts/$prompt_name.md" | sha256sum | awk '{print $1}')" ] \
    || fail "payload prompt sha256 differs from App-owned source: $prompt_name"
  if printf '%s\n' "$payload_prompt" | grep -Eqi \
    '(^|[^A-Za-z0-9])(sk-[A-Za-z0-9_-]{12,}|Bearer[[:space:]]+[A-Za-z0-9._~+/-]{12,}|(api[_ -]?key|token|authorization|password)[[:space:]]*[:=][[:space:]]*[A-Za-z0-9._~+/-]{12,})'; then
    fail "prompt contains secret-like material: $prompt_name"
  fi
done

for contract in \
  'openhouse-first-config|# /openhouse-first-config' \
  'openhouse-first-config|$HOME/openhouse/docs' \
  'openhouse-first-config|/root/openhouse/docs' \
  'openhouse-first-config|$HOME/.local/share/openhouseai/handoffs/second-ai/latest' \
  'openhouse-first-config|agent identity' \
  'openhouse-first-config|HANDOFF.md' \
  'openhouse-first-config|system-check.json' \
  'openhouse-first-config|task.json' \
  'openhouse-first-config|不同' \
  'openhouse-first-config|pi-web 本身就是当前第一阶段的 Agent identity' \
  'openhouse-first-config|两个不同 Agent 可以使用相同模型' \
  'openhouse-first-config|MODEL_API_SETUP.md' \
  'openhouse-first-config|model-config-migration.md' \
  'openhouse-first-config|CLOUDCLI_CLAUDE_CODE.md' \
  'openhouse-docs|# /openhouse-docs' \
  'openhouse-docs|$HOME/openhouse/docs' \
  'openhouse-docs|/root/openhouse/docs' \
  'openhouse-docs|不是两个大模型' \
  'openhouse-second-ai-handoff|# /openhouse-second-ai-handoff' \
  'openhouse-second-ai-handoff|$HOME/.local/share/openhouseai/handoffs/second-ai/latest' \
  'openhouse-second-ai-handoff|agent identity' \
  'openhouse-second-ai-handoff|HANDOFF.md' \
  'openhouse-second-ai-handoff|system-check.json' \
  'openhouse-second-ai-handoff|task.json' \
  'openhouse-second-ai-handoff|两个 Agent 可以使用相同模型' \
  'openhouse-second-ai-handoff|不同'; do
  prompt_name="${contract%%|*}"
  required="${contract#*|}"
  tar -xOf "$PAYLOAD" "./prompts/$prompt_name.md" | grep -Fq "$required" \
    || fail "$prompt_name prompt missing: $required"
done

install_script="$(tar -xOf "$PAYLOAD" ./scripts/install.sh)"
printf '%s' "$install_script" | grep -Fq 'PI_AGENT_DIR="${PI_CODING_AGENT_DIR:-$HOME/.pi}"' \
  || fail 'install.sh does not default PI_AGENT_DIR to $HOME/.pi'
printf '%s' "$install_script" | grep -Fq 'PROMPT_DST="$PI_AGENT_DIR/prompts"' \
  || fail 'install target is not $HOME/.pi/prompts by default'
printf '%s' "$install_script" | grep -Fq 'install -m 600 "$PROMPT_SRC/$prompt_name.md"' \
  || fail 'install.sh does not copy prompts with private permissions'
for prompt_name in "${prompt_names[@]}"; do
  printf '%s' "$install_script" | grep -Fq "$prompt_name" \
    || fail "install.sh does not list prompt: $prompt_name"
done

start_script="$(tar -xOf "$PAYLOAD" ./bin/openhouse-pi-web-start)"
printf '%s' "$start_script" | grep -Fq 'PI_WEB_DEFAULT_CWD' \
  || fail 'launcher missing generic PI_WEB_DEFAULT_CWD'
if printf '%s' "$start_script" | grep -Eq \
  'OPENHOUSE_(PI_WEB_DEFAULT_CWD|DOCS_DIR|SCRIPTS_DIR|FIRST_CONFIG_STATE_PATH|SECOND_AI_HANDOFF_DIR)'; then
  fail 'launcher still injects product-specific OpenHouse environment'
fi

register_script="$(tar -xOf "$PAYLOAD" ./scripts/register-service.sh)"
printf '%s' "$register_script" | grep -Fq 'PI_WEB_DEFAULT_CWD' \
  || fail 'service registration missing generic PI_WEB_DEFAULT_CWD'
if printf '%s' "$register_script" | grep -Eq \
  'OPENHOUSE_(PI_WEB_DEFAULT_CWD|DOCS_DIR|SCRIPTS_DIR|FIRST_CONFIG_STATE_PATH|SECOND_AI_HANDOFF_DIR)'; then
  fail 'service environment embeds OpenHouse docs or handoff paths'
fi
for required in \
  '_openhouse_config="$CONFIG_DIR/service-manager/config.json"' \
  'config.auth_token' \
  'if [ -n "${SERVICE_MANAGER_TOKEN:-}" ]' \
  'service-manager token show'; do
  printf '%s' "$register_script" | grep -Fq "$required" \
    || fail "service token resolver missing: $required"
done
python3 - "$register_script" <<'PY'
import re
import sys

source = sys.argv[1]
markers = (
    '_openhouse_config="$CONFIG_DIR/service-manager/config.json"',
    "config.auth_token",
    'if [ -n "${SERVICE_MANAGER_TOKEN:-}" ]',
    "service-manager token show",
)
positions = tuple(source.index(marker) for marker in markers)
if positions != tuple(sorted(positions)):
    raise SystemExit("FAIL: OpenHouse auth_token must precede environment and CLI fallbacks")
resolver = source[source.index("resolve_token() {"):source.index("write_spec() {")]
if re.search(r"(?:log|warn|echo)\s+.*(?:auth_token|_openhouse_token)", resolver):
    raise SystemExit("FAIL: service token resolver logs the resolved token")
PY

grep -Fq 'app/src/main/assets/openhouse/pi-prompts' "$BUILD_SCRIPT" \
  || fail 'payload build script does not source App-owned prompts'
grep -Fq 'PI_WEB_REQUIRED_BRANCH:-openhouse' "$BUILD_SCRIPT" \
  || fail 'payload build script does not require the OpenHouse pi-web branch by default'
grep -Fq 'PI_WEB_REQUIRED_COMMIT:-82a025dbc98cf522f42e36d97972485f02712be8' "$BUILD_SCRIPT" \
  || fail 'payload build script does not pin the reviewed pi-web commit by default'

python3 - "$PAYLOAD" "$MANIFEST" "$PAYLOAD_MANIFEST" "$REQUIRED_BRANCH" "$REQUIRED_COMMIT" <<'PY'
import hashlib
import json
import os
import sys

payload_path, manifest_path, payload_manifest_path, required_branch, required_commit = sys.argv[1:]
digest = hashlib.sha256()
with open(payload_path, "rb") as handle:
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        digest.update(chunk)
actual_sha = digest.hexdigest()
actual_size = os.path.getsize(payload_path)

for path, array_name in ((manifest_path, "components"), (payload_manifest_path, "payloads")):
    with open(path, "r", encoding="utf-8") as handle:
        document = json.load(handle)
    entry = next((item for item in document.get(array_name, ()) if item.get("id") == "pi-web"), None)
    if entry is None:
        raise SystemExit(f"FAIL: {os.path.basename(path)} missing pi-web entry")
    if entry.get("sha256") != actual_sha or entry.get("size") != actual_size:
        raise SystemExit(f"FAIL: {os.path.basename(path)} pi-web sha256/size contract mismatch")
    if entry.get("sourceBranch") != required_branch:
        raise SystemExit(f"FAIL: {os.path.basename(path)} pi-web sourceBranch mismatch")
    if entry.get("sourceCommit") != required_commit:
        raise SystemExit(f"FAIL: {os.path.basename(path)} pi-web sourceCommit mismatch")
PY

printf 'pi-web prompt payload focused tests passed\n'
