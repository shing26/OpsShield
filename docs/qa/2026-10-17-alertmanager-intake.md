# Alertmanager 接入验收台账（2026-10-17）

> **这次验的是什么**：外部监控 adapter 的第一个真实输入端——Prometheus 规则 → Alertmanager →
> 宿主 bridge（`scripts/alert_bridge.py`）→ 现有编排链路。此前所有"真实告警"的**来源**都是
> 本机自己的运行态（ADR-0011 自举生产者、`watchdog_alerts.py` 的 Docker 状态翻转）；
> 这一段补的是"由本系统管不着的组件产生、经标准协议投递进来"的告警。
>
> **一条重要口径**：桥是**探测性研究产物**，本轮的首要产出不是功能，而是**指纹在真实告警标签下
> 的真实行为**。下面第 3 节就是那份产出。

## 1. 环境与前置

| 项 | 值 |
| --- | --- |
| 网关形态 | 宿主裸进程，`-jar target/opspilot-gateway-1.0.0.jar`，8081 |
| bridge 形态 | 宿主常驻，127.0.0.1:8089，主体 `sre-watcher` |
| 中间件 | docker compose：redis / qdrant / elasticsearch |
| 监控侧 | `docker compose --profile alerting up -d`：prometheus 9090 / alertmanager 9093 |
| 上游 | 真实 DashScope（embedding / rerank 真跑）；LLM 腿按需指向本地 429 注入 |
| 语料 | 448 chunks，ES 别名 `opspilot-chunks-read` 计数一致 |

配置三项语法验证（本机实测）：

```text
promtool check rules   ops/alerting/rules/opspilot.yml   → SUCCESS: 3 rules found
promtool check config  ops/alerting/prometheus.yml       → SUCCESS
amtool  check-config   ops/alerting/alertmanager.yml     → SUCCESS（1 inhibit rule / 1 receiver）
```

## 2. 三条规则各验一次（触发手段全走真实故障）

| 规则 | 触发手段 | 计数器 | 规则态 | 到达 Alertmanager | 到达 bridge |
| --- | --- | --- | --- | --- | --- |
| `OpsPilotUpstreamRateLimited` | 假上游对 `/chat/completions` 恒回 **429**（embedding 腿仍返回合法向量） | `aiops_llm_rate_limited_total` 0→1 | **firing** | active | ✅ `sent_ok=true` |
| `OpsPilotRetrievalTimeouts` | `docker compose stop qdrant`（真实依赖不可用） | `aiops_retrieval_timeouts_total` 0→1 | **firing** | active | ✅ `sent_ok=true` |
| `OpsPilotSopFallbacks` | 手动打 L2 + 触发一次请求 | `aiops_sop_fallbacks_total` 0→2 | **firing** | active | ✅ `sent_ok=true` |

审计面佐证（`logs/audit.jsonl`）：三次均出现 `source=alert` + `sub=sre-watcher` 的行，
其中两条 `mode=sop_fallback`（第三条真实走了 SOP 直出路径）。

**第三条的注入方式（如实登记）**：`aiops_sop_fallbacks_total` 只在**真的走到静态 SOP 直出**时计数，
而直出要求 Redis 里预热过 `sop:<tenant>:*`。本机实测该前缀为空（预热只发生在 ingest 内），
故先跑了一次 `POST /api/v1/admin/reingest` 把真实语料里的「止损操作」段灌进去
（实测 448 chunks、别名原子切换、耗时 3.55s），再打 L2。**没有伪造 SOP 内容**——
键的形状、文本、错误码索引与 `IngestionRunner.warmSop` 逐条一致。

## 3. 研究产出：指纹在真实告警标签下碎在哪

### 3.1 实证现象

同一条 `OpsPilotUpstreamRateLimited` 的两次投递，桥台账里是这样的：

