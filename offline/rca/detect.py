"""在线检测模拟（AC-6/AC-7）。

两个检测臂：
- resource：cpu / mem / diskio；
- sli：latency / latency-50 / latency-90 / error。

两臂共用「只读滚动基线 + 正向 robust-z + 8% 材料性下限 + 连续 3 点越线」。
任一臂报警即合并报警；同时分别报单臂覆盖率。

RE1-SS 只有 6 分钟注入前数据，因此观察窗 ``pre = min(600s, 注入前可用长度)``；
报告必须写清每套数据的实际前窗。

用法::

    python offline/rca/detect.py --suite re1-ob --split test --arms resource
    python offline/rca/detect.py --suite re1-ss --split test --arms both
"""
from __future__ import annotations

import argparse
import csv
import json
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from dataset import (Case, candidate_services, load_case, load_index,
                     metric_family, select_cases, to_service)
from methods import METHODS
from metrics import mrr, rank_position, top_k

ROOT = Path(__file__).resolve().parent
DEFAULT_DATA = ROOT / "data"
DEFAULT_OUT = ROOT / "reports"

RESOURCE = "resource"
SLI = "sli"
ARMS = (RESOURCE, SLI)
DEFAULT_THRESHOLDS = {RESOURCE: 8.0, SLI: 12.0}

BASELINE_S = 300
SUSTAIN = 3
HORIZON_S = 300
PRE_S = 600
POST_S = 600
MATERIALITY_FLOOR = 0.08


