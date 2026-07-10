#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/_retry-profile.sh" ]; then
  # shellcheck source=_retry-profile.sh
  . "$SCRIPT_DIR/_retry-profile.sh"
fi

log() {
  printf '[SmallPhoneAI] %s\n' "$*"
}

run_logged() {
  log "+ $*"
  "$@"
}

is_current_ubuntu() {
  [ -f /etc/os-release ] && grep -qi '^ID=ubuntu' /etc/os-release
}

run_ubuntu_logged() {
  if is_current_ubuntu; then
    run_logged env \
      OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
      SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
      NPM_REGISTRY="${NPM_REGISTRY:-}" \
      NPM_CONFIG_REGISTRY="${NPM_CONFIG_REGISTRY:-${NPM_REGISTRY:-}}" \
      SMALLPHONEAI_NPM_FETCH_RETRIES="${SMALLPHONEAI_NPM_FETCH_RETRIES:-}" \
      SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:-}" \
      SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-}" \
      SMALLPHONEAI_NPM_FETCH_TIMEOUT="${SMALLPHONEAI_NPM_FETCH_TIMEOUT:-}" \
      "$@"
  else
    run_logged proot-distro login ubuntu -- env \
      OPENHOUSE_RETRY_MODE="${OPENHOUSE_RETRY_MODE:-normal}" \
      SMALLPHONEAI_RETRY_MODE="${SMALLPHONEAI_RETRY_MODE:-${OPENHOUSE_RETRY_MODE:-normal}}" \
      NPM_REGISTRY="${NPM_REGISTRY:-}" \
      NPM_CONFIG_REGISTRY="${NPM_CONFIG_REGISTRY:-${NPM_REGISTRY:-}}" \
      SMALLPHONEAI_NPM_FETCH_RETRIES="${SMALLPHONEAI_NPM_FETCH_RETRIES:-}" \
      SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:-}" \
      SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT="${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-}" \
      SMALLPHONEAI_NPM_FETCH_TIMEOUT="${SMALLPHONEAI_NPM_FETCH_TIMEOUT:-}" \
      "$@"
  fi
}

if ! is_current_ubuntu && { ! command -v proot-distro >/dev/null 2>&1 || ! proot-distro login ubuntu -- true >/dev/null 2>&1; }; then
  log "Ubuntu 不可用，请先运行：bash bootstrap.sh ubuntu"
  exit 2
fi

if [ -f "$SCRIPT_DIR/44-install-claude-code.sh" ]; then
  log "先检查 CloudCLI 依赖的 Claude Code native path。"
  run_logged bash "$SCRIPT_DIR/44-install-claude-code.sh"
else
  log "未找到 44-install-claude-code.sh，继续安装 CloudCLI，但 Claude Code 可能需要单独安装。"
fi

if command -v smallphoneai_log_retry_profile >/dev/null 2>&1; then
  smallphoneai_log_retry_profile '[SmallPhoneAI]'
fi

log "正在 Ubuntu 内安装或检查 ClaudeCodeUI / CloudCLI。"
run_ubuntu_logged bash -lc 'set -euo pipefail
export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"

if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
  echo "Node.js 尚未安装，请先执行 Node.js 安装阶段。" >&2
  exit 3
fi

major="$(node -p "process.versions.node.split(\".\")[0]" 2>/dev/null || printf 0)"
if [ "${major:-0}" -lt 24 ]; then
  echo "Node.js 版本过旧：$(node -v)，请先执行 Node.js 安装阶段。" >&2
  exit 4
fi

export DEBIAN_FRONTEND=noninteractive
if command -v apt-get >/dev/null 2>&1; then
  dpkg --configure -a
  apt-get update
  apt-get -f install -y
  if ! apt-get install -y python3 make g++ build-essential; then
    echo "Ubuntu apt install failed; refreshing package indexes and retrying with --fix-missing." >&2
    apt-get update
    apt-get install -y --fix-missing python3 make g++ build-essential
  fi
fi

