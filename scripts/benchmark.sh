#!/usr/bin/env bash
# OpsShield 一键评测基准（roadmap 近期 #3，ADR-0028 无需——编排层不引入新决策）：
#   起 memory/mock 活体栈 → 播种评测账号 → 检索质量/越权 + 门控矩阵 + 准入探针 →
#   聚合成 benchmark.{json,md} 并按入库阈值门禁（回归即退出非 0，CI 直接拦）。
#
# 为什么 memory/mock：CI 可复现、零真金成本；阈值按 mock 校准（见 benchmark_thresholds.json），
# live 后端指标只会更好。全量降级演练在 fault_drill.sh，本脚本不冒充。
#
# 用法：bash scripts/benchmark.sh
#       BENCH_PORT=18103 bash scripts/benchmark.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${BENCH_JAR:-$ROOT/target/opspilot-gateway-1.0.0.jar}"
PORT="${BENCH_PORT:-18102}"
# JAVA_BIN 不能盲信 PATH：Git Bash 的 java 常是 17，jar 是 21 编译（class 65）——
# 首选显式 JAVA_BIN，其次 java_home.sh 探测（E:/java/jdk21 等），最后才 PATH。
JAVA_BIN="${JAVA_BIN:-}"
if [ -z "$JAVA_BIN" ]; then
  # shellcheck source=scripts/java_home.sh
  . "$ROOT/scripts/java_home.sh" 2>/dev/null || true
  [ -n "${JAVA_HOME:-}" ] && JAVA_BIN="$JAVA_HOME/bin/java"
fi
JAVA_BIN="${JAVA_BIN:-java}"
PYTHON="${PYTHON:-python3}"
if ! command -v "$PYTHON" >/dev/null 2>&1; then PYTHON=python; fi

fail() { echo "FAIL: $*" >&2; [ -f "$WORK/app.log" ] && tail -60 "$WORK/app.log" >&2; exit 1; }

[ -f "$JAR" ] || { echo "FAIL: jar 不存在：$JAR（先 mvn -B -DskipTests package）" >&2; exit 1; }
[ -n "${DEMO_PASSWORD:-}" ] || { echo "FAIL: DEMO_PASSWORD 未设置（播种/登录要用）" >&2; exit 1; }

TMP="$(mktemp -d)"
WORK="$TMP"
if command -v cygpath >/dev/null 2>&1; then WORK="$(cygpath -m "$TMP")"; fi
PID=""
cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

# 基准跑的是 mock：README/对外口径引用的 eval_report/gate_matrix/PROVENANCE 是 **live 证据**，
# 两者口径必须分开——跑完把 live 产物还原（CI 干净检出 = no-op），否则 mock 数字顶掉
# live 证据会让 doc_numbers 门禁红（2026-10-24 实测）。benchmark.{json,md} 本身不受影响。
restore_live_reports() {
  git -C "$ROOT" checkout -- \
    offline/eval/reports/eval_report.json \
    offline/eval/reports/eval_report.md \
    offline/eval/reports/gate_matrix.json \
    offline/eval/reports/gate_matrix.md \
    offline/eval/reports/PROVENANCE.json 2>/dev/null || true
}

mkdir -p "$WORK/data" "$WORK/logs" "$WORK/backup" "$WORK/offline/corpus"
cp "$JAR" "$WORK/app.jar"
cp "$ROOT/offline/corpus/chunks.jsonl" "$WORK/offline/corpus/chunks.jsonl"

seed() {
  (cd "$WORK" && "$JAVA_BIN" -cp app.jar -Dloader.main=com.opspilot.auth.UserAdminCli \
    org.springframework.boot.loader.launch.PropertiesLauncher add \
    --user "$1" --tenant "$2" --level "$3" --role "$4" --password-env DEMO_PASSWORD) \
    >/dev/null 2>&1 || echo "skip（已存在或失败，登录时见分晓）: $1"
}
# 与 seed_demo_users.sh 同集合的子集（评测面只需这 3 个；watcher/告警链路与基准无关）
seed sre-limited tenant-internal 1 sre
seed sre-full    tenant-internal 3 platform
seed sre-acme    tenant-acme     3 sre

cd "$WORK"
STORAGE_MODE=memory DASHSCOPE_MODE=mock SERVER_PORT="$PORT" SERVER_ADDRESS=127.0.0.1 \
JWT_SECRET="benchmark-secret-0123456789-abcdefghijklmnopqrstuvwxyz" DASHSCOPE_API_KEY="" \
"$JAVA_BIN" -Xms128m -Xmx512m -jar app.jar >app.log 2>&1 &
PID=$!
cd "$ROOT"
BASE="http://127.0.0.1:$PORT"
for _ in $(seq 1 60); do
  if curl -fsS --max-time 2 "$BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS --max-time 3 "$BASE/actuator/health/readiness" | grep -q '"status":"UP"' \
  || fail "readiness 非 UP"

export OPSPILOT_BASE="$BASE"
export PYTHON

echo "== [0/3] 数据集脱敏审计 + 指纹清单（dataset_audit.py） =="
(cd offline && "$PYTHON" eval/dataset_audit.py) || fail "数据集脱敏审计未过：公开数据集认证失败，先清语料再测"

echo "== [1/3] 检索质量 + 越权（evaluate.py） =="
(cd offline && "$PYTHON" eval/evaluate.py >/dev/null) || fail "evaluate.py 失败"

echo "== [2/3] 门控矩阵（gate_matrix.py） =="
(cd offline && "$PYTHON" eval/gate_matrix.py >/dev/null) || fail "gate_matrix.py 失败"

echo "== [3/3] 聚合 + 准入探针 + 阈值门禁（benchmark.py） =="
BENCH_RC=0
(cd offline && "$PYTHON" eval/benchmark.py --burst "${BENCH_BURST:-12}") || BENCH_RC=$?
restore_live_reports
if [ "$BENCH_RC" -ne 0 ]; then
  echo "FAIL: 评测基准低于阈值基线（回归被门禁拦下，详见 offline/eval/reports/benchmark.md）" >&2
  exit 1
fi

echo "PASS benchmark：报告见 offline/eval/reports/benchmark.md"
