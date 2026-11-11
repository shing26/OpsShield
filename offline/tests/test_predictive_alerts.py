# -*- coding: utf-8 -*-
"""统计基线预警（ADR-0030）单元测试。

全部离线：``_no_real_network`` 把 urlopen / localapi 三个出口全打死，任何真实网络调用
都会以 AssertionError 失败——"测试不触网"因此是机械保证，不靠自觉。
"""
from __future__ import annotations

import json
import sys
import urllib.parse
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))
import predictive_alerts as pa  # noqa: E402


@pytest.fixture(autouse=True)
def _no_real_network(monkeypatch):
    def _blocked(*a, **kw):
        raise AssertionError("测试不得触网")

    monkeypatch.setattr(pa.urllib.request, "urlopen", _blocked)
    monkeypatch.setattr(pa.localapi, "login", _blocked)
    monkeypatch.setattr(pa.localapi, "get_json", _blocked)
    monkeypatch.setattr(pa.localapi, "stream_chat", _blocked)


# ---- 测试替身 -------------------------------------------------------------

class _FakeResp:
    def __init__(self, payload):
        self._payload = payload

    def read(self):
        return json.dumps(self._payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeOpener:
    """记录请求 URL，按预置 payload 返回。不发网络包。"""

    def __init__(self, payload):
        self.payload = payload
        self.urls = []

    def __call__(self, url, timeout=None):
        self.urls.append(url)
        return _FakeResp(self.payload)


def matrix(*values):
    """Prometheus matrix 响应体（单序列）。"""
    return {
        "status": "success",
        "data": {
            "resultType": "matrix",
            "result": [{"metric": {}, "values": [[1700000000 + i, str(v)]
                                                 for i, v in enumerate(values)]}],
        },
    }


class FakeClient:
    def __init__(self, series_by_expr=None, fail_exprs=()):
        self.series_by_expr = dict(series_by_expr or {})
        self.fail_exprs = set(fail_exprs)
        self.calls = []

    def query_range(self, expr, start, end, step):
        self.calls.append({"expr": expr, "start": start, "end": end, "step": step})
        if expr in self.fail_exprs:
            raise pa.PromRangeError(f"{expr} 不可达")
        return list(self.series_by_expr.get(expr, []))


class FakeApi:
    """AlertSession 的 api 面：login / get_json / stream_chat。"""

    def __init__(self, state=None, response=None, state_error=None):
        self.state = state if state is not None else {
            "runtime": {"quota": {"limit": 1000, "used": 0}}}
        self.response = response or {"status": 200, "code": "0", "meta": {}, "done": {}}
        self.state_error = state_error
        self.state_reads = 0
        self.login_calls = 0
        self.stream_calls = []

    def login(self, user):
        self.login_calls += 1
        return "tok"

    def get_json(self, path, token):
        self.state_reads += 1
        if self.state_error is not None:
            raise self.state_error
        return self.state

    def stream_chat(self, body, token, **kwargs):
        self.stream_calls.append(body)
        return self.response


def noisy_baseline(n=30, center=1.0, spread=0.1):
    """交替 ±spread：中位数=center，MAD=spread（确定、可手算）。"""
    return [center + spread if i % 2 else center - spread for i in range(n)]


def make_args(**kw):
    args = pa.build_parser().parse_args([])
    args.rules = pa.DEFAULT_RULES
    args.once = True                      # 单轮语义：consecutive 恒为 1
    args.dry_run = False
    for key, value in kw.items():
        setattr(args, key, value)
    args.lookback_seconds = pa.parse_duration_seconds(args.lookback)
    return args


RULE = pa.Rule(id="t-rule", expr="metric_x", direction="above", min_abs_delta=0.5,
               service="opspilot-gateway", metric_cn="测试指标")

P = pa.Params(min_samples=5, z_threshold=3.5, recent_points=3)


def read_ledger(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip()]


# ---- 基线数学 -------------------------------------------------------------

def test_baseline_is_median_and_median_absolute_deviation():
    med, mad = pa.compute_baseline([1, 2, 3, 4, 5])
    assert med == 3.0
    assert mad == 1.0                      # |1-3|,|2-3|,|3-3|,|4-3|,|5-3| → 中位数 1


def test_baseline_rejects_empty_history():
    with pytest.raises(ValueError):
        pa.compute_baseline([])


def test_modified_z_matches_hand_computation():
    """history 中位数 3、MAD 1；当前值 9 → z = 0.6745 * 6 / 1。"""
    rule = pa.Rule(id="z", expr="e", direction="above", min_abs_delta=0.0,
                   service="s", metric_cn="m")
    verdict = pa.evaluate(rule, [1, 2, 3, 4, 5, 9, 9, 9], P)
    assert verdict["method"] == "modified_z"
    assert verdict["median"] == 3.0
    assert verdict["mad"] == 1.0
    assert verdict["delta"] == 6.0
    assert verdict["z"] == pytest.approx(pa.MODIFIED_Z_CONST * 6.0 / 1.0)


def test_modified_z_constant_is_iglewicz_hoaglin():
    assert pa.MODIFIED_Z_CONST == 0.6745


# ---- 判定 ----------------------------------------------------------------

def test_normal_series_does_not_fire():
    verdict = pa.evaluate(RULE, noisy_baseline(30) + [1.0, 1.0, 1.0], P)
    assert verdict["fired"] is False
    assert "skip_reason" not in verdict
    assert verdict["delta"] == pytest.approx(0.0)


def test_upward_spike_fires():
    verdict = pa.evaluate(RULE, noisy_baseline(30) + [1.6] * 3, P)
    assert verdict["fired"] is True
    assert verdict["z"] > 3.5
    assert verdict["delta"] == pytest.approx(0.6)


def test_downward_drop_fires_for_below_rule():
    rule = pa.Rule(id="b", expr="e", direction="below", min_abs_delta=0.5,
                   service="s", metric_cn="m")
    verdict = pa.evaluate(rule, noisy_baseline(30) + [0.4] * 3, P)
    assert verdict["fired"] is True
    assert verdict["delta"] < 0


def test_direction_mismatch_does_not_fire():
    """above 规则遇上向下偏离：统计上显著，但方向不符——不发。"""
    verdict = pa.evaluate(RULE, noisy_baseline(30) + [0.4] * 3, P)
    assert verdict["fired"] is False
    assert verdict["z"] < -3.5


def test_abs_delta_gate_binds_even_when_z_is_huge():
    """double threshold：z 很远但绝对差不足 → 不发（噪声上的微抖不是故障）。"""
    rule = pa.Rule(id="d", expr="e", direction="above", min_abs_delta=10.0,
                   service="s", metric_cn="m")
    verdict = pa.evaluate(rule, noisy_baseline(30, spread=0.1) + [2.0] * 3, P)
    assert verdict["z"] > 3.5
    assert verdict["delta"] < 10.0
    assert verdict["fired"] is False


def test_z_gate_binds_even_when_abs_delta_is_large():
    """double threshold：绝对差够了但分布本身很宽 → 不算异常。"""
    rule = pa.Rule(id="z", expr="e", direction="above", min_abs_delta=0.1,
                   service="s", metric_cn="m")
    verdict = pa.evaluate(rule, noisy_baseline(30, spread=1.0) + [2.0] * 3, P)
    assert verdict["delta"] >= 0.1
    assert verdict["z"] < 3.5
    assert verdict["fired"] is False


def test_degenerate_mad_zero_fires_on_absolute_delta():
    """恒定基线 MAD=0：z 无定义，只认绝对差，且必须标记方法。"""
    verdict = pa.evaluate(RULE, [1.0] * 30 + [1.6] * 3, P)
    assert verdict["mad"] == 0.0
    assert verdict["z"] is None
    assert verdict["method"] == "degenerate_mad_zero"
    assert verdict["fired"] is True


def test_degenerate_mad_zero_below_delta_does_not_fire():
    verdict = pa.evaluate(RULE, [1.0] * 30 + [1.2] * 3, P)
    assert verdict["method"] == "degenerate_mad_zero"
    assert verdict["fired"] is False


def test_degenerate_never_divides_by_zero():
    """MAD=0 分支不得抛 ZeroDivisionError，也不得静默返回 fired=True。"""
    verdict = pa.evaluate(RULE, [5.0] * 40, P)
    assert verdict["fired"] is False
    assert verdict["delta"] == 0.0
    assert verdict["method"] == "degenerate_mad_zero"


def test_insufficient_samples_is_a_skip_not_a_pass():
    verdict = pa.evaluate(RULE, noisy_baseline(4) + [9.0] * 3, P)
    assert verdict["fired"] is False
    assert verdict["skip_reason"] == "insufficient_samples"
    assert verdict["total_points"] == 7


def test_empty_series_is_no_data_not_zero():
    verdict = pa.evaluate(RULE, [], P)
    assert verdict["fired"] is False
    assert verdict["skip_reason"] == "no_data"
    assert verdict["total_points"] == 0


def test_current_is_median_of_recent_points():
    """最近 3 点取中位数：单个毛刺不该把当前值拉走。"""
    verdict = pa.evaluate(RULE, noisy_baseline(30) + [1.0, 9.0, 1.6], P)
    assert verdict["current"] == pytest.approx(1.6)
    assert verdict["n"] == 30


# ---- 告警文案 ------------------------------------------------------------

def test_render_query_embeds_verifiable_numbers_and_method():
    verdict = pa.evaluate(RULE, noisy_baseline(30) + [1.6] * 3, P)
    text = pa.render_query(verdict, "6h")
    assert "t-rule" in text
    assert "1.600" in text          # current
    assert "1.000" in text          # median
    assert "0.100" in text          # mad
    assert "4.05σ" in text          # 0.6745*0.6/0.1
    assert "0.600" in text          # delta
    assert "modified_z" in text
    assert "6h" in text
    assert "测试指标" in text


def test_render_query_degenerate_labels_method_and_omits_sigma():
    verdict = pa.evaluate(RULE, [1.0] * 30 + [1.6] * 3, P)
    text = pa.render_query(verdict, "6h")
    assert "degenerate_mad_zero" in text
    assert "MAD=0（仅绝对差）" in text


def test_render_query_carries_no_error_code():
    """刻意走语义检索路：正文不带 XXXX_NN 形态的错误码。"""
    import re
    verdict = pa.evaluate(RULE, noisy_baseline(30) + [1.6] * 3, P)
    text = pa.render_query(verdict, "6h")
    assert not re.search(r"\b\d{5}_[A-Z]", text)


# ---- Prometheus 拉取面 ---------------------------------------------------

def test_query_range_hits_query_range_endpoint_with_window():
    opener = FakeOpener(matrix(1, 2, 3))
    client = pa.PromRangeClient("http://127.0.0.1:9090", opener=opener)
    from datetime import datetime, timezone
    start = datetime(2026, 11, 11, 0, 0, tzinfo=timezone.utc)
    end = datetime(2026, 11, 11, 6, 0, tzinfo=timezone.utc)
    values = client.query_range("up", start, end, 60)
    assert values == [1.0, 2.0, 3.0]
    parsed = urllib.parse.urlparse(opener.urls[0])
    assert parsed.path == "/api/v1/query_range"
    params = urllib.parse.parse_qs(parsed.query)
    assert params["query"] == ["up"]
    assert params["step"] == ["60"]
    assert int(params["end"][0]) - int(params["start"][0]) == 6 * 3600


def test_empty_result_returns_empty_list_never_zero():
    opener = FakeOpener({"status": "success",
                         "data": {"resultType": "matrix", "result": []}})
    client = pa.PromRangeClient(opener=opener)
    assert client.query_range("x", _now(), _now(), 60) == []


def test_nan_and_inf_samples_are_dropped_not_zeroed():
    opener = FakeOpener(matrix(1.0, "NaN", 2.0, "+Inf", 3.0))
    client = pa.PromRangeClient(opener=opener)
    assert client.query_range("x", _now(), _now(), 60) == [1.0, 2.0, 3.0]


def test_multi_series_result_is_rejected():
    payload = {"status": "success", "data": {"resultType": "matrix", "result": [
        {"metric": {"job": "a"}, "values": [[1, "1"]]},
        {"metric": {"job": "b"}, "values": [[1, "2"]]},
    ]}}
    client = pa.PromRangeClient(opener=FakeOpener(payload))
    with pytest.raises(pa.PromRangeError, match="聚合到单条"):
        client.query_range("x", _now(), _now(), 60)


def test_non_matrix_result_is_rejected():
    payload = {"status": "success", "data": {"resultType": "vector", "result": [
        {"metric": {}, "value": [1, "1"]}]}}
    client = pa.PromRangeClient(opener=FakeOpener(payload))
    with pytest.raises(pa.PromRangeError, match="matrix"):
        client.query_range("x", _now(), _now(), 60)


def test_error_status_raises_prom_range_error():
    client = pa.PromRangeClient(opener=FakeOpener({"status": "error"}))
    with pytest.raises(pa.PromRangeError, match="status="):
        client.query_range("x", _now(), _now(), 60)


def test_transport_failure_raises_prom_range_error():
    def boom(url, timeout=None):
        raise OSError("connection refused")

    client = pa.PromRangeClient(opener=boom)
    with pytest.raises(pa.PromRangeError, match="请求失败"):
        client.query_range("x", _now(), _now(), 60)


def test_non_loopback_target_is_refused_before_any_request():
    """出口白名单是真接线（guard 默认 localapi.assert_local），非环回在发请求前就被拒。"""
    opener = FakeOpener(matrix(1))
    client = pa.PromRangeClient("http://example.com", opener=opener)
    with pytest.raises(ValueError, match="仅允许本机"):
        client.query_range("x", _now(), _now(), 60)
    assert opener.urls == []


def test_userinfo_in_target_is_refused():
    opener = FakeOpener(matrix(1))
    client = pa.PromRangeClient("http://127.0.0.1@example.com", opener=opener)
    with pytest.raises(ValueError, match="仅允许本机"):
        client.query_range("x", _now(), _now(), 60)


def _now():
    from datetime import datetime, timezone
    return datetime(2026, 11, 11, 6, 0, tzinfo=timezone.utc)


# ---- 规则集 --------------------------------------------------------------

def test_default_rules_are_the_frozen_five():
    assert [r.id for r in pa.DEFAULT_RULES] == [
        "ttft-p99", "request-p99-success", "cache-hit-rate",
        "inflight", "sop-fallbacks-rate",
    ]
    by_id = {r.id: r for r in pa.DEFAULT_RULES}
    assert by_id["cache-hit-rate"].direction == "below"
    assert by_id["inflight"].min_abs_delta == 2.0
    assert all(r.expr.strip() for r in pa.DEFAULT_RULES)
    # 单序列约束：默认表达式必须自带聚合（否则运行期必被多序列拒掉）
    assert by_id["sop-fallbacks-rate"].expr.startswith("sum(")
    assert "by (le)" in by_id["ttft-p99"].expr


def test_rules_from_payload_round_trips():
    rules = pa.rules_from_payload([{
        "id": "r1", "expr": "up", "direction": "below",
        "min_abs_delta": 1, "service": "svc", "metric_cn": "中文",
    }])
    assert rules == (pa.Rule(id="r1", expr="up", direction="below",
                             min_abs_delta=1.0, service="svc", metric_cn="中文"),)


@pytest.mark.parametrize("payload, match", [
    ([], "非空数组"),
    ({}, "非空数组"),
    ([{"id": "", "expr": "x", "min_abs_delta": 1}], "缺 id 或 expr"),
    ([{"id": "a", "expr": "x", "min_abs_delta": 1}], None),
    ([{"id": "a", "expr": "x", "min_abs_delta": 1, "direction": "sideways"}], "direction 非法"),
    ([{"id": "a", "expr": "x", "min_abs_delta": "abc"}], "min_abs_delta 非法"),
    ([{"id": "a", "expr": "x", "min_abs_delta": -1}], "不能为负"),
])
def test_rules_from_payload_rejects_bad_input(payload, match):
    if match is None:
        assert pa.rules_from_payload(payload)          # 合法基线：应通过
        return
    with pytest.raises(pa.ConfigError, match=match):
        pa.rules_from_payload(payload)


def test_rules_from_payload_rejects_duplicate_id():
    base = {"expr": "x", "min_abs_delta": 1}
    with pytest.raises(pa.ConfigError, match="重复"):
        pa.rules_from_payload([{**base, "id": "a"}, {**base, "id": "a"}])


@pytest.mark.parametrize("raw, seconds", [
    ("90s", 90), ("5m", 300), ("6h", 21600), ("120", 120), ("120S", 120),
])
def test_parse_duration_seconds(raw, seconds):
    assert pa.parse_duration_seconds(raw) == seconds


@pytest.mark.parametrize("raw", ["", "abc", "0", "-5m", "6d"])
def test_parse_duration_seconds_rejects_garbage(raw):
    with pytest.raises(pa.ConfigError):
        pa.parse_duration_seconds(raw)


# ---- 编排链复用 ----------------------------------------------------------

def _alerter(tmp_path, *, client, api, rules=(RULE,), **kw):
    args = make_args(rules=rules, **kw)
    return pa.PredictiveAlerter(args, client=client, api=api,
                                ledger_path=tmp_path / "ledger.jsonl",
                                clock=lambda: 1_700_000_000.0)


def test_cycle_sends_through_existing_chain_as_alert_source(tmp_path):
    api = FakeApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api)

    assert alerter.cycle(now=1_700_000_000.0) == 0

    assert len(api.stream_calls) == 1
    body = api.stream_calls[0]
    assert body["source"] == "alert"                 # 既有编排链的告警入口
    assert body["env"] == "local"
    assert body["service"] == "opspilot-gateway"
    assert "预测预警" in body["query"]

    rows = read_ledger(tmp_path / "ledger.jsonl")
    emitted = [r for r in rows if r.get("rule") == "t-rule" and r.get("emit")]
    assert len(emitted) == 1
    assert emitted[0]["method"] == "modified_z"
    assert emitted[0]["mad"] == pytest.approx(0.1)
    assert alerter.stats["emitted"] == 1


