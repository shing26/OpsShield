#!/usr/bin/env bash
# OpsShield 生产门禁（G0 fast / G1 full）
#
# 为什么要有它：审查发现的问题大多不是"不会修"，而是"修了没人持续核"——
# 备份停了 35 天、网关 down 2 小时无人知、面板 XSS 藏在一次改版里，都是缺一个
# 每次都能跑、失败能点名的事后判据。本脚本把 G0 的验收点收敛成一条命令。
#
# 用法：
#   bash scripts/production_gate.sh --fast     # 零依赖：静态检查 + 单测 + 契约/文档门闩 + 备份龄
#   bash scripts/production_gate.sh --full     # 额外查活体：health/prometheus/备份龄（要求服务已起）
#   SKIP_TESTS=1 bash scripts/production_gate.sh --fast   # 只跑静态（改文档时提速）
#
set -uo pipefail
cd "$(dirname "$0")/.."

MODE="${1:---fast}"
case "$MODE" in
  --fast|--full) ;;
  *) echo "用法: $0 [--fast|--full]" >&2; exit 2 ;;
esac

PASS=0; FAIL=0; WARN=0
ok()   { printf 'PASS  %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf 'FAIL  %s\n' "$*"; FAIL=$((FAIL+1)); }
warn() { printf 'WARN  %s\n' "$*"; WARN=$((WARN+1)); }

have() { command -v "$1" >/dev/null 2>&1; }
PY="${PYTHON:-}"
if [ -z "$PY" ]; then
  if have python; then PY=python
  elif have python3; then PY=python3
  else echo "FAIL 找不到 Python（文档门闩与备份龄检查需要）"; exit 1
  fi
fi

# ---------- 1. 静态门禁：G0 的每个 P0 都要有一个可 grep 的落地证据 ----------
static_ok() { # pattern file label
  if grep -q -- "$1" "$2"; then ok "静态：$3"; else bad "静态：$3（$2 未命中 $1）"; fi
}
static_absent() {
  if grep -q -- "$1" "$2"; then bad "静态：$3（$2 仍含 $1）"; else ok "静态：$3"; fi
}

static_ok "SERVER_ADDRESS"                     src/main/resources/application.yml "监听地址可配置（默认环回）"
static_ok "shutdown: graceful"                 src/main/resources/application.yml "优雅停机"
static_ok "timeout-per-shutdown-phase"         src/main/resources/application.yml "停机排空窗口"
static_ok "max-swallow-size"                   src/main/resources/application.yml "请求体上限"
static_absent "jdbc:h2:file:./data/users;AUTO_SERVER" src/main/resources/application.yml "H2 不再默认 AUTO_SERVER"
static_ok "totalSizeCap"                       src/main/resources/logback-spring.xml "审计总容量上限"
static_ok "AUDIT_ASYNC"                        src/main/resources/logback-spring.xml "审计异步落盘"
static_ok "esc(ev.sub"                         src/main/resources/static/index.html "面板事件流转义 sub（XSS）"
static_ok 'up{job="opspilot"}'                 ops/alerting/rules/opspilot.yml "网关 down 告警"
static_ok "OpsPilotBackupStale"                ops/alerting/rules/opspilot.yml "备份龄告警"
static_ok "OpsPilotRedisDown"                  ops/alerting/rules/opspilot.yml "Redis down 告警（G1）"
static_ok "OpsPilotDiskLow"                    ops/alerting/rules/opspilot.yml "磁盘余量告警（G1）"
static_ok "aiops.dependency.up"                src/main/java/com/opspilot/health/DependencyMetrics.java "依赖健康指标（G1）"
static_ok "logChatFailed"                      src/main/java/com/opspilot/metrics/AuditService.java "失败请求审计"
static_ok "prev_hash"                          src/main/java/com/opspilot/metrics/AuditService.java "审计哈希链（G1）"
static_ok "ProtectSystem=strict"               ops/systemd/opspilot-gateway.service "systemd 加固（G1）"
static_ok "AUDIT_OFFSITE_DIR"                  scripts/audit_offsite.sh "审计异地副本（G1）"
static_ok "PASS soak"                          scripts/soak.sh "准入 soak（G1）"
static_ok "PASS fault drill"                   scripts/fault_drill.sh "strict/审计篡改演练（G1）"
static_ok "PASS release/rollback"              scripts/drill_release_rollback.sh "发布/回滚链路演练（G1）"
static_ok "opspilot-audit-offsite"             ops/systemd/opspilot-audit-offsite.service "异地副本 timer（G1）"
static_ok "OPSPILOT_HUMAN_WEBHOOK"              ops/systemd/opspilot-notify.service "人类告警出口（G1）"
static_ok "aiops.requests.failed"              src/main/java/com/opspilot/metrics/OpsMetrics.java "失败请求计数"
static_ok "OPSPILOT_STRICT"                    .env.example "strict 模式文档化"
static_ok "DeploymentGuard"                    src/main/java/com/opspilot/config/DeploymentGuard.java "strict 启动校验"
static_ok "admission:"                         src/main/resources/application.yml "准入配置（G1）"
static_ok "l2-ttl-hours"                       src/main/resources/application.yml "L2 TTL（G1）"
static_ok "volatile-lru"                       docker-compose.yml "Redis 淘汰策略（G1）"
static_ok "cap_drop"                           docker-compose.yml "网关容器加固（G1）"
static_ok "dbHealthIndicator"                  src/main/java/com/opspilot/health/HealthCheckConfig.java "账号库健康探针（G1）"
static_ok "aiops.ttft.seconds"                 src/main/java/com/opspilot/metrics/OpsMetrics.java "TTFT 指标（G1）"
static_ok "probes:"                            src/main/resources/application.yml "健康分组（G1）"
static_ok "flyway:"                            src/main/resources/application.yml "schema 版本化（G1）"
static_ok "CREATE TABLE IF NOT EXISTS users"    src/main/resources/db/migration/V1__users.sql "Flyway V1 迁移"
static_ok "G1 发布 smoke"                     scripts/smoke.sh "发布 smoke 脚本（G1）"
static_ok "sha256"                             scripts/release.sh "发布 manifest（G1）"
static_ok "回滚目标校验"                       scripts/rollback.sh "回滚脚本（G1）"
static_ok "trivy"                              .github/workflows/ci.yml "SCA 扫描（G1）"
static_ok "Spring_Boot-4.1.1"                 README.md "受支持 Boot 版本（G1）"


# ---------- 1.5 仓库卫生（#36） ----------
# JVM 崩溃日志/回放日志与 H2 崩溃残留（*.trace.db）既不该进版本库，也不该躺在仓库根目录/数据目录。
# repo-map 的自纠记录明确写过「根目录只留入口」——这类文件上次就是靠人眼发现的。
clutter=$(ls -1 hs_err_pid*.log replay_pid*.log data/*.trace.db 2>/dev/null | tr '\n' ' ' || true)
if [ -n "$clutter" ]; then
  bad "仓库卫生：残留崩溃/回放/跟踪文件（$clutter）——见 .gitignore 与 docs/repo-map.md"
else
  ok "静态：根目录与 data/ 无崩溃/回放/跟踪残留（#36）"
fi
for f in scripts/*.sh; do
  if bash -n "$f"; then ok "shell 语法：$f"; else bad "shell 语法：$f"; fi
done

# ---------- 2. 测试与既有门闩 ----------
if [ "${SKIP_TESTS:-0}" = "1" ]; then
  warn "SKIP_TESTS=1：跳过 mvn/pytest"
else
  TMPLOG="$(mktemp)"
  if mvn -B -q test >"$TMPLOG" 2>&1; then ok "mvn -B test"; else bad "mvn -B test（见 $TMPLOG）"; tail -30 "$TMPLOG"; fi
  if (cd offline && "$PY" -m pytest tests -q >"$TMPLOG" 2>&1); then ok "pytest offline/tests"; else bad "pytest offline/tests（见 $TMPLOG）"; tail -30 "$TMPLOG"; fi
fi

if bash scripts/check_panel_contract.sh >/dev/null 2>&1; then ok "面板契约"; else bad "面板契约（bash scripts/check_panel_contract.sh）"; fi
if (cd offline && "$PY" provenance.py --check >/dev/null 2>&1); then ok "报告↔语料同代"; else bad "provenance --check"; fi
if (cd offline && "$PY" doc_numbers.py --check >/dev/null 2>&1); then ok "文档数字↔产物"; else bad "doc_numbers --check"; fi
if "$PY" scripts/check_public_copy.py --check >/dev/null 2>&1; then ok "对外用词门闩"; else bad "check_public_copy --check"; fi

# L1 审计链 WORM 完整性（盲点 6）：配了 WORM 目录就强制逐条对账；未配则显式 SKIP——
# 生产环境必须配置（审计链断层 = 事后无法回放），开发机没有 WORM 属正常。
if [ -n "${OPSPILOT_WORM_DIR:-}" ]; then
  if "$PY" scripts/l1_worm_gate.py --worm-dir "$OPSPILOT_WORM_DIR" >/dev/null 2>&1; then
    ok "L1 WORM 完整性"
  else
    bad "L1 WORM 完整性（scripts/l1_worm_gate.py --worm-dir $OPSPILOT_WORM_DIR）"
  fi
else
  warn "L1 WORM 完整性：未配置 OPSPILOT_WORM_DIR（生产必须配置）"
fi

# ---------- 3. 备份新鲜度（G0 允许"从未记录"仅告警；G1 full 必须红） ----------
"$PY" scripts/check_backup_age.py
rc=$?
if [ "$rc" -eq 0 ]; then ok "备份龄"
elif [ "$rc" -eq 2 ]; then
  if [ "$MODE" = "--full" ]; then bad "备份龄：从未记录成功备份"; else warn "备份龄：从未记录（G0 允许，上线前必须接计划任务）"; fi
else bad "备份龄：记录过期或校验失败"; fi

# ---------- 4. 活体面（--full） ----------
if [ "$MODE" = "--full" ]; then
  PORT="${SERVER_PORT:-8081}"
  BASE="http://127.0.0.1:$PORT"
  if curl -fsS --max-time 3 "$BASE/actuator/health" | grep -q '"status":"UP"'; then
    ok "活体：/actuator/health UP"
  else
    bad "活体：/actuator/health 非 UP（先看 logs/app.log 与中间件）"
  fi
  PROM="$(curl -fsS --max-time 3 "$BASE/actuator/prometheus" || true)"
  for series in aiops_requests_failed_total aiops_inflight aiops_backup_last_success_timestamp_seconds aiops_audit_write_failures_total aiops_ttft_seconds_count aiops_request_seconds_count aiops_requests_aborted_total aiops_admission_rejected_total aiops_cache_write_fail_total aiops_sop_miss_total; do
    if printf '%s' "$PROM" | grep -q "$series"; then
      ok "活体：$series 已暴露"
    else
      bad "活体：/actuator/prometheus 缺 $series（规则/门禁依赖它，缺了是静默失效）"
    fi
  done
  if curl -fsS --max-time 3 "$BASE/actuator/health/readiness" | grep -q '"status":"UP"'; then
    ok "活体：/actuator/health/readiness UP"
  else
    bad "活体：readiness 非 UP（看 db/redis/es/qdrant/backend 哪个 DOWN）"
  fi
  shopt -s nullglob
  set -- logs/audit*.jsonl
  shopt -u nullglob
  if [ "$#" -gt 0 ]; then
    AUDIT_OUT="$("$PY" scripts/verify_audit_chain.py "$@" --head logs/.audit-head 2>&1)"
    AUDIT_RC=$?
    if [ "$AUDIT_RC" -ne 0 ]; then
      bad "活体：审计哈希链断链/被改（跑 scripts/verify_audit_chain.py 看第一处）"
    elif printf '%s' "$AUDIT_OUT" | grep -q '^WARN'; then
      warn "活体：$AUDIT_OUT"
    else
      ok "活体：审计哈希链自洽（$# 个文件）"
    fi
  else
    warn "活体：logs/audit*.jsonl 不存在，跳过审计链（刚起服务/无请求）"
  fi
  # 全量测试并行时机器会很忙：面板是 80KB 单文件，3s 单次可能被 CPU 抢占误判。
  # 重试 3 次（仍限时 5s）区分"慢"与"端口被别的服务占了"。
  PANEL_OK=0
  for _ in 1 2 3; do
    if curl -fsS --max-time 5 "$BASE/" | grep -q "OpsShield"; then PANEL_OK=1; break; fi
    sleep 1
  done
  if [ "$PANEL_OK" -eq 1 ]; then
    ok "活体：面板身份锚点"
  else
    bad "活体：/ 不是 OpsShield 面板（端口被别的服务占了？）"
  fi
fi

# ---------- 汇总 ----------
echo "----------------------------------------"
echo "production_gate $MODE：PASS=$PASS WARN=$WARN FAIL=$FAIL"
if [ "$FAIL" -ne 0 ]; then
  echo "结论：FAIL——先修上面点名的项，不要看总览。"
  exit 1
fi
echo "结论：PASS（WARN 项按 M1 门禁继续收敛）"
exit 0
