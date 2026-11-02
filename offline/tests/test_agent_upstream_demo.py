"""offline/agent_upstream_demo.py 的离线单测：HTTP 层全换替身，不打真网关。

被测对象是 **agent 侧**策略——有界状态机、正文解析、渲染与失败路径。故 localapi 的三个
出口（assert_local / login / _open）都换成脚本化替身；只有 SSRF 那条用例用真的
assert_local，因为"底座只被环回调用"本身就是它要验的行为。

替身与真网关的对应关系（对照 OpenAiChatSink）：
  _open(req, timeout) 成功 → 返回 SSE 流：若干 data: 帧 + data: [DONE]；
  失败 → 抛 urllib.error.HTTPError（chat() 靠它做 401 重登与错误话术）。
"""
from __future__ import annotations

import io
import json
import sys
import urllib.error
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "offline"))
import agent_upstream_demo as demo  # noqa: E402
import localapi  # noqa: E402


# —— 构造与真网关同形的正文/流 ——

def answer_md(body: str, actions=(), refs=(), failure: bool = False) -> str:
    """按 OpenAiChatSink 的注入顺序拼正文：答案 → 只读行动契约 → 参考来源 →（异常帧的系统提示）。"""
    parts = [body]
    if actions:
        parts.append("\n\n## 只读行动契约\n")
        for i, (step, cmd) in enumerate(actions, 1):
            parts.append(f"{i}. {step}\n   `{cmd}`\n")
    if refs:
        parts.append("\n\n## 参考来源\n")
        for breadcrumb, service in refs:
            parts.append(f"- {breadcrumb}（{service}）\n")
    if failure:
        parts.append("\n\n【系统提示】本轮回答因网关内部异常中断，请稍后重试或联系值班 SRE。")
    return "".join(parts)


def sse_lines(answer: str) -> list[bytes]:
    chunk = json.dumps({"choices": [{"index": 0, "delta": {"content": answer},
                                     "finish_reason": None}]}, ensure_ascii=False)
    return [b"data: " + chunk.encode("utf-8") + b"\n", b"\n", b"data: [DONE]\n"]


def http_error(code: int, body: bytes = b"") -> urllib.error.HTTPError:
    return urllib.error.HTTPError("http://localhost:8081/v1/chat/completions", code,
                                  "fake", {}, io.BytesIO(body))


class _FakeStream:
    """with localapi._open(...) as resp: for raw in resp —— 故 __enter__ 交出可迭代的行。"""

    def __init__(self, lines: list[bytes]):
        self._lines = lines

    def __enter__(self):
        return iter(self._lines)

    def __exit__(self, *exc):
        return False


class _FakeGateway:
    """脚本化网关：responses 里依次是正文（→ SSE）或异常（→ 原样抛）。"""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls: list = []
        self.logins = 0

    def login(self, username, password=None):
        self.logins += 1
        return "jwt-for-" + username

    def open(self, req, timeout):
        self.calls.append(req)
        if not self.responses:
            # 预算外调用：状态机"至多 MAX_GATEWAY_CALLS 次"的红线在这里断掉。
            raise AssertionError("agent 发起了预算外的网关调用")
        item = self.responses.pop(0)
        if isinstance(item, Exception):
            raise item
        return _FakeStream(sse_lines(item))

    @property
    def trace_ids(self):
        return [_header(r, "X-Trace-Id") for r in self.calls]

    @property
    def messages(self):
        out = []
        for r in self.calls:
            body = json.loads(r.data.decode("utf-8"))
            out.append(body["messages"][-1]["content"])
        return out


def _header(req, name: str):
    for k, v in req.header_items():
        if k.lower() == name.lower():
            return v
    return None


@pytest.fixture(autouse=True)
def _restore_base():
    """GatewayClient.__init__ 会写 localapi.BASE（单基址设计的唯一写口），用例间要还原。"""
    saved = localapi.BASE
    yield
    localapi.BASE = saved