mkdir -p "$HOME/.npm-global/bin" "$HOME/.cloudcli" "$HOME/.config/openhouseai" "$HOME/workspace"
npm config set prefix "$HOME/.npm-global"
npm config set registry "${NPM_REGISTRY:-https://registry.npmjs.org/}"
npm config set fetch-retries "${SMALLPHONEAI_NPM_FETCH_RETRIES:-5}"
npm config set fetch-retry-mintimeout "${SMALLPHONEAI_NPM_FETCH_RETRY_MINTIMEOUT:-20000}"
npm config set fetch-retry-maxtimeout "${SMALLPHONEAI_NPM_FETCH_RETRY_MAXTIMEOUT:-120000}"
npm config set fetch-timeout "${SMALLPHONEAI_NPM_FETCH_TIMEOUT:-600000}"

cloudcli_package_root() {
  npm_root="$(npm root -g 2>/dev/null || true)"
  for dir in \
    "$npm_root/@cloudcli-ai/cloudcli" \
    "$HOME/.npm-global/lib/node_modules/@cloudcli-ai/cloudcli" \
    "/usr/local/lib/node_modules/@cloudcli-ai/cloudcli"; do
    [ -n "$dir" ] && [ -d "$dir" ] && { printf "%s\n" "$dir"; return 0; }
  done
  return 1
}

patch_cloudcli_workspace_policy() {
  package_root="$(cloudcli_package_root)" || {
    echo "WARN: CloudCLI package root not found; skip workspace policy patch." >&2
    return 0
  }
  patched=0
  for target in \
    "$package_root/dist-server/server/shared/utils.js" \
    "$package_root/server/shared/utils.ts"; do
    [ -f "$target" ] || continue
    node - "$target" <<\NODE
const fs = require("fs");
const file = process.argv[2] || process.argv[1];
const sq = String.fromCharCode(39);
const dq = String.fromCharCode(34);
const text = fs.readFileSync(file, "utf8");
const lines = text.split(/\r?\n/).filter((line) => {
  const trimmed = line.trim();
  return ![
    `${sq}/root${sq},`,
    `${dq}/root${dq},`,
    `${sq}/root${sq}`,
    `${dq}/root${dq}`,
  ].includes(trimmed);
});
const next = lines.join("\n");
if (next !== text) fs.writeFileSync(file, next);
NODE
    patched=1
  done
  [ "$patched" = "1" ] || echo "WARN: CloudCLI workspace policy file not found under $package_root." >&2
}

seed_cloudcli_default_project() {
  mkdir -p "$HOME/workspace" "$HOME/.cloudcli"
  package_root="$(cloudcli_package_root)" || {
    echo "WARN: CloudCLI package root not found; skip default project seed." >&2
    return 0
  }
  CLOUDCLI_PACKAGE_ROOT="$package_root" \
  CLOUDCLI_DEFAULT_WORKSPACE="${CLOUDCLI_DEFAULT_WORKSPACE:-$HOME/workspace}" \
  DATABASE_PATH="${DATABASE_PATH:-$HOME/.cloudcli/openhouse-auth.db}" \
  WORKSPACES_ROOT="${WORKSPACES_ROOT:-$HOME}" \
    node --input-type=module <<\NODE || echo "WARN: CloudCLI default project seed failed; continuing." >&2
import path from "node:path";
import fs from "node:fs";
import { pathToFileURL } from "node:url";

const packageRoot = process.env.CLOUDCLI_PACKAGE_ROOT;
const workspace = process.env.CLOUDCLI_DEFAULT_WORKSPACE || "/root/workspace";
process.env.DATABASE_PATH = process.env.DATABASE_PATH || "/root/.cloudcli/openhouse-auth.db";
process.env.WORKSPACES_ROOT = process.env.WORKSPACES_ROOT || "/root";

const dbIndex = [
  path.join(packageRoot, "dist-server/server/modules/database/index.js"),
  path.join(packageRoot, "dist/server/modules/database/index.js"),
  path.join(packageRoot, "server/modules/database/index.js"),
].find((candidate) => fs.existsSync(candidate));
if (!dbIndex) {
  throw new Error("CloudCLI database module is unavailable");
}
const dbModule = await import(pathToFileURL(dbIndex).href);
if (typeof dbModule.initializeDatabase === "function") {
  await dbModule.initializeDatabase();
}
const projectsDb = dbModule.projectsDb || (dbModule.default && dbModule.default.projectsDb);
if (!projectsDb || typeof projectsDb.createProjectPath !== "function") {
  throw new Error("CloudCLI projectsDb.createProjectPath is unavailable");
}
let exists = false;
for (const method of ["getProjectByPath", "findByPath"]) {
  if (typeof projectsDb[method] === "function") {
    const found = projectsDb[method](workspace);
    if (found) exists = true;
  }
}
if (!exists && typeof projectsDb.getAllProjects === "function") {
  const all = projectsDb.getAllProjects();
  if (Array.isArray(all)) {
    exists = all.some((item) => String(item && (item.project_path || item.projectPath || item.path || "")).trim() === workspace);
  }
}
if (!exists) {
  try {
    projectsDb.createProjectPath(workspace, "workspace");
  } catch (error) {
    const message = String(error && error.message || error);
    if (!/exists|duplicate|unique/i.test(message)) throw error;
  }
}
NODE
}

