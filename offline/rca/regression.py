"""AC-3 回归护栏：v1 六个方法在 RE1-OB 测试集上必须与 ``M2-report.md`` 一致。

两个锚点：

1. 逐 case 与 v1 已提交的 ``results.csv`` 比对（method, case → rank / predicted）；
2. 聚合值与 ``M2-report.md`` 的表格字符串逐字比对（``:0.3f``）。

用法::

    python offline/rca/regression.py
    python offline/rca/regression.py --ref offline/rca/reports/20261125T201149Z_re1-ob_test
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

import pandas as pd

from run import DEFAULT_DATA, evaluate

ROOT = Path(__file__).resolve().parent
DEFAULT_REF = ROOT / "reports" / "20261125T201149Z_re1-ob_test"

V1_METHODS = ("random", "static-threshold", "3sigma", "baro", "mad-z", "topo-first")

# M2-report.md §2 测试集表格（Top-1 / Top-3 / MRR），逐字抄录。
REPORT_TABLE = {
    "3sigma": ("0.680", "0.920", "0.803"),
    "topo-first": ("0.700", "0.860", "0.796"),
    "static-threshold": ("0.200", "0.560", "0.425"),
    "baro": ("0.160", "0.860", "0.487"),
    "mad-z": ("0.020", "0.820", "0.388"),
    "random": ("0.060", "0.220", "0.217"),
}


def compare_cases(fresh: pd.DataFrame, ref: pd.DataFrame) -> list[str]:
    problems = []
    key = ["method", "case"]
    for name in ("rank", "predicted", "top1", "top3", "n_candidates"):
        if name not in fresh.columns or name not in ref.columns:
            problems.append(f"missing column: {name}")
    if problems:
        return problems

    merged = fresh.merge(ref, on=key, suffixes=("_new", "_ref"), how="outer",
                         indicator=True)
    only_new = merged[merged["_merge"] == "left_only"]
    only_ref = merged[merged["_merge"] == "right_only"]
    for _, row in only_new.iterrows():
        problems.append(f"only in new run: {row['method']}/{row['case']}")
    for _, row in only_ref.iterrows():
        problems.append(f"only in reference: {row['method']}/{row['case']}")

    both = merged[merged["_merge"] == "both"]
    for name in ("rank", "top1", "top3", "n_candidates"):
        bad = both[both[f"{name}_new"].astype(float) != both[f"{name}_ref"].astype(float)]
        for _, row in bad.iterrows():
            problems.append(
                f"{row['method']}/{row['case']}: {name} "
                f"{row[f'{name}_new']} != {row[f'{name}_ref']}"
            )
    bad_pred = both[both["predicted_new"].astype(str) != both["predicted_ref"].astype(str)]
    for _, row in bad_pred.iterrows():
        problems.append(
            f"{row['method']}/{row['case']}: predicted "
            f"{row['predicted_new']} != {row['predicted_ref']}"
        )
    return problems


def compare_table(summary: dict) -> list[str]:
    problems = []
    for method, expected in REPORT_TABLE.items():
        stats = summary["methods"].get(method)
        if stats is None:
            problems.append(f"method missing from summary: {method}")
            continue
        actual = (f"{stats['top1']:.3f}", f"{stats['top3']:.3f}", f"{stats['mrr']:.3f}")
        if actual != expected:
            problems.append(f"{method}: {actual} != M2-report {expected}")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="v1 regression guardrail (AC-3)")
    parser.add_argument("--ref", type=Path, default=DEFAULT_REF)
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    args = parser.parse_args(argv)

    if not (args.ref / "results.csv").exists():
        print(f"FAIL: reference run missing: {args.ref}", file=sys.stderr)
        return 2

    with tempfile.TemporaryDirectory() as tmp:
        summary = evaluate("re1-ob", "test", list(V1_METHODS), 7, args.data_dir,
                           Path(tmp))
        fresh_dir = next(Path(tmp).iterdir())
        fresh = pd.read_csv(fresh_dir / "results.csv")
        summary = json.loads((fresh_dir / "summary.json").read_text(encoding="utf-8"))

    ref = pd.read_csv(args.ref / "results.csv")
    ref = ref[ref["method"].isin(V1_METHODS)]
    problems = compare_cases(fresh, ref) + compare_table(summary)
    if problems:
        print("FAIL:\n  " + "\n  ".join(problems[:40]), file=sys.stderr)
        if len(problems) > 40:
            print(f"  ... {len(problems) - 40} more", file=sys.stderr)
        return 2
    print(f"OK: {len(fresh)} rows / {len(V1_METHODS)} methods match "
          f"{args.ref.name} and M2-report.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
