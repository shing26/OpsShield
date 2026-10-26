#!/usr/bin/env python3
"""OpsShield MCP Server（stdio，stdlib-only）——「可信运维上下文」的生态入口（ADR-0027）。

为什么存在：Claude Code / Cursor / Cline 等工具都说 MCP。把 OpsShield 暴露成 MCP Server，
Agent 就能零改造地把运维检索/问答接进自己的决策循环，而**全部安全机制仍留在网关侧**：
租户隔离、密级过滤、逐字抽取只读契约、配额、准入、审计。MCP 只是一层协议适配，
不是绕过这些护栏的旁路——本进程以一个配置用户登录网关，权限恰好等于该用户。

为什么 stdlib 而不是官方 mcp SDK：本仓运行时纪律是零第三方依赖（requirements-dev 只锁
pytest/locust）。MCP stdio 传输就是「按行分隔的 JSON-RPC 2.0」，v1 需要的协议面只有
initialize / tools/list / tools/call / ping 四个方法，自实现约百行，换来 CI 不新增依赖链、
不背上游 SDK 破坏性变更。代价：协议后续演进需手动对齐规范（见 ADR-0027 的后果段）。

工具（v1，全部只读）：
  search_ops_docs     纯检索（embedding+rerank，无 LLM 成本），租户/密级由登录用户决定
  ask_ops             可信问答：SSE 流 → 答案 + 溯源引用 + 只读命令契约 + 降级状态；
                      消耗网关配额、受准入控制（可能 429——这是护栏在工作，不是故障）
  get_gateway_state   网关 build/health/degradation 快照（降级时引用需人工复核）

边界（刻意不做）：
  - 不暴露任何写操作/执行工具（路线图「明确不做」#2/#3）；
  - get_readonly_commands(doc_id) 需要网关提供 chunk-action 端点后再加（当前 actions 走 ask_ops）；
  - 不做 resources/prompts/sampling 能力——工具面最小化，攻击面才小。

协议：MCP 2025-06-18（stdio = 按行分隔 JSON-RPC 2.0）。stdout 只准出现 JSON-RPC 帧，
日志一律走 stderr——stdout 被客户端当协议流解析，混入一行日志就是一次断连。
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "offline"))
from localapi import assert_local  # noqa: E402  复用既有 SSRF 防线：http+环回+解析后 IP 校验

PROTOCOL_VERSION = "2025-06-18"
SERVER_INFO = {"name": "opsshield-mcp", "version": "0.1.0"}

BASE = os.environ.get("OPSPILOT_BASE", "http://localhost:8081").rstrip("/")
MCP_USER = os.environ.get("OPSPILOT_MCP_USER", "sre-full")
# 密码回退 DEMO_PASSWORD：演示形态一个口令；生产用 OPSPILOT_MCP_PASSWORD 注入专用服务账号
MCP_PASSWORD = os.environ.get("OPSPILOT_MCP_PASSWORD") or os.environ.get("DEMO_PASSWORD") or ""

SEARCH_TIMEOUT = 30.0
STATE_TIMEOUT = 10.0
CHAT_TIMEOUT = 120.0   # 整体截止而非逐次读超时：慢速滴流不能占死连接（localapi 同口径）

_state = {"token": None}


def log(message: str) -> None:
    print(f"[mcp] {message}", file=sys.stderr, flush=True)


# ---- HTTP 面 ---------------------------------------------------------------

def _http_json(method: str, path: str, body: dict | None = None,
               token: str | None = None, timeout: float = 10.0) -> tuple[int, dict]:
    url = assert_local(BASE + path)
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "ignore")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw[:200]}


def _login() -> None:
    """登录并缓存 token。失败摘要**绝不携带用户名以外的信息**，更不回显密码。"""
    if not MCP_PASSWORD:
        raise RuntimeError("OPSPILOT_MCP_PASSWORD / DEMO_PASSWORD 未设置，无法登录网关")
    status, data = _http_json("POST", "/api/v1/auth/login",
                              {"username": MCP_USER, "password": MCP_PASSWORD})
    if status != 200 or not data.get("token"):
        raise RuntimeError(f"网关登录失败: HTTP {status}")
    _state["token"] = data["token"]


def _ensure_token() -> str:
    if not _state["token"]:
        _login()
    return _state["token"]


def _with_auth(fn) -> tuple[int, dict]:
    """401 时重登一次再试：token 有效期 24h，MCP 常驻进程必须能自愈。"""
    status, data = fn(_ensure_token())
    if status == 401:
        _login()
        status, data = fn(_state["token"])
    return status, data


def _chat(question: str, source: str) -> dict:
    """SSE 流式问答 → 汇总结构。帧协议与 offline/localapi.stream_chat 同口径。"""
    token = _ensure_token()
    url = assert_local(BASE + "/api/v1/copilot/chat/stream")
    body = json.dumps({"query": question, "source": source}).encode("utf-8")

    def do(tok: str):
        req = urllib.request.Request(url, data=body, method="POST", headers={
            "Authorization": "Bearer " + tok, "Content-Type": "application/json",
            "Accept": "text/event-stream"})
        return _read_sse(req)

    try:
        return do(token)
    except urllib.error.HTTPError as e:
        if e.code != 401:
            raise
        _login()
        return do(_state["token"])


def _read_sse(req: urllib.request.Request) -> dict:
    out: dict = {"answer": "", "meta": {}, "refs": [], "actions": [],
                 "error": None, "ttft_s": None, "total_s": 0.0, "truncated": False}
    t0 = time.time()
    event = None
    with urllib.request.urlopen(req, timeout=CHAT_TIMEOUT) as resp:
        for raw in resp:
            if time.time() - t0 > CHAT_TIMEOUT:
                out["truncated"] = True
                break
            line = raw.decode("utf-8", "replace").rstrip("\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                try:
                    data = json.loads(line[5:].strip())
                except json.JSONDecodeError:
                    continue
                if event == "delta":
                    if out["ttft_s"] is None:
                        out["ttft_s"] = round(time.time() - t0, 3)
                    out["answer"] += data.get("token", "")
                elif event == "meta":
                    out["meta"] = data
                elif event == "done":
                    out["refs"] = data.get("refs") or []
                    out["actions"] = data.get("actions") or []
                elif event == "error":
                    out["error"] = data
    out["total_s"] = round(time.time() - t0, 3)
    return out


# ---- 工具实现（全部只读） ---------------------------------------------------

def tool_search_ops_docs(args: dict) -> dict:
    query = str(args.get("query") or "").strip()
    if not query:
        raise ValueError("query 不能为空")
    mode = args.get("mode") or "hybrid"
    status, data = _with_auth(lambda tok: _http_json(
        "POST", "/api/v1/copilot/search", {"query": query, "mode": mode},
        token=tok, timeout=SEARCH_TIMEOUT))
    if status != 200:
        raise RuntimeError(f"search HTTP {status}: {json.dumps(data, ensure_ascii=False)[:200]}")
    results = [{"chunk_id": r.get("chunk_id"), "doc_id": r.get("doc_id"),
                "breadcrumb": r.get("breadcrumb"), "service": r.get("service"),
                "auth_level": r.get("auth_level"),
                "rrf_score": round(r.get("rrf_score") or 0, 4),
                "rerank_score": round(r.get("rerank_score") or 0, 4)}
               for r in (data.get("results") or [])]
    return {"mode": data.get("mode"), "took_ms": data.get("took_ms"),
            "result_count": len(results), "results": results,
            "note": "结果已按登录用户的租户与密级过滤；auth_level 是该文档块的密级"}


def tool_ask_ops(args: dict) -> dict:
    question = str(args.get("question") or "").strip()
    if not question:
        raise ValueError("question 不能为空")
    source = str(args.get("source") or "manual")
    r = _chat(question, source)
    meta = r.get("meta") or {}
    return {"answer": r["answer"], "refs": r["refs"], "actions": r["actions"],
            "gate": meta.get("gate"), "degradation_level": meta.get("degradation_level"),
            "cache_hit": meta.get("cache_hit"), "refused": meta.get("refused"),
            "error": r.get("error"), "truncated": r.get("truncated"),
            "ttft_s": r.get("ttft_s"), "total_s": r.get("total_s"),
            "note": ("actions 是从文档逐字抽取的只读命令，不是 LLM 生成；"
                     "降级档位非 L0 时引用完整性下降，需人工复核")}


def tool_get_gateway_state(args: dict) -> dict:
    status, data = _with_auth(lambda tok: _http_json(
        "GET", "/api/v1/admin/state", None, token=tok, timeout=STATE_TIMEOUT))
    if status == 403:
        # #34：/admin/state 是 platform 面；用「最小密级账号」调这里只会拿到 403。
        # 文案必须说清是账号角色问题，而不是把 403 当网关故障去排查。
        raise RuntimeError("state 403：该账号无 platform 角色（get_gateway_state 读 /admin/state；"
                           "OPSPILOT_MCP_USER 需为平台账号，最小密级账号只够 search_ops_docs / ask_ops）")
    if status != 200:
        raise RuntimeError(f"state HTTP {status}: {json.dumps(data, ensure_ascii=False)[:200]}")
    runtime = data.get("runtime") or {}
    return {"build": data.get("build"),
            "degradation": runtime.get("degradation"),
            "quota": runtime.get("quota"),
            "inflight": runtime.get("inflight"),
            "health_status": (data.get("health") or {}).get("status"),
            "note": "degradation.level 非 L0 时，检索覆盖与 LLM 质量可能下降"}


_TOOL_HANDLERS = {
    "search_ops_docs": tool_search_ops_docs,
    "ask_ops": tool_ask_ops,
    "get_gateway_state": tool_get_gateway_state,
}

TOOLS = [
    {"name": "search_ops_docs",
     "description": ("检索 OpsShield 运维文档/Runbook（纯检索，无 LLM 成本）。"
                     "结果已按登录用户的租户与密级过滤，返回 chunk_id/doc_id/breadcrumb 与相关度分，"
                     "适合先定位文档再让 Agent 引用。"),
     "inputSchema": {"type": "object", "additionalProperties": False,
                     "properties": {"query": {"type": "string", "minLength": 1,
                                              "description": "检索词或错误码，如 50012_DB_TIMEOUT"},
                                    "mode": {"type": "string",
                                             "enum": ["hybrid", "es_only", "vector_only"],
                                             "default": "hybrid",
                                             "description": "hybrid=双路召回，es_only/vector_only 用于对照诊断"}},
                     "required": ["query"]}},
    {"name": "ask_ops",
     "description": ("向 OpsShield 提运维问题，返回逐字抽取的可信回答：答案 + 溯源引用 + "
                     "只读命令契约（actions）+ 降级档位。消耗网关配额并受准入控制（可能 429）；"
                     "命令只来自文档白名单，不会被 LLM 生成或改写。"),
     "inputSchema": {"type": "object", "additionalProperties": False,
                     "properties": {"question": {"type": "string", "minLength": 1,
                                                 "description": "运维问题，最好带错误码/症状"},
                                    "source": {"type": "string", "enum": ["manual", "alert"],
                                               "default": "manual",
                                               "description": "来源标记：manual=人工提问，alert=告警桥自动调用"
                                                              "（与网关 ChatRequest.source 的 @Pattern 同口径）"}},
                     "required": ["question"]}},
    {"name": "get_gateway_state",
     "description": ("读取 OpsShield 网关自身状态：版本、降级档位、配额、在途、健康摘要。"
                     "Agent 应在采信 ask_ops 结果前用它判断当前是否处于降级。"),
     "inputSchema": {"type": "object", "additionalProperties": False, "properties": {}}},
]


# ---- JSON-RPC / MCP 协议面 --------------------------------------------------

def _ok(mid, result: dict) -> dict:
    return {"jsonrpc": "2.0", "id": mid, "result": result}


def _error(mid, code: int, message: str) -> dict:
    return {"jsonrpc": "2.0", "id": mid, "error": {"code": code, "message": message}}


def _tools_call(params: dict) -> dict:
    name = params.get("name")
    handler = _TOOL_HANDLERS.get(name)
    if handler is None:
        return {"content": [{"type": "text", "text": f"unknown tool: {name}"}], "isError": True}
    args = params.get("arguments") or {}
    if not isinstance(args, dict):
        return {"content": [{"type": "text", "text": "arguments 必须是对象"}], "isError": True}
    t0 = time.time()
    try:
        result = handler(args)
        log(f"tool={name} ok took={time.time() - t0:.2f}s")
        return {"content": [{"type": "text",
                             "text": json.dumps(result, ensure_ascii=False, indent=2)}],
                "isError": False}
    except Exception as e:  # noqa: BLE001  工具错误按规范进 result（isError），不打断进程
        log(f"tool={name} failed took={time.time() - t0:.2f}s: {type(e).__name__}")
        return {"content": [{"type": "text", "text": f"{type(e).__name__}: {e}"}],
                "isError": True}


def handle_message(msg) -> dict | None:
    """一条已解析消息 → 响应；通知（无 id）返回 None 不回包。纯函数（除 token 状态）。"""
    if not isinstance(msg, dict):
        return _error(None, -32600, "invalid request: not an object")
    if msg.get("jsonrpc") != "2.0":
        return _error(msg.get("id"), -32600, "jsonrpc must be \"2.0\"")
    method = msg.get("method")
    if not isinstance(method, str) or not method:
        return _error(msg.get("id"), -32600, "missing method")
    if "id" not in msg:
        return None   # notification：initialized/cancelled 等一律不回包
    mid = msg.get("id")
    params = msg.get("params") or {}
    if method == "initialize":
        # 版本协商：现只支持 2025-06-18；直接回报自己的版本，由客户端决定是否接受
        return _ok(mid, {"protocolVersion": PROTOCOL_VERSION,
                         "capabilities": {"tools": {"listChanged": False}},
                         "serverInfo": SERVER_INFO})
    if method == "ping":
        return _ok(mid, {})
    if method == "tools/list":
        return _ok(mid, {"tools": TOOLS})
    if method == "tools/call":
        if not isinstance(params, dict):
            return _error(mid, -32602, "params 必须是对象")
        return _ok(mid, _tools_call(params))
    return _error(mid, -32601, f"method not found: {method}")


def _emit(resp) -> None:
    sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def _force_utf8_stdio() -> bool:
    """协议帧必须是 UTF-8；Windows 管道默认 cp936、部分 Linux 是 ASCII locale——
    中文 tool 描述会写出非 UTF-8 字节，客户端直接 UnicodeDecodeError 断连
    （Windows 实测踩到）。

    规则：真 TextIOWrapper 必须重配成功（ValueError=环境无法保证 UTF-8，退出而非写坏帧）；
    测试注入的假 stdin/stdout 没有 reconfigure，跳过即可（它们不产生编码问题）。
    """
    ok = True
    for stream in (sys.stdin, sys.stdout, sys.stderr):
        reconf = getattr(stream, "reconfigure", None)
        if reconf is None:
            continue
        try:
            reconf(encoding="utf-8")
        except ValueError:
            print("[mcp] stdio 无法重配为 UTF-8，协议帧可能损坏", file=sys.stderr)
            ok = False
    return ok


def main() -> int:
    if not _force_utf8_stdio():
        return 2
    log(f"starting base={BASE} user={MCP_USER} tools={','.join(t['name'] for t in TOOLS)}")
    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError as e:
            _emit(_error(None, -32700, f"parse error: {e}"))
            continue
        if isinstance(msg, list):   # 旧版批处理形态：逐条处理，只回带 id 的
            responses = [r for r in (handle_message(m) for m in msg) if r is not None]
            if responses:
                _emit(responses if len(responses) > 1 else responses[0])
            continue
        resp = handle_message(msg)
        if resp is not None:
            _emit(resp)
    return 0


def reset_state() -> None:
    """测试用：清空 token 缓存。"""
    _state["token"] = None


if __name__ == "__main__":
    raise SystemExit(main())
