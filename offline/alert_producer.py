#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""自举告警生产者（ADR-0011）：让 OpsPilot 成为自己的告警源。

为什么存在：语料、query、评测集全部由本项目自己生成——系统至今没接触过任何它无法预测的
输入。本脚本轮询网关**已有的真相面**，命中异常就把观测到的事实当作一条真实告警 POST 回
自己的 /chat/stream（source=alert）。事件是真的（真触发、真 root cause、可对提交与台账核验），
只是来源恰好是系统自身的运行史。

它只读既有观测面、只调既有端点：不加后端接口、不新增指标、不改架构。

已知盲区（不假装覆盖）：网关自身挂掉时 /api/v1/admin/state 不可达，watcher 只能记一行
unreachable——"进程内自证自己已死"不可能，这是自举路径的固有边界，交付台账里如实登记。

用法:
  python alert_producer.py --once                      # 单轮探测后退出（验收友好）
  python alert_producer.py                             # 常驻，默认 15s 一轮
  python alert_producer.py --dry-run --once            # 只判定与留痕，不发任何告警请求
  python alert_producer.py --interval 5 --cooldown 60 --max-per-hour 10
  python alert_producer.py --user sre-watcher          # 告警主体（默认 sre-watcher）

退出码: 0 正常（含"无告警"与 dry-run）/ 2 主体权限不足（403，重试无意义）
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import localapi  # noqa: E402  （单一事实源：登录/取数/SSRF 白名单/SSE 原语）
from alert_delivery import (  # noqa: E402
    AlertDelivery,
    AlertSession,
    DeliveryAttempt,
    DeliveryPolicy,
    Gate,
    HourAccounting,
    LoginFailed,
    LoginFailureMode,
    PRODUCER_GATES,
    QuotaSnapshot,
    TransportFailed,
    TransportFailureHandling,
)

REPO = Path(__file__).resolve().parent.parent
LEDGER = REPO / "logs" / "alert-producer.jsonl"
LOCK = REPO / "logs" / ".alert-producer.lock"
ENV = "local"
# 配额保留线：告警不得把自己烧穿——防的是极端风暴下"告警自己耗尽配额，
# 使随后的人工排障被 429"。注意配额按 sub 计数，而本进程有独立主体 sre-watcher，
# 所以这条线保护的是**告警主体自己的余量**，不是别人的配额（ADR-0011 已按此口径修正）。
QUOTA_RESERVE = 50
# 登录面连续失败上限：口令错/主体被锁时重试只会把 15 分钟锁定刷成常锁（AuthService 5 次失败锁 15 分钟），
# 且每次失败都写一行鉴权审计——故失败即退避，超过上限直接退出并报告，等人修凭据。
MAX_LOGIN_FAILURES = 3
# 组件 detail 进 prompt 前的白名单：这是全链路唯一"非人工撰写"的输入面，
# 虽来自本机中间件异常，仍按不可信处理（去控制字符、限长、只留安全字符集）。
# 刻意不含 < > ` $ 与引号：错误文本不需要它们，而它们正是拼接/注入类写法的原料。
DETAIL_SAFE = re.compile(r"[^\w\s:.,;/()\[\]=@+\-]")
DETAIL_MAX = 120

# 规则 → 告警文案。文案刻意带错误码：真实告警本就携带错误码，且精确符号走快路径
# （跳过 rerank 压 TTFT）；两条复用既有语料（51001/51005），两条指向 2xx 自举语料，
# 一条纯语义兜底——这样"闭环"不依赖新写的文档也能被验证。
QUERIES = {
    "dep_down": ("告警：依赖组件 {comp} 健康检查异常（status={status}，detail={detail}）。"
                 "混合检索/缓存的对应腿已不可用，请给出排查步骤与止损操作 51208_DEPENDENCY_DOWN"),
    "degrade": ("告警：网关降级档位={level}（熔断失败计数 {failures}/{threshold}，冷却剩余 {cooldown}s，"
                "在途 {inflight}），LLM 链路可能已熔断改直出 SOP，"
                "请给出止损与恢复确认步骤 51201_CIRCUIT_BREAKER_NO_SELFHEAL"),
    "upstream_limited": ("告警：上游模型服务异常（llm_rate_limited 增量 {rate_limited}，"
                         "llm_network_errors 增量 {net_errors}），流式链路可能整体失败，"
                         "请给出分型诊断与止损操作 51001_MODEL_ARREARAGE"),
    "retrieval_degraded": ("告警：检索路劣化（retrieval_timeouts 增量 {timeouts}，"
                           "es_only_requests 增量 {es_only}），向量腿疑似持续超时、双路静默退化为单路，"
                           "请给出排查与止损步骤 51005_LEG_TIMEOUT_SILENT_DEGRADE"),
    "refusal_spike": ("告警：拒答/降级直出激增（low_confidence_refusals 增量 {refusals}，"
                      "sop_fallbacks 增量 {sop}），召回质量或上游与配置可能不一致，请给出排查方向"),
}