def z_frame(case: Case, family: str = RESOURCE) -> pd.DataFrame:
    """某一指标族的在线正向 robust-z（只用滚动历史，不看未来）。"""
    start = case.inject_time - PRE_S - BASELINE_S
    frame = case.metrics[case.metrics["time"] >= start].set_index("time")
    columns = [c for c in frame.columns if metric_family(c) == family]
    if not columns:
        return pd.DataFrame(index=frame.index)

    values = frame[columns]
    median = values.rolling(BASELINE_S, min_periods=BASELINE_S // 2).median()
    mad = (values - median).abs().rolling(BASELINE_S, min_periods=BASELINE_S // 2).median() * 1.4826
    std = values.rolling(BASELINE_S, min_periods=BASELINE_S // 2).std()
    high = values.rolling(BASELINE_S, min_periods=BASELINE_S // 2).quantile(0.95)
    low = values.rolling(BASELINE_S, min_periods=BASELINE_S // 2).quantile(0.05)
    floor = MATERIALITY_FLOOR * (median.abs() + (high - low) + 1e-9)
    scale = np.maximum.reduce([
        np.nan_to_num(mad.to_numpy(dtype=float), nan=0.0),
        np.nan_to_num(floor.to_numpy(dtype=float), nan=0.0),
        # MAD 需要二次滚动，冷启动期比中位数晚成熟；用 std 兜住这段，避免 NaN 传播。
        np.nan_to_num(std.to_numpy(dtype=float), nan=0.0) * 0.5,
        np.full(median.shape, 1e-9),
    ])
    median_values = median.to_numpy(dtype=float)
    positive_z = np.maximum((values.to_numpy(dtype=float) - median_values) / scale, 0.0)
    positive_z = np.where(np.isnan(median_values), np.nan, positive_z)
    return pd.DataFrame(positive_z, index=values.index, columns=columns)


def alarm_series(z: pd.DataFrame, z_threshold: float, sustain: int = SUSTAIN,
                 min_metrics: int = 1) -> pd.Series:
    """服务级报警：同一服务至少 ``min_metrics`` 个指标连续 ``sustain`` 点越线。"""
    if z.empty:
        return pd.Series(dtype=bool)

    by_service: dict[str, list[str]] = {}
    for column in z.columns:
        by_service.setdefault(to_service(column), []).append(column)
    service_hits = {}
    for service, columns in by_service.items():
        hits = (z[columns] > z_threshold).sum(axis=1) >= min_metrics
        service_hits[service] = hits.rolling(sustain, min_periods=sustain).sum() >= sustain
    return pd.DataFrame(service_hits).any(axis=1)


def first_alarm(alarms: pd.Series, inject_time: int) -> int | None:
    if alarms.empty:
        return None
    window = alarms.index[(alarms.index >= inject_time - PRE_S)
                          & (alarms.index <= inject_time + POST_S)]
    return next((int(t) for t in window if bool(alarms.loc[t])), None)


def summarize_alarm(alarm: int | None, inject_time: int) -> dict:
    if alarm is None:
        return {"alarm_time": None, "latency_s": None, "detected": False, "false_alarm": False}
    latency = alarm - inject_time
    return {
        "alarm_time": alarm,
        "latency_s": latency,
        "detected": 0 <= latency <= HORIZON_S,
        "false_alarm": latency < 0,
    }


def detect_case(case: Case, arms: tuple[str, ...] = (RESOURCE,),
                thresholds: dict[str, float] | None = None,
                sustain: int = SUSTAIN, min_metrics: int = 1) -> dict:
    thresholds = thresholds or DEFAULT_THRESHOLDS
    per_arm: dict[str, dict] = {}
    earliest: int | None = None
    for arm in arms:
        alarm = first_alarm(alarm_series(z_frame(case, arm), thresholds[arm], sustain,
                                         min_metrics), case.inject_time)
        per_arm[arm] = summarize_alarm(alarm, case.inject_time)
        if alarm is not None and (earliest is None or alarm < earliest):
            earliest = alarm
    combined = summarize_alarm(earliest, case.inject_time)
    combined["arms"] = per_arm
    return combined


def detected_ranking(case: Case, alarm_time: int, method_name: str) -> list[str]:
    """用检测到的起点替代 inject_time，做端到端定位。"""
    window = case.metrics[(case.metrics["time"] >= alarm_time - BASELINE_S)
                          & (case.metrics["time"] <= alarm_time + HORIZON_S)]
    shifted = Case(
        name=case.name,
        root_cause_service=case.root_cause_service,
        fault=case.fault,
        repetition=case.repetition,
        inject_time=alarm_time,
        metrics=window.reset_index(drop=True),
        system=case.system,
    )
    return METHODS[method_name].rank(shifted, candidate_services(shifted.metrics), seed=7)


def detected_rank(ranking: list[str], truth: str, n_candidates: int) -> int:
    """漏检（空排序）记满盘皆输，不能算成第 1 名。"""
    if not ranking:
        return n_candidates + 1
    return rank_position(ranking, truth)


def counts_as_detection(result: dict) -> bool:
    """只有「在注入后窗口内首次报警」才算检出；未报警与提前误报都算失败。"""
    return result["alarm_time"] is not None and not result["false_alarm"]


def _arm_summary(records: list[dict], arm: str) -> dict:
    detected = [r for r in records if r["arms"][arm]["detected"]]
    latencies = [r["arms"][arm]["latency_s"] for r in detected]
    return {
        "detected": len(detected),
        "detection_rate": len(detected) / len(records) if records else 0.0,
        "false_alarms": sum(1 for r in records if r["arms"][arm]["false_alarm"]),
        "no_alarm": sum(1 for r in records if r["arms"][arm]["alarm_time"] is None),
        "latency_median_s": float(np.median(latencies)) if latencies else None,
        "latency_p90_s": float(np.percentile(latencies, 90)) if latencies else None,
    }


def run(suite: str, split: str, arms: tuple[str, ...], thresholds: dict[str, float],
        data_dir: Path, out_dir: Path, reps: list[int] | None = None) -> dict:
    index = load_index(data_dir)
    rows = select_cases(index, suite, split, reps)
    cases = [load_case(data_dir, row) for _, row in rows.iterrows()]
    if not cases:
        raise SystemExit(f"no cases for {suite}/{split}")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_dir = out_dir / f"{stamp}_{suite}_detection_{split}"
    run_dir.mkdir(parents=True, exist_ok=True)

    records = []
    for case in cases:
        result = detect_case(case, arms, thresholds)
        record = {"case": case.name, "truth": case.root_cause_service, **result}
        record.pop("arms")
        # 下面评分用合并口径；arms 的原始结果保留在 per-arm 字段里。
        record["arms"] = result["arms"]

        candidates = candidate_services(case.metrics)
        for method_name in ("dual-arm", "topo-first", "3sigma"):
            if method_name not in METHODS:
                continue
            oracle = METHODS[method_name].rank(case, candidates, seed=7)
            record[f"{method_name}_oracle_rank"] = rank_position(oracle,
                                                                 case.root_cause_service)
            if not counts_as_detection(result):
                record[f"{method_name}_rank"] = len(candidates) + 1
            else:
                detected = detected_ranking(case, result["alarm_time"], method_name)
                record[f"{method_name}_rank"] = detected_rank(detected,
                                                              case.root_cause_service,
                                                              len(candidates))
        records.append(record)

    flat_records = []
    for record in records:
        flat = {k: v for k, v in record.items() if k != "arms"}
        for arm in arms:
            for key, value in record["arms"][arm].items():
                flat[f"{arm}_{key}"] = value
        flat_records.append(flat)

    with (run_dir / "detection.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(flat_records[0]))
        writer.writeheader()
        writer.writerows(flat_records)

    detected_records = [r for r in records if counts_as_detection(r)]
    summary = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "suite": suite,
        "split": split,
        "reps": reps,
        "n_cases": len(records),
        "arms": list(arms),
        "thresholds": thresholds,
        "params": {
            "baseline_s": BASELINE_S, "sustain": SUSTAIN, "horizon_s": HORIZON_S,
            "pre_s": PRE_S, "post_s": POST_S, "materiality_floor": MATERIALITY_FLOOR,
        },
        "detected": len(detected_records),
        "detection_rate": len(detected_records) / len(records),
        "false_alarms": sum(1 for r in records if r["false_alarm"]),
        "no_alarm": sum(1 for r in records if r["alarm_time"] is None),
        "detection_failures": sum(1 for r in records if not counts_as_detection(r)),
        "latency_median_s": float(np.median([r["latency_s"] for r in detected_records]))
        if detected_records else None,
        "latency_p90_s": float(np.percentile([r["latency_s"] for r in detected_records], 90))
        if detected_records else None,
        "per_arm": {arm: _arm_summary(records, arm) for arm in arms},
    }
    for method_name in ("dual-arm", "topo-first", "3sigma"):
        if method_name not in METHODS:
            continue
        summary[f"{method_name}_oracle_top1_all"] = top_k(
            [r[f"{method_name}_oracle_rank"] for r in records], 1)
        summary[f"{method_name}_detected_top1_all"] = top_k(
            [r[f"{method_name}_rank"] for r in records], 1)
        if detected_records:
            summary[f"{method_name}_detected_conditional_top1"] = top_k(
                [r[f"{method_name}_rank"] for r in detected_records], 1)
            summary[f"{method_name}_detected_conditional_mrr"] = mrr(
                [r[f"{method_name}_rank"] for r in detected_records])

    (run_dir / "detection_summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"suite={suite} split={split} cases={len(records)} "
          f"detected={summary['detected']} rate={summary['detection_rate']:.3f} "
          f"false_alarms={summary['false_alarms']} no_alarm={summary['no_alarm']}")
    if detected_records:
        print(f"latency median={summary['latency_median_s']:.0f}s "
              f"p90={summary['latency_p90_s']:.0f}s")
    for arm in arms:
        stats = summary["per_arm"][arm]
        print(f"  {arm}: rate={stats['detection_rate']:.3f} fa={stats['false_alarms']} "
              f"no_alarm={stats['no_alarm']}")
    print(f"-> {run_dir}")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Online detection simulation")
    parser.add_argument("--suite", default="re1-ob", choices=["re1-ob", "re1-ss"])
    parser.add_argument("--split", default="dev", choices=["dev", "test", "all"])
    parser.add_argument("--arms", default="both", choices=["resource", "sli", "both"])
    parser.add_argument("--threshold-resource", type=float,
                        default=DEFAULT_THRESHOLDS[RESOURCE])
    parser.add_argument("--threshold-sli", type=float, default=DEFAULT_THRESHOLDS[SLI])
    parser.add_argument("--reps", nargs="+", type=int, default=None)
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)

    arms = (RESOURCE, SLI) if args.arms == "both" else (args.arms,)
    thresholds = {RESOURCE: args.threshold_resource, SLI: args.threshold_sli}
    run(args.suite, args.split, arms, thresholds, args.data_dir, args.out, args.reps)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
