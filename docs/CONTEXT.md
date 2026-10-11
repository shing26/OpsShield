# OpsPilot — AIOps 智能排障与契约检索网关

面向微服务与云原生环境的混合检索（Hybrid RAG）与智能故障自查系统：SRE 粘贴报错堆栈或口语化描述，系统经「指纹去重 → 双路召回 → RRF 融合 → 精排裁剪 → LLM 流式生成」输出可溯源的排障步骤。

## Language

### 检索域

**排障请求（Query）**:
一次用户/告警发起的排障输入，可以是口语化描述或错误堆栈。_Avoid_: 问题、提问

**语义块（Chunk）**:
切分产物的原子单位——一个 OpenAPI Endpoint 或一段标题感知的 Markdown 小节，自包含且携带元数据。_Avoid_: 片段、分块、文档

**双路召回（Hybrid Retrieval）**:
ES 倒排（精确符号）与 Qdrant 向量（语义泛化）并行检索后融合的模式。_Avoid_: 混合搜索

**RRF**:
Reciprocal Rank Fusion，倒数排名融合（k=60），将双路排名无量纲合并。_Avoid_: 加权融合

**精排（Rerank）**:
对 RRF Top-20 调用云端 Reranker 二次过滤，裁剪至 Top-3 上下文。_Avoid_: 重排序

**快路径（Fast Path）**:
查询含错误码且 ES keyword 精确命中 Top-1 时跳过 Rerank 的捷径。

**强标识符（Strong Identifier）**:
错误码或全限定名（FQCN）——形状判据非查表判据。词法单一事实源=EsSearchService 的 ERROR_CODE/FQCN 两个形状常量；但两个消费方的**组合判据不同**：快路径只认错误码（+keyword 精确命中 Top-1），FQCN 不参与其判定；防断言语态门认错误码∨FQCN（`hasStrongIdentifier`），query 含强标识符才允许结论式锚定具体事故，否则假设语态作答。_Avoid_: 关键词、特征码（词表会随语料漂移）

**回指澄清（Clarification）**:
识别“依赖上文”的提问（如 `刚才那个怎么办`）并在**检索前**要求补信息的行为——**不是拒答**：拒答是“这次问的东西知识库里没有”，澄清是“这次问的东西不完整”。触发是三重合取——短（≤8 字）∧ 以回指短语开头 ∧ 其后只剩提问尾巴（呢/吧/怎么办…）——另有独立前提：无强标识符（错误码 ∨ FQCN，复用既有单一事实源）；裸的“这/那/它”刻意不在词表内（太常见，误拦会堵住正常提问）。落审计 `mode="clarify"` + `refused=true`，零 LLM、无 refs、不落 `stage_ms`（检索根本没发生）。位置在**缓存之前**（判的是输入形态，与缓存状态无关），仅 L2 期让位给 SOP 直出。**存在的理由**：本系统单轮无会话，把回指句当独立 query 硬检索会命中无关内容并答得自信——对“可信”的伤害大于多问一句。_Avoid_: 追问、多轮（本系统不保留会话，见 ADR-0007/ADR-0013）

### 风暴防线域

**指纹（Fingerprint）**:
`SHA256(service + "|" + env + "|" + normalized_error_msg)` **取摘要前 128 位**（32 位十六进制）——归一化剥离时间戳/数字/ID 后的错误签名。分隔符与长度都写明，是因为指纹同时是 L1 缓存键的组分、SSE meta 的回传值、以及反馈端点归档"问题"的键：只写 `SHA256(...)` 会让人以为是 64 位（2026-09-27 实测对照时确实这样误解过一次）。截断到 128 位的碰撞界约 2^64，对告警去重足够。**注**：该截断在仓内**只有实现、没有决策记录**（无 ADR/注释，见台账 2026-09-27 节）。_Avoid_: 签名、哈希键

**滑动窗口（Sliding Window）**:
按 `来源×指纹` 维护的 Redis ZSET 聚合计数窗口（manual 30s / alert 60s），仅服务 dedup 计数与"风暴收敛"叙事；穿透闸门的唯一归属是单飞，窗口不做排除。_Avoid_: 防抖（口语可用，代码中统一 Sliding Window）、"窗口内仅首条穿透"（旧口径与实现不符，2026-09-13 grill 裁定废止）