METRIC_KEYS = ("llm_rate_limited", "llm_network_errors", "retrieval_timeouts",
               "es_only_requests", "low_confidence_refusals", "sop_fallbacks")


def _num(v) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return 0


def metrics_of(state: dict) -> dict:
    return {k: _num((state.get("metrics") or {}).get(k)) for k in METRIC_KEYS}


def deltas(cur: dict, prev: dict | None) -> tuple[dict, bool]:
    """计数器增量。返回 (增量, 是否复位)。

    计数器是进程内 AtomicLong，重启即归零——负增量必须当复位处理（记 0 并留痕），
    否则会凭空造出"负告警"，或把重启掩盖成一次静默。
    """
    if prev is None:
        return {k: 0 for k in METRIC_KEYS}, False
    reset = any(cur[k] < prev.get(k, 0) for k in METRIC_KEYS)
    return ({k: (0 if reset else max(0, cur[k] - prev.get(k, 0))) for k in METRIC_KEYS}, reset)


def _safe_detail(v) -> str:
    """把组件 detail 洗成可安全进 prompt/审计的短文本（限长 + 字符白名单）。"""
    s = DETAIL_SAFE.sub(" ", str(v or ""))
    return " ".join(s.split())[:DETAIL_MAX]


def detect(state: dict, d: dict) -> list[dict]:
    """真相面 → 候选告警。只做判定；冷却/预算/配额等发送闸门在 Producer 里。"""
    out: list[dict] = []

    for comp in ("redis", "qdrant", "es"):
        c = (state.get("health") or {}).get(comp) or {}
        if str(c.get("status", "UP")).upper() != "UP":
            out.append({"rule": "dep_down", "service": comp,
                        "query": QUERIES["dep_down"].format(comp=comp, status=_safe_detail(c.get("status")),
                                                            detail=_safe_detail(c.get("detail")))})

    rt = state.get("runtime") or {}
    deg = rt.get("degradation") or {}
    level = str(deg.get("level") or "L0")
    if level in ("L1", "L2") or _num(deg.get("cooldown_s")) > 0:
        out.append({"rule": "degrade", "service": "opspilot-gateway",
                    "query": QUERIES["degrade"].format(level=_safe_detail(level), failures=_num(deg.get("failures")),
                                                       threshold=_num(deg.get("llm_failure_threshold")),
                                                       cooldown=_num(deg.get("cooldown_s")),
                                                       inflight=_num(rt.get("inflight")))})

    if d["llm_rate_limited"] or d["llm_network_errors"]:
        out.append({"rule": "upstream_limited", "service": "dashscope-api",
                    "query": QUERIES["upstream_limited"].format(rate_limited=d["llm_rate_limited"],
                                                                net_errors=d["llm_network_errors"])})

    if d["retrieval_timeouts"] and d["es_only_requests"]:
        out.append({"rule": "retrieval_degraded", "service": "opspilot-gateway",
                    "query": QUERIES["retrieval_degraded"].format(timeouts=d["retrieval_timeouts"],
                                                                  es_only=d["es_only_requests"])})

    if d["low_confidence_refusals"] or d["sop_fallbacks"]:
        out.append({"rule": "refusal_spike", "service": "opspilot-gateway",
                    "query": QUERIES["refusal_spike"].format(refusals=d["low_confidence_refusals"],
                                                             sop=d["sop_fallbacks"])})
    return out


