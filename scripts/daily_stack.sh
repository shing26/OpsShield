#!/usr/bin/env bash
# 门禁 6/7 观察期的每日维持（确定性，不依赖任何 LLM/会话模型）。
#
# 为什么不用应用内定时任务：2026-11-07 起每日任务连续失败——它绑定的模型 id 被下线后，
# 任务在"选模型"这一步就死了，而失败是静默的（每天只留一条 failed run），门禁 7 的时钟
# 停了 9 天才被发现。本脚本把同一件事做成确定性流程：成与不成只看退出码与日志。
#
# 职责（幂等，已在跑的不重启）：
#   1. Docker Desktop 不可用时尝试拉起并等待；
#   2. compose 起 redis/qdrant/elasticsearch/prometheus；
#   3. 网关未就绪则 start_gateway.sh；
#   4. 校验 readiness / up{job="opspilot"}=1 / 保留期 30d；
#   5. 周报：最近一份 slo-weekly 距今 ≥7 天则生成，产物（仅两文件）提交并推送 main。
#
# 退出码：0 全部就绪；10 Docker 不可用；20 compose 失败；30 网关未就绪；
#         40 Prometheus 抓取/保留期不符；50 周报生成或推送失败。
# 日志：logs/daily-stack.log（gitignored）。
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO" || exit 10
LOG_DIR="$REPO/logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/daily-stack.log"

log() { printf '%s %s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" "$*" | tee -a "$LOG"; }
die() { log "FAIL($1) $2"; exit "$1"; }

# python 单点：优先 Anaconda（计划任务环境 PATH 不含它）
PY="python"
if [ -x "E:/anaconda/Anaconda3/python.exe" ]; then PY="E:/anaconda/Anaconda3/python.exe"; fi

# ── 1. Docker ────────────────────────────────────────────────────────────
if ! docker info >/dev/null 2>&1; then
  log "Docker daemon 不可用，尝试启动 Docker Desktop"
  [ -x "D:/Docker/Docker Desktop.exe" ] && ( "D:/Docker/Docker Desktop.exe" >/dev/null 2>&1 & ) || true
  for i in $(seq 1 48); do
    sleep 5
    if docker info >/dev/null 2>&1; then log "Docker daemon 就绪（等待 $((i*5))s）"; break; fi
  done
  docker info >/dev/null 2>&1 || die 10 "Docker Desktop 240s 内未就绪"
fi

# ── 2. 中间件 ────────────────────────────────────────────────────────────
if ! docker compose up -d redis qdrant elasticsearch prometheus >>"$LOG" 2>&1; then
  die 20 "docker compose up 失败（见 $LOG）"
fi
log "compose: $(docker ps --filter name=opspilot- --format '{{.Names}}={{.State}}' | tr '\n' ' ')"

# ── 3. 网关 ──────────────────────────────────────────────────────────────
if [ "$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/actuator/health)" != "200" ]; then
  log "网关未就绪，启动 jar"
  if ! bash scripts/start_gateway.sh >>"$LOG" 2>&1; then
    die 30 "start_gateway.sh 失败（见 $LOG）"
  fi
fi

# ── 4. 校验 ──────────────────────────────────────────────────────────────
READY="$(curl -s -m 5 http://127.0.0.1:8081/actuator/health/readiness || true)"
case "$READY" in *'"UP"'*) log "readiness UP" ;; *) die 30 "readiness 非 UP：$READY" ;; esac

# 抓取间隔 15s：刚就绪时目标还没被采过一次，须重试（2026-11-16 实测首次校验即撞上）
UP="none"
for i in $(seq 1 12); do
  UP="$(curl -s -m 5 --get --data-urlencode 'query=up{job="opspilot"}' \
        http://127.0.0.1:9090/api/v1/query | "$PY" -c 'import json,sys;r=json.load(sys.stdin)["data"]["result"];print(r[0]["value"][1] if r else "none")' 2>/dev/null)"
  [ "$UP" = "1" ] && break
  sleep 5
done
[ "$UP" = "1" ] || die 40 "抓取目标 up=$UP（60s 内未变 1）"

RET="$(curl -s -m 5 http://127.0.0.1:9090/api/v1/status/flags | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["data"].get("storage.tsdb.retention.time","?"))' 2>/dev/null)"
[ "$RET" = "30d" ] || die 40 "保留期=$RET（须为 30d）"
log "抓取 up=1，保留期=$RET"

# ── 5. 周报 ──────────────────────────────────────────────────────────────
newest="$(ls -1 docs/ops/slo-weekly-*.json 2>/dev/null | sort | tail -1)"
age_days=999
if [ -n "${newest:-}" ]; then
  newest_date="$(basename "$newest" .json | sed 's/^slo-weekly-//')"
  age_days=$(( ( $(date -u +%s) - $(date -u -d "$newest_date" +%s) ) / 86400 ))
fi
if [ "$age_days" -ge 7 ]; then
  log "周报最新一份距今 ${age_days} 天，生成新周报"
  if ! "$PY" offline/slo_report.py --kind weekly >>"$LOG" 2>&1; then
    die 50 "slo_report.py 失败（见 $LOG）"
  fi
  report_json="$(ls -1t docs/ops/slo-weekly-*.json | head -1)"
  report_md="${report_json%.json}.md"
  if [ "$(git branch --show-current)" = "main" ]; then
    git add "$report_json" "$report_md" >>"$LOG" 2>&1
    if git commit -m "chore(slo): 周报 $(basename "$report_json" .json | sed 's/^slo-weekly-//')——门禁 7 覆盖固化" >>"$LOG" 2>&1; then
      git push origin main >>"$LOG" 2>&1 || { sleep 30; git push origin main >>"$LOG" 2>&1 || die 50 "周报推送失败"; }
      log "周报已提交并推送：$report_json"
    else
      log "周报无变化（commit 为空），跳过推送"
    fi
  else
    log "当前不在 main（$(git branch --show-current)），周报只落盘不提交"
  fi
else
  log "周报最新一份距今 ${age_days} 天，未到周更，跳过"
fi

log "完成：栈保持运行（门禁 7 当天覆盖依赖它继续跑）"
exit 0
