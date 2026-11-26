"""配对比较：bootstrap 差值 CI + McNemar 精确检验。

用于 AC-4（v2 vs 3σ 的 Top-1 增益）与 AC-5（分故障非回归）的证据。
"""
from __future__ import annotations

import math
import random
from statistics import mean
from typing import Sequence


def paired_bootstrap_diff(treatment: Sequence[int], control: Sequence[int],
                          n: int = 5000, seed: int = 7,
                          alpha: float = 0.05) -> dict:
    """逐 case 配对的 Top-1 差值（treatment − control）与 percentile CI。"""
    if len(treatment) != len(control):
        raise ValueError("paired samples must have equal length")
    if not treatment:
        raise ValueError("empty samples")
    size = len(treatment)
    diffs = [a - b for a, b in zip(treatment, control)]
    rng = random.Random(seed)
    draws = []
    for _ in range(n):
        draws.append(sum(diffs[rng.randrange(size)] for _ in range(size)) / size)
    draws.sort()
    return {
        "n": size,
        "diff": mean(diffs),
        "ci95": [draws[int((alpha / 2) * (n - 1))],
                 draws[int((1 - alpha / 2) * (n - 1))]],
    }


def mcnemar_exact(treatment: Sequence[int], control: Sequence[int]) -> dict:
    """不一致对的精确二项检验（双侧）。"""
    only_t = sum(1 for a, b in zip(treatment, control) if a == 1 and b == 0)
    only_c = sum(1 for a, b in zip(treatment, control) if a == 0 and b == 1)
    n = only_t + only_c
    if n == 0:
        p = 1.0
    else:
        tail = sum(math.comb(n, i) for i in range(min(only_t, only_c) + 1))
        p = min(1.0, 2 * tail / 2 ** n)
    return {"treatment_only": only_t, "control_only": only_c, "p": p}


def compare(rows: list[dict], treatment: str, control: str, k: int = 1) -> dict:
    """``rows`` 每条含 method/case/rank；按 case 配对，缺失即报错。"""
    by_method: dict[str, dict[str, int]] = {}
    for row in rows:
        by_method.setdefault(row["method"], {})[row["case"]] = row["rank"]
    left, right = by_method[treatment], by_method[control]
    if set(left) != set(right):
        raise ValueError(f"case sets differ between {treatment} and {control}")
    cases = sorted(left)
    a = [1 if left[c] <= k else 0 for c in cases]
    b = [1 if right[c] <= k else 0 for c in cases]
    return {"k": k, "treatment": treatment, "control": control,
            **paired_bootstrap_diff(a, b), **mcnemar_exact(a, b)}
