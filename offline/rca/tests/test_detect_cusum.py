import numpy as np
import pandas as pd

import detect as v2
import detect_cusum as cusum
from dataset import Case


def _z(values: dict[str, list[float]], start: int = 0) -> pd.DataFrame:
    length = len(next(iter(values.values())))
    return pd.DataFrame(values, index=pd.Index(range(start, start + length)))


def _case(cpu: np.ndarray, inject: int = 700,
          root_cause: str = "adservice", fault: str = "cpu") -> Case:
    return Case(
        name="synthetic",
        root_cause_service=root_cause,
        fault=fault,
        repetition=1,
        inject_time=inject,
        metrics=pd.DataFrame({"time": np.arange(len(cpu)), "adservice_cpu": cpu}),
    )


def test_cusum_alarm_accumulates_then_resets():
    z = _z({"a_cpu": [1.0, 1.0, 1.0, 0.0, 0.0]})
    alarms = cusum.cusum_alarm_frame(z, k=0.5, h=1.5)
    assert alarms["a_cpu"].tolist() == [False, False, True, False, False]


def test_cusum_service_requires_min_metrics():
    z = _z({"a_cpu": [1.0, 1.0, 1.0], "a_mem": [0.0, 0.0, 0.0]})
    two = cusum.cusum_service_alarms(z, k=0.5, h=1.5, min_metrics=2)
    one = cusum.cusum_service_alarms(z, k=0.5, h=1.5, min_metrics=1)
    assert two["a"].tolist() == [False, False, False]
    assert one["a"].tolist() == [False, False, True]


def test_cusum_detects_sustained_shift_that_v2_threshold_misses():
    rng = np.random.default_rng(11)
    cpu = rng.normal(10.0, 0.2, 1200)
    cpu[700:] += 3.0
    case = _case(cpu)

    plain = v2.detect_case(case)
    assert plain["detected"] is False

    result = cusum.detect_case(case, config=cusum.CusumConfig(k=1.0, h=6.0))
    assert result["detected"] is True
    assert 0 <= result["latency_s"] <= 5


def test_cusum_ignores_label_fields():
    rng = np.random.default_rng(13)
    cpu = rng.normal(10.0, 0.2, 1200)
    cpu[700:] += 3.0
    first = cusum.detect_case(_case(cpu, root_cause="adservice", fault="cpu"))
    second = cusum.detect_case(_case(cpu, root_cause="frontend", fault="mem"))
    assert first == second


def test_cusum_status_separates_false_alarm_and_late():
    assert cusum.classify({"alarm_time": None}, 700) == "none"
    assert cusum.classify({"alarm_time": 699}, 700) == "false_alarm"
    assert cusum.classify({"alarm_time": 700}, 700) == "detected"
    assert cusum.classify({"alarm_time": 1000}, 700) == "detected"
    assert cusum.classify({"alarm_time": 1001}, 700) == "late"


def test_cusum_grid_is_pre_registered_and_default_is_inside():
    grid = cusum.configs()
    assert len(grid) == 16
    assert cusum.CusumConfig() in grid
