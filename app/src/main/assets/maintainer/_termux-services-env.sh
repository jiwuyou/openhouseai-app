#!/usr/bin/env bash

# Shared Termux service environment and runit readiness helpers. This file is
# sourced by Android maintenance and bootstrap entrypoints; it must not start
# a service-manager process outside runit.

oh_termux_services_warn() {
  if declare -F warn >/dev/null 2>&1; then
    warn "$*"
  else
    printf '[OpenHouse control-plane] WARN: %s\n' "$*" >&2
  fi
}

oh_termux_services_environment() {
  PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
  SVDIR="${SVDIR:-$PREFIX/var/service}"
  LOGDIR="${LOGDIR:-$PREFIX/var/log}"
  export PREFIX SVDIR LOGDIR
  mkdir -p "$SVDIR" "$LOGDIR" || {
    oh_termux_services_warn "无法创建 Termux 服务目录：SVDIR=$SVDIR LOGDIR=$LOGDIR"
    return 1
  }
  [ -d "$SVDIR" ] && [ -d "$LOGDIR" ] || {
    oh_termux_services_warn "Termux 服务目录不可用：SVDIR=$SVDIR LOGDIR=$LOGDIR"
    return 1
  }
}

oh_termux_runsvdir_pid() {
  local proc comm args
  for proc in /proc/[0-9]*; do
    [ -r "$proc/comm" ] && [ -r "$proc/cmdline" ] || continue
    comm="$(cat "$proc/comm" 2>/dev/null || true)"
    [ "$comm" = runsvdir ] || continue
    args="$(tr '\000' '\n' < "$proc/cmdline" 2>/dev/null || true)"
    printf '%s\n' "$args" | grep -Fqx -- "$SVDIR" || continue
    printf '%s\n' "${proc##*/}"
    return 0
  done
  return 1
}

oh_termux_runsvdir_active() {
  oh_termux_runsvdir_pid >/dev/null 2>&1
}

oh_wait_for_runsvdir() {
  local attempts="${1:-40}" attempt=1
  while [ "$attempt" -le "$attempts" ]; do
    if oh_termux_runsvdir_active; then
      return 0
    fi
    case "$attempt" in
      1) sleep 0.25 ;;
      2) sleep 0.5 ;;
      *) sleep 1 ;;
    esac
    attempt=$((attempt + 1))
  done
  return 1
}

oh_start_termux_services_daemon() {
  oh_termux_services_environment || return 1
  command -v service-daemon >/dev/null 2>&1 || {
    oh_termux_services_warn "缺少 service-daemon；请先安装 termux-services。"
    return 1
  }
  command -v sv >/dev/null 2>&1 || {
    oh_termux_services_warn "缺少 sv；请先安装 termux-services。"
    return 1
  }
  service-daemon start >/dev/null 2>&1 || true
  oh_wait_for_runsvdir "${SMALLPHONEAI_RUNSVDIR_READY_ATTEMPTS:-40}" || {
    oh_termux_services_warn "runsvdir 未在限定时间内监控 SVDIR：$SVDIR"
    return 1
  }
}

oh_wait_for_service_run_file() {
  local service_id="$1" attempts="${2:-40}" attempt=1
  local run_file="$SVDIR/$service_id/run"
  while [ "$attempt" -le "$attempts" ]; do
    [ -x "$run_file" ] && return 0
    case "$attempt" in
      1) sleep 0.25 ;;
      2) sleep 0.5 ;;
      *) sleep 1 ;;
    esac
    attempt=$((attempt + 1))
  done
  return 1
}

oh_service_manager_sv_up_with_retry() {
  local service_id="${1:?missing service id}"
  local attempts="${2:-10}" attempt=1 status output
  oh_termux_services_environment || return 1
  oh_termux_runsvdir_active || oh_wait_for_runsvdir "${SMALLPHONEAI_RUNSVDIR_READY_ATTEMPTS:-40}" || {
    oh_termux_services_warn "runsvdir 未就绪，无法执行 sv up：SVDIR=$SVDIR"
    return 1
  }
  oh_wait_for_service_run_file "$service_id" "${SMALLPHONEAI_SERVICE_RUN_READY_ATTEMPTS:-40}" || {
    oh_termux_services_warn "service-manager run 文件未就绪：$SVDIR/$service_id/run"
    return 1
  }

  while [ "$attempt" -le "$attempts" ]; do
    output="$(env SVDIR="$SVDIR" sv up "$service_id" 2>&1 || true)"
    status="$(env SVDIR="$SVDIR" sv status "$service_id" 2>/dev/null || true)"
    case "$status" in
      run:*)
        if declare -F log >/dev/null 2>&1; then
          log "sv up $service_id 已就绪：retryCount=$((attempt - 1)) status=$status"
        fi
        return 0
        ;;
    esac
    if declare -F log >/dev/null 2>&1 && [ -n "$output" ]; then
      log "sv up $service_id 第 ${attempt}/${attempts} 次未就绪：$output"
    fi
    case "$attempt" in
      1) sleep 0.25 ;;
      2) sleep 0.5 ;;
      *) sleep 1 ;;
    esac
    attempt=$((attempt + 1))
  done
  oh_termux_services_warn "sv up $service_id 超时：retryCount=$attempts SVDIR=$SVDIR lastStatus=${status:-unavailable}"
  return 1
}
