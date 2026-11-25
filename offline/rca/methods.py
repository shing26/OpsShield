"""排序方法注册表。

M0：random。
M1：static-threshold / 3sigma / baro（BARO 口径的 robust-z 复现）。
M2：本方法在此追加。
"""
from __future__ import annotations

import random
from typing import Protocol, Sequence

import numpy as np
import pandas as pd

from dataset import Case, to_service
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


class BaroRanker:
    """BARO 的逐指标 robust-z 复现（MAD/IQR 口径，取窗口内最大 z）。"""

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
    """阈值告警基线：只看 SLI（error / latency）是否越过正常窗基线。

    误差项按"出现即报"计分；时延项按相对正常窗 p99 的超出比例计分。
    没有越界的服务得 0 分——这正是阈值告警"只看门面"的局限。
    """

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
    """本方法：材料性下限 + 持续性 + 拓扑扣减 + 时相加权。

    三个机制各自解决一个已知失败模式（见 plans/rca-benchmark-accept.md §9）：

    1. **材料性下限**：z 分用 MAD 做尺度，但要求变化至少是「|中位数|+正常窗跨度」的
       8%——否则低方差服务（如 emailservice）的一次抖动就能拿到巨大 z。
    2. **持续性**：取故障窗内正偏离的 P90，而不是最大值；单点尖峰不算根因。
    3. **拓扑扣减**：若某服务的被调方资源异常更强，则该服务的分数减半——
       它的 latency 很可能只是下游症状，不是根因。
    4. **时相加权**：异常起始更早的服务加权（根因先动，症状后动）。

    正偏离而非绝对值，是 RE1 的故障类型先验：cpu/mem/disk/delay/loss 都表现为升高；
    下降通常是被调方流量减少的症状。该先验不适用于"服务宕机"类故障，报告需声明。
    """

    name = "topo-first"
    materiality_floor = 0.08
    persistence_q = 90
    sli_weight = 0.25
    callee_discount = 0.5
    first_mover_boost = 0.3
    onset_z = 3.0

    def rank(self, case: Case, candidates: Sequence[str], seed: int) -> list[str]:
        normal = case.normal
        faulty = case.faulty
        columns = [c for c in normal.columns if c != "time" and c in faulty.columns]
        if not columns:
            return list(candidates)

        base = normal[columns].to_numpy(dtype=float)
        current = faulty[columns].to_numpy(dtype=float)
        median = np.median(base, axis=0)
        mad = 1.4826 * np.median(np.abs(base - median), axis=0)
        span = np.percentile(base, 95, axis=0) - np.percentile(base, 5, axis=0)
        floor = self.materiality_floor * (np.abs(median) + span + 1e-9)
        scale = np.maximum.reduce([mad, floor, np.full_like(median, 1e-9)])

        signed = (current - median) / scale
        positive = np.maximum(signed, 0.0)
        persistent = np.percentile(positive, self.persistence_q, axis=0)

        strong = np.abs(signed) > self.onset_z
        onset = np.where(strong.any(axis=0), np.argmax(strong, axis=0), len(current))
        duration = max(len(current) - 1, 1)

        resource: dict[str, float] = {}
        sli: dict[str, float] = {}
        onset_by_service: dict[str, int] = {}
        for idx, column in enumerate(columns):
            service = to_service(column)
            if column.endswith(("_cpu", "_mem", "_diskio")):
                resource[service] = max(resource.get(service, 0.0), float(persistent[idx]))
            elif column.endswith(("_latency", "_error")):
                sli[service] = max(sli.get(service, 0.0), float(persistent[idx]))
            onset_by_service[service] = min(onset_by_service.get(service, duration + 1),
                                            int(onset[idx]))

        scores: dict[str, float] = {}
        for service in set(resource) | set(sli):
            score = resource.get(service, 0.0) + self.sli_weight * sli.get(service, 0.0)
            if any(resource.get(down, 0.0) > max(resource.get(service, 0.0), 1e-9)
                   for down in callees(service)):
                score *= self.callee_discount
            start = onset_by_service.get(service, duration)
            if start < duration:
                score *= 1.0 + self.first_mover_boost * (1.0 - start / duration)
            scores[service] = score

        return [s for s, _ in sorted(scores.items(), key=lambda item: (-item[1], item[0]))]


METHODS: dict[str, Ranker] = {
    RandomRanker.name: RandomRanker(),
    BaroRanker.name: BaroRanker(),
    ThreeSigmaRanker.name: ThreeSigmaRanker(),
    StaticThresholdRanker.name: StaticThresholdRanker(),
    TopoFirstRanker.name: TopoFirstRanker(),
}
