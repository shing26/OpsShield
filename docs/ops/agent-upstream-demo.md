# 上游多步排障 agent demo（「agent 是调用方，网关是只读可信底座」的实证）

> 定位见 [ADR-0013](../adr/0013-trust-base-for-agents-not-an-agent.md) 与
> [positioning-aiops.md](../positioning-aiops.md) 的「与 agent 的关系」节。这份 demo 是**在网关
> 之外**写的一个调用方：它不引入任何 Java 改动，只把 `/v1` 面按多步编排调两次——证明「底座零
> 改动即可被 agent 编排」不是口号，而是可以对着 mock 后端跑出来的东西。
>
> 它**不是第 4 个 agent 项目**，也**不是给底座加循环**：网关侧仍然没有 LLM 决策循环、没有
> `tools` 字段、没有会话态；多步的"决策"全在调用方的确定性状态机里，至多两次调用，线性可预算。

## 这个 demo 是什么 / 不是什么

| 是 | 不是 |
|---|---|
| 一个 2–3 步的排障 agent **调用方**（收到告警 → 调网关取结论 → 按结论决定追问或汇报 → 输出带溯源的处置建议） | 网关内的新能力（`git diff` 里没有一行 Java） |
| 有界状态机：至多 2 次网关调用（初问 + 至多一次追问），超预算即升级给人 | 开放 LLM 循环（agent 侧也刻意不放大：没有"再多试一轮"的滑坡） |
| 无状态单轮语义的忠实客户端：上下文由 agent 并入每条 message | 靠服务端记忆的多轮会话（ADR-0007 明确不做） |
| 跨步关联只用一个调用方关联键 `X-Trace-Id` | 服务端会话态、注入上下文、改检索 |

## 调用契约逐条对照 ADR-0013

| 契约条目 | 底座侧的实现 | demo 侧怎么用 | 证据 |
|---|---|---|---|
| 定位＝agent 的可信底座，不并列成 "agent 项目" | 网关只提供编排面与护栏 | 整个 agent 活在 `offline/`，复用 `offline/localapi.py` | `offline/agent_upstream_demo.py`；本 PR 无 Java 改动 |
| 不引入 LLM 决策循环 / tool-calling | 请求体无 `tools` 字段，唯一 LLM 调用点是一次流式生成 | 步骤划分与追问方向由**确定性决策表**决定，无 LLM 参与编排 | `TriageAgent` 的决策表注释；`MAX_GATEWAY_CALLS = 2` |
| 无状态单轮（ADR-0007） | `/v1` 只取**最后一条 user 消息**，忽略 system/历史 | 追问时把原始告警 query + 补充标识符并入同一条 message | `build_query()` / `TriageAgent.run()` |
| 调用方关联键 `X-Trace-Id`（8–64 位 `[A-Za-z0-9_-]`，非法即 400 并留痕） | `RequestIdFilter` → MDC → `AuditService.writeEvent` | 一次 incident 只生成一个 `trace_id`，**每一步都带同一个** | `new_trace_id()`；测试断言 `fake.trace_ids == [report["trace_id"]] * 2` |
| `request_id`（服务端权威）与 `trace_id`（调用方提供）分工 | 两个 id 并存于同一审计行 | demo 只提供 `trace_id`，不伪造 `request_id` | 审计面按 `trace_id` 整段取出 |
| JWT 即 Key（ADR-0007 决策 2） | `/v1` 在 `JwtAuthFilter` 守卫内；`sub` 必须在库且 `tver` 匹配 | 走 `POST /api/v1/auth/login` 换 24h token（复用 `localapi.login`）；401 时重登一次 | `GatewayClient._ensure_token` / `chat()` |
| 只读行动契约（ADR-0017 / ADR-0028） | `OpenAiChatSink` 以 `## 只读行动契约` 注入正文尾部，命令逐字来自引用 chunk | 逐字打印，**不执行任何命令**（agent 不持有执行权限） | `parse_answer()` / `render()`；测试断言命令原文出现 |
| 溯源是合同不是装饰 | `## 参考来源` 段（breadcrumb + service） | 逐条打印，作为处置建议的引用依据 | `parse_answer()` 的 refs 解析 |
| 不知道就说不该知道 | 检索门控拒答，固定话术含 `无法可靠作答`，且**跳过 LLM 调用** | 识别拒答 → 用告警自身的错误码/服务名补上下文追问一次 | `REFUSAL_MARKER` / `alert_identifiers()` |
| 风暴与故障是设计输入 | 异常时 `OpenAiChatSink.deliverFailure` 以 `【系统提示】` 收尾，不丢连接；准入拒绝给 429 + `Retry-After` | 识别异常中断帧 → **立即升级给人**，不消耗第二次预算；HTTP 错误按码报出，不做重试风暴 | `FAILURE_MARKER` / `GatewayClient.chat()` 的错误话术 |
| `/v1` 仅支持 `stream=true` | 非流式请求 400 | 只发 `stream=true`，按 SSE 逐帧拼 delta | `_join_sse()` |

