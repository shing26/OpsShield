"""V2-2：自适应融合定位的 dev 选参（只用 rep 1–3）。

硬约束（契约 §3.4）：任一故障类型的 Top-1 不得比 3σ 低超过 5pp；
在此前提下最大化聚合 Top-1。测试集不参与。

用法::

    python offline/rca/tune_rank.py --suite re1-ss --guard re1-ob
"""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path

from dataset import candidate_services, load_case, load_index, select_cases
from methods import DualArmRanker, ThreeSigmaRanker
from metrics import mrr, rank_position, top_k

ROOT = Path(__file__).resolve().parent
DEFAULT_DATA = ROOT / "data"
DEFAULT_OUT = ROOT / "reports"

GRID = {
    "fusion": ("weighted", "rrf", "max"),
    "tau_res": (3.0, 5.0, 8.0),
    "tau_sli": (3.0, 5.0, 8.0),
    "callee_discount": (0.5, 0.8),
    "first_mover_boost": (0.3, 0.0),
}
MAX_FAULT_DROP = 0.05
DEFAULT_CFG = {
    "fusion": "weighted", "tau_res": 3.0, "tau_sli": 3.0,
    "callee_discount": 0.5, "first_mover_boost": 0.3,
}


def configs() -> list[dict]:
    keys = list(GRID)
    return [dict(zip(keys, values)) for values in itertools.product(*(GRID[k] for k in keys))]


def _ranker(cfg: dict) -> DualArmRanker:
    ranker = DualArmRanker()
    for key, value in cfg.items():
        setattr(ranker, key, value)
    return ranker


def _scores(rows: list[dict]) -> dict:
    positions = [r["rank"] for r in rows]
    by_fault: dict[str, list[int]] = {}
    by_service: dict[str, list[int]] = {}
    for row in rows:
        by_fault.setdefault(row["fault"], []).append(row["rank"])
        by_service.setdefault(row["truth"], []).append(row["rank"])
    return {
        "top1": top_k(positions, 1),
        "top3": top_k(positions, 3),
        "mrr": mrr(positions),
        "mean_rank": sum(positions) / len(positions),
        "by_fault": {f: top_k(p, 1) for f, p in sorted(by_fault.items())},
        "by_service": {s: top_k(p, 1) for s, p in sorted(by_service.items())},
    }


def evaluate(suite: str, grid: list[dict], data_dir: Path) -> dict:
    rows = select_cases(load_index(data_dir), suite, "dev")
    cases = [load_case(data_dir, row) for _, row in rows.iterrows()]
    baseline = ThreeSigmaRanker()
    base_rows = []
    for case in cases:
        candidates = candidate_services(case.metrics)
        base_rows.append({
            "case": case.name,
            "truth": case.root_cause_service,
            "fault": case.fault,
            "rank": rank_position(baseline.rank(case, candidates, seed=7),
                                  case.root_cause_service),
        })
    out = {"baseline_3sigma": _scores(base_rows), "configs": {}}
    for cfg in grid:
        ranker = _ranker(cfg)
        result_rows = []
        for case in cases:
            candidates = candidate_services(case.metrics)
            ranking = ranker.rank(case, candidates, seed=7)
            result_rows.append({
                "case": case.name,
                "truth": case.root_cause_service,
                "fault": case.fault,
                "rank": rank_position(ranking, case.root_cause_service),
            })
        out["configs"][json.dumps(cfg, sort_keys=True)] = {
            "config": cfg,
            **_scores(result_rows),
        }
    return out


def fault_deltas(result: dict, baseline: dict) -> dict:
    return {fault: value - baseline["by_fault"].get(fault, 0.0)
            for fault, value in result["by_fault"].items()}


def select(primary: dict, min_fault_delta: float = -MAX_FAULT_DROP) -> dict | None:
    """分故障非回归优先；在合规集合里最大化聚合 Top-1。"""
    baseline = primary["baseline_3sigma"]
    eligible = []
    for item in primary["configs"].values():
        deltas = fault_deltas(item, baseline)
        if min(deltas.values()) < min_fault_delta:
            continue
        eligible.append((item, min(deltas.values())))
    if not eligible:
        return None
    def rank_key(pair: tuple[dict, float]):
        item, worst = pair
        distance = sum(1 for key, value in DEFAULT_CFG.items()
                       if item["config"][key] != value)
        return (round(item["top1"], 12), round(worst, 12), -distance,
                round(item["mrr"], 12))

    # Top-1 相同（同分同 worst-fault）时保留默认参数，避免对 0.002 MRR 这类噪声调参。
    return max(eligible, key=rank_key)[0]


def _line(item: dict, baseline: dict) -> str:
    cfg = item["config"]
    deltas = fault_deltas(item, baseline)
    worst = min(deltas.values())
    return (f"{cfg['fusion']:<8} tr={cfg['tau_res']:<3} ts={cfg['tau_sli']:<3} "
            f"disc={cfg['callee_discount']:<3} boost={cfg['first_mover_boost']:<3} | "
            f"top1={item['top1']:.3f} top3={item['top3']:.3f} mrr={item['mrr']:.3f} "
            f"worst_fault={worst:+.3f}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Dual-arm ranking parameter sweep (dev only)")
    parser.add_argument("--suite", default="re1-ss")
    parser.add_argument("--guard", default="re1-ob")
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args(argv)

    grid = configs()
    primary = evaluate(args.suite, grid, args.data_dir)
    guard = evaluate(args.guard, grid, args.data_dir)
    chosen = select(primary)

    payload = {
        "suite": args.suite,
        "guard": args.guard,
        "grid": {k: list(v) for k, v in GRID.items()},
        "n_configs": len(grid),
        "selection_rule": "all fault types >= 3sigma - 5pp on primary dev; "
                          "maximize top1, then mrr, then worst-fault delta",
        "chosen": chosen,
        "primary": primary,
        "guard_results": guard,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    path = args.out / "V2-rank-sweep.json"
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n",
                    encoding="utf-8")

    baseline = primary["baseline_3sigma"]
    print(f"{args.suite} dev: {len(grid)} configs; 3sigma "
          f"top1={baseline['top1']:.3f} by_fault={baseline['by_fault']}")
    ranked = sorted(primary["configs"].values(),
                    key=lambda item: -item["top1"])
    for item in ranked[:10]:
        print("  " + _line(item, baseline))
    if chosen is None:
        print("NO CONFIG satisfies per-fault delta >= -5pp", flush=True)
        return 2
    print("CHOSEN " + _line(chosen, baseline))
    print("  guard " + _line(guard["configs"][json.dumps(chosen["config"], sort_keys=True)],
                             guard["baseline_3sigma"]))
    print(f"-> {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
