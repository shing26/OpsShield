# -*- coding: utf-8 -*-
"""Alertmanager webhook 桥的映射与闸门回归锁（`scripts/alert_bridge.py`）。

锁四件事，前三件是纯函数级、不触网：

  1. **摊平口径**——每条 alert 独立转发，绝不合并成一条（合并会把 N 条压成一个指纹，
     测的就成了本脚本拼字符串的手艺，而不是 Alertmanager 的真实行为）。
  2. **错误码契约**——`labels.error_code` 有就拼进 query，**没有就不补**。
     绝不能回退到 alertname/指标名去凑：那些形状会被 EsSearchService 的 ERROR_CODE
     判据当成精确符号走快路径，等于伪造一个强标识符。缺失走语义路是被设计的分支。
  3. **不可信载荷**——注解与标签是外部输入，进 query 前必须过白名单与限长。
     与控制流字符一起被洗掉的还有 < > ` $ 与引号（拼接/注入类写法的原料）。
  4. **HTTP 面**——畸形载荷回 400 而不是 5xx（5xx 会让 Alertmanager 重试，
     畸形载荷重试一万次还是畸形）；正常载荷即使内部转发失败也回 200 并留痕。

不触网：`_open` 被替换为断言失败，需要网络语义的用例显式 monkeypatch `stream_chat`/`get_json`。
第四组的 HTTP 用例只打本进程起的临时服务（127.0.0.1:0），不出环回。
"""
from __future__ import annotations

import json
import os
import re
import sys
import threading
import types
import urllib.error
import urllib.request
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts"))

import alert_bridge as ab  # noqa: E402
import alert_delivery as ad  # noqa: E402
import localapi  # noqa: E402


@pytest.fixture(autouse=True)
def _no_real_network(monkeypatch):
    """把"不触网"从文档承诺变成机制（与 test_alert_producer.py 同一教训）。"""
    def _boom(req, timeout):
        raise AssertionError("单元用例不得发起真实网络调用：请 monkeypatch localapi.stream_chat/get_json")

    monkeypatch.setattr(ab.localapi, "_open", _boom)
    # 台账不落真实文件：用例不该往 logs/ 里添行
    monkeypatch.setattr(ab.Bridge, "ledger", lambda self, rec: None)
    monkeypatch.setattr(ad.AlertLedger, "append", lambda self, rec: rec)


def _bridge(**kw) -> ab.Bridge:
    args = types.SimpleNamespace(user="sre-watcher", env="local", dry_run=False,
                                 max_per_hour=20, quota_reserve=50, group_cap=ab.GROUP_CAP)
    for k, v in kw.items():
        setattr(args, k, v)
    return ab.Bridge(args)


def _allow_live_quota(monkeypatch, bridge: ab.Bridge, remaining: int | None = 1000) -> None:
    monkeypatch.setattr(bridge.delivery, "_live_quota_remaining", lambda: remaining)


# ---- .env 读取（桥要能独立起，不依赖 shell 方言）---------------------------

def test_load_env_file_parses_pairs_without_overriding_explicit_env(tmp_path, monkeypatch):
    env = tmp_path / ".env"
    env.write_text('# 注释\nDEMO_PASSWORD="p=w"\nOPSPILOT_TEST_KEY=\'http://localhost:1\'\n\nBROKEN\n',
                   encoding="utf-8")
    monkeypatch.setenv("DEMO_PASSWORD", "explicit")
    monkeypatch.delenv("OPSPILOT_TEST_KEY", raising=False)
    ab.load_env_file(env)
    assert os.environ["DEMO_PASSWORD"] == "explicit", "显式 env 必须赢（setdefault）"
    assert os.environ["OPSPILOT_TEST_KEY"] == "http://localhost:1"
    assert "BROKEN" not in os.environ, "无 = 的行不是配置，跳过而非执行"


def test_load_env_file_missing_is_noop(tmp_path):
    ab.load_env_file(tmp_path / "nope.env")     # 文件不存在不该让桥起不来


def _payload(*alerts, status="firing", group_key='{}:{alertname="X"}') -> dict:
    return {"version": "4", "status": status, "groupKey": group_key,
            "commonLabels": {"job": "opspilot"}, "commonAnnotations": {},
            "alerts": list(alerts)}