## 决策表（有界状态机）

| 步骤 1 的结果 | 步骤 2 | 终态 |
|---|---|---|
| 有结论且带行动契约 | 不调用 | 汇报（预算只花 1 次） |
| 有结论但无行动契约 | 追问"给出只读排查步骤" | 汇报 |
| 拒答（`无法可靠作答`） | 用告警里的错误码/服务名补上下文重问 | 汇报 |
| 拒答且告警中无标识符 | 不调用 | 升级给人 |
| 收到异常中断帧 | 不调用（该帧是系统提示收尾，不是结论） | 升级给人 |
| 步骤 2 仍拒答 | 不调用（绝不第三次） | 升级给人（带 `trace_id` 与已试过的问法） |

## 怎么跑（mock 后端即可，无需 DashScope Key）

~~~bash
# 1) 起网关：memory 存储 + mock LLM（不依赖中间件，不需要真实模型 Key）
STORAGE_MODE=memory DASHSCOPE_MODE=mock bash scripts/start_gateway.sh

# 2) 播种演示账号（幂等；sre-full = level3 + platform）
DEMO_PASSWORD=... bash scripts/seed_demo_users.sh

# 3) 跑 agent（口令只从环境变量读）
DEMO_PASSWORD=... offline/.venv/Scripts/python offline/agent_upstream_demo.py
~~~

Windows 下 venv 解释器路径为 `offline/.venv/Scripts/python.exe`；Linux/macOS 为
`offline/.venv/bin/python`。网关端口非 8081 时用 `OPSPILOT_BASE` 或 `--base` 覆盖。

## 环境变量与参数

| 变量 / 参数 | 默认 | 说明 |
|---|---|---|
| `DEMO_PASSWORD` | 无（缺失即退出 1） | 登录口令，**只从环境读**，不落日志、不进错误话术 |
| `OPSPILOT_BASE` / `--base` | `http://localhost:8081` | 网关地址；只允许 http + 环回（与 `localapi.assert_local` 同一条 SSRF 防线） |
| `--user` | `sre-full` | 登录用户名（演示全权账号） |
| `--alert-file` | 内置样例告警 | 告警 JSON 文件；字段取 `labels.service` / `labels.env` / `text` / `annotations.summary` |

## 预期输出

~~~text
== OpsShield 上游多步排障 agent demo ==
trace_id: agent-xxxxxxxxxxxx  （审计按此键可整段取出本次 incident 全部步骤）
网关调用次数: <1 或 2> / 预算 2

[步骤1 · triage · answered] 收到一条运维告警，请给出排障结论与只读排查步骤：

【处置建议 · 供人执行；agent 不执行任何命令（底座只读承诺，谱系⑤）】

结论：
…
只读行动契约（逐字来自网关，未经 agent 改写）：
  1. …
     `…`
溯源：
  - <breadcrumb>（<service>）
~~~

拒答/异常路径下输出以 `【升级给人】` 开头并附原因与 `trace_id`，**不输出处置建议**——
把"没拿到可用结论"渲染成建议是本 demo 最想避免的误导。

## 实测记录（2026-11-02，memory + mock，无 DashScope Key）