def test_cycle_dry_run_never_reads_quota_and_never_sends(tmp_path):
    api = FakeApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api, dry_run=True)

    assert alerter.cycle(now=1_700_000_000.0) == 0

    assert api.state_reads == 0            # dry-run 必须能完全离线
    assert api.stream_calls == []
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert any(r.get("skip") == "dry_run" for r in rows)


def test_cycle_cooldown_blocks_immediate_repeat(tmp_path):
    api = FakeApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api, cooldown=1800)

    alerter.cycle(now=1_700_000_000.0)
    alerter.cycle(now=1_700_000_010.0)

    assert len(api.stream_calls) == 1
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert any(r.get("skip") == "cooldown" for r in rows)


def test_cycle_hour_budget_caps_emissions(tmp_path):
    api = FakeApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api, cooldown=0, max_per_hour=1)

    alerter.cycle(now=1_700_000_000.0)
    alerter.cycle(now=1_700_000_010.0)

    assert len(api.stream_calls) == 1
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert any(r.get("skip") == "hour_budget" for r in rows)


def test_cycle_quota_reserve_blocks_send(tmp_path):
    api = FakeApi(state={"runtime": {"quota": {"limit": 100, "used": 90}}})
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api, quota_reserve=50)

    assert alerter.cycle(now=1_700_000_000.0) == 0
    assert api.stream_calls == []
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert any(r.get("skip") == "quota_reserve" for r in rows)


