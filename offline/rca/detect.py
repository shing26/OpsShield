"""在线检测模拟（AC-7）。

每个 case 只用注入点**之前**的滚动基线做判定；注入点只用于最后算延迟，不参与检测。

判定规则：
- 指标：资源类（``_cpu`` / ``_mem`` / ``_diskio``）的正向 robust-z；
- 尺度：MAD，且不小于「|中位数| + 正常窗跨度」的 8%（与 topo-first 同一材料性下限）；
- 服务级：该服务任一资源指标越线即算；
- 持续性：连续 3 个采样点越线（z > 8）才报警；
- 窗口：注入前 10 分钟到注入后 10 分钟；报警落在 [inject, inject+300s] 记为检出。

``z > 8`` 与 ``sustain = 3`` 是在 **dev** 上按「误报率 ≤ 10% 前提下最大化检出率」
选出的（候选网格见报告 §检测轨），测试集只跑一次。

用法::

    python offline/rca/detect.py --split dev
    python offline/rca/detect.py --split test
"""
from __future__ import annotations

import argparse
import csv
import json
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

from dataset import Case, candidate_services, load_case, load_index, select_cases, to_service
from methods import METHODS
from metrics import mrr, rank_position, top_k

ROOT = Path(__file__).resolve().parent
DEFAULT_DATA = ROOT / "data"
DEFAULT_OUT = ROOT / "reports"

RESOURCE_SUFFIXES = ("_cpu", "_mem", "_diskio")
BASELINE_S = 300
SUSTAIN = 3
Z_THRESHOLD = 8.0
HORIZON_S = 300
PRE_S = 600
POST_S = 600
MATERIALITY_FLOOR = 0.08


def z_frame(case: Case) -> pd.DataFrame:
    """每个资源指标的在线正向 robust-z（只用滚动历史，不看未来）。"""
    start = case.inject_time - PRE_S - BASELINE_S
    frame = case.metrics[case.metrics["time"] >= start].set_index("time")
    resource_columns = [c for c in frame.columns if c.endswith(RESOURCE_SUFFIXES)]
    if not resource_columns:
        return pd.DataFrame(index=frame.index)

    values = frame[resource_columns]
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
    return pd.DataFrame(positive_z, index=values.index, columns=resource_columns)


def alarm_series(z: pd.DataFrame, z_threshold: float = Z_THRESHOLD,
                 sustain: int = SUSTAIN, min_metrics: int = 1) -> pd.Series:
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


def detect_case(case: Case, z_threshold: float = Z_THRESHOLD, sustain: int = SUSTAIN,
                min_metrics: int = 1) -> dict:
    alarms = alarm_series(z_frame(case), z_threshold, sustain, min_metrics)
    if alarms.empty:
        return {"alarm_time": None, "latency_s": None, "detected": False, "false_alarm": False}

    window = alarms.index[(alarms.index >= case.inject_time - PRE_S)
                          & (alarms.index <= case.inject_time + POST_S)]
    alarm_time = next((int(t) for t in window if bool(alarms.loc[t])), None)
    if alarm_time is None:
        return {"alarm_time": None, "latency_s": None, "detected": False, "false_alarm": False}
    latency = alarm_time - case.inject_time
    return {
        "alarm_time": alarm_time,
        "latency_s": latency,
        "detected": 0 <= latency <= HORIZON_S,
        "false_alarm": latency < 0,
    }


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
    )
    return METHODS[method_name].rank(shifted, candidate_services(shifted.metrics), seed=7)


def detected_rank(ranking: list[str], truth: str, n_candidates: int) -> int:
    """漏检（空排序）记满盘皆输，不能算成第 1 名。"""
    if not ranking:
        return n_candidates + 1
    return rank_position(ranking, truth)


def run(split: str, data_dir: Path, out_dir: Path) -> dict:
    index = load_index(data_dir)
    rows = select_cases(index, "re1-ob", split)
    cases = [load_case(data_dir, row) for _, row in rows.iterrows()]

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_dir = out_dir / f"{stamp}_re1-ob_detection_{split}"
    run_dir.mkdir(parents=True, exist_ok=True)

    records = []
    for case in cases:
        result = detect_case(case)
        record = {"case": case.name, "truth": case.root_cause_service, **result}
        candidates = candidate_services(case.metrics)
        for method_name in ("topo-first", "3sigma"):
            oracle = METHODS[method_name].rank(case, candidates, seed=7)
            record[f"{method_name}_oracle_rank"] = rank_position(oracle, case.root_cause_service)
            if result["alarm_time"] is None:
                record[f"{method_name}_rank"] = detected_rank([], case.root_cause_service,
                                                             len(candidates))
            else:
                detected = detected_ranking(case, result["alarm_time"], method_name)
                record[f"{method_name}_rank"] = detected_rank(detected, case.root_cause_service,
                                                             len(candidates))
        records.append(record)

    with (run_dir / "detection.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)

    latencies = [r["latency_s"] for r in records if r["detected"]]
    detected_records = [r for r in records if r["detected"]]

    def top1(key: str, subset) -> float:
        return top_k([r[key] for r in subset], 1)

    def mr(key: str, subset) -> float:
        return mrr([r[key] for r in subset])

    summary = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "suite": "re1-ob",
        "split": split,
        "n_cases": len(records),
        "params": {
            "baseline_s": BASELINE_S, "sustain": SUSTAIN, "z_threshold": Z_THRESHOLD,
            "horizon_s": HORIZON_S, "pre_s": PRE_S, "post_s": POST_S,
            "materiality_floor": MATERIALITY_FLOOR,
        },
        "detected": len(latencies),
        "detection_rate": len(latencies) / len(records),
        "false_alarms": sum(1 for r in records if r["false_alarm"]),
        "no_alarm": sum(1 for r in records if r["alarm_time"] is None),
        "latency_median_s": float(np.median(latencies)) if latencies else None,
        "latency_p90_s": float(np.percentile(latencies, 90)) if latencies else None,
        "oracle_top1_all": {m: top1(f"{m}_oracle_rank", records) for m in ("topo-first", "3sigma")},
        "detected_top1_all": {m: top1(f"{m}_rank", records) for m in ("topo-first", "3sigma")},
        "detected_conditional_top1": {
            m: top1(f"{m}_rank", detected_records) for m in ("topo-first", "3sigma")
        } if detected_records else None,
        "detected_conditional_mrr": {
            m: mr(f"{m}_rank", detected_records) for m in ("topo-first", "3sigma")
        } if detected_records else None,
    }
    (run_dir / "detection_summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"cases={len(records)} detected={summary['detected']} "
          f"rate={summary['detection_rate']:.3f} false_alarms={summary['false_alarms']} "
          f"no_alarm={summary['no_alarm']}")
    if latencies:
        print(f"latency median={summary['latency_median_s']:.0f}s p90={summary['latency_p90_s']:.0f}s")
    print(f"-> {run_dir}")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Online detection simulation")
    parser.add_argument("--split", default="dev", choices=["dev", "test", "all"])
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)
    run(args.split, args.data_dir, args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
