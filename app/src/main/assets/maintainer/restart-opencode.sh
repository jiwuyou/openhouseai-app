PORT="__PORT__"

is_web_ready() {
  if run_ubuntu_logged bash -lc "curl -fsS --max-time 3 http://127.0.0.1:$PORT/ >/dev/null 2>&1"; then
    return 0
  fi
  return 1
}

require_ubuntu

stop_opencode_port() {
  run_ubuntu_logged bash -lc "set -euo pipefail; pids=''; self=\$\$; while read -r pid comm args; do [ -n \"\$pid\" ] || continue; [ \"\$pid\" = \"\$self\" ] && continue; case \"\$comm\" in opencode|opencode-*|opencode.exe|node) ;; *) continue ;; esac; case \"\$comm\" in node) case \" \$args \" in *opencode*) ;; *) continue ;; esac ;; esac; case \" \$args \" in *' web '*) ;; *) continue ;; esac; case \" \$args \" in *' --port $PORT '*|*' --port=$PORT '*) pids=\"\$pids \$pid\" ;; esac; done < <(ps -eo pid=,comm=,args=); if [ -n \"\$pids\" ]; then kill \$pids 2>/dev/null || true; sleep 1; kill -9 \$pids 2>/dev/null || true; fi"
}

ensure_opencode_configuration_path() {
  run_ubuntu_logged bash -lc "set -euo pipefail; mkdir -p \"\$HOME/.config/openhouseai\" \"\$HOME/.config/opencode\" \"\$HOME/workspace\"; printf '%s\n' '/root' > \"\$HOME/.config/openhouseai/opencode-project-directory\"; printf '%s\n' '$PORT' > \"\$HOME/.config/openhouseai/opencode-port\""
}

log "正在重启 OpenCode 网页服务，端口 $PORT，启动目录为 Ubuntu /root"
ensure_opencode_configuration_path
run_ubuntu_logged bash -lc "set -euo pipefail; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; if ! command -v opencode >/dev/null 2>&1 && ! test -x \"\$HOME/.opencode/bin/opencode\"; then echo '尚未安装 OpenCode，请先执行“下载 OpenCode”。' >&2; exit 3; fi"
stop_opencode_port
sleep 1

if is_current_ubuntu; then
  nohup bash -lc "set -euo pipefail; export HOME=/root PWD=/root; cd /root; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; export BROWSER=/bin/true; exec opencode web --hostname 127.0.0.1 --port $PORT --print-logs >\"\$HOME/.opencode-web.log\" 2>&1" >>"$LOG_FILE" 2>&1 < /dev/null &
else
  nohup proot-distro login ubuntu -- bash -lc "set -euo pipefail; export HOME=/root PWD=/root; cd /root; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; export BROWSER=/bin/true; exec opencode web --hostname 127.0.0.1 --port $PORT --print-logs >\"\$HOME/.opencode-web.log\" 2>&1" >>"$LOG_FILE" 2>&1 < /dev/null &
fi

for _ in $(seq 1 30); do
  if is_web_ready; then
    log "OpenCode 已在端口 $PORT 重启完成。"
    exit 0
  fi
  log "正在等待 OpenCode 监听端口 $PORT"
  sleep 1
done

log "OpenCode 未能在端口 $PORT 上成功重启。"
run_ubuntu_logged bash -lc 'if test -f "$HOME/.opencode-web.log"; then echo "==== OpenCode 运行日志 ===="; tail -n 80 "$HOME/.opencode-web.log"; else echo "未找到 OpenCode 运行日志。"; fi' || true
exit 1