**单飞（Single-Flight）**:
同指纹并发请求挂起等待首条结果并复用的进程内合并机制，key 掺 authLevel 防跨权限复用。_Avoid_: 请求合并

**来源（Source）**:
排障请求的发起方标记：`alert`（告警系统转发）或 `manual`（SRE 手动粘贴），影响去重窗口配置。

### 缓存域

**L1 精确缓存**:
`tenant + authLevel + SHA-256(normalize(query))` 为 Key 的 Redis 哈希缓存，TTL 2h。**已知边界（2026-10-03 登记，live QA F-4）**：门控拒答**也写 L1**（同 query 重发直接回放拒答，省一次检索）——代价是语料重灌补上新内容后，同 query 最长 2h 内仍回放旧拒答；运维 SOP：reingest 后用**改变措辞**的 query（不同指纹）验证新语料，别复用旧原句。触发线：若该 2h 窗口内发生"补了内容仍漏检"的漏检事故 → 缩短拒答类条目的 TTL。_Avoid_: 热缓存

**L2 语义缓存**:
Qdrant 专属 Collection，余弦相似度 > 0.95 判定命中，跳过检索与 LLM 直接回放答案；payload 携带 `max_auth_level` 防权限泄漏。_Avoid_: 向量缓存

**回放（Replay）**:
等待者/缓存命中者收到完整答案一次性写入自身 SSE 流的行为，区别于实时流式。

### 降级域

**Level 0（正常）**: ES+Qdrant 双路 + Rerank + LLM 全链路；相关性门控生效。
**Level 1（高负载）**: 摘除向量路与 Rerank，纯 ES 关键词召回（检索侧等同 `es_only` 模式）。**代价有两项**：检索质量下降（语义 Hit@1 88.2%→70.6%），且**门控降级**——摘除 Rerank 后无相关性分数可依，门控从「相关性级」退为「零召回级」，即"不知道就不答"在降级期被弱化。取舍与重开触发线见 [ADR-0012](adr/0012-degradation-cost-semantics.md)。
**Level 2（熔断）**: 切断 LLM，直出 Redis 预热的静态 SOP 止损清单。
_Avoid_: 熔断级别、降级档位（统一 Level 0/1/2）

**静态 SOP**:
入库时按 `error_code/service` 预热进 Redis 的止损步骤清单，Level 2 兜底数据源。

**档位转移（Degrade Transition）**:
档位发生变化这一**事件**本身，落审计 `ev="degrade_transition"`，带 `from`/`to`/`cause`。为什么不能只靠审计行的 `degrade_level` 字段反推：字段答的是"**这次请求时**是几档"，切换时刻要靠请求密度间接推断，无请求的区间里切换会被整段漏掉。`cause` 是**有限词表**：`inflight`（在途超阈→L1）/ `llm_failure`（连续失败达阈→L2）/ `load_subsided`（负载回落→L0）/ `cooldown_expired`（冷却到期半开→L0）/ `manual` / `manual_clear`。**量具边界**：状态机是**拉模型**（无独立定时器），转移在计数器变化处（enter/exit/llmFailure/llmSuccess/manual*）与 `current()` 被调用时被观察到——负载回落因此不依赖后续请求即可落痕，但"既无请求又无人看 `/state`"的静默期内的切换会与下一次观察合并。_Avoid_: 降级日志（日志是过程，这是可复核的事件序列）

### 权限域

**auth_level**:
数据密级与用户凭证等级，整数 1/2/3（1=公开 SOP，2=内部复盘，3=含生产配置）。硬过滤规则：`chunk.auth_level <= user.auth_level`，在 ES 与 Qdrant 引擎层强制注入。_Avoid_: 角色（role 是另一维度，见下）

**role**:
用户职能（sre/dev/manager），仅用于审计与 Prompt 个性化，不参与数据过滤。