class Producer:
    """一轮 = 读一次真相面 → 判定 → 过闸门 → 发告警 → 落台账。单轮失败绝不让进程死。"""

    def __init__(self, args):
        self.user = args.user
        self.dry_run = args.dry_run
        self.cooldown = args.cooldown
        self.max_per_hour = args.max_per_hour
        self.interval = getattr(args, "interval", 15)
        # 可调是为了可测：把保留线设 0 才能构造"配额真被打满 → 429"的活体用例，
        # 否则保留线总是先一步挡住，429 分支永远测不到。
        self.quota_reserve = getattr(args, "quota_reserve", QUOTA_RESERVE)
        self.collect_deltas = getattr(args, "collect_deltas", False)
        self.login_failures = 0
        self.prev: dict | None = None
        self.stats = {"cycles": 0, "candidates": 0, "emitted": 0, "skipped": 0, "errors": 0}
        self.delivery = AlertDelivery(
            AlertSession(
                self.user,
                api=localapi,
                login_failure_mode=LoginFailureMode.ANY_HTTP_ERROR,
            ),
            ledger_path=LEDGER,
        )

    # ---- 与网关交互 -------------------------------------------------------
    @property
    def token(self) -> str:
        return self.delivery.session.token

    @token.setter
    def token(self, value: str) -> None:
        self.delivery.session.token = value

    def login(self) -> None:
        """登录失败归类为 LoginFailed（凭据/锁定，需人介入），其余异常才是瞬时不可达。"""
        self.delivery.session.login()

    def fetch_state(self) -> dict:
        """读管理面；401 重登一次（24h token 过期是常态），403 直接判主体配置错误。"""
        return self.delivery.observe_state()

    @staticmethod
    def _key(alert: dict) -> str:
        """冷却与预算的键 = 规则 + 组件。

        按规则单键会在"一个规则覆盖多个组件"时出错：dep_down 覆盖 redis/es/qdrant，
        三个同时不可用时只有第一个能发出，其余被冷却静默吞掉——真实的 redis+es 同时中断
        就是这么暴露的。冷却的语义是"别再重复报同一件事"，而"哪个组件"是这件事的一部分。
        """
        return f"{alert.get('rule')}:{alert.get('service', '')}"

    def _deliver(self, alert: dict, state: dict | None, *, gates=PRODUCER_GATES,
                 now: float | None = None) -> dict:
        q = ((state or {}).get("runtime") or {}).get("quota") or {}
        quota = QuotaSnapshot(limit=_num(q.get("limit")), used=_num(q.get("used")))
        policy = DeliveryPolicy(
            gates=gates,
            max_per_hour=self.max_per_hour,
            quota_reserve=self.quota_reserve,
            hour_accounting=HourAccounting.RESPONSE,
            login_before_send=False,
            transport_failure_handling=TransportFailureHandling.RAISE,
            response_code_key="error_code",
            dry_run=self.dry_run,
            quota=quota,
            cooldown_key=self._key(alert),
            cooldown_seconds=self.cooldown,
        )
        old_clock = self.delivery.clock
        if now is not None:
            self.delivery.clock = lambda: now
        try:
            outcome = self.delivery.deliver(
                DeliveryAttempt(
                    body={"query": alert["query"], "source": "alert",
                          "service": alert["service"], "env": ENV},
                    ledger={"rule": alert["rule"], "service": alert["service"],
                            "query": alert["query"]},
                    collect_deltas=self.collect_deltas,
                ),
                policy,
            )
        finally:
            self.delivery.clock = old_clock
        if (
            isinstance(outcome, TransportFailed)
            and outcome.handling is TransportFailureHandling.RAISE
        ):
            if outcome.exception is not None:
                raise outcome.exception
            raise RuntimeError(outcome.error)
        return outcome.record

    def emit(self, alert: dict) -> dict:
        """Send one candidate without the Producer gate plan (test/internal primitive)."""
        return self._deliver(alert, None, gates=(Gate.DRY_RUN,))

    def ledger(self, rec: dict) -> None:
        self.delivery.ledger.append(rec)

    # ---- 发送闸门 ---------------------------------------------------------
    def gate(self, alert: dict, state: dict, now: float) -> dict:
        """闸门顺序即优先级：预算 > 配额 > 冷却。每个 skip 都留痕，便于事后问"为什么没发"。"""
        return self._deliver(alert, state, now=now)

    # ---- 主循环 -----------------------------------------------------------
    def cycle(self) -> list[dict]:
        state = self.fetch_state()
        self.stats["cycles"] += 1
        cur = metrics_of(state)
        d, reset = deltas(cur, self.prev)
        self.prev = cur
        if reset:
            self.ledger({"rule": "-", "emit": False, "skip": "counter_reset",
                         "note": "计数器归零（服务重启）——本轮增量按 0 处理，不据复位造告警"})

        now = time.time()
        written = []
        for alert in detect(state, d):
            self.stats["candidates"] += 1
            rec = self.gate(alert, state, now)
            if rec.get("emit"):
                self.stats["emitted"] += 1
                print(f"[alert] {alert['rule']} service={alert['service']} http={rec.get('http')} "
                      f"fp={(rec.get('fp') or '')[:12]} cache={rec.get('cache_hit')} refs={rec.get('refs')}")
            else:
                self.stats["skipped"] += 1
            written.append(rec)
        return written

    def run(self, interval: int) -> int:
        if self.dry_run:
            print(f"[dry-run] 主体={self.user}：只判定与留痕，不发告警请求")
        while True:
            try:
                # 登录放进循环：常驻进程很可能比网关先起（cron / 开机自启），
                # 初始登录失败必须是"这一轮失败"而不是进程猝死——否则一次重启窗口
                # 就能让告警源永久失效，而那正是最需要它的时刻。
                if not self.token:
                    self.login()
                self.login_failures = 0
                self._touch_lock()
                self.cycle()
            except PermissionError as e:
                print(f"[fatal] {e}", file=sys.stderr)
                return 2
            except LoginFailed as e:
                # 凭据错或主体被锁：重试有害（AuthService 5 次失败锁 15 分钟，重试即常锁），
                # 且每次失败都写一行鉴权审计 → 退避 + 上限后退出，等人修凭据。
                self.stats["errors"] += 1
                self.token = ""
                self.login_failures += 1
                self.ledger({"rule": "-", "emit": False, "skip": "login_failed",
                             "note": f"{e}（连续 {self.login_failures} 次）"})
                if self.login_failures >= MAX_LOGIN_FAILURES:
                    print(f"[fatal] 连续 {self.login_failures} 次登录失败（{e}）："
                          f"检查 {self.user} 的凭据/是否被锁，修好后重启——不再空转重试",
                          file=sys.stderr)
                    return 2
                backoff = min(300, max(interval, 5) * (2 ** self.login_failures))
                print(f"[warn] 登录失败（{e}），退避 {backoff}s", file=sys.stderr)
                time.sleep(backoff)
                continue
            except Exception as e:                       # 网关不可达等瞬时错：留痕后继续
                self.stats["errors"] += 1
                self.token = ""                          # 凭证/网络态未知 → 下轮重新登录
                self.ledger({"rule": "-", "emit": False, "skip": "unreachable",
                             "note": f"{type(e).__name__}: {e}"})
                print(f"[warn] 本轮失败（继续）: {type(e).__name__}: {e}", file=sys.stderr)
            if interval <= 0:
                break
            time.sleep(interval)
        return 0

    # ---- 单实例守卫 -------------------------------------------------------
    def _touch_lock(self) -> None:
        """刷新锁文件 mtime：存活实例每轮触碰，僵死实例的锁会自然过期。"""
        try:
            LOCK.write_text(str(os.getpid()), encoding="utf-8")
        except OSError:
            pass


