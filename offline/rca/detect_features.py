"""A1 检测新特征：C 跨服务一致性 / D 持续时间 / P 调用链上下文。

三个判据只读在线 z 与拓扑，不读 ``root_cause_service`` / ``fault`` / case 名。
P 只产出相关系数供报告使用，不参与报警判定（TT 没有调用图）。
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from dataset import to_service
from topology import callees

P_WINDOW_S = 120


def service_anomaly(z: pd.DataFrame, z0: float) -> pd.DataFrame:
    """每个时间点、每个服务是否“任一指标 z >= z0”。"""
    if z.empty:
        return pd.DataFrame(index=z.index)
    by_service: dict[str, list[str]] = {}
    for column in z.columns:
        by_service.setdefault(to_service(column), []).append(column)
    return pd.DataFrame(
        {service: (z[columns] >= z0).any(axis=1)
         for service, columns in by_service.items()},
        index=z.index,
    )


def coherence_pass(anomaly: pd.DataFrame, k: int) -> pd.Series:
    """C：同一时刻异常服务数 >= k。"""
    if anomaly.empty:
        return pd.Series(dtype=bool)
    return anomaly.sum(axis=1) >= k


def _time_index(index: pd.Index) -> pd.Index:
    return pd.to_timedelta(pd.Index(index).astype("int64"), unit="s")


def duration_fraction(flags: pd.Series, window_s: int) -> pd.Series:
    """D 的分子：最近 ``window_s`` 秒内异常时间点占比（时间加权）。"""
    if flags.empty:
        return flags.astype(float)
    series = flags.astype(float).set_axis(_time_index(flags.index))
    fraction = series.rolling(f"{int(window_s)}s", min_periods=1).mean()
    fraction.index = flags.index
    return fraction


def duration_pass(flags: pd.Series, window_s: int, fraction: float) -> pd.Series:
    """D：最近 ``window_s`` 秒内异常占比 >= ``fraction``。"""
    return duration_fraction(flags, window_s) >= fraction


def service_score(z: pd.DataFrame, service: str) -> pd.Series:
    columns = [c for c in z.columns if to_service(c) == service]
    if not columns:
        return pd.Series(dtype=float)
    return z[columns].max(axis=1)


def topology_correlation(z: pd.DataFrame, service: str, alarm_time: int,
                         system: str, window_s: int = P_WINDOW_S,
                         min_points: int = 3) -> float | None:
    """P：报警服务与其直接 callee 的平均得分在窗口内的 Pearson 相关。"""
    neighbors = sorted(callees(service, system))
    if not neighbors:
        return None
    own = service_score(z, service)
    if own.empty:
        return None
    neighbor_scores = [service_score(z, neighbor) for neighbor in neighbors]
    neighbor_scores = [score for score in neighbor_scores if not score.empty]
    if not neighbor_scores:
        return None
    reference = pd.concat(neighbor_scores, axis=1).mean(axis=1)

    mask = (own.index >= alarm_time - window_s) & (own.index <= alarm_time)
    left = own[mask].to_numpy(dtype=float)
    right = reference.reindex(own.index)[mask].to_numpy(dtype=float)
    if len(left) < min_points:
        return None
    if np.nanstd(left) == 0 or np.nanstd(right) == 0:
        return None
    value = float(np.corrcoef(left, right)[0, 1])
    return None if np.isnan(value) else value