@pytest.fixture()
def install(monkeypatch):
    def _install(responses, username="sre-full", password="demo-pw"):
        fake = _FakeGateway(responses)
        monkeypatch.setattr(localapi, "assert_local", lambda url: url)
        monkeypatch.setattr(localapi, "login", fake.login)
        monkeypatch.setattr(localapi, "_open", fake.open)
        client = demo.GatewayClient("http://localhost:8081", username, password)
        return fake, client
    return _install


# —— parse_answer：标题切分与标记识别 ——

def test_parse_answer_success_order_body_actions_refs():
    text = answer_md("数据库连接池打满，建议先看慢查询。",
                     actions=[("查看数据库连接池", "mysql -e 'show status like \"Threads_connected\"'"),
                              ("查看慢查询", "mysql -e 'select * from mysql.slow_log limit 20'")],
                     refs=[("ops/db-pool-tuning#c1", "order-service")])
    parsed = demo.parse_answer(text)
    assert parsed.refusal is False and parsed.failed is False
    assert parsed.text == "数据库连接池打满，建议先看慢查询。"
    assert parsed.actions == [("查看数据库连接池", "mysql -e 'show status like \"Threads_connected\"'"),
                              ("查看慢查询", "mysql -e 'select * from mysql.slow_log limit 20'")]
    assert parsed.refs == [("ops/db-pool-tuning#c1", "order-service")]


def test_parse_answer_actions_only():
    parsed = demo.parse_answer(answer_md("先确认实例状态。",
                                         actions=[("查看实例", "kubectl get pods -n prod")]))
    assert parsed.text == "先确认实例状态。"
    assert parsed.actions == [("查看实例", "kubectl get pods -n prod")]
    assert parsed.refs == []


def test_parse_answer_failure_frame_is_not_a_conclusion():
    """异常帧 = 契约 → 来源 → 系统提示；正文要摘掉系统提示，且 failed 置位。"""
    text = answer_md("", actions=[("查看日志", "kubectl logs deploy/order -n prod --tail=50")],
                     refs=[("ops/logs#c2", "order-service")], failure=True)
    parsed = demo.parse_answer(text)
    assert parsed.failed is True
    assert "【系统提示】" not in parsed.text
    assert parsed.actions == [("查看日志", "kubectl logs deploy/order -n prod --tail=50")]
    assert parsed.refs == [("ops/logs#c2", "order-service")]


def test_parse_answer_refusal_marker():
    parsed = demo.parse_answer("当前知识库中没有足够相关的参考，无法可靠作答。请补充错误码或服务名。")
    assert parsed.refusal is True
    assert parsed.actions == [] and parsed.refs == []


def test_parse_answer_headers_take_last_occurrence():
    """正文里提到同名标题不应截断真正的尾部区段（rfind 而非 find）。"""
    text = answer_md("排查思路参见 ## 参考来源 一节的写法。",
                     refs=[("ops/db-pool-tuning#c1", "order-service")])
    parsed = demo.parse_answer(text)
    assert parsed.text == "排查思路参见 ## 参考来源 一节的写法。"
    assert parsed.refs == [("ops/db-pool-tuning#c1", "order-service")]


def test_join_sse_skips_non_data_and_bad_json():
    chunk = json.dumps({"choices": [{"delta": {"content": "结论"}}]}, ensure_ascii=False)
    lines = [b"event: ping\n", b"data: {oops\n",
             ("data: " + chunk + "\n").encode("utf-8"),
             b"data: [DONE]\n",
             b'data: {"choices":[{"delta":{"content":"should-not-appear"}}]}\n']
    assert demo._join_sse(lines) == "结论"


# —— 告警 → query / 标识符 / trace_id ——

def test_build_query_carries_alert_text_and_labels():
    q = demo.build_query(demo.SAMPLE_ALERT)
    assert "50012_DB_TIMEOUT" in q and "order-service" in q and "prod" in q
    assert q.splitlines()[0].startswith("收到一条运维告警")


def test_alert_identifiers_from_text_and_labels():
    ids = demo.alert_identifiers(demo.SAMPLE_ALERT)
    assert "50012_DB_TIMEOUT" in ids and "order-service" in ids


