#!/usr/bin/env bash
# G1 发布/回滚链路演练（门禁 5 的可离线部分，2026-10-22）：
#   1) release.sh 产出 jar + sha256 + manifest；
#   2) sha256sum -c 独立复核；
#   3) rollback.sh 校验同一份产物并打印 systemd 操作步骤（不 --apply，不碰真实服务）。
# 它证明“产物可校验、回滚入口可执行”；**不**证明真实停服/切换/回滚成功——那仍要 live 演练。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-v0.0.0-drill}"
cd "$ROOT"
OUT="$(mktemp)"
cleanup() { rm -rf "$ROOT/release" "$OUT"; }
trap cleanup EXIT INT TERM

echo "== 1. release =="
bash scripts/release.sh "$VERSION"
[ -f "release/opspilot-$VERSION.jar" ] || { echo "FAIL 缺 jar" >&2; exit 1; }
[ -f "release/manifest-$VERSION.json" ] || { echo "FAIL 缺 manifest" >&2; exit 1; }

echo "== 2. sha256 独立复核 =="
(cd release && sha256sum -c "opspilot-$VERSION.jar.sha256")

echo "== 3. rollback 入口校验（不 apply） =="
bash scripts/rollback.sh "$VERSION" >"$OUT"
grep -q "回滚目标校验" "$OUT" || { echo "FAIL rollback 未做目标校验" >&2; cat "$OUT" >&2; exit 1; }
grep -q "systemctl stop opspilot-gateway" "$OUT" || { echo "FAIL rollback 缺操作步骤" >&2; exit 1; }
echo "PASS release/rollback 链路（产物哈希 + 回滚入口；未执行真实停服切换）"
