"""AC-3 护栏自身的测试：比对器必须能抓住 rank 与聚合值漂移。"""
import pandas as pd

from regression import REPORT_TABLE, compare_cases, compare_table


def _frame(ranks: list[int]) -> pd.DataFrame:
    return pd.DataFrame({
        "method": ["topo-first"] * len(ranks),
        "case": [f"c{i}" for i in range(len(ranks))],
        "rank": ranks,
        "predicted": ["svc"] * len(ranks),
        "top1": [int(r <= 1) for r in ranks],
        "top3": [int(r <= 3) for r in ranks],
        "n_candidates": [10] * len(ranks),
    })


def test_compare_cases_detects_rank_drift():
    assert compare_cases(_frame([1, 2]), _frame([1, 2])) == []
    problems = compare_cases(_frame([1, 2]), _frame([1, 4]))
    assert any("rank" in p for p in problems)
    assert any("top3" in p for p in problems)


def test_compare_cases_detects_missing_rows():
    problems = compare_cases(_frame([1]), _frame([1, 2]))
    assert any("only in reference" in p for p in problems)


def test_compare_table_matches_report_strings():
    summary = {"methods": {
        name: {"top1": float(t1), "top3": float(t3), "mrr": float(mrr)}
        for name, (t1, t3, mrr) in REPORT_TABLE.items()
    }}
    assert compare_table(summary) == []
    summary["methods"]["3sigma"]["top1"] = 0.681
    assert any("3sigma" in p for p in compare_table(summary))
