"""自举告警生产者的判定与闸门回归锁（ADR-0011）。

为什么这些用例必须存在：告警链路的失效方式不是"报错"，而是**静默错判**——
计数器归零被当成负增量、配额/预算闸门顺序颠倒、健康面读成默认值，
三者都会让 watcher 看起来在正常工作却发出错误或不发告警。故用纯函数级用例锁死。

不触网：detect/deltas/gate 全部以合成 state 驱动，网络部分由活体验收覆盖。
"""
from __future__ import annotations

import sys
import types
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))
import alert_delivery as ad  # noqa: E402
import alert_producer as ap  # noqa: E402
import localapi  # noqa: E402


@pytest.fixture(autouse=True)
def _no_real_network(monkeypatch):
    """把"本文件不触网"从文档承诺变成机制。

    教训来源：`test_gate_order_*` 曾用"真发一次请求"来断言闸门全开，本机网关恰好在跑所以绿，
    CI（无网关）立刻 `Connection refused`。凡用例需要网络语义，必须显式 monkeypatch
    （`localapi.stream_chat` / `get_json` / `login`）；未 patch 的真实调用在这里直接判失败。
    """
    def _boom(req, timeout):
        raise AssertionError("单元用例不得发起真实网络调用：请 monkeypatch localapi.stream_chat/_open/get_json/login")

    monkeypatch.setattr(ap.localapi, "_open", _boom)
    monkeypatch.setattr(ad.AlertLedger, "append", lambda self, rec: rec)


def _state(metrics=None, health=None, degradation=None, quota=None, inflight=0) -> dict:
    return {
        "metrics": metrics or {},
        "health": health or {c: {"status": "UP"} for c in ("redis", "qdrant", "es")},
        "runtime": {"degradation": degradation or {"level": "L0", "failures": 0,
                                                   "llm_failure_threshold": 3,
                                                   "inflight_threshold": 40, "cooldown_s": 0},
                    "quota": quota or {"limit": 5000, "used": 0, "sub": "sre-watcher"},
                    "inflight": inflight},
    }


def _producer(**kw):
    args = types.SimpleNamespace(user="sre-watcher", dry_run=False, cooldown=300, max_per_hour=20,
                                 quota_reserve=50)
    for k, v in kw.items():
        setattr(args, k, v)
    return ap.Producer(args)


# ---- 计数器增量与归零 ------------------------------------------------------

def test_first_cycle_has_no_deltas():
    """首轮没有基准，不得凭历史计数造告警（否则重启后第一轮必误报）。"""
    d, reset = ap.deltas(ap.metrics_of(_state(metrics={"llm_calls": 99, "retrieval_timeouts": 7})), None)
    assert set(d.values()) == {0} and reset is False


def test_counter_reset_yields_zero_delta_and_flag():
    """进程内计数器重启归零：负增量必须当复位（记 0 + 置位），不得造负告警或静默掩盖。"""
    prev = ap.metrics_of(_state(metrics={"retrieval_timeouts": 9, "es_only_requests": 4}))
    cur = ap.metrics_of(_state(metrics={"retrieval_timeouts": 0, "es_only_requests": 0}))
    d, reset = ap.deltas(cur, prev)
    assert reset is True and d["retrieval_timeouts"] == 0 and d["es_only_requests"] == 0
    assert ap.detect(_state(metrics={"retrieval_timeouts": 0, "es_only_requests": 0}), d) == []


def test_deltas_track_growth():
    prev = ap.metrics_of(_state(metrics={"retrieval_timeouts": 2, "es_only_requests": 1}))
    cur = ap.metrics_of(_state(metrics={"retrieval_timeouts": 5, "es_only_requests": 3}))
    d, reset = ap.deltas(cur, prev)
    assert reset is False and d["retrieval_timeouts"] == 3 and d["es_only_requests"] == 2


# ---- 每条规则的触发与不触发 ------------------------------------------------

def test_healthy_and_quiet_state_emits_nothing():
    assert ap.detect(_state(), {k: 0 for k in ap.METRIC_KEYS}) == []


def test_dependency_down_is_per_component():
    st = _state(health={"redis": {"status": "UP"}, "qdrant": {"status": "DOWN", "detail": "conn refused"},
                        "es": {"status": "UP"}})
    got = ap.detect(st, {k: 0 for k in ap.METRIC_KEYS})
    assert [a["rule"] for a in got] == ["dep_down"]
    assert got[0]["service"] == "qdrant" and "qdrant" in got[0]["query"]


