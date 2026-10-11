#!/usr/bin/env bash
# 自举闭环演示驱动（docs/DEMO.md 幕⑧ / ADR-0011）——一次跑完"系统自己发现故障"的完整链路。
#
# 为什么要有这个脚本：这一幕的价值在**链路是真的**（真停一个依赖、真自检、真告警、
# 真检索到自己写的复盘、真恢复），而链路本身跨 8 个动作、含两次等待，手敲必然散。
# 固化成脚本 = 录屏可重来、现场可复现、观众可照抄。
#
# 它做的事：
#   ① 基线：健康态探测 → 期望零候选
#   ② 制造真实故障：停掉 qdrant 中间件
#   ③ 等网关侧健康面反映（探活 5s TTL + 3s 超时；实测 12-15s 内可见）
#   ④ 单轮探测 → 告警发出（source=alert）；打印台账行与审计行（来源可辨）
#   ⑤ 收敛：连发同故障告警 → 指纹一致、L1 命中、LLM 增量 0
#   ⑥ 命中自举语料：直查 51208 → Top-1 = rb-208（含"止损操作"小节）
#   ⑦ 恢复：起回 qdrant，倒计时等健康面自愈（实测 1 分钟量级）→ 再探测零候选
#
# 前置：网关在跑（宿主或容器）、docker 可用、.env 可 source。
# 用法：bash scripts/demo_self_alert.sh [--quick]    # --quick 只演到告警+收敛，跳过恢复等待
set -euo pipefail
cd "$(dirname "$0")/.."

QUICK=0
[ "${1:-}" = "--quick" ] && QUICK=1

[ -f .env ] || { echo "缺 .env（演示需要 DEMO_PASSWORD 等环境变量）"; exit 1; }
set -a; . ./.env; set +a

PY=$(bash scripts/py.sh || true)
[ -n "$PY" ] || { echo "未找到 Python 解释器（scripts/py.sh 三档探测全空）"; exit 1; }

shot()  { printf '\n\033[1;36m=== %s ===\033[0m\n' "$*"; }
say()   { printf '\033[0;37m%s\033[0m\n' "$*"; }
count() { for i in $(seq "$1" -1 1); do printf '\r  等待自愈… %2ss ' "$i"; sleep 1; done; printf '\r\033[K'; }

api()   { "$PY" - "$@" <<'PYEOF'
import sys
sys.path.insert(0, "offline")
import localapi
tok = localapi.login("sre-watcher")
cmd = sys.argv[1]
if cmd == "once":                      # 单轮探测：--once 打印 summary
    pass
elif cmd == "state":
    s = localapi.get_json("/api/v1/admin/state", tok)
    print("health=%s degradation=%s quota_used=%s llm_calls=%s" % (
        s["health"]["status"], s["runtime"]["degradation"]["level"],
        s["runtime"]["quota"]["used"], s["metrics"]["llm_calls"]))
elif cmd == "search":
    r = localapi.search_docs(sys.argv[2], "hybrid", tok)
    print("Top-3: %s" % [h["doc_id"] for h in r[:3]])
elif cmd == "audit":                   # 最近一条"本次告警主体发出"的来源行
    # 必须同时按 sub 过滤：审计是全局的，A2 风暴用例也用 source=alert（主体 sre-full），
    # 只按 source 取最近一条会抓到别的演示留下的行——指纹对不上，现场即翻车。
    e = localapi.get_json("/api/v1/admin/audit/recent?since=0&limit=200", tok)
    rows = [x for x in e["events"]
            if x.get("source") == "alert" and x.get("sub") == "sre-watcher"]
    if rows:
        r = rows[-1]
        print("audit: via=%s source=%s sub=%s fp=%s mode=%s took_ms=%s" % (
            r.get("via"), r.get("source"), r.get("sub"), (r.get("fp") or "")[:12],
            r.get("mode"), r.get("took_ms")))
    else:
        print("audit: （环形缓冲内暂未见 sre-watcher 的 alert 行）")
PYEOF
}

probe() { "$PY" offline/alert_producer.py --once --cooldown 0 "$@"; }

say "本演示会真实停掉 qdrant 中间件并在最后起回；全程无人工构造 query。"
sleep 3

shot "① 基线：健康态探测（期望：零候选，无告警可发）"
probe

shot "② 制造真实故障：停掉 qdrant 中间件"
docker compose stop qdrant >/dev/null
say "   qdrant 已停——现在系统面对的是真实的依赖不可用，不是模拟参数。"

shot "③ 等网关侧健康面反映（探活缓存 5s + 探测超时 3s）"
sleep 15
api state

shot "④ 自检 → 自报：告警生产者读运行态并把它当告警发回自身链路"
probe
say "   台账最后一条（生产者侧决策链）："
tail -1 logs/alert-producer.jsonl | "$PY" -c "
import sys, json
e = json.loads(sys.stdin.read())
print('   rule=%s service=%s emit=%s sent_ok=%s http=%s' % (
    e['rule'], e['service'], e['emit'], e.get('sent_ok'), e.get('http')))
print('   fp=%s cache_hit=%s' % (e.get('fp'), e.get('cache_hit')))
print('   引用命中的文档：%s' % e.get('ref_docs'))"
api audit

shot "⑤ 收敛：同一故障再发 2 次（期望：指纹一致、走 L1、LLM 不再被调用）"
BEFORE=$("$PY" -c "
import sys; sys.path.insert(0,'offline'); import localapi
tok=localapi.login('sre-watcher'); print(localapi.get_json('/api/v1/admin/state',tok)['metrics']['llm_calls'])")
probe >/dev/null; probe >/dev/null
AFTER=$("$PY" -c "
import sys; sys.path.insert(0,'offline'); import localapi
tok=localapi.login('sre-watcher'); print(localapi.get_json('/api/v1/admin/state',tok)['metrics']['llm_calls'])")
say "   llm_calls: $BEFORE → $AFTER（增量 $((AFTER-BEFORE))）"
tail -2 logs/alert-producer.jsonl | "$PY" -c "
import sys, json
for line in sys.stdin:
    e = json.loads(line)
    print('   fp=%s cache_hit=%s' % (e.get('fp'), e.get('cache_hit')))"

shot "⑥ 命中自举语料：告警文案里的错误码直查知识库"
api search 51208_DEPENDENCY_DOWN
say "   ↑ rb-208 就是这套系统在自己的事故复盘里写下的处置单（含「止损操作」小节）。"

if [ "$QUICK" = "1" ]; then
  shot "（--quick：跳过恢复等待）"
  docker compose start qdrant >/dev/null
  say "   qdrant 已起回；完整模式会等到健康面自愈并验证停止上报。"
  exit 0
fi

shot "⑦ 恢复：起回 qdrant，等网关侧健康面自愈"
docker compose start qdrant >/dev/null
say "   网关侧 gRPC 通道重建需要 1 分钟量级（外部 REST 早已健康，网关侧观测滞后——这条实测写进了 rb-208）。"
count 80
api state

shot "⑧ 恢复即停报：再无候选告警"
probe
say "   闭环完成：自检 → 自报 → 收敛 → 命中自举语料 → 恢复停报，全程零人工构造 query。"
