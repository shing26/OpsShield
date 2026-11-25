"""无泄漏断言：排序方法只能看到遥测与候选集，看不到标签。"""
from pathlib import Path

import pandas as pd

from dataset import Case, candidate_services
from methods import RandomRanker


def _case(truth: str) -> Case:
    metrics = pd.DataFrame({
        "time": list(range(10)),
        "adservice_cpu": [1.0] * 10,
        "frontend_latency": [0.1] * 10,
    })
    return Case(
        name="same_case",
        root_cause_service=truth,
        fault="cpu",
        repetition=1,
        inject_time=5,
        metrics=metrics,
    )


def test_random_ranking_is_invariant_to_truth():
    a = RandomRanker().rank(_case("adservice"), candidate_services(_case("adservice").metrics), 7)
    b = RandomRanker().rank(_case("frontend"), candidate_services(_case("frontend").metrics), 7)
    assert a == b


def test_candidates_exclude_label_and_time_fields():
    case = _case("adservice")
    names = candidate_services(case.metrics)
    assert "time" not in names
    assert "root_cause_service" not in names
    assert "fault" not in names
    assert "adservice" in names and "frontend" in names
