"""选参规则本身的测试：约束不能被聚合指标绕过。"""
from tune_detect import select as select_detection
from tune_rank import select as select_ranking
from paired import compare


def _rank_item(top1: float, faults: dict[str, float], config: dict | None = None) -> dict:
    return {
        "config": config or {"fusion": "weighted", "tau_res": 3.0, "tau_sli": 3.0,
                             "callee_discount": 0.5, "first_mover_boost": 0.3},
        "top1": top1,
        "top3": top1,
        "mrr": top1,
        "by_fault": faults,
    }


def test_rank_selection_rejects_fault_regression_even_with_best_aggregate():
    primary = {
        "baseline_3sigma": {"by_fault": {"delay": 0.8, "cpu": 1.0}},
        "configs": {
            "bad": _rank_item(0.99, {"delay": 0.5, "cpu": 1.0}),
            "good": _rank_item(0.90, {"delay": 0.8, "cpu": 1.0}),
            "better": _rank_item(0.91, {"delay": 0.85, "cpu": 1.0},
                                 {"fusion": "rrf", "tau_res": 5.0, "tau_sli": 5.0,
                                  "callee_discount": 0.5, "first_mover_boost": 0.3}),
        },
    }
    chosen = select_ranking(primary)
    assert chosen["top1"] == 0.91


def test_rank_selection_keeps_default_on_exact_tie():
    tuned = {"fusion": "rrf", "tau_res": 8.0, "tau_sli": 8.0,
             "callee_discount": 0.8, "first_mover_boost": 0.0}
    primary = {
        "baseline_3sigma": {"by_fault": {"delay": 0.8}},
        "configs": {
            "tuned": _rank_item(0.90, {"delay": 0.8}, tuned),
            "default": _rank_item(0.90, {"delay": 0.8}),
        },
    }
    assert select_ranking(primary)["config"]["fusion"] == "weighted"


def test_rank_selection_returns_none_when_all_violate():
    primary = {
        "baseline_3sigma": {"by_fault": {"delay": 1.0}},
        "configs": {"bad": _rank_item(0.99, {"delay": 0.5})},
    }
    assert select_ranking(primary) is None


def _detect_item(coverage: float, fa: float, p90: float = 30.0) -> dict:
    return {"config": {"threshold_resource": 20.0, "threshold_sli": 8.0},
            "combined": {"coverage": coverage, "false_alarm_rate": fa,
                         "latency_p90_s": p90, "latency_median_s": 18.0}}


def test_detection_selection_rejects_high_false_alarm():
    results = {"a": _detect_item(0.99, 0.20), "b": _detect_item(0.85, 0.04)}
    assert select_detection(results)["combined"]["coverage"] == 0.85


def test_detection_selection_prefers_coverage_then_false_alarm():
    results = {"a": _detect_item(0.90, 0.04), "b": _detect_item(0.90, 0.01)}
    assert select_detection(results)["combined"]["false_alarm_rate"] == 0.01
    assert select_detection({}) is None


def test_paired_compare_uses_case_level_pairs():
    rows = [
        {"method": "a", "case": "c1", "rank": 1},
        {"method": "a", "case": "c2", "rank": 4},
        {"method": "b", "case": "c1", "rank": 2},
        {"method": "b", "case": "c2", "rank": 4},
    ]
    result = compare(rows, "a", "b", k=1)
    assert result["n"] == 2
    assert result["diff"] == 0.5
    assert result["treatment_only"] == 1
    assert result["control_only"] == 0
    assert result["ci95"][0] <= 0.5 <= result["ci95"][1]
