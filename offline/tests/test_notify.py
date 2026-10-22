# -*- coding: utf-8 -*-
"""人类通知通道转发器（`scripts/notify.py`）的回归锁。

锁四件事：

  1. **处置口径**——未配置 webhook 时落 JSONL + 回 200（重试不会让配置凭空出现）；
     配置后成功回 200、上游失败回 503（让 Alertmanager 重试）。缺了这条，
     "人类收不到告警"会伪装成"系统没告警"。
  2. **原样转发**——载荷是 Alertmanager 的契约，notify 不在中间改写它（改写等于
     发明第二份通知格式契约）。
  3. **台账字段**——未配置时的本地兜底行必须带 ts/status/count/alertnames/group_key/text，
     且写失败不抛（通知不该因为日志写不了而消失）。
  4. **HTTP 面**——/healthz 只答存活与通道配置（**不泄露 webhook URL**，路径/query 常带
     token）；畸形载荷 400、超限 413、未知路径 404。

不出环回：autouse 夹具把真转发限制在 127.0.0.1（本用例起的假上游），其余用例注入假 forwarder。
"""
from __future__ import annotations

import json
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

import pytest

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts"))

import notify  # noqa: E402


@pytest.fixture(autouse=True)
def _loopback_only(monkeypatch):
    """把"不出环回"从文档承诺变成机制：真转发只许打本进程起的假上游。"""
    real_forward = notify.forward

    def guarded(payload, webhook, timeout=notify.TIMEOUT_S):
        host = urlparse(webhook).hostname
        assert host in ("127.0.0.1", "localhost"), f"用例不得打真实上游：{host}"
        return real_forward(payload, webhook, timeout)

    monkeypatch.setattr(notify, "forward", guarded)


def _payload(*alerts, status="firing", group_key='{}:{alertname="X"}') -> dict:
    return {"version": "4", "status": status, "groupKey": group_key,
            "commonLabels": {}, "commonAnnotations": {}, "alerts": list(alerts)}


def _alert(name="OpsPilotDown", **labels) -> dict:
    return {"status": "firing", "labels": {"alertname": name, "job": "opspilot", **labels},
            "annotations": {}, "startsAt": "2026-10-22T00:00:00Z",
            "endsAt": "0001-01-01T00:00:00Z"}


# ---- 纯函数：摘要（台账与可读文本的唯一来源）--------------------------------

def test_summarize_counts_alerts_and_dedupes_names():
    p = _payload(_alert("OpsPilotDown"), _alert("OpsPilotDown"), _alert("OpsPilotHighInflight"))
    s = notify.summarize(p)
    assert s["count"] == 3
    assert s["alertnames"] == ["OpsPilotDown", "OpsPilotHighInflight"], "同名规则只念一次"
    assert s["status"] == "firing" and s["group_key"] == '{}:{alertname="X"}'


def test_summarize_tolerates_malformed_payload():
    """载荷是不可信输入：缺 alerts / alerts 不是 list / 元素不是 dict 都不得抛。"""
    assert notify.summarize({})["count"] == 0
    assert notify.summarize({"alerts": "nope"})["count"] == 0
    assert notify.summarize({"alerts": [1, "x", None]})["count"] == 3
    assert notify.summarize({"alerts": [1, "x", None]})["alertnames"] == []


def test_format_text_is_one_line_human_readable():
    text = notify.format_text(_payload(_alert("OpsPilotDown")))
    assert text.startswith("[firing] 1 条告警：") and "OpsPilotDown" in text
    assert "\n" not in text


# ---- 台账：未配置时的本地兜底 ----------------------------------------------

def test_append_ledger_writes_one_jsonl_row_per_payload(tmp_path):
    path = tmp_path / "human-alerts.jsonl"
    row = notify.append_ledger(_payload(_alert("OpsPilotDown")), path, reason="no_webhook",
                               now_ms=111)
    assert row is not None and row["ts"] == 111 and row["reason"] == "no_webhook"
    notify.append_ledger(_payload(_alert("OpsPilotHighInflight")), path, reason="no_webhook")
    lines = [json.loads(x) for x in path.read_text(encoding="utf-8").splitlines()]
    assert len(lines) == 2
    assert set(lines[0]) >= {"ts", "status", "count", "alertnames", "group_key", "reason", "text"}
    assert lines[0]["alertnames"] == ["OpsPilotDown"] and lines[1]["alertnames"] == ["OpsPilotHighInflight"]


