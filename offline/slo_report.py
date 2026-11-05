"""门禁 6/7 的周报/月报生成器——把"连续 30 天"从手工抄表变成一条命令。

为什么存在：`docs/ops/gate6-7-30day-observation.md` 已经写清了判据、模板与周节奏，
但它要求的动作是"每周一 15 分钟、手工查 PromQL 再贴表"。手工抄表的结局在门禁 1 上已经演过
一次（备份"每日跑"，实际停摆 35 天而无人察觉）：**没有产物就没有证据**。本工具负责把
Prometheus 读数、并发样本与抓取连续性固化成可提交的文件。

三条设计边界：

1. **只读、只出报表**。不触发压测、不改告警规则、不写语料；并发样本由
   `scripts/live_concurrency.py` 产出，本工具只消费。
2. **连续性证据按周固化，不靠"30 天后回看 Prometheus"**。保留期 30d 意味着第 30 天时
   第 1 天的数据正好在过期边缘；每周把 `scrape_days` 写进 `docs/ops/slo-weekly-*.json`，
   30 天的覆盖由这些周报求并集证明（`scripts/check_observation.py`）。
3. **证明不了的判据写"未证明"，不写通过**。schema 1 的旧样本没有 `Retry-After` 与降级档，
   本工具把它们标成 `UNPROVEN`；这与仓库"不假装知道"的纪律一致。

用法：
    python offline/slo_report.py --kind weekly
    python offline/slo_report.py --kind weekly --end 2026-11-09 --stdout
    python offline/slo_report.py --kind monthly --month 2026-11
    python offline/slo_report.py --kind weekly --prometheus http://127.0.0.1:9090 --json

产物（`--out` 默认 `docs/ops/`）：
    slo-weekly-<window-end>.md / .json
    slo-monthly-<YYYY-MM>.md / .json

退出码：0 成功（门禁未达成也照样出报表）；2 Prometheus 不可达或查不到数据；3 拒绝写出。
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OPS_DIR = REPO_ROOT / "docs" / "ops"
DEFAULT_PROM = os.environ.get("OPSPILOT_PROM", "http://127.0.0.1:9090")

# 一天按 15s 抓取应有 5760 个点。门槛取 1 小时（240 点）：覆盖"当天大部分时间在跑"，
# 又不把一次重启（几分钟空档）判成整天缺失——判据是"连续采集"，不是"零重启"。
MIN_SCRAPES_PER_DAY = 240

# 门禁 6：4 周 × 3 组 ×（加载 + 对照）= 24 条对照记录（见观察文档 §3.1）。
REQUIRED_RECORDS = 24
REQUIRED_DAYS = 30
WEEK_DAYS = 7

# (键, PromQL, 说明)。一律在窗口末端求值，窗口长度写进区间选择器。
SLI_QUERIES = [
    ("requests_total", "sum(increase(aiops_requests_total[{w}]))", "请求总数"),
    ("requests_failed", "sum(increase(aiops_requests_failed_total[{w}]))", "失败请求（5xx/异常）"),
    ("admission_rejected", "sum(increase(aiops_admission_rejected_total[{w}]))", "准入拒绝（429，受控背压）"),
    ("ttft_p50", "histogram_quantile(0.50, sum(rate(aiops_ttft_seconds_bucket[{w}])) by (le))", "TTFT P50（s）"),
    ("ttft_p95", "histogram_quantile(0.95, sum(rate(aiops_ttft_seconds_bucket[{w}])) by (le))", "TTFT P95（s）"),
    ("ttft_p99", "histogram_quantile(0.99, sum(rate(aiops_ttft_seconds_bucket[{w}])) by (le))", "TTFT P99（s）"),
    ("req_p95", "histogram_quantile(0.95, sum(rate(aiops_request_seconds_bucket[{w}])) by (le))", "请求耗时 P95（s）"),
    ("req_p99", "histogram_quantile(0.99, sum(rate(aiops_request_seconds_bucket[{w}])) by (le))", "请求耗时 P99（s）"),
    ("degradation_max", "max_over_time(aiops_degradation_state[{w}])", "降级档位峰值（0=L0/1=L1/2=L2）"),
    ("inflight_max", "max_over_time(aiops_inflight[{w}])", "在途峰值"),
]


def _iso_day(d: date) -> str:
    return d.isoformat()


def _window_end(day: date) -> datetime:
    """窗口末端的求值时刻：当天 23:59:59 UTC。"""
    return datetime(day.year, day.month, day.day, 23, 59, 59, tzinfo=timezone.utc)


class PrometheusError(Exception):
    """Prometheus 不可达 / 返回错误 / 结果形状不对。"""


class PromClient:
    """Prometheus HTTP API 的最小客户端。`opener` 可注入，测试不触网。"""

    def __init__(self, base: str = DEFAULT_PROM, opener=None, timeout: int = 10):
        self.base = base.rstrip("/")
        self.opener = opener or urllib.request.urlopen
        self.timeout = timeout

    def _get(self, path: str, params: dict) -> dict:
        url = self.base + path + "?" + urllib.parse.urlencode(params)
        try:
            with self.opener(url, timeout=self.timeout) as r:
                payload = json.loads(r.read().decode("utf-8"))
        except Exception as exc:  # 网络/解析错误一律归为"拿不到证据"
            raise PrometheusError(f"{url} 请求失败：{exc}") from exc
        if payload.get("status") != "success":
            raise PrometheusError(f"{url} 返回 status={payload.get('status')!r}")
        return payload.get("data") or {}

    def query(self, expr: str, at: datetime) -> float | None:
        data = self._get("/api/v1/query", {"query": expr, "time": str(int(at.timestamp()))})
        if data.get("resultType") != "vector":
            raise PrometheusError(f"{expr!r} 结果不是 instant vector：{data.get('resultType')!r}")
        for series in data.get("result") or []:
            try:
                return float(series["value"][1])
            except (KeyError, IndexError, TypeError, ValueError):
                continue
        return None  # 没有序列 ≠ 0：报表里要显示"无数据"

    def retention_days(self) -> float | None:
        flags = self._get("/api/v1/status/flags", {})
        raw = flags.get("storage.tsdb.retention.time")
        return parse_retention(raw) if raw else None


def parse_retention(raw: str) -> float | None:
    """Prometheus 的保留期字符串（如 `30d`、`720h`）→ 天。解析不了返回 None，不猜。"""
    raw = str(raw).strip().lower()
    units = {"y": 365.0, "w": 7.0, "d": 1.0, "h": 1 / 24, "m": 1 / 1440, "s": 1 / 86400, "ms": 0}
    for unit, factor in units.items():
        if raw.endswith(unit):
            try:
                return round(float(raw[: -len(unit)]) * factor, 3)
            except ValueError:
                return None
    return None


def window_for(kind: str, end: date, month: str | None) -> tuple[date, date, int]:
    """返回 (start, end, days)。weekly=含 end 在内的 7 天；monthly=整月或指定月。"""
    if kind == "monthly":
        if month:
            y, m = (int(x) for x in month.split("-"))
            start = date(y, m, 1)
            nxt = date(y + (m == 12), 1 if m == 12 else m + 1, 1)
        else:
            start = date(end.year, end.month, 1)
            nxt = date(end.year + (end.month == 12), 1 if end.month == 12 else end.month + 1, 1)
        last = nxt - timedelta(days=1)
        return start, last, (last - start).days + 1
    start = end - timedelta(days=WEEK_DAYS - 1)
    return start, end, WEEK_DAYS


def collect_sli(client: PromClient, start: date, end: date, days: int) -> tuple[dict, list[str]]:
    """在窗口末端求值。读不到的序列记 `None`（渲染成"无数据"），不补 0。"""
    at = _window_end(end)
    window = f"{days}d"
    sli: dict[str, float | None] = {}
    warnings: list[str] = []
    for key, expr, _ in SLI_QUERIES:
        try:
            sli[key] = client.query(expr.format(w=window), at)
        except PrometheusError as exc:
            warnings.append(f"{key}: {exc}")
            sli[key] = None
    return sli, warnings


def scrape_coverage(client: PromClient, start: date, end: date) -> tuple[list[str], list[str], list[str]]:
    """逐日证明"当天在抓"。返回 (covered, missing, notes)。

    每天单独求值一次 `count_over_time(up[1d])`：用 range query 拉 30 天会因保留期边界而截断，
    而且渲染时需要的是"哪几天"，不是一条连续曲线。
    """
    covered: list[str] = []
    missing: list[str] = []
    notes: list[str] = []
    day = start
    while day <= end:
        at = _window_end(day)
        try:
            n = client.query('count_over_time(up{job="opspilot"}[1d])', at)
        except PrometheusError as exc:
            notes.append(f"{day}: {exc}")
            n = None
        if n is not None and n >= MIN_SCRAPES_PER_DAY:
            covered.append(_iso_day(day))
        else:
            missing.append(_iso_day(day))
            if n is not None:
                notes.append(f"{day}: 仅 {int(n)} 个抓取点（门槛 {MIN_SCRAPES_PER_DAY}）")
        day += timedelta(days=1)
    return covered, missing, notes


def load_samples(samples_dir: Path, start: date, end: date) -> list[dict]:
    """读 `live-concurrency-*.json`，按窗口过滤，并给出逐样本判定。"""
    out: list[dict] = []
    for path in sorted(samples_dir.glob("live-concurrency-*.json")):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            out.append({"file": path.name, "verdict": "INVALID", "reasons": [f"读取失败：{exc}"]})
            continue
        day = _sample_day(data, path)
        if day is None:
            out.append({"file": path.name, "verdict": "INVALID", "reasons": ["started_at 无法解析"]})
            continue
        if not (start <= day <= end):
            continue
        verdict, reasons = judge_sample(data)
        out.append({"file": path.name, "concurrency": data.get("concurrency"),
                    "started_at": data.get("started_at"), "schema": data.get("schema", 1),
                    "verdict": verdict, "reasons": reasons})
    return out


def _sample_day(data: dict, path: Path) -> date | None:
    raw = str(data.get("started_at") or "")
    for fmt in ("%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%S"):
        try:
            return datetime.strptime(raw, fmt).date()
        except ValueError:
            continue
    # 退路：从文件名取日期（`live-concurrency-YYYY-MM-DD-cN.json`）
    parts = path.stem.split("-")
    for i in range(len(parts) - 2):
        try:
            return date(int(parts[i]), int(parts[i + 1]), int(parts[i + 2]))
        except ValueError:
            continue
    return None


def judge_sample(data: dict) -> tuple[str, list[str]]:
    """门禁 6 的单样本判定：硬判据违规 → FAIL；证据不够 → UNPROVEN；全齐 → PASS。

    "没出现 429"是 **UNPROVEN** 而不是 FAIL：c4 档并发数可能恰在许可数之内，
    削峰本来就不该触发（2026-11-05 live 实测：c4 全 200、0×429，属预期）。
    背压判据由更高并发档（c8/c20）证明；把"没触发"判 FAIL 会让按周固定跑的
    低并发档永久性把门禁打红——那是在惩罚按文档办事的人。
    """
    load = data.get("load") or {}
    control = data.get("control") or {}
    unproven: list[str] = []
    if load.get("rejected_429", 0) <= 0:
        unproven.append(f"并发 {data.get('concurrency', '?')} 未触发削峰（无 429）——"
                        "背压判据需更高并发档证明")
    if load.get("other", 0) > 0:
        return "FAIL", [f"加载租户出现 {load.get('other')} 条非 200/429（5xx 或连接错误）"]
    if control.get("total", 0) <= 0 or control.get("ok") != control.get("total"):
        return "FAIL", ["对照租户未全部 200（隔离失败或被连带限流）"]
    if data.get("schema", 1) < 2:
        return "UNPROVEN", ["schema 1 缺 Retry-After 与降级档字段，门禁 6 两条判据无法证明"] + unproven
    if load.get("retry_after_missing", 0) > 0:
        return "FAIL", [f"{load.get('retry_after_missing')} 条 429 缺 Retry-After"]
    degradation = (data.get("degradation") or {}).get("level")
    if degradation is None:
        return "UNPROVEN", ["样本未记录降级档（aiops_degradation_state 不可读）"] + unproven
    if degradation != "L0":
        return "FAIL", [f"压测后降级档为 {degradation}（对照租户/系统未保持 L0）"]
    if unproven:
        return "UNPROVEN", unproven
    return "PASS", []


def gate6(samples: list[dict]) -> dict:
    """对照记录 = 每个样本 2 条（加载 + 对照）；只有 PASS 计入通过数。"""
    passing = sum(1 for s in samples if s.get("verdict") == "PASS")
    return {"samples": len(samples), "passing_samples": passing,
            "records": len(samples) * 2, "passing_records": passing * 2,
            "required_records": REQUIRED_RECORDS,
            "verdict": "GREEN" if passing * 2 >= REQUIRED_RECORDS else "INCOMPLETE"}


def gate7(covered: list[str], missing: list[str], retention: float | None) -> dict:
    days = len(covered)
    verdict = "GREEN" if days >= REQUIRED_DAYS and retention is not None and retention >= REQUIRED_DAYS \
        else "INCOMPLETE"
    return {"days_covered": days, "days_missing": len(missing), "required_days": REQUIRED_DAYS,
            "retention_days": retention, "verdict": verdict}


# ---- 渲染 ---------------------------------------------------------------------

def _fmt(value: float | None, digits: int = 3, suffix: str = "") -> str:
    if value is None:
        return "无数据"
    if digits == 0:
        return f"{int(round(value))}{suffix}"
    return f"{value:.{digits}f}{suffix}"


def _verdict_cn(verdict: str) -> str:
    return {"GREEN": "**绿**", "INCOMPLETE": "黄（未达成）"}.get(verdict, verdict)


def render(payload: dict, kind: str) -> str:
    w = payload["window"]
    sli = payload["sli"]
    cov = payload["coverage"]
    g6, g7 = payload["gate6"], payload["gate7"]
    lines: list[str] = []
    add = lines.append

    if kind == "monthly":
        add(f"# SLO 月报 · {w['start'][:7]}")
        add("")
        add(f"> 窗口：{w['start']} ~ {w['end']}（连续 {w['days']} 天，UTC）")
    else:
        add(f"# 门禁 6/7 观察周报 · {w['start']} ~ {w['end']}")
        add("")
        add(f"> 窗口：{w['days']} 天（UTC）；由 `offline/slo_report.py` 生成，可重跑复现。")
    add(f"> 数据源：Prometheus `{payload['prometheus']['url']}`"
        f"（job=`opspilot`，保留期 {_fmt(cov['retention_days'], 0, ' 天')}）"
        f" + `docs/ops/live-concurrency-*.json`。")
    add("")
    add("## 1. SLI")
    add("")
    add("| SLI | 窗口值 | 判据 |")
    add("|---|---|---|")
    total = sli.get("requests_total")
    failed = sli.get("requests_failed")
    avail = None
    if total is not None and failed is not None and total > 0:
        avail = 1 - failed / total
    add(f"| 请求总数 | {_fmt(total, 0)} | — |")
    add(f"| 失败请求 / 可用性（剔除 429） | {_fmt(failed, 0)} / {_fmt(avail * 100 if avail is not None else None, 2, '%')} | ≥ 99.5% |")
    add(f"| 准入拒绝（429） | {_fmt(sli.get('admission_rejected'), 0)} | 受控背压，非故障 |")
    add(f"| TTFT P50 / P95 / P99 | {_fmt(sli.get('ttft_p50'))} / {_fmt(sli.get('ttft_p95'))} / {_fmt(sli.get('ttft_p99'))} s | P95 < 3s |")
    add(f"| 请求耗时 P95 / P99 | {_fmt(sli.get('req_p95'))} / {_fmt(sli.get('req_p99'))} s | 观测项 |")
    add(f"| 降级档位峰值 / 在途峰值 | {_fmt(sli.get('degradation_max'), 0)} / {_fmt(sli.get('inflight_max'), 0)} | 事件驱动 |")
    add("")

    add("## 2. 抓取连续性")
    add("")
    add(f"- 覆盖 {len(cov['covered'])}/{w['days']} 天（门槛：单日 ≥ {MIN_SCRAPES_PER_DAY} 个抓取点）")
    if cov["missing"]:
        add(f"- 缺失：{'、'.join(cov['missing'])}")
    for note in cov["notes"]:
        add(f"- {note}")
    add("")

    add("## 3. 门禁 6：准入对照样本")
    add("")
    if payload["samples"]:
        add("| 文件 | 并发 | 判定 | 理由 |")
        add("|---|---|---|---|")
        for s in payload["samples"]:
            add(f"| `{s['file']}` | {s.get('concurrency', '—')} | {s['verdict']} | "
                f"{'；'.join(s['reasons']) or '—'} |")
    else:
        add("- 窗口内没有并发样本（先跑 `scripts/live_concurrency.py`）。")
    add("")
    add(f"- 通过 {g6['passing_records']}/{g6['required_records']} 条对照记录"
        f"（每个样本 = 加载 + 对照 2 条）→ 门禁 6：{_verdict_cn(g6['verdict'])}")
    add("")

    add("## 4. 门禁 7：SLO")
    add("")
    add(f"- 连续覆盖 {g7['days_covered']}/{g7['required_days']} 天；保留期 "
        f"{_fmt(g7['retention_days'], 0, ' 天')} → 门禁 7：{_verdict_cn(g7['verdict'])}")
    add("")
    add("> 连续性按**周报固化**：保留期是 30 天，第 30 天回看时第 1 天正好在过期边缘。"
        "每周把 `scrape_days` 写进 JSON，`scripts/check_observation.py` 对这些文件求并集。")
    add("")
    return "\n".join(lines)


def default_names(kind: str, start: date, end: date) -> tuple[str, str]:
    if kind == "monthly":
        return f"slo-monthly-{end.isoformat()[:7]}.md", f"slo-monthly-{end.isoformat()[:7]}.json"
    return f"slo-weekly-{end.isoformat()}.md", f"slo-weekly-{end.isoformat()}.json"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="从 Prometheus 与并发样本生成门禁 6/7 的周报/月报（只读，不改任何配置）")
    ap.add_argument("--kind", choices=["weekly", "monthly"], default="weekly")
    ap.add_argument("--end", help="窗口末日 YYYY-MM-DD（UTC，默认今天）")
    ap.add_argument("--month", help="月报月份 YYYY-MM（只对 --kind monthly 生效）")
    ap.add_argument("--prometheus", default=DEFAULT_PROM, help=f"Prometheus 地址（默认 {DEFAULT_PROM}）")
    ap.add_argument("--samples-dir", default=str(OPS_DIR), help="并发样本目录（默认 docs/ops）")
    ap.add_argument("--out", default=str(OPS_DIR), help="输出目录（默认 docs/ops）")
    ap.add_argument("--stdout", action="store_true", help="只打印 Markdown，不落盘")
    ap.add_argument("--json", action="store_true", help="打印 JSON（配合 --stdout 或落盘）")
    args = ap.parse_args(argv)

    try:
        end = date.fromisoformat(args.end) if args.end else datetime.now(timezone.utc).date()
    except ValueError:
        print(f"--end 不是合法日期：{args.end}", file=sys.stderr)
        return 2
    start, end, days = window_for(args.kind, end, args.month)

    client = PromClient(args.prometheus)
    try:
        retention = client.retention_days()
        sli, warnings = collect_sli(client, start, end, days)
        covered, missing, notes = scrape_coverage(client, start, end)
    except PrometheusError as exc:
        print(f"Prometheus 不可达，未生成报表：{exc}", file=sys.stderr)
        return 2
    for w in warnings:
        print(f"warn: {w}", file=sys.stderr)

    samples = load_samples(Path(args.samples_dir), start, end)
    payload = {
        "schema": 1, "kind": args.kind,
        "window": {"start": start.isoformat(), "end": end.isoformat(), "days": days},
        "prometheus": {"url": args.prometheus, "job": "opspilot"},
        "coverage": {"covered": covered, "missing": missing, "notes": notes,
                     "retention_days": retention, "min_scrapes_per_day": MIN_SCRAPES_PER_DAY},
        "sli": sli,
        "samples": samples,
        "gate6": gate6(samples),
        "gate7": gate7(covered, missing, retention),
    }
    md = render(payload, args.kind)
    js = json.dumps(payload, ensure_ascii=False, indent=2)

    if args.stdout:
        sys.stdout.write((js if args.json else md) + "\n")
        return 0

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    md_name, json_name = default_names(args.kind, start, end)
    (out_dir / md_name).write_text(md, encoding="utf-8", newline="\n")
    (out_dir / json_name).write_text(js, encoding="utf-8", newline="\n")
    print(f"已生成：{out_dir / md_name}")
    print(f"        {out_dir / json_name}")
    print(f"  窗口 {start} ~ {end}；抓取覆盖 {len(covered)}/{days} 天；"
          f"样本 {len(samples)} 个（通过 {payload['gate6']['passing_samples']}）；"
          f"门禁 6 {payload['gate6']['verdict']} / 门禁 7 {payload['gate7']['verdict']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
