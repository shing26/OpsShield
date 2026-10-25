#!/usr/bin/env bash
# OpsShield 红队对抗套件（含 L1 操作面端到端）：
#   起 memory/mock 活体栈（L1 启用 + 派发指回 runner 内置假执行器）→ manifest 防篡改 →
#   32 条用例（注入/越狱/跨租户/密级/MCP/L1 全链/verifier）→ 泄漏或 SKIP 即非 0。
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

# JAVA_BIN 探测（Git Bash PATH 的 java 常是 17，jar 需 21）
JAVA_BIN="${JAVA_BIN:-}"
if [ -z "$JAVA_BIN" ]; then
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

mkdir -p "$WORK/data" "$WORK/logs" "$WORK/offline/corpus"
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
seed sre-manager tenant-internal 3 platform
seed sre-acme    tenant-acme     3 sre

# 语料追加 L1 声明 chunk（仅网关侧副本；仓库语料不动）
"$PYTHON" - "$WORK/offline/corpus/chunks.jsonl" << 'PYEOF'
import json, sys
cmd = "kubectl rollout restart deployment/inventory-api -n prod"
fence = chr(96) * 3
text = "## 恢复动作\n确认副本数后执行滚动重启：\n" + fence + "bash\n" + cmd + "\n" + fence + "\n"
# 键名与仓库语料同风格（snake_case）——Jackson 全局命名策略
chunk = {
    "chunk_id": "rb-l1rt::恢复动作", "doc_id": "rb-l1rt", "type": "runbook_step",
    "text": text, "breadcrumb": "rb-l1rt > 恢复",
    "metadata": {"service": "inventory-service", "error_codes": ["50012_DB_TIMEOUT"],
                 "auth_level": 3, "env": "prod", "tenant": "tenant-internal",
                 "action_class": "L1-write-low"},
}
with open(sys.argv[1], "a", encoding="utf-8") as fh:
    fh.write(json.dumps(chunk, ensure_ascii=False) + "\n")
PYEOF

# 派发密钥运行时生成（64 位十六进制）——与 runner 的 L1_KEY 同值
DISPATCH_KEY="$(printf 'bd%.0s' $(seq 1 32))"

cd "$WORK"
STORAGE_MODE=memory DASHSCOPE_MODE=mock SERVER_PORT="$PORT" SERVER_ADDRESS=127.0.0.1 \
JWT_SECRET="$(head -c 48 /dev/urandom | base64 | tr -dc a-zA-Z0-9)" DASHSCOPE_API_KEY="" \
OPSPILOT_L1_ENABLED=true OPSPILOT_DISPATCH_KEY="$DISPATCH_KEY" \
OPSPILOT_L1_DISPATCHWEBHOOK="http://127.0.0.1:18104/exec" \
"$JAVA_BIN" -Xms128m -Xmx512m -jar app.jar >app.log 2>&1 &
PID=$!
cd "$ROOT"
export OPSPILOT_BASE="http://127.0.0.1:$PORT"
export REDTEAM_EXECUTOR_PORT=18104
for _ in $(seq 1 60); do
  if curl -fsS --max-time 2 "$OPSPILOT_BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS --max-time 3 "$OPSPILOT_BASE/actuator/health/readiness" | grep -q UP \
  || fail "readiness 非 UP"

echo "== 红队全量：32 用例（fail-closed，泄漏/SKIP 即 FAIL） =="
(cd offline && "$PYTHON" redteam/runner.py --base "$OPSPILOT_BASE") || {
  echo "FAIL: 红队发现泄漏或用例失效（详见 offline/redteam/report/）" >&2
  exit 1
}

echo "PASS redteam：零泄漏零跳过（报告见 offline/redteam/report/redteam_report.md）"
