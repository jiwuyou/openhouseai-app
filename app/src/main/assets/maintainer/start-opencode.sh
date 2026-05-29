PORT="__PORT__"

is_web_ready() {
  if run_ubuntu_logged bash -lc "curl -fsS --max-time 3 http://127.0.0.1:$PORT/ >/dev/null 2>&1"; then
    return 0
  fi
  return 1
}

is_opencode_cwd_home() {
  run_ubuntu_logged bash -lc "set -euo pipefail; found=0; for pid in \$(pgrep -f 'opencode web.*--port $PORT' 2>/dev/null || true); do found=1; cwd=\$(readlink \"/proc/\$pid/cwd\" 2>/dev/null || true); if [ \"\$cwd\" = \"\$HOME\" ] || [ \"\$cwd\" = '/root' ]; then exit 0; fi; echo \"OpenCode 进程 \$pid 当前目录：\${cwd:-未知}\" >&2; done; [ \"\$found\" -eq 0 ] && echo '未找到 OpenCode 进程，准备重新拉起。' >&2; exit 1"
}

require_ubuntu

if is_web_ready; then
  if is_opencode_cwd_home; then
    log "OpenCode 已可通过端口 $PORT 访问，且启动目录是 Ubuntu root。"
    exit 0
  fi
  log "OpenCode 已在端口 $PORT 运行，但启动目录不是 Ubuntu root，正在重启。"
  run_ubuntu_logged bash -lc "pkill -f 'opencode web.*--port $PORT' >/dev/null 2>&1 || true"
  sleep 1
fi

log "正在通过端口 $PORT 启动 OpenCode 网页服务"
run_ubuntu_logged bash -lc "set -euo pipefail; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; if ! command -v opencode >/dev/null 2>&1 && ! test -x \"\$HOME/.opencode/bin/opencode\"; then echo '尚未安装 OpenCode，请先执行“安装 OpenCode”。' >&2; exit 3; fi"
if is_current_ubuntu; then
  nohup bash -lc "set -euo pipefail; cd \"\$HOME\"; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; export BROWSER=/bin/true; exec opencode web --hostname 127.0.0.1 --port $PORT --print-logs >\"\$HOME/.opencode-web.log\" 2>&1" >>"$LOG_FILE" 2>&1 < /dev/null &
else
  nohup proot-distro login ubuntu -- bash -lc "set -euo pipefail; cd \"\$HOME\"; export PATH=\"\$HOME/.local/node/bin:\$HOME/.npm-global/bin:\$HOME/.opencode/bin:\$HOME/.local/bin:\$PATH\"; export BROWSER=/bin/true; exec opencode web --hostname 127.0.0.1 --port $PORT --print-logs >\"\$HOME/.opencode-web.log\" 2>&1" >>"$LOG_FILE" 2>&1 < /dev/null &
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