**租户（Tenant）**:
数据归属与隔离的第一维度，与 auth_level 同级在 ES/Qdrant/L2/SOP 四面引擎硬过滤（`metadata.tenant` term 等值 + range lte 双 must）。内部工具形态恒 `tenant-internal`；tenant claim 缺失/空白/超 64 在入口 401。_Avoid_: 工作区、命名空间（缓存前缀是另一回事）

**账号（Account）**:
H2 主库中的一行用户记录（sub + bcrypt 口令 + tenant + auth_level + disabled + token_ver），权限维度的唯一真相源；JWT 只是账号身份的短时载体（24h）。_Avoid_: 用户（user 指运行时 UserContext）

**吊销版本（token_ver / tver）**:
账号行上的整数游标，签进 JWT 的 `tver` claim；`JwtAuthFilter` 每请求比对 DB 值，不等即 401。disable/passwd/rotate 均 bump——改一行即全局失效该用户所有存量 token，无需黑名单。_Avoid_: JWT 黑名单（明确否决的方案）

**审计事件（Audit Event）**:
每 chat/search 请求落一行 JSON 到 `logs/audit.jsonl`（sub/tenant/level/query 截断/fp/cache_hit/mode/**degrade_level**/refused/max_level/took_ms + 请求关联键），回答"谁查过什么、答案触到哪个密级、**这次请求处在哪一档降级**"，是权限引擎层的可核查闭环。**`degrade_level` 是必需列而非冗余**：`mode` 有两个来源（L1 降级按档位传 `es_only` / 检索腿超时后 `effectiveMode` 也是 `es_only`），单看 `mode` 无法判定"这次是不是负载触发的降级"。_Avoid_: 应用日志（业务日志非合规留痕）

**请求关联键（request_id / trace_id）**:
审计行上的两个身份键，**并存且互不覆盖**。`request_id` = 服务端为每次 HTTP 请求自生的 8 位短 id（`RequestIdFilter` 注入 MDC，同时进 app.log 与审计行），语义是"这一行是哪个请求产生的"，**服务端权威**、调用方输入顶不掉。`trace_id` = **调用方提供**的调用链 id（请求头 `X-Trace-Id`，8–64 位 `[A-Za-z0-9_-]`，空白按未提供，非法即 400 + 留痕），语义是"这次 incident 分几步问过、都问了什么"——凑不上它时，被测 agent 的多步调用在审计里串不成一次 incident（既有 `fingerprint` 是**内容派生**的"问题身份"，三步问不同错误码即散成三个，担不起调用链身份）。排障时按问题选：查单次请求用 `request_id`，查跨步链路用 `trace_id`。_Avoid_: 会话 id（无状态单轮，见 ADR-0007/ADR-0013）、traceId 大小写混写（落盘统一 `trace_id`）

### 重建域

**blue/green 重建（Staging Reingest）**:
知识库更新的安全流程：写时间戳物理库 `<base>-<ts>` → 计数硬验收 → 单请求原子换 ES/Qdrant 别名 → 删旧库；任何失败保留旧别名指向。查询侧永远只认别名（`opspilot-chunks-read` / `opspilot-vectors-live`），零空窗。_Avoid_: 热更新、滚动重建（旧"先删后建"是被否决的反模式）

**回滚（Alias Rollback）**:
改别名指回上一版物理库（若未清理）即秒级回退，重建失败无需恢复数据。

### 评测域

**Golden Dataset**:
68 组标注样本（34 精确错误码 + 34 口语化语义），与合成语料同源，ground truth 为期望 chunk_id 集合。2026-10-03 扩样：纳入两条被置信度门控误拒的口语极值（见 live QA F-3），盯住“能答的别拒”。2026-10-07 扩样：本机辖区真实事故系列 rb-101~105 的口语化覆盖 + 旧系列（重编号 rb-111~115）两篇。

**越狱用例（Jailbreak Case）**:
诱导模型输出越权内容的对抗 Prompt，验收标准是引擎层过滤使其召回为空，而非模型自觉。

**TTFT**:
双口径——`connect`（SSE 连接建立）与 `first_token`（首个内容 token），报告分别记录。

