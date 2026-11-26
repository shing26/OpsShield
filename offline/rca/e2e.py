"""端到端配对分析（AC-7）：只读一次运行产出的 ``detection.csv``，不重新检测。

用法::

    python offline/rca/e2e.py --dir offline/rca/reports/<detection_run>
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd

from paired import compare

METHODS = ("dual-arm", "topo-first", "3sigma")


def analyze(run_dir: Path) -> dict:
    frame = pd.read_csv(run_dir / "detection.csv")
    rows = [
        {"method": method, "case": row["case"], "rank": int(row[f"{method}_rank"])}
        for _, row in frame.iterrows()
        for method in METHODS
        if f"{method}_rank" in frame.columns
    ]
    methods = [m for m in METHODS if f"{m}_rank" in frame.columns]
    payload = {
        "run": run_dir.name,
        "n_cases": len(frame),
        "methods": methods,
        "paired": {},
    }
    for treatment in ("dual-arm", "topo-first"):
        if treatment not in methods or "3sigma" not in methods:
            continue
        payload["paired"][f"{treatment}_vs_3sigma"] = {
            f"top{k}": compare(rows, treatment, "3sigma", k) for k in (1, 3)
        }
    return payload


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="End-to-end paired analysis (AC-7)")
    parser.add_argument("--dir", type=Path, required=True)
    args = parser.parse_args(argv)

    payload = analyze(args.dir)
    (args.dir / "e2e_paired.json").write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    print(f"run={payload['run']} cases={payload['n_cases']}")
    for name, stats in payload["paired"].items():
        top1 = stats["top1"]
        print(f"{name}: top1 diff={top1['diff']:+.3f} "
              f"CI95=[{top1['ci95'][0]:+.3f},{top1['ci95'][1]:+.3f}] "
              f"only_t={top1['treatment_only']} only_c={top1['control_only']} "
              f"p={top1['p']:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
