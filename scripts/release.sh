#!/usr/bin/env bash
# G1 发布产物（2026-10-22）：一条命令产出 jar + sha256 + manifest（版本/commit/构建时间）。
# 不 tag、不 push、不改 pom version——发布编号由调用方显式给，避免脚本偷偷改版本。
# 用法：bash scripts/release.sh v1.2.0
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-}"
[ -n "$VERSION" ] || { echo "用法：bash scripts/release.sh <version>（如 v1.2.0）" >&2; exit 2; }
JAR="$ROOT/target/opspilot-gateway-1.0.0.jar"
OUT="$ROOT/release"
COMMIT="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
DIRTY="$(git -C "$ROOT" status --porcelain 2>/dev/null | wc -l | tr -d " ")"

echo "build $VERSION @ $COMMIT（工作区变更 $DIRTY 项）"
(cd "$ROOT" && mvn -B -q -DskipTests package)
[ -f "$JAR" ] || { echo "构建失败：$JAR 不存在" >&2; exit 1; }
mkdir -p "$OUT"
cp "$JAR" "$OUT/opspilot-$VERSION.jar"

if command -v sha256sum >/dev/null 2>&1; then
  SUM="$(sha256sum "$OUT/opspilot-$VERSION.jar" | awk '{print $1}')"
else
  SUM="$(shasum -a 256 "$OUT/opspilot-$VERSION.jar" | awk '{print $1}')"
fi
printf "%s  %s\n" "$SUM" "opspilot-$VERSION.jar" > "$OUT/opspilot-$VERSION.jar.sha256"

cat > "$OUT/manifest-$VERSION.json" <<EOF
{
  "version": "$VERSION",
  "commit": "$COMMIT",
  "dirty_files": $DIRTY,
  "built_at_utc": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "jar": "opspilot-$VERSION.jar",
  "sha256": "$SUM",
  "java": "$(java -version 2>&1 | head -1)"
}
EOF

echo "OK release/$VERSION"
echo "  jar      release/opspilot-$VERSION.jar"
echo "  sha256   $SUM"
echo "  manifest release/manifest-$VERSION.json"
if [ "$DIRTY" != "0" ]; then
  echo "WARN 工作区有未提交变更；发布 manifest 的 dirty_files 已如实登记。" >&2
fi
