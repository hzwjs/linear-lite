#!/usr/bin/env bash
# Linear Lite 服务端启动脚本（单 JAR 部署）
# 约定：linear-lite-server-*.jar、本脚本、环境文件（.env 和/或 .env.properties）放在同一目录。
# 用法: ./start-server.sh {start|stop|status|restart}
# 覆盖目录或 JAR：APP_HOME、APP_JAR；仅开发机可从仓库内 scripts/ 启动（自动找上级 linear-lite-server/target/*.jar）。

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# ---------- 可配置（可通过环境变量覆盖） ----------
APP_HOME="${APP_HOME:-$SCRIPT_DIR}"

if [[ -n "$APP_JAR" ]]; then
  JAR_PATH="$APP_JAR"
elif compgen -G "${APP_HOME}/linear-lite-server-"*.jar >/dev/null 2>&1; then
  JAR_PATH="$(ls -t "${APP_HOME}"/linear-lite-server-*.jar 2>/dev/null | head -1)"
else
  # 仓库内：脚本在 scripts/ 时，从上级 linear-lite-server/target 取 JAR
  DEV_JAR="$(cd "$APP_HOME/.." && pwd)/linear-lite-server/target/linear-lite-server-0.1.0-SNAPSHOT.jar"
  if [[ -f "$DEV_JAR" ]]; then
    JAR_PATH="$DEV_JAR"
  else
    echo "[linear-lite] 未找到 JAR：请将 linear-lite-server-*.jar 与本脚本放在同一目录，或设置 APP_JAR。" >&2
    exit 1
  fi
fi

PID_FILE="${PID_FILE:-${APP_HOME}/linear-lite.pid}"
LOG_FILE="${LOG_FILE:-${APP_HOME}/logs/app.log}"
ENV_FILE="${ENV_FILE:-}"
JAVA_OPTS="${JAVA_OPTS:--Xmx512m -Xms256m}"

RUN_USER="${RUN_AS_USER:-}"

die() { echo "[linear-lite] $*" >&2; exit 1; }
log() { echo "[linear-lite] $*"; }

# 将 KEY=value 行导出为环境变量（供 Spring 与 JVM 继承）；支持可选前缀 export
load_env_file() {
  local f="$1"
  [[ -f "$f" ]] || return 0
  set -a
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%$'\r'}"
    [[ "$line" =~ ^#.*$ ]] && continue
    [[ "$line" =~ ^[[:space:]]*$ ]] && continue
    if [[ "$line" =~ ^export[[:space:]]+(.+)$ ]]; then
      line="${BASH_REMATCH[1]}"
    fi
    if [[ "$line" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      export "$line"
    fi
  done < "$f"
  set +a
}

load_env() {
  if [[ -n "${ENV_FILE:-}" ]]; then
    load_env_file "$ENV_FILE"
    return 0
  fi
  load_env_file "${APP_HOME}/.env"
  load_env_file "${APP_HOME}/.env.properties"
}

mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
mkdir -p "$(dirname "$PID_FILE")" 2>/dev/null || true

is_running() {
  [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null
}

# 结束命令行中包含「-jar 本 JAR 绝对路径」的 Java（pid 文件丢失时仍占端口）
kill_java_holding_jar() {
  local pid cmd
  [[ -d /proc ]] || return 0
  for pid in $(pgrep -x java 2>/dev/null || true); do
    cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)
    [[ "$cmd" == *"-jar $JAR_PATH"* ]] || continue
    log "结束仍持有本 JAR 的进程 $pid"
    kill "$pid" 2>/dev/null || true
    local i
    for i in {1..15}; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      log "强制结束 $pid"
      kill -9 "$pid" 2>/dev/null || true
    fi
  done
}

listen_port_busy() {
  local p="${SERVER_PORT:-8080}"
  if command -v ss >/dev/null 2>&1; then
    ss -tln 2>/dev/null | grep -q ":$p " && return 0
    return 1
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -i ":$p" -sTCP:LISTEN >/dev/null 2>&1
    return $?
  fi
  return 1
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
  if listen_port_busy; then
    die "端口 ${SERVER_PORT:-8080} 已被占用，请先执行 $0 stop（会按 JAR 清理孤儿进程）"
  fi
  log "启动: $JAR_PATH（工作目录: $APP_HOME）"
  log "日志: $LOG_FILE"
  local java_cmd="java $JAVA_OPTS -jar $JAR_PATH"
  if [[ -n "$RUN_USER" ]] && [[ "$(whoami)" != "$RUN_USER" ]]; then
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
  if is_running; then
    local pid
    pid="$(cat "$PID_FILE")"
    log "停止 PID $pid ..."
    kill "$pid" 2>/dev/null || true
    local i
    for i in {1..30}; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$pid" 2>/dev/null; then
      log "强制结束 $pid"
      kill -9 "$pid" 2>/dev/null || true
    fi
  else
    log "无有效 pid 记录（若端口仍被占，将按 JAR 路径清理 Java）"
  fi
  kill_java_holding_jar
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
