PORT="__PORT__"

is_web_ready() {
  if run_ubuntu_logged bash -lc "curl -fsS --max-time 3 http://127.0.0.1:$PORT/ >/dev/null 2>&1"; then
    return 0
  fi
  return 1
}

is_opencode_cwd_home() {
  run_ubuntu_logged bash -lc "set -euo pipefail; found=0; self=\$\$; while read -r pid comm args; do [ -n \"\$pid\" ] || continue; [ \"\$pid\" = \"\$self\" ] && continue; case \"\$comm\" in opencode|opencode-*|opencode.exe|node) ;; *) continue ;; esac; case \"\$comm\" in node) case \" \$args \" in *opencode*) ;; *) continue ;; esac ;; esac; case \" \$args \" in *' web '*) ;; *) continue ;; esac; case \" \$args \" in *' --port $PORT '*|*' --port=$PORT '*) ;; *) continue ;; esac; found=1; if tr '\\0' '\\n' < \"/proc/\$pid/environ\" 2>/dev/null | grep -Fxq 'PWD=/root'; then exit 0; fi; echo \"OpenCode 进程 \$pid 启动环境不是 PWD=/root\" >&2; done < <(ps -eo pid=,comm=,args=); [ \"\$found\" -eq 0 ] && echo '未找到 OpenCode 进程，准备重新拉起。' >&2; exit 1"
}

stop_opencode_port() {
  run_ubuntu_logged bash -lc "set -euo pipefail; pids=''; self=\$\$; while read -r pid comm args; do [ -n \"\$pid\" ] || continue; [ \"\$pid\" = \"\$self\" ] && continue; case \"\$comm\" in opencode|opencode-*|opencode.exe|node) ;; *) continue ;; esac; case \"\$comm\" in node) case \" \$args \" in *opencode*) ;; *) continue ;; esac ;; esac; case \" \$args \" in *' web '*) ;; *) continue ;; esac; case \" \$args \" in *' --port $PORT '*|*' --port=$PORT '*) pids=\"\$pids \$pid\" ;; esac; done < <(ps -eo pid=,comm=,args=); if [ -n \"\$pids\" ]; then kill \$pids 2>/dev/null || true; sleep 1; kill -9 \$pids 2>/dev/null || true; fi"
}

ensure_opencode_configuration_path() {
  run_ubuntu_logged bash -lc "set -euo pipefail; mkdir -p \"\$HOME/.config/openhouseai\" \"\$HOME/.config/opencode\" \"\$HOME/workspace\"; printf '%s\n' '/root' > \"\$HOME/.config/openhouseai/opencode-project-directory\"; printf '%s\n' '$PORT' > \"\$HOME/.config/openhouseai/opencode-port\""
}

require_ubuntu
ensure_opencode_configuration_path

if is_web_ready; then
  if is_opencode_cwd_home; then
    log "OpenCode 已可通过端口 $PORT 访问，且启动目录是 Ubuntu root。"
    exit 0
  fi
  log "OpenCode 已在端口 $PORT 运行，但启动目录不是 Ubuntu root，正在重启。"
  stop_opencode_port
  sleep 1
fi

log "正在通过端口 $PORT 启动 OpenCode 网页服务，启动目录为 Ubuntu /root"
run_ubuntu_logged bash -lc "set -euo pipefail; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; if ! command -v opencode >/dev/null 2>&1 && ! test -x \"\$HOME/.opencode/bin/opencode\"; then echo '尚未安装 OpenCode，请先执行“安装 OpenCode”。' >&2; exit 3; fi"
if is_current_ubuntu; then
  nohup bash -lc "set -euo pipefail; export HOME=/root PWD=/root; cd /root; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; export BROWSER=/bin/true; exec opencode web --hostname 127.0.0.1 --port $PORT --print-logs >\"\$HOME/.opencode-web.log\" 2>&1" >>"$LOG_FILE" 2>&1 < /dev/null &
else
  nohup proot-distro login ubuntu -- bash -lc "set -euo pipefail; export HOME=/root PWD=/root; cd /root; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; export BROWSER=/bin/true; exec opencode web --hostname 127.0.0.1 --port $PORT --print-logs >\"\$HOME/.opencode-web.log\" 2>&1" >>"$LOG_FILE" 2>&1 < /dev/null &
fi

for _ in $(seq 1 30); do
  if is_web_ready; then
    log "OpenCode 已可通过端口 $PORT 访问。"
    exit 0
  fi
  log "正在等待 OpenCode 监听端口 $PORT"
  sleep 1
done

log "OpenCode 未能在端口 $PORT 上成功启动。"
run_ubuntu_logged bash -lc 'if test -f "$HOME/.opencode-web.log"; then echo "==== OpenCode 运行日志 ===="; tail -n 80 "$HOME/.opencode-web.log"; else echo "未找到 OpenCode 运行日志。"; fi' || true
exit 1
