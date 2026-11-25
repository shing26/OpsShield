"""把一次运行的 results.csv 与 summary.json 做一致性校验，并渲染表格。

``--check`` 不信任 summary.json：从逐 case 结果重算 Top-1/Top-3/MRR 与 CI，
任何一项对不上即退出码 2。
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pandas as pd

from metrics import bootstrap_ci, mrr, top_k

ROOT = Path(__file__).resolve().parent
DEFAULT_OUT = ROOT / "reports"


def latest_run(out_dir: Path) -> Path:
    runs = sorted((p for p in out_dir.iterdir() if (p / "results.csv").exists()),
                  key=lambda p: p.name)
    if not runs:
        raise SystemExit(f"no runs under {out_dir}")
    return runs[-1]


def recompute(results: pd.DataFrame, seed: int) -> dict:
    computed = {}
    for method, rows in results.groupby("method", sort=True):
        positions = rows["rank"].astype(int).tolist()
        computed[method] = {
            "top1": top_k(positions, 1),
            "top1_ci95": list(bootstrap_ci([1.0 if p <= 1 else 0.0 for p in positions], seed=seed)),
            "top3": top_k(positions, 3),
            "top3_ci95": list(bootstrap_ci([1.0 if p <= 3 else 0.0 for p in positions], seed=seed)),
            "mrr": mrr(positions),
            "mrr_ci95": list(bootstrap_ci([1.0 / p for p in positions], seed=seed)),
            "mean_rank": sum(positions) / len(positions),
        }
    return computed


def check(run_dir: Path) -> int:
    results = pd.read_csv(run_dir / "results.csv")
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    seed = int(summary["seed"])
    computed = recompute(results, seed)
    problems = []
    if set(computed) != set(summary["methods"]):
        problems.append(f"method set differs: {sorted(computed)} vs {sorted(summary['methods'])}")
    for method, values in computed.items():
        recorded = summary["methods"].get(method, {})
        if len(results[results["method"] == method]) != summary["n_cases"]:
            problems.append(f"{method}: case count != summary.n_cases")
        for key, value in values.items():
            if key not in recorded:
                problems.append(f"{method}.{key} missing in summary")
                continue
            if isinstance(value, list):
                if any(abs(a - b) > 1e-12 for a, b in zip(value, recorded[key])):
                    problems.append(f"{method}.{key}: {value} != {recorded[key]}")
            elif abs(float(value) - float(recorded[key])) > 1e-12:
                problems.append(f"{method}.{key}: {value} != {recorded[key]}")
    if problems:
        print("FAIL:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 2
    print(f"OK: {run_dir.name} ({summary['n_cases']} cases, {len(computed)} methods)")
    return 0


def render(run_dir: Path) -> str:
    summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
    lines = [
        f"run: `{run_dir.name}`  suite={summary['suite']} split={summary['split']} "
        f"cases={summary['n_cases']} seed={summary['seed']}",
        "",
        "| method | Top-1 | Top-3 | MRR | mean rank |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for method, stats in sorted(summary["methods"].items(),
                                key=lambda item: -item[1]["top1"]):
        lines.append(
            f"| {method} | {stats['top1']:.3f} | {stats['top3']:.3f} | "
            f"{stats['mrr']:.3f} | {stats['mean_rank']:.2f} |"
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate or render one RCA run")
    parser.add_argument("--dir", type=Path, default=None,
                        help="run directory; defaults to the latest under offline/rca/reports")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(argv)
    run_dir = args.dir or latest_run(DEFAULT_OUT)
    if args.check:
        return check(run_dir)
    print(render(run_dir))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
