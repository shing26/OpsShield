from pathlib import Path

import pandas as pd
import pytest

from dataset import (
    candidate_services,
    load_case,
    load_index,
    select_cases,
    to_service,
)


def _write_case(root: Path, name: str, truth: str, inject: int = 105) -> None:
    case_dir = root / name
    case_dir.mkdir(parents=True)
    metrics = pd.DataFrame({
        "time": list(range(100, 111)),
        "adservice_cpu": [1.0] * 5 + [9.0] * 6,
        "frontend_latency": [0.1] * 11,
        "frontend-external_error": [0.0] * 11,
    })
    metrics.to_parquet(case_dir / "metrics.parquet", index=False)
    (case_dir / "inject_time.txt").write_text(str(inject), encoding="utf-8")


def test_inject_time_split_is_strict(tmp_path: Path):
    _write_case(tmp_path, "case_a", "adservice")
    row = pd.Series({
        "case": "case_a",
        "root_cause_service": "adservice",
        "fault": "cpu",
        "repetition": 1,
    })
    case = load_case(tmp_path, row)
    assert len(case.normal) == 5
    assert len(case.faulty) == 6
    assert case.normal["time"].max() < case.inject_time
    assert case.faulty["time"].min() == case.inject_time
    assert "time" not in candidate_services(case.metrics)


def test_to_service_matches_rcaeval_convention():
    assert to_service("adservice_cpu") == "adservice"
    assert to_service("frontend-external_error") == "frontend-external"
    assert to_service("PassthroughCluster_load") == "PassthroughCluster"
    assert to_service("ts-auth-service_cpu") == "ts-auth-service"
    assert to_service("ts-travel-service_latency-90") == "ts-travel-service"


def test_select_cases_uses_repetition_split(tmp_path: Path):
    index = pd.DataFrame({
        "case": [f"c{i}" for i in range(1, 6)],
        "dataset": ["RE1-OB"] * 5,
        "root_cause_service": ["adservice"] * 5,
        "fault": ["cpu"] * 5,
        "repetition": [1, 2, 3, 4, 5],
    })
    index.to_parquet(tmp_path / "cases.parquet", index=False)
    loaded = load_index(tmp_path)
    assert select_cases(loaded, "re1-ob", "dev")["repetition"].tolist() == [1, 2, 3]
    assert select_cases(loaded, "re1-ob", "test")["repetition"].tolist() == [4, 5]


def test_load_index_rejects_missing_columns(tmp_path: Path):
    pd.DataFrame({"case": ["x"]}).to_parquet(tmp_path / "cases.parquet", index=False)
    with pytest.raises(ValueError):
        load_index(tmp_path)