def _alert(name="OpsPilotUpstreamRateLimited", *, summary="上游模型服务正在限流",
           description=None, code=None, job="opspilot", status="firing", **labels) -> dict:
    ann = {}
    if summary is not None:
        ann["summary"] = summary
    if description is not None:
        ann["description"] = description
    lb = {"alertname": name, "job": job, **labels}
    if code is not None:
        lb["error_code"] = code
    return {"status": status, "labels": lb, "annotations": ann,
            "startsAt": "2026-10-17T00:00:00Z", "endsAt": "0001-01-01T00:00:00Z",
            "generatorURL": "http://localhost:9090/graph"}


# ---- 摊平：每条 alert 独立 ------------------------------------------------

def test_each_alert_becomes_its_own_request():
    """N 条不同告警必须摊成 N 个请求——合并即破坏观测面（文件头第 1 条）。"""
    items, dropped = ab.flatten(_payload(
        _alert(summary="上游限流", code="51001_MODEL_ARREARAGE"),
        _alert(name="OpsPilotRetrievalTimeouts", summary="检索腿超时",
               code="51005_LEG_TIMEOUT_SILENT_DEGRADE"),
    ), "local")
    assert dropped == 0 and len(items) == 2
    assert {i["alertname"] for i in items} == {"OpsPilotUpstreamRateLimited",
                                               "OpsPilotRetrievalTimeouts"}
    for i in items:
        assert i["request"]["source"] == "alert"
        assert i["request"]["service"] == "opspilot"
        assert i["request"]["env"] == "local"


def test_group_cap_drops_tail_and_reports_count():
    alerts = [_alert(name=f"Rule{i}", summary=f"第{i}条") for i in range(5)]
    items, dropped = ab.flatten(_payload(*alerts), "local", cap=2)
    assert len(items) == 2 and dropped == 3, "超出上限必须留痕计数，不得静默"


def test_non_dict_alert_entries_are_skipped_not_fatal():
    """载荷是外部契约，畸形元素不该让整批 500；跳过并计入丢弃。"""
    payload = _payload(_alert())
    payload["alerts"].append("not-a-dict")
    items, dropped = ab.flatten(payload, "local")
    assert len(items) == 1 and dropped == 1


def test_missing_or_wrong_type_alerts_yields_empty():
    assert ab.flatten({"alerts": None}, "local") == ([], 0)
    assert ab.flatten({}, "local") == ([], 0)


def test_resolved_status_is_carried_through():
    """resolved 照转（设计决定，不是遗漏）：它同样是真实输入。"""
    items, _ = ab.flatten(_payload(_alert(status="resolved"), status="resolved"), "local")
    assert items[0]["status"] == "resolved"


# ---- 错误码契约：有就拼，没有就不补 ----------------------------------------

def test_error_code_is_appended_when_present():
    q = ab.compose_query(_alert(summary="上游模型服务正在限流", code="51001_MODEL_ARREARAGE"))
    assert q.endswith("51001_MODEL_ARREARAGE") and q.startswith("上游模型服务正在限流")


def test_error_code_absent_does_not_fabricate_one():
    """缺失时绝不能拿 alertname/指标名去凑——那会伪造一个强标识符。

    OpsPilotSopFallbacks 规则就是故意不带 error_code 的（走语义路），
    这条断言是那个设计决定在代码侧的守卫。
    """
    q = ab.compose_query(_alert(name="OpsPilotSopFallbacks", summary="静态 SOP 兜底次数在上升"))
    assert q == "静态 SOP 兜底次数在上升"
    assert "OpsPilotSopFallbacks" not in q, "规则名不是错误码，不得混入 query"


def test_summary_falls_back_to_description():
    assert ab.compose_query(_alert(summary=None, description="描述兜底")) == "描述兜底"


def test_empty_alert_yields_empty_query():
    """无任何可用文本 → 空 query。空 query 在 forward 里被跳过（skip=empty_query），
    绝不打一个空串给网关（那会命中门控拒答并写 L1 缓存）。"""
    assert ab.compose_query(_alert(summary=None, description=None)) == ""
    assert ab.compose_query({}) == ""


