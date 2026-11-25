import numpy as np
import pandas as pd

from dataset import Case, candidate_services
from methods import BaroRanker, StaticThresholdRanker, ThreeSigmaRanker


def _case(adservice_faulty: bool = True) -> Case:
    rng = np.random.default_rng(3)
    n = 60
    injected = np.arange(n) >= 30
    ad = rng.normal(10, 0.5, n)
    if adservice_faulty:
        ad[injected] += 25
    metrics = pd.DataFrame({
        "time": np.arange(n),
        "adservice_cpu": ad,
        "frontend_latency": rng.normal(0.1, 0.005, n) + (0.02 if adservice_faulty else 0),
        "frontend_error": np.zeros(n),
    })
    return Case(
        name="synthetic",
        root_cause_service="adservice",
        fault="cpu",
        repetition=1,
        inject_time=30,
        metrics=metrics,
    )


def test_baro_and_3sigma_find_the_injected_service():
    case = _case()
    candidates = candidate_services(case.metrics)
    for method in (BaroRanker(), ThreeSigmaRanker()):
        ranking = method.rank(case, candidates, seed=7)
        assert ranking[0] == "adservice", (method.name, ranking)
        assert sorted(ranking) == sorted(candidates)


def test_baro_handles_zero_iqr_without_nan():
    case = _case(adservice_faulty=False)
    case.metrics["constant_cpu"] = 1.0
    ranking = BaroRanker().rank(case, candidate_services(case.metrics), seed=7)
    assert len(ranking) == len(set(ranking))


def test_static_threshold_only_ranks_sli_violations():
    case = _case(adservice_faulty=False)
    case.metrics.loc[30:, "frontend_error"] = 0.2
    ranking = StaticThresholdRanker().rank(case, candidate_services(case.metrics), seed=7)
    assert ranking[0] == "frontend"
    assert ranking[-1] == "adservice"
