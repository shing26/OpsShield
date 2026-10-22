#!/usr/bin/env bash
# G1 SBOM（2026-10-22）：CycloneDX Maven 插件生成依赖物料清单；CI 归档 target/bom.json。
# 这不是漏洞扫描（SCA 仍缺，见 docs/ops/production-gate.md 门禁 10），但至少让"这次发布带了什么"可复核。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mvn -B -q org.cyclonedx:cyclonedx-maven-plugin:2.8.2:makeAggregateBom \
  -DoutputFormat=json -DoutputName=bom
[ -f target/bom.json ] || { echo "SBOM 未生成 target/bom.json" >&2; exit 1; }
grep -q '"bomFormat" *: *"CycloneDX"' target/bom.json || { echo "SBOM 格式不对" >&2; exit 1; }
echo "OK target/bom.json（$(wc -c < target/bom.json) bytes）"
