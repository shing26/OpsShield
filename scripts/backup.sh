#!/usr/bin/env bash
# 备份 SOP（OPS.md §8）：只备份**不可再生**的数据。
#   users zip     —— H2 账号库（口令散列 + token_ver 吊销状态）
#   audit tar.gz  —— 合规留痕（logs/，logback 14 天滚动会回收）
# 刻意不备份 ES/Qdrant：派生索引，chunks.jsonl 在 git（ADR-0001），恢复=reingest（实测 423 文档 22.3s，live）。
# 路径 A（网关在跑，常态）：HTTP /admin/backup 由 DB 所有者执行，零 TCP 依赖；
# 路径 B（网关已停）：CLI 嵌入式直连。
#
# G0（2026-10-22 运行可信）把"备份完成"升级成"备份可信"：
#   1) 产物必须 unzip -t 通过才允许宣布成功——"文件存在"≠"能恢复"；
#   2) 落 sha256/size/verified 到 backup/.last-success.json（tmp+mv 原子替换），
#      让"最近成功备份时间"成为机器可读信号（gauge aiops.backup.last_success.timestamp.seconds）；
#   3) 清理只在成功之后执行——旧脚本在失败路径照样 find -delete，断档一周重跑会把唯一存量也删掉；
#   4) 可选 BACKUP_PASSPHRASE（openssl 优先，口令走 env: 不进 argv；7z 兜底）：
#      账号库里有 bcrypt 散列，备份落在哪块盘上不该默认是明文。缺工具显式失败，不静默退化。
# 退出码：0 = 成功且产物已核验；非 0 = 失败（不写状态文件、不清理）。
set -euo pipefail
cd "$(dirname "$0")/.."
DAY=$(date +%F)
mkdir -p backup

die() { echo "FAIL: $*" >&2; exit 1; }

# ---- 工具与校验（缺工具显式失败，不许"看起来成功"）----
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
    die "无 sha256 工具（sha256sum/shasum/openssl/certutil 均缺失）——拒绝在未摘要的情况下宣布备份成功"
  fi
}

# zip 完整性：unzip -t 是主判据；宿主无 unzip 时用 Python zipfile 等价校验（仍算通过），
# 两者都没有就失败——不做"跳过校验"这种自欺。
verify_zip() {
  local f="$1"
  if command -v unzip >/dev/null 2>&1; then
    unzip -tqq "$f" >/dev/null 2>&1
    return $?
  fi
  local py
  py=$(bash scripts/py.sh 2>/dev/null || true)
  [ -n "$py" ] || die "无 unzip 且无 Python——无法校验 $f 的 zip 完整性（拒绝写成功状态）"
  "$py" - "$f" <<'PY'
import sys, zipfile
try:
    z = zipfile.ZipFile(sys.argv[1])
    sys.exit(0 if z.testzip() is None else 1)
except Exception:
    sys.exit(1)
PY
}

# 加密：openssl 优先——它支持 -pass env:VAR，口令不落 argv（ps/任务计划历史都看不到）。
# 7z 没有等价开关，只能 -p，故仅在无 openssl 时兜底并显式警告。
encrypt_file() {
  local in="$1" out="$2"
  if command -v openssl >/dev/null 2>&1; then
    openssl enc -aes-256-cbc -pbkdf2 -salt -in "$in" -out "$out" -pass env:BACKUP_PASSPHRASE \
      || die "openssl 加密失败: $in"
  elif command -v 7z >/dev/null 2>&1; then
    echo "warn: 7z 加密会把口令暴露在进程参数中；建议安装 openssl" >&2
    7z a -t7z -mhe=on -p"$BACKUP_PASSPHRASE" "$out" "$in" >/dev/null || die "7z 加密失败: $in"
  else
    die "BACKUP_PASSPHRASE 已设置但 openssl/7z 都不可用——显式失败（不退化成明文备份）"
  fi
  rm -f "$in"
}

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

# ---- 1) 产出 users 备份（网关活着走 HTTP，停了走 CLI）----
USERS_ZIP="backup/users-${DAY}.zip"
if curl -s -o /dev/null --max-time 3 http://localhost:8081/actuator/health; then
  set -a; [ -f .env ] && . ./.env; set +a
  PY=$(bash scripts/py.sh 2>/dev/null || true)
  [ -n "$PY" ] || { echo "无可用 Python（py.sh 三档探测失败）"; exit 1; }
  # 仓库根执行（py.sh 输出为根相对路径；cd 进 offline 会使 venv 路径失效）
  L3=$("$PY" -c "import sys;sys.path.insert(0,'offline');import localapi;print(localapi.login('sre-full'))")
  curl -sf -X POST http://localhost:8081/api/v1/admin/backup -H "Authorization: Bearer $L3" \
    -H "Content-Type: application/json" \
    -d "{\"to\":\"backup/users-${DAY}.zip\"}" | grep -o '"backup":"[^"]*"' || { echo "HTTP 备份失败"; exit 1; }
  # 产物存在性校验（QA P1-3）：响应说成功≠文件真的在——必须核宿主文件，
  # 防"容器模式落未映射层"造成的每日假绿
  test -s "$USERS_ZIP" || { echo "响应正常但宿主 backup/users-${DAY}.zip 缺失（容器未挂载 ./backup 卷？）"; exit 1; }