# ---- 服务与环境的取值 ------------------------------------------------------

def test_service_comes_from_job_not_instance():
    """instance 带端口、会被 <N> 归一化，取它会把不同实例压成同一服务身份。"""
    items, _ = ab.flatten(_payload(_alert(job="opspilot", instance="localhost:8081")), "local")
    assert items[0]["request"]["service"] == "opspilot"


def test_missing_job_yields_empty_service_not_a_guess():
    a = _alert()
    a["labels"].pop("job")
    items, _ = ab.flatten(_payload(a), "local")
    assert items[0]["request"]["service"] == ""


# ---- 告警身份（alert_id）：与 message 无关 ---------------------------------

def test_alert_id_is_independent_of_message_text():
    """模板把 $value 渲染进 summary → 文案每次都变，但身份必须稳定（台账 §3.1 的修法判据）。"""
    a = _alert(summary="上游限流 0.020 次/秒", code="51001_MODEL_ARREARAGE")
    b = _alert(summary="上游限流 0.999 次/秒", code="51001_MODEL_ARREARAGE")
    ia, _ = ab.flatten(_payload(a), "local")
    ib, _ = ab.flatten(_payload(b), "local")
    assert ia[0]["request"]["query"] != ib[0]["request"]["query"], "前提：文案确实不同"
    assert ia[0]["alert_id"] == ib[0]["alert_id"], "身份不得吃 message 文本"


def test_alert_id_changes_when_any_identity_field_changes():
    """四元组 alertname/job/error_code/status 任一变化都必须换身份——这是身份隔离判据②的锁。"""
    base = dict(name="Rule", summary="s", code="51001_MODEL_ARREARAGE", job="opspilot", status="firing")
    base_id, _ = ab.flatten(_payload(_alert(**base)), "local")
    variants = [
        dict(base, name="OtherRule"),
        dict(base, job="other-job"),
        dict(base, code="51005_LEG_TIMEOUT_SILENT_DEGRADE"),
        dict(base, status="resolved"),
    ]
    for v in variants:
        other, _ = ab.flatten(_payload(_alert(**v)), "local")
        assert other[0]["alert_id"] != base_id[0]["alert_id"], f"身份组分变化未反映到 alert_id: {v}"


def test_firing_and_resolved_are_distinct_identities():
    """status 进身份是设计决定（D2）：resolved 不该命中 firing 的缓存。"""
    f, _ = ab.flatten(_payload(_alert(status="firing"), status="firing"), "local")
    r, _ = ab.flatten(_payload(_alert(status="resolved"), status="resolved"), "local")
    assert f[0]["alert_id"] != r[0]["alert_id"]


def test_request_records_and_sends_the_same_alert_id():
    """请求体、item 与哈希前四元组三处必须同源，避免"对不上账"。

    线格式字段名必须是 `alertId`（camelCase）：`ChatRequest` 走 Jackson 默认命名策略，
    发 `alert_id` 会被静默忽略、身份退回文本指纹（2026-10-17 live 验收实测抓到）。
    """
    items, _ = ab.flatten(_payload(_alert()), "local")
    item = items[0]
    assert item["request"]["alertId"] == item["alert_id"]
    assert "alert_id" not in item["request"], "线格式是 camelCase；snake_case 会被 Jackson 静默忽略"
    assert item["alert_id"] == ab.derive_alert_id(
        item["alertname"], item["job"], item["error_code"], item["status"])
    assert item["alert_id_basis"] == "|".join(
        [item["alertname"], item["job"], item["error_code"], item["status"]])


def test_alert_id_matches_gateway_shape_constraint():
    """桥产出的 alert_id 必须过 ChatRequest 的 @Pattern：8–128 位 [A-Za-z0-9_.=|\\-]。"""
    items, _ = ab.flatten(_payload(_alert()), "local")
    aid = items[0]["alert_id"]
    assert re.fullmatch(r"[A-Za-z0-9_.=\-|]{8,128}", aid), aid


