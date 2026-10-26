# MCP Server 使用指南（Agent 生态入口）

> OpsShield 的 MCP Server 把「可信运维上下文」暴露给 Claude Code / Cursor / Cline 等
> Agent 工具。**它只是协议适配层**：以一个配置用户登录网关，检索、削峰、降级、租户隔离、
> 配额、审计全部发生在网关侧——MCP 不是绕过护栏的旁路。
> 设计决策见 [ADR-0027](../adr/0027-mcp-server-stdlib-stdio.md)；方向背景见 [roadmap.md](roadmap.md)。

## 工具面（v1，全部只读）

| 工具 | 作用 | 网关成本 |
|---|---|---|
| `search_ops_docs(query, mode?)` | 检索文档/Runbook，返回 chunk_id/doc_id/breadcrumb/相关度分；结果已按登录用户租户与密级过滤 | embedding+rerank，**无 LLM 成本** |
| `ask_ops(question, source?)` | 可信问答：答案（逐字）+ 溯源引用 + 只读命令契约（actions）+ 降级档位；可能被准入 429 | 全链路，消耗配额 |
| `get_gateway_state()` | 版本、降级档位、配额、在途、健康摘要；采信 ask_ops 结果前先看降级档位。**需 platform 身份**（读 `/admin/state`，非 platform 返回 403） | 无 |

`get_readonly_commands(doc_id)` 需要网关提供 chunk-action 查询端点后再加；
当前 actions 经 `ask_ops` 返回。刻意不提供任何写/执行工具（roadmap「明确不做」）。

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `OPSPILOT_BASE` | `http://localhost:8081` | 网关地址；只允许 http+环回（SSRF 防线与 bridge 同源） |
| `OPSPILOT_MCP_USER` | `sre-full` | MCP 专用服务账号。**若要用 `get_gateway_state`，必须是 platform 身份**；只做检索/问答时可用最小密级账号（该工具会返回明确的 403 而不是伪装成网关故障） |
| `OPSPILOT_MCP_PASSWORD` | 回退 `DEMO_PASSWORD` | 该账号口令；只从环境读，不落日志 |

## 接入 Claude Code

项目根 `.mcp.json`：

~~~json
{
  "mcpServers": {
    "opsshield": {
      "command": "python",
      "args": ["scripts/mcp_server.py"],
      "env": {
        "OPSPILOT_BASE": "http://127.0.0.1:8081",
        "OPSPILOT_MCP_USER": "sre-full",
        "OPSPILOT_MCP_PASSWORD": "<口令，不要提交>"
      }
    }
  }
}
~~~

或 CLI：`claude mcp add opsshield -- python scripts/mcp_server.py`（凭据走环境变量注入）。

## 接入 Cursor

`~/.cursor/mcp.json` 或项目 `.cursor/mcp.json`，`mcpServers` 结构同上。

## 自检（验收/排障第一入口）

~~~bash
# 网关起着的情况下：
OPSPILOT_BASE=http://127.0.0.1:8081 \
OPSPILOT_MCP_USER=sre-full DEMO_PASSWORD=... \
python scripts/mcp_smoke.py            # 检索+状态，不打 LLM
python scripts/mcp_smoke.py --ask      # 额外验证 ask_ops 全链路（消耗配额）
~~~

预期输出以 `PASS mcp smoke` 结尾；任何一步 `isError` 都会点名列出。

## 已知边界（v1）

- 传输仅 stdio（本地客户端形态）；远程 HTTP/SSE 形态需要网关侧评审后再加——
  把 MCP 直接暴露到网络上等于把鉴权面放大，暂不做；
- 协议只实现 initialize / tools/list / tools/call / ping；
  resources/prompts/sampling 不做（工具面最小化，攻击面才小）；
- 协议版本 2025-06-18；stdlib 自实现，上游规范演进需手动对齐（ADR-0027 后果段）；
- Windows 管道编码坑已修（stdio 强制 UTF-8，见 `_force_utf8_stdio`）。

## 实测记录（2026-10-24）

~~~text
1) initialize   server=opsshield-mcp@0.1.0 protocol=2025-06-18
2) tools/list   search_ops_docs,ask_ops,get_gateway_state
3) state        degradation=L0
4) search       mode=hybrid took=3ms hits=3 top=pm-001::…50012_DB_TIMEOUT…
5) ask          gate=none degrade=L0 refs=3 answer_chars=198 ttft=0.021s   (mock LLM)
PASS mcp smoke
~~~

pytest：`offline/tests/test_mcp_server.py` 16 条（协议/工具/401 自愈/SSRF/凭据不泄漏）。