def test_cycle_quota_unavailable_refuses_to_send_and_reports_2(tmp_path):
    api = FakeApi(state_error=RuntimeError("admin/state 不可达"))
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api)

    assert alerter.cycle(now=1_700_000_000.0) == 2
    assert api.stream_calls == []
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert any(r.get("skip") == "quota_unavailable" for r in rows)


def test_cycle_prometheus_unreachable_returns_2_and_ledgers(tmp_path):
    api = FakeApi()
    alerter = _alerter(tmp_path, client=FakeClient(fail_exprs={RULE.expr}), api=api)

    assert alerter.cycle(now=1_700_000_000.0) == 2
    assert api.stream_calls == []
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert any(r.get("skip_reason") == "prometheus_unreachable" for r in rows)
    assert alerter.stats["prom_failures"] == 1


def test_cycle_one_rule_failing_does_not_block_others(tmp_path):
    good = pa.Rule(id="good", expr="good_metric", direction="above",
                   min_abs_delta=0.5, service="s", metric_cn="m")
    bad = pa.Rule(id="bad", expr="bad_metric", direction="above",
                  min_abs_delta=0.5, service="s", metric_cn="m")
    api = FakeApi()
    client = FakeClient({good.expr: noisy_baseline(30) + [1.6] * 3},
                        fail_exprs={bad.expr})
    alerter = _alerter(tmp_path, client=client, api=api, rules=(bad, good))

    assert alerter.cycle(now=1_700_000_000.0) == 0     # 部分失败 ≠ 全不可达
    assert len(api.stream_calls) == 1