```text
query = "… 近 5 分钟 0.020 次/秒 … 51001_MODEL_ARREARAGE"   fp=c817f438192dbb63f17859b36e149f81
query = "… 近 5 分钟 0.003 次/秒 … 51001_MODEL_ARREARAGE"   fp=1f28342938dc58f66b9fa8743482b126
```

两行**只差模板渲染出来的那个速率数字**，其余逐字相同。三条规则各出现 2–3 个不同 `fp`，
全部 `cache_hit=none`、`deduplicated=false`、`mode` 走的是完整链路（不是回放）。

### 3.2 根因（读代码可判定，不是猜测）

`FingerprintService.fingerprint(service, env, errorMsg)` 把 `errorMsg` 交给 `normalizeError()`
做 `<TS>/<UUID>/<HEX>/<N>` 掩码——**但 `ChatOrchestrator` 传进去的是 query 原文，而那条
归一化只对"原始错误消息"设计**。Alertmanager 投进来的 query 是**人类可读的告警文案**，
模板（`{{ $value | printf "%.3f" }}`）在每次评估时把当前速率渲染进去，于是：

> **同一个故障、同一条规则、同一份文案，每次重发都是一个新指纹。**

后果是链式的，且都落在这套系统的卖点上：

1. **L1 精确缓存永远 miss**（缓存键含指纹）——同一条告警反复重发，每次都要重跑检索与生成；
2. **Single-Flight 永不合并**（组键含指纹）——Alertmanager 的重发不是风暴，但**真风暴**里
   "同一根因的多条告警"会因数值抖动而各自穿透，收敛率被系统性高估；
3. **`<N>` 归一化看着像防弹衣，实际没穿在这条路径上**——它保护的是自产告警（文案是脚本拼的、
   数值形状可控），一旦输入换成外部模板渲染的文案就失效。

**这条与"租户/密级过滤"无关，也与桥的映射口径无关**（桥只拼接 `summary` + `error_code`，
数值来自规则自己的模板），所以它不是桥的实现缺陷，而是**指纹契约与真实告警文案之间的错配**。

### 3.3 候选修法（未实施，本轮只到"看得见"）

| 方案 | 做法 | 代价 |
| --- | --- | --- |
| A. 规则侧去抖 | 注解里不渲染 `$value`，改写成"近 5 分钟速率高于阈值" | 失去人读时的量化信息；且只治我们写得出的规则，治不了别人的 |
| B. 桥侧归一化 | query 合成后再跑一遍与 `FingerprintService` 同源的归一化 | **等于让桥替网关决定指纹身份**，与服务端单一事实源冲突；且会连带掩掉错误码之外的语义差异 |
| C. 服务端拆身份 | 指纹不该吃"人类可读文案"，而应吃规则身份（`alertname` + 关键标签） | 要新增"告警指纹 ≠ 查询指纹"的概念与字段，属产品面变更 |

三条都有真实代价，且方向不同（规则纪律 / 桥越权 / 服务端新概念）。**本轮不动**，
留给下一步：先把三条候选按"改动面 × 治不治得了外部规则"摆开再定，触发线见 OPS §5.1 新增行。
**【2026-10-17 当日更新】已定案为 C（服务端拆身份）并实施，验收见 §6；本节保留当时的候选代价对照。**

### 3.4 顺带观测到的一处规则侧行为：进程内计数器复位会被 `rate()` 读成"上升"

收尾时把网关重启回真实上游，`aiops_sop_fallbacks_total` 从 `4` 归零（进程内 AtomicLong）。
Prometheus 的 `rate()` 对**计数器复位**的处理是"把复位前的峰值补回来"，于是
`sum(rate(...[5m])) > 0` 在复位后**继续成立了约 10 分钟**，直到复位前的样本滑出 5 分钟窗口
才回到 `0`、告警才转 inactive。

