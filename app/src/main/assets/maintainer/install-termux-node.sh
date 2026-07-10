termux_node_log() {
  if type log >/dev/null 2>&1; then
    log "$@"
  else
    printf '[OpenHouseAI] %s\n' "$*" >&2
  fi
}

termux_node_run() {
  if type run_logged >/dev/null 2>&1; then
    run_logged "$@"
  else
    "$@"
  fi
}

termux_node_timeout() {
  local timeout_seconds="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout "$timeout_seconds" "$@"
  else
    "$@"
  fi
}

termux_node_apt_install() {
  local timeout_seconds="${OPENHOUSEAI_TERMUX_APT_INSTALL_TIMEOUT_SECONDS:-${SMALLPHONEAI_TERMUX_APT_INSTALL_TIMEOUT_SECONDS:-1800}}"
  termux_node_run termux_node_timeout "$timeout_seconds" apt \
    -o Acquire::Retries=2 \
    -o Acquire::http::Timeout=30 \
    -o Acquire::https::Timeout=30 \
    install -y "$@"
}

termux_node_apt_update() {
  local timeout_seconds="${OPENHOUSEAI_TERMUX_APT_UPDATE_TIMEOUT_SECONDS:-${SMALLPHONEAI_TERMUX_APT_UPDATE_TIMEOUT_SECONDS:-300}}"
  termux_node_run termux_node_timeout "$timeout_seconds" apt \
    -o Acquire::Retries=2 \
    -o Acquire::http::Timeout=30 \
    -o Acquire::https::Timeout=30 \
    update
}

termux_node_repair_package_state() {
  if command -v dpkg >/dev/null 2>&1; then
    dpkg --configure -a >/dev/null 2>&1 || true
  fi
  if command -v apt >/dev/null 2>&1; then
    apt -f install -y >/dev/null 2>&1 || true
  fi
}

termux_node_major() {
  node -p "process.versions.node.split('.')[0]" 2>/dev/null || printf 0
}

termux_node_configure_npm() {
  local npm_prefix profile_file path_line registry
  npm_prefix="$HOME/.npm-global"
  registry="${NPM_REGISTRY:-${NPM_CONFIG_REGISTRY:-https://registry.npmjs.org/}}"

  mkdir -p "$npm_prefix/bin" "$HOME/.config/openhouseai"
  npm config set prefix "$npm_prefix"
  npm config set registry "$registry"

  path_line='export PATH="$HOME/.npm-global/bin:$PREFIX/bin:$PATH"'
  for profile_file in "$HOME/.profile" "$HOME/.bashrc"; do
    touch "$profile_file"
    if ! grep -Fq "$path_line" "$profile_file"; then
      {
        printf '\n# OpenHouseAI Termux Node.js 24 LTS/npm runtime\n'
        printf '%s\n' "$path_line"
      } >> "$profile_file"
    fi
  done

  {
    printf 'TERMUX_NODE_PATH=%s\n' "$(command -v node || true)"
    printf 'TERMUX_NPM_PATH=%s\n' "$(command -v npm || true)"
    printf 'TERMUX_NODE_VERSION=%s\n' "$(node -v 2>/dev/null || true)"
    printf 'TERMUX_NPM_VERSION=%s\n' "$(npm -v 2>/dev/null || true)"
    printf 'TERMUX_NPM_PREFIX=%s\n' "$npm_prefix"
  } > "$HOME/.config/openhouseai/termux-node.env"
}

termux_node_ready() {
  local major
  command -v node >/dev/null 2>&1 || return 1
  command -v npm >/dev/null 2>&1 || return 1
  major="$(termux_node_major)"
  [ "${major:-0}" -ge 24 ]
}

termux_node_install_runtime_packages() {
  local node_package="$1"
  termux_node_apt_install "$node_package" python make clang pkg-config
}

if [ ! -d "${PREFIX:-}/bin" ] || [ ! -d "/data/data/com.termux/files" ]; then
  termux_node_log "Termux Node.js 24 LTS/npm 阶段必须在 Termux native 外层执行。"
  exit 2
fi

export PATH="$HOME/.npm-global/bin:${PREFIX:-/data/data/com.termux/files/usr}/bin:/system/bin:${PATH:-}"

if ! command -v apt >/dev/null 2>&1; then
  termux_node_log "缺少 apt，无法安装 Termux Node.js 24 LTS/npm。"
  exit 1
fi

termux_node_log "正在安装或检查 Termux native Node.js 24 LTS/npm。"

if ! termux_node_install_runtime_packages nodejs-lts; then
  termux_node_log "Termux nodejs-lts 安装失败，尝试修复包状态并刷新索引。"
  termux_node_repair_package_state
  termux_node_apt_update
  if ! termux_node_install_runtime_packages nodejs-lts; then
    termux_node_log "Termux nodejs-lts 仍不可用，尝试 fallback 到 nodejs 包。"
    termux_node_repair_package_state
    if ! termux_node_install_runtime_packages nodejs; then
      termux_node_log "Termux Node.js 24 LTS/npm 安装失败，请稍后重试。"
      exit 1
    fi
  fi
fi

hash -r 2>/dev/null || true

if ! termux_node_ready; then
  termux_node_log "Termux Node.js 24 LTS/npm 不满足 pi-agent/pi-web 要求：node=$(node -v 2>/dev/null || printf missing) npm=$(npm -v 2>/dev/null || printf missing)"
  exit 1
fi

termux_node_configure_npm

termux_node_log "Termux Node.js 24 LTS/npm 已就绪：node=$(node -v)，npm=$(npm -v)"
