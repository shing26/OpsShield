import numpy as np
import pandas as pd

from dataset import Case, candidate_services, metric_family, to_service, v1_metric_family
from methods import DualArmRanker
from topology import callees


def test_metric_family_handles_sock_shop_suffixes():
    assert metric_family("carts_latency-50") == "sli"
    assert metric_family("carts_latency-90") == "sli"
    assert metric_family("carts_error") == "sli"
    assert metric_family("carts_workload") == "traffic"
    assert metric_family("carts-db_cpu") == "resource"
    assert metric_family("front-end_cpu") == "resource"
    assert metric_family("time") == "other"


def test_v1_family_keeps_latency_quantiles_out_of_sli():
    # v1 冻结口径漏读分位列；AC-3 要求 v1 方法保持这个行为。
    assert v1_metric_family("carts_latency-90") == "other"
    assert v1_metric_family("carts_latency") == "sli"
    assert v1_metric_family("carts-db_cpu") == "resource"
    assert metric_family("carts_latency-90") == "sli"


def test_to_service_folds_sock_shop_db_and_keeps_hyphen():
    assert to_service("carts-db_cpu") == "carts"
    assert to_service("front-end_latency-90") == "front-end"
    assert to_service("orders_error") == "orders"


def test_sock_shop_topology():
    assert {"carts", "payment", "user"} <= callees("orders", "sock-shop")
    assert "orders" in callees("front-end", "sock-shop")
    assert callees("payment", "sock-shop") == frozenset()


def _case(rows: list[dict], truth: str, inject: int = 50) -> Case:
    return Case(
        name="synthetic-ss",
        root_cause_service=truth,
        fault="delay",
        repetition=1,
        inject_time=inject,
        metrics=pd.DataFrame(rows),
        system="sock-shop",
    )


def test_dual_arm_uses_sli_when_only_latency_moves():
    rng = np.random.default_rng(17)
    n = 100
    injected = np.arange(n) >= 50
    latency = rng.normal(0.05, 0.002, n)
    latency[injected] += 0.05
    cpu = rng.normal(10, 0.2, n)
    case = _case({
        "time": np.arange(n),
        "orders_latency-90": latency,
        "carts_cpu": cpu,
        "orders_cpu": cpu.copy(),
    }, truth="orders")
    ranking = DualArmRanker().rank(case, candidate_services(case.metrics), seed=7)
    assert ranking[0] == "orders", ranking


def test_dual_arm_uses_resource_when_only_cpu_moves():
    rng = np.random.default_rng(19)
    n = 100
    injected = np.arange(n) >= 50
    cpu = rng.normal(10, 0.2, n)
    cpu[injected] += 10
    latency = rng.normal(0.05, 0.002, n)
    case = _case({
        "time": np.arange(n),
        "carts_cpu": cpu,
        "orders_latency-90": latency,
    }, truth="carts")
    ranking = DualArmRanker().rank(case, candidate_services(case.metrics), seed=7)
    assert ranking[0] == "carts", ranking


def test_dual_arm_discounts_caller_sli_when_callee_is_stronger():
    rng = np.random.default_rng(23)
    n = 100
    injected = np.arange(n) >= 50
    carts_cpu = rng.normal(10, 0.2, n)
    carts_cpu[injected] += 10
    orders_latency = rng.normal(0.05, 0.002, n)
    orders_latency[injected] += 0.05
    case = _case({
        "time": np.arange(n),
        "carts_cpu": carts_cpu,
        "orders_latency-90": orders_latency,
    }, truth="carts")
    ranking = DualArmRanker().rank(case, candidate_services(case.metrics), seed=7)
    assert ranking[0] == "carts", ranking


def test_dual_arm_treats_all_nan_column_as_no_evidence():
    rng = np.random.default_rng(31)
    n = 100
    injected = np.arange(n) >= 50
    cpu = rng.normal(10, 0.2, n)
    cpu[injected] += 10
    case = _case({
        "time": np.arange(n),
        "carts_cpu": cpu,
        "orders_error": np.full(n, np.nan),
        "orders_cpu": rng.normal(10, 0.2, n),
    }, truth="carts")
    ranking = DualArmRanker().rank(case, candidate_services(case.metrics), seed=7)
    assert ranking[0] == "carts", ranking
    assert set(ranking) == {"carts", "orders"}