这不是本次接线引入的缺陷，而是**规则设计与"计数器会在重启时归零"这一事实的错配**：
本系统的所有 `aiops_*` 都是进程内计数器，重启即归零。当下**如实登记、不修**——
它不产生错误的排障结论（只是让"上游刚抖动过"这件事在恢复后多挂一会儿），
而修法（改用 `increase` 配合重启检测、或把"重启"本身建模成事件）属规则侧设计，
应与 §3.3 的三个候选一并决定。**观测记录**：04:32:20 计数器归零 → 04:45 前后 `rate` 回 0、
三条规则全部 inactive。

### 3.5 比指纹抖动更硬的一处：**同指纹也会重复穿透缓存**

台账里有两组**主要服务端指纹完全相同**的投递（不是 §3.1 那种只差一个数字的）：

| 指纹 | 第一次 | 第二次 | cache_hit |
| --- | --- | --- | --- |
| `c04688c9…`（RetrievalTimeouts） | 04:22:36 | 04:27:36 | 均为 `none` |
| `1f283429…`（UpstreamRateLimited） | 04:20:37 | 04:30:36 | 均为 `none` |

同指纹、L1 TTL 是 2 小时，本该第二次直接回放——**实测没有**。两次的运行档位解释了原因：
`1f283429` 那两次都走 LLM 429 失败路径（`server_error.code=PIPELINE_ERROR`），
`c04688c9` 的第二次是 L2 档位下的 `mode=sop_fallback`。
按 `CONTEXT.md` 缓存域的既定口径，**SOP 直出不写 L1**（A2-6 的取舍），而失败路径同样不写。

结论：**L1 只缓存"成功且非 SOP"的答案**。所以在这条外部告警链路上，缓存与单飞的保护
恰好**在系统已经出问题的时候失效**——那正是告警风暴最可能发生的时刻。这条与 §3.1 的
指纹抖动是**两个独立原因**：即使把指纹修稳（§3.3 任一方案），同指纹在降级/失败路径下
仍会重复穿透。两者要分开决策。

代码指认（读 `gateway/ChatOrchestrator.runPipeline`）：L2 分支在写任何缓存之前 `return`
（注释写明"P4：SOP 直出**不写 L1**"，A2-6 同源）；LLM 失败的两个 `catch` 也在
L2 写入与 L1 写入**之前**抛出。故两条路径都不落 L1——**不是缓存失效，是这两条路径
本来就不入缓存**，而它们恰好是降级期与故障期的答案。

## 4. 本轮实测到的两处实现坑（已修，留作教训）

1. **假上游必须正确解 chunked 请求体**。JDK HttpClient 对流式 POST 发的是
   `Transfer-Encoding: chunked`（没有 `Content-Length`），只按 `Content-Length` 读会拿到 0 字节，
   于是每批只回 1 条向量、调用方 `IndexOutOfBoundsException: Index 45 out of bounds for length 45`。
   **第一次定位错了方向**（以为是批量数算错），加了 `req_bytes=` 日志才看到它是 0。
2. **embedding 与 LLM 共用 `opspilot.dashscope.base-url`**。要单独注入 LLM 的 429，
   embedding 腿必须仍然返回合法向量，否则向量腿先退化、触发的会是第二条规则，观测面被污染。

## 5. 边界与未验

- **未验**：真实外部集群的告警量级；多实例部署下的 bridge（单实例锁会成瓶颈）；
  `repeat_interval` 到点后的真实重发行为（4h，本轮没等）。
- **已知**：`resolved` 通知本轮未触发（三条规则都还在 active）；桥的 `group_cap` 未触发。
- **环境备注**：本机 Docker Hub 直连超时，镜像经 `docker.1panel.live` 镜像拉取后按规范名打标签；
  这是本机网络条件，不是仓库配置。

## 6. 后续：拆分告警身份与查询指纹（同日实施 + 第二轮实机验收）

