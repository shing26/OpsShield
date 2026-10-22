#!/usr/bin/env bash
# 恢复完整性演练（OPS.md §8）：备份"能不能恢复"不能等灾难才知道。
#
# 做什么：取最新 users 备份 → 恢复到独立演练库（默认 data/restore-drill）→ 用 CLI 列账号，
# 核对"账号数 > 0 且每行 token_ver 可读" → 删除演练库 → 打印 PASS/FAIL 并以退出码表态。
# 为什么演练库而不是主库：恢复主库要停网关、移走旧库，是事故动作；演练库是另一个文件，
# 网关在跑也能做（--to-db 的目标不是网关持有的 users.mv.db，不存在同库双写）。
# 为什么仍要 FORCE=1：user_admin.sh 的网关守卫是"默认拒绝 CLI"的粗粒度闸门，本脚本明确
# 知道自己在碰演练库，所以显式覆盖——覆盖必须写出来，不能悄悄发生。
# 为什么不悄悄跳过加密产物：最新备份若是 .enc 而没有 BACKUP_PASSPHRASE，说明"最新一份
# 不可演练"；退回去演练旧明文备份会制造假绿。此时失败并说清原因。
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f .env ]; then set -a; . ./.env; set +a; fi

DRILL_DB="${DRILL_DB:-restore-drill}"
H2_URL="${H2_DB_URL:-jdbc:h2:file:./data/users}"
DB_FILE="${H2_URL#jdbc:h2:file:}"
DB_FILE="${DB_FILE%%;*}"
DB_DIR="$(dirname "$DB_FILE")"
TMPD=""

die() { echo "FAIL: $*" >&2; exit 1; }

cleanup() {
  # 演练库必须清掉：留着会让下一次 restore 撞"目标库已存在"，也会让人误以为那是主库。
  rm -f "${DB_DIR}/${DRILL_DB}.mv.db" "${DB_DIR}/${DRILL_DB}.trace.db" "${DB_DIR}/${DRILL_DB}.lock.db"
  [ -n "$TMPD" ] && rm -rf "$TMPD"
  return 0
}
trap cleanup EXIT

sha256_of() {
  local f="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$f" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$f" | cut -d' ' -f1
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 -r "$f" | cut -d' ' -f1
  elif command -v certutil >/dev/null 2>&1; then
    certutil -hashfile "$f" SHA256 | sed -n '2p' | tr -d ' \r'
  else
    die "无 sha256 工具，无法核对产物摘要"
  fi
}

# 与 backup.sh 同一约定：openssl 优先（口令走 env: 不进 argv），7z 兜底。
decrypt_file() {
  local in="$1" out="$2"
  if command -v openssl >/dev/null 2>&1; then
    openssl enc -d -aes-256-cbc -pbkdf2 -in "$in" -out "$out" -pass env:BACKUP_PASSPHRASE 2>/dev/null
  elif command -v 7z >/dev/null 2>&1; then
    7z e -so -p"$BACKUP_PASSPHRASE" "$in" > "$out" 2>/dev/null
  else
    return 1
  fi
}

# ---- 1) 选最新产物（明文与加密一起排序：只挑明文会让"最新一份不可演练"被掩盖）----
shopt -s nullglob
CANDIDATES=(backup/users-*.zip backup/users-*.zip.enc)
shopt -u nullglob
[ "${#CANDIDATES[@]}" -gt 0 ] || die "backup/ 下没有 users-*.zip 产物——先跑 bash scripts/backup.sh"
LATEST=$(ls -1t "${CANDIDATES[@]}" | head -1)

# ---- 2) 加密产物先解密（解密到 backup/ 下：H2BackupPath 白名单只认 backup/*.zip）----
SRC_ZIP="$LATEST"
if [[ "$LATEST" == *.enc ]]; then
  [ -n "${BACKUP_PASSPHRASE:-}" ] || die "最新备份是加密产物（$LATEST），但 BACKUP_PASSPHRASE 未设置——无法演练最新一份（拒绝退回旧明文制造假绿）"
  TMPD=$(mktemp -d backup/.drill-XXXXXX)
  SRC_ZIP="$TMPD/source.zip"
  decrypt_file "$LATEST" "$SRC_ZIP" || die "加密备份解密失败（口令不对或工具异常）: $LATEST"
  [ -s "$SRC_ZIP" ] || die "解密产物为空: $LATEST"
fi

RAW_SHA=$(sha256_of "$LATEST")
SRC_SHA=$(sha256_of "$SRC_ZIP")
echo "演练源: $LATEST"
echo "  产物 sha256=$RAW_SHA"
[ "$SRC_ZIP" = "$LATEST" ] || echo "  解密后 sha256=$SRC_SHA"

# 与 .last-success.json 对账：不一致不阻断演练（可能演练的是旧产物），但必须说出来。
if [ -f backup/.last-success.json ]; then
  STATE_SHA=$(sed -n 's/.*"sha256":"\([0-9a-f]\{64\}\)".*/\1/p' backup/.last-success.json | head -1)
  if [ -n "$STATE_SHA" ]; then
    if [ "$STATE_SHA" = "$RAW_SHA" ]; then
      echo "  与 .last-success.json 的 sha256 一致"
    else
      echo "  warn: sha256 与 .last-success.json 不一致（状态记录的不是这份产物）——继续演练"
    fi
  fi
fi

# ---- 3) 恢复到演练库 ----
cleanup   # 先清掉上次残留（trap 里还会再清一次）
if ! FORCE=1 bash scripts/user_admin.sh restore --from "$SRC_ZIP" --to-db "$DRILL_DB"; then
  die "恢复到演练库失败（见上方 CLI 输出）"
fi
[ -f "${DB_DIR}/${DRILL_DB}.mv.db" ] || die "CLI 说恢复成功但 ${DB_DIR}/${DRILL_DB}.mv.db 不存在"

# ---- 4) 核对账号数 / token_ver ----
# stderr 单独丢弃：user_admin.sh 的 FORCE 警告不是账号行，混进来会把 TOTAL 算多一行。
if ! LIST_OUT=$(FORCE=1 bash scripts/user_admin.sh list --to-db "$DRILL_DB" 2>/dev/null); then
  die "打开演练库失败（list --to-db）: $LIST_OUT"
fi
COUNT=$(printf '%s\n' "$LIST_OUT" | grep -cE 'ver=[0-9]+$' || true)
TOTAL=$(printf '%s\n' "$LIST_OUT" | grep -cve '^$' || true)
[ "$TOTAL" -gt 0 ] || die "演练库 0 个账号——恢复出来的库是空的（备份可能只是空 schema）"
[ "$COUNT" -eq "$TOTAL" ] || die "有账号行缺 token_ver（$COUNT/$TOTAL 可解析）——恢复出的库结构不对: $LIST_OUT"
echo "  账号数=$COUNT（token_ver 全部可读）"
printf '%s\n' "$LIST_OUT" | sed 's/^/    /'

echo "PASS 恢复演练：$LATEST → $DRILL_DB，账号数=$COUNT；演练库已清理（本次 restore 已追加 logs/audit.jsonl 的 ev=admin 行）"
