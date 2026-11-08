"""采样脚本的抓取体解析：门禁 6 的 metrics_delta 全靠这一步，算错就整份证据失真。

为什么单独锁：`aiops_requests_failed_total` 按 `reason` 打标签，抓取体里是多条序列。
只取最后一条会把其它 reason 桶漏掉——"5xx=0"是门禁 6 的硬判据，这一漏就是假绿。
"""
from __future__ import annotations

import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent.parent / "scripts"
sys.path.insert(0, str(SCRIPTS))

import live_concurrency as lc  # noqa: E402

SCRAPE = """\
# HELP aiops_requests_total 总请求
# TYPE aiops_requests_total counter
aiops_requests_total 80.0
# HELP aiops_requests_failed_total 失败请求
# TYPE aiops_requests_failed_total counter
aiops_requests_failed_total{reason="RuntimeException"} 2.0
aiops_requests_failed_total{reason="unknown"} 1.0
aiops_requests_failed_total_created{reason="unknown"} 1.7e9
# TYPE aiops_degradation_state gauge
aiops_degradation_state 1.0
aiops_degradation_state 0.0
aiops_unrelated_metric 42.0
"""


def test_labeled_counters_are_summed_not_overwritten():
    m = lc.parse_metrics(SCRAPE)
    assert m["aiops_requests_failed_total"] == 3.0, "两个 reason 桶必须相加（2+1）"


def test_unlabeled_counter_is_read_as_is():
    assert lc.parse_metrics(SCRAPE)["aiops_requests_total"] == 80.0


def test_gauge_takes_the_last_sample_not_the_sum():
    assert lc.parse_metrics(SCRAPE)["aiops_degradation_state"] == 0.0


def test_created_series_and_unrelated_metrics_are_ignored():
    m = lc.parse_metrics(SCRAPE)
    assert "aiops_requests_failed_total_created" not in m
    assert "aiops_unrelated_metric" not in m


def test_missing_series_stays_absent():
    m = lc.parse_metrics("aiops_requests_total 1.0\n")
    assert "aiops_requests_failed_total" not in m, "没抓到就是没有，不补 0"


def test_malformed_line_does_not_crash_and_later_value_wins():
    m = lc.parse_metrics(
        "aiops_llm_calls_total{reason=\"x\"} not-a-number\n"
        "aiops_llm_calls_total{reason=\"y\"} 4.0\n")
    assert m["aiops_llm_calls_total"] == 4.0
