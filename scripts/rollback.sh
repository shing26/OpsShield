#!/usr/bin/env bash
# G1 回滚（2026-10-22）：只做**校验与操作提示**，不自动停服/覆盖——回滚是高风险动作，
# 单机形态下由值班人确认 jar 哈希、旧版本 manifest 与当前服务状态后手动执行。
# 用法：bash scripts/rollback.sh v1.2.0 [--apply]
#   --apply：在确认后执行 systemctl 停/换/起（仅 Linux systemd 形态；容器形态请改镜像 tag）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-}"
APPLY="${2:-}"
[ -n "$VERSION" ] || { echo "用法：bash scripts/rollback.sh <version> [--apply]" >&2; exit 2; }
JAR="$ROOT/release/opspilot-$VERSION.jar"
SUMFILE="$ROOT/release/opspilot-$VERSION.jar.sha256"
MAN="$ROOT/release/manifest-$VERSION.json"
[ -f "$JAR" ] || { echo "缺回滚产物：$JAR（先 release 该版本并归档）" >&2; exit 2; }
[ -f "$SUMFILE" ] || { echo "缺校验文件：$SUMFILE" >&2; exit 2; }
[ -f "$MAN" ] || { echo "缺 manifest：$MAN" >&2; exit 2; }

echo "== 回滚目标校验 =="
(cd "$ROOT/release" && sha256sum -c "opspilot-$VERSION.jar.sha256")
echo "== manifest =="
cat "$MAN"

echo
echo "== 手工步骤（systemd 单机） =="
echo "  1) systemctl stop opspilot-gateway"
echo "  2) install -m 0644 $JAR /opt/opspilot/app.jar"
echo "  3) systemctl start opspilot-gateway"
echo "  4) curl -fsS http://127.0.0.1:8081/actuator/health/readiness"
echo "  5) 查 /api/v1/admin/state 的 build.version/commit 是否等于 manifest"

if [ "$APPLY" = "--apply" ]; then
  command -v systemctl >/dev/null 2>&1 || { echo "--apply 仅支持 systemd 形态" >&2; exit 2; }
  systemctl stop opspilot-gateway
  install -m 0644 "$JAR" /opt/opspilot/app.jar
  systemctl start opspilot-gateway
  echo "已应用回滚；请按第 4/5 步验证"
else
  echo "（未执行；确认后加 --apply，或按上面步骤手工操作）"
fi
