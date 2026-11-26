"""RCA 评测入口（M0）。

用法::

    python offline/rca/run.py --split dev --methods random --seed 7
"""
from __future__ import annotations

import argparse
import csv
import json
from datetime import datetime, timezone
from pathlib import Path

from dataset import candidate_services, load_case, load_index, select_cases
from methods import METHODS
from metrics import bootstrap_ci, mrr, rank_position, top_k
from paired import compare

ROOT = Path(__file__).resolve().parent
DEFAULT_DATA = ROOT / "data"
DEFAULT_OUT = ROOT / "reports"


def evaluate(suite: str, split: str, method_names: list[str], seed: int,
             data_dir: Path, out_dir: Path, reps: list[int] | None = None,
             entry_offset: int = 0) -> dict:
    index = load_index(data_dir)
    rows = select_cases(index, suite, split, reps)
    if rows.empty:
        raise SystemExit(f"no cases for {suite}/{split}")

    cases = [load_case(data_dir, row) for _, row in rows.iterrows()]
    if entry_offset:
        cases = [case.with_entry_offset(entry_offset) for case in cases]
    usable = {case.name: case.has_faulty_evidence for case in cases}
    miss_cases = sorted(name for name, ok in usable.items() if not ok)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = out_dir / f"{stamp}_{suite}_{split}_entry{entry_offset}s"
    out_dir.mkdir(parents=True, exist_ok=True)

    results = []
    positions_by_method: dict[str, list[tuple[str, str, str, int]]] = {}
    candidate_counts = [len(candidate_services(case.metrics)) for case in cases]
    summary = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "suite": suite,
        "split": split,
        "reps": reps,
        "seed": seed,
        "entry_offset": entry_offset,
        "n_cases": len(cases),
        "n_candidates": {"min": min(candidate_counts), "max": max(candidate_counts)},
        "miss_cases": miss_cases,
        "methods": {},
    }

    for method_name in method_names:
        method = METHODS[method_name]
        positions = []
        for case in cases:
            candidates = candidate_services(case.metrics)
            if usable[case.name]:
                ranking = method.rank(case, candidates, seed)
                position = rank_position(ranking, case.root_cause_service)
            else:
                ranking = []
                position = len(candidates) + 1
            positions.append(position)
            positions_by_method.setdefault(method_name, []).append(
                (case.name, case.fault, case.root_cause_service, position)
            )
            results.append({
                "method": method_name,
                "case": case.name,
                "fault": case.fault,
                "repetition": case.repetition,
                "truth": case.root_cause_service,
                "predicted": ranking[0] if ranking else "",
                "rank": position,
                "n_candidates": len(candidates),
                "entry_offset": entry_offset,
                "miss": int(not usable[case.name]),
                "top1": int(position <= 1),
                "top3": int(position <= 3),
            })

        top1_values = [1.0 if p <= 1 else 0.0 for p in positions]
        top3_values = [1.0 if p <= 3 else 0.0 for p in positions]
        summary["methods"][method_name] = {
            "n_cases": len(positions),
            "top1": top_k(positions, 1),
            "top1_ci95": list(bootstrap_ci(top1_values, seed=seed)),
            "top3": top_k(positions, 3),
            "top3_ci95": list(bootstrap_ci(top3_values, seed=seed)),
            "mrr": mrr(positions),
            "mrr_ci95": list(bootstrap_ci([1.0 / p for p in positions], seed=seed)),
            "mean_rank": sum(positions) / len(positions),
            "by_fault": _subset_stats(positions_by_method[method_name], 1),
            "by_service": _subset_stats(positions_by_method[method_name], 2),
            "misses": len(miss_cases),
        }

    summary["paired"] = {}
    for treatment, control in (("dual-arm", "3sigma"), ("dual-arm-rrf", "3sigma"),
                               ("topo-first", "3sigma")):
        if treatment in summary["methods"] and control in summary["methods"]:
            summary["paired"][f"{treatment}_vs_{control}"] = {
                f"top{k}": compare(results, treatment, control, k) for k in (1, 3)
            }

    with (out_dir / "results.csv").open("w", newline="", encoding="utf-8") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(results[0]))
        writer.writeheader()
        writer.writerows(results)
    (out_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    print(f"cases={len(cases)} misses={len(miss_cases)} "
          f"candidates={summary['n_candidates']['min']}-"
          f"{summary['n_candidates']['max']} offset={entry_offset}s -> {out_dir}")
    print(f"{'method':<12}{'top1':>8}{'top3':>8}{'mrr':>8}{'mean_rank':>11}")
    for name, stats in summary["methods"].items():
        print(f"{name:<12}{stats['top1']:>8.3f}{stats['top3']:>8.3f}{stats['mrr']:>8.3f}{stats['mean_rank']:>11.2f}")
    return summary


def _subset_stats(rows: list[tuple[str, str, str, int]], field: int) -> dict:
    """按 fault（field=1）或 truth service（field=2）分组统计。"""
    groups: dict[str, list[int]] = {}
    for row in rows:
        groups.setdefault(row[field], []).append(row[3])
    return {
        key: {"n": len(values), "top1": top_k(values, 1), "top3": top_k(values, 3),
              "mrr": mrr(values)}
        for key, values in sorted(groups.items())
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="RCA benchmark runner")
    parser.add_argument("--suite", default="re1-ob")
    parser.add_argument("--split", default="dev", choices=["dev", "test", "all"])
    parser.add_argument("--methods", nargs="+", default=["random"],
                        choices=sorted(METHODS) + ["all"])
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--reps", nargs="+", type=int, default=None,
                        help="only these repetitions (e.g. --reps 1 for smoke)")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--entry-offset", nargs="+", type=int, default=[0],
                        help="seconds to delay the known detection start (default 0)")
    args = parser.parse_args(argv)
    methods = list(METHODS) if "all" in args.methods else args.methods
    summaries = []
    for offset in args.entry_offset:
        if offset < 0:
            raise SystemExit("--entry-offset must be >= 0")
        summaries.append(evaluate(args.suite, args.split, methods, args.seed,
                                  args.data_dir, args.out, args.reps, offset))

    if len(summaries) > 1:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        sweep = {
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "suite": args.suite,
            "split": args.split,
            "seed": args.seed,
            "offsets": [
                {
                    "offset": s["entry_offset"],
                    "n_cases": s["n_cases"],
                    "misses": len(s["miss_cases"]),
                    "methods": {
                        name: {
                            "top1": stats["top1"],
                            "top3": stats["top3"],
                            "mrr": stats["mrr"],
                            "by_fault": {
                                fault: fault_stats["top1"]
                                for fault, fault_stats in stats["by_fault"].items()
                            },
                        }
                        for name, stats in s["methods"].items()
                    },
                }
                for s in summaries
            ],
        }
        args.out.mkdir(parents=True, exist_ok=True)
        path = args.out / f"{stamp}_entry-sweep_{args.suite}_{args.split}.json"
        path.write_text(json.dumps(sweep, indent=2, ensure_ascii=False) + "\n",
                        encoding="utf-8")
        print(f"sweep -> {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
