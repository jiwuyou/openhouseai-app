require_ubuntu

PORT="__PORT__"

log "正在停止 ClaudeCodeUI / CloudCLI，端口 $PORT"
run_ubuntu_logged bash -lc "set -euo pipefail; pids=''; self=\$\$; proc_list=\"\$(mktemp)\"; trap 'rm -f \"\$proc_list\"' EXIT; ps -eo pid=,comm=,args= > \"\$proc_list\"; while read -r pid comm args; do [ -n \"\$pid\" ] || continue; [ \"\$pid\" = \"\$self\" ] && continue; case \"\$comm\" in node|cloudcli) ;; *) continue ;; esac; case \" \$args \" in *cloudcli*|*dist-server/server/index.js*) ;; *) continue ;; esac; case \" \$args \" in *'--port $PORT'*|*'--port=$PORT'*|*'SERVER_PORT=$PORT'*|*'SERVER_PORT $PORT'*) pids=\"\$pids \$pid\" ;; esac; done < \"\$proc_list\"; if [ -n \"\$pids\" ]; then kill \$pids 2>/dev/null || true; sleep 1; kill -9 \$pids 2>/dev/null || true; echo \"已停止 CloudCLI 进程：\$pids\"; else echo '未找到 $PORT 端口的 CloudCLI 进程。'; fi"

if curl -fsS --connect-timeout 2 --max-time 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  log "ClaudeCodeUI / CloudCLI 仍可通过端口 $PORT 访问，请查看进程状态。"
  exit 1
fi

log "ClaudeCodeUI / CloudCLI $PORT 端口服务已停止。"
