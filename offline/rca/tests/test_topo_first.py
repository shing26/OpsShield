import numpy as np
import pandas as pd

from dataset import Case, candidate_services
from methods import TopoFirstRanker


def _case(rows: list[dict], inject: int = 50) -> Case:
    return Case(
        name="synthetic",
        root_cause_service="productcatalogservice",
        fault="cpu",
        repetition=1,
        inject_time=inject,
        metrics=pd.DataFrame(rows),
    )


def test_topology_discounts_caller_when_callee_is_stronger():
    rng = np.random.default_rng(5)
    n = 100
    injected = np.arange(n) >= 50
    product_cpu = rng.normal(10, 0.3, n)
    product_cpu[injected] += 20
    checkout_latency = rng.normal(0.05, 0.002, n)
    checkout_latency[injected] += 0.5
    case = _case({
        "time": np.arange(n),
        "productcatalogservice_cpu": product_cpu,
        "checkoutservice_latency": checkout_latency,
    })
    candidates = candidate_services(case.metrics)

    class NoDiscount(TopoFirstRanker):
        callee_discount = 1.0

    assert NoDiscount().rank(case, candidates, seed=7)[0] == "checkoutservice"
    assert TopoFirstRanker().rank(case, candidates, seed=7)[0] == "productcatalogservice"


def test_first_mover_breaks_equal_scores():
    n = 100
    early = np.zeros(n)
    early[50:60] = 10
    late = np.zeros(n)
    late[70:90] = 10
    # 用不同的正常窗跨度抵消材料性下限差异，使两服务持续分相同
    case = _case({
        "time": np.arange(n),
        "adservice_cpu": np.r_[np.zeros(50), early[50:]] + 1.0,
        "currencyservice_cpu": np.r_[np.zeros(50), late[50:]] + 1.0,
    })
    ranking = TopoFirstRanker().rank(case, candidate_services(case.metrics), seed=7)
    assert ranking[0] == "adservice", ranking


def test_ranking_is_a_permutation_and_deterministic():
    rng = np.random.default_rng(9)
    n = 80
    case = _case({
        "time": np.arange(n),
        "adservice_cpu": rng.normal(5, 0.2, n),
        "frontend_latency": rng.normal(0.1, 0.01, n),
    })
    candidates = candidate_services(case.metrics)
    method = TopoFirstRanker()
    first = method.rank(case, candidates, seed=7)
    second = method.rank(case, candidates, seed=7)
    assert first == second
    assert sorted(first) == sorted(candidates)
