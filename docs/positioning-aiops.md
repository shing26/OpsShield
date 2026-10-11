# 定位自证：AIOps 谱系、与朴素 RAG 的差异、与 agent 的关系

> 本文回答三个"专业读者必然会问"的问题，每条带可核验指针。它从 README 迁出（2026-10-11，
> 渐进式披露：展厅讲价值，自证讲证据），内容口径不变。

## AIOps 谱系定位 · 是什么，以及明确不是什么

> 按 Gartner 谱系五域自证，状态三分类——**已覆盖 / 待还债（触发线在案）/ 永久非目标（产品承诺）**，每条带可核验指针。"知道自己不是什么"也是一等可观测面。

| AIOps 能力域 | 状态 | 事实与边界 | 证据指针 |
| --- | --- | --- | --- |
| ① 遥测采集（metrics/traces/topology） | **永久非目标** | 输入恒为一段文本 query（人贴堆栈或告警系统 POST），无指标流/事件总线/拓扑图；做成采集平台是另一个产品，不是本系统的缺口 | 架构图入口面（三入口皆文本协议）；`gateway/CopilotController`、`gateway/OpenAiController`（全部入参=文本+元数据） |
| ② 异常检测（统计/ML） | **永久非目标** | 全仓无检测算法路径——"何时算异常"的判定权恒归上游告警系统，本系统消费其结果 | `grep -riE "anomal|forecast" src/main` 为空；[CONTEXT.md](CONTEXT.md) 术语表无此词条（有词条必先入术语表，反向可验） |
| ③ 告警降噪 / 事件收敛 | **已覆盖（同指纹域）**，边界明示 | `来源×指纹` 滑窗计数 + 进程内 Single-Flight：500 并发同指纹 → LLM 仅 1 次。**跨指纹 incident 关联未覆盖**——但 2026-09-16 起**前置已满足**（自举告警源提供了真实告警流，[ADR-0011](adr/0011-self-bootstrapped-alert-source.md)），该边界已入债务账并带触发线（OPS §5），不再"未在账" | `storm/FingerprintService`、`storm/SingleFlightRegistry`；[ADR-0003](adr/0003-single-instance-inprocess-single-flight.md)；A2-5（`offline/acceptance_a2.py`）；台账 [qa/2026-09-16-self-alert-loop.md](qa/2026-09-16-self-alert-loop.md) §2.1（11 条同故障告警 → 指纹一致、LLM 增量 0） |
| ④ 知识化根因辅助 | **已覆盖（检索域）**，边界明示 | 双路召回 + RRF + 精排 over 68 篇复盘/Runbook + OpenAPI（**2026-10-06 起含 5 篇本机真实事故复盘**——「复盘→知识回灌」边界的第一次真实兑现）；置信度不足显式拒答而非硬编。**边界：知识库是只读的**——运行期没有任何「结论→语料」回灌路径，语料由人工撰写、重建靠 `POST /admin/reingest` 从既有 `chunks.jsonl` 重灌；"真实排障结论沉淀回知识库"这一环**未覆盖**，已按触发线登记（OPS §5.1） | `retrieval/HybridSearchService`；[ADR-0001](adr/0001-java-online-python-offline-split-at-jsonl.md)/[ADR-0002](adr/0002-dashscope-one-stop-1024-dim.md)；`offline/eval/reports/eval_report.md`（es_only 70.6%→hybrid 88.2%，现报告版）；`ingest/IngestionRunner`（只读 chunks → 写派生索引） |
| ⑤ 处置闭环（动作执行/自愈） | **待还债（触发线在案）** | 当前形态=输出可溯源排障步骤供**人**执行；对目标系统零写操作是产品承诺非缺陷。触发线（合取）：接入可审计执行通道（runbook 执行引擎 + 审批链/HITL 门）后立项。**告警接入侧已解除挂起**——自举告警源已上线（ADR-0011，换源而非 adapter）；面向外部监控系统的 adapter 仍按硬约束挂起 | OPS §5 债务闹钟表"处置闭环"行（含 2026-09-16 状态注）；`docs/ops/production-readiness-2026-09-12.md` M3 |

**一句话口径**：OpsPilot 做的是 AIOps 的 **③④ 两个子域的网关入口层**——"让告警风暴里的一条 query 得到可信、可溯源、越不了权的排障建议"。标题词 AIOps 指的是这个可验证子集；①②⑤ 上表三分类各归其位，欢迎按证据列逐行核验。

> **原版面板上的「能力边界」与「AIOps 谱系定位」两块静态声明已撤除**（2026-10-06，用户判断"没什么作用"）：一字不变地挂在监控屏上不产生任何信息，还抢注意力。内容即本文这张表与 OPS §5 债务闹钟表——**该自证的地方是文档，不是值班屏幕**。

## 与朴素 RAG 的五条差异

> 骨架诚实承认：检索+生成就是 RAG。差异在骨架外那一圈**决定运维工程师敢不敢信**的东西——每条 = 机制 + 代码 + 验收锁，不是形容词：

