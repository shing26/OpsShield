#!/usr/bin/env bash
# OpsShield 红队对抗套件（roadmap 近期 #8 → 中期 L1/L2 的前置门禁）：
#   起 memory/mock 活体栈 + 播种账号 → manifest 防篡改校验 → 21 条对抗用例
#   （注入/越狱/跨租户/密级/MCP 端到端/命令契约）→ 泄漏即退出非 0（0 容忍、无阈值）。
#
# 与 benchmark.sh 对称：CI 可复现、mock 栈零真金成本；判定全部确定性
# （字符串/结构/状态机），无 LLM-as-judge。MCP 用例端到端起真 stdio 进程。
#
# 用法：bash scripts/redteam.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${REDTEAM_JAR:-$ROOT/target/opspilot-gateway-1.0.0.jar}"
PORT="${REDTEAM_PORT:-18103}"
PYTHON="${PYTHON:-python3}"
if ! command -v "$PYTHON" >/dev/null 2>&1; then PYTHON=python; fi

fail() { echo "FAIL: $*" >&2; [ -f "$WORK/app.log" ] && tail -60 "$WORK/app.log" >&2; exit 1; }

[ -f "$JAR" ] || { echo "FAIL: jar 不存在：$JAR（先 mvn -B -DskipTests package）" >&2; exit 1; }
[ -n "${DEMO_PASSWORD:-}" ] || { echo "FAIL: DEMO_PASSWORD 未设置" >&2; exit 1; }

# JAVA_BIN：Git Bash 的 PATH java 常是 17，jar 是 21 编译——java_home.sh 探测（同 benchmark.sh）
JAVA_BIN="${JAVA_BIN:-}"
if [ -z "$JAVA_BIN" ]; then
  # shellcheck source=scripts/java_home.sh
  . "$ROOT/scripts/java_home.sh" 2>/dev/null || true
  [ -n "${JAVA_HOME:-}" ] && JAVA_BIN="$JAVA_HOME/bin/java"
fi
JAVA_BIN="${JAVA_BIN:-java}"

TMP="$(mktemp -d)"
WORK="$TMP"
if command -v cygpath >/dev/null 2>&1; then WORK="$(cygpath -m "$TMP")"; fi
PID=""
cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

mkdir -p "$WORK/data" "$WORK/logs" "$WORK/backup" "$WORK/offline/corpus"
cp "$JAR" "$WORK/app.jar"
cp "$ROOT/offline/corpus/chunks.jsonl" "$WORK/offline/corpus/chunks.jsonl"

seed() {
  (cd "$WORK" && "$JAVA_BIN" -cp app.jar -Dloader.main=com.opspilot.auth.UserAdminCli \
    org.springframework.boot.loader.launch.PropertiesLauncher add \
    --user "$1" --tenant "$2" --level "$3" --role "$4" --password-env DEMO_PASSWORD) \
    >/dev/null 2>&1 || echo "skip: $1"
}
seed sre-limited tenant-internal 1 sre
seed sre-full    tenant-internal 3 platform
seed sre-acme    tenant-acme     3 sre

cd "$WORK"
STORAGE_MODE=memory DASHSCOPE_MODE=mock SERVER_PORT="$PORT" SERVER_ADDRESS=127.0.0.1 \
JWT_SECRET="redteam-secret-0123456789-abcdefghijklmnopqrstuvwxyz" DASHSCOPE_API_KEY="" \
"$JAVA_BIN" -Xms128m -Xmx512m -jar app.jar >app.log 2>&1 &
PID=$!
cd "$ROOT"
export OPSPILOT_BASE="http://127.0.0.1:$PORT"
for _ in $(seq 1 60); do
  if curl -fsS --max-time 2 "$OPSPILOT_BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS --max-time 3 "$OPSPILOT_BASE/actuator/health/readiness" | grep -q '"status":"UP"' \
  || fail "readiness 非 UP"

echo "== 红队全量：21 用例（fail-closed，泄漏即 FAIL） =="
(cd offline && "$PYTHON" redteam/runner.py --base "$OPSPILOT_BASE") || {
  echo "FAIL: 红队发现泄漏（判据 fail-closed：泄漏=1 即拦，详见 offline/redteam/report/）" >&2
  exit 1
}

echo "PASS redteam：零泄漏（报告见 offline/redteam/report/redteam_report.md）"
