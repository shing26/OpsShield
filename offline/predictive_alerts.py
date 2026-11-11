#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统计基线预警（ADR-0030）：只消费既有时序，不自建采集。

为什么存在：事中链路（告警接入 → 削峰 → 检索 → 建议）已经闭环，但**事前**是空白——
系统只能在静态阈值被击穿之后才知道出事。本模块补的是更早的那一层：读 Prometheus 里
**已经存在的**时序，用可解释的统计基线判断"这阵子不太对"，然后把判断当作一条告警打进
**既有的**编排链。

三条硬边界（契约 AC-1/AC-2/AC-5）：

1. **不自建采集**：不新增 scrape 配置、不建指标库、不做拓扑。只 GET Prometheus HTTP API。
   在别的部署里 Prometheus 抓什么，这里就能读什么。
2. **可解释基线**：中位数 + 绝对中位差（MAD）的 modified z-score。每条预警正文自带
   当前值、基线值、MAD、偏离度、样本数、窗口与方法名——读者可以手算复核，不需要相信黑盒。
3. **走既有编排链**：命中后以 ``source=alert`` POST 到既有的 ``/api/v1/copilot/chat/stream``，
   不新增后端端点、不新增指标、不改权限模型；冷却 / 每小时上限 / 配额保留线 / dry-run
   四道闸门复用 ``alert_delivery``（ADR-0024）。

刻意的"不判定"（fail-closed，全部留痕）：
- Prometheus 不可达 → 不告警，退出码 2；
- 某条规则查不到序列 → 该条跳过，不影响其它规则；
- 基线样本不足 → 跳过（``insufficient_samples``）；
- ``MAD = 0``（序列恒定/近恒定，z 无定义）→ 走"仅绝对差"分支并标
  ``method="degenerate_mad_zero"``。**绝不除零、绝不静默放过**。

用法::

    python offline/predictive_alerts.py --once --dry-run          # 判定一次、只留痕
    python offline/predictive_alerts.py --once                     # 判定一次、命中即发
    python offline/predictive_alerts.py                            # 常驻，默认 60s 一轮
    python offline/predictive_alerts.py --prometheus http://127.0.0.1:9090
    python offline/predictive_alerts.py --rules my-rules.json --once --dry-run

