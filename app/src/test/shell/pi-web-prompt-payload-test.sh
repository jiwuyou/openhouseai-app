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
REQUIRED_COMMIT="${PI_WEB_REQUIRED_COMMIT:-19a4496149bf8198be1362e31d81d79b5d250051}"

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
  'openhouse-first-config|全程使用中文进行回答' \
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
  'openhouse-first-config|配置并真实测通 AionUI' \
  'openhouse-first-config|第二阶段 Agent 默认直接取 AionUI，identity 为 `aionui`，无需再次询问用户' \
  'openhouse-first-config|只有用户已经明确指定其它第二阶段 Agent 时，才用该选择覆盖 AionUI 默认值' \
  'openhouse-first-config|不要为了确认默认值再次询问用户' \
  'openhouse-first-config|发生覆盖时，对最终实际目标执行相同的模型配置复制和主动最小真实请求流程' \
  'openhouse-first-config|不把非默认选择称为高级或备用' \
  'openhouse-first-config|始终使用最终实际选定的 Agent 名称与 identity' \
  'openhouse-first-config|最小真实请求' \
  'openhouse-first-config|默认账号固定为 `openhouse`，默认密码固定为 `openhouse123`' \
  'openhouse-first-config|aionui-web resetpass --data-dir /root/.aionui-web' \
  'openhouse-first-config|`POST /login`' \
  'openhouse-first-config|`{"new_username":"openhouse"}`' \
  'openhouse-first-config|`{"new_password":"openhouse123"}`' \
  'openhouse-first-config|`123456` 过短' \
  'openhouse-first-config|不要未经实测声称“常见密码”必然被拒绝' \
  'openhouse-first-config|字段名必须是 `new_password`，不是 `newPassword`' \
  'openhouse-first-config|`"models":["deepseek-v4-pro"]`' \
  'openhouse-first-config|不得传成 `[{"id":"deepseek-v4-pro","name":"..."}]` 对象数组' \
  'openhouse-first-config|`deepseek-v4 pro` 中间带空格是错误 ID' \
  'openhouse-first-config|`deepseek-v4-flash` 也已验证成功' \
  'openhouse-first-config|创建一个新 AionRS conversation' \
  'openhouse-first-config|`extra.sessionMode="default"`' \
  'openhouse-first-config|HTTP 状态码精确为 `201`' \
  'openhouse-first-config|HTTP 状态码精确为 `202`' \
  'openhouse-first-config|assistant 最终文本只能原样回复该 nonce' \
  'openhouse-first-config|轮询 conversation 直到 `status=finished`' \
  'openhouse-first-config|消息历史响应的 `data.items`' \
  'openhouse-first-config|assistant 文本精确等于该 nonce' \
  'openhouse-first-config|不能只是包含 nonce 或表达对应语义' \
  'openhouse-first-config|页面可打开、health-check' \
  'openhouse-first-config|不得直接请求供应商 API 冒充 AionUI 测通' \
  'openhouse-first-config|一阶段 Agent 还必须亲自读取自身当前实际生效的模型配置' \
  'openhouse-first-config|一阶段 Agent 主动调用或驱动第二阶段 Agent 发起一次最小真实请求' \
  'openhouse-first-config|不能把这一步留给第二阶段 Agent 自行完成' \
  'openhouse-first-config|用户实际选定的第二阶段 Agent' \
  'openhouse-first-config|第二阶段 Agent 名称和 identity' \
  'openhouse-first-config|输出前必须把 Agent 名称和 identity 替换为实际值，不得保留占位符' \
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
  'openhouse-second-ai-handoff|默认由 AionUI 执行' \
  'openhouse-second-ai-handoff|task.json.status` 为 `completed' \
  'openhouse-second-ai-handoff|配置 Claude Code' \
  'openhouse-second-ai-handoff|配置 Codex' \
  'openhouse-second-ai-handoff|创建一个小型 Web App' \
  'openhouse-second-ai-handoff|跳过' \
  'openhouse-second-ai-handoff|CUSTOM_FRONTEND_AND_APPS.md' \
  'openhouse-second-ai-handoff|不同'; do
  prompt_name="${contract%%|*}"
  required="${contract#*|}"
  tar -xOf "$PAYLOAD" "./prompts/$prompt_name.md" | grep -Fq "$required" \
    || fail "$prompt_name prompt missing: $required"
done

first_config_prompt="$(tar -xOf "$PAYLOAD" ./prompts/openhouse-first-config.md)"
for forbidden in \
  '只有 AionUI 不可用，或者用户主动选择其它 Agent 时' \
  '默认明确交给 `aionui`'; do
  if printf '%s' "$first_config_prompt" | grep -Fq "$forbidden"; then
    fail "openhouse-first-config still fixes the second-stage Agent: $forbidden"
  fi
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
grep -Fq 'PI_WEB_REQUIRED_COMMIT:-19a4496149bf8198be1362e31d81d79b5d250051' "$BUILD_SCRIPT" \
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
