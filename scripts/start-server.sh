#!/usr/bin/env bash
# Linear Lite 服务端启动脚本（单 JAR 部署）
# 用法: ./start-server.sh {start|stop|status|restart} [选项]
# 建议：将本脚本与 linear-lite-server-*.jar、.env 同目录部署，或通过 APP_HOME 指定目录。

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# ---------- 可配置（可通过环境变量覆盖） ----------
APP_HOME="${APP_HOME:-$(cd "$SCRIPT_DIR/.." && pwd)}"
# JAR：若与脚本同目录则用 jar；否则用 APP_HOME 或 target 下 jar
if [[ -n "$APP_JAR" ]]; then
  JAR_PATH="$APP_JAR"
else
  if compgen -G "${SCRIPT_DIR}/linear-lite-server-"*.jar >/dev/null 2>&1; then
    JAR_PATH="$(ls -t "${SCRIPT_DIR}"/linear-lite-server-*.jar 2>/dev/null | head -1)"
  elif compgen -G "${APP_HOME}/linear-lite-server-"*.jar >/dev/null 2>&1; then
    JAR_PATH="$(ls -t "${APP_HOME}"/linear-lite-server-*.jar 2>/dev/null | head -1)"
  else
    JAR_PATH="${APP_HOME}/linear-lite-server/target/linear-lite-server-0.1.0-SNAPSHOT.jar"
  fi
fi

PID_FILE="${PID_FILE:-${APP_HOME}/linear-lite.pid}"
LOG_FILE="${LOG_FILE:-${APP_HOME}/logs/app.log}"
# 可选：加载 .env（APP_HOME 或当前目录）
ENV_FILE="${ENV_FILE:-}"
# JVM：生产可设 -Xmx -Xms
JAVA_OPTS="${JAVA_OPTS:--Xmx512m -Xms256m}"

# ---------- 内部变量 ----------
RUN_USER="${RUN_AS_USER:-}"

die() { echo "[linear-lite] $*" >&2; exit 1; }
log() { echo "[linear-lite] $*"; }

# 若存在 .env，导出后给 java 继承（不 source 避免执行任意代码，仅 export KEY=val）
load_env() {
  local f="${ENV_FILE:-}"
  [[ -z "$f" ]] && f="${APP_HOME}/.env"
  [[ -z "$f" ]] && f="${SCRIPT_DIR:-.}/.env"
  if [[ -f "$f" ]]; then
    set -a
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ "$line" =~ ^#.*$ ]] && continue
      [[ "$line" =~ ^[[:space:]]*$ ]] && continue
      if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
        export "$line"
      fi
    done < "$f"
    set +a
  fi
}

mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
mkdir -p "$(dirname "$PID_FILE")" 2>/dev/null || true

is_running() {
  [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

start() {
  if is_running; then
    log "已运行 (PID $(cat "$PID_FILE"))，无需重复启动。"
    return 0
  fi
  if [[ ! -f "$JAR_PATH" ]]; then
    die "JAR 不存在: $JAR_PATH"
  fi
  load_env
  log "启动: $JAR_PATH"
  log "日志: $LOG_FILE"
  local java_cmd="java $JAVA_OPTS -jar $JAR_PATH"
  if [[ -n "$RUN_USER" ]] && [[ "$(whoami)" != "$RUN_USER" ]]; then
    # 若需以某用户运行，需 root 执行脚本并设置 RUN_AS_USER
    log "以用户 $RUN_USER 启动"
    su -s /bin/bash "$RUN_USER" -c "cd $APP_HOME && $java_cmd >> $LOG_FILE 2>&1 & echo \$! > $PID_FILE"
  else
    cd "$APP_HOME"
    nohup $java_cmd >> "$LOG_FILE" 2>&1 &
    echo $! > "$PID_FILE"
  fi
  sleep 1
  if is_running; then
    log "启动完成，PID $(cat "$PID_FILE")"
  else
    die "启动后进程未存活，请查看日志: $LOG_FILE"
  fi
}

stop() {
  if ! is_running; then
    log "未在运行"
    [[ -f "$PID_FILE" ]] && rm -f "$PID_FILE"
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  log "停止 PID $pid ..."
  kill "$pid" 2>/dev/null || true
  for i in {1..30}; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    log "强制结束 $pid"
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  log "已停止"
}

status() {
  if is_running; then
    log "运行中，PID $(cat "$PID_FILE")"
    return 0
  fi
  log "未运行"
  [[ -f "$PID_FILE" ]] && rm -f "$PID_FILE"
  return 1
}

restart() {
  stop
  start
}

case "${1:-start}" in
  start)  start ;;
  stop)   stop ;;
  status) status ;;
  restart) restart ;;
  *)
    echo "用法: $0 {start|stop|status|restart}" >&2
    echo "环境变量: APP_HOME, APP_JAR, PID_FILE, LOG_FILE, ENV_FILE, JAVA_OPTS, RUN_AS_USER" >&2
    exit 1
    ;;
esac