def test_append_ledger_write_failure_does_not_raise(tmp_path, capsys):
    """日志写不了不是"通知成功"的同义词，但更不能把告警链路打死：只 WARN。"""
    row = notify.append_ledger(_payload(_alert()), tmp_path, reason="no_webhook")  # 路径是目录
    assert row is None
    assert "台账写入失败" in capsys.readouterr().err


# ---- handle_payload：状态码契约 --------------------------------------------

def test_without_webhook_records_and_returns_200(tmp_path):
    path = tmp_path / "human-alerts.jsonl"
    called = []
    code, body = notify.handle_payload(
        _payload(_alert("OpsPilotDown")), webhook="", ledger_path=path,
        forwarder=lambda *a, **k: called.append(a) or (True, 200, ""))
    assert code == 200 and body["status"] == "recorded" and called == [], "未配置时不得尝试上游"
    assert json.loads(path.read_text(encoding="utf-8"))["reason"] == "no_webhook"


def test_forward_success_returns_200(tmp_path):
    code, body = notify.handle_payload(
        _payload(_alert()), webhook="https://im.example.com/hook",
        ledger_path=tmp_path / "x.jsonl",
        forwarder=lambda payload, webhook, timeout: (True, 204, ""))
    assert code == 200 and body["status"] == "forwarded" and body["upstream"] == 204
    assert body["channel"] == "generic"


def test_upstream_failure_returns_503_so_alertmanager_retries(tmp_path):
    code, body = notify.handle_payload(
        _payload(_alert()), webhook="https://im.example.com/hook",
        ledger_path=tmp_path / "x.jsonl",
        forwarder=lambda payload, webhook, timeout: (False, 500, "HTTP 500"))
    assert code == 503 and body["status"] == "upstream_failed"
    assert body["upstream"] == 500 and "HTTP 500" in body["error"]


# ---- 配置入口：env 与 URL 形状 ---------------------------------------------

def test_webhook_from_env_trims_and_defaults_empty():
    assert notify.webhook_from_env({}) == ""
    assert notify.webhook_from_env({"OPSPILOT_HUMAN_WEBHOOK": "  https://x/y  "}) == "https://x/y"


def test_validate_webhook_accepts_empty_and_http_forms():
    notify.validate_webhook("")
    notify.validate_webhook("http://127.0.0.1:9999/hook")
    notify.validate_webhook("https://im.example.com/hook?token=abc")


def test_forward_connection_refused_is_failure_not_exception():
    """上游连不上是数据不是异常：回 503 让 Alertmanager 重试，进程不许因此冒泡。"""
    ok, code, detail = notify.forward(_payload(_alert()), "http://127.0.0.1:1/hook", timeout=2)
    assert ok is False and code is None and detail, "必须带失败摘要，便于台账/日志定位"


def test_validate_webhook_rejects_non_http_and_userinfo():
    for bad in ("ftp://im.example.com/hook", "file:///etc/passwd",
                "https://user:pw@im.example.com/hook", "https:///no-host"):
        with pytest.raises(ValueError):
            notify.validate_webhook(bad)


# ---- HTTP 面（进程内临时服务，不出环回）------------------------------------

