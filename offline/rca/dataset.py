"""RCAEval case 解析与候选服务构造。

标签侧的字段（root_cause_service / fault）只在评分时使用，不进入特征。
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import pandas as pd

REQUIRED_INDEX_COLUMNS = (
    "case",
    "dataset",
    "root_cause_service",
    "fault",
    "repetition",
)

SYSTEMS = {
    "ob": "online-boutique",
    "ss": "sock-shop",
    "tt": "train-ticket",
}

SPLIT_REPS = {
    "dev": (1, 2, 3),
    "test": (4, 5),
    "all": (1, 2, 3, 4, 5),
}


@dataclass(frozen=True)
class Case:
    name: str
    root_cause_service: str
    fault: str
    repetition: int
    inject_time: int
    metrics: pd.DataFrame
    system: str = "online-boutique"

    @property
    def normal(self) -> pd.DataFrame:
        return self.metrics[self.metrics["time"] < self.inject_time]

    @property
    def faulty(self) -> pd.DataFrame:
        return self.metrics[self.metrics["time"] >= self.inject_time]


def load_index(data_dir: Path) -> pd.DataFrame:
    path = data_dir / "cases.parquet"
    if not path.exists():
        raise FileNotFoundError(f"missing index: {path}")
    frame = pd.read_parquet(path)
    missing = [c for c in REQUIRED_INDEX_COLUMNS if c not in frame.columns]
    if missing:
        raise ValueError(f"index missing columns: {missing}")
    return frame


def select_cases(index: pd.DataFrame, suite: str, split: str = "dev",
                 reps: list[int] | None = None) -> pd.DataFrame:
    if split not in SPLIT_REPS:
        raise ValueError(f"unknown split {split!r}; choose from {sorted(SPLIT_REPS)}")
    label = suite.upper()
    rows = index[index["dataset"] == label]
    rows = rows[rows["repetition"].isin(SPLIT_REPS[split])]
    if reps:
        rows = rows[rows["repetition"].isin(reps)]
    return rows.sort_values("case").reset_index(drop=True)


def to_service(column: str) -> str:
    """按 RCAEval 的口径把 ``<service>_<indicator>`` 折叠为服务名。

    从最后一个 ``_`` 切分，兼容 Train Ticket 的 ``ts-auth-service_cpu``；
    对 Online Boutique / Sock Shop 的列名与旧的 ``split("_")[0]`` 逐列等价。
    """
    return column.rsplit("_", 1)[0].replace("-db", "")


def metric_family(column: str) -> str:
    """把指标列归入 resource / sli / traffic / other。

    Sock Shop 用 ``carts_latency-90`` 与 ``carts_workload``；Online Boutique 用
    ``adservice_latency`` 与 ``adservice_load``。两者都必须识别。
    """
    name = column.lower()
    if name.endswith(("_cpu", "_mem", "_diskio")):
        return "resource"
    if "_latency" in name or name.endswith("_error"):
        return "sli"
    if name.endswith(("_load", "_workload")):
        return "traffic"
    return "other"


def v1_metric_family(column: str) -> str:
    """v1 冻结口径：SLI 只认 ``*_latency`` / ``*_error``，不认 ``latency-50/-90``。

    AC-3 要求 v1 六方法与 ``M2-report.md`` 完全一致，因此 v1 方法必须保留这个
    （后来看是漏读分位列的）口径；v2 的 ``dual-arm`` 才用 ``metric_family``。
    """
    name = column.lower()
    if name.endswith(("_cpu", "_mem", "_diskio")):
        return "resource"
    if name.endswith(("_latency", "_error")):
        return "sli"
    return "other"


def candidate_services(metrics: pd.DataFrame) -> list[str]:
    """遥测中出现的服务（不含 time），按首次出现顺序去重。"""
    seen: list[str] = []
    for column in metrics.columns:
        if column == "time":
            continue
        service = to_service(column)
        if service and service not in seen:
            seen.append(service)
    return seen


def load_case(data_dir: Path, row: pd.Series) -> Case:
    name = str(row["case"])
    case_dir = data_dir / name
    metrics_path = case_dir / "metrics.parquet"
    inject_path = case_dir / "inject_time.txt"
    if not metrics_path.exists() or not inject_path.exists():
        raise FileNotFoundError(f"case incomplete: {case_dir}")

    metrics = pd.read_parquet(metrics_path).sort_values("time").reset_index(drop=True)
    if "time" not in metrics.columns:
        raise ValueError(f"case {name} has no time column")

    inject_time = int(inject_path.read_text(encoding="utf-8").strip())
    if not (metrics["time"].min() < inject_time <= metrics["time"].max()):
        raise ValueError(f"case {name}: inject_time outside telemetry range")

    system_raw = str(row.get("system", "") or "")
    return Case(
        name=name,
        root_cause_service=str(row["root_cause_service"]),
        fault=str(row["fault"]),
        repetition=int(row["repetition"]),
        inject_time=inject_time,
        metrics=metrics,
        system=SYSTEMS.get(system_raw, system_raw or "online-boutique"),
    )
