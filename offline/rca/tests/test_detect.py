import numpy as np
import pandas as pd

from dataset import Case
from detect import detect_case, detected_rank


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
