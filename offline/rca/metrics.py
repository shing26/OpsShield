"""排序评测指标：Top-k / MRR / bootstrap CI。"""
from __future__ import annotations

import random
from statistics import mean
from typing import Callable, Sequence


def rank_position(ranking: Sequence[str], truth: str) -> int:
    """真值在排序中的 1-based 位置；不在候选里返回 len+1。"""
    try:
        return list(ranking).index(truth) + 1
    except ValueError:
        return len(ranking) + 1


def top_k(positions: Sequence[int], k: int) -> float:
    if not positions:
        raise ValueError("empty positions")
    return sum(1 for p in positions if p <= k) / len(positions)


def mrr(positions: Sequence[int]) -> float:
    if not positions:
        raise ValueError("empty positions")
    return mean(1.0 / p for p in positions)


def bootstrap_ci(values: Sequence[float], stat: Callable[[Sequence[float]], float] = mean,
                 n: int = 2000, seed: int = 7, alpha: float = 0.05) -> tuple[float, float]:
    """ Percentile bootstrap；固定 seed，保证同一输入重跑一致。"""
    if not values:
        raise ValueError("empty values")
    rng = random.Random(seed)
    samples = []
    size = len(values)
    for _ in range(n):
        draw = [values[rng.randrange(size)] for _ in range(size)]
        samples.append(stat(draw))
    samples.sort()
    lo = samples[int((alpha / 2) * (n - 1))]
    hi = samples[int((1 - alpha / 2) * (n - 1))]
    return lo, hi
