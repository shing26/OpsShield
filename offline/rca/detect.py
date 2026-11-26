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
    python offline/rca/detect.py --suite re1-tt --split all --arms both
"""
from __future__ import annotations

import argparse
import csv
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

import detect_features as feat
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
# dev 选参结果（见 reports/V2-detect-sweep.json）：RE1-SS dev 覆盖率 0.907、
# 误报 1/75；契约固定 sustain=3、材料性下限 8%。
DEFAULT_THRESHOLDS = {RESOURCE: 20.0, SLI: 8.0}

BASELINE_S = 300
SUSTAIN = 3
HORIZON_S = 300
PRE_S = 600
POST_S = 600
MATERIALITY_FLOOR = 0.08

# A1 预注册默认值（见 plans/rca-detection-a1-accept.md §4）。
DEFAULT_FILTER = {"modes": ("coherence", "duration"), "z0": 4.0, "k": 3,
                  "window_s": 120, "fraction": 0.8}


@dataclass(frozen=True)
class AlarmFilter:
    modes: tuple[str, ...] = ("coherence", "duration")
    z0: float = 4.0
    k: int = 3
    window_s: int = 120
    fraction: float = 0.8


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


def service_alarm_series(z: pd.DataFrame, z_threshold: float, sustain: int = SUSTAIN,
                         min_metrics: int = 1) -> pd.DataFrame:
    """逐服务报警：同一服务至少 ``min_metrics`` 个指标连续 ``sustain`` 点越线。"""
    if z.empty:
        return pd.DataFrame(index=z.index)

    by_service: dict[str, list[str]] = {}
    for column in z.columns:
        by_service.setdefault(to_service(column), []).append(column)
    service_hits = {}
    for service, columns in by_service.items():
        hits = (z[columns] > z_threshold).sum(axis=1) >= min_metrics
        service_hits[service] = hits.rolling(sustain, min_periods=sustain).sum() >= sustain
    return pd.DataFrame(service_hits, index=z.index)


def alarm_series(z: pd.DataFrame, z_threshold: float, sustain: int = SUSTAIN,
                 min_metrics: int = 1) -> pd.Series:
    """任一服务报警即报警。"""
    frame = service_alarm_series(z, z_threshold, sustain, min_metrics)
    if frame.empty:
        return pd.Series(dtype=bool)
    return frame.any(axis=1)


def filtered_first_alarm(alarms: pd.DataFrame, coherence: pd.Series,
                         duration: dict[str, pd.Series], alarm_filter: AlarmFilter,
                         inject_time: int) -> tuple[int | None, str | None]:
    """按时间顺序取第一个通过 ``C or D`` 的报警事件。"""
    if alarms.empty:
        return None, None
    low, high = inject_time - PRE_S, inject_time + POST_S
    times = alarms.index[(alarms.index >= low) & (alarms.index <= high)]
    for moment in times:
        services = [s for s in alarms.columns if bool(alarms.at[moment, s])]
        if not services:
            continue
        coherent = ("coherence" in alarm_filter.modes
                    and bool(coherence.get(moment, False)))
        for service in services:
            if coherent:
                return int(moment), service
            if "duration" in alarm_filter.modes:
                flags = duration.get(service)
                if flags is not None and bool(flags.get(moment, False)):
                    return int(moment), service
    return None, None


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
                sustain: int = SUSTAIN, min_metrics: int = 1,
                alarm_filter: AlarmFilter | None = None) -> dict:
    thresholds = thresholds or DEFAULT_THRESHOLDS
    per_arm: dict[str, dict] = {}
    earliest: int | None = None
    for arm in arms:
        z = z_frame(case, arm)
        extra: dict = {}
        if alarm_filter is None:
            alarm = first_alarm(alarm_series(z, thresholds[arm], sustain, min_metrics),
                                case.inject_time)
        else:
            alarms = service_alarm_series(z, thresholds[arm], sustain, min_metrics)
            anomaly = feat.service_anomaly(z, alarm_filter.z0)
            coherence = feat.coherence_pass(anomaly, alarm_filter.k)
            duration = {
                service: feat.duration_pass(anomaly[service], alarm_filter.window_s,
                                            alarm_filter.fraction)
                for service in anomaly.columns
            }
            alarm, service = filtered_first_alarm(alarms, coherence, duration,
                                                  alarm_filter, case.inject_time)
            extra = {
                "alarm_service": service,
                "p_corr": (feat.topology_correlation(z, service, alarm, case.system)
                           if alarm is not None and service else None),
            }
        per_arm[arm] = {**summarize_alarm(alarm, case.inject_time), **extra}
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
    """只有「注入后 ``HORIZON_S`` 内的首次报警」才算检出。

    未报警、提前误报、以及超过 horizon 的迟到报警都算失败。v1 的这里只判
    ``alarm_time is not None``，会把 +300s 之后的报警也记成检出，与 CSV 的
    ``detected`` 列不一致——v2 修正为同一个口径。
    """
    if "detected" in result:
        return bool(result["detected"]) and not result.get("false_alarm", False)
    latency = result.get("latency_s")
    if latency is not None:
        return 0 <= latency <= HORIZON_S
    return result.get("alarm_time") is not None and not result.get("false_alarm", False)


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
        data_dir: Path, out_dir: Path, reps: list[int] | None = None,
        alarm_filter: AlarmFilter | None = None) -> dict:
    index = load_index(data_dir)
    rows = select_cases(index, suite, split, reps)
    cases = [load_case(data_dir, row) for _, row in rows.iterrows()]
    if not cases:
        raise SystemExit(f"no cases for {suite}/{split}")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    suffix = "" if alarm_filter is None else f"_filter{'-'.join(alarm_filter.modes)}"
    run_dir = out_dir / f"{stamp}_{suite}_detection_{split}{suffix}"
    run_dir.mkdir(parents=True, exist_ok=True)

    records = []
    for case in cases:
        result = detect_case(case, arms, thresholds, alarm_filter=alarm_filter)
        record = {"case": case.name, "truth": case.root_cause_service,
                  "fault": case.fault, **result}
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
        "filter": (None if alarm_filter is None else {
            "modes": list(alarm_filter.modes), "z0": alarm_filter.z0,
            "k": alarm_filter.k, "window_s": alarm_filter.window_s,
            "fraction": alarm_filter.fraction,
        }),
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
    if alarm_filter is not None:
        print(f"filter={','.join(alarm_filter.modes)} z0={alarm_filter.z0} "
              f"k={alarm_filter.k} W={alarm_filter.window_s}s "
              f"p={alarm_filter.fraction}")
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
    parser.add_argument("--suite", default="re1-ob",
                        choices=["re1-ob", "re1-ss", "re1-tt"])
    parser.add_argument("--split", default="dev", choices=["dev", "test", "all"])
    parser.add_argument("--arms", default="both", choices=["resource", "sli", "both"])
    parser.add_argument("--threshold-resource", type=float,
                        default=DEFAULT_THRESHOLDS[RESOURCE])
    parser.add_argument("--threshold-sli", type=float, default=DEFAULT_THRESHOLDS[SLI])
    parser.add_argument("--filter", default="off",
                        choices=["off", "coherence", "duration", "both"],
                        help="A1 new-feature filter; 'off' keeps v2 behaviour")
    parser.add_argument("--z0", type=float, default=DEFAULT_FILTER["z0"])
    parser.add_argument("--k", type=int, default=DEFAULT_FILTER["k"])
    parser.add_argument("--duration-window", type=int,
                        default=DEFAULT_FILTER["window_s"])
    parser.add_argument("--duration-fraction", type=float,
                        default=DEFAULT_FILTER["fraction"])
    parser.add_argument("--reps", nargs="+", type=int, default=None)
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)

    arms = (RESOURCE, SLI) if args.arms == "both" else (args.arms,)
    thresholds = {RESOURCE: args.threshold_resource, SLI: args.threshold_sli}
    modes = {"off": (), "coherence": ("coherence",), "duration": ("duration",),
             "both": ("coherence", "duration")}[args.filter]
    alarm_filter = None if not modes else AlarmFilter(
        modes=modes, z0=args.z0, k=args.k, window_s=args.duration_window,
        fraction=args.duration_fraction)
    run(args.suite, args.split, arms, thresholds, args.data_dir, args.out, args.reps,
        alarm_filter)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