退出码：0 正常（含"无预警"与 dry-run）/ 2 依赖不可达（Prometheus 或配额面）/ 3 配置错误。
"""
from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import sys
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import localapi  # noqa: E402  （单一事实源：出口白名单 / 登录 / SSE 原语）
from alert_delivery import (  # noqa: E402
    AlertDelivery,
    AlertSession,
    DeliveryAttempt,
    DeliveryPolicy,
    Gate,
    HourAccounting,
    LoginFailed,
    LoginFailureMode,
    QuotaSnapshot,
    TransportFailed,
    TransportFailureHandling,
)

REPO = Path(__file__).resolve().parent.parent
LEDGER = REPO / "logs" / "predictive-alerts.jsonl"
LOCK = REPO / "logs" / ".predictive-alerts.lock"
DEFAULT_PROM = os.environ.get("OPSPILOT_PROM", "http://127.0.0.1:9090")
ENV = "local"
# 连续登录失败上限：口令错/主体被锁时重试只会把 15 分钟锁定刷成常锁（与 alert_producer 同口径）。
MAX_LOGIN_FAILURES = 3

# Iglewicz–Hoaglin 的 modified z-score 常数：让基于 MAD 的偏离度与正态下的标准差同量纲。
MODIFIED_Z_CONST = 0.6745

# 闸门顺序是刻意排的：dry-run 最先（只判定不发），冷却最后（语义最具体）。
PREDICTIVE_GATES = (
    Gate.DRY_RUN,
    Gate.HOUR_BUDGET,
    Gate.SNAPSHOT_QUOTA,
    Gate.COOLDOWN,
)


@dataclass(frozen=True)
class Rule:
    """一条预警规则。``expr`` 必须聚合到单条序列（多序列会被拒，见 PromRangeClient）。"""

    id: str
    expr: str
    direction: str  # above | below | both
    min_abs_delta: float
    service: str
    metric_cn: str


# 默认规则集（契约冻结）：尾延迟 / 比率 / 容量 / 兜底四种形态各取代表。
# 计数器一律在 PromQL 里先 rate()，判定拿到的已是每秒速率，不在 Python 侧再除窗口。
DEFAULT_RULES = (
    Rule(
        id="ttft-p99",
        expr="histogram_quantile(0.99, sum(rate(aiops_ttft_seconds_bucket[5m])) by (le))",
        direction="above",
        min_abs_delta=0.5,
        service="opspilot-gateway",
        metric_cn="首字延迟 P99",
    ),
    Rule(
        id="request-p99-success",
        expr=('histogram_quantile(0.99, '
              'sum(rate(aiops_request_seconds_bucket{outcome="success"}[5m])) by (le))'),
        direction="above",
        min_abs_delta=0.5,
        service="opspilot-gateway",
        metric_cn="成功请求端到端 P99",
    ),
    Rule(
        id="cache-hit-rate",
        expr="aiops_cache_hit_rate",
        direction="below",
        min_abs_delta=0.10,
        service="opspilot-gateway",
        metric_cn="缓存命中率",
    ),
    Rule(
        id="inflight",
        expr="aiops_inflight",
        direction="above",
        min_abs_delta=2.0,
        service="opspilot-gateway",
        metric_cn="在途请求数",
    ),
    Rule(
        id="sop-fallbacks-rate",
        expr="sum(rate(aiops_sop_fallbacks_total[5m]))",
        direction="above",
        min_abs_delta=0.01,
        service="opspilot-gateway",
        metric_cn="静态兜底直出速率",
    ),
)

ALERT_TEMPLATE = (
    "预测预警：{rule} 当前 {current:.3f}，基线中位数 {median:.3f}，MAD {mad:.3f}，"
    "偏离 {deviation} / 绝对差 {delta:.3f}（样本 {n}，窗口 {lookback}，方法 {method}）。"
    "{metric_cn}出现统计异常，请给出可能原因与排查步骤。"
)


class PromRangeError(RuntimeError):
    """Prometheus 不可达 / 返回错误 / 结果形状不对（一律是"拿不到证据"，不是 0）。

    ``reason`` 直接进台账的 ``skip_reason``，让"为什么没报"可被机械追问：
    不可达/错误响应 → ``prometheus_unreachable``；结果形状不合规 → ``rule_shape``。
    """

    def __init__(self, message: str, *, reason: str = "prometheus_unreachable"):
        super().__init__(message)
        self.reason = reason


class ConfigError(ValueError):
    """规则文件或参数不合法（退出码 3，不重试）。"""


def skip_record(reason: str, **extra) -> dict:
    """台账的"跳过"行：同时写 ``skip`` 与 ``skip_reason``。

    前者是既有接缝（``alert_delivery`` / ``alert_producer``）的字段名，后者是契约
    （AC-3.5 / AC-4.1）点名的字段名——两个都写，避免读台账的人按哪套词汇都找不到。
    """
    return {"emit": False, "skip": reason, "skip_reason": reason, **extra}


def _num(value) -> int:
    """与 alert_producer 同口径：取不到整数就当 0。"""
    try:
        return int(value)
    except (TypeError, ValueError):
        return 0


def parse_duration_seconds(raw: str) -> int:
    """``90s`` / ``5m`` / ``6h`` / 裸秒数 → 秒。解析不了抛 ConfigError，不猜。"""
    text = str(raw).strip().lower()
    if not text:
        raise ConfigError("时长不能为空")
    unit = text[-1]
    try:
        if unit == "s":
            value = int(text[:-1])
        elif unit == "m":
            value = int(text[:-1]) * 60
        elif unit == "h":
            value = int(text[:-1]) * 3600
        else:
            value = int(text)
    except ValueError as exc:
        raise ConfigError(f"无法解析时长：{raw!r}") from exc
    if value <= 0:
        raise ConfigError(f"时长必须为正：{raw!r}")
    return value


def _parse_values(values) -> list[float]:
    """matrix 的 ``values`` → float 列表。NaN/Inf/坏形状丢弃（丢点不等于填 0）。"""
    out: list[float] = []
    for item in values or []:
        try:
            raw = item[1]
        except (IndexError, TypeError):
            continue
        try:
            val = float(raw)
        except (TypeError, ValueError):
            continue
        if math.isnan(val) or math.isinf(val):
            continue
        out.append(val)
    return out


class PromRangeClient:
    """Prometheus ``/api/v1/query_range`` 的最小客户端。``opener`` 可注入，测试不触网。"""

    def __init__(self, base: str = DEFAULT_PROM, opener=None, timeout: int = 10,
                 guard=localapi.assert_local):
        self.base = str(base).rstrip("/")
        self.opener = opener or urllib.request.urlopen
        self.timeout = timeout
        self.guard = guard

    def _get(self, path: str, params: dict) -> dict:
        url = self.base + path + "?" + urllib.parse.urlencode(params)
        if self.guard is not None:
            self.guard(url)  # 出口白名单：仅 http + 解析后仍为环回
        try:
            with self.opener(url, timeout=self.timeout) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except Exception as exc:  # noqa: BLE001 - 网络/解析错误一律归为"拿不到证据"
            raise PromRangeError(f"{url} 请求失败：{exc}") from exc
        if payload.get("status") != "success":
            raise PromRangeError(f"{url} 返回 status={payload.get('status')!r}")
        return payload.get("data") or {}

    def query_range(self, expr: str, start: datetime, end: datetime, step: int) -> list[float]:
        data = self._get("/api/v1/query_range", {
            "query": expr,
            "start": str(int(start.timestamp())),
            "end": str(int(end.timestamp())),
            "step": str(step),
        })
        if data.get("resultType") != "matrix":
            raise PromRangeError(f"{expr!r} 结果不是 matrix：{data.get('resultType')!r}",
                                 reason="rule_shape")
        result = data.get("result") or []
        if not result:
            return []  # 没有序列 ≠ 值为 0
        if len(result) > 1:
            raise PromRangeError(
                f"{expr!r} 返回 {len(result)} 条序列；规则必须先用 sum()/by() 聚合到单条",
                reason="rule_shape")
        return _parse_values(result[0].get("values") or [])


def compute_baseline(history: list[float]) -> tuple[float, float]:
    """返回 (中位数, 绝对中位差)。空序列抛 ValueError——空基线不该被静默接受。"""
    if not history:
        raise ValueError("基线样本为空")
    med = float(statistics.median(history))
    spread = float(statistics.median([abs(v - med) for v in history]))
    return med, spread


@dataclass(frozen=True)
class Params:
    min_samples: int = 20
    z_threshold: float = 3.5
    recent_points: int = 3


def evaluate(rule: Rule, series: list[float], params: Params) -> dict:
    """对一条序列做一次判定。返回的 dict 既是台账行也是告警文案的数据源。

    跳过（样本不足 / 无数据）时 ``fired=False`` 且带 ``skip_reason``——调用方必须留痕。
    """
    base = {
        "rule": rule.id,
        "metric_cn": rule.metric_cn,
        "service": rule.service,
        "total_points": len(series),
    }
    if not series:
        return {**base, "fired": False, "skip_reason": "no_data"}

    if len(series) < params.recent_points + params.min_samples:
        return {**base, "fired": False, "skip_reason": "insufficient_samples"}

    recent = series[-params.recent_points:]
    history = series[:-params.recent_points]
    med, mad = compute_baseline(history)
    current = float(statistics.median(recent))
    delta = current - med

    if mad == 0.0:
        # 退化情形：恒定/近恒定序列，z 无定义。只认绝对差，且必须标出来。
        method = "degenerate_mad_zero"
        z = None
        strong = abs(delta) >= rule.min_abs_delta
    else:
        method = "modified_z"
        z = MODIFIED_Z_CONST * delta / mad
        strong = abs(z) >= params.z_threshold and abs(delta) >= rule.min_abs_delta

    if strong and rule.direction == "above" and delta <= 0:
        strong = False
    elif strong and rule.direction == "below" and delta >= 0:
        strong = False

    return {
        **base,
        "fired": strong,
        "current": current,
        "median": med,
        "mad": mad,
        "z": z,
        "delta": delta,
        "method": method,
        # 契约 AC-3.4 点名要 ledger/baseline 记录退化情形，与 method 同值、两个名字都给。
        "baseline": method,
        "n": len(history),
        "direction": rule.direction,
    }


def render_query(verdict: dict, lookback: str) -> str:
    """告警正文：必须自带可手算复核的数字；刻意不带错误码（走语义检索路）。"""
    deviation = (f"{verdict['z']:.2f}σ" if verdict.get("z") is not None
                 else "MAD=0（仅绝对差）")
    return ALERT_TEMPLATE.format(
        rule=verdict["rule"],
        current=verdict["current"],
        median=verdict["median"],
        mad=verdict["mad"],
        deviation=deviation,
        delta=verdict["delta"],
        n=verdict["n"],
        lookback=lookback,
        method=verdict["method"],
        metric_cn=verdict["metric_cn"],
    )


def rules_from_payload(payload: list[dict]) -> tuple[Rule, ...]:
    """``--rules`` JSON 的校验。缺字段/方向非法/空表达式一律 ConfigError（不猜、不跳过）。"""
    if not isinstance(payload, list) or not payload:
        raise ConfigError("规则文件必须是非空数组")
    rules: list[Rule] = []
    seen: set[str] = set()
    for i, item in enumerate(payload):
        if not isinstance(item, dict):
            raise ConfigError(f"第 {i} 条规则不是对象")
        rid = str(item.get("id") or "").strip()
        expr = str(item.get("expr") or "").strip()
        direction = str(item.get("direction") or "above").strip()
        if not rid or not expr:
            raise ConfigError(f"第 {i} 条规则缺 id 或 expr")
        if rid in seen:
            raise ConfigError(f"规则 id 重复：{rid}")
        if direction not in ("above", "below", "both"):
            raise ConfigError(f"规则 {rid} 的 direction 非法：{direction}")
        try:
            delta = float(item.get("min_abs_delta"))
        except (TypeError, ValueError) as exc:
            raise ConfigError(f"规则 {rid} 的 min_abs_delta 非法") from exc
        if delta < 0:
            raise ConfigError(f"规则 {rid} 的 min_abs_delta 不能为负")
        seen.add(rid)
        rules.append(Rule(
            id=rid, expr=expr, direction=direction, min_abs_delta=delta,
            service=str(item.get("service") or "opspilot-gateway"),
            metric_cn=str(item.get("metric_cn") or rid),
        ))
    return tuple(rules)


class PredictiveAlerter:
    """一轮 = 拉序列 → 判定 → 过闸门 → 发预警 → 落台账。单轮失败不让进程死。"""

    def __init__(self, args, *, client: PromRangeClient | None = None,
                 ledger_path: Path = LEDGER, clock=time.time, api=None):
        self.args = args
        self.rules = tuple(args.rules)
        self.params = Params(
            min_samples=args.min_samples,
            z_threshold=args.z_threshold,
            recent_points=args.recent_points,
        )
        self.step_s = args.step
        self.lookback_label = args.lookback
        self.lookback_s = (getattr(args, "lookback_seconds", None)
                           or parse_duration_seconds(args.lookback))
        self.dry_run = args.dry_run
        self.cooldown = args.cooldown
        self.max_per_hour = args.max_per_hour
        self.quota_reserve = args.quota_reserve
        # --once 只跑一轮，连续命中要求没有意义——按 1 处理（契约冻结语义）。
        self.consecutive = 1 if args.once else max(1, args.consecutive)
        self.client = client or PromRangeClient(args.prometheus)
        self.delivery = AlertDelivery(
            AlertSession(args.user, api=localapi if api is None else api,
                         login_failure_mode=LoginFailureMode.ANY_HTTP_ERROR),
            ledger_path=ledger_path,
            clock=clock,
        )
        self._streak: dict[str, int] = {}
        self.login_failures = 0
        self.stats = {"cycles": 0, "fired": 0, "emitted": 0, "skipped": 0,
                      "errors": 0, "prom_failures": 0, "login_failures": 0}

    # ---- 台账 -------------------------------------------------------------
    def ledger(self, record: dict) -> None:
        self.delivery.ledger.append(record)

    # ---- 配额面 -----------------------------------------------------------
    def quota(self) -> QuotaSnapshot | None:
        """读不到就返回 None（调用方据此拒发）。配额是闸门，不是健康前置。"""
        try:
            state = self.delivery.observe_state()
        except LoginFailed:
            raise  # 凭据错/被锁：交由主循环退避与上限处置，不在这里被静默吞掉
        except Exception as exc:  # noqa: BLE001 - 读不到配额就不猜
            self.ledger(skip_record("quota_unavailable",
                                    note=f"{type(exc).__name__}: {exc}"))
            return None
        raw = ((state or {}).get("runtime") or {}).get("quota") or {}
        return QuotaSnapshot(limit=_num(raw.get("limit")), used=_num(raw.get("used")))

    # ---- 判定 -------------------------------------------------------------
    def _window(self, now: float) -> tuple[datetime, datetime]:
        end = datetime.fromtimestamp(now, timezone.utc)
        return end - timedelta(seconds=self.lookback_s), end

    def _series_for(self, rule: Rule, start: datetime, end: datetime) -> list[float] | None:
        try:
            return self.client.query_range(rule.expr, start, end, self.step_s)
        except PromRangeError as exc:
            self.stats["errors"] += 1
            self.stats["prom_failures"] += 1
            self.ledger(skip_record(exc.reason, rule=rule.id, note=str(exc)))
            return None

    # ---- 发送 -------------------------------------------------------------
    def deliver(self, rule: Rule, verdict: dict, query_text: str,
                quota: QuotaSnapshot | None, now: float) -> dict:
        policy = DeliveryPolicy(
            gates=PREDICTIVE_GATES,
            max_per_hour=self.max_per_hour,
            quota_reserve=self.quota_reserve,
            hour_accounting=HourAccounting.RESPONSE,
            login_before_send=False,
            transport_failure_handling=TransportFailureHandling.RAISE,
            response_code_key="error_code",
            dry_run=self.dry_run,
            quota=quota,
            cooldown_key=f"{rule.id}:{rule.service}",
            cooldown_seconds=self.cooldown,
        )
        old_clock = self.delivery.clock
        self.delivery.clock = lambda: now
        try:
            outcome = self.delivery.deliver(
                DeliveryAttempt(
                    body={"query": query_text, "source": "alert",
                          "service": rule.service, "env": ENV},
                    ledger={
                        "rule": rule.id, "metric_cn": rule.metric_cn,
                        "current": verdict["current"], "median": verdict["median"],
                        "mad": verdict["mad"], "z": verdict["z"],
                        "delta": verdict["delta"], "method": verdict["method"],
                        "baseline": verdict["baseline"],
                        "n": verdict["n"], "lookback": self.lookback_label,
                        "query": query_text,
                    },
                ),
                policy,
            )
        finally:
            self.delivery.clock = old_clock
        if (isinstance(outcome, TransportFailed)
                and outcome.handling is TransportFailureHandling.RAISE):
            if outcome.exception is not None:
                raise outcome.exception
            raise RuntimeError(outcome.error)
        return outcome.record

    # ---- 主循环 -----------------------------------------------------------
    def cycle(self, now: float | None = None) -> int:
        """跑一轮。返回退出码：0 正常 / 2 依赖不可达（Prometheus 全挂或配额面读不到）。"""
        now = time.time() if now is None else now
        self.stats["cycles"] += 1
        start, end = self._window(now)

        # dry-run 只判定不发：既不需要也不该去读配额面（--dry-run 必须能离线跑）。
        quota: QuotaSnapshot | None = None
        if not self.dry_run:
            quota = self.quota()

        attempted = 0
        for rule in self.rules:
            series = self._series_for(rule, start, end)
            if series is None:
                continue
            attempted += 1
            verdict = evaluate(rule, series, self.params)

            if verdict.get("skip_reason"):
                self.stats["skipped"] += 1
                self.ledger(skip_record(verdict["skip_reason"], rule=rule.id,
                                        total_points=verdict["total_points"]))
                continue

            if not verdict["fired"]:
                self._streak[rule.id] = 0
                continue

            self.stats["fired"] += 1
            self._streak[rule.id] = self._streak.get(rule.id, 0) + 1
            if self._streak[rule.id] < self.consecutive:
                self.ledger(skip_record("consecutive_pending", rule=rule.id,
                                        streak=self._streak[rule.id],
                                        need=self.consecutive))
                continue

            if quota is None and not self.dry_run:
                self.ledger(skip_record("quota_unavailable", rule=rule.id))
                continue

            query_text = render_query(verdict, self.lookback_label)
            try:
                record = self.deliver(rule, verdict, query_text, quota, now)
            except LoginFailed:
                raise  # 凭据问题不该被记成"投递失败"，见主循环的退避与上限
            except Exception as exc:  # noqa: BLE001 - 投递失败不打断其它规则
                self.stats["errors"] += 1
                self.ledger(skip_record("delivery_failed", rule=rule.id,
                                        note=f"{type(exc).__name__}: {exc}"))
                continue
            if record.get("emit") and record.get("sent_ok", True):
                self.stats["emitted"] += 1
            elif record.get("emit"):
                self.stats["errors"] += 1
            else:
                self.stats["skipped"] += 1

        self.ledger({"cycle": True, "rules": len(self.rules), "queried": attempted,
                     "fired": self.stats["fired"], "emitted": self.stats["emitted"],
                     "skipped": self.stats["skipped"], "errors": self.stats["errors"]})

        if self.rules and attempted == 0:
            return 2
        if quota is None and not self.dry_run:
            return 2
        return 0


# ---- 单实例锁 -------------------------------------------------------------

def _acquire_lock(interval: int, force: bool) -> bool:
    """单实例守门：冷却/小时预算/连续计数都是**进程内**状态，跑两个实例 = 双倍告警速率。

    锁文件按 mtime 判活（存活实例每轮触碰）；僵死实例的锁在 max(120, interval*4) 秒后
    可被接管。锁不可用（无写权限等）不阻断告警——可用性优先于互斥，风险已知。
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