def test_degrade_triggers_on_level_and_on_cooldown():
    quiet = {k: 0 for k in ap.METRIC_KEYS}
    l1 = ap.detect(_state(degradation={"level": "L1", "failures": 1, "llm_failure_threshold": 3, "cooldown_s": 0}), quiet)
    assert [a["rule"] for a in l1] == ["degrade"]
    # 熔断冷却期档位显示 L0 但 cooldown>0（半开前）同样必须报——只看 level 会漏
    cool = ap.detect(_state(degradation={"level": "L0", "failures": 0, "llm_failure_threshold": 3, "cooldown_s": 42}), quiet)
    assert [a["rule"] for a in cool] == ["degrade"] and "42" in cool[0]["query"]


def test_metric_rules_need_their_companion_signal():
    only_timeouts = {k: 0 for k in ap.METRIC_KEYS}
    only_timeouts["retrieval_timeouts"] = 3
    assert ap.detect(_state(), only_timeouts) == []          # 单腿超时不足以下"双路退化"结论
    both = dict(only_timeouts, es_only_requests=2)
    got = ap.detect(_state(), both)
    assert [a["rule"] for a in got] == ["retrieval_degraded"]


def test_upstream_and_refusal_rules():
    d = {k: 0 for k in ap.METRIC_KEYS}
    d["llm_rate_limited"] = 1
    assert [a["rule"] for a in ap.detect(_state(), d)] == ["upstream_limited"]
    d2 = {k: 0 for k in ap.METRIC_KEYS}
    d2["sop_fallbacks"] = 2
    assert [a["rule"] for a in ap.detect(_state(), d2)] == ["refusal_spike"]


# ---- 发送闸门：顺序即优先级 -------------------------------------------------

def test_gate_order_budget_beats_quota_beats_cooldown(monkeypatch):
    p = _producer(max_per_hour=1)
    p.delivery.emits = [__import__("time").time()]           # 已用满小时预算
    low_q = _state(quota={"limit": 100, "used": 99})
    alert = {"rule": "dep_down", "service": "qdrant", "query": "q"}
    assert p.gate(alert, low_q, 0.0)["skip"] == "hour_budget"

    p2 = _producer()
    assert p2.gate(alert, low_q, 0.0)["skip"] == "quota_reserve"

    # 闸门全开时必须落到真实投递路径；底层 HTTP 由系统边界桩承接。
    p3 = _producer(cooldown=300)
    p3.token = "t"
    monkeypatch.setattr(ap.localapi, "stream_chat",
                        lambda body, token, timeout=120, collect_deltas=True:
                        {"status": 200, "meta": {}, "done": {}, "deltas": [],
                         "error": None, "ttft_s": None, "total_s": 0.01})
    p3.delivery.last_emit[p3._key(alert)] = 1000.0
    assert p3.gate(alert, _state(), 1200.0)["skip"] == "cooldown"
    assert p3.gate(alert, _state(), 1400.0).get("emit") is True


def test_cooldown_is_per_component_not_per_rule(monkeypatch):
    """多组件同时不可用：冷却必须按 (规则,组件) 计。

    按规则单键时 `dep_down` 只报第一个组件，其余被静默吞掉——真实的 redis+es 同时中断
    就是这么暴露的（2026-09-17 实测：candidates=2 但 emitted=1）。
    """
    p = _producer(cooldown=300)
    p.token = "t"
    monkeypatch.setattr(ap.localapi, "stream_chat",
                        lambda body, token, timeout=120, collect_deltas=True:
                        {"status": 200, "meta": {}, "done": {}, "deltas": [],
                         "error": None, "ttft_s": None, "total_s": 0.01})
    a_redis = {"rule": "dep_down", "service": "redis", "query": "q"}
    a_es = {"rule": "dep_down", "service": "es", "query": "q"}
    assert p.gate(a_redis, _state(), 1000.0).get("emit") is True
    assert p.gate(a_es, _state(), 1100.0).get("emit") is True, "另一组件的故障不得被冷却吞掉"
    assert p.gate(a_redis, _state(), 1100.0)["skip"] == "cooldown", "同一组件仍受冷却约束"