def test_forward_ledger_row_carries_alert_identity(monkeypatch):
    """台账每行带 alert_id 与哈希前四元组——本轮研究产出的对账主键。"""
    b = _bridge()
    b.token = "t"
    _allow_live_quota(monkeypatch, b)
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: {
        "status": 200, "meta": {}, "done": {}, "error": None, "ttft_s": 0.1, "total_s": 0.2})
    item = ab.flatten(_payload(_alert()), "local")[0][0]
    rec = b.forward(item)
    assert rec["alert_id"] == item["alert_id"]
    assert rec["alert_id_basis"] == item["alert_id_basis"]


# ---- incident 身份（incident_id）：关联而非归并 -----------------------------

def test_incident_id_matches_trace_header_shape_constraint():
    """桥产出的 incident_id 必须过 `X-Trace-Id` 的 [A-Za-z0-9_-]{8,64}——否则转发必然 400。

    `am-` 前缀 + 32 位十六进制 = 35 字符；前缀是自描述用的（审计行 trace_id 这一列
    平时装的是人写的调用链 id，裸十六进制看不出从哪来）。
    """
    items, _ = ab.flatten(_payload(_alert()), "local")
    iid = items[0]["incident_id"]
    assert re.fullmatch(r"am-[0-9a-f]{32}", iid), iid
    assert re.fullmatch(r"[A-Za-z0-9_-]{8,64}", iid), iid


def test_incident_id_is_independent_of_message_text():
    """同一条规则重发时 summary 里的 $value 每次都变，incident 身份不得跟着变。"""
    a, _ = ab.flatten(_payload(_alert(summary="上游限流 0.020 次/秒")), "local")
    b, _ = ab.flatten(_payload(_alert(summary="上游限流 0.999 次/秒")), "local")
    assert a[0]["request"]["query"] != b[0]["request"]["query"], "前提：文案确实不同"
    assert a[0]["incident_id"] == b[0]["incident_id"]


def test_incident_id_changes_when_group_key_changes():
    """分组变化即换 incident——上游怎么分组，我们就怎么认事故。"""
    one, _ = ab.flatten(_payload(_alert(), group_key='{}:{alertname="A"}'), "local")
    two, _ = ab.flatten(_payload(_alert(), group_key='{}:{alertname="B"}'), "local")
    assert one[0]["incident_id"] != two[0]["incident_id"]


def test_incident_id_changes_when_the_group_starts_later():
    """时间维生效：同 groupKey、起点不同 ⇒ 换 incident。

    这条锁的是"groupKey 是路由桶 id、不是 incident id"——桶没有时间，
    同一条规则下周再 fire 会拿到同一个 key，没有起点锚点就会把两次事故并成一次。
    """
    early = _alert()
    early["startsAt"] = "2026-10-17T00:00:00Z"
    later = _alert()
    later["startsAt"] = "2026-10-18T00:00:00Z"
    a, _ = ab.flatten(_payload(early), "local")
    b, _ = ab.flatten(_payload(later), "local")
    assert a[0]["incident_id"] != b[0]["incident_id"]


def test_incident_id_is_taken_from_the_whole_payload_not_the_truncated_head():
    """截断不得改变 incident 身份——本组唯一会**静默**失效的不变式。

    若 min(startsAt) 在 GROUP_CAP 截断之后才算，同一场事故会因为"这批带了几条"
    而换身份，台账里看不出任何异常（没有报错、没有 skip 行）。故这里刻意把
    **最早的那条排在会被丢弃的尾部**。
    """
    early, late = _alert(name="Early"), _alert(name="Late")
    early["startsAt"] = "2026-10-17T00:00:00Z"
    late["startsAt"] = "2026-10-17T00:05:00Z"
    payload = _payload(late, early)
    capped, dropped = ab.flatten(payload, "local", cap=1)
    full, _ = ab.flatten(payload, "local", cap=10)
    assert dropped == 1 and capped[0]["alertname"] == "Late", "前提：被丢掉的那条才是最早的"
    assert capped[0]["incident_id"] == full[0]["incident_id"]
    assert capped[0]["incident_id"] == ab.derive_incident_id(
        payload["groupKey"], "2026-10-17T00:00:00Z")


