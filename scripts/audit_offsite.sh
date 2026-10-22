#!/usr/bin/env bash
# G1 审计异地副本（门禁 9，2026-10-22）：把当前审计链复制到仓库之外的只读目录，并当场校验。
#
# 为什么不是"再 cp 一份"：异地副本必须满足 ① 在仓库外（同盘同目录不是异地）；
# ② 每次生成新的时间戳子目录（不覆盖历史，避免"最新副本被改"）；③ 复制后校验链自洽；
# ④ 落地即只读（普通误删/误改至少需要先 chmod）。真正的 WORM（S3 Object Lock/远端 syslog）
# 仍需外部存储，本脚本只负责"把证据安全送出去"这一段。
#
# 用法：AUDIT_OFFSITE_DIR=/mnt/audit-offsite bash scripts/audit_offsite.sh
#      bash scripts/audit_offsite.sh --to /mnt/audit-offsite --dry-run
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="${PYTHON:-python3}"
if ! command -v "$PY" >/dev/null 2>&1; then PY=python; fi

TO="${AUDIT_OFFSITE_DIR:-}"
DRY=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --to) TO="${2:-}"; shift 2 ;;
    --dry-run) DRY=1; shift ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done
[ -n "$TO" ] || { echo "用法：AUDIT_OFFSITE_DIR=<仓库外目录> $0 [--dry-run]" >&2; exit 2; }
# 路径比较要跨 Git Bash/MSYS 形态：调用方可能传 D:\...，而 $ROOT 是 /d/...；
# 用 cygpath -m 统一成 D:/... 再比，否则"仓库内"守卫在 Windows 上会漏。
ROOT_CMP="$ROOT"
TO_CMP="$TO"
if command -v cygpath >/dev/null 2>&1; then
  ROOT_CMP="$(cygpath -m "$ROOT")"
  TO_CMP="$(cygpath -m "$TO")"
fi
case "$TO_CMP" in
  "$ROOT_CMP"|"$ROOT_CMP"/*) echo "拒绝：异地目录不能位于仓库内（$TO）" >&2; exit 2 ;;
esac
cd "$ROOT"
[ -f logs/audit.jsonl ] || { echo "缺 logs/audit.jsonl" >&2; exit 2; }

# 先验本地链，再复制；本地都断链就不把坏副本送出去。
shopt -s nullglob
AUDIT_FILES=(logs/audit*.jsonl)
shopt -u nullglob
"$PY" scripts/verify_audit_chain.py "${AUDIT_FILES[@]}" --head logs/.audit-head

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="$TO/$STAMP"
if [ "$DRY" = "1" ]; then
  echo "DRY-RUN 将复制 ${#AUDIT_FILES[@]} 个审计文件 + head 到 $DEST"
  exit 0
fi
mkdir -p "$DEST"
cp -p "${AUDIT_FILES[@]}" "$DEST"/
if [ -f logs/.audit-head ]; then cp -p logs/.audit-head "$DEST"/; fi

(cd "$DEST" && { command -v sha256sum >/dev/null 2>&1 && sha256sum audit*.jsonl .audit-head 2>/dev/null || shasum -a 256 audit*.jsonl .audit-head 2>/dev/null; } > SHA256SUMS)
# 复制后按副本自身再验一次（防复制截断）
"$PY" "$ROOT/scripts/verify_audit_chain.py" "$DEST"/audit*.jsonl --head "$DEST/.audit-head"
chmod -R a-w,a+r "$DEST"
echo "OK 审计异地副本：$DEST（${#AUDIT_FILES[@]} 个文件，已只读）"