def test_gate_skips_are_recorded_with_rule_and_query():
    """每个 skip 都要带 rule/query——否则事后问"为什么没发"时无从回答。"""
    p = _producer()
    rec = p.gate({"rule": "degrade", "service": "opspilot-gateway", "query": "qq"},
                 _state(quota={"limit": 100, "used": 99}), 0.0)
    assert rec["emit"] is False and rec["skip"] == "quota_reserve"
    assert rec["rule"] == "degrade" and rec["query"] == "qq"


def test_dry_run_sends_nothing():
    p = _producer(dry_run=True)
    rec = p.emit({"rule": "dep_down", "service": "qdrant", "query": "q"})
    assert rec["emit"] is False and rec["skip"] == "dry_run"


# ---- 出口白名单（SSRF 面）-------------------------------------------------

def test_network_primitives_refuse_non_local_targets():
    for bad in ("http://evil.example.com/v1", "https://127.0.0.1:8081/x", "file:///etc/passwd",
                "http://localhost@evil.example.com/x",          # userinfo 混淆
                "http://user:pw@localhost:8081/x"):
        try:
            localapi.assert_local(bad)
        except ValueError:
            continue
        raise AssertionError("非本机 http 目标必须被拒绝: " + bad)


def test_loopback_ip_is_verified_after_resolution(monkeypatch):
    """主机名通过但解析到非环回地址 → 必须拒绝（hosts 篡改 / DNS rebinding 面）。"""
    import socket as real_socket

    def fake_getaddrinfo(host, port, **kw):
        return [(real_socket.AF_INET, real_socket.SOCK_STREAM, 6, "", ("203.0.113.7", port or 80))]

    monkeypatch.setattr(ap.localapi.socket, "getaddrinfo", fake_getaddrinfo)
    try:
        localapi.assert_local("http://localhost:8081/api/v1/admin/state")
    except ValueError as e:
        assert "非环回" in str(e)
        return
    raise AssertionError("解析到非环回地址必须拒绝")


# ---- 组件 detail 进 prompt 前的清洗 ---------------------------------------

def test_safe_detail_whitelist_and_cap():
    assert ap._safe_detail("io.grpc.StatusRuntimeException: UNAVAILABLE: io exception") == \
        "io.grpc.StatusRuntimeException: UNAVAILABLE: io exception"
    assert "<script>" not in ap._safe_detail("<script>alert(1)</script>")
    assert ap._safe_detail("x" * 500) == "x" * ap.DETAIL_MAX
    assert ap._safe_detail(None) == ""
    # 清洗后的 detail 必须真的进不了危险字符
    st = _state(health={"redis": {"status": "DOWN", "detail": "`rm -rf /`\x07\x00"}})
    got = ap.detect(st, {k: 0 for k in ap.METRIC_KEYS})[0]["query"]
    assert "`" not in got and "\x00" not in got


# ---- 登录失败的退避与快速失败 ---------------------------------------------

def test_login_failure_backs_off_then_exits(monkeypatch):
    """凭据错/主体被锁：不得无限重试（会加深 15 分钟锁定并刷审计），退避后退出码 2。"""
    p = _producer()
    writes = []
    monkeypatch.setattr(p, "ledger", lambda rec: writes.append(rec))
    monkeypatch.setattr(ap.time, "sleep", lambda s: None)
    monkeypatch.setattr(p, "login", lambda: (_ for _ in ()).throw(ap.LoginFailed("HTTP 401")))
    rc = p.run(interval=0)
    assert rc == 2 and p.login_failures == ap.MAX_LOGIN_FAILURES
    assert all(w["skip"] == "login_failed" for w in writes), writes
    assert len(writes) == ap.MAX_LOGIN_FAILURES


def test_login_success_resets_failure_counter(monkeypatch):
    p = _producer()
    p.login_failures = 2
    monkeypatch.setattr(p, "ledger", lambda rec: None)
    monkeypatch.setattr(p, "login", lambda: setattr(p, "token", "t"))
    monkeypatch.setattr(p, "cycle", lambda: [])
    assert p.run(interval=0) == 0
    assert p.login_failures == 0


# ---- 单实例守卫 -----------------------------------------------------------

