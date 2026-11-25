import pytest

from metrics import bootstrap_ci, mrr, rank_position, top_k


def test_rank_position_is_one_based_and_missing_is_len_plus_one():
    assert rank_position(["a", "b", "c"], "a") == 1
    assert rank_position(["a", "b", "c"], "c") == 3
    assert rank_position(["a", "b", "c"], "z") == 4


def test_top_k_and_mrr_known_values():
    positions = [1, 2, 3]
    assert top_k(positions, 1) == pytest.approx(1 / 3)
    assert top_k(positions, 3) == pytest.approx(1.0)
    assert mrr(positions) == pytest.approx((1 + 0.5 + 1 / 3) / 3)


def test_bootstrap_is_deterministic_and_brackets_mean():
    values = [1.0, 0.0, 1.0, 0.0, 1.0]
    first = bootstrap_ci(values, n=500, seed=11)
    second = bootstrap_ci(values, n=500, seed=11)
    assert first == second
    lo, hi = first
    assert lo <= sum(values) / len(values) <= hi


def test_empty_inputs_raise():
    with pytest.raises(ValueError):
        top_k([], 1)
    with pytest.raises(ValueError):
        bootstrap_ci([])