def _touch_lock() -> None:
    try:
        LOCK.write_text(str(os.getpid()), encoding="utf-8")
    except OSError:
        pass


def _release_lock() -> None:
    try:
        LOCK.unlink()
    except OSError:
        pass


# ---- CLI ------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(
        description="统计基线预警（ADR-0030）：读 Prometheus 既有时序，异常时打进既有编排链")
    ap.add_argument("--once", action="store_true", help="只跑一轮后退出")
    ap.add_argument("--interval", type=int, default=60, help="轮询间隔秒（默认 60）")
    ap.add_argument("--dry-run", action="store_true", help="只判定与留痕，不发送（也不读配额面）")
    ap.add_argument("--prometheus", default=DEFAULT_PROM,
                    help=f"Prometheus 地址（默认 {DEFAULT_PROM}）")
    ap.add_argument("--user", default="sre-watcher", help="告警主体（默认复用既有 sre-watcher）")
    ap.add_argument("--lookback", default="6h", help="基线窗口（默认 6h；支持 90s/5m/6h）")
    ap.add_argument("--step", type=int, default=60, help="query_range 步长秒（默认 60）")
    ap.add_argument("--min-samples", type=int, default=20, help="基线样本下限（默认 20）")
    ap.add_argument("--z-threshold", type=float, default=3.5, help="modified z 阈值（默认 3.5）")
    ap.add_argument("--recent-points", type=int, default=3,
                    help="当前值取最近 N 点中位数（默认 3）")
    ap.add_argument("--consecutive", type=int, default=2,
                    help="连续命中轮数才发（默认 2；--once 恒按 1）")
    ap.add_argument("--cooldown", type=int, default=1800, help="同一规则冷却秒（默认 1800）")
    ap.add_argument("--max-per-hour", type=int, default=6, help="每小时最多发出条数（默认 6）")
    ap.add_argument("--quota-reserve", type=int, default=50, help="配额保留线（默认 50）")
    ap.add_argument("--rules", help="规则 JSON 覆盖默认五条")
    ap.add_argument("--force", action="store_true", help="无视既有锁文件强行启动")
    ap.add_argument("--json", action="store_true", help="打印结构化统计")
    return ap


