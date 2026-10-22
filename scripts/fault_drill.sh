#!/usr/bin/env bash
# G1 故障/护栏演练（2026-10-22）：两条不需要活体中间件的硬判据。
#   A. strict 部署声明下，占位 JWT + mock + memory + 0.0.0.0 必须在启动期被拒（exit 1），不能起服务。
#   B. 审计链被改一行时，校验器必须 exit 3；未改时必须 exit 0。
# 把 G0 手工做过的两次验证变成可重复脚本；不替代门禁 5/6/10 的 live 演练。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${FAULT_JAR:-$ROOT/target/opspilot-gateway-1.0.0.jar}"
JAVA_BIN="${JAVA_BIN:-java}"
PYTHON="${PYTHON:-python3}"
if ! command -v "$PYTHON" >/dev/null 2>&1; then PYTHON=python; fi
TMP="$(mktemp -d)"
WORK="$TMP"
if command -v cygpath >/dev/null 2>&1; then WORK="$(cygpath -m "$TMP")"; fi
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT INT TERM
fail() { echo "FAIL: $*" >&2; exit 1; }

[ -f "$JAR" ] || fail "jar 不存在：$JAR（先 mvn -B -DskipTests package）"

echo "== A. strict 启动拒绝 =="
STRICT_LOG="$WORK/strict.log"
(
  # 子 shell 不能继承 EXIT trap：否则 java 退出即触发 cleanup，父进程的 TMP 被删。
  trap - EXIT INT TERM
  set +e
  cd "$WORK"
  STORAGE_MODE=memory DASHSCOPE_MODE=mock OPSPILOT_STRICT=true \
  SERVER_ADDRESS=0.0.0.0 JWT_SECRET="change-me-to-a-random-32-char-secret" \
  H2_DB_PASSWORD="" H2_DB_URL="jdbc:h2:mem:strict-drill" DASHSCOPE_API_KEY="" \
  "$JAVA_BIN" -jar "$JAR" --server.port=0 >"$STRICT_LOG" 2>&1 &
  PID=$!
  echo "$PID" > "$WORK/strict.pid"
  wait "$PID"
  echo $? > "$WORK/strict.rc"
)
PID="$(cat "$WORK/strict.pid")"
for _ in $(seq 1 60); do
  if ! kill -0 "$PID" 2>/dev/null; then break; fi
  sleep 1
done
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID" 2>/dev/null || true
  wait "$PID" 2>/dev/null || true
  fail "strict 配置没有在 60s 内退出（守卫失效，服务可能已起）"
fi
RC="$(cat "$WORK/strict.rc" 2>/dev/null || echo 127)"
[ "$RC" -ne 0 ] || fail "strict 配置退出码为 0（应当拒绝启动）"
grep -qi "strict\|DeploymentGuard\|拒绝" "$STRICT_LOG" || fail "strict 日志没有点名守卫/拒绝原因"
echo "PASS strict：exit=$RC，拒绝原因见日志首段"

echo "== B. 审计链篡改检测 =="
AUDIT_SRC="$(grep -l '"hash":"' logs/audit*.jsonl 2>/dev/null | head -n 1 || true)"
if [ -z "$AUDIT_SRC" ]; then
  echo "WARN 当前没有带 hash 的审计行（新仓库/未产生请求），跳过篡改检测"
  echo "PASS fault drill（A 通过，B 跳过）"
  exit 0
fi
cp "$AUDIT_SRC" "$WORK/audit.jsonl"
[ -f logs/.audit-head ] && cp logs/.audit-head "$WORK/.audit-head" || true
"$PYTHON" "$ROOT/scripts/verify_audit_chain.py" "$WORK/audit.jsonl" \
  --head "$WORK/.audit-head" --allow-segment >/dev/null || fail "原始审计副本链校验失败"
# 只改**带 hash 的那一行**并重新序列化：改 legacy 行不会影响链，测不出篡改。
"$PYTHON" - "$WORK/audit.jsonl" <<'PY'
import json
import sys
p = sys.argv[1]
lines = open(p, encoding="utf-8").read().splitlines()
for i, line in enumerate(lines):
    if '"hash":"' in line:
        obj = json.loads(line)
        obj["ev"] = "tampered"
        lines[i] = json.dumps(obj, ensure_ascii=False, separators=(",", ":"))
        break
open(p, "w", encoding="utf-8").write("\n".join(lines) + "\n")
PY
set +e
"$PYTHON" "$ROOT/scripts/verify_audit_chain.py" "$WORK/audit.jsonl" \
  --head "$WORK/.audit-head" --allow-segment >/dev/null 2>&1
TAMPER_RC=$?
set -e
[ "$TAMPER_RC" -eq 3 ] || fail "篡改后校验器 rc=$TAMPER_RC（应为 3）"
echo "PASS 篡改检测：改一行后校验器 exit=3"
echo "PASS fault drill（strict 拒绝 + 审计篡改检测）"
