import json
from pathlib import Path

import pandas as pd

from metrics import mrr
from report import check, recompute


def _write_run(root: Path, ranks: list[int]) -> Path:
    run = root / "run"
    run.mkdir()
    results = pd.DataFrame({
        "method": ["m"] * len(ranks),
        "case": [f"c{i}" for i in range(len(ranks))],
        "rank": ranks,
    })
    results.to_csv(run / "results.csv", index=False)
    summary = {
        "suite": "re1-ob",
        "split": "dev",
        "seed": 7,
        "n_cases": len(ranks),
        "methods": {"m": recompute(results, 7)["m"]},
    }
    (run / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
    return run


def test_recompute_matches_closed_form():
    results = pd.DataFrame({"method": ["m"] * 3, "rank": [1, 2, 3]})
    stats = recompute(results, 7)["m"]
    assert stats["top1"] == 1 / 3
    assert stats["top3"] == 1.0
    assert stats["mrr"] == mrr([1, 2, 3])


def test_check_accepts_consistent_run(tmp_path: Path):
    assert check(_write_run(tmp_path, [1, 2, 3])) == 0


def test_check_rejects_tampered_summary(tmp_path: Path):
    run = _write_run(tmp_path, [1, 2, 3])
    summary = json.loads((run / "summary.json").read_text(encoding="utf-8"))
    summary["methods"]["m"]["top1"] = 0.9
    (run / "summary.json").write_text(json.dumps(summary), encoding="utf-8")
    assert check(run) == 2