else
  bash scripts/user_admin.sh backup --to "$USERS_ZIP"
  # CLI 路径同样必须核产物（QA P1-3 只补了 HTTP 路径）：响应/退出码说成功≠文件真在
  test -s "$USERS_ZIP" || { echo "CLI 备份未产出宿主 backup/users-${DAY}.zip"; exit 1; }
fi

# ---- 2) 产物核验（不通过就不许说成功）----
[ -s "$USERS_ZIP" ] || die "备份产物缺失/为空: $USERS_ZIP"
verify_zip "$USERS_ZIP" || die "备份 zip 校验失败（unzip -t）: $USERS_ZIP —— 文件可能被截断/磁盘满"
PLAIN_SHA=$(sha256_of "$USERS_ZIP")
echo "users 备份已核验: $USERS_ZIP sha256=$PLAIN_SHA size=$(wc -c < "$USERS_ZIP" | tr -d ' ')"

# ---- 3) 审计打包（logs/ 空可以跳过，但要说出来；tar 因权限/IO 失败必须失败）----
AUDIT_TGZ="backup/audit-${DAY}.tar.gz"
if [ -n "$(ls -A logs 2>/dev/null || true)" ]; then
  tar czf "$AUDIT_TGZ" logs/ || die "审计打包失败（logs/ 非空但 tar 非零退出）"
  tar tzf "$AUDIT_TGZ" >/dev/null 2>&1 || die "审计包读回失败: $AUDIT_TGZ"
else
  echo "warn: logs/ 为空，跳过审计打包"
fi

# ---- 4) 可选加密（加密后回读自检：解不开/对不上摘要都算失败）----
USERS_ARTIFACT="$USERS_ZIP"
ENCRYPTED=false
if [ -n "${BACKUP_PASSPHRASE:-}" ]; then
  encrypt_file "$USERS_ZIP" "${USERS_ZIP}.enc"
  USERS_ARTIFACT="${USERS_ZIP}.enc"
  ENCRYPTED=true
  [ -f "$AUDIT_TGZ" ] && encrypt_file "$AUDIT_TGZ" "${AUDIT_TGZ}.enc"
  TMPV=$(mktemp -d)
  trap 'rm -rf "$TMPV"' EXIT
  decrypt_file "$USERS_ARTIFACT" "$TMPV/plain.zip" \
    || die "加密产物无法解密（BACKUP_PASSPHRASE 不匹配或加密工具异常）"
  [ "$(sha256_of "$TMPV/plain.zip")" = "$PLAIN_SHA" ] \
    || die "解密回读的 sha256 与加密前不一致——加密产物不可信"
  verify_zip "$TMPV/plain.zip" || die "解密回读的 zip 校验失败——加密产物不可信"
  rm -rf "$TMPV"
  trap - EXIT
fi
USERS_SHA=$(sha256_of "$USERS_ARTIFACT")
USERS_SIZE=$(wc -c < "$USERS_ARTIFACT" | tr -d ' ')

# ---- 5) 成功状态（原子替换：读侧永远看不到半截 JSON）----
STATE_TMP="backup/.last-success.json.tmp"
printf '{"epoch":%s,"ts":"%s","sha256":"%s","size":%s,"verified":true,"file":"%s","encrypted":%s}\n' \
  "$(date +%s)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$USERS_SHA" "$USERS_SIZE" "$USERS_ARTIFACT" "$ENCRYPTED" \
  > "$STATE_TMP"
mv -f "$STATE_TMP" backup/.last-success.json

# ---- 6) 清理：只在此刻（全部产物核验通过、状态已落盘）之后执行 ----
find backup -name 'users-*.zip' -mtime +7 -delete
find backup -name 'users-*.zip.enc' -mtime +7 -delete
find backup -name 'audit-*.tar.gz' -mtime +14 -delete
find backup -name 'audit-*.tar.gz.enc' -mtime +14 -delete
# 清理后必须还有账号库备份活着：cron 断档一周后重跑，别把唯一存量也删掉
ls backup/users-*.zip* >/dev/null 2>&1 \
  || die "清理后没有任何 users 备份存活——检查保留策略/系统时钟（mtime 异常会误删新备份）"
ls -la backup/ | tail -5
echo "OK ${DAY} 备份完成且已核验（users 保 7 天 / audit 14 天；encrypted=${ENCRYPTED}）"
