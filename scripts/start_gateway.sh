#!/usr/bin/env bash
# jar 形态启动网关（OPS §6 定的本机生产/演示形态）。
#
# 形态选择的理由（别顺手改回容器）：
#   - 开发态用 scripts/run.sh（mvn spring-boot:run，改动即生效，不用打包）；
#   - 本机 Docker 拉不到 base 镜像（maven:3.9-eclipse-temurin-21 超时）⇒
#     **容器网关形态在本机不可复现**，这是 2026-09-16 实测后定的取舍，不是遗漏；
#   - 中间件仍走容器（docker compose 的 redis/qdrant/elasticsearch）。
#
# 这个脚本存在的理由：端口探测与"启动成功"判据曾两次把人带进错结论
# （OPS §7 纪律 #1/#2/#3——旧实例幽灵、截断的 jar、按 app 自报 pid 杀进程）。
# 所以这里把三件事做死：**启动前查端口占用并点名占用者**、**jar 缺失即拒启**、
# **以日志里的 Started 行为唯一成功判据**（不信 /actuator/health——它可能属于别人）。
#
# 用法：scripts/start_gateway.sh [--foreground]
#   默认：后台起，轮询到 Started 后打印结论并退出（日志留在 logs/）
#   --foreground：直接前台跑（Ctrl-C 停）
#
# 覆盖口（放 .env 即可，均有默认）：
#   SERVER_PORT      监听端口，默认 8081（与 application.yml / compose / README 同口径）
#   SERVER_ADDRESS   监听地址，默认 127.0.0.1（G0-W0.1：只给本机访问）
#                    ——容器（compose 端口映射）或前置反代形态才显式设 0.0.0.0；
#                    裸机设 0.0.0.0 等于把面板与 /v1 面直接放到网络上（鉴权不是网络边界）。
#   GATEWAY_HEAP     JVM 堆上限，默认 1024m；内存紧张的机器在 .env 里调小
set -euo pipefail
cd "$(dirname "$0")/.."

FOREGROUND=0
[ "${1:-}" = "--foreground" ] && FOREGROUND=1

PORT="${SERVER_PORT:-8081}"
ADDR="${SERVER_ADDRESS:-127.0.0.1}"
HEAP="${GATEWAY_HEAP:-1024m}"
JAR="target/opspilot-gateway-1.0.0.jar"

if [ -f .env ]; then
  set -a; . ./.env; set +a
  # .env 可能在 set -a 之前被读过一次，重新取以让 SERVER_PORT/SERVER_ADDRESS/GATEWAY_HEAP 生效
  PORT="${SERVER_PORT:-8081}"
  ADDR="${SERVER_ADDRESS:-127.0.0.1}"
  HEAP="${GATEWAY_HEAP:-1024m}"
fi
# shellcheck source=scripts/java_home.sh
. "$(dirname "$0")/java_home.sh"

die() { echo "FAIL: $*" >&2; exit 1; }

# ---- 1) 端口占用预检：点名占用者，别让人对着 503 猜 ----
port_pids() {
  if command -v netstat >/dev/null 2>&1; then
    netstat -ano 2>/dev/null | awk -v p=":$PORT\$" '$2 ~ p && $4 == "LISTENING" {print $5}' | sort -u
  elif command -v ss >/dev/null 2>&1; then
    ss -lptnH "sport = :$PORT" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u
  elif command -v lsof >/dev/null 2>&1; then
    lsof -ti "tcp:$PORT" -sTCP:LISTEN 2>/dev/null | sort -u
  fi
}
describe_pid() {
  if [ -r "/proc/$1/cmdline" ]; then
    tr '\0' ' ' < "/proc/$1/cmdline" | cut -c1-160
  elif command -v powershell >/dev/null 2>&1; then
    powershell -NoProfile -Command \
      "(Get-CimInstance Win32_Process -Filter \"ProcessId=$1\" -ErrorAction SilentlyContinue).CommandLine" 2>/dev/null \
      | tr -d '\r' | cut -c1-160
  else
    echo "(无法读取命令行)"
  fi
}
OCCUPIED="$(port_pids)"
if [ -n "$OCCUPIED" ]; then
  echo "端口 $PORT 已被占用：" >&2
  for p in $OCCUPIED; do echo "  pid $p → $(describe_pid "$p")" >&2; done
  die "换端口（.env 里设 SERVER_PORT=8099 之类）再起。**不要**以为占着的就是本项目的旧实例——
       它可能是另一个项目的进程，它的 /actuator/health 返回什么都不能证明你的网关状态（OPS §7 纪律 #1）。"
fi

# ---- 2) jar 在不在：截断过的 jar 能起但起的是错的东西 ----
[ -f "$JAR" ] || die "$JAR 不存在。先打：JAVA_HOME 到位后 mvn -q package -DskipTests
     （打 jar 前必须先停服务：Windows 上 JVM 锁着 jar 时 repackage 会留下一个被截断的 jar，OPS §7 纪律 #3）"
ls -l "$JAR" | awk '{printf "jar: %s (%d MB)\n", $NF, int($5/1048576)}'

STAMP="$(date +%Y%m%d-%H%M%S)"
LOG="logs/run-${STAMP}.log"
mkdir -p logs
export OPSPILOT_BASE="http://localhost:${PORT}"   # python 工具链的覆盖口，别漏（否则打默认端口）
echo "listen=${ADDR}:${PORT}  heap=${HEAP}  log=${LOG}"
# 0.0.0.0/:: 时浏览器不能直接用该地址访问，提示一句，免得对着"打不开"猜
case "$ADDR" in
  0.0.0.0|::|"[::]") echo "注意：绑定在所有网卡上（浏览器请用 http://127.0.0.1:${PORT}/）；仅容器/反代形态应如此。" ;;
esac

if [ "$FOREGROUND" = "1" ]; then
  echo "前台启动（Ctrl-C 停），监听 ${ADDR}:${PORT}。"
  exec "$JAVA_HOME/bin/java" "-Xmx${HEAP}" "-Dserver.port=${PORT}" "-Dserver.address=${ADDR}" -jar "$JAR"
fi

# ---- 3) 后台起，轮询到 Started 才算成 ----
nohup "$JAVA_HOME/bin/java" "-Xmx${HEAP}" "-Dserver.port=${PORT}" "-Dserver.address=${ADDR}" -jar "$JAR" > "$LOG" 2>&1 &
disown 2>/dev/null || true

for i in $(seq 1 60); do
  if grep -q "Started OpsPilotApplication" "$LOG" 2>/dev/null; then
    echo "OK 已启动（实际监听 ${ADDR}:${PORT}）："
    grep "Started OpsPilotApplication" "$LOG" | tail -1 | sed 's/^.*: //'
    echo "面板   http://localhost:${PORT}/"
    echo "健康   http://localhost:${PORT}/actuator/health"
    echo "工作台 http://localhost:${PORT}/  # 右上角账号胶囊填用户名+口令；默认视图=排障台"
    echo "凭证   bash scripts/console_token.sh   # role=platform 的 JWT，给 curl/探针/自动化用"
    exit 0
  fi
  if grep -q "APPLICATION FAILED TO START" "$LOG" 2>/dev/null; then
    echo "FAIL 启动失败，日志尾部：" >&2
    tail -25 "$LOG" >&2
    exit 1
  fi
  sleep 1
done
echo "FAIL 60s 内没等到 Started，看日志：$LOG" >&2
tail -15 "$LOG" >&2
exit 1