§3.1 的错配在同日定案为 **方案 C（服务端拆身份）**。规则侧不动、桥侧不做归一化（那等于让桥
替网关决定指纹身份），改为"这条告警是什么"由调用方显式给出——桥从
`alertname/job/error_code/status` 四元组定长派生 `alert_id`（与 message 文本无关），
网关把它当**不透明身份**算指纹；L1 键与 SingleFlight 组键改吃这个指纹。人工提问与既有
自举告警源不传该字段，逐字回退到原文本指纹。

### 6.1 四组验收判据与结果

投递方式：直接向桥的 `/api/v2/alerts` POST 构造的 Alertmanager 载荷（外部契约的合法子集），
经桥 → 网关完整链路；判据取自 `logs/alert-bridge.jsonl` 与 `logs/audit.jsonl`。

| 判据 | 操作 | 结果 |
| --- | --- | --- |
| ① 身份稳定性 | 同 `alertname/job/error_code/status`、**词级不同**的三条 summary 投递 | `alert_id` 与 `fp` 三次完全相同；第 1 次 `L2`（语义缓存命中上一轮同形查询），第 2、3 次 `cache_hit=L1` |
| ② 身份隔离 | 换 `alertname`（其余维度不变）再投两条 | `alert_id`/`fp` 与①不同，未命中①的 L1 条目；本组 `L2 → L1` 各自成序 |
| ③ 缓存真生效（冷启动） | 新 `alertname` + 新错误码 + 词级不同 summary ×2 | 第 1 次 `cache_hit=none`、`total_s=14.242`（完整检索+生成）、refs=`rb-003,rb-115,rb-203`；第 2 次 `cache_hit=L1`、`total_s=0.034`、**同一 fp 与同一组 refs** |
| ④ 回归不动 | `mvn test` / `pytest offline/tests` / `doc_numbers.py --check` / `provenance.py --check` | Java 244 全绿、Python 276 全绿、两道文档门闩 OK |

**`fp` 与身份哈希逐字核对**：`SHA256("alert-id|" + alert_id)[:32]`（现算）与台账 `fp`
在 identity2 / identity2B / identity3 / final 四组**逐字相等**——服务端确实走了身份分支，
不是文本指纹的巧合。

审计面：上述每一行在 `logs/audit.jsonl` 都能找到 `source=alert` + `sub=sre-watcher` +
同 `fp` 的 `ev=chat` 行（缓存命中类型与桥台账一致）。

### 6.2 第二轮验收抓出的一个集成缺陷（已修）

第一轮验收出现"同一 `alert_id` 的两次投递 `fp` 不同"：桥把身份字段发成
**`alert_id`（snake_case）**，而 `ChatRequest` 是 Jackson 绑定的请求体（默认 camelCase），
且 Spring Boot 默认关闭 `FAIL_ON_UNKNOWN_PROPERTIES`——字段被**静默忽略**，请求退回文本指纹。
第一组的 A 案当时"看着命中 L1"是**假绿**：三条文案只差被 `<N>` 掩码抹平的数字，文本指纹
恰好也稳定；换成词级不同的文案复测即现形。

**修法**：桥的线格式字段改为 `alertId`；并补两层回归锁——桥侧断言请求体键是 `alertId`、
Java 侧用与 Spring 同配置（关 `FAIL_ON_UNKNOWN_PROPERTIES`）的 ObjectMapper 反序列化锁线格式。
**教训**：request body 的字段名必须用真实反序列化路径验，构造 record 的单测测不到它。

### 6.3 仍未解决（如实登记）

- §3.5 的同身份降级/失败路径重复穿透**不在本次修复范围**：拆身份修的是"身份碎"，
  不改变"L1 只缓存成功且非 SOP 答案"。触发线见 OPS §5.1 新增行。
- `resolved` 通知与跨指纹 incident 关联仍未做（见 §5 边界）。

## 7. 跨指纹 incident 关联（同日第二轮：关联，不是归并）