def test_cycle_no_data_rule_is_skipped_and_ledgered(tmp_path):
    api = FakeApi()
    alerter = _alerter(tmp_path, client=FakeClient({}), api=api)

    assert alerter.cycle(now=1_700_000_000.0) == 0
    assert api.stream_calls == []
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert any(r.get("skip") == "no_data" for r in rows)


def test_cycle_non_firing_rule_sends_nothing(tmp_path):
    api = FakeApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.0] * 3})
    alerter = _alerter(tmp_path, client=client, api=api)

    assert alerter.cycle(now=1_700_000_000.0) == 0
    assert api.stream_calls == []
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert [r for r in rows if r.get("rule")] == []     # 无跳过、无发送 → 只有 cycle 汇总


def test_cycle_consecutive_requires_two_rounds(tmp_path):
    api = FakeApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api, once=False, consecutive=2)

    alerter.cycle(now=1_700_000_000.0)
    assert api.stream_calls == []
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert any(r.get("skip") == "consecutive_pending" for r in rows)

    alerter.cycle(now=1_700_000_010.0)
    assert len(api.stream_calls) == 1


def test_once_treats_consecutive_as_one(tmp_path):
    api = FakeApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api, once=True, consecutive=5)

    alerter.cycle(now=1_700_000_000.0)
    assert len(api.stream_calls) == 1