class _NotifyServer:
    def __init__(self, ledger_path: Path, webhook: str = "", forwarder=None):
        self.httpd = ThreadingHTTPServer(
            ("127.0.0.1", 0), notify._handler_for(webhook, ledger_path, forwarder))
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def get(self, path="/healthz") -> tuple[int, dict]:
        with urllib.request.urlopen(f"http://127.0.0.1:{self.port}{path}", timeout=10) as r:
            return r.status, json.loads(r.read().decode("utf-8"))

    def post(self, body: bytes, path="/api/v2/alerts") -> tuple[int, dict]:
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=body,
                                     method="POST", headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8"))

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class _FakeUpstream:
    """假人类 webhook：记录收到的原始载荷，按预设状态码/响应体回话。"""

    def __init__(self, status: int = 200, body: dict | None = None):
        self.status = status
        self.body = body if body is not None else {}
        self.calls: list[dict] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                n = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(n)
                outer.calls.append({"path": self.path, "raw": raw,
                                    "content_type": self.headers.get("Content-Type")})
                payload = json.dumps(outer.body).encode("utf-8")
                self.send_response(outer.status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def log_message(self, fmt, *args):
                pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/hook"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


@pytest.fixture
def server(tmp_path):
    s = _NotifyServer(tmp_path / "human-alerts.jsonl")
    try:
        yield s
    finally:
        s.close()


def test_healthz_reports_up_and_does_not_leak_webhook_url(tmp_path):
    secret = "http://127.0.0.1:9/hook?token=super-secret"
    s = _NotifyServer(tmp_path / "x.jsonl", webhook=secret)
    try:
        status, body = s.get("/healthz")
    finally:
        s.close()
    assert status == 200 and body["status"] == "UP" and body["service"] == "notify"
    assert "super-secret" not in json.dumps(body, ensure_ascii=False), "台账/健康面不得泄露 hook token"
    assert body["human_channel"] == "已配置(host=127.0.0.1)"


def test_healthz_without_webhook_reports_ledger_only(server):
    status, body = server.get("/healthz")
    assert status == 200 and body["human_channel"].startswith("未配置")


def test_post_without_webhook_returns_200_and_writes_ledger(server, tmp_path):
    status, body = server.post(json.dumps(_payload(_alert("OpsPilotDown"))).encode("utf-8"))
    assert status == 200 and body["status"] == "recorded"
    ledger = (tmp_path / "human-alerts.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(ledger) == 1 and json.loads(ledger[0])["alertnames"] == ["OpsPilotDown"]


def test_post_forwards_raw_payload_to_upstream(tmp_path):
    up = _FakeUpstream(status=200)
    s = _NotifyServer(tmp_path / "x.jsonl", webhook=up.url)
    payload = _payload(_alert("OpsPilotDown"))
    try:
        status, body = s.post(json.dumps(payload).encode("utf-8"))
    finally:
        s.close()
        up.close()
    assert status == 200 and body["status"] == "forwarded" and body["upstream"] == 200
    assert len(up.calls) == 1 and up.calls[0]["path"] == "/hook"
    assert json.loads(up.calls[0]["raw"].decode("utf-8")) == payload, "必须原样转发，不改写载荷"
    assert "application/json" in up.calls[0]["content_type"]


def test_post_returns_503_when_upstream_rejects(tmp_path):
    up = _FakeUpstream(status=500)
    s = _NotifyServer(tmp_path / "x.jsonl", webhook=up.url)
    try:
        status, body = s.post(json.dumps(_payload(_alert())).encode("utf-8"))
    finally:
        s.close()
        up.close()
    assert status == 503 and body["status"] == "upstream_failed" and body["upstream"] == 500


def test_malformed_payload_returns_400(server):
    for bad in (b"{not json", b'["a","list"]', b""):
        status, body = server.post(bad)
        assert status == 400 and body["code"] == "BAD_PAYLOAD"


def test_unknown_path_returns_404(server):
    status, body = server.post(b"{}", path="/api/v1/alerts")
    assert status == 404 and body["code"] == "NOT_FOUND"


def test_get_unknown_path_returns_404(server):
    with pytest.raises(urllib.error.HTTPError) as ei:
        urllib.request.urlopen(f"http://127.0.0.1:{server.port}/nope", timeout=10)
    assert ei.value.code == 404


# ---- 渠道自动识别与消息形状（G1 2026-10-22 扩展）--------------------------

def test_channel_auto_detects_common_bot_urls():
    cases = {
        "https://hooks.slack.com/services/T/B/X": "slack",
        "https://open.feishu.cn/open-apis/bot/v2/hook/abc": "feishu",
        "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc": "wecom",
        "https://relay.example.com/alertmanager": "generic",
    }
    for url, kind in cases.items():
        ch = notify.channel_from_env({"OPSPILOT_HUMAN_WEBHOOK": url})
        assert ch.kind == kind and ch.configured(), (url, ch)


def test_channel_telegram_requires_token_and_chat():
    ch = notify.channel_from_env({
        "OPSPILOT_HUMAN_WEBHOOK": "",
        "OPSPILOT_TELEGRAM_BOT_TOKEN": "123:abc",
        "OPSPILOT_TELEGRAM_CHAT_ID": "42",
    })
    assert ch.kind == "telegram" and ch.configured()
    assert "123:abc" not in ch.label(), "健康/日志不得回显 bot token"


def test_channel_rejects_unknown_kind_and_incomplete_telegram():
    with pytest.raises(ValueError):
        notify.channel_from_env({"OPSPILOT_HUMAN_CHANNEL": "carrier-pigeon"})
    with pytest.raises(ValueError):
        notify.channel_from_env({"OPSPILOT_HUMAN_CHANNEL": "telegram"})


def test_forward_channel_posts_slack_shape_to_loopback():
    up = _FakeUpstream(status=200)
    ch = notify.HumanChannel(kind="slack", webhook=up.url)
    try:
        ok, code, detail = notify.forward_channel(_payload(_alert("OpsPilotDown")), ch)
    finally:
        up.close()
    assert ok and code == 200 and detail == ""
    sent = json.loads(up.calls[0]["raw"].decode("utf-8"))
    assert sent == {"text": notify.format_text(_payload(_alert("OpsPilotDown")))}


def test_feishu_signature_fields_when_secret_set():
    ch = notify.HumanChannel(kind="feishu", webhook="https://open.feishu.cn/open-apis/bot/v2/hook/x",
                             feishu_secret="s3cret")
    body = notify.build_message(_payload(_alert()), ch)
    assert body["msg_type"] == "text" and "timestamp" in body and "sign" in body
    assert "s3cret" not in json.dumps(body), "消息体不得回显 secret"


def test_feishu_http_200_business_error_is_failure():
    up = _FakeUpstream(status=200, body={"code": 19021, "msg": "sign match fail"})
    ch = notify.HumanChannel(kind="feishu", webhook=up.url)
    try:
        ok, code, detail = notify.forward_channel(_payload(_alert()), ch)
    finally:
        up.close()
    assert not ok and code == 200 and "19021" in detail, "HTTP 200 但业务码非 0 必须判失败"


def test_build_message_per_channel_shape():
    p = _payload(_alert("OpsPilotDown"))
    text = notify.format_text(p)
    assert notify.build_message(p, notify.HumanChannel(kind="generic")) == p
    assert notify.build_message(p, notify.HumanChannel(kind="slack")) == {"text": text}
    assert notify.build_message(p, notify.HumanChannel(kind="feishu")) == {
        "msg_type": "text", "content": {"text": text}}
    assert notify.build_message(p, notify.HumanChannel(kind="wecom")) == {
        "msgtype": "text", "text": {"content": text}}
    assert notify.build_message(p, notify.HumanChannel(kind="telegram", chat_id="42")) == {
        "chat_id": "42", "text": text, "disable_web_page_preview": True}


def test_oversized_payload_is_rejected(server):
    """超限被拒（413 或写体时连接被重置，两者都是"没被接收"的同一语义）。

    服务端刻意**不读**超限的体（读完再判等于把拒收变成一次内存放大），
    所以客户端可能在写剩余字节时先拿到连接重置。断言按"被拒"判，不按具体错误码判。
    """
    big = b'{"alerts":[]}' + b" " * (1_000_001)
    try:
        status, body = server.post(big)
        assert status == 413 and body["code"] == "PAYLOAD_TOO_LARGE"
    except (ConnectionError, OSError):
        pass      # 写体阶段被重置 = 同样被拒
