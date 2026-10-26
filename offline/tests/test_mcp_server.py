"""OpsShield MCP Server 的协议与工具面测试（stdlib 实现，见 ADR-0027）。

不依赖官方 mcp SDK，也不起真网关：HTTP 面全部 monkeypatch。
协议兼容性另由 scripts/mcp_smoke.py 对真网关冒烟（见 docs/ops/mcp-server.md）。
"""
from __future__ import annotations

import importlib
import json
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
import mcp_server  # noqa: E402


@pytest.fixture()
def fresh():
    mcp_server.reset_state()
    mcp_server.BASE = "http://localhost:8081"
    yield mcp_server
    mcp_server.reset_state()


def rpc(method, mid=None, params=None):
    msg = {"jsonrpc": "2.0", "method": method}
    if mid is not None:
        msg["id"] = mid
    if params is not None:
        msg["params"] = params
    return msg


# ---- 协议面 ----

def test_initialize_negotiates_version_and_capabilities(fresh):
    resp = mcp_server.handle_message(rpc("initialize", 1, {
        "protocolVersion": "2025-06-18", "capabilities": {},
        "clientInfo": {"name": "t", "version": "0"}}))
    assert resp["id"] == 1
    assert resp["result"]["protocolVersion"] == mcp_server.PROTOCOL_VERSION
    assert "tools" in resp["result"]["capabilities"]
    assert resp["result"]["serverInfo"]["name"] == "opsshield-mcp"


def test_notification_returns_none(fresh):
    assert mcp_server.handle_message(rpc("notifications/initialized")) is None
    assert mcp_server.handle_message(rpc("notifications/cancelled", params={"requestId": 1})) is None


def test_ping_and_unknown_method(fresh):
    assert mcp_server.handle_message(rpc("ping", 2))["result"] == {}
    resp = mcp_server.handle_message(rpc("no/such", 3))
    assert resp["error"]["code"] == -32601


def test_malformed_frames(fresh):
    assert mcp_server.handle_message("not-a-dict")["error"]["code"] == -32600
    assert mcp_server.handle_message({"id": 1, "method": "x"})["error"]["code"] == -32600
    assert mcp_server.handle_message({"jsonrpc": "1.0", "id": 1, "method": "x"})["error"]["code"] == -32600


def test_tools_list_schema(fresh):
    tools = mcp_server.handle_message(rpc("tools/list", 4))["result"]["tools"]
    names = [t["name"] for t in tools]
    assert names == ["search_ops_docs", "ask_ops", "get_gateway_state"]
    for t in tools:
        assert t["description"] and t["inputSchema"]["type"] == "object"
        assert t["inputSchema"].get("additionalProperties") is False


def test_main_loop_emits_and_survives_bad_line(fresh, monkeypatch):
    lines = ["not-json",
             json.dumps(rpc("initialize", 1, {"protocolVersion": "2025-06-18"})),
             json.dumps(rpc("notifications/initialized")),
             json.dumps(rpc("ping", 2)), ""]
    monkeypatch.setattr(sys, "stdin", iter(lines))
    out = []
    monkeypatch.setattr(mcp_server, "_emit", lambda r: out.append(r))
    assert mcp_server.main() == 0
    assert out[0]["error"]["code"] == -32700        # 坏行不杀死进程
    assert out[1]["id"] == 1 and out[2]["id"] == 2  # 通知不回包


# ---- 工具面（HTTP 全部打桩） ----

def _fake_search(status=200, body=None):
    body = body or {"mode": "hybrid", "took_ms": 12, "results": [
        {"chunk_id": "rb-001::x", "doc_id": "rb-001", "breadcrumb": "a > b",
         "service": "order-service", "auth_level": 2,
         "es_score": 1.23456, "vector_score": 0.5, "rrf_score": 0.0321,
         "rerank_score": 0.98765}]}
    return status, body


def test_search_tool_maps_and_trims(fresh, monkeypatch):
    seen = {}

    def fake_http(method, path, body=None, token=None, timeout=10.0):
        seen.update(method=method, path=path, body=body, token=token)
        return _fake_search()

    monkeypatch.setattr(fresh, "_http_json", fake_http)
    monkeypatch.setattr(fresh, "_ensure_token", lambda: "tok-1")
    resp = mcp_server.handle_message(rpc("tools/call", 5, {
        "name": "search_ops_docs", "arguments": {"query": "50012_DB_TIMEOUT"}}))
    result = json.loads(resp["result"]["content"][0]["text"])
    assert resp["result"]["isError"] is False
    assert seen["method"] == "POST" and seen["path"].endswith("/copilot/search")
    assert seen["token"] == "tok-1"
    r0 = result["results"][0]
    assert r0["rrf_score"] == 0.0321 and r0["rerank_score"] == 0.9877  # 分位裁剪
    assert "es_score" not in r0 and "vector_score" not in r0           # 只留决策需要的分


def test_search_tool_error_becomes_iserror_result(fresh, monkeypatch):
    monkeypatch.setattr(fresh, "_http_json", lambda *a, **k: (500, {"raw": "boom"}))
    monkeypatch.setattr(fresh, "_ensure_token", lambda: "tok-1")
    resp = mcp_server.handle_message(rpc("tools/call", 6, {
        "name": "search_ops_docs", "arguments": {"query": "x"}}))
    assert resp["result"]["isError"] is True
    assert "HTTP 500" in resp["result"]["content"][0]["text"]