def test_forward_sends_incident_id_as_trace_header(monkeypatch):
    """incident_id 走 `X-Trace-Id` 请求头，**不进请求体**（关联键不是 DTO 字段）。"""
    seen = {}

    def _fake(*a, **k):
        seen.update(k)
        return {"status": 200, "meta": {}, "done": {}, "error": None,
                "ttft_s": 0.1, "total_s": 0.2}

    b = _bridge()
    b.token = "t"
    _allow_live_quota(monkeypatch, b)
    monkeypatch.setattr(ab.localapi, "stream_chat", _fake)
    item = ab.flatten(_payload(_alert()), "local")[0][0]
    rec = b.forward(item)
    assert seen["trace_id"] == item["incident_id"] == rec["incident_id"]
    assert "incident_id" not in item["request"] and "trace_id" not in item["request"], \
        "关联键走请求头；请求体多一个字段就是动了 DTO 面"


def test_ledger_rows_carry_incident_identity(monkeypatch):
    """带 incident 身份的行有两处：转发行，以及 group_cap 丢弃行（group 级事实）。"""
    rows = []
    b = _bridge(group_cap=1)
    b.token = "t"
    monkeypatch.setattr(b, "ledger", lambda rec: rows.append(rec))
    monkeypatch.setattr(b.delivery.ledger, "append", lambda rec: rows.append(rec))
    _allow_live_quota(monkeypatch, b)
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: {
        "status": 200, "meta": {}, "done": {}, "error": None, "ttft_s": 0.1, "total_s": 0.2})
    payload = _payload(_alert(), _alert(name="OpsPilotRetrievalTimeouts"))
    b.handle_payload(payload)
    cap = [r for r in rows if r.get("skip") == "group_cap"]
    fwd = [r for r in rows if r.get("emit")]
    assert len(cap) == 1 and len(fwd) == 1, f"行集合不对：{rows}"
    expected = ab.incident_of(payload)[0]
    assert cap[0]["incident_id"] == fwd[0]["incident_id"] == expected
    assert cap[0]["incident_basis"] == fwd[0]["incident_basis"] == \
        payload["groupKey"] + "|" + "2026-10-17T00:00:00Z"


# ---- 不可信载荷的清洗 ------------------------------------------------------

def test_dangerous_characters_are_stripped_from_query():
    q = ab.compose_query(_alert(
        summary='<script>alert(1)</script> `rm -rf /` $(whoami) "x"\x00\x07'))
    for bad in ("<", ">", "`", "$", '"', "\x00", "\x07"):
        assert bad not in q, f"危险字符 {bad!r} 逃过了白名单"
    assert "script" in q and "rm -rf" in q      # 语义保留，只去掉字符


def test_text_is_length_capped():
    q = ab.compose_query(_alert(summary="x" * 5000))
    assert len(q) <= ab.QUERY_MAX
    assert len(ab._clean("y" * 5000, ab.SUMMARY_MAX)) == ab.SUMMARY_MAX


def test_non_string_labels_do_not_crash():
    """Alertmanager 契约保证 string→string，但载荷是不可信输入，不该靠对方守规矩。"""
    a = _alert()
    a["labels"]["job"] = 12345
    a["annotations"]["summary"] = ["not", "a", "string"]
    items, _ = ab.flatten(_payload(a), "local")
    assert items[0]["request"]["service"] == ""
    assert items[0]["request"]["query"] == ""


# ---- 闸门与转发 ------------------------------------------------------------

def test_dry_run_never_calls_the_gateway(monkeypatch):
    called = []
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: called.append(a) or {})
    b = _bridge(dry_run=True)
    rec = b.forward(ab.flatten(_payload(_alert()), "local")[0][0])
    assert rec["emit"] is False and rec["skip"] == "dry_run" and called == []


def test_empty_query_is_skipped_before_any_network_call(monkeypatch):
    called = []
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: called.append(a) or {})
    b = _bridge()
    rec = b.forward(ab.flatten(_payload(_alert(summary=None)), "local")[0][0])
    assert rec["skip"] == "empty_query" and called == []


