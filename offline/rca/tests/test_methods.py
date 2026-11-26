import numpy as np
import pandas as pd

from dataset import Case, candidate_services
from methods import (BaroRanker, MadZRanker, StaticThresholdRanker,
                     ThreeSigmaRanker, conf_rrf_scores)


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
    for method in (BaroRanker(), MadZRanker(), ThreeSigmaRanker()):
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


def _equal_rrf(resource: dict[str, float], sli: dict[str, float], k: int = 60) -> dict:
    res_rank = {s: i + 1 for i, s in enumerate(sorted(resource, key=lambda s: (-resource[s], s)))}
    sli_rank = {s: i + 1 for i, s in enumerate(sorted(sli, key=lambda s: (-sli[s], s)))}
    size = len(set(resource) | set(sli))
    return {s: 1.0 / (k + res_rank.get(s, size + 1))
            + 1.0 / (k + sli_rank.get(s, size + 1))
            for s in set(resource) | set(sli)}


def test_conf_rrf_weights_the_confident_arm_but_keeps_rank_ordering():
    # Truth (adservice) is #1 on SLI but #3 on resource; frontend is #1 on resource, #2 on SLI.
    resource = {"frontend": 25.0, "cartservice": 20.0, "adservice": 15.0}
    sli = {"adservice": 500.0, "frontend": 200.0, "cartservice": 1.0}
    equal = _equal_rrf(resource, sli)
    conf = conf_rrf_scores(resource, sli)
    assert max(equal, key=equal.get) == "frontend"
    assert max(conf, key=conf.get) == "adservice"