**答案接地（Grounding）**:
"答案里出现的东西是否都有据"的判据——补防幻觉链路的**事后**环。在线既有闸门全在事前（置信度门控拒答）或通用文本层（逐字导出护栏），故系统能保证"没证据就不答"，却不能保证"答了的内容都有证据"。当前判据只覆盖**错误码**这一种精确符号：答案中出现的错误码必须落在本轮 refs 的 `metadata.error_codes` 并集内，**幻觉率 = 未命中比例**（实现 `offline/grounding.py`，live 断言 V9）。已知边界：runbook 步骤编号（pm-*/rb-*）不在管辖内（那类归 V4 语态锁与人工评审）；query 自身含语料外错误码时，模型复述会被计为无据（方向保守）。_Avoid_: 引用正确率（那是"标号是否支撑该句"，需 LLM-as-Judge，本判据不做）、幻觉率（不加限定语时易被误读为覆盖全部幻觉形态）

**同代门闩（Same-Generation Gate）**:
"文档里的数字必须等于随附产物算出来的数字"这一约束的机器判据，分两个面：**面一**「报告↔语料」——对语料字节集、切分产物、评测集取内容摘要，与登记比对（判据是**内容**不是 mtime：CI 的 `git checkout` 会把所有 mtime 刷成检出时刻，时间戳判据在那里恒真、等于空转）；**面二**「文档数字↔产物」——按登记表**每次现算**产物真值，与文档里的字面量比对，不存数值快照。面一同代**不等于**面二同代：报告是对的，人把它抄进 README 时抄错或改了产物忘了改文档，面一完全看不见。_Avoid_: 数字校验、metrics 校验（门闩只对登记过的数字负责，不做全文扫描）

**触发线（Trigger Line）**:
债务的到期日——一个**可判读**的条件句（到点必须做，未触发不做）。不可判读的触发线等于没有触发线，因为没有任何时刻会让它到期。_Avoid_: TODO、待优化、以后再说

**误拒 / 漏拒（False Refusal / Missed Refusal）**:
门控的两类错误，方向相反且都伤系统：**误拒** = 本可作答却拒答（伤可用性——用户问了知识库里有的东西，却被告知"无相关参考"）；**漏拒** = 本无据却作答（伤可信度——正是"不知道就不答"要挡的那类）。两者必须**一起看**：单看任一个都能被一个极端阈值刷到很好看（全拒 → 漏拒 0；全答 → 误拒 0）。度量产物 `offline/eval/reports/gate_matrix.md`。_Avoid_: 拒答率（那只是次数，不含正确性）

**分段耗时（Stage Timings）**:
一条请求的时间去哪儿了——检索分腿（`es`/`vector`/`rrf`/`rerank`）+ `retrieval` + `llm_ttft` + `llm` + `l2_store`，随 chat 审计行落 `stage_ms` 嵌套对象（**不落 SSE 帧**：那是对外协议面，内部耗时不该泄到那里）。口径两条易误读：`vector` 段**含该腿内的 embedding 调用**；`rerank = 0` 表示**未调用**（快路径/es_only/降级）而非"很快"——**但 sub-ms 段例外**：`rrf` 是纯计算（恒 <1ms），mock 后端下的 `rerank` 亦如此（实测：`/search` 返回非零 `rerank_score` 证明已应用，审计行却是 0），故"是否调用"请以 `mode`/`fast_path` 为准，`0` 只是"没测出耗时"。非 chat 路径传 `null` → 不落字段（"没测"），与"测得为 0"是两回事。_Avoid_: 耗时（不指明分段时无法回答"时间花在哪"）

**答案反馈（Feedback）**:
人把"这个答案不对"告诉系统的入口（`POST /api/v1/copilot/feedback`），落审计 `ev="feedback"`。**按 fingerprint 归档**而非 request_id——fingerprint 标识的是**问题**不是某次生成，而"复盘→知识回灌"要沉淀的正是知识（问题）。不占配额：反馈是治理信号不是成本，若占配额，用户会在配额耗尽时放弃上报坏答案。它是"复盘→知识回灌"（OPS §5.1 在案债务）的前置入口。_Avoid_: 点赞、评分（都易被读成产品功能，而它是治理信号）