def test_hour_budget_blocks_after_limit(monkeypatch):
    b = _bridge(max_per_hour=1)
    b.token = "t"          # 有 token 才走"转发"路径；否则会先尝试登录（单测无凭据）
    _allow_live_quota(monkeypatch, b)
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: {
        "status": 200, "meta": {}, "done": {}, "error": None, "ttft_s": 0.1, "total_s": 0.2})
    item = ab.flatten(_payload(_alert()), "local")[0][0]
    assert b.forward(item).get("sent_ok") is True
    assert b.forward(item)["skip"] == "hour_budget", "超限后必须停止转发（防自伤）"


def test_quota_reserve_blocks_forwarding(monkeypatch):
    b = _bridge(quota_reserve=50)
    _allow_live_quota(monkeypatch, b, 49)
    called = []
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: called.append(a) or {})
    rec = b.forward(ab.flatten(_payload(_alert()), "local")[0][0])
    assert rec["skip"] == "quota_reserve" and called == []


def test_quota_read_failure_does_not_block_forwarding(monkeypatch):
    """配额面读不到 ≠ 配额不足：告警可用性优先（下游网关自己还有 429 兜底）。"""
    b = _bridge()
    b.token = "t"
    _allow_live_quota(monkeypatch, b, None)
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: {
        "status": 200, "meta": {"fingerprint": "abc", "cache_hit": "L1"}, "done": {},
        "error": None, "ttft_s": 0.01, "total_s": 0.02})
    rec = b.forward(ab.flatten(_payload(_alert()), "local")[0][0])
    assert rec["sent_ok"] is True and rec["fp"] == "abc" and rec["cache_hit"] == "L1"


def test_gateway_unreachable_records_failure_without_raising(monkeypatch):
    b = _bridge()
    b.token = "t"
    _allow_live_quota(monkeypatch, b)
    monkeypatch.setattr(ab.localapi, "stream_chat",
                        lambda *a, **k: (_ for _ in ()).throw(ConnectionRefusedError("refused")))
    rec = b.forward(ab.flatten(_payload(_alert()), "local")[0][0])
    assert rec["emit"] is True and rec["sent_ok"] is False and "refused" in rec["error"]
    assert b.token == "", "不可达后凭证态未知，须置空以便下次重登"


def test_401_clears_token_for_self_healing(monkeypatch):
    b = _bridge()
    b.token = "stale"
    _allow_live_quota(monkeypatch, b)
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: {
        "status": 401, "code": "HTTP_401", "meta": {}, "done": {},
        "error": None, "ttft_s": None, "total_s": 0.01})
    b.forward(ab.flatten(_payload(_alert()), "local")[0][0])
    assert b.token == ""


def test_refs_are_derived_from_chunk_ids(monkeypatch):
    """ref_docs 是本轮观测的主要产出之一（指纹碎没碎之外，还得看命中了哪些文档）。"""
    b = _bridge()
    b.token = "t"
    _allow_live_quota(monkeypatch, b)
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: {
        "status": 200, "meta": {}, "done": {"refs": [
            {"chunkId": "rb-201::排查步骤 > 第一步"},
            {"chunkId": "rb-201::止损操作"},
            {"chunkId": "pm-101::现象"},
        ]}, "error": None, "ttft_s": 0.1, "total_s": 0.5})
    rec = b.forward(ab.flatten(_payload(_alert()), "local")[0][0])
    assert rec["ref_docs"] == ["pm-101", "rb-201"]


def test_login_failure_is_skipped_for_batch_and_leaves_token_empty(monkeypatch):
    """凭据错落 login_failed 台账，不伪装成网络故障，也不阻断同批其余告警。"""
    b = _bridge()
    _allow_live_quota(monkeypatch, b)

    def _boom(*a, **k):
        error = RuntimeError("locked")
        error.code = 401
        raise error

    monkeypatch.setattr(ab.localapi, "login", _boom)
    rec = b.forward(ab.flatten(_payload(_alert()), "local")[0][0])
    assert rec["emit"] is False and rec["skip"] == "login_failed"
    assert b.token == ""


# ---- HTTP 面（进程内临时服务，不出环回）----------------------------------

