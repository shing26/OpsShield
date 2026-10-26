#!/usr/bin/env python3
"""MCP Server 冒烟客户端：对真网关走一遍 initialize → tools/list → tools/call。

用途：门禁/验收时证明「MCP 协议面 + 认证 + 检索/状态工具」在真进程上全通；
不是单元测试（那是 offline/tests/test_mcp_server.py 的事），也不默认打 LLM——
--ask 才会调 ask_ops（消耗配额）。

用法：
  OPSPILOT_BASE=http://127.0.0.1:8081 DEMO_PASSWORD=... python scripts/mcp_smoke.py
  python scripts/mcp_smoke.py --ask --query "50012_DB_TIMEOUT 怎么排查"

输出：每步一行摘要（stdout）；任何一步失败退出码非 0。token/密码不打印。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent


def main() -> int:
    ap = argparse.ArgumentParser(description="OpsShield MCP 冒烟客户端")
    ap.add_argument("--query", default="50012_DB_TIMEOUT 怎么排查")
    ap.add_argument("--ask", action="store_true", help="额外调用 ask_ops（消耗网关配额）")
    a = ap.parse_args()

    env = os.environ.copy()
    base = env.get("OPSPILOT_BASE", "http://localhost:8081")
    proc = subprocess.Popen(
        [sys.executable, str(REPO / "scripts" / "mcp_server.py")],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", env=env)

    def send(msg: dict) -> None:
        assert proc.stdin is not None
        proc.stdin.write(json.dumps(msg, ensure_ascii=False) + "\n")
        proc.stdin.flush()

    def recv() -> dict:
        assert proc.stdout is not None
        line = proc.stdout.readline()
        if not line:
            err = proc.stderr.read() if proc.stderr else ""
            raise RuntimeError(f"MCP server 无响应；stderr: {err[-400:]}")
        return json.loads(line)

    failures: list[str] = []

    def _tool_payload(resp: dict, name: str, failures: list[str]) -> dict:
        """工具结果统一出口：isError 时把原文记进失败清单并返回空壳，绝不炸栈。"""
        result = resp.get("result") or {}
        text = (result.get("content") or [{}])[0].get("text", "")
        if result.get("isError"):
            failures.append(name)
            print(f"   !! {name} isError: {text[:200]}")
            return {}
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            failures.append(name)
            print(f"   !! {name} 非 JSON 结果: {text[:200]}")
            return {}

    # 1) initialize：协议协商
    send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": "2025-06-18", "capabilities": {},
        "clientInfo": {"name": "opsshield-mcp-smoke", "version": "0.1.0"}}})
    init = recv()
    info = init.get("result", {}).get("serverInfo", {})
    print(f"1) initialize   server={info.get('name')}@{info.get('version')} "
          f"protocol={init.get('result', {}).get('protocolVersion')}")
    if init.get("error") or info.get("name") != "opsshield-mcp":
        failures.append("initialize")
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})

    # 2) tools/list：工具面完整
    send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
    listing = recv()
    tools = [t["name"] for t in listing.get("result", {}).get("tools", [])]
    print(f"2) tools/list   {','.join(tools)}")
    if set(tools) != {"search_ops_docs", "ask_ops", "get_gateway_state"}:
        failures.append("tools/list")

    mid = 3
    # 3) get_gateway_state：登录 + 读状态（降级档位决定可信度）
    send({"jsonrpc": "2.0", "id": mid, "method": "tools/call", "params": {
        "name": "get_gateway_state", "arguments": {}}})
    state_resp = recv()
    state = _tool_payload(state_resp, "get_gateway_state", failures)
    degr = (state.get("degradation") or {}).get("level")
    commit = (state.get("build") or {}).get("commit")
    print(f"3) state        commit={commit} degradation={degr} "
          f"quota_used={(state.get('quota') or {}).get('used')}")
    if state_resp["result"].get("isError"):
        failures.append("get_gateway_state")
    mid += 1

    # 4) search：纯检索，无 LLM 成本
    send({"jsonrpc": "2.0", "id": mid, "method": "tools/call", "params": {
        "name": "search_ops_docs", "arguments": {"query": a.query}}})
    search_resp = recv()
    search = _tool_payload(search_resp, "search_ops_docs", failures)
    top = (search.get("results") or [{}])[0]
    print(f"4) search       mode={search.get('mode')} took={search.get('took_ms')}ms "
          f"hits={search.get('result_count')} top={top.get('chunk_id')}")
    if search_resp["result"].get("isError") or not search.get("results"):
        failures.append("search_ops_docs")
    mid += 1

    # 5) ask（可选）：真 LLM 路径，带只读命令契约
    if a.ask:
        send({"jsonrpc": "2.0", "id": mid, "method": "tools/call", "params": {
            "name": "ask_ops", "arguments": {"question": a.query}}})
        ask_resp = recv()
        ask = _tool_payload(ask_resp, "ask_ops", failures)
        print(f"5) ask          gate={ask.get('gate')} degrade={ask.get('degradation_level')} "
              f"refs={len(ask.get('refs') or [])} actions={len(ask.get('actions') or [])} "
              f"answer_chars={len(ask.get('answer') or '')} ttft={ask.get('ttft_s')}s")
        if ask_resp["result"].get("isError") or ask.get("error"):
            failures.append("ask_ops")

    proc.stdin.close()
    proc.wait(timeout=10)
    if failures:
        print(f"FAIL smoke: {','.join(failures)}", file=sys.stderr)
        return 1
    print("PASS mcp smoke")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
