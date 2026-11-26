"""排序方法注册表。

v1：random / static-threshold / 3sigma / baro(IQR) / mad-z(MAD) / topo-first。
v2：dual-arm（资源臂 + SLI 臂，按显著性自适应融合）。
"""
from __future__ import annotations

import random
from dataclasses import dataclass
from typing import Protocol, Sequence

import numpy as np
import pandas as pd

from dataset import Case, metric_family, to_service, v1_metric_family
from topology import callees


class Ranker(Protocol):
    name: str

    def rank(self, case: Case, candidates: Sequence[str], seed: int) -> list[str]: ...


class RandomRanker:
    """确定性随机基线：同 seed + 同 case 名 → 同顺序。"""

    name = "random"

    def rank(self, case: Case, candidates: Sequence[str], seed: int) -> list[str]:
        rng = random.Random(f"{seed}:{case.name}")
        ranked = list(candidates)
        rng.shuffle(ranked)
        return ranked


def _drop_constant(frame: pd.DataFrame) -> pd.DataFrame:
    """复刻 RCAEval 的 ``drop_constant``：与首行比较，保留有变化的列。"""
    if frame.empty:
        return frame
    return frame.loc[:, (frame != frame.iloc[0]).any()]


def _prepared(case: Case) -> tuple[pd.DataFrame, pd.DataFrame]:
    """按 RCAEval 口径：两个窗口各自去常量列，再取交集。"""
    normal = _drop_constant(case.normal.drop(columns=["time"], errors="ignore"))
    faulty = _drop_constant(case.faulty.drop(columns=["time"], errors="ignore"))
    common = [c for c in normal.columns if c in faulty.columns]
    return normal[common], faulty[common]


def _collapse(column_scores: dict[str, float]) -> list[str]:
    """``<service>_<indicator>`` → 服务，取该服务最高分；同分按服务名稳定排序。"""
    best: dict[str, float] = {}
    for column, score in column_scores.items():
        service = to_service(column)
        best[service] = max(best.get(service, float("-inf")), score)
    return [s for s, _ in sorted(best.items(), key=lambda item: (-item[1], item[0]))]


def _rank_map(scores: dict[str, float]) -> dict[str, int]:
    ordered = [s for s, _ in sorted(scores.items(), key=lambda item: (-item[1], item[0]))]
    return {service: index + 1 for index, service in enumerate(ordered)}


@dataclass(frozen=True)
class ArmScores:
    resource: dict[str, float]
    sli: dict[str, float]
    onset: dict[str, int]
    duration: int


def service_arms(case: Case, materiality_floor: float = 0.08,
                 persistence_q: float = 90, onset_z: float = 3.0,
                 family_fn=metric_family, nan_aware: bool = False) -> ArmScores:
    """把逐指标的正向 robust-z 聚成资源臂与 SLI 臂（两个系统共用）。

    ``nan_aware=False`` 保留 v1 的 NaN 传播语义（AC-3 要求逐 case 一致）；
    v2 的 ``dual-arm`` 用 ``nan_aware=True``，把缺测当「无证据」（记 0）而不是
    让整列变 NaN。
    """
    normal = case.normal
    faulty = case.faulty
    columns = [c for c in normal.columns if c != "time" and c in faulty.columns]
    if not columns:
        return ArmScores({}, {}, {}, max(len(faulty) - 1, 1))

    base = normal[columns].to_numpy(dtype=float)
    current = faulty[columns].to_numpy(dtype=float)
    median = np.median(base, axis=0)
    mad = 1.4826 * np.median(np.abs(base - median), axis=0)
    span = np.percentile(base, 95, axis=0) - np.percentile(base, 5, axis=0)
    floor = materiality_floor * (np.abs(median) + span + 1e-9)
    scale = np.maximum.reduce([mad, floor, np.full_like(median, 1e-9)])

    signed = (current - median) / scale
    positive = np.maximum(signed, 0.0)
    if nan_aware:
        has_data = ~np.isnan(positive).all(axis=0)
        persistent = np.zeros(positive.shape[1], dtype=float)
        if has_data.any():
            persistent[has_data] = np.nanpercentile(
                positive[:, has_data], persistence_q, axis=0
            )
    else:
        persistent = np.percentile(positive, persistence_q, axis=0)

    strong = np.abs(signed) > onset_z
    onset = np.where(strong.any(axis=0), np.argmax(strong, axis=0), len(current))
    duration = max(len(current) - 1, 1)

    resource: dict[str, float] = {}
    sli: dict[str, float] = {}
    onset_by_service: dict[str, int] = {}
    for idx, column in enumerate(columns):
        service = to_service(column)
        family = family_fn(column)
        if family == "resource":
            resource[service] = max(resource.get(service, 0.0), float(persistent[idx]))
        elif family == "sli":
            sli[service] = max(sli.get(service, 0.0), float(persistent[idx]))
        onset_by_service[service] = min(onset_by_service.get(service, duration + 1),
                                        int(onset[idx]))
    return ArmScores(resource, sli, onset_by_service, duration)