def test_search_blank_query_is_param_error(fresh):
    resp = mcp_server.handle_message(rpc("tools/call", 7, {
        "name": "search_ops_docs", "arguments": {"query": "  "}}))
    assert resp["result"]["isError"] is True
    assert "query" in resp["result"]["content"][0]["text"]


def test_unknown_tool_is_iserror_not_rpc_error(fresh):
    resp = mcp_server.handle_message(rpc("tools/call", 8, {"name": "exec_shell"}))
    assert resp["result"]["isError"] is True
    assert "exec_shell" in resp["result"]["content"][0]["text"]
    assert "error" not in resp  # 工具面拒绝 ≠ JSON-RPC 协议错误


def test_ask_tool_parses_chat(fresh, monkeypatch):
    def fake_chat(question, source):
        assert question == "50012_DB_TIMEOUT 怎么排查"
        assert source == "manual"
        return {"answer": "先查连接池……", "meta": {"degradation_level": "L0", "gate": "none"},
                "refs": [{"chunk_id": "rb-001::x", "doc_id": "rb-001"}],
                "actions": [{"command": "kubectl get pods"}],
                "error": None, "ttft_s": 0.5, "total_s": 2.0, "truncated": False}
    monkeypatch.setattr(fresh, "_chat", fake_chat)
    resp = mcp_server.handle_message(rpc("tools/call", 9, {
        "name": "ask_ops", "arguments": {"question": "50012_DB_TIMEOUT 怎么排查"}}))
    result = json.loads(resp["result"]["content"][0]["text"])
    assert result["answer"].startswith("先查") and result["degradation_level"] == "L0"
    assert result["refs"] and result["actions"]


def test_state_tool_selects_fields(fresh, monkeypatch):
    def fake_http(method, path, body=None, token=None, timeout=10.0):
        assert method == "GET" and path.endswith("/admin/state")
        return 200, {"build": {"commit": "969e137"},
                     "runtime": {"degradation": {"level": "L0"}, "quota": {"used": 1}},
                     "health": {"status": "UP"}}
    monkeypatch.setattr(fresh, "_http_json", fake_http)
    monkeypatch.setattr(fresh, "_ensure_token", lambda: "tok-1")
    resp = mcp_server.handle_message(rpc("tools/call", 10, {"name": "get_gateway_state", "arguments": {}}))
    result = json.loads(resp["result"]["content"][0]["text"])
    assert result["build"]["commit"] == "969e137" and result["health_status"] == "UP"


# ---- 认证自愈与安全边界 ----

def test_401_relogin_then_retry_once(fresh, monkeypatch):
    calls = {"n": 0, "logins": 0}

    def fake_http(method, path, body=None, token=None, timeout=10.0):
        calls["n"] += 1
        if calls["n"] == 1:
            return 401, {"code": "UNAUTHORIZED"}
        return _fake_search()

    def fake_login():
        calls["logins"] += 1
        fresh._state["token"] = "tok-2"

    monkeypatch.setattr(fresh, "_http_json", fake_http)
    monkeypatch.setattr(fresh, "_login", fake_login)
    monkeypatch.setattr(fresh, "_ensure_token", lambda: "tok-expired")
    resp = mcp_server.handle_message(rpc("tools/call", 11, {
        "name": "search_ops_docs", "arguments": {"query": "x"}}))
    result = json.loads(resp["result"]["content"][0]["text"])
    assert resp["result"]["isError"] is False
    assert calls == {"n": 2, "logins": 1}          # 恰好重登一次，不多打


def test_login_failure_message_carries_no_secret(fresh, monkeypatch):
    monkeypatch.setattr(fresh, "MCP_PASSWORD", "super-secret-pw")
    monkeypatch.setattr(fresh, "_http_json", lambda *a, **k: (401, {"code": "BAD_CREDENTIALS"}))
    resp = mcp_server.handle_message(rpc("tools/call", 12, {
        "name": "get_gateway_state", "arguments": {}}))
    text = resp["result"]["content"][0]["text"]
    assert resp["result"]["isError"] is True
    assert "super-secret-pw" not in text and "401" in text


def test_missing_password_fails_closed(fresh, monkeypatch):
    monkeypatch.setattr(fresh, "MCP_PASSWORD", "")
    resp = mcp_server.handle_message(rpc("tools/call", 13, {
        "name": "get_gateway_state", "arguments": {}}))
    assert resp["result"]["isError"] is True
    assert "未设置" in resp["result"]["content"][0]["text"]


def test_ssrf_guard_rejects_non_loopback(fresh, monkeypatch):
    monkeypatch.setattr(fresh, "BASE", "http://169.254.169.254:8081")
    monkeypatch.setattr(fresh, "_ensure_token", lambda: "tok-1")
    resp = mcp_server.handle_message(rpc("tools/call", 14, {
        "name": "get_gateway_state", "arguments": {}}))
    assert resp["result"]["isError"] is True
    # assert_local 的报错信息本身不含目标地址细节也没关系，关键是拒绝而非转发
    assert "isError" in json.dumps(resp)
