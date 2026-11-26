"""V2-1：双检测臂的 dev 选参（只用 rep 1–3，不碰测试集）。

对每个候选 ``(threshold_resource, threshold_sli, sustain, min_metrics)``
在两套 dev 上重放检测，输出覆盖率/误报/延迟。选择规则见 ``select()``。

用法::

    python offline/rca/tune_detect.py --suite re1-ss --guard re1-ob
"""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path

import numpy as np

from dataset import load_case, load_index, select_cases
from detect import (RESOURCE, SLI, alarm_series, first_alarm, summarize_alarm,
                    z_frame)

ROOT = Path(__file__).resolve().parent
DEFAULT_DATA = ROOT / "data"
DEFAULT_OUT = ROOT / "reports"

# 契约 §3.2 固定：材料性下限 8%、连续 3 点越线、服务级「任一指标越线」。
# 唯一可调项是两臂阈值；允许范围在 dev 上选择。
GRID = {
    "threshold_resource": (8.0, 12.0, 20.0, 30.0),
    "threshold_sli": (8.0, 12.0, 20.0, 30.0, 50.0),
    "sustain": (3,),
    "min_metrics": (1,),
}


def configs() -> list[dict]:
    keys = list(GRID)
    return [dict(zip(keys, values)) for values in itertools.product(*(GRID[k] for k in keys))]


def _metrics(results: list[dict]) -> dict:
    detected = [r for r in results if r["detected"]]
    latencies = [r["latency_s"] for r in detected]
    return {
        "coverage": len(detected) / len(results),
        "false_alarm_rate": sum(1 for r in results if r["false_alarm"]) / len(results),
        "no_alarm_rate": sum(1 for r in results if r["alarm_time"] is None) / len(results),
        "latency_median_s": float(np.median(latencies)) if latencies else None,
        "latency_p90_s": float(np.percentile(latencies, 90)) if latencies else None,
    }


def sweep(suite: str, data_dir: Path, grid: list[dict]) -> dict:
    """一过式：case 外层、config 内层，z 帧只算一次。"""
    rows = select_cases(load_index(data_dir), suite, "dev")
    cases = [load_case(data_dir, row) for _, row in rows.iterrows()]
    collected = {json.dumps(cfg, sort_keys=True): {"combined": [], "resource": [],
                                                   "sli": []} for cfg in grid}

    for case in cases:
        zr = z_frame(case, RESOURCE)
        zs = z_frame(case, SLI)
        for cfg in grid:
            key = json.dumps(cfg, sort_keys=True)
            per_arm = {}
            earliest = None
            for arm, z, threshold in ((RESOURCE, zr, cfg["threshold_resource"]),
                                      (SLI, zs, cfg["threshold_sli"])):
                alarm = first_alarm(
                    alarm_series(z, threshold, cfg["sustain"], cfg["min_metrics"]),
                    case.inject_time,
                )
                result = summarize_alarm(alarm, case.inject_time)
                per_arm[arm] = result
                collected[key][arm].append(result)
                if alarm is not None and (earliest is None or alarm < earliest):
                    earliest = alarm
            collected[key]["combined"].append(summarize_alarm(earliest, case.inject_time))

    out = {}
    for cfg in grid:
        key = json.dumps(cfg, sort_keys=True)
        bucket = collected[key]
        out[key] = {
            "config": cfg,
            "n_cases": len(bucket["combined"]),
            "combined": _metrics(bucket["combined"]),
            "resource": _metrics(bucket["resource"]),
            "sli": _metrics(bucket["sli"]),
        }
    return out


def select(results: dict, min_coverage: float = 0.80,
           max_false_alarm: float = 0.05) -> dict | None:
    """覆盖率 ≥ 下限、误报 ≤ 上限；先最大化覆盖，再最小化误报，再最小化 P90。

    指标完全相同时取更低的阈值（更敏感），避免无依据地选更保守的一端。
    """
    eligible = [
        item for item in results.values()
        if item["combined"]["coverage"] >= min_coverage
        and item["combined"]["false_alarm_rate"] <= max_false_alarm
    ]
    if not eligible:
        return None
    return max(eligible, key=lambda item: (
        item["combined"]["coverage"],
        -item["combined"]["false_alarm_rate"],
        -(item["combined"]["latency_p90_s"] or 1e9),
        -item["config"]["threshold_resource"],
        -item["config"]["threshold_sli"],
    ))


def _fmt(row: dict) -> str:
    cfg, comb = row["config"], row["combined"]
    return (f"r={cfg['threshold_resource']:>4.1f} s={cfg['threshold_sli']:>4.1f} "
            f"sus={cfg['sustain']:>2} min={cfg['min_metrics']} | "
            f"cov={comb['coverage']:.3f} fa={comb['false_alarm_rate']:.3f} "
            f"med={comb['latency_median_s'] or -1:>5.0f} p90={comb['latency_p90_s'] or -1:>5.0f}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Dual-arm detection parameter sweep (dev only)")
    parser.add_argument("--suite", default="re1-ss")
    parser.add_argument("--guard", default="re1-ob")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)

    grid = configs()
    primary = sweep(args.suite, args.data_dir, grid)
    guard = sweep(args.guard, args.data_dir, grid)
    chosen = select(primary)

    payload = {
        "suite": args.suite,
        "guard": args.guard,
        "grid": {k: list(v) for k, v in GRID.items()},
        "n_configs": len(grid),
        "selection_rule": "coverage>=0.80 and false_alarm<=0.05 on primary dev; "
                          "maximize coverage, then minimize FA, then minimize p90",
        "chosen": chosen,
        "primary": primary,
        "guard_results": guard,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    path = args.out / "V2-detect-sweep.json"
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8")

    print(f"{args.suite} dev: {len(grid)} configs")
    ranked = sorted(primary.values(), key=lambda item: (
        -item["combined"]["coverage"], item["combined"]["false_alarm_rate"]))
    for row in ranked[:10]:
        print("  " + _fmt(row))
    if chosen is None:
        print("NO CONFIG meets coverage>=0.80 and FA<=0.05", flush=True)
    else:
        key = json.dumps(chosen["config"], sort_keys=True)
        print("CHOSEN " + _fmt(chosen))
        print("  guard " + _fmt(guard[key]))
    print(f"-> {path}")
    return 0 if chosen else 2


if __name__ == "__main__":
    raise SystemExit(main())