class BaroRanker:
    """BARO 的逐指标 robust-z 复现（IQR 口径，取窗口内最大有符号 z）。"""

    name = "baro"

    def rank(self, case: Case, candidates: Sequence[str], seed: int) -> list[str]:
        normal, faulty = _prepared(case)
        scores: dict[str, float] = {}
        for column in normal.columns:
            base = normal[column].to_numpy(dtype=float)
            current = faulty[column].to_numpy(dtype=float)
            if not len(base) or not len(current):
                continue
            median = float(np.median(base))
            q1, q3 = np.percentile(base, [25, 75])
            iqr = float(q3 - q1)
            scale = iqr if iqr > 0 else 1.0
            scores[column] = float(np.max((current - median) / scale))
        return _collapse(scores)


class MadZRanker:
    """契约字面要求的 robust-z（MAD）：1.4826 × 中位绝对偏差，取最大有符号 z。"""

    name = "mad-z"

    def rank(self, case: Case, candidates: Sequence[str], seed: int) -> list[str]:
        normal, faulty = _prepared(case)
        scores: dict[str, float] = {}
        for column in normal.columns:
            base = normal[column].to_numpy(dtype=float)
            current = faulty[column].to_numpy(dtype=float)
            if not len(base) or not len(current):
                continue
            median = float(np.median(base))
            mad = 1.4826 * float(np.median(np.abs(base - median)))
            scale = mad if mad > 0 else 1.0
            scores[column] = float(np.max((current - median) / scale))
        return _collapse(scores)


class ThreeSigmaRanker:
    """经典 3σ：均值/标准差在正常窗拟合，故障窗取最大绝对偏离。"""

    name = "3sigma"

    def rank(self, case: Case, candidates: Sequence[str], seed: int) -> list[str]:
        normal, faulty = _prepared(case)
        scores: dict[str, float] = {}
        for column in normal.columns:
            base = normal[column].to_numpy(dtype=float)
            current = faulty[column].to_numpy(dtype=float)
            if not len(base) or not len(current):
                continue
            mean = float(np.mean(base))
            std = float(np.std(base))
            scale = std if std > 0 else 1.0
            scores[column] = float(np.max(np.abs((current - mean) / scale)))
        return _collapse(scores)


class StaticThresholdRanker:
    """阈值告警基线：只看 SLI（error / latency）是否越过正常窗基线。"""

    name = "static-threshold"

    def rank(self, case: Case, candidates: Sequence[str], seed: int) -> list[str]:
        normal = case.normal
        faulty = case.faulty
        scores = {service: 0.0 for service in candidates}
        for column in faulty.columns:
            if column == "time":
                continue
            service = to_service(column)
            if service not in scores:
                continue
            current = faulty[column].to_numpy(dtype=float)
            if not len(current):
                continue
            if column.endswith("_error"):
                severity = float(np.max(current))
            elif column.endswith("_latency"):
                base = normal[column].to_numpy(dtype=float)
                if not len(base):
                    continue
                guard = float(np.percentile(base, 99))
                severity = max(0.0, float(np.max(current)) / max(guard, 1e-9) - 1.0)
            else:
                continue
            scores[service] = max(scores[service], severity)
        return [s for s, _ in sorted(scores.items(), key=lambda item: (-item[1], item[0]))]


