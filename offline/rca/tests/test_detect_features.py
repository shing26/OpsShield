import numpy as np
import pandas as pd

import detect_features as feat
from dataset import Case
from detect import (AlarmFilter, alarm_series, detect_case,
                    filtered_first_alarm, service_alarm_series)


def _z(values: dict[str, list[float]], start: int = 0) -> pd.DataFrame:
    length = len(next(iter(values.values())))
    return pd.DataFrame(values, index=pd.Index(range(start, start + length)))


def _case(metrics: pd.DataFrame, inject_time: int = 900,
          root_cause: str = "frontend", fault: str = "cpu") -> Case:
    return Case(
        name="synthetic",
        root_cause_service=root_cause,
        fault=fault,
        repetition=1,
        inject_time=inject_time,
        metrics=metrics,
    )


def test_service_anomaly_counts_services_not_columns():
    z = _z({"a_cpu": [1, 5], "a_mem": [9, 0], "b_cpu": [0, 0]})
    anomaly = feat.service_anomaly(z, 4.0)
    assert list(anomaly.columns) == ["a", "b"]
    assert anomaly["a"].tolist() == [True, True]
    assert anomaly["b"].tolist() == [False, False]
    assert feat.coherence_pass(anomaly, 2).tolist() == [False, False]
    assert feat.coherence_pass(anomaly, 1).tolist() == [True, True]


def test_duration_is_time_weighted_not_row_counted():
    flags = pd.Series([i % 2 == 0 for i in range(120)], index=range(120))
    fraction = feat.duration_fraction(flags, 120)
    assert abs(float(fraction.iloc[-1]) - 0.5) < 1e-9
    assert bool(feat.duration_pass(flags, 120, 0.5).iloc[-1]) is True
    assert bool(feat.duration_pass(flags, 120, 0.6).iloc[-1]) is False


def test_totality_of_alarm_series_matches_per_service_frame():
    z = _z({"a_cpu": [0, 0, 9, 9, 9, 0], "b_cpu": [0, 0, 0, 0, 0, 0]})
    per_service = service_alarm_series(z, 8.0)
    assert per_service.shape[1] == 2
    assert alarm_series(z, 8.0).equals(per_service.any(axis=1))


def _spike_metrics(spike_value: float = 1000.0, length: int = 1200) -> pd.DataFrame:
    values = np.ones(length)
    values[950:955] = spike_value
    return pd.DataFrame({"time": np.arange(length), "frontend_cpu": values})


def test_filter_suppresses_single_service_transient_that_v2_detects():
    case = _case(_spike_metrics())
    plain = detect_case(case)
    assert plain["detected"] is True

    spec = AlarmFilter(modes=("coherence", "duration"), z0=4.0, k=3,
                       window_s=120, fraction=0.8)
    filtered = detect_case(case, alarm_filter=spec)
    assert filtered["detected"] is False
    assert filtered["false_alarm"] is False
    assert filtered["arms"]["resource"]["alarm_service"] is None


def test_filter_keeps_alarm_when_duration_holds():
    index = [10, 20, 30]
    alarms = pd.DataFrame({"a": [True, True, True]}, index=index)
    coherence = pd.Series([False, False, False], index=index)
    duration = {"a": pd.Series([False, True, True], index=index)}
    spec = AlarmFilter(modes=("duration",), z0=4.0, k=3, window_s=120, fraction=0.8)
    assert filtered_first_alarm(alarms, coherence, duration, spec, 0) == (20, "a")


def test_filter_keeps_alarm_when_coherence_holds():
    index = [10, 20]
    alarms = pd.DataFrame({"a": [True, True]}, index=index)
    coherence = pd.Series([True, False], index=index)
    duration = {"a": pd.Series([False, False], index=index)}
    spec = AlarmFilter(modes=("coherence",), z0=4.0, k=3, window_s=120, fraction=0.8)
    assert filtered_first_alarm(alarms, coherence, duration, spec, 0) == (10, "a")


def test_filter_does_not_read_labels():
    metrics = _spike_metrics()
    spec = AlarmFilter(modes=("coherence", "duration"), z0=4.0, k=3,
                       window_s=120, fraction=0.8)
    first = detect_case(_case(metrics, root_cause="frontend", fault="cpu"),
                        alarm_filter=spec)
    second = detect_case(_case(metrics, root_cause="adservice", fault="mem"),
                         alarm_filter=spec)
    assert first["alarm_time"] == second["alarm_time"]
    assert first["arms"]["resource"]["alarm_service"] == \
        second["arms"]["resource"]["alarm_service"]
    assert first["arms"]["resource"]["p_corr"] == \
        second["arms"]["resource"]["p_corr"]


def test_topology_correlation_requires_neighbours():
    z = _z({"redis_cpu": [1.0, 2.0, 3.0], "frontend_cpu": [1.0, 2.0, 4.0]},
           start=100)
    assert feat.topology_correlation(z, "redis", 102, "online-boutique") is None
    z2 = _z({"frontend_cpu": [1.0, 2.0, 4.0, 8.0],
             "adservice_cpu": [1.0, 2.0, 3.0, 6.0],
             "cartservice_cpu": [1.0, 2.0, 3.5, 7.0]}, start=100)
    value = feat.topology_correlation(z2, "frontend", 103, "online-boutique")
    assert value is not None and 0.0 < value <= 1.0
