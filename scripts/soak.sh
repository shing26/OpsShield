#!/usr/bin/env bash
# G1 准入/稳定性 soak（2026-10-22）：memory+mock 起真进程，固定并发打不同 query，
# 观察 429（预期背压）与 5xx（不允许）、进程存活、审计链完整。
# 这不是 live 容量验收：mock 后端不承诺吞吐数字；它校验的是"入口准入 + 失败分类 + 收尾"不崩。
# 用法：JAVA_BIN=... bash scripts/soak.sh [seconds] [concurrency]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${SOAK_JAR:-$ROOT/target/opspilot-gateway-1.0.0.jar}"
SECS="${1:-10}"
CONC="${2:-8}"
PORT="${SOAK_PORT:-18101}"
JAVA_BIN="${JAVA_BIN:-java}"
PYTHON="${PYTHON:-python3}"
if ! command -v "$PYTHON" >/dev/null 2>&1; then PYTHON=python; fi
TMP="$(mktemp -d)"
WORK="$TMP"
if command -v cygpath >/dev/null 2>&1; then WORK="$(cygpath -m "$TMP")"; fi
PID=""

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

fail() { echo "FAIL: $*" >&2; [ -f "$WORK/app.log" ] && tail -60 "$WORK/app.log" >&2; exit 1; }

[ -f "$JAR" ] || fail "jar 不存在：$JAR（先 mvn -B -DskipTests package）"
mkdir -p "$WORK/data" "$WORK/logs" "$WORK/backup" "$WORK/offline/corpus"
cp "$JAR" "$WORK/app.jar"
cp "$ROOT/offline/corpus/chunks.jsonl" "$WORK/offline/corpus/chunks.jsonl"

export H2_DB_URL="jdbc:h2:file:$WORK/data/users"
export H2_DB_PASSWORD="soak-h2-pass"
export DEMO_PASSWORD="soak-demo-pass"
(cd "$WORK" && "$JAVA_BIN" -cp app.jar -Dloader.main=com.opspilot.auth.UserAdminCli \
  org.springframework.boot.loader.launch.PropertiesLauncher add \
  --user soak --tenant tenant-internal --level 3 --role platform --password-env DEMO_PASSWORD) \
  >/dev/null 2>&1 || fail "seed 用户失败"

cd "$WORK"
STORAGE_MODE=memory DASHSCOPE_MODE=mock SERVER_PORT="$PORT" SERVER_ADDRESS=127.0.0.1 \
JWT_SECRET="soak-secret-0123456789-abcdefghijklmnopqrstuvwxyz" DASHSCOPE_API_KEY="" \
"$JAVA_BIN" -jar app.jar >app.log 2>&1 &
PID=$!
cd "$ROOT"
BASE="http://127.0.0.1:$PORT"
for _ in $(seq 1 60); do
  if curl -fsS --max-time 2 "$BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS --max-time 3 "$BASE/actuator/health/readiness" | grep -q '"status":"UP"' || fail "readiness 非 UP"

printf '{"username":"soak","password":"soak-demo-pass"}' > "$WORK/login.json"
TOKEN="$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H "Content-Type: application/json" \
  --data-binary "@$WORK/login.json" | "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
[ -n "$TOKEN" ] || fail "登录未返回 token"

RESULTS="$WORK/status.txt"
: > "$RESULTS"
DEADLINE=$((SECONDS + SECS))
PIDS=()
for i in $(seq 1 "$CONC"); do
  (
    n=0
    while [ "$SECONDS" -lt "$DEADLINE" ]; do
      n=$((n + 1))
      printf "{\"query\":\"soak-%s-%s\",\"source\":\"manual\"}" "$i" "$n" > "$WORK/req.$i.json"
      code="$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 -X POST "$BASE/api/v1/copilot/chat/stream" \
        -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
        --data-binary "@$WORK/req.$i.json" || echo "000")"
      echo "$code" >> "$RESULTS"
    done
  ) &
  PIDS+=($!)
done
# 必须只等请求 worker：网关本身也是本 shell 的子进程，裸 wait 会等它到天荒地老。
for p in "${PIDS[@]}"; do wait "$p"; done

TOTAL="$(wc -l < "$RESULTS" | tr -d " ")"
OK="$(grep -c "^200$" "$RESULTS" || true)"
TOO="$(grep -c "^429$" "$RESULTS" || true)"
BAD="$((TOTAL - OK - TOO))"
echo "soak: total=$TOTAL ok=$OK rejected429=$TOO other=$BAD (${SECS}s x ${CONC})"
[ "$OK" -gt 0 ] || fail "没有成功请求"
[ "$BAD" -eq 0 ] || fail "出现 5xx/连接错误（见 status.txt）"

curl -fsS --max-time 3 "$BASE/actuator/health" >/dev/null || fail "soak 后健康端点不可用"
"$PYTHON" "$ROOT/scripts/verify_audit_chain.py" "$WORK/logs/audit.jsonl" \
  --head "$WORK/logs/.audit-head" >/dev/null || fail "soak 后审计链断链"
echo "PASS soak：$TOTAL 请求，429=$TOO（准入背压，预期），5xx=0，健康/审计链正常"
