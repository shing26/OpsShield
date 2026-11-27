"""A2：独立的单侧 CUSUM 检测器（dev-only）。

这不是 v2 报警的后置过滤器：每个指标在自己的 robust-z 序列上累积正向偏离，
由 CUSUM 自己产生报警。v2 只在输出中作为逐 case 对照，不参与 A2 的报警。
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

import detect as v2
from dataset import (Case, load_case, load_index, select_cases, to_service)

ROOT = Path(__file__).resolve().parent
DEFAULT_DATA = ROOT / "data"
DEFAULT_OUT = ROOT / "reports"

STATUSES = ("detected", "false_alarm", "late", "none")


@dataclass(frozen=True)
class CusumConfig:
    k: float = 1.0
    h: float = 8.0
    min_metrics: int = 1


GRID = {
    "k": (0.5, 1.0),
    "h": (6.0, 8.0, 10.0, 12.0),
    "min_metrics": (1, 2),
}


def configs() -> list[CusumConfig]:
    return [
        CusumConfig(k=k, h=h, min_metrics=min_metrics)
        for k in GRID["k"]
        for h in GRID["h"]
        for min_metrics in GRID["min_metrics"]
    ]


def cusum_alarm_frame(z: pd.DataFrame, k: float, h: float) -> pd.DataFrame:
    """逐指标单侧 CUSUM；报警后把该指标的累积量重置为 0。"""
    if z.empty:
        return pd.DataFrame(index=z.index, dtype=bool)

    values = z.to_numpy(dtype=float)
    state = np.zeros(values.shape[1], dtype=float)
    alarms = np.zeros(values.shape, dtype=bool)
    for row_index in range(values.shape[0]):
        row = np.nan_to_num(values[row_index], nan=0.0, posinf=0.0, neginf=0.0)
        state = np.maximum(0.0, state + row - k)
        hit = state >= h
        alarms[row_index] = hit
        state[hit] = 0.0
    return pd.DataFrame(alarms, index=z.index, columns=z.columns)


def cusum_service_alarms(z: pd.DataFrame, k: float, h: float,
                         min_metrics: int = 1) -> pd.DataFrame:
    """服务级报警：同一时刻至少有 ``min_metrics`` 个指标报警。"""
    metric_alarms = cusum_alarm_frame(z, k, h)
    if metric_alarms.empty:
        return pd.DataFrame(index=z.index, dtype=bool)

    by_service: dict[str, list[str]] = {}
    for column in metric_alarms.columns:
        by_service.setdefault(to_service(column), []).append(column)
    service_hits = {
        service: metric_alarms[columns].sum(axis=1) >= min_metrics
        for service, columns in by_service.items()
    }
    return pd.DataFrame(service_hits, index=z.index)


def first_alarm(alarms: pd.DataFrame, inject_time: int) -> tuple[int | None, str | None]:
    """返回窗口内最早的服务报警（时间, 服务）。"""
    if alarms.empty:
        return None, None
    low, high = inject_time - v2.PRE_S, inject_time + v2.POST_S
    for moment in alarms.index[(alarms.index >= low) & (alarms.index <= high)]:
        services = [service for service in alarms.columns if bool(alarms.at[moment, service])]
        if services:
            return int(moment), services[0]
    return None, None


def detect_case(case: Case, arms: tuple[str, ...] = (v2.RESOURCE,),
                config: CusumConfig | None = None) -> dict:
    """独立 CUSUM 检测；不读取 case 标签。"""
    config = config or CusumConfig()
    per_arm: dict[str, dict] = {}
    earliest: int | None = None
    for arm in arms:
        alarms = cusum_service_alarms(v2.z_frame(case, arm), config.k,
                                      config.h, config.min_metrics)
        alarm, service = first_alarm(alarms, case.inject_time)
        per_arm[arm] = {
            **v2.summarize_alarm(alarm, case.inject_time),
            "alarm_service": service,
        }
        if alarm is not None and (earliest is None or alarm < earliest):
            earliest = alarm
    combined = v2.summarize_alarm(earliest, case.inject_time)
    combined["arms"] = per_arm
    return combined


def classify(result: dict, inject_time: int) -> str:
    """把首次报警归入 detected / false_alarm / late / none。"""
    alarm = result.get("alarm_time")
    if alarm is None:
        return "none"
    latency = int(alarm) - inject_time
    if latency < 0:
        return "false_alarm"
    if latency <= v2.HORIZON_S:
        return "detected"
    return "late"


def _per_fault(records: list[dict], key: str) -> dict[str, dict]:
    grouped: dict[str, list[dict]] = {}
    for record in records:
        grouped.setdefault(record["fault"], []).append(record)
    result: dict[str, dict] = {}
    for fault, rows in sorted(grouped.items()):
        detected = sum(1 for row in rows if row[key] == "detected")
        result[fault] = {"n": len(rows), "detected": detected,
                         "coverage": detected / len(rows)}
    return result


def _latency(records: list[dict], key: str) -> tuple[float | None, float | None]:
    latencies = [row[f"{key}_latency_s"] for row in records
                 if row[f"{key}_latency_s"] is not None]
    if not latencies:
        return None, None
    return float(np.median(latencies)), float(np.percentile(latencies, 90))


def _run_tag(suite: str, config: CusumConfig) -> str:
    return (f"{suite}_a2-cusum_k{config.k:g}_h{config.h:g}"
            f"_m{config.min_metrics}")


def run(suite: str, split: str, arms: tuple[str, ...], config: CusumConfig,
        data_dir: Path, out_dir: Path) -> dict:
    index = load_index(data_dir)
    rows = select_cases(index, suite, split)
    cases = [load_case(data_dir, row) for _, row in rows.iterrows()]
    if not cases:
        raise SystemExit(f"no cases for {suite}/{split}")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    run_dir = out_dir / f"{stamp}_{_run_tag(suite, config)}"
    run_dir.mkdir(parents=True, exist_ok=True)

    records: list[dict] = []
    for case in cases:
        v2_result = v2.detect_case(case, arms=arms)
        a2_result = detect_case(case, arms=arms, config=config)
        v2_status = classify(v2_result, case.inject_time)
        a2_status = classify(a2_result, case.inject_time)
        records.append({
            "case": case.name,
            "fault": case.fault,
            "v2_status": v2_status,
            "a2_status": a2_status,
            "v2_alarm_time": v2_result.get("alarm_time"),
            "a2_alarm_time": a2_result.get("alarm_time"),
            "v2_latency_s": v2_result.get("latency_s"),
            "a2_latency_s": a2_result.get("latency_s"),
            "a2_arms": json.dumps(a2_result["arms"], ensure_ascii=False),
        })

    transitions = Counter(f"{row['v2_status']}->{row['a2_status']}" for row in records)
    gross_new = sum(1 for row in records
                    if row["v2_status"] != "detected" and row["a2_status"] == "detected")
    lost = sum(1 for row in records
               if row["v2_status"] == "detected" and row["a2_status"] != "detected")
    v2_median, v2_p90 = _latency(records, "v2")
    a2_median, a2_p90 = _latency(records, "a2")
    summary = {
        "suite": suite,
        "split": split,
        "config": asdict(config),
        "n_cases": len(records),
        "arms": list(arms),
        "statuses": {status: sum(1 for row in records if row["a2_status"] == status)
                     for status in STATUSES},
        "v2_statuses": {status: sum(1 for row in records if row["v2_status"] == status)
                        for status in STATUSES},
        "transitions": dict(sorted(transitions.items())),
        "gross_new": gross_new,
        "lost": lost,
        "v2_detection_rate": sum(1 for row in records if row["v2_status"] == "detected")
        / len(records),
        "a2_detection_rate": sum(1 for row in records if row["a2_status"] == "detected")
        / len(records),
        "a2_false_alarm_rate": sum(1 for row in records if row["a2_status"] == "false_alarm")
        / len(records),
        "v2_latency_median_s": v2_median,
        "v2_latency_p90_s": v2_p90,
        "a2_latency_median_s": a2_median,
        "a2_latency_p90_s": a2_p90,
        "per_fault": {
            "v2": _per_fault(records, "v2_status"),
            "a2": _per_fault(records, "a2_status"),
        },
        "params": {
            "baseline_s": v2.BASELINE_S,
            "materiality_floor": v2.MATERIALITY_FLOOR,
            "pre_s": v2.PRE_S,
            "post_s": v2.POST_S,
            "horizon_s": v2.HORIZON_S,
        },
    }

    with (run_dir / "detection.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(records[0]))
        writer.writeheader()
        writer.writerows(records)
    (run_dir / "detection_summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"suite={suite} cases={len(records)} "
          f"a2_rate={summary['a2_detection_rate']:.3f} "
          f"a2_fa={summary['a2_false_alarm_rate']:.3f} "
          f"new={gross_new} lost={lost}")
    print(f"-> {run_dir}")
    return summary


def sweep(suite: str, split: str, arms: tuple[str, ...],
          data_dir: Path, out_dir: Path) -> dict:
    """A2-2 用：跑完预注册 16 配置并写一个 grid.json。"""
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    grid_dir = out_dir / f"{stamp}_{suite}_a2-grid"
    grid_dir.mkdir(parents=True, exist_ok=True)
    results = []
    for config in configs():
        summary = run(suite, split, arms, config, data_dir, grid_dir)
        results.append(summary)
    payload = {
        "suite": suite,
        "split": split,
        "arms": list(arms),
        "n_configs": len(results),
        "configs": [asdict(config) for config in configs()],
        "results": results,
    }
    (grid_dir / "grid.json").write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="A2 CUSUM detection (dev only)")
    parser.add_argument("--suite", required=True, choices=["re1-ob", "re1-ss"])
    parser.add_argument("--split", default="dev", choices=["dev"])
    parser.add_argument("--arms", default="both", choices=["resource", "sli", "both"])
    parser.add_argument("--k", type=float, default=CusumConfig().k)
    parser.add_argument("--h", type=float, default=CusumConfig().h)
    parser.add_argument("--min-metrics", type=int, default=CusumConfig().min_metrics)
    parser.add_argument("--sweep", action="store_true")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)

    arms = (v2.RESOURCE, v2.SLI) if args.arms == "both" else (args.arms,)
    if args.sweep:
        sweep(args.suite, args.split, arms, args.data_dir, args.out)
    else:
        run(args.suite, args.split, arms,
            CusumConfig(k=args.k, h=args.h, min_metrics=args.min_metrics),
            args.data_dir, args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
