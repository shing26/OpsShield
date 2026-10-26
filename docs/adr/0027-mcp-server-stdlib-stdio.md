# ADR-0027：MCP Server 作为生态入口——stdio、stdlib 自实现、工具面最小化

日期：2026-10-24 ｜ 状态：已接受 ｜ 关联：[roadmap.md](../ops/roadmap.md)、[mcp-server.md](../ops/mcp-server.md)

## 背景

路线图把「MCP Server」列为近期最高优先级：Claude Code / Cursor / Cline 等工具都说 MCP，
把 OpsShield 的检索与可信问答暴露成 MCP 工具，Agent 生态就能零改造接入；
而 OpsShield 的差异化（逐字抽取、只读契约、租户隔离、削峰降级）正好是这类工具最缺的「敢托付」部分。

## 决策

1. **MCP Server 是前置适配层，不是新信任边界**：以一个配置用户登录网关，
   权限恰好等于该用户；租户隔离/密级过滤/配额/准入/审计全部复用网关现有机制，一行不改。
2. **传输只做 stdio**：本地客户端（Claude Code/Cursor）的形态。把 MCP 直接暴露到网络上
   等于把鉴权面从「本机进程」放大到「网络」，需要独立的评审与加固，v1 明确不做。
3. **stdlib 自实现协议面**（MCP 2025-06-18：stdio = 按行分隔 JSON-RPC 2.0），
   只实现 initialize / tools/list / tools/call / ping。不引入官方 mcp SDK
   （会拉进 anyio/httpx/pydantic 依赖链），守住「运行时零第三方依赖」纪律。
4. **工具面 v1 = 3 个只读工具**：search_ops_docs（纯检索，无 LLM 成本）、
   ask_ops（可信问答，带只读命令契约与降级档位）、get_gateway_state（降级时引用需人工复核）。
   不提供任何写/执行工具；get_readonly_commands(doc_id) 等网关补 chunk-action 端点后再加。
5. **失败语义**：工具错误按 MCP 规范进 result（isError=true），不用 JSON-RPC error——
   429 配额/准入是护栏在工作，Agent 应读到结构化失败而非协议异常。

## 备选与取舍

| 备选 | 否决原因 |
|---|---|
| 官方 Python mcp SDK | 引入 anyio/httpx/pydantic 依赖链；CI 面变大；规范演进反而被 SDK 版本绑架 |
| Java Spring-AI MCP（并入网关） | 把生态适配面塞进信任边界本体，网关攻击面变大；且绑定 Spring AI 版本节奏 |
| 自定义 HTTP API + 各家 SDK | 每个客户端一套集成，生态成本最高——正是路线图要避免的 |

## 后果

- 正面：CI/部署零新增依赖；协议面 ~200 行可审计；Agent 工具天然继承全部安全保证。
- 代价：协议演进（新方法/能力协商变化）需手动对齐规范；tools/list 的 inputSchema 是手写的，
  与 handler 的参数校验可能漂移——由 `offline/tests/test_mcp_server.py` 16 条测试钉住。
- stdio 强制 UTF-8（`_force_utf8_stdio`）：Windows 管道默认 cp936，中文工具描述会把协议帧
  写成非 UTF-8 字节导致客户端断连——已实测踩到并修复；这是自实现路线必须自己扛的坑。
- 401 自愈：token 24h 过期，常驻进程在首个 401 时重登一次再重试（`_with_auth`）。

## 验证

- 单测：`offline/tests/test_mcp_server.py` 16 条（协议协商/通知/坏帧存活/工具映射/
  401 重登恰一次/凭据不进错误信息/SSRF 拒绝非环回）；
- 真进程冒烟：`scripts/mcp_smoke.py` 对 memory/mock 网关全链路 PASS（记录见 mcp-server.md 实测段）。