def _acquire_lock(interval: int, force: bool) -> bool:
    """单实例守门：冷却/小时预算/计数器基线都是**进程内**状态，跑两个实例 = 双倍告警速率，
    且两个进程交错写同一 JSONL。锁文件按 mtime 判活（存活实例每轮触碰）：
    僵死实例留下的锁在 max(120, interval*4) 秒后视为过期、可被接管。
    锁不可用（无写权限等）时不阻断告警——可用性优先于互斥，风险已知且已写入文档。
    """
    try:
        if LOCK.exists() and not force:
            age = time.time() - LOCK.stat().st_mtime
            if age < max(120, interval * 4):
                return False
        LOCK.parent.mkdir(parents=True, exist_ok=True)
        LOCK.write_text(str(os.getpid()), encoding="utf-8")
        return True
    except OSError:
        return True


def _release_lock() -> None:
    try:
        LOCK.unlink()
    except OSError:
        pass


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="OpsPilot 自举告警生产者（ADR-0011）")
    ap.add_argument("--once", action="store_true", help="只跑一轮后退出（等价 --interval 0）")
    ap.add_argument("--interval", type=int, default=15, help="轮询间隔秒（默认 15；健康面本身 5s TTL）")
    ap.add_argument("--cooldown", type=int, default=300, help="同一规则两次发送的最小间隔秒（默认 300）")
    ap.add_argument("--max-per-hour", type=int, default=20, help="每小时告警上限（默认 20，防风暴自伤）")
    ap.add_argument("--quota-reserve", type=int, default=QUOTA_RESERVE,
                    help=f"本主体配额保留线（默认 {QUOTA_RESERVE}）：剩余低于此值则不发告警")
    ap.add_argument("--user", default="sre-watcher", help="告警主体（默认 sre-watcher）")
    ap.add_argument("--dry-run", action="store_true", help="只判定与留痕，不发告警请求")
    ap.add_argument("--collect-deltas", action="store_true",
                    help="在返回值里收集完整答案增量（默认关：仅用 meta/done，长答案下省内存）")
    ap.add_argument("--force", action="store_true", help="无视既有锁文件强行启动（单实例约束见 --help 说明）")
    a = ap.parse_args(argv)
    p = Producer(a)
    if not _acquire_lock(a.interval, a.force):
        print("[fatal] 已有一个告警生产者实例在运行（冷却/小时预算为进程内状态，多实例会成倍告警）——"
              "确认无实例后可加 --force 接管", file=sys.stderr)
        return 3
    try:
        rc = p.run(0 if a.once else a.interval)
    finally:
        _release_lock()
    s = p.stats
    print(f"[summary] cycles={s['cycles']} candidates={s['candidates']} emitted={s['emitted']} "
          f"skipped={s['skipped']} errors={s['errors']}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
