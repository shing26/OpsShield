#!/usr/bin/env bash
# G1 发布 smoke（2026-10-22）：不用 compose/Redis/ES/Qdrant，memory+mock 起一个真进程，
# 走「登录 → SSE chat → 指标 → 管理面」最短链路。CI 每次构建后跑；本地用
#   JAVA_BIN=E:/java/jdk21/bin/java.exe PYTHON=python bash scripts/smoke.sh
# 参数：可选 jar 路径（默认 target/opspilot-gateway-1.0.0.jar）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${1:-$ROOT/target/opspilot-gateway-1.0.0.jar}"
PORT="${SMOKE_PORT:-18099}"
JAVA_BIN="${JAVA_BIN:-java}"
PYTHON="${PYTHON:-python3}"
TMP="$(mktemp -d)"
WORK="$TMP"
if command -v cygpath >/dev/null 2>&1; then WORK="$(cygpath -m "$TMP")"; fi
PID=""

cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null || true
    wait "$PID" 2>/dev/null || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT INT TERM

fail() {
  echo "FAIL: $*" >&2
  if [ -f "$WORK/app.log" ]; then tail -80 "$WORK/app.log" >&2; fi
  exit 1
}

[ -f "$JAR" ] || fail "jar 不存在：$JAR（先 mvn -B -DskipTests package）"
mkdir -p "$WORK/data" "$WORK/logs" "$WORK/backup" "$WORK/offline/corpus"
cp "$JAR" "$WORK/app.jar"
cp "$ROOT/offline/corpus/chunks.jsonl" "$WORK/offline/corpus/chunks.jsonl"

export H2_DB_URL="jdbc:h2:file:$WORK/data/users"
export H2_DB_PASSWORD="smoke-h2-pass"
export DEMO_PASSWORD="smoke-demo-pass"

# 先 seed（Flyway 在 CLI 启动时也跑一次，证明 CLI 与网关共用同一迁移）
(cd "$WORK" && "$JAVA_BIN" -cp app.jar -Dloader.main=com.opspilot.auth.UserAdminCli \
  org.springframework.boot.loader.launch.PropertiesLauncher add \
  --user smoke --tenant tenant-internal --level 3 --role platform --password-env DEMO_PASSWORD) \
  >/dev/null 2>&1 || fail "seed 用户失败（Flyway/CLI）"

cd "$WORK"
STORAGE_MODE=memory DASHSCOPE_MODE=mock SERVER_PORT="$PORT" SERVER_ADDRESS=127.0.0.1 \
JWT_SECRET="smoke-secret-0123456789-abcdefghijklmnopqrstuvwxyz" \
DASHSCOPE_API_KEY="" \
"$JAVA_BIN" -jar app.jar >app.log 2>&1 &
PID=$!
cd "$ROOT"
BASE="http://127.0.0.1:$PORT"

for _ in $(seq 1 60); do
  if curl -fsS --max-time 2 "$BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS --max-time 3 "$BASE/actuator/health" >/dev/null || fail "健康端点未起来"
curl -fsS --max-time 3 "$BASE/actuator/health/readiness" | grep -q '"status":"UP"' || fail "readiness 非 UP"

printf '{"username":"smoke","password":"smoke-demo-pass"}' > "$WORK/login.json"
TOKEN="$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H "Content-Type: application/json" \
  --data-binary "@$WORK/login.json" | "$PYTHON" -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
[ -n "$TOKEN" ] || fail "登录未返回 token"

printf '{"query":"50012_DB_TIMEOUT 怎么排查","source":"manual"}' > "$WORK/chat.json"
curl -fsS -N --max-time 20 -X POST "$BASE/api/v1/copilot/chat/stream" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  --data-binary "@$WORK/chat.json" > "$WORK/chat.out" || fail "SSE chat 失败"
grep -q "event:done" "$WORK/chat.out" || fail "SSE 未收到 done"
"$PYTHON" "$ROOT/scripts/verify_audit_chain.py" "$WORK/logs/audit.jsonl" \
  --head "$WORK/logs/.audit-head" >/dev/null || fail "审计哈希链校验失败"

curl -fsS --max-time 5 "$BASE/actuator/prometheus" > "$WORK/prom"
grep -q "^aiops_ttft_seconds_count" "$WORK/prom" || fail "缺 aiops_ttft_seconds_count"
grep -q '^aiops_request_seconds_count{outcome="success"}' "$WORK/prom" || fail "缺成功请求耗时序列"

curl -fsS --max-time 5 "$BASE/api/v1/admin/state" -H "Authorization: Bearer $TOKEN" > "$WORK/state.json"
grep -q '"build"' "$WORK/state.json" || fail "state 缺 build"
grep -q '"commit"' "$WORK/state.json" || fail "state 缺 build.commit"

echo "PASS smoke：health/readiness/登录/SSE/指标/state 全绿（jar=$(basename "$JAR")）"