> 承接 §3.1 的另一半。§6 修的是"身份**碎**"（同一根因被拆成多个指纹）；这一节做的是
> §5 边界里那条"跨指纹 incident 关联"，而**决策是关联、不是归并**——N 条告警仍然各自成答，
> 变的只是"能不能按一次事故整段读出来"。事故的边界**由上游定义**（Alertmanager 的 `group_by`），
> 本系统不重新发明这层判断。定案与理由见 [ADR-0019](../adr/0019-incident-boundary-owned-by-upstream.md)
> （上一轮的拆身份同日追认为 [ADR-0018](../adr/0018-alert-identity-split-from-query-fingerprint.md)）。

### 7.1 主判据（真跑，真实规则触发）

触发手段全走真实故障：`docker compose stop opspilot-qdrant` → 发一条 hybrid 请求（检索腿超时，
`aiops_retrieval_timeouts_total` 0→1）；`POST /admin/degrade {"level":"L2"}` → 发一条**换了措辞**的请求
（同 query 会命中 L1 回放、根本不进 SOP 分支，实测踩到一次）→ `aiops_sop_fallbacks_total` 0→1。
两条规则随即 fire（`OpsPilotRetrievalTimeouts` 05:32:06Z / `OpsPilotSopFallbacks` 05:32:51Z）。

桥台账 `logs/alert-bridge.jsonl` 三行（同一 `incident_id`，两个不同 `alert_id`）：

```text
ts=05:33:03  OpsPilotRetrievalTimeouts firing  incident=am-3a91aa…  alert_id=71c5d2…  hit=none
ts=05:33:50  OpsPilotSopFallbacks       firing  incident=am-3a91aa…  alert_id=dfc80c…  hit=none
ts=05:33:50  OpsPilotRetrievalTimeouts  firing  incident=am-3a91aa…  alert_id=71c5d2…  hit=L1
```

审计面 `logs/audit.jsonl` 同三条：`source=alert` + `sub=sre-watcher` + **同一个** `trace_id=am-3a91aa…`，
而三个 `fp` 互不相同（`2f67ba…` / `5565b7…` / `2f67ba…`）。

**判据成立，且同时否证了归并**：两条不同告警各有自己的 `alert_id` 与 `fp`（N 个答案），
却共用一个 `incident_id`（一次事故）。

### 7.2 三条边界判据（构造载荷直投桥）

真实故障等不到这些形态（跨天、组重建、超上限），故按 §6 的先例构造载荷 POST `/api/v2/alerts`。
这批载荷**注解留空**，故台账里 `skip=empty_query`——身份派生发生在摊平阶段，与是否真的打网关无关，
这样跑既不消耗配额、也不给产品面制造假流量。

| 判据 | 做法 | 结果 |
| --- | --- | --- |
| ① 时间维生效 | 同 `groupKey`、`startsAt` 差一天的两条各投一次 | `am-f76cd3…` ≠ `am-e04435…`，**换新**（否证"裸 groupKey 当事故 id"） |
| ② 组生命周期 | 同组先投 1 条、再投 2 条（含新成员）；另投一次起点更晚的 | 并入成员前后同为 `am-26a0b5…`；起点更晚那次为 `am-055af1…`（组重建换新） |
| ③ 形状 + 截断无关 | 21 条（超 `GROUP_CAP=20`），一次把最早那条放在**被截断的尾部**、一次放在头部 | 两次同为 `am-cc3603…`；形状匹配 `^am-[0-9a-f]{32}$` |

判据③是唯一会**静默**失效的一条：若 `min(startsAt)` 在截断后计算，同一场事故只因"这批带了几条"
就换身份，台账里没有任何异常痕迹。故它有专门的单测锁（`test_incident_id_is_taken_from_the_whole_payload_not_the_truncated_head`）。

### 7.3 代价观测（主动认下的账，实测读数）