class TopoFirstRanker:
    """v1 方法：材料性下限 + P90 持续性 + 拓扑扣减 + 时相加权。"""

    name = "topo-first"
    materiality_floor = 0.08
    persistence_q = 90
    sli_weight = 0.25
    callee_discount = 0.5
    first_mover_boost = 0.3
    onset_z = 3.0

    def rank(self, case: Case, candidates: Sequence[str], seed: int) -> list[str]:
        arms = service_arms(case, self.materiality_floor, self.persistence_q,
                            self.onset_z, family_fn=v1_metric_family)
        scores: dict[str, float] = {}
        for service in set(arms.resource) | set(arms.sli):
            score = arms.resource.get(service, 0.0) + self.sli_weight * arms.sli.get(service, 0.0)
            if any(arms.resource.get(down, 0.0) > max(arms.resource.get(service, 0.0), 1e-9)
                   for down in callees(service, case.system)):
                score *= self.callee_discount
            start = arms.onset.get(service, arms.duration)
            if start < arms.duration:
                score *= 1.0 + self.first_mover_boost * (1.0 - start / arms.duration)
            scores[service] = score
        return [s for s, _ in sorted(scores.items(), key=lambda item: (-item[1], item[0]))]


class DualArmRanker:
    """v2 方法：资源臂 + SLI 臂，按显著性自适应融合。

    - 两臂都显著：``weighted`` 按两臂峰值加权归一化；``rrf`` 按秩融合；``max`` 取大。
    - 仅一臂显著：直接用该臂，避免另一臂的噪声顶替。
    - 都不显著：退回两臂原始分的较大者。
    - 拓扑扣减只作用于 SLI 分量（调用方的延迟可能是被调方症状）。
    """

    name = "dual-arm"
    materiality_floor = 0.08
    persistence_q = 90
    onset_z = 3.0
    tau_res = 3.0
    tau_sli = 3.0
    fusion = "weighted"  # weighted | rrf | max
    rrf_k = 60
    callee_discount = 0.5
    first_mover_boost = 0.3

    def rank(self, case: Case, candidates: Sequence[str], seed: int) -> list[str]:
        arms = service_arms(case, self.materiality_floor, self.persistence_q,
                            self.onset_z, nan_aware=True)
        resource = dict(arms.resource)
        sli = dict(arms.sli)
        for service in list(sli):
            if any(resource.get(down, 0.0) > resource.get(service, 0.0)
                   or sli.get(down, 0.0) > sli.get(service, 0.0)
                   for down in callees(service, case.system)):
                sli[service] *= self.callee_discount

        services = set(resource) | set(sli)
        max_res = max(resource.values(), default=0.0)
        max_sli = max(sli.values(), default=0.0)
        res_sig = max_res >= self.tau_res
        sli_sig = max_sli >= self.tau_sli

        if res_sig and sli_sig and self.fusion == "rrf":
            res_rank = _rank_map(resource)
            sli_rank = _rank_map(sli)
            size = len(services)
            scores = {
                service: 1.0 / (self.rrf_k + res_rank.get(service, size + 1))
                + 1.0 / (self.rrf_k + sli_rank.get(service, size + 1))
                for service in services
            }
        elif res_sig and sli_sig and self.fusion == "weighted":
            total = max_res + max_sli
            w_res, w_sli = max_res / total, max_sli / total
            scores = {
                service: w_res * (resource.get(service, 0.0) / max_res)
                + w_sli * (sli.get(service, 0.0) / max_sli)
                for service in services
            }
        elif res_sig and not sli_sig:
            scores = {service: resource.get(service, 0.0) for service in services}
        elif sli_sig and not res_sig:
            scores = {service: sli.get(service, 0.0) for service in services}
        else:
            scores = {
                service: max(resource.get(service, 0.0), sli.get(service, 0.0))
                for service in services
            }

        for service in scores:
            start = arms.onset.get(service, arms.duration)
            if start < arms.duration:
                scores[service] *= 1.0 + self.first_mover_boost * (1.0 - start / arms.duration)
        scores = {s: (0.0 if not np.isfinite(v) else v) for s, v in scores.items()}

        return [s for s, _ in sorted(scores.items(), key=lambda item: (-item[1], item[0]))]


METHODS: dict[str, Ranker] = {
    RandomRanker.name: RandomRanker(),
    BaroRanker.name: BaroRanker(),
    MadZRanker.name: MadZRanker(),
    ThreeSigmaRanker.name: ThreeSigmaRanker(),
    StaticThresholdRanker.name: StaticThresholdRanker(),
    TopoFirstRanker.name: TopoFirstRanker(),
    DualArmRanker.name: DualArmRanker(),
}