def test_delivery_failure_does_not_kill_the_cycle(tmp_path):
    class ExplodingApi(FakeApi):
        def stream_chat(self, body, token, **kwargs):
            raise OSError("gateway down")

    api = ExplodingApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api)

    assert alerter.cycle(now=1_700_000_000.0) == 0
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert any(r.get("skip") == "delivery_failed" for r in rows)


def test_ledger_labels_degenerate_baseline(tmp_path):
    """AC-3.4：MAD=0 走绝对差分支，台账必须有 baseline=degenerate_mad_zero。"""
    api = FakeApi()
    client = FakeClient({RULE.expr: [1.0] * 30 + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api)

    alerter.cycle(now=1_700_000_000.0)

    emitted = [r for r in read_ledger(tmp_path / "ledger.jsonl")
               if r.get("rule") == "t-rule" and r.get("emit")]
    assert len(emitted) == 1
    assert emitted[0]["baseline"] == "degenerate_mad_zero"
    assert emitted[0]["method"] == "degenerate_mad_zero"


def test_skip_rows_carry_both_skip_and_skip_reason(tmp_path):
    api = FakeApi()
    alerter = _alerter(tmp_path, client=FakeClient({}), api=api)

    alerter.cycle(now=1_700_000_000.0)

    row = next(r for r in read_ledger(tmp_path / "ledger.jsonl") if r.get("rule") == "t-rule")
    assert row["skip"] == "no_data"
    assert row["skip_reason"] == "no_data"


def test_shape_errors_are_labelled_rule_shape():
    payload = {"status": "success", "data": {"resultType": "matrix", "result": [
        {"metric": {"job": "a"}, "values": [[1, "1"]]},
        {"metric": {"job": "b"}, "values": [[1, "2"]]},
    ]}}
    client = pa.PromRangeClient(opener=FakeOpener(payload))
    with pytest.raises(pa.PromRangeError) as exc:
        client.query_range("x", _now(), _now(), 60)
    assert exc.value.reason == "rule_shape"


def test_transport_errors_are_labelled_prometheus_unreachable():
    def boom(url, timeout=None):
        raise OSError("connection refused")

    client = pa.PromRangeClient(opener=boom)
    with pytest.raises(pa.PromRangeError) as exc:
        client.query_range("x", _now(), _now(), 60)
    assert exc.value.reason == "prometheus_unreachable"


def test_login_failure_is_not_swallowed_as_quota_unavailable(tmp_path):
    """凭据错必须能被主循环归因（AC-5.4），不能被 quota() 的宽 except 吞成 quota_unavailable。"""
    from alert_delivery import LoginFailed

    class LockedApi(FakeApi):
        def get_json(self, path, token):
            raise LoginFailed("HTTP 401")

    api = LockedApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api)

    with pytest.raises(LoginFailed):
        alerter.cycle(now=1_700_000_000.0)


