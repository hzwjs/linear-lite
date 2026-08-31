#!/usr/bin/env bash
# Linear Lite 本地前后端一键启动脚本。
# 用法：./scripts/start-local.sh {start|stop|status|restart}

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$ROOT_DIR/linear-lite-server"
RUNTIME_DIR="${LINEAR_LITE_RUNTIME_DIR:-$ROOT_DIR/.linear-lite-local}"

FRONTEND_PID_FILE="$RUNTIME_DIR/frontend.pid"
FRONTEND_LOG_FILE="$RUNTIME_DIR/frontend.log"
BACKEND_PID_FILE="$RUNTIME_DIR/backend.pid"
BACKEND_LOG_FILE="$RUNTIME_DIR/backend.log"
BACKEND_JAR="$SERVER_DIR/target/linear-lite-server-0.1.0-SNAPSHOT.jar"

log() {
  printf '[linear-lite] %s\n' "$*"
}

die() {
  printf '[linear-lite] %s\n' "$*" >&2
  exit 1
}

pid_value() {
  local pid_file="$1"
  [[ -f "$pid_file" ]] || return 1
  local pid
  pid="$(<"$pid_file")"
  [[ "$pid" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "$pid"
}

pid_running() {
  local pid_file="$1"
  local pid
  pid="$(pid_value "$pid_file" 2>/dev/null)" || return 1
  kill -0 "$pid" 2>/dev/null
}

port_busy() {
  local port="$1"
  lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
}

clear_stale_pid() {
  local pid_file="$1"
  if [[ -f "$pid_file" ]] && ! pid_running "$pid_file"; then
    rm -f "$pid_file"
  fi
}

start_frontend() {
  clear_stale_pid "$FRONTEND_PID_FILE"
  if pid_running "$FRONTEND_PID_FILE"; then
    log "前端已运行（PID $(pid_value "$FRONTEND_PID_FILE")）"
    return 0
  fi
  port_busy 5173 && die '端口 5173 已被其他进程占用，请先执行 status 检查。'
  command -v pnpm >/dev/null 2>&1 || die '未找到 pnpm，请先安装 Node.js 与 pnpm。'

  mkdir -p "$RUNTIME_DIR"
  log "启动前端：http://localhost:5173/"
  (
    cd "$ROOT_DIR"
    nohup pnpm dev --host localhost >"$FRONTEND_LOG_FILE" 2>&1 < /dev/null &
    printf '%s\n' "$!" >"$FRONTEND_PID_FILE"
  )
}

start_backend() {
  clear_stale_pid "$BACKEND_PID_FILE"
  if pid_running "$BACKEND_PID_FILE"; then
    log "后端已运行（PID $(pid_value "$BACKEND_PID_FILE")）"
    return 0
  fi
  port_busy 9080 && die '端口 9080 已被其他进程占用，请先执行 status 检查。'
  [[ -f "$BACKEND_JAR" ]] || die "后端 JAR 不存在：$BACKEND_JAR"

  mkdir -p "$RUNTIME_DIR"
  log "启动后端：http://localhost:9080/"
  APP_HOME="$SERVER_DIR" \
  APP_JAR="$BACKEND_JAR" \
  ENV_FILE="$SERVER_DIR/.env" \
  PID_FILE="$BACKEND_PID_FILE" \
  LOG_FILE="$BACKEND_LOG_FILE" \
    "$SCRIPT_DIR/start-server.sh" start
}

start() {
  mkdir -p "$RUNTIME_DIR"
  start_backend
  start_frontend
  log '本地项目启动完成。'
  log '前端验证地址：http://localhost:5173/'
  log '后端地址：http://localhost:9080/'
  log '当前终端将持续托管服务，按 Ctrl-C 可同时停止前后端。'

  local interrupted=0
  cleanup() {
    trap - INT TERM EXIT
    stop
  }
  trap 'interrupted=1; exit 130' INT TERM
  trap cleanup EXIT

  while pid_running "$FRONTEND_PID_FILE" || pid_running "$BACKEND_PID_FILE"; do
    sleep 1
  done
  if (( interrupted == 0 )); then
    log '检测到前后端进程均已退出。'
  fi
}

stop_process() {
  local name="$1"
  local pid_file="$2"
  if ! pid_running "$pid_file"; then
    clear_stale_pid "$pid_file"
    log "$name 未运行"
    return 0
  fi

  local pid
  pid="$(pid_value "$pid_file")"
  local child_pid
  for child_pid in $(pgrep -P "$pid" 2>/dev/null || true); do
    kill "$child_pid" 2>/dev/null || true
  done
  log "停止${name}（PID ${pid}）"
  kill "$pid" 2>/dev/null || true
  for _ in {1..20}; do
    pid_running "$pid_file" || break
    sleep 0.25
  done
  if pid_running "$pid_file"; then
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$pid_file"
}

stop() {
  stop_process '前端' "$FRONTEND_PID_FILE"
  stop_process '后端' "$BACKEND_PID_FILE"
  log '本地项目已停止。'
}

status_one() {
  local name="$1"
  local pid_file="$2"
  local port="$3"
  if pid_running "$pid_file"; then
    log "${name} 运行中（PID $(pid_value "$pid_file")，端口 ${port}）"
  elif port_busy "$port"; then
    log "${name} 端口 ${port} 被其他进程占用（PID 文件无效）"
  else
    clear_stale_pid "$pid_file"
    log "$name 未运行"
  fi
}

status() {
  status_one '前端' "$FRONTEND_PID_FILE" 5173
  status_one '后端' "$BACKEND_PID_FILE" 9080
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  restart) stop; start ;;
  *)
    printf '用法：%s {start|stop|status|restart}\n' "$0" >&2
    exit 1
    ;;
esac
