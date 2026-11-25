"""所有方法都必须对标签字段免疫（不止 random）。"""
import numpy as np
import pandas as pd
import pytest

from dataset import Case, candidate_services
from detect import detect_case
from methods import METHODS


def _case(name: str, truth: str, fault: str) -> Case:
    rng = np.random.default_rng(13)
    n = 400
    cpu = rng.normal(10, 0.3, n)
    cpu[200:] += 8
    return Case(
        name=name,
        root_cause_service=truth,
        fault=fault,
        repetition=1,
        inject_time=200,
        metrics=pd.DataFrame({"time": np.arange(n), "adservice_cpu": cpu}),
    )


@pytest.mark.parametrize("method_name", sorted(m for m in METHODS if m != "random"))
def test_methods_ignore_label_fields(method_name):
    method = METHODS[method_name]
    first = method.rank(_case("case_a", "adservice", "cpu"),
                        candidate_services(_case("case_a", "adservice", "cpu").metrics), 7)
    second = method.rank(_case("case_b", "frontend", "mem"),
                         candidate_services(_case("case_b", "frontend", "mem").metrics), 7)
    assert first == second


def test_random_ignores_truth_and_fault_but_uses_case_name():
    method = METHODS["random"]
    a = _case("case_a", "adservice", "cpu")
    b = _case("case_a", "frontend", "mem")
    assert method.rank(a, candidate_services(a.metrics), 7) == \
        method.rank(b, candidate_services(b.metrics), 7)


def test_detection_ignores_label_fields():
    assert detect_case(_case("case_a", "adservice", "cpu")) == \
        detect_case(_case("case_b", "frontend", "mem"))