def test_cycle_writes_one_summary_row_per_cycle(tmp_path):
    api = FakeApi()
    client = FakeClient({RULE.expr: noisy_baseline(30) + [1.6] * 3})
    alerter = _alerter(tmp_path, client=client, api=api)

    alerter.cycle(now=1_700_000_000.0)
    rows = read_ledger(tmp_path / "ledger.jsonl")
    assert sum(1 for r in rows if r.get("cycle")) == 1


# ---- CLI -----------------------------------------------------------------

def test_help_exits_zero():
    with pytest.raises(SystemExit) as exc:
        pa.main(["--help"])
    assert exc.value.code == 0


def test_bad_rules_file_exits_3(tmp_path):
    path = tmp_path / "rules.json"
    path.write_text("{not json", encoding="utf-8")
    assert pa.main(["--once", "--rules", str(path)]) == 3


def test_bad_direction_in_rules_file_exits_3(tmp_path):
    path = tmp_path / "rules.json"
    path.write_text(json.dumps([{"id": "a", "expr": "x", "direction": "sideways",
                                 "min_abs_delta": 1}]), encoding="utf-8")
    assert pa.main(["--once", "--rules", str(path)]) == 3


def test_bad_lookback_exits_3():
    assert pa.main(["--once", "--lookback", "6d"]) == 3