| 观测项 | 读数 |
| --- | --- |
| 投递延迟 = 台账 `ts` − 该 alert 的 `startsAt` | `OpsPilotRetrievalTimeouts` **57s**；`OpsPilotSopFallbacks` **59s**（含 `group_wait 30s`） |
| 组更新间隔（首次通知 → 并入新成员后的更新） | 05:33:03 → 05:33:50，**47s**（`group_interval: 1m`） |
| 载荷是全体成员还是增量 | **全体成员**：05:33:50 那次同时带了新成员 `OpsPilotSopFallbacks` 与重发的 `OpsPilotRetrievalTimeouts`（一条 webhook 帧 → 两行台账） |
| 重复转发那次的成本 | `cache_hit=L1`——**重复不再重跑检索与生成**，这是 ADR-0018 那层身份在真实流量上的回报 |
| `resolved` 通知 | 与 firing 共享同一 `incident_id`；但 `alert_id` 不同（`41c82f…` / `bf0709…`，`status` 进身份），且两次都 `cache_hit=none`——**各跑一次完整管线** |

### 7.4 回归面

| 项 | 结果 |
| --- | --- |
| Python 用例 | **284 passed**（本轮 +8：桥 7 条 + `localapi` 1 条） |
| Java | `mvn test` 0 failures（A2-8 修复后 surefire 执行 250 / `@Test` 声明 249；差 1 的原因未查，见 7.5） |
| 文档门闩 | `doc_numbers.py --check` OK（23 文档 / 36 数字）· `provenance.py --check` OK（chunks 448 / golden 68） |
| 面板契约 | 五层判据**等价复算** OK——本机 `bash` 不可用（WSL 无发行版），按脚本原文在 PowerShell 里逐层重算 |
| 配置语法 | `amtool check-config` SUCCESS（1 inhibit / 1 receiver）· `promtool check rules` SUCCESS（3 rules） |

### 7.5 仍未做与如实登记的异常

- **`acceptance_a2.py` 曾 9/10：A2-8（权限硬隔离）稳定红，已定位并修复（2026-10-17 复查）。**
  根因不在权限过滤，也不在快路径取舍，而在 **active Qdrant 集合本身退化**：
  `opspilot-vectors-20261017042627` 的 448 个点全部是同一个常向量（每个分量 `1/sqrt(1024)`），
  两两余弦=1.0；查询向量与库内向量近似正交（同文本余弦仅 -0.024），向量腿退化为噪声，
  RRF 把 ES 精确命中的 level-3 块挤出 final-top-3。修复：按当前 live 配置 reingest（16s），
  新集合 `opspilot-vectors-20261017194227` 向量恢复区分度（同文本/查询余弦 0.76–0.88 / 0.51–0.81），
  A2-8 与 A2 全套回到 **10/10 PASS**。防复发：新增 `EmbeddingSanity` 入库前护栏
  （条数 / 维度 / 有限值 / 零向量 / 常向量 / 全同向量），5 条单测锁住"448 点全同也要在换别名前失败"。
  残余风险：`50071_CONFIG_CENTER_UNREACHABLE` 这类多命中查询在向量腿恢复后仍可能不出现 level-3 块，
  属相关性与密级覆盖的取舍，不是越权。
- `mvn test` 的 surefire 执行数比 `@Test` 声明数多 1（245 vs 244）。`doc_numbers` 登记的是**声明数**
  （README/repo-map 引用的也是它），故门闩不受影响；差异原因未查，登记备查。
- **不做**：跨指纹归并（N→1）、SSE `meta` 回吐关联键、审计行加 `alert_id` 列（当前要读"这次事故
  有哪几条告警"需 join 桥台账）、给 Python 用例数加门闩。
- §3.5 那条"同身份在降级/失败路径重复穿透"**仍是独立问题**，本轮未碰。

### 6.4 环境备注（本轮新增）

本机无可用 bash，桥新增最小 `.env` 读取（`load_env_file`，不覆盖显式 env），使其可脱离
shell 方言独立启动；`README.md` / `docs/repo-map.md` 的测试计数（244 / 276）随本轮用例同步。