def test_lock_blocks_second_instance_and_expires(monkeypatch, tmp_path):
    lock = tmp_path / "producer.lock"
    monkeypatch.setattr(ap, "LOCK", lock)
    assert ap._acquire_lock(interval=15, force=False) is True
    assert ap._acquire_lock(interval=15, force=False) is False     # 第二个实例被挡
    assert ap._acquire_lock(interval=15, force=True) is True        # --force 可接管
    import os as _os
    old = lock.stat().st_mtime - 10_000                             # 模拟僵死实例的陈旧锁
    _os.utime(lock, (old, old))
    assert ap._acquire_lock(interval=15, force=False) is True        # 过期锁可被接管


def test_emit_marks_failed_sends(monkeypatch):
    """非 2xx 仍记 emit=true（请求确实发出、配额确实扣了），但必须带 sent_ok=false 可查。"""
    p = _producer()
    p.token = "t"
    monkeypatch.setattr(ap.localapi, "stream_chat",
                        lambda body, token, timeout=120, collect_deltas=True:
                        {"status": 429, "code": "HTTP_429", "meta": {}, "done": {}, "deltas": [],
                         "error": None, "ttft_s": None, "total_s": 0.01})
    rec = p.emit({"rule": "dep_down", "service": "qdrant", "query": "q"})
    assert rec["emit"] is True and rec["sent_ok"] is False and rec["http"] == 429


def test_emit_clears_token_on_401(monkeypatch):
    p = _producer()
    p.token = "stale"
    monkeypatch.setattr(ap.localapi, "stream_chat",
                        lambda body, token, timeout=120, collect_deltas=True:
                        {"status": 401, "code": "HTTP_401", "meta": {}, "done": {}, "deltas": [],
                         "error": None, "ttft_s": None, "total_s": 0.01})
    p.emit({"rule": "dep_down", "service": "qdrant", "query": "q"})
    assert p.token == "", "401 后必须清 token，让下一轮走重登自愈"


# ---- 鉴权失败的两条路径（401 自愈 / 403 致命）-----------------------------

def _http_error(code: int):
    import urllib.error
    return urllib.error.HTTPError("http://localhost:8081/x", code, "err", {}, None)


def test_fetch_state_relogins_on_401(monkeypatch):
    """24h token 过期是常态：401 必须换新 token 重试一次，而不是让 watcher 停摆。"""
    p = _producer()
    calls = {"login": 0, "get": 0}

    def fake_login(username=None):
        calls["login"] += 1
        p.token = "fresh-token"
        return p.token

    def fake_get(path, token, timeout=10):
        calls["get"] += 1
        if calls["get"] == 1:
            raise _http_error(401)
        return {"metrics": {}, "runtime": {}}

    monkeypatch.setattr(ap.localapi, "login", fake_login)
    monkeypatch.setattr(ap.localapi, "get_json", fake_get)
    assert p.fetch_state() == {"metrics": {}, "runtime": {}}
    assert calls == {"login": 1, "get": 2} and p.token == "fresh-token"


def test_fetch_state_403_is_fatal_and_not_retried(monkeypatch):
    """403 = 主体权限配置错（非 platform/level<3），重试无意义——必须快速失败而非空转。"""
    p = _producer()
    calls = {"get": 0}

    def fake_get(path, token, timeout=10):
        calls["get"] += 1
        raise _http_error(403)

    monkeypatch.setattr(ap.localapi, "get_json", fake_get)
    try:
        p.fetch_state()
    except PermissionError:
        assert calls["get"] == 1
        return
    raise AssertionError("403 必须抛 PermissionError（退出码 2），不得静默继续")


def test_run_survives_unreachable_gateway(monkeypatch):
    """网关不可达（自举的固有盲区）：单轮必须失败-留痕-继续，绝不把 watcher 打死。"""
    p = _producer()
    writes = []
    monkeypatch.setattr(p, "ledger", lambda rec: writes.append(rec))
    monkeypatch.setattr(p, "login", lambda: None)
    monkeypatch.setattr(p, "cycle", lambda: (_ for _ in ()).throw(ConnectionRefusedError("refused")))
    assert p.run(interval=0) == 0                      # 不可达不是致命错误，进程照常收尾
    assert p.stats["errors"] == 1
    assert writes and writes[0]["skip"] == "unreachable" and writes[0]["emit"] is False
