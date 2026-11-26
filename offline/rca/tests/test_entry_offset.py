import pandas as pd
import pytest

from dataset import MIN_FAULTY_SPAN_S, Case


def _case(inject_time=100, last_time=400):
    metrics = pd.DataFrame({
        "time": list(range(0, last_time + 1, 1)),
        "svc_cpu": [0.0] * (last_time + 1),
    })
    return Case(
        name="case_a",
        root_cause_service="svc",
        fault="cpu",
        repetition=1,
        inject_time=inject_time,
        metrics=metrics,
    )


def test_default_offset_keeps_original_boundary():
    case = _case()
    assert case.entry_time == 100
    assert case.normal["time"].max() == 99
    assert case.faulty["time"].min() == 100


def test_entry_offset_shifts_boundary_without_touching_labels():
    case = _case().with_entry_offset(120)
    assert case.entry_time == 220
    assert case.normal["time"].max() == 219
    assert case.faulty["time"].min() == 220
    assert case.root_cause_service == "svc"
    assert case.fault == "cpu"
    assert case.inject_time == 100


def test_entry_offset_must_be_non_negative():
    with pytest.raises(ValueError):
        _case().with_entry_offset(-1)


def test_faulty_span_matches_observed_window():
    case = _case(last_time=400).with_entry_offset(100)
    assert case.faulty_span_s == 200.0


def test_miss_rule_uses_time_span_not_row_count():
    assert MIN_FAULTY_SPAN_S == 60
    exact = _case(inject_time=100, last_time=160)
    assert exact.faulty_span_s == 60.0
    assert exact.has_faulty_evidence is True

    short = _case(inject_time=100, last_time=159)
    assert short.faulty_span_s == 59.0
    assert short.has_faulty_evidence is False


def test_offset_past_telemetry_is_a_miss():
    case = _case(inject_time=100, last_time=200).with_entry_offset(1000)
    assert case.faulty.empty
    assert case.faulty_span_s == 0.0
    assert case.has_faulty_evidence is False