def test_alert_identifiers_empty_for_bare_summary():
    assert demo.alert_identifiers({"annotations": {"summary": "接口变慢了"}}) == []


def test_new_trace_id_fits_header_contract():
    tid = demo.new_trace_id()
    assert demo.TRACE_ID_RE.match(tid), tid


# —— 有界状态机 ——

def test_step1_with_actions_answers_in_one_call(install):
    fake, client = install([answer_md("连接池打满。",
                                      actions=[("查看连接池", "mysql -e 'show status'")],
                                      refs=[("ops/db-pool-tuning#c1", "order-service")])])
    report = demo.TriageAgent(client).run(demo.SAMPLE_ALERT)
    assert report["gateway_calls"] == 1 and report["budget"] == demo.MAX_GATEWAY_CALLS
    assert report["escalated"] is False
    assert report["steps"][0].outcome == "answered"
    assert report["answer"].actions and report["answer"].refs
    assert len(fake.calls) == 1
    assert fake.trace_ids == [report["trace_id"]]


def test_refusal_triggers_identifier_followup(install):
    fake, client = install([
        "当前知识库中没有足够相关的参考，无法可靠作答。请补充错误码或服务名。",
        answer_md("超时源于连接池耗尽。",
                  actions=[("查看连接池", "mysql -e 'show status like \"Threads_connected\"'")],
                  refs=[("ops/db-pool-tuning#c1", "order-service")]),
    ])
    report = demo.TriageAgent(client).run(demo.SAMPLE_ALERT)
    assert report["gateway_calls"] == 2 and report["escalated"] is False
    assert report["steps"][0].outcome == "refusal"
    assert report["steps"][1].phase == "followup-refusal"
    assert "50012_DB_TIMEOUT" in fake.messages[1] and "order-service" in fake.messages[1]
    assert fake.trace_ids == [report["trace_id"]] * 2  # 同一条 incident 串在一个键上


def test_answer_without_actions_triggers_contract_followup(install):
    fake, client = install([
        "疑似数据库超时。",
        answer_md("请先只读确认连接池。", actions=[("查看连接池", "mysql -e 'show status'")]),
    ])
    report = demo.TriageAgent(client).run(demo.SAMPLE_ALERT)
    assert report["gateway_calls"] == 2
    assert report["steps"][0].outcome == "no-actions"
    assert report["steps"][1].phase == "followup-actions"
    assert "只读" in fake.messages[1]


def test_second_refusal_escalates_without_third_call(install):
    fake, client = install([
        "无法可靠作答：知识库没有足够相关的参考。",
        "无法可靠作答：知识库没有足够相关的参考。",
    ])
    report = demo.TriageAgent(client).run(demo.SAMPLE_ALERT)
    assert report["gateway_calls"] == demo.MAX_GATEWAY_CALLS
    assert len(fake.calls) == demo.MAX_GATEWAY_CALLS  # 绝不第三次
    assert report["escalated"] is True
    assert report["steps"][1].outcome == "refusal"
    assert "仍拒答" in report["escalation_reason"]


def test_refusal_without_identifiers_escalates_at_once(install):
    fake, client = install(["无法可靠作答：请补充错误码或服务名。"])
    report = demo.TriageAgent(client).run({"annotations": {"summary": "接口变慢了"}})
    assert report["gateway_calls"] == 1 and len(fake.calls) == 1
    assert report["escalated"] is True
    assert "无可补充的标识符" in report["escalation_reason"]


def test_failure_frame_on_step1_escalates_without_second_call(install):
    fake, client = install([answer_md("", refs=[("ops/x#c1", "order-service")], failure=True)])
    report = demo.TriageAgent(client).run(demo.SAMPLE_ALERT)
    assert report["gateway_calls"] == 1 and len(fake.calls) == 1
    assert report["steps"][0].outcome == "gateway-failed"
    assert report["escalated"] is True and report["answer"] is None