class _Server:
    """只在环回上用随机端口起一个 bridge，用例直接 POST 它。"""

    def __init__(self, bridge: ab.Bridge):
        from http.server import ThreadingHTTPServer
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), ab._handler_for(bridge))
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def post(self, body: bytes, path="/api/v2/alerts") -> tuple[int, dict]:
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=body,
                                     method="POST", headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8"))
        except (ConnectionError, ConnectionAbortedError, OSError):
            # 超限载荷：服务端读完 1MB 就回 400 关连接，客户端可能还在写剩余字节，
            # 于是在**读取响应**这一步拿到连接中止而不是 400。这仍是"被拒"语义，
            # 不是测试失败——但必须与"根本没连上"区分开（后者是真故障）。
            raise

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


@pytest.fixture
def server():
    b = _bridge()
    s = _Server(b)
    try:
        yield s, b
    finally:
        s.close()


def test_healthz_reports_up_without_touching_gateway(server):
    s, _ = server
    with urllib.request.urlopen(f"http://127.0.0.1:{s.port}/healthz", timeout=10) as r:
        body = json.loads(r.read().decode("utf-8"))
    assert r.status == 200 and body["status"] == "UP" and body["service"] == "alert-bridge"


def test_valid_payload_returns_200_even_when_forwarding_fails(server, monkeypatch):
    """转发失败不该回 5xx：Alertmanager 会重试，而重试不会让网关复活，只会放大压力。"""
    s, b = server
    b.token = "t"
    _allow_live_quota(monkeypatch, b)
    monkeypatch.setattr(ab.localapi, "stream_chat",
                        lambda *a, **k: (_ for _ in ()).throw(ConnectionRefusedError("refused")))
    status, body = s.post(json.dumps(_payload(_alert(summary="上游限流"))).encode("utf-8"))
    assert status == 200 and body["received"] == 1
    assert b.stats["forwarded"] == 1 and b.stats["errors"] == 1


def test_malformed_payload_returns_400_not_5xx(server):
    s, b = server
    for bad in (b"{not json", b'["a","list"]', b""):
        status, body = s.post(bad)
        assert status == 400, f"载荷 {bad!r} 应回 400"
        assert body["code"] == "BAD_PAYLOAD"
    assert b.stats["errors"] == 3


def test_unknown_path_returns_404(server):
    s, _ = server
    status, body = s.post(b"{}", path="/api/v1/alerts")
    assert status == 404 and body["code"] == "NOT_FOUND"


def test_oversized_payload_is_rejected(server):
    """超限载荷被拒（400 或写体时连接被重置，两者都是"没被接收"的同一语义）。

    服务端刻意**不读**超限的体（读完再判等于把拒收变成一次内存放大），
    所以客户端可能在写剩余字节时先拿到连接重置。断言按"被拒"判，不按具体错误码判——
    但必须排除"服务端收下了"这种可能。
    """
    s, _ = server
    big = b'{"alerts":[]}' + b" " * (1_000_001)
    try:
        status, _ = s.post(big)
        assert status == 400
    except (ConnectionError, OSError):
        pass      # 写体阶段被重置 = 同样被拒


def test_group_cap_records_a_skip_row(server, monkeypatch):
    """超限丢弃必须落一行台账——"为什么这条告警没进系统"要能从记录里回答。"""
    s, b = server
    b.group_cap = 1
    written = []
    monkeypatch.setattr(b, "ledger", lambda rec: written.append(rec))
    b.token = "t"
    _allow_live_quota(monkeypatch, b)
    monkeypatch.setattr(ab.localapi, "stream_chat", lambda *a, **k: {
        "status": 200, "meta": {}, "done": {}, "error": None, "ttft_s": 0.1, "total_s": 0.2})
    status, body = s.post(json.dumps(_payload(_alert(), _alert(name="Other"))).encode("utf-8"))
    assert status == 200 and body["dropped"] == 1
    assert any(r.get("skip") == "group_cap" for r in written)


# ---- 出口白名单（复用 localapi 的 SSRF 面）--------------------------------

def test_bridge_inherits_loopback_only_exit_allowlist():
    """桥打网关的出口走 localapi.assert_local——不能被载荷里的任何字段改写。"""
    for bad in ("http://evil.example.com/v1", "https://127.0.0.1:8081/x",
                "http://user:pw@localhost:8081/x"):
        with pytest.raises(ValueError):
            localapi.assert_local(bad)
