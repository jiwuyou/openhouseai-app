require_ubuntu

PORT="__PORT__"

log "正在重启 ClaudeCodeUI / CloudCLI，端口 $PORT"
run_ubuntu_logged bash -lc "set -euo pipefail; pids=''; self=\$\$; proc_list=\"\$(mktemp)\"; trap 'rm -f \"\$proc_list\"' EXIT; ps -eo pid=,comm=,args= > \"\$proc_list\"; while read -r pid comm args; do [ -n \"\$pid\" ] || continue; [ \"\$pid\" = \"\$self\" ] && continue; case \"\$comm\" in node|cloudcli) ;; *) continue ;; esac; case \" \$args \" in *cloudcli*|*dist-server/server/index.js*) ;; *) continue ;; esac; case \" \$args \" in *'--port $PORT'*|*'--port=$PORT'*|*'SERVER_PORT=$PORT'*|*'SERVER_PORT $PORT'*) pids=\"\$pids \$pid\" ;; esac; done < \"\$proc_list\"; if [ -n \"\$pids\" ]; then kill \$pids 2>/dev/null || true; sleep 1; kill -9 \$pids 2>/dev/null || true; fi"
run_ubuntu_logged bash -lc "set -euo pipefail; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.local/bin:/usr/local/bin:\$PATH\"; if ! command -v cloudcli >/dev/null 2>&1; then echo '尚未安装 ClaudeCodeUI / CloudCLI，请先执行安装。' >&2; exit 3; fi; mkdir -p \"\$HOME/.cloudcli\" \"\$HOME/.config/openhouseai\" \"\$HOME/workspace\"; printf '%s\n' '$PORT' > \"\$HOME/.config/openhouseai/claude-code-ui-port\"; printf '%s\n' 'http://127.0.0.1:$PORT' > \"\$HOME/.config/openhouseai/claude-code-ui-url\""

if is_current_ubuntu; then
  nohup bash -lc "set -euo pipefail; export HOME=/root PWD=/root; cd /root; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.local/bin:/usr/local/bin:\$PATH\"; if [ -f \"\$HOME/.config/openhouseai/claude-code-env\" ]; then . \"\$HOME/.config/openhouseai/claude-code-env\"; fi; export HOST=127.0.0.1 SERVER_PORT=$PORT PORT=$PORT BROWSER=/bin/true; export DATABASE_PATH=\"\$HOME/.cloudcli/openhouse-auth.db\"; exec cloudcli --port $PORT >\"\$HOME/.cloudcli-web.log\" 2>&1" >>"$LOG_FILE" 2>&1 < /dev/null &
else
  nohup proot-distro login ubuntu -- bash -lc "set -euo pipefail; export HOME=/root PWD=/root; cd /root; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.local/bin:/usr/local/bin:\$PATH\"; if [ -f \"\$HOME/.config/openhouseai/claude-code-env\" ]; then . \"\$HOME/.config/openhouseai/claude-code-env\"; fi; export HOST=127.0.0.1 SERVER_PORT=$PORT PORT=$PORT BROWSER=/bin/true; export DATABASE_PATH=\"\$HOME/.cloudcli/openhouse-auth.db\"; exec cloudcli --port $PORT >\"\$HOME/.cloudcli-web.log\" 2>&1" >>"$LOG_FILE" 2>&1 < /dev/null &
fi

for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if curl -fsS --connect-timeout 2 --max-time 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
    log "ClaudeCodeUI / CloudCLI 已在端口 $PORT 重启完成。"
    exit 0
  fi
  log "正在等待 ClaudeCodeUI / CloudCLI 监听端口 $PORT"
  sleep 2
done

log "ClaudeCodeUI / CloudCLI 未能在端口 $PORT 上成功重启。"
run_ubuntu_logged bash -lc 'if test -f "$HOME/.cloudcli-web.log"; then echo "==== CloudCLI 运行日志 ===="; tail -n 100 "$HOME/.cloudcli-web.log"; else echo "未找到 CloudCLI 运行日志。"; fi' || true
exit 1
