"""数据审计（AC-1/AC-2 的数据侧）：索引标签、注入时间、指标族覆盖。

用法::

    python offline/rca/audit.py --suite re1-ss
    python offline/rca/audit.py --suite re1-ob
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import pandas as pd

from dataset import candidate_services, load_case, load_index, metric_family, select_cases

ROOT = Path(__file__).resolve().parent
DEFAULT_DATA = ROOT / "data"


def _pre_window(rows: pd.DataFrame, data_dir: Path) -> dict:
    """注入前可用秒数（报告必须写清两套数据的前窗差异）。"""
    lengths = []
    for _, row in rows.head(25).iterrows():
        metrics = pd.read_parquet(data_dir / str(row["case"]) / "metrics.parquet",
                                  columns=["time"])
        inject = int(row["inject_time"])
        lengths.append(int(metrics.loc[metrics["time"] < inject, "time"].count()))
    return {"sampled_cases": len(lengths), "min": min(lengths), "max": max(lengths)}


def audit_suite(data_dir: Path, suite: str) -> tuple[list[str], dict]:
    index = load_index(data_dir)
    rows = select_cases(index, suite, "all")
    problems: list[str] = []
    stats = {
        "suite": suite,
        "cases": len(rows),
        "repetitions": sorted(rows["repetition"].unique().tolist()),
        "services": sorted(rows["root_cause_service"].unique().tolist()),
        "faults": sorted(rows["fault"].unique().tolist()),
        "families": {},
    }
    if len(rows) != 125:
        problems.append(f"case count {len(rows)} != 125")
    if len(rows["case"].unique()) != len(rows):
        problems.append("duplicate case names in index")

    family_counts: dict[str, list[int]] = {}
    nan_cases = 0
    nan_cells = 0
    for _, row in rows.iterrows():
        name = str(row["case"])
        try:
            case = load_case(data_dir, row)
        except (FileNotFoundError, ValueError) as exc:
            problems.append(f"{name}: {exc}")
            continue

        expected_inject = int(row["inject_time"])
        if case.inject_time != expected_inject:
            problems.append(
                f"{name}: inject_time {case.inject_time} != index {expected_inject}"
            )

        candidates = candidate_services(case.metrics)
        if case.root_cause_service not in candidates:
            problems.append(f"{name}: truth {case.root_cause_service} not in telemetry")

        n_timesteps = int(row["n_timesteps"])
        if len(case.metrics) != n_timesteps:
            problems.append(f"{name}: timesteps {len(case.metrics)} != index {n_timesteps}")
        n_metrics = int(row["n_metrics"])
        if len(case.metrics.columns) - 1 != n_metrics:
            problems.append(
                f"{name}: metric columns {len(case.metrics.columns) - 1} != index {n_metrics}"
            )
        if len(case.normal) != int(row["normal_timesteps"]):
            problems.append(f"{name}: normal rows {len(case.normal)} != index "
                            f"{int(row['normal_timesteps'])}")
        if len(case.faulty) != int(row["faulty_timesteps"]):
            problems.append(f"{name}: faulty rows {len(case.faulty)} != index "
                            f"{int(row['faulty_timesteps'])}")
        nan_count = int(case.metrics.isna().sum().sum())
        if nan_count:
            nan_cases += 1
            nan_cells += nan_count
        normal = case.normal.drop(columns=["time"], errors="ignore")
        for family in ("resource", "sli"):
            columns = [c for c in normal.columns if metric_family(c) == family]
            if columns and normal[columns].isna().all().all():
                problems.append(f"{name}: all {family} columns NaN in normal window")

        families = {metric_family(c) for c in case.metrics.columns if c != "time"}
        for family in ("resource", "sli"):
            family_counts.setdefault(family, []).append(int(family in families))
            if family not in families:
                problems.append(f"{name}: no {family} metric")

    stats["families"] = {
        family: {"cases_with_family": sum(values), "of": len(values)}
        for family, values in family_counts.items()
    }
    stats["nan"] = {"cases": nan_cases, "cells": nan_cells, "of_cases": len(rows)}
    stats["pre_window_s"] = _pre_window(rows, data_dir)
    return problems, stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Audit RCAEval telemetry against its index")
    parser.add_argument("--suite", default="re1-ss", choices=["re1-ob", "re1-ss"])
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    args = parser.parse_args(argv)

    problems, stats = audit_suite(args.data_dir, args.suite)
    print(f"suite={stats['suite']} cases={stats['cases']} "
          f"reps={stats['repetitions']} faults={stats['faults']}")
    print(f"services={stats['services']}")
    print(f"pre_window={stats['pre_window_s']}")
    print(f"nan={stats['nan']}")
    for family, value in stats["families"].items():
        print(f"{family}: {value['cases_with_family']}/{value['of']} cases have >=1 column")
    if problems:
        print("FAIL:\n  " + "\n  ".join(problems[:30]), file=sys.stderr)
        if len(problems) > 30:
            print(f"  ... {len(problems) - 30} more", file=sys.stderr)
        return 2
    print("OK: labels, inject_time, timesteps and metric families consistent")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