1. **不知道就说不该知道**：检索 Top-1 相关度低于阈值或零召回 → 显式拒答并**跳过 LLM 调用**（朴素 RAG 会把空上下文硬喂给模型赌它不编）；精确符号快路径天然高置信豁免门控；**问得不完整时也先要信息**——依赖上文的短句（如 `刚才那个怎么办`）不会拿去硬检索，而是直接说清「本系统单轮、请把关键信息并入本条」（`mode=clarify`，同样零 LLM 调用），把"自信地答非所问"挡在检索之前。→ `gateway/ChatOrchestrator` 门控分支、`gateway/ClarificationGate`、`config/OpsPilotProperties`（min-relevance）、QA 台账 P2-6（慢拒答归因）。
2. **权限是数据层不变量，不是提示词约定**：tenant/auth_level 以 term/range 注入 ES Query DSL 与 Qdrant Filter 双引擎，Prompt 越狱语料实测零泄漏——防线里没有任何"请不要回答越权内容"式的软承诺。→ `retrieval/EsSearchService`、`retrieval/QdrantSearchService`、[ADR-0008](adr/0008-permission-dimensions-on-every-shared-path.md)、A2-8/8b/8c 边界矩阵。
3. **"可读"不等于"可倒出"**：出口句级 LCS 硬护栏（连续重叠 >80 字整句替换占位，carry=160 堵"逐行不超阈、拼接超阈"的表格式漏检），且设卡一处即同时覆盖生成流、缓存回放、Single-Flight follower 与双协议面。→ `llm/VerbatimStreamFilter`、`llm/VerbatimGuard`、[ADR-0010](adr/0010-generation-layer-enforcement-split.md)、探针 V2/V3（`offline/qa_gen_quality_probes.py`）。
4. **溯源是合同不是装饰**：答案 refs 进 L1/L2 缓存 payload、随 Single-Flight 回放、落 SSE done 帧——引用标号在缓存命中路径与现网生成路径逐字节一致（第四轮 QA 曾把"L2 命中丢 refs"按缺陷修复并入回归锁）。→ `cache/L2SemanticCacheService`、A2-3/A2-9 引用断言。
5. **风暴与故障是设计输入，不是运行时异常**：同指纹 500 并发→1 次 LLM 穿透；过载降纯 ES、LLM 熔断直出预热静态 SOP、冷却到期半开自探（修复见 `e966feb`）——降级是状态机的一等公民，不是 catch 块。→ `storm/SingleFlightRegistry`、`resilience/DegradationStateMachine`、A2-5/A2-6、`DegradationRecoveryTest`。

> 五条合起来是一次**成功标准的换位**：朴素 RAG 的成败判据在检索指标；本系统的判据在"每个出口都可信"。这也解释了测试面为何比检索评测宽得多——229 单测（`@Test` 声明数）+ A2 十项 + 生成质量门禁 V1–V9（其中 live 六道 V2/V3/V4/V5/V7/V9 由 `offline/qa_gen_quality_probes.py` 承担，V1=Java 单测、V6=ZSET 混源用例、V8=文档核对；V9=答案接地一致性，复用 V2/V4 已产出答案故不占 chat 预算）+ 越狱/风暴/降级/留痕矩阵。

## 与 agent 的关系：它是 agent 的**可信底座**，不是第 4 个 agent

> 定位见 [ADR-0013](adr/0013-trust-base-for-agents-not-an-agent.md)。这一节和上面那张谱系表是同一个用意：**"知道自己不是什么"要写在别人读得到的地方**，而不是留在设计者的记忆里。

上层 agent 无论用哪家框架做多步排障，最终都要落到一个"这一步的结论有没有据、这一步的身份越没越权、上游挂了怎么办"的**底座**上。本系统就是那一层——权限在引擎层硬过滤（不是 Prompt 约束）、答案带溯源合同（refs 逐条落盘并随缓存回放）、过载时降级而非拒绝、不知道就显式拒答。

**它刻意不做的事**：不引入 LLM 决策循环，也不做 tool-calling。全仓唯一的 LLM 调用点是一次流式生成，请求体里没有 `tools` 字段——不是做不了，是这个任务形态不需要，而线性链路的**可预算**正是"可信"的前提。三处 agent 形状（自举告警闭环 / 降级状态机 / 置信度门控）的判据都是阈值与计数器，不是模型。

**给多步调用者的接口**：请求头 `X-Trace-Id`（8–64 位 `[A-Za-z0-9_-]`，非法即 400 并留痕）把 agent 的多次调用串成一次 incident。它只做**关联**——不改检索、不注入上下文、不存会话（本系统无状态单轮，多轮上下文由客户端负责，见 [ADR-0007](adr/0007-openai-compatible-surface-jwt-as-key.md) 修订注）。两个 id 分工明确：`request_id` 查单次请求（服务端权威），`trace_id` 查跨步链路。

**重开条件**：不新开触发线，直接指向 `OPS.md` §5 债务闹钟表的「处置闭环（对目标系统的自动执行/自愈）」行——能到达的循环必然带写动作，而只读式多步循环的上游（拓扑/指标类遥测）在谱系表里是**永久非目标**。
