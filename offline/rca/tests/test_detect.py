import numpy as np
import pandas as pd

from dataset import Case
from detect import counts_as_detection, detect_case, detected_rank, summarize_alarm


def _case(inject: int = 700, fault: bool = True) -> Case:
    rng = np.random.default_rng(11)
    n = 900
    cpu = rng.normal(10, 0.2, n)
    if fault:
        cpu[inject:] += 20
    return Case(
        name="synthetic",
        root_cause_service="adservice",
        fault="cpu",
        repetition=1,
        inject_time=inject,
        metrics=pd.DataFrame({"time": np.arange(n), "adservice_cpu": cpu}),
    )


def test_detects_step_change_near_injection():
    result = detect_case(_case())
    assert result["detected"] is True
    assert 0 <= result["latency_s"] <= 5


def test_flat_series_does_not_alarm():
    result = detect_case(_case(fault=False))
    assert result["alarm_time"] is None
    assert result["detected"] is False
    assert result["false_alarm"] is False


def test_missing_detection_scores_as_a_miss():
    assert detected_rank([], "adservice", 14) == 15
    assert detected_rank(["adservice"], "adservice", 14) == 1


def test_false_alarm_is_not_a_detection():
    assert counts_as_detection({"alarm_time": 90, "false_alarm": True}) is False
    assert counts_as_detection({"alarm_time": None, "false_alarm": False}) is False
    assert counts_as_detection({"alarm_time": 110, "false_alarm": False}) is True


def test_alarm_after_horizon_is_not_a_detection():
    late = summarize_alarm(450, 100)
    assert late["latency_s"] == 350
    assert late["detected"] is False
    assert counts_as_detection(late) is False


def test_sli_arm_catches_latency_only_fault():
    rng = np.random.default_rng(29)
    n = 900
    inject = 700
    latency = rng.normal(0.05, 0.002, n)
    latency[inject:] += 0.05
    case = Case(
        name="sli-only",
        root_cause_service="orders",
        fault="delay",
        repetition=1,
        inject_time=inject,
        metrics=pd.DataFrame({
            "time": np.arange(n),
            "orders_latency-90": latency,
            "orders_cpu": rng.normal(10, 0.2, n),
        }),
        system="sock-shop",
    )
    resource = detect_case(case, arms=("resource",), thresholds={"resource": 8.0})
    sli = detect_case(case, arms=("sli",), thresholds={"sli": 8.0})
    both = detect_case(case, arms=("resource", "sli"),
                       thresholds={"resource": 8.0, "sli": 8.0})
    assert resource["detected"] is False
    assert sli["detected"] is True
    assert both["detected"] is True