def _load_rules(path: str | None) -> tuple[Rule, ...]:
    if not path:
        return DEFAULT_RULES
    try:
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
    except OSError as exc:
        raise ConfigError(f"规则文件读取失败：{exc}") from exc
    except json.JSONDecodeError as exc:
        raise ConfigError(f"规则文件不是合法 JSON：{exc}") from exc
    return rules_from_payload(payload)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        args.lookback_seconds = parse_duration_seconds(args.lookback)
        args.rules = _load_rules(args.rules)
        if args.step <= 0:
            raise ConfigError("--step 必须为正")
        if args.min_samples < 1 or args.recent_points < 1:
            raise ConfigError("--min-samples / --recent-points 必须 >= 1")
        if args.consecutive < 1:
            raise ConfigError("--consecutive 必须 >= 1")
        if args.interval < 0:
            raise ConfigError("--interval 不能为负")
    except ConfigError as exc:
        print(f"配置错误：{exc}", file=sys.stderr)
        return 3

    if not _acquire_lock(args.interval, force=args.force):
        print("[fatal] 已有实例在跑（冷却/小时预算/连续计数为进程内状态，多实例会成倍告警）——"
              "确认无实例后可加 --force 接管", file=sys.stderr)
        return 3

    alerter = PredictiveAlerter(args)
    rc = 0
    try:
        while True:
            try:
                rc = alerter.cycle()
                alerter.login_failures = 0
            except LoginFailed as exc:
                # 凭据错或主体被锁：重试有害（AuthService 5 次失败锁 15 分钟），
                # 且每次失败都写一行鉴权审计 → 退避 + 上限后退出，等人修凭据。
                alerter.login_failures += 1
                alerter.stats["login_failures"] = alerter.login_failures
                alerter.ledger(skip_record(
                    "login_failed",
                    note=f"{exc}（连续 {alerter.login_failures} 次）"))
                print(f"[warn] 登录失败（{exc}），连续 {alerter.login_failures} 次",
                      file=sys.stderr)
                if args.once or alerter.login_failures >= MAX_LOGIN_FAILURES:
                    print(f"[fatal] 登录失败（{exc}）：检查 {args.user} 的凭据/是否被锁，"
                          f"修好后重启——不再空转重试", file=sys.stderr)
                    rc = 2
                    break
                rc = 0
                time.sleep(min(300, max(args.interval, 5) * (2 ** alerter.login_failures)))
                continue
            except Exception as exc:  # noqa: BLE001 - 单轮失败不退出常驻进程
                alerter.stats["errors"] += 1
                print(f"[warn] 本轮失败：{type(exc).__name__}: {exc}", file=sys.stderr)
                rc = 2 if args.once else 0
            if rc == 2:
                print("依赖不可达（Prometheus 或配额面），本轮未产生预警", file=sys.stderr)
            if args.once:
                break
            _touch_lock()
            time.sleep(max(1, args.interval))
    except KeyboardInterrupt:
        pass
    finally:
        _release_lock()

    s = alerter.stats
    if args.json:
        print(json.dumps(s, ensure_ascii=False))
    print(f"[summary] cycles={s['cycles']} fired={s['fired']} emitted={s['emitted']} "
          f"skipped={s['skipped']} errors={s['errors']} "
          f"login_failures={alerter.login_failures}")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