~~~text
== OpsShield 上游多步排障 agent demo ==
trace_id: agent-d79546923c0b
网关调用次数: 2 / 预算 2
[步骤1 · triage · no-actions]           # mock 正文没带行动契约 → 走"追问只读契约"分支
[步骤2 · followup-actions · answered]
【处置建议 · 供人执行；agent 不执行任何命令（底座只读承诺，谱系⑤）】
溯源: 3 条（order-service ×2 + user-service ×1）
[agent] 完成：2 次网关调用，耗时 1.0s
~~~

审计面按 `trace_id` 整段取出（`logs/audit.jsonl`，同一 incident 两行、两个服务端
`request_id`——正是 ADR-0007 修订注里两个 id 的分工）：

~~~text
{'ev': 'chat', 'trace_id': 'agent-d79546923c0b', 'request_id': '140e7793', 'sub': 'sre-full', 'source': 'manual', 'took_ms': 196, 'refused': False}
{'ev': 'chat', 'trace_id': 'agent-d79546923c0b', 'request_id': '04aee4c5', 'sub': 'sre-full', 'source': 'manual', 'took_ms': 95,  'refused': False}
~~~

第二条实测（`--alert-file` 造一条知识库覆盖不到的告警）：同样 2 次调用，gateway 换了一条
相近的延迟类手册作答，`trace_id=agent-3a9a7ae768f0`，审计两行同样按 `trace_id` 串起。
**未观测到**「拒答 → 补标识符追问」这一支——mock 模式下域外提问不一定被拒（见下「已知边界」），
该分支由单测钉住，不靠活体运气。

## 已知边界

- **身份路径与需求描述的一处偏差**：`scripts/gen_tokens.py` 是**红队畸形 token 生成器**
  （`sre_l0` / `sre_neg` / `tenant_none` / `tenant_blank` / `tenant_long`），它签的样本按设计
  必须被 `JwtAuthFilter` 401 拒绝；`JwtAuthFilter` 还要求 `sub` 在 H2 用户库且 `tver` 匹配，
  本地自签 token 无法通过。故合法 token 一律走 `POST /api/v1/auth/login`（复用
  `localapi.login`，与 `scripts/console_token.sh` 同一条正规路径）——这是仓库自 P2 起的纪律，
  demo 不为省事破坏它。
- **memory + mock 形态下不承诺检索质量**：向量腿是词法哈希，且 mock 模式下域外提问不一定被
  拒答（见 ADR-0022 / OPS §14）。故"拒答 → 补标识符追问"这一支在 mock 下可能走不到；要演它，
  用 `--alert-file` 造一条知识库覆盖不到的告警，或改用 live 形态。
- 账号库必须已播种（`scripts/seed_demo_users.sh`），否则登录即 401；demo 的 401 自愈只覆盖
  token 过期，不覆盖账号不存在。
- 429（准入拒绝）按 HTTP 错误报出并附带网关给的 `Retry-After`；demo 不做自动重试——重试风暴
  正是底座在防的东西。

## 与 MCP Server 的分工

| | MCP Server（`scripts/mcp_server.py`，ADR-0027） | 本 demo |
|---|---|---|
| 形态 | 给 IDE 类 agent 的**单发工具面**（检索 / 问答 / 状态，一次一调） | 同一编排面（`/v1`）被**多步调用**，用 `trace_id` 串成一次 incident |
| 协议 | MCP stdio | OpenAI 兼容 HTTP + SSE |
| 演示的是 | 底座能被工具化接入 | 底座能被**有界编排**，且自身零改动 |

## 测试

`offline/tests/test_agent_upstream_demo.py` 25 条：正文解析（成功序/异常序/标题重名）、SSE
拼接、告警→query/标识符、决策表每一条分支（含"绝不第三次调用"与"异常帧不重试"）、401 重登与
口令不泄漏、SSRF 拒绝、渲染逐字性与升级路径、CLI 缺口令即退出。HTTP 层全换脚本化替身，
**不触网**；只有 SSRF 那条用真的 `assert_local`——"底座只被环回调用"本身就是它要验的行为。