install_timeout="${SMALLPHONEAI_NPM_INSTALL_TIMEOUT:-7200s}"
if command -v cloudcli >/dev/null 2>&1; then
  echo "CloudCLI 已安装：$(command -v cloudcli)"
  cloudcli version || cloudcli --version || true
else
  for attempt in 1 2 3; do
    echo "正在安装 @cloudcli-ai/cloudcli（第 $attempt 次，最长等待 $install_timeout）"
    if command -v timeout >/dev/null 2>&1; then
      if timeout -k 30s "$install_timeout" npm install -g @cloudcli-ai/cloudcli --no-audit --no-fund --loglevel=verbose; then
        break
      fi
    elif npm install -g @cloudcli-ai/cloudcli --no-audit --no-fund --loglevel=verbose; then
      break
    fi
    if [ "$attempt" -eq 3 ]; then
      echo "@cloudcli-ai/cloudcli 安装失败，请检查网络或 npm registry。" >&2
      exit 1
    fi
    sleep $((attempt * 10))
  done
fi

export PATH="$HOME/.local/node/bin:$HOME/.npm-global/bin:$HOME/.local/bin:/usr/local/bin:$PATH"
export WORKSPACES_ROOT="${WORKSPACES_ROOT:-$HOME}"
export DATABASE_PATH="${DATABASE_PATH:-$HOME/.cloudcli/openhouse-auth.db}"
test -x "$HOME/.local/bin/claude"
"$HOME/.local/bin/claude" --version || true
patch_cloudcli_workspace_policy
seed_cloudcli_default_project
command -v cloudcli
cloudcli version || cloudcli --version || true

printf "%s\n" "23083" > "$HOME/.config/openhouseai/claude-code-ui-port"
printf "%s\n" "http://127.0.0.1:23083" > "$HOME/.config/openhouseai/claude-code-ui-url"

PATH_LINE="export PATH=\"\$HOME/.local/node/bin:\$HOME/.local/bin:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH\""
for PROFILE_FILE in "$HOME/.profile" "$HOME/.bashrc"; do
  touch "$PROFILE_FILE"
  if ! grep -Fq "$PATH_LINE" "$PROFILE_FILE"; then
    {
      printf "\n# SmallPhoneAI agent tools\n"
      printf "%s\n" "$PATH_LINE"
    } >> "$PROFILE_FILE"
  fi
done'

find_cloudcli_register_script() {
  local dir
  for dir in \
    "${OPENHOUSEAI_MAINTAINER_DIR:-}" \
    "${SMALLPHONEAI_MAINTAINER_DIR:-}" \
    "$HOME/.smallphoneai-bootstrap/apk-assets/maintainer" \
    "$HOME/.smallphoneai-bootstrap/maintainer"; do
    [ -n "$dir" ] || continue
    if [ -f "$dir/register-cloudcli-service.sh" ]; then
      printf '%s\n' "$dir/register-cloudcli-service.sh"
      return 0
    fi
  done
  return 1
}

register_script="$(find_cloudcli_register_script || true)"
if [ -n "$register_script" ]; then
  log "正在把 CloudCLI 注册到 service-manager：cloudcli"
  run_logged bash "$register_script" "23083"
else
  log "未找到 CloudCLI service-manager 注册脚本；CloudCLI 已安装但暂未纳入服务控制。"
fi

log "ClaudeCodeUI / CloudCLI 安装阶段完成。"