def test_failure_frame_on_step2_escalates(install):
    fake, client = install([
        "疑似数据库超时。",
        answer_md("", actions=[("查看连接池", "mysql -e 'show status'")], failure=True),
    ])
    report = demo.TriageAgent(client).run(demo.SAMPLE_ALERT)
    assert report["gateway_calls"] == 2 and len(fake.calls) == 2
    assert report["steps"][1].outcome == "gateway-failed"
    assert report["escalated"] is True and report["answer"] is None


# —— 401 自愈与错误话术（不泄露口令） ——

def test_401_relogin_once_then_succeeds(install):
    fake, client = install([
        http_error(401, b'{"code":"UNAUTHORIZED"}'),
        answer_md("连接池打满。", actions=[("查看连接池", "mysql -e 'show status'")]),
    ])
    report = demo.TriageAgent(client).run(demo.SAMPLE_ALERT)
    assert report["gateway_calls"] == 1 and report["escalated"] is False
    assert fake.logins == 2  # 首登 + 401 后重登，恰好一次
    assert len(fake.calls) == 2


def test_401_twice_raises_and_never_leaks_password(install):
    fake, client = install([http_error(401), http_error(401)], password="super-secret-pw")
    with pytest.raises(RuntimeError) as exc:
        client.chat("hi", demo.new_trace_id())
    msg = str(exc.value)
    assert "401" in msg and "super-secret-pw" not in msg
    assert fake.logins == 2


def test_http_500_carries_status_and_short_detail(install):
    fake, client = install([http_error(500, b"boom-from-gateway")])
    with pytest.raises(RuntimeError) as exc:
        client.chat("hi", demo.new_trace_id())
    msg = str(exc.value)
    assert "HTTP 500" in msg and "boom-from-gateway" in msg


# —— SSRF：真 assert_local 才验得出 ——

def test_gateway_client_rejects_non_loopback_base():
    with pytest.raises(ValueError):
        demo.GatewayClient("http://169.254.169.254:8081", "sre-full", "pw")
    with pytest.raises(ValueError):
        localapi.assert_local("https://localhost:8081")


# —— 渲染与 CLI ——

def test_render_includes_contract_verbatim_and_refs(install):
    fake, client = install([answer_md("连接池打满，先只读确认。",
                                      actions=[("查看连接池", "mysql -e 'show status'")],
                                      refs=[("ops/db-pool-tuning#c1", "order-service")])])
    report = demo.TriageAgent(client).run(demo.SAMPLE_ALERT)
    out = demo.render(report)
    assert report["trace_id"] in out
    assert "处置建议" in out and "agent 不执行任何命令" in out
    assert "mysql -e 'show status'" in out  # 逐字，未经 agent 改写
    assert "ops/db-pool-tuning#c1（order-service）" in out


def test_render_escalation_has_reason_and_no_answer(install):
    fake, client = install(["无法可靠作答：请补充错误码或服务名。"])
    report = demo.TriageAgent(client).run({"annotations": {"summary": "接口变慢了"}})
    out = demo.render(report)
    assert "【升级给人】" in out and "无可补充的标识符" in out
    assert "处置建议" not in out


def test_main_requires_demo_password(monkeypatch, capsys):
    monkeypatch.delenv("DEMO_PASSWORD", raising=False)
    monkeypatch.setattr(sys, "argv", ["agent_upstream_demo.py"])
    assert demo.main() == 1
    err = capsys.readouterr().err
    assert "DEMO_PASSWORD" in err and "未设置" in err


def test_main_prints_report_on_success(monkeypatch, capsys, install):
    fake, _ = install([answer_md("连接池打满。",
                                 actions=[("查看连接池", "mysql -e 'show status'")],
                                 refs=[("ops/db-pool-tuning#c1", "order-service")])])
    monkeypatch.setenv("DEMO_PASSWORD", "demo-pw")
    monkeypatch.setattr(sys, "argv", ["agent_upstream_demo.py"])
    assert demo.main() == 0
    out = capsys.readouterr().out
    assert "OpsShield 上游多步排障 agent demo" in out
    assert "网关调用次数: 1 / 预算 2" in out
    assert "mysql -e 'show status'" in out
