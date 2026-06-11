PORT="__PORT__"

is_web_ready() {
  if run_ubuntu_logged bash -lc "curl -fsS --max-time 3 http://127.0.0.1:$PORT/ >/dev/null 2>&1"; then
    return 0
  fi
  return 1
}

require_ubuntu

log "正在停止 OpenCode 网页服务，端口 $PORT"
run_ubuntu_logged bash -lc "set -euo pipefail; pids=''; self=\$\$; while read -r pid comm args; do [ -n \"\$pid\" ] || continue; [ \"\$pid\" = \"\$self\" ] && continue; case \"\$comm\" in opencode|opencode-*|opencode.exe|node) ;; *) continue ;; esac; case \"\$comm\" in node) case \" \$args \" in *opencode*) ;; *) continue ;; esac ;; esac; case \" \$args \" in *' web '*) ;; *) continue ;; esac; case \" \$args \" in *' --port $PORT '*|*' --port=$PORT '*) pids=\"\$pids \$pid\" ;; esac; done < <(ps -eo pid=,comm=,args=); if [ -n \"\$pids\" ]; then kill \$pids 2>/dev/null || true; sleep 1; kill -9 \$pids 2>/dev/null || true; echo \"已停止 OpenCode Web 进程：\$pids\"; else echo '未找到 $PORT 端口的 OpenCode Web 进程。'; fi"

if is_web_ready; then
  log "OpenCode 仍可通过端口 $PORT 访问，请查看进程状态。"
  exit 1
fi

log "OpenCode $PORT 端口 Web 服务已停止。"
