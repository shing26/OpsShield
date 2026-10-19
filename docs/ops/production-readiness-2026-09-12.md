# 生产就绪度台账 — OpsPilot（起始评估 2026-09-12）

> **读法**：顶部「现行状态」是唯一的现行口径；横线以下为逐轮追加的历史记录（原文保留），
> 各节内的状态表是**该节写作时刻的快照**，与顶部冲突时以顶部为准。
> **债务与触发线不在此重复维护**——单一事实源是 [`OPS.md` §5 / §5.1](../../OPS.md)；本文件只回答
> "已经做完什么、证据在哪"。
> **台账契约**：每项修复落地后回填「现行状态」+ 证据落点（commit 或产物）——不写就没人知道它做完了。
> （2026-09-22 的 B9 曾漏回填，2026-09-23 清理台账时据 commit 补记——补记一律标注出处，不冒充原作者。）
> **本文件不手写易漂的数字**（用例数、语料数、指标值）：那些由 `offline/doc_numbers.py` 在 CI 守着，
> 以 README/产物为准——手写的数字必然过期，这正是 2026-09-21 那一轮的教训。

## 现行状态（最后更新 2026-10-19）

| 能力面 | 现行状态 | 依据 / 证据落点 |
|---|---|---|
| H1 应用日志持久化 | **已修** | logback `APP_FILE` 按天+100MB 滚动、7 天/500MB cap，pattern 带 request_id |
| H2 request_id 贯穿 | **已修** | `RequestIdFilter`（MDC）→ 编排虚拟线程重挂 → 审计行同 id |
| H3 LLM 退避重试 | **已修**（并按 2026-09-19 P1 **类型化**：5xx 分类读 status 字段而非文案） | `LlmClient`；`LlmHttpException` |
| M1 启动脱敏配置摘要 | **已修** | `StartupConfigSummary`（凭据只出 `set(len=N)/absent`） |
| M2 磁盘哨兵 | **已修** | `daily_usage.py` 输出 `logs_disk_mb`，>500MB 告警 |
| M3 告警接入 | **自举源已上线**（ADR-0011）；**外部监控 adapter 第一版已落地**（2026-10-17，触发线①兑现） | 宿主桥 `scripts/alert_bridge.py` + `ops/alerting/`（Prometheus 规则 → Alertmanager → 桥）+ compose `alerting` profile；实证见 `docs/qa/2026-10-17-alertmanager-intake.md`。**「提升为网关原生端点」是另一个决策**，触发线见 `OPS.md` §5.1 |
| M4 错误码枚举 | **不立项**（实测全仓恰好 1 处且属拒答话术文案，"<10 处"原判不实） | `docs/qa/2026-09-13-module-verification.md` §8 |
| L1–L5 企业级项 | **挂起**（Prometheus 导出 / 多实例 / OIDC / TLS / 增量 ingest） | 触发线见 `OPS.md` §5；勿提前做 |
| B3 出站超时 | **已修**（2026-09-22） | 精排/embedding/ES 三路此前**完全无超时**；改为两个配置点覆盖四个调用位 + 4 个 env 键 |
| B9 覆盖率棘轮 | **已接**（2026-09-22）：LINE 设闸、BRANCH 只报 | `scripts/check_coverage.py` + jacoco `target/site/jacoco/jacoco.xml`；**本机量不到覆盖率**的边界见文末该节 |
| O1 证据链打包 | **已修**（2026-09-18），2026-09-21 增强 | 出包前自检"报告↔语料同代"，分叉默认拒绝、`--allow-stale` 才放行并留痕；CI 每次干净检出跑一次 |
| O2 同代门闩（两面） | **已修** | 面一「报告↔语料」`offline/provenance.py`；面二「文档数字↔产物」`offline/doc_numbers.py`（每次现算、无 `--stamp`）；两面进 CI |
| O4 许可证 | **已做**：MIT | 根 `LICENSE`（`.gitattributes` 钉 LF）；理由见 README §许可 |
| 处置闭环（对目标系统写操作/自愈） | **未覆盖，产品承诺非缺陷**（只读立场 + 人做决定） | 触发线见 `OPS.md` §5 "处置闭环"行 |
| 复盘 → 知识回灌 | **未覆盖，已入账**（2026-09-23 登记） | 知识库只读、运行期无「结论→语料」写路径；触发线见 `OPS.md` §5.1；谱系表 ④ 行已明示。**2026-09-24 补：前置入口已建**（答案反馈端点，见下） |
| 承诺线（SLI/SLO） | **已建**（2026-09-24） | `OPS.md` §11 承诺表（七条 SLI + 核色方式与频次）；可派生数字入门闩面二 |
| 防幻觉事后环（答案接地 V9） | **已建，且 live 已验**（2026-09-25） | `offline/grounding.py`（纯函数）+ 探针 V9（复用 V2/V4 答案，零额外 chat 预算）。**live 断言已通过**（无据错误码 0）——具体读数属 live 波动值，按本文件契约不入表，见探针当次输出。OPS §5.1 已登记其覆盖边界 |
| 降级代价语义 | **已登记**（2026-09-24） | [ADR-0012](../../docs/adr/0012-degradation-cost-semantics.md)：L1 = **检索质量降**（即 `es_only` 列的口径，数值以评测报告为准、不在本表复述）+ **门控降**（相关性级 → 零召回级）；同构回归锁 `ChatOrchestratorTest.degradedL1SearchIsIsomorphicToEsOnlyMode`；重开触发线入 OPS §5 |
| 分段耗时（审计行 `stage_ms`） | **已建**（2026-09-24） | `retrieval/LegTimings` + `metrics/StageTimings`（八段）；非 chat 路径传 null → 不落字段（"没测"≠"测得为 0"） |
| 代价量化脚本（门控混淆矩阵 / 并发曲线 / 缓存节省账 / 真实输入探测） | **四项全部出数**（两项 2026-09-24、两项 2026-09-25 欠费解除后完成） | `offline/eval/reports/gate_matrix.{json,md}`、`offline/eval/reports/observed_probe.{json,md}`、`offline/load/reports/concurrency_sweep.{json,md}`、`offline/load/reports/cache_savings.{json,md}`（数值以产物为准，不在本表复述）。**探针 V9 的 live 断言已通过**。**核色前置**：`python scripts/check_upstream.py`（三路探活，全通过才 exit 0）——降级会静默掩盖上游故障，故已写成每次核色的第一步（见 `OPS.md` §11） |
| 答案反馈入口（人机协同） | **已建，且 live 已验**（2026-09-27） | `POST /api/v1/copilot/feedback` → 审计 `ev="feedback"`（按 fingerprint 归档、不占配额）；是"复盘→知识回灌"的前置入口。**live 四态实测**：未认证 401 ｜ 合法 200 且审计行落（含身份三元组+fp+verdict）｜ 未知 fingerprint 200（接受而非拒绝）｜ 非法 verdict 400（DTO @Pattern） |
| 告警身份拆分（ADR-0018） | **已落**（2026-10-17） | 指纹不再从人类可读文案推断身份：调用方（桥）从 `alertname\|job\|error_code\|status` 定长派生 `alertId`，服务端只做**不透明**哈希；L1 键、单飞组键、审计列与 `meta` 共用同一 `fp`。缺省逐字回退文本指纹。踩坑与实证见 qa 台账 §3.1/§6 |
| 跨指纹事故关联（ADR-0019） | **已落**（2026-10-17） | 事故边界由上游 `group_by` 定义，`am-…` 经 `X-Trace-Id` 落审计 `trace_id`；**只关联不归并**（N 条告警仍是 N 个独立答案）。未实测边界（anchor 随成员进出漂移）见 ADR-0019 注记；操作面见 `OPS.md` §13 |
| 排障台 → 统一工作台（ADR-0020/0021） | **已落**（2026-10-18 / 19） | `/` 一个壳两个视图、默认排障台；健康抽屉按身份分级（**门禁一字未动**）；页内用户名+口令登录，token 存 `sessionStorage`；对照模式降为高级入口。单文件阈值告警（1363 行）见 ADR-0021 注记 |
| 零依赖存储模式（ADR-0022） | **已落**（2026-10-19） | `opspilot.storage.mode=live\|memory`：显式开关、默认 `live`、**不自动回退**；三个中间件全停时可启动并跑通，启动摘要与 `/admin/health` 标 `backend=in-memory`。**memory 环境不进任何核心指标表**。两条边界（L2 缓存过滤未共享、审计无 `backend` 列）见 ADR-0022 注记 |
| 覆盖范围声明 + 对外面术语门闩（ADR-0023） | **已落**（2026-10-19） | `GET /api/v1/copilot/coverage`（按身份过滤、从语料现算，与检索共用 `identityFilters`）；`done.near_miss` 只给标识符导航；术语双轨 + `scripts/check_public_copy.py`（词表从 `CONTEXT.md` 现取）进 CI。反向启发式只报不闸 |

**逐轮修复流水**（细节见横线以下各节）：2026-09-12 五维评估 + H1/H2/H3/M1/M2 → 09-18 证据链 O1/O2 →
09-19 外部评审核实（2 采纳 2 不采纳）+ P1 类型化 5xx + P2 配置 fail-fast → 09-21 O2 面二 + 打包自检互锁 +
MIT LICENSE → 09-22 B3 出站超时 + B9 覆盖率棘轮 → 09-23 账外边界「复盘→知识回灌」入账 + 门闩自身两处缺陷修复 →
**09-24 承诺线 SLI/SLO + 降级代价语义（ADR-0012）+ 分段耗时 + 代价量化四脚本 + 答案反馈入口** →
09-25/27 防幻觉事后环 V9 + 答案反馈入口 live 四态 → **10-17 告警接入第一版（ADR-0018/0019：告警身份拆分 + 跨指纹事故关联，触发线①兑现）→ 10-18/19 统一工作台（ADR-0020/0021）+ 零依赖存储模式（ADR-0022）+ 覆盖范围声明与对外面术语门闩（ADR-0023）**。
（10-19 一轮 code-review 后补：`OPS.md` §5 补"静态 SOP 缺 auth_level 维度"债务行；ADR-0019/0022 补未实测边界与未落地项的如实登记。）

---

> **—— 以下为历史记录（2026-09-12 起逐轮追加，原文未改）——**
> 起始评估的口径是双定义制：**定义 A** = 5-50 人团队的单机内部工具（可上线线）；**定义 B** = 多租户
> 企业级 SaaS（季度级）。结论按定义 A 给出，定义 B 差距全部在债务闹钟表有触发线（ADR-0003/0005/
> 0009、OPS 债务表），是**记录在案的取舍**而非疏忽。起始评估时 HEAD 为 `a2f41f0`。

## 总判

**不是玩具级 Demo，已达到"单机生产就绪"（定义 A 可上线）**。玩具的标志——无鉴权、无降级、
配置硬编码、错误裸抛、print 调试——五项全部相反。距企业级（定义 B）的差距真实存在但均带触发线。

## 五维度评估

### 1. 容错机制 — 部分具备（偏强）

| 项 | 状态 | 代码依据 |
|---|---|---|
| 降级 | 已具备 | `DegradationStateMachine`：inflight 超阈→L1 纯 ES、LLM 连败 3 次→L2 熔断 SOP 直出、60s 冷却自愈、手动锁（A2-6 锁） |
| 超时 | 已具备（三层） | 检索 leg 4500ms 双路隔离（`HybridSearchService`，`retrieval_timeouts` 计数）；LLM 60s+连接 5s（`LlmClient`）；HealthProbe 3s 自吞；SseEmitter 120s/300s |
| 异常捕获 | 已具备 | `ChatOrchestrator.submit` try/catch→`sink.error` 协议化收尾；SSE 客户端断开容错（RST 三连实测 inflight 零泄漏）；全局异常处理器按面分流 |
| 失败切换 | 已具备 | blue/green 失败保旧库在线（ADR-0006）；live/mock 双模自动切换 |
| 重试 | **缺失** | 全仓 grep retry/backoff 零命中——瞬时网络抖动无退避直接计熔断；429 与网络错未分类 |

修补：**H3**（单次退避重试 + 429/网络错分类计数）。

### 2. 日志体系 — 部分具备

| 项 | 状态 | 代码依据 |
|---|---|---|
| 分级 | 已具备（基础） | `application.yml:68-70` root/com.opspilot INFO |
| 结构化 | 部分 | 审计面每请求一行 JSON（AUDIT appender，14 天滚动，**七类事件**全留痕：`chat`/`search`/`auth`/`admin`/`invalid`/`feedback`/`degrade_transition`，各自独立 schema 但共用 `writeEvent` 唯一出口）；**应用日志人读 pattern 非结构化** |
| 持久化 | 部分 | audit.jsonl 落盘 ✓；**应用日志仅 CONSOLE——容器重建即丢**，无 totalSizeCap |
| 关键链路 | 已具备 | 七类事件覆盖业务/鉴权/运维/校验/反馈/档位转移；**关联已闭环**——`request_id` 贯穿 app.log 与审计行（H2），2026-09-28 增调用方 `trace_id`（`X-Trace-Id`）把上层 agent 的多步调用串成一次 incident。两个 id 分工见 `CONTEXT.md` 权限域 |

修补：**H1**（APP_FILE 滚动 appender）、**H2**（request_id 贯穿审计与生成链）。

### 3. 配置管理 — 部分具备（取舍已记录）

| 项 | 状态 | 代码依据 |
|---|---|---|
| 外部化 | 已具备 | 全量 `${VAR:default}`；compose `:?` 缺凭据 fail-fast；`.env` gitignore；口令仅 env/stdin |
| 环境区分 | 部分（有意） | 无 profile 文件——mock/live 由 key 存在自动切换（同代码路径双模）；单机下 env 即环境差异（ADR 级取舍） |
| 安全默认 | 已具备 | actuator `show-details: never`；端口环回绑定；中间件全认证 |

修补：**M1**（启动时脱敏配置摘要）。

### 4. 监控与告警 — 部分具备

| 项 | 状态 | 代码依据 |
|---|---|---|
| 健康检查 | 已具备（双面） | `/actuator/health` 聚合 + `/admin/health` 鉴权明细探针（5s TTL 双检防惊群）；断 ES 实测 DEGRADED |
| 指标采集 | 已具备（自研） | OpsMetrics 10 计数器 + 运行态（在途组/熔断/冷却/配额），Ops Console 实时可视化；**进程内存态，重启清零，无趋势** |
| 指标导出 | 缺失 | 无 micrometer/prometheus——ADR-0009 触发线（多实例/>5 人日常用）后升级，约 1 天 |
| 告警 | 部分 | daily_usage 拒答率阈值退出码（cron MAILTO）；实时 webhook 按硬约束挂起（无真实告警源不做 adapter）。**状态注（2026-09-16，ADR-0011）**：已有**自举告警源**（`offline/alert_producer.py`，读运行态真相面 → `source=alert` 回打自身链路），外部监控系统的 adapter 仍挂起 |

修补：**M2**（logs 目录磁盘哨兵并入 daily_usage）；导出线不动。

### 5. 错误处理 — 已具备（2026-09-12 当轮闭环）

| 项 | 状态 | 代码依据 |
|---|---|---|
| 业务/系统分离 | 已具备 | 4xx（INVALID_REQUEST/HTTP_405/415/404）与 5xx 分离，四专属分支杜绝客户端错误污染 5xx（V2 十探针） |
| 形状规范化 | 已具备（双面） | /api `{code,message}`；/v1 OpenAI `{"error":{message,type}}`；filter 短路亦保形状 |
| 输入校验 | 已具备 | @Valid + 类型/方法/媒体类型全分支，文案直写必达 + 400 类留痕 |
| 卫生 | 已具备 | 无栈泄漏（多轮红队实证）；对内留栈对外屏蔽 |

剩余小项：错误码字符串散落，随下一需求收拢为枚举（M4，1h，未入本轮）。

> **状态注（2026-09-17，两处口径更正 + 一项降级）**：
> 1. **本节状态表是 2026-09-12 的修复前快照**：上表"重试｜缺失"与"应用日志仅 CONSOLE"两行在**本节之后的修补段**已记 H3/H1 修完。**现行口径一律看文件顶部「现行状态」（2026-09-23 起置顶）**——下方原文保留只为可追溯，不再承担"当前状态"的职责。
> 2. **M4 不立项（降级）**：后出的实测证词（`docs/qa/2026-09-13-module-verification.md` §8）grep 全仓主源码+资源，错误码字符串**恰好 1 处**且是拒答话术里的示例码（文案非配置）——"<10 处"的原判不实。故 M4 从"待修"降级为**不立项**；若将来演进到需要按枚举做类型化处理，再按新需求重开。
> 3. **计数器数目**：本文件写"OpsMetrics 10 计数器"，现为 **11 键**（新增 `verbatim_masked`，生成质量包 Q2=C 的产物）。
> 4. M3 告警接入的状态更新见 §"告警"行与末段（自举源已接入，ADR-0011）。

## 差距清单与实施顺序

**高（上线前，~1 工作日）**：H1 应用日志持久化+滚动+cap（0.5h）→ H2 request_id 贯穿（1-2h）→
H3 LLM 单次退避重试+429/网络错分类（2-3h）。
**中（上线后随手）**：M1 启动脱敏配置摘要（0.5h）→ M2 磁盘哨兵（1h）→ M4 错误码枚举（1h，另轮）。
M3 告警 webhook：**2026-09-16 状态更新（ADR-0011）**——自举告警源已上线（换源非 adapter）；面向外部监控系统的 webhook adapter 仍等真实告源（硬约束）。
**低（企业级触发线，ADR/债务闹钟在案，勿提前做）**：L1 Micrometer 导出（~1 天）→ L2 多实例
RS256+分布式 SF（周级，>200 人/QPS50）→ L3 OIDC（公司强推）→ L4 TLS/等保（跨机）→
L5 增量 ingest（语料 >2000 条或更新 <10min）。

## 修补记录（落地后回填）

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| H1 应用日志持久化 | **已修** | logback APP_FILE（按天+100MB 滚动，7 天/500MB cap，pattern 带 request_id） | 容器实测 logs/app.log 56KB 落盘；启动摘要在场且凭据全部 set(len=N)/absent 形态、无凭据值 |
| H2 request_id 贯穿 | **已修** | RequestIdFilter（MDC，@Order 最高先于守卫）→ 编排虚拟线程任务内重挂 → 审计行 writeEvent 读取；审计行 additivity=false 不入 app.log（关联语义=编排 warn/error 行与审计行同 id） | mvn 单测锁 MDC 同源；容器实测部署后审计行 100% 带 8 位唯一 request_id。**踩坑**：类名不得叫 RequestContextFilter（与 Boot 内置 bean 冲突启动失败，实测） |
| H3 LLM 单次退避重试 | **已修** | LlmClient.streamChat 重试循环：仅首 token 吐出前的瞬时错（429/网络 IOException/HTTP 5xx）单次退避（1s/400ms）；OpsMetrics 增 llm_retries/llm_network_errors；终态 429 仍 llm_rate_limited | 单测 5 例（重试/耗尽/429 类型传播/吐 token 后禁重试/非瞬时禁重试）全绿；/state 暴露新键；面板 tiles 同步（契约四层仍绿） |
| M1 启动配置摘要 | **已修** | StartupConfigSummary（ApplicationRunner）：凭据全部 set(len=N)/absent 归约后拼接，无字面量 | 容器实测摘要在 app.log；Mimosa 扫描器对 apiKey() 方法名两次误报"硬编码凭据"——实为脱敏读取，已留档待人工复核 |
| M2 磁盘哨兵 | **已修** | daily_usage.py 输出增 logs_disk_mb，>500MB stderr 告警（不占 refuse 退出码） | 实测 logs_disk_mb=1.7 正常输出 |
| M3 告警 webhook | 部分接入 | 自举源已上线（ADR-0011）；外部监控 adapter 仍无真实告源（硬约束） | `offline/alert_producer.py`、`docs/qa/2026-09-16-self-alert-loop.md` |
| M4 错误码枚举 | **不立项（2026-09-17 降级）** | 原判"字符串 <10 个"不实：主源码+资源 grep 实测**恰好 1 处**且是拒答话术里的示例码（文案非配置），见 `docs/qa/2026-09-13-module-verification.md` §8 | 不立项；若演进到需按枚举做类型化处理再按新需求重开 |
| L1-L5 | 挂起（企业级触发线） | ADR-0003/0005/0006/0009、OPS 债务表 | — |

## 修补记录（2026-09-18：证据链与文档时效，来自外部补齐清单 O1/O2）

> 这两项不是"缺功能"，而是**卖点的交付方式**：本项目的差异化是"每个出口都可核验"，
> 而证据此前散在五个落点、且"数字与产物同代"只有人工纪律兜底（E1 教训两次，两次都靠人发现）。

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| O2 报告↔语料同代门闩 | **已修** | `offline/provenance.py`：对"语料文档字节集 + `chunks.jsonl` + `golden_dataset.jsonl`"取内容摘要（行尾归一），CI 新增 `provenance` job 跑 `--check`；`evaluate.py` 落盘报告后自动 `--stamp` 刷新出处登记 | `offline/tests/test_provenance.py` 13 例全绿；**双向锁**——"加一篇语料不重生成报告 → 红"与"只重新登记 → 不红"都有用例；变异验证：把 `diff()` 改成恒空则 4 例失败、把自洽性检查放水则 2 例失败（证明门闩非空转） |
| O1 证据链一键打包 | **已修** | `scripts/pack_evidence.py`：一条命令产出自述快照（目录 + 可选 zip），档内 `MANIFEST.md` 逐项写明出处文件/产生命令/复核前提三档（干净检出｜需活体栈｜需 live key）；`CHECKSUMS.sha256` 供 `sha256sum -c` | 干净检出形态（隐藏 `logs/`）实测一条命令产 35 产物、35 项校验全 OK；`.env` 全部 8 个值 grep 归档零命中；`offline/tests/test_pack_evidence.py` 10 例全绿（含"非 LOCAL 登记项不得是 gitignore 路径"与"MANIFEST 不得含 key 值"） |

**两处实测踩坑（已修，均属跨平台行尾一类）**：
1. 内容摘要最初直接吃工作区字节 → 本机 CRLF 与 CI（LF）会算出不同摘要、门闩在 CI **假红**。改为按内容归一（文本类 CRLF→LF）后再摘要；并把 `*.jsonl`/`*.md` 一并钉进 `.gitattributes`（该文件此前只覆盖 `.sh/.py/.yml`）。实测等价性：同一语料按 LF 复制后三路摘要逐位相同。
2. `CHECKSUMS.sha256` 最初用 `write_text` 落盘 → Windows 写出 CRLF，`sha256sum -c` 把行尾 `\r` 当文件名、**35 项全部失败**（归档自带的完整性命令直接失效）。改为显式 LF 落盘，实测 35/35 OK。

## 外部框架评审核实（2026-09-19，来源：code-review-agent《全项目框架补强建议》§3.6 + §四）

> 该评审对 OpsPilot 的指控均标**【勘察】未复核**。逐条实测（HEAD bf9b1ad）后：2 条属实采纳、
> 2 条不采纳。不采纳项按纪律登记触发线（见 OPS §5 债务表）——不留"没有到期日的欠账"。

| 指控 | 实测 | 处置 |
|---|---|---|
| 错误分类靠 `startsWith("LLM HTTP 5")` 文本判别（§四#1） | **属实**：`LlmClient.java:104` 拼文案、`:140` 按前缀分类；全测试域 grep `LLM HTTP 5` 零命中——5xx→transient 分支无回归锁（LlmClientTest 只锁了 IOException 与 401 两路） | **采纳（P1）**：类型化异常带 status 字段，`isTransient` 判 status 不读文案；用例双向锁 |
| 配置无 fail-fast（§四#2） | **属实**：`OpsPilotProperties` 全 record 零校验注解；实测锐边——`inflight-threshold=-1` 使 `DegradationStateMachine.java:42` 恒真 = **永久 L1 且无告警**；`llm-timeout-seconds=0` = 每次调用立即超时→熔断常开 | **采纳（P2）**：`@Validated` + 约束注解，非法值启动即拒并点名变量；application.yml 默认值逐项核对不会误伤 |
| `LlmClient` 无接口、"换供应商须重构核心" | **夸大**：baseUrl/model/key 全走配置，硬编码仅 URL 路径段 `/compatible-mode`；LLM 腿换 OpenAI 兼容供应商≈改配置。真供应商耦合在数据层（1024 维向量 + rerank，[ADR-0002](../../docs/adr/0002-dashscope-one-stop-1024-dim.md) 在案决策） | **不采纳**：接口只有一个真实实现（mock 是测试模式非供应商），为它建抽象是投机泛化。触发线入 OPS §5 债务表 |
| `Level` 枚举被 sink 层耦合，挪出可减改动点 | **诊断反了**：Level 属 resilience（`DegradationStateMachine.java:18`），gateway→resilience 依赖方向正确；"加一档改 ≥6 处"是带 UI 呈现的状态机的固有扇出，挪文件减少 0 个改动点；漏改风险已被面板契约 CI job + `DegradationRecoveryTest` + `AdminControllerTest` 18 格矩阵兜住（有报错，非评审所称"漏改不报错"） | **不采纳**（扇出≠错位） |

> 口径注：评审 §2 给的"83 文件 / 7,506 行"与实测 **82 文件 / 7,056 行**（main+test Java）不符（其扫描脚本口径不同，`IngestionRunner 371 行`一条倒是精确）。外部二手数字引用前须按本表对账——与 E1 教训同源：不采信未复核的他方数字。

## 修补记录（2026-09-19：外部评审采纳项落地回填）

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| P1 5xx 分类类型化 | **已修** | `LlmHttpException`（带 `status` 字段 + `isServerSide()` 收口瞬时性判定）替换 `"LLM HTTP n"` 文案异常；`isTransient` 读字段不读文案；`attemptOnce` 对其直传不包裹（一旦被包裹就丢 status，分类退化回读文案）。message 保留同文案仅供人读 | LlmClientTest 7 例全绿：503 重试耗尽→计 llm_retry + llm_network_error 且 `status=503` 原样上抛（新增）；401→不重试（改类型化）；**反向锁**——文案含 "LLM HTTP 503" 的普通异常→不重试（证明文案不再承重，倒退回字符串匹配时该例必红）；mvn 123 全绿 |
| P2 配置 fail-fast | **已修** | `OpsPilotProperties` 加 `@Validated` + 嵌套 record 逐字段约束（`@Min`/`@Max`/`@Pattern`）。约束只挡"会让系统静默走错"的值：degrade 三值、llm-timeout-seconds、leg-timeout-ms、各 topK、embedding-dim、grpc-port 1-65535、min-relevance/l2-threshold ∈ [0,1]、mode 白名单 auto\|live\|mock（写错此前会静默退回按 key 判定——演示时 key 在场就悄悄 live 烧真钱）。合法边界（如 min-relevance=0 关闭门控）不挡；application.yml 默认值逐项核对不会误伤 | OpsPilotPropertiesValidationTest 8 例（ApplicationContextRunner；负例一律以全量合法值打底、注入单个非法值，保证失败原因唯一）：`inflight-threshold=-1`/`llm-timeout-seconds=0`/`min-relevance=1.5`/`mode=LIVA`/`grpc-port=0` 各自**启动失败且报错含字段名**；全量默认值绑定成功、mode 大小写不敏感、auto 无 key 回落 mock（双模既有行为未被校验破坏）。mvn 131 全绿（CI ubuntu 同绿）。**活体复验（2026-09-19，打包 jar）**：① 负向——`java -jar … --opspilot.degrade.inflight-threshold=-1` → exit=1 + `APPLICATION FAILED TO START` + `Property: opspilot.degrade.inflightThreshold` + `Reason: 最小不能小于1`；② 正向对照——真实 yml 合法值启动**零校验告警**，推进到 Tomcat 8081 与 H2 连接，仅因本机未起 Redis（`RedisConnectionException: localhost/127.0.0.1:6379`）中止 ⇒ 真实 yml 在约束下合法，门闩不误伤正常流程。**顺带钉死一条语义**（首跑实测发现）：构造器绑定下缺省 int 字段=0，必被 @Min(1) 拒绝——这是刻意设计，谁删了 yml 一行就应在启动期被点名，而不是带 0 阈值静默跑（判据见测试类 Javadoc） |

## 修补记录（2026-09-21：O2 面二 + 证据包自检互锁 + LICENSE）

> 触发：外部补齐清单 `05-opspilot-aiops` 复核（结论：O1/O2 已于 09-19 落地、O3 挂起合理、只剩 O4）。
> 复核中实测出一条**已发生但无人看见**的同族事故，故本轮补的不是新功能，而是 O2 漏掉的那一半：
> README 写「121 用例」，而 `src/test` 下 `@Test` 声明与 surefire 执行数**同为 131**
> （差 10 条 = `RequestIdFilterTest` 2 + `OpsPilotPropertiesValidationTest` 8 两次修复新增）。
> **面一（报告↔语料）同代 ≠ 面二（文档↔产物）同代**：报告是对的，人抄进文档时抄错，面一完全看不见。

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| O2 面二：文档数字↔产物门闩 | **已修** | `offline/doc_numbers.py` + `offline/doc_numbers.json`（21 条登记：Java `@Test` 数、ADR 份数、chunks/语料/golden 计数、评测报告各命中率与 MRR、越狱用例数、L1 延迟 p99、Locust 吞吐/请求/失败/P50）。判据三条：派生自产物**现算**（不存快照、**无 `--stamp`**，故不存在"重新登记即掩盖漂移"）、每个 `cited_in` **至少命中一次**（0 命中＝门闩指不到人，报红而非静默放过）、命中**全部**须等于派生值（同一数字多处出现，任一处漂移即红） | `offline/tests/test_doc_numbers.py` 15 例全绿；**变异验证**：改文档数字（131→132）→ 红且点名 `README.md` 的具体行号 + 期望/实际（该行号由用例现算，不硬编码）；改产物（chunks 加一行）→ 红且点名 `chunks_lines`；改写文案（"131 单测"→"131 个单测"）→ 红且报"0 命中"。**反向锁**：未登记数字（"22.3s"）与历史叙事口径（"0.813→0.767"）改动**不得红**（钉住"只对登记数字负责、不做全文扫描"的边界）；连跑两次同结果。**手工演示（真实工作区，非仅 fixture）**：把 README 的 131 改成 132 → **面二转红**并点名 `README.md:339` + 期望/实际；**同一刻面一仍为绿**（语料与报告都没动）——这是"两面互不替代"的直接证据，而非推论；`git checkout` 后工作区逐字节复原、门闩回绿 |
| O1 增强：出包前同代自检 | **已修** | `scripts/pack_evidence.py` 出包前复用 `offline/provenance.py` 的判据自检（同一份实现，不另写一套——两套判据分叉后读者无从判断哪个算数）。分叉时**默认拒绝出包**（挡在任何产物落地之前，不留半个归档目录）；`--allow-stale` 显式放行时档内显著标注 + `MANIFEST.json` 记 `same_generation.ok=false`（CI 从不传该开关） | `test_pack_evidence.py` 16 例全绿（新增 6 例）：分叉→退出 1、`out/` 未创建、stderr 点名分叉项并给出修法；`--allow-stale`→退出 0 且 MANIFEST 含"同代自检：未通过""不可作为「当前能力」引用"与差异明细、JSON 记 `ok=false`；同代→档内记"通过"；未携带结论时 MANIFEST 显式说"未记录"（静默缺席会被读成"查过了"） |
| O1 进 CI（验收从人工一次性→每次复现） | **已修** | `provenance` job 内加两步（**不新增 job**，故 README「五个 job」仍成立）：`Doc numbers ⟷ artifacts gate` + `Evidence pack smoke (clean checkout, mock)`（出包 → 断言三件套存在 → `sha256sum -c` 全 OK → MANIFEST 含同代自检结论） | 本地等价复现：干净形态出包 35 产物、`sha256sum -c` 35/35 OK、MANIFEST 含"同代自检：通过" |
| README 数字改准 | **已修** | `README.md:36`/`:337` 的 121 → **131**，并写明口径是「`@Test` 声明数」（避免"声明 vs 执行"歧义） | `mvn -B test` 实测 `Tests run: 131, Failures: 0, Errors: 0`、BUILD SUCCESS（35s）；surefire XML 汇总 131/0/0/0（25 文件）——声明数=执行数，两种口径同为 131 |
| O4 LICENSE | **已做** | 新增 MIT `LICENSE`（`Copyright (c) 2026 shing26`；与同批 7 仓中 CodeCompass / truetailor 一致）；README 加目录表一行 + §许可一节（含"为何选 MIT 而非 Apache-2.0"） | `ls LICENSE*` 命中；`grep -c $'\r' LICENSE` = 0（LF 落盘，避免跨平台假红）；README 两处指向。**决定**：`LICENSE` 刻意**不纳入**证据快照 REGISTRY——快照收录"可核验的能力声明"，许可是元数据不是声明，纳入只会改变 `CHECKSUMS.sha256` 项数而不增加可核验信息 |

**本轮明确不做（登记为触发线，不新开票）**：`docs/adr/` 0001–0006 与 0011 无 `状态：` 行（体例统一留到下次新增 ADR 时一并做）；ADR-0006 的旧数字「216 条」（ADR 是历史决策快照，不为数字改历史）；O3 外部监控 adapter（触发线已改写为可判读的三组析取项，见 `OPS.md` §5.1）；不新增 ADR——本轮无"难以反转 + 结果意外"的决策，取舍按本仓范式写进模块 docstring（`provenance.py` 即此形态）。

**账外边界入账（2026-09-23 补记）：「复盘 → 知识回灌」**。起因是回答"业务闭环了没有"时逐段核对，发现"真实排障结论沉淀回知识库"这一环**此前不在任何账上**——既非谱系表的"已覆盖"，也不在 `OPS.md` §5.1 的债务表里。事实：知识库是只读的，运行期没有任何「结论→语料」写路径（`IngestionRunner` 只读 `chunks.jsonl` 写派生索引；全仓唯一写 `chunks.jsonl` 的是 `offline/chunkers/build_chunks.py`，吃的是人工撰写的语料源）。处置：按本仓纪律补**可判读**触发线入 `OPS.md` §5.1（① 同一根因的故障被人工复盘 ≥2 次——第二次就该沉淀复用；② 外部监控 adapter 触发后同批处理），并在 README 谱系表 ④ 行明示"知识库只读"这一边界。**它至今未造成问题，只因系统里还没有真实故障流可沉淀**——而"没有到期日的欠账永远不会到期"，故不能靠"暂时无事"留着不登记。

**同批修掉两处门闩自身的缺陷（2026-09-23，由并行改动触发暴露）**：① **回归锁里硬编码了「131」**——用例数因新增 8 例涨到 139 后，变异注入再也命中不了文档，两条变异用例**假红**（而门闩本身是绿的、判据没坏）。"会假红的门闩比没有门闩更快被关掉"，故改为**从产物现算真值**再注入变异，与被测门闩共用同一派生入口（`_derived()`），行号同样现算。② **报错措辞「期望 X、实际 Y」主语有歧义**——本模块作者自己把它读反过一次（把"期望"当成"文档该写的值"），而读反的方向恰好会让人去改**产物**而不是改文档，属最坏的一种误导；改为「**真值 X、文档写的是 Y**」。两处都属"门闩自己也要经得起同一条纪律"，非功能性改动，判据未放宽。

## 修补记录（2026-09-22：B3 出站超时补齐）

> 触发：《项目提升计划-20260921.md》的 B-1——它是全批**唯一一条「不做有实质风险」**的任务：
> 标准不合格线字面要求"**所有**外部调用显式设超时"，而本项目只做到一部分。
> 与 `pm-101` 同族但更重：那条是**超时值过时**（800ms 的 mock 期魔数），这次是**根本没有超时**——
> 无限等待连"可见的静默降级"都做不到，连 pm-101 那条"降级不可怕、看不见降级才可怕"的兜底都没有。

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| B3 出站超时补齐 | **已修** | 实测只有 LLM 一路有超时（`LlmClient` 自带 connect 5s + 请求级 `llm-timeout-seconds`），其余三路全靠底层客户端默认值——`RerankClient` / `RestClientConfig` / `EsConfig` / `EmbeddingClient` 四文件 `grep -iE "timeout\|Duration"` **全部零命中**。修法是**两个配置点覆盖四个调用位**：`RerankClient` 与 `EmbeddingClient` 共用 `RestClientConfig` 建的那个 DashScope `RestClient`（一处覆盖两路），`EsConfig` 覆盖 ES。新增 4 个 env 可覆盖键：`dashscope.connect-timeout-ms`(3000) / `dashscope.read-timeout-ms`(15000) / `es.connect-timeout-ms`(3000) / `es.read-timeout-ms`(10000)，全部 `@Min(1)` | `OutboundTimeoutTest` 6 例 + `OpsPilotPropertiesValidationTest` 从 8 例扩到 10 例（新增两个 0 值负例、正例补 3 个绑定断言）；**mvn 139 全绿**（131 + 8）。**变异对照**：摘掉 `RestClientConfig.settings()` 里的 `.withReadTimeout(...)` 一行 → `OutboundTimeoutTest` 6 例中 **4 例当场红**；恢复后全绿。三项本地门禁同绿：`check_panel_contract.sh`、`doc_numbers.py --check`、`provenance.py --check` |
| 取值口径：socket 兜底必须比业务级预算**松** | **已定** | 检索腿的业务预算是 `retrieval.leg-timeout-ms`（4500ms = embedding 观测 P99 3.7s + 余量）。若 socket 读超时压到同量级，两者会赛跑，最后抛出的是**原始 socket 异常**而不是走业务降级——降级状态机就收不到它该收的信号，`pm-101` 那条"靠 `SearchOutcome` 的 `mode/degraded` 暴露真实执行路径"的可见性兜底会失效。故 15000/10000 是**兜底值不是 SLO**，且刻意留松 | `OutboundTimeoutTest.esSocketTimeoutIsLooserThanTheRetrievalLegBudget` 把这条口径钉成断言（两个 socket 超时都必须 > 4500）；`pm-101` 的防复发教训第 1 条"每个魔数都隐含环境假设，切换后端时逐一重审所有时间/大小/阈值参数"由此落实为**被测试守住的口径**，而不是散文 |
| 验收口径从 grep 升级为断言 | **已改** | 原验收写的是「`grep -i timeout` 在四个文件中应有命中」。那条只证明**写了字**，证明不了**生效**——把 `.withReadTimeout(...)` 删掉只留注释里的 "timeout"，grep 照样绿（变异对照已实测这条）。故把超时对象抽成 `RestClientConfig.settings()` / `EsConfig.applyTimeouts()` 两个静态方法，**生产路径与测试走同一个函数**，断言改为验"配置值 → 请求工厂设置"的传递链 | 变异对照见上行（摘一行即 4 红）就是这条升级的直接证据。**一处诚实记录**：`RerankClient.java` 与 `EmbeddingClient.java` 两个文件自身 `grep timeout` **仍为 0**——超时落在它们共用的客户端上，不在每个调用方重复配一遍；原验收的"四文件各应有命中"因此没有字面满足，改为"四个调用位都被覆盖"，覆盖性由共用 bean 的单一配置点保证 |

**README 数字同步（本轮的连带改动）**：新增 8 条用例使 `@Test` 声明数 131 → **139**，面二门闩当场报红并点名 `README.md:36` 与 `:339`（期望 139 / 实际 131）——这正是 O2 面二设计要抓的形状，**门闩没有被改宽，改的是文档**。同步处：`README.md` 两处数字、`offline/doc_numbers.json` 里 `java_test_declarations` 的 `unit` 说明文案（"同为 131" → "同为 139"）。修后 `doc_numbers.py --check` 报「21 条文档数字与产物一致（29 处引用全部命中且相符）」。

## 修补记录（2026-09-22：B9 覆盖率棘轮）

> **本节的来历**：`8e9bf4c` 落地时未回填台账（违反本文件"每项修复落地后回填"的自订契约），
> 2026-09-23 清理台账时据 commit 原文补记，**非原作者撰写**——事实与数字均取自该 commit 正文。

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| B9 覆盖率棘轮 | **已接**（来源：《项目提升计划-20260921》B-3，照 ShopPilot 已完成的那份做） | jacoco 的 `prepare-agent` + `report` 绑到 **test** 阶段（本项目 CI 跑 `mvn -B test` 而非 `verify`，故 report 不能留在默认的 verify 阶段）→ 产物 `target/site/jacoco/jacoco.xml`；棘轮本身归 `scripts/check_coverage.py`：读产物按 LINE 比门槛并把实测值打出来（比 `jacoco:check` 只给一句失败更有据可引）。CI 里**加步骤不加 job**（产物就在 mvn test 那个 job 里，另起 job 要重跑一遍构建） | 口径：**LINE 设闸、BRANCH 只报不设闸**；门槛 = 首次实测值向下取整再留 1pp。首次实测 **LINE 47.83%（971/2030）/ BRANCH 42.67%（358/839）→ 门槛 46.0**；那 1pp 是抖动余量不是目标值——贴着实测设闸会让合法的防御性分支当场变红，然后这条红就被学会忽略。验证：`python scripts/check_coverage.py` exit 0；**变异对照**把门槛抬到 49.0 → COVERAGE FAIL 且 exit 1，恢复后 exit 0；三项既有门禁复跑全绿（面板契约 / 文档数字 21 条 29 处 / 报告与语料同代），`mvn test` 139 全绿 |
| 边界：**本机量不到覆盖率** | **登记**（不是缺陷，是环境约束） | 本仓目录名 `OpsPilot — AIOps` 含**长破折号**，jacoco 的 `destFile` 会被解析成绝对路径，该路径经 `cmd.exe` 传给 `-javaagent` 时被写坏、代理静默不落盘（日志只有一句 `Skipping JaCoCo execution due to missing execution data file`） | 对照矩阵已做全：同一个代理同一条命令，**相对路径写得出、ASCII 绝对路径写得出、含长破折号的绝对路径写不出且文件未落到别处**。故 47.83% 是把仓库复制到 ASCII 路径（`D:/OpsPilotAscii`，已删）跑出来的；CI runner 路径本就是 ASCII，不受影响。**本机要常态量覆盖率需把目录改成 ASCII 名**；相对路径配置试过无效（jacoco 内部仍解析成绝对），故未留在 pom 里。另：`jacoco-maven-plugin` 版本显式钉在 **0.8.15**——`spring-boot-starter-parent` 的 `pluginManagement` 不管理它，不钉会报 `plugin.version is missing` 警告且版本随 Maven 默认解析漂移 |

## 修补记录（2026-09-24：承诺线 · 降级代价语义 · 分段耗时 · 代价量化 · 闭环前置）

**本轮的出发点**：对六个维度（需求-架构匹配 / 任务规划 / 上下文工程 / 可观测与评估 / 人机协同 /
业务闭环）做了一轮评估，逐项定出"该做"与"不该做"。本轮的九项全部来自"该做"那一列——性质是
**把已有的东西变成可被质证的数字**，不是加深项目。评审时另查实两处**宣称与实现分叉**（见下表）。

| 项 | 状态 | 依据 | 验收证据 |
|---|---|---|---|
| **宣称对账：撤「缩减 Prompt」** | **已修**（查实的缺陷） | `CONTEXT.md` 与 `README.md` 都把 L1 写成「纯 ES + 缩减 Prompt」，但 `ChatOrchestrator.runPipeline` 的 L1 分支只切 `mode="es_only"`，prompt 仍走同一个 `PromptAssembler.build` ——**该能力从未实现**，全仓 grep「缩减」只有那两处文档 | `grep -rn 缩减 README.md CONTEXT.md` 零命中；措辞改为 L1 实际做的事（摘向量路 + 摘 Rerank） |
| **降级代价语义 + 同构锁** | **已登记** | 复核时查出第二处未登记的事实：`HybridSearchService` 在 `noRerank`（含 L1）时把 `topRelevance` 置 1.0，而门控判据是 `!fastPath && topRelevance < minRel` ⇒ **L1 期相关性门控实际失效、只剩零召回拒答**。代码注释承认有意为之，但该后果从未作为"降级代价"进任何文档 | [ADR-0012](../../docs/adr/0012-degradation-cost-semantics.md)（含三条否决项与重开触发线）；OPS §5 补触发线行；`ChatOrchestratorTest.degradedL1SearchIsIsomorphicToEsOnlyMode`——**变异对照**：L1 分支 mode 由 `es_only` 改 `hybrid` → 该用例当场红，还原复绿 |
| **承诺线 SLI/SLO** | **已建** | 此前有真计数面板、有实测指标表，但没有一处写明"承诺线"与"多久核一次、怎么核" | `OPS.md` §11：七条 SLI + 核色频次；其中可派生者入门闩（`doc_numbers.json` 新增 `jailbreak_leaked`，并给两条命中率加 OPS 引用位；21 条/29 处 → 22 条/32 处）。**变异对照**：把 §11 的「权限泄漏率」实测值 `**0**` 改成 `**1**` → 门闩当场红并点名 `OPS.md:253`，还原复绿 |
| **防幻觉事后环（答案接地 V9）** | **已建**（live 数字待补） | 在线三道闸门全在**事前**（门控拒答）或**通用文本层**（逐字导出护栏）⇒ 能保证"没证据就不答"，不能保证"答了的都有据" | `offline/grounding.py`（纯函数，词法复用 `chunkers/errorcode.py` 不另立副本）+ 探针 V9（复用 V2/V4 答案，零额外 chat 预算）。**变异对照三路**：判据改恒空 → 2 例红；就地编译同串正则副本 → 1 例红；漂移副本 → 7 例红 |
| **分段耗时（G2）** | **已建** | 此前 `SearchOutcome` 只有 `tookMs` 一个总数——一个 17.5s 的请求答不出时间花在哪 | `retrieval/LegTimings` + `metrics/StageTimings`（八段）落 chat 审计行 `stage_ms`；**落审计行而非 SSE 帧**（SSE 是对外协议面，内部耗时不该泄到那里）。**变异对照**：摘掉 `supply(...)` 的 finally 计时段 → 腿耗时断言当场红（`LegTimings[esMs=0, vectorMs=0, ...]`），还原复绿。**顺带查实一处注释失实**：L2 写入处原注释写"复用检索时已算好的向量"，实际又调了一次 `embedding.embedOne` ——同请求 embed 两次，注释已改如实，成本由 `stage_ms.l2_store` 显式化 |
| **门控混淆矩阵（D1/D2）** | **脚本已建**（live 数字待补） | 门控此前只有 `low_confidence_refusals` 一个计数，答"拒了多少次"不答"拒对了吗"；误拒（伤可用性）与漏拒（伤可信度）两类都不在计数里 | `offline/eval/gate_matrix.py` + `refuse_set.jsonl`（24 条：12 幽灵错误码 + 12 域外提问，**在 corpus 之外**）。**关键简化**：门控信号 `topRelevance` 在正常路径就等于 `results[0].rerank_score`，而 `/search` 已返回它 ⇒ 一次遍历算完**任意**阈值（无需重启网关）；且 `/search` 不查 L1/L2，排除"缓存回放绕过门控"的混淆。**变异对照**：判据的严格小于改小于等于 → 边界用例红。拒答集的 premise（幽灵码确实不在语料里）由测试**现算语料错误码集合求交**锁死 |
| **并发曲线（F1）** | **脚本已建**（live 数字待补） | 此前只有 50 与 500 两个孤立档位，中间 100/200/300 空白 ⇒「系统极限在哪」答不出来 | `offline/load/sweep.py` 扫六档出 P50/P95/P99 + 失败率 + RPS 并标出**首次 L1 触发档**；降级档位轮询取运行期间最高档（瞬时采样漏脉冲）；每档前预热。原始 locust 产物入 `.gitignore`，只入汇总报告。**变异对照**：解析由"取 Aggregated 行"改成"取第一行" → 2 例红 |
| **缓存节省账（E1）** | **脚本已建**（live 数字待补） | 有 `l1/l2_cache_hits` 计数，但没有"命中值多少" | `offline/load/cache_savings.py`：受控混合负载 → 命中/未命中延迟差 × 命中率。归因靠本脚本自己的负载（`hits/total_requests` 的分母含 Single-Flight follower，是浑的）；单侧无样本时不算节省（不编数字） |
| **答案反馈入口（闭环前置）** | **已建** | 在此之前人**无法**把"答错了"告诉系统——答案对错的唯一人工信号只存在于验收期，而"复盘→知识回灌"（OPS §5.1 在案债务）正缺这个入口 | `POST /api/v1/copilot/feedback` + 审计辅助写入器 `ev="feedback"`（仿 `logAdmin` 先例，零改动 13 参 `log()`）。按 fingerprint 归档（标识"问题"而非"某次生成"）、不占配额（治理信号非成本）。三态锁：未认证/密级<1 → 403 且零审计；未知 fingerprint 接受而非拒绝 |
| **真实输入探测（G4）** | **脚本已建**（live 数字待补） | 语料/query/评测集全部自产 ⇒ 64%/88% 是在合成输入上测的，系统至今没接触过它无法预测的输入 | `offline/eval/observed_probe.py`：**真实 query 派生不出真值**，故只测三件无需真值的事（零召回率 / 快路径命中率 / 门控拒答率），**不冒充**召回质量。query 集不入库（`logs/` 含查询内容与租户标识），报告只含聚合——该约束由测试做成**结构性保证**（聚合返回里出现任何字符串即红） |

**本轮明确不做**（带理由，防止下一轮重开）：不修 L1 门控降级（RRF 分数无量纲，照搬 0.2 是拍脑袋拒答，已按 ADR-0012 登记 + 立重开触发线）；不加并发限流器（与"过载时降级而非拒绝"打架）；不做任务规划/多步 Agent 循环（无此任务形态，会摧毁定位叙事）；不加 HITL 审批（只读系统没有高危动作可批，属空转）；不引入 Prometheus/OTel（触发线未到）；**不新增 `OpsMetrics` 计数器**（避开面板四层契约面，反馈的可观测性由审计流承担）；不把真实 query 集入库。

**待补**：四份 live 报告（gate_matrix / concurrency_sweep / cache_savings / observed_probe）与探针 V9 的 live 断言。原因：本机 8081 被非 OpsPilot 的 python 进程占用，而 `localapi.BASE` 硬编码 8081、无 env 覆盖口。脚本与纯函数单测已全部落地并过门禁，**未伪造任何数字**。

**门禁终态**：`mvn test` **150 全绿** ｜ `pytest` **112 全绿**（本轮 +23）｜ `provenance --check` OK ｜ `doc_numbers --check` 22 条/32 处 OK ｜ 面板契约四层一致。本轮**未触碰面板契约面**（未新增计数器），契约复跑绿即证明未误伤。

## 修补记录（2026-09-24 续：补跑 live 报告——两项出数、两项被上游账户阻断）

**目标**：把上一节登记的"四份 live 报告待补"真跑出来。**结果：2 份拿到真数字，2 份被上游账户问题阻断（未伪造）**。

### 起栈（宿主裸进程，避开 8081 冲突）

本机 8081 被一个与本项目无关的 python 进程占用，且 `offline/localapi.py` 的 `BASE` 当时**硬编码** 8081——即仓内已登记的那处不对称（`console_client` 早有 `OPSPILOT_BASE`，`localapi` 没有），它使"端口被占"直接等于"整条 live 验证链不可做"。本轮按 grill 裁定**加覆盖口而不动那个未知进程**：

| 步骤 | 做法 | 实测结果 |
|---|---|---|
| 加环境覆盖口 | `localapi._resolve_base(env)` 纯函数 + `BASE = _resolve_base(os.environ)`；**`assert_local` 一行未动**（仍是唯一执行点：scheme/环回白名单/解析后 IP/拒 userinfo，故覆盖口不削弱 SSRF 防线） | `OPSPILOT_BASE=http://localhost:8099` 实测生效；非法主机仍被拒（有测试锁） |
| 端口 | `SERVER_PORT=8099`（8099 空闲） | 启动日志 `quota=100000/day`，`/actuator/health` 200 |
| 依赖 | 宿主侧 ES 401 @83ms ⇒ Docker 端口代理**健康**，无需 `OPS.md` §10 的 restart 顺序 | redis `reachable` / qdrant 423 pts / es 423 docs，全 UP |
| 账号库 | `.env` **无** `H2_DB_PASSWORD`（`application.yml` 默认空）——曾担心打不开 | `user_admin.sh list` 正常：4 账号齐、enabled、库完好，无需密码 |
| 配额 | 六档合计约 2 万请求 > 默认 5000/天 ⇒ `OPSPILOT_QUOTA_DAILY_LIMIT=100000` 启动，并要求 `sweep.py` 把当时配额值写进报告 | `sweep.py` 已加 `quota_limit` 字段与口径注 |
| 索引 | 未带 `--opspilot.ingest` | ES `opspilot-chunks-20260916205334` 423 docs；Qdrant `opspilot-vectors-20260916205334` 存在 |

### 已出数的两份（都只走 `/search`，不触 LLM）

| 报告 | 关键数字 | 解读 |
|---|---|---|
| `offline/eval/reports/gate_matrix.{json,md}` | 阈值 0.1/0.2/0.3/0.4 下：误拒率 **0.0% / 6.8% / 16.9% / 23.7%**；漏拒率 **58.3% / 8.3% / 0.0% / 0.0%**；各档零召回误拒均为 **0** | **当前阈值 0.2 正好在拐点上**：从 0.1 抬到 0.2，漏拒率从 58.3% 骤降到 8.3% 而误拒率只到 6.8%；再抬到 0.3 只多换来 8.3% 的漏拒改善，代价是误拒率翻 2.5 倍。零召回误拒恒为 0 说明那 4 条误拒是**真的低相关 golden query**，不是检索失败 |
| `offline/eval/reports/observed_probe.{json,md}` | 真实 query 100 条（95 人工 + 5 自举告警）：零召回率 **0.0%**、快路径命中率 **95.7%**（45/47）、门控拒答率 **20.0%** | 真实输入上检索从不空手而归；含精确错误码的真实 query 里 95.7% 走通快路径（即"压 TTFT"的设计在真实流量上确实生效）。门控拒答率 20% 高于 golden 集的误拒率 6.8%，但两者口径不同（真实样本含无真值的噪声 query，**不能**读成"误拒 20%"） |

**口径提示**：`observed_probe` 的数字**不得并入** README 的 64%/88%——那是合成集，这是真实输入，两者不同源。

### 两项被阻断（附实证，非推断）

| 报告 | 状态 | 阻断原因（实证） |
|---|---|---|
| `concurrency_sweep`（六档并发曲线） | **未出数** | 见下"上游账户" |
| `cache_savings`（缓存节省账） | **未出数**（首跑产物已**丢弃**） | 首跑得到 `命中率 0.0% / 全 26 条 cache_hit=none @ ~106ms`，看似"缓存无效"。查计数发现真相：`dedup_aggregated=20`、`sop_fallbacks=26`——26 条全部走了 **L2 SOP 兜底**（不写 L1，设计如此），故那个 0% 是**降级兜底的产物、不是缓存的性质**。该报告已删除，未入库 |
| 探针 V9 live 断言 | **未跑** | V9 复用 V2/V4 的生成答案，而生成面全断 ⇒ 会得到"无可用答案素材"的 FAIL，属误导性记录，故不跑 |

**上游账户问题的实证链**（不是猜的）：① 网关日志 `stream error: LlmHttpException: LLM HTTP 400` ×3 → 连续失败达阈 3 → 熔断 L2 60s（`sop_fallbacks=26`）；② 直接对 DashScope 打一发最小 chat 请求，返回 `{"error":{"message":"Access denied, please make sure your account is in good standing...","code":"Arrearage"}}`——**账户欠费**；③ 而 embedding/rerank 仍可用：同一时段 384 条 `/search` 审计行里 **383 条 mode=hybrid**（仅 1 条 degraded），故**检索面健康、生成面全断**。这与 2026-09-13 那次是同一环境问题。

**结论**：机器、栈、端口、配额、账号库、索引全部就绪且已验证；**唯一卡点是 DashScope 账户欠费**。充值后两条命令即可补齐：`python load/sweep.py`、`python load/cache_savings.py`（外加 `qa_gen_quality_probes.py V2 V4 V9`）。

### 途中修掉的本轮自身缺陷（5 处，均已加锁）

| # | 缺陷 | 后果 | 修法与锁 |
|---|---|---|---|
| 1 | `observed_probe.py` / `sweep.py` 的 `--user` 默认值是 `sre-l3` | `sre-l3` 只是 `load_tokens` 的**键名**，不是真实用户名（真名 `sre-full`）⇒ 照默认跑必然 401，且 5 次失败会**锁住账号 15 分钟** | 引入单一常量 `localapi.DEFAULT_EVAL_USER`，四个测量脚本统一引用；测试**现算 `seed_demo_users.sh` 的播种集合**断言常量在其中，另加 AST 锁禁止硬编码字面量。**变异**：还原成 `sre-l3` → 2 例红；某脚本写回字面量 → AST 锁红 |
| 2 | 两处 docstring 称"每次 `/search` 消耗 1 次当日配额" | 实际 `/search` **配额豁免**（配额只挂 `/chat/stream` 与 `/v1/chat/completions`）——宣称失实 | 改为"零 token 成本且配额豁免" |
| 3 | `observed_probe` 只读 `logs/audit.jsonl` | logback **按天滚动**，当前那份只含今天 ⇒ 每次滚动后样本凭空缩小，最后缩成"本轮自己跑的测试 query"（自指） | 改为扫全部分片；**变异**：去掉逆序 → 顺序测试红 |
| 4 | 分片扫描是**字典序升序**（最老在前） | `audit.2026-09-10` 字典序小于 `audit.jsonl`，故从**最老**分片取样——那些分片的语料早被 blue/green 换掉。首跑因此拿的是 09-10 时代的 query（快路径命中率 83.6%），改后为 95.7%，**数字确实变了** | 改逆序（新→旧，当前文件在最前） |
| 5 | 报告只列**候选**分片 | 会让人以为 8 片全都参与了取样 | 每条样本记 `shard`，报告改列**实际贡献**分片（本轮落在 09-11～09-17 五片） |

另有一处**非本轮引入**但造成实质卡点的：`--include-alerts` 去读 `logs/alert-producer.jsonl`，而那份记的是告警元数据、**没有 query 字段**⇒ 该 flag 空转（"宣称了却不生效"）。已改为在审计流里优先取 `source=alert` 的样本（告警 query 本就在审计里，09-17 分片有 513 条），并加测试锁"该 flag 确实会改变取样"。**变异**：把 flag 变成空转 → 测试红。

### 门禁终态

`pytest` **123 全绿**（本轮 112 → 123，+11：`test_localapi.py` 6 例 + `test_observed_probe.py` 增 5 例）｜ `provenance --check` OK ｜ `doc_numbers --check` 22 条/32 处 OK ｜ 面板契约四层一致 ｜ Java 未改动（150 全绿不变）。本轮新增两条 live 报告入库，另加两处代码修复与五处自身缺陷修复。

## 修补记录（2026-09-25：欠费解除后补跑剩余三项 + 核色前置脚本）

**转折**：上一节记录的四项里有两项（+探针 V9）被 DashScope 欠费阻断。此后重新探活发现**三路全部恢复 200**（欠费解除），故把剩余三项补跑完成——**四份报告现已全部出数**。

### 补跑结果（全部 live，三路探活通过后开跑）

| 报告 | 关键数字 | 解读 |
|---|---|---|
| `offline/load/reports/cache_savings.{json,md}` | 命中率 **84.0%**（L1 20 / L2 1 / miss 4）；命中 p50 **8ms**、未命中 p50 **833ms**；**单请求平均节省 693ms** | 交叉核对显示 `llm_calls=4`、`low_confidence_refusals=0` ⇒ 未命中组是**真生成**（此前首版那条 528ms 的"节省"是拿**拒答**当基线算的，见下"自身缺陷"） |
| `concurrency_sweep.{json,md}` | 六档：25/50/100/200 全程 **0 失败**、RPS 峰值 **282**（u=200）；u=300 失败率 **99.5%**、u=500 **91.9%**；失败全部是 `HTTP 0`（**连接层**，无一条 5xx） | **拐点在 200→300 之间**。吞吐在 ~230–282 rps 平台化 |
| 探针 V9 live 断言 | **11 条答案 / 15 个错误码 / 无据 0 个（接地率 100%）**；V2 5/5、V4 2/2，合计 9/9 PASS | A1（幻觉事后环）至此真正闭环：清单 A1 要求的"每次 live 回归强制断言的幻觉率"有了实测值 0 |

### 本轮最重要的发现：降级状态机在压测中**从未有机会触发**

`sweep.py` 记录了每档的**在途峰值**（`/admin/state` 的 `runtime.inflight`），六档分别是 **4 / 9 / 11 / 11 / 0 / 11**——而 L1 的触发条件是**在途 ≥ 40**。同时各档 `degradation` 全程 `L0`、失败**全部**是连接层 `HTTP 0`（无应用 5xx）。

由此得到两条必须一起读的结论：
1. **这份曲线刻画的是连接层、不是应用自身容量。** 连接在 200→300 之间先垮，其压力从未传递到应用的在途计数上。
2. **"过载时降级而非拒绝"这条核心主张，在本机这一栈上没有机会生效**——不是它失效，而是本机栈先以另一种方式失败了。这与 README 里"500 并发同指纹 → LLM 仅 1 次"（风暴场景，全量去重、请求极快）并不矛盾，但**两者测的是不同的东西**，引用时不可混用。

**未收敛**：连接层失败的具体成因未隔离（候选：本机 Windows 回环/TIME_WAIT 端口耗尽、Tomcat accept 队列）。要定论需 Linux 运行或调高 Tomcat 连接上限后复测。**在途阈值 40 未由 `/admin/state` 暴露**，故脚本不臆造它、改以"在途峰值"直接对照（早先把 `degradation.threshold`(=LLM 熔断失败阈值 3) 标成"inflight 阈值"是错的，已修正）。

### 核色前置脚本（把"别在降级态上核色"变成机制）

新增 `scripts/check_upstream.py` + `offline/tests/test_check_upstream.py`（9 例，假 opener 注入不触网）。三路（LLM / embedding / rerank）全通过才 exit 0；任一路被拒即非 0 退出并**点名到 `code=Arrearage` 这类具体 code**，而不是只报"HTTP 400"。已写进 `OPS.md` §11 作为核色第一步，并在 `README` 脚本一览登记。**为什么必须机制化**：降级会静默掩盖上游故障（embedding 断→检索退 `es_only`；LLM 断→熔断直出 SOP，两者 HTTP 均 200，只有 `mode` 字段才看得出），人眼不会注意到。**变异验证**：`all_ok` 放水成 `any(...)` → 2 例红。

### 本轮修掉的自身缺陷（3 处，均已加锁）

| # | 缺陷 | 后果 | 修法与锁 |
|---|---|---|---|
| 1 | `cache_savings` 的冷 query 用的是**无错误码的泛化问句** | 5 条冷 query 全被置信度门控拒掉（实测 `low_confidence_refusals=5`、`llm_calls=0`），于是"单请求平均节省 528ms"是拿**拒答**当基线算的——拒答本身很便宜，该数字显著低估真实节省 | 冷 query 改用含**精确错误码**的问句（快路径命中 → 豁免门控 → 真调 LLM）；并把 `low_confidence_refusals` 纳入交叉核对，使"未命中组是不是真生成"**可被读者自行验证**。重跑后 `llm_calls=4`、refusals=0、节省 693ms |
| 2 | `sweep.py` 用固定 `-r 10` + 30s | **并发上限 = ramp × 时长 = 300**，于是"u=500"那档实际只跑到 **290**，报告标签与事实不符（且首跑因此拿到的"u=500 零失败"是假象） | 改为按 `档位 / --ramp-seconds` 自适应 ramp；并逐档记录**实到并发**（读 `_stats_history.csv` 的 User Count），未达标打 ⚠️。**变异**：去掉自适应 → 测试红 |
| 3 | `sweep.py` 用 `subprocess.run(check=True)` | locust 默认在有**任何失败**时以 1 退出，而本脚本测的就是失败率——首跑把"u=200 出现连接层失败"当致命错，**整个 sweep 崩在那档、连拐点数据一起丢掉** | 加 `--exit-code-on-error 0` + `check=False`，改为"stats.csv 不存在才报错"（真崩仍能定位）。**变异**：去掉该 flag → 测试红 |

### 门禁终态

`pytest` **123 → 139 全绿**（本轮 +16：`test_check_upstream.py` 新增 9 例、`test_sweep.py` 由 5 例增至 12 例）｜ `provenance --check` OK ｜ `doc_numbers --check` 22 条/32 处 OK ｜ 面板契约四层一致 ｜ Java 未改动（150）。

## 修补记录（2026-09-25 续：清理上一节自曝的缺陷）

上一节末尾列了 6 条"卡点或缺陷"。本轮逐条处置：**4 条修掉、1 条登记触发线、1 条被环境阻断**。

| # | 项 | 处置 | 依据 / 证据 |
|---|---|---|---|
| 1 | 台账「现行状态」中「防幻觉事后环 V9」仍写 *live 数字待补* | **已修** | V9 的 live 断言已于本轮实测通过（接地率 100%），该行改为「已建，且 live 已验」并写入实测值。这是该表**第三次**出现同类过期（前两次：README「121 用例」实测 131；「检索面正常」实为三路全断）——每次都是**事实已变、表未跟**，故该表只写"已做到什么"、不写易漂数值的纪律需要人工纪律兜底 |
| 2 | `/admin/state` 未暴露 inflight 阈值（40），只暴露了一个笼统的 `threshold` | **已修**（改名 + 补键 + 上面板） | 键名分开：`llm_failure_threshold`（3，L2 触发）+ `inflight_threshold`（40，L1 触发）；面板降级块新增「在途 X/40」一灯（此前运维看不到自己离 L1 还有多远）。**影响面 6 处**（Java 装配 / 面板 / 契约脚本字面量 / AdminControllerTest / `alert_producer.py` / `sweep.py`），逐处同步；`sweep.py` 因此可直接引用真阈值而非手写注释 |
| 3 | 「过载时降级而非拒绝」在实测负载范围内从未被行使 | **已修**（把边界写进 README） | `README.md` 降级段新增一段：六档压测下在途峰值最高 11 « 触发线 40 ⇒ 降级全程未触发；u≥300 的失败全是连接层 `HTTP 0`。并明确「500 并发同指纹 → LLM 1 次」是**风暴场景**的数、与这条曲线**不可互相推广**。成因未收敛如实标注 |
| 6 | L2 写入重复 embed | **登记触发线**（不现在修） | 写入 `OPS.md` §5：触发线 = ①embedding 调用成本成瓶颈，或 ②`stage_ms.l2_store` 占比 >10%。**并区分两件事**：原注释"复用检索时已算好的向量"是**宣称失实**（真缺陷，已于上一轮改为如实描述）；重复 embed 本身是**已知开销**（实测 232ms / 10.2s），修法要向 Qdrant 腿返回面回传向量、接口面有改动，按纪律挂起不预防施工 |
| 4 | 反馈端点只有单测、无 live 实测 | **被阻断**（未修） | 需要活体栈，而本轮期间 **Docker Desktop 引擎退出**（`docker` API 报 daemon not running、9200/6337/6333/6334 全部不再 LISTENING；非本项目所为，我只停过自己起的 Java 网关）。Docker 恢复后一条命令即可补：`set -a; . ./.env; set +a; export OPSPILOT_BASE=…; python -c "import localapi; localapi.post_json('/api/v1/copilot/feedback', {...}, token)"`（三态：未认证 403／合法 200／未知 fp 200） |
| 5 | 9 笔未推、本轮从未过 CI | **待你决定** | 推送是对外动作，未经指示不做。代码面已自查 CI 可行性（新测试读的是**已入库**的 `seed_demo_users.sh` 与 `locust_a_stats.csv`、`check_upstream` 用假 opener 不触网），但**未验证**——本项目有「本机绿≠CI 绿」前科（2026-09-16） |

**一次差点误报的自我纠错**（值得记）：我用 `bash scripts/x.sh | tail -3; echo $?` 读面板契约的退出码，得到 0，一度以为"该门闩能打印 FAIL 却在 CI 里永远绿"。实际是 **`$?` 取的是管道末端 `tail` 的退出码**，不是脚本的；脚本末尾本就有 `exit $fail`。改用 `> file 2>&1; echo $?` 复测得 **1**，确认门闩有效。**教训：管道之后的 `$?` 不是被管道命令的退出码**——门闩自身的"是否会红"必须用不经过管道的方式验证。

**门禁终态**：`mvn test` **150 全绿**（含改后的 `AdminControllerTest` 断言两个阈值）｜ `pytest` **139 全绿** ｜ `provenance --check` OK ｜ `doc_numbers --check` 22 条/32 处 OK ｜ 面板契约四层一致（**变异验证**：删掉面板的 `inflight_threshold` 消费点 → 契约 FAIL 且 exit 1，已还原）。

## 修补记录（2026-09-27：Docker 恢复后补测被阻断项 + 一处新发现的口径缺口）

**背景**：上一节末尾第 4 条"反馈端点只有单测、无 live 实测"被 Docker 引擎退出阻断。用户重启 Docker Desktop 后，中间件（`opspilot-redis` healthy / `opspilot-qdrant` / `opspilot-es` healthy）恢复，本轮把它与另外两处**只经单测、未经 live** 的改动一并补验。

### 补测结果（三处改动从"单测绿"升级为"live 已验"）

| 项 | live 实测 | 备注 |
|---|---|---|
| **反馈端点四态** | 未认证 → **401** ｜ 合法 fp → **200 `{"ok":true}`** 且审计行落盘（`ev=feedback` + sub/tenant/level/fp/verdict/note）｜ **未知 fp → 200**（接受而非拒绝，设计如此）｜ 非法 verdict（`maybe`）→ **400**（DTO `@Pattern` 经 GlobalExceptionHandler） | fp 取自**真实审计行**（32 位十六进制），不是造的 |
| **`/state` 两个阈值键**（上一轮的改名+补键） | `degradation = {"level":"L0","inflight_threshold":40,"manual":false,"cooldown_s":0,"llm_failure_threshold":3,"failures":0}` —— 两键在场、**旧的笼统 `threshold` 已消失** | 单位断言写在调用脚本里（不是肉眼看） |
| 核色前置 | `scripts/check_upstream.py` 三路全 PASS、exit 0 | 起栈第一步 |

### 新发现并修掉的口径缺口：指纹的**长度与分隔符**从未写明

补测时取真实 fp 做反馈，拿到的是 **32 位**十六进制（`823ac0a0459ce42e80c9e9279b3b4a1e`），而 `CONTEXT.md` 与 `FingerprintService` 类注释都只写 `SHA256(service + env + normalized_error_msg)` ⇒ 一个按文档对照的人会以为应该看到 64 位。查实现：`HexFormat.of().formatHex(d).substring(0, 32)`，即 **SHA-256 摘要取前 128 位**；且拼接用的是 `|` 分隔符，文档同样未提。

**为什么这算缺陷而不是吹毛求疵**：指纹是**对外形状契约**——它同时是 L1 缓存键的组分、SSE `meta` 帧的回传值、以及反馈端点归档"问题"的键。长度或取值漂移**不报错**，只是让缓存命中与已归档反馈静默对不上。

处置：
- `CONTEXT.md` 词条与 `FingerprintService` 类注释补上**分隔符与截断**（128 位 / 32 位十六进制）。
- 新增两条锁（`FingerprintServiceTest`）：`fingerprintShapeIsLockedToThirtyTwoLowercaseHex`（长度 32 + 小写十六进制 + 可重放）与 `serviceAndEnvBoundariesAreNotCollapsible`（`|` 必须参与摘要，否则 service/env 边界可被拼接抹平）。**变异验证**：去掉 `substring(0, 32)` → 形状锁当场红（`expected: <32> but was: <64>`，并打印出实际 64 位值）。
- 关于"截断到 128 位是有意设计"——**仓内查无决策记录**（`grep` 全仓只有实现那一行；该行自首个 Sprint 1-3 commit 就在）。故词条只陈述事实并标注"无决策记录"，**不替它补一个理由**。参考量级：128 位碰撞界 ~2^64，对告警去重足够。

### 一次自我纠错（同一段落内）

上面那条词条我第一版写的是「**32 位而不是 64 位是刻意的**（碰撞界 ~2^64，去重绰绰有余）」——**"刻意的"是我推断的，没有任何依据**。写完立刻去核（全仓 grep + `git log -S`），发现只有实现、无 ADR/注释/文档，于是改回只陈述事实。这与本项目一路在修的"宣称与实现分叉"是同一类错误，只是这次是我自己刚犯的：**不能把"看起来合理"写成"当初就是这么决定的"**。

### 门禁终态

`mvn test` **150 → 152 全绿**（+2 指纹锁）｜ `pytest` **139 全绿** ｜ `provenance --check` OK ｜ `doc_numbers --check` 22 条/32 处 OK（**本轮它又抓出一次用例数漂移 150→152**，已按产物真值改 README）｜ 面板契约四层一致。

### 收口：推送与 CI 首验（2026-09-27）

上一节末尾把"11 笔未推 ⇒ 本轮从未过 CI"记为唯一剩余开项。已按 OPS §6 三道门闩先检后推：

| §6 门闩 | 结果 |
|---|---|
| 全历史 JWT 形扫描（`git grep eyJhbGciOi... $(git rev-list --all)`） | **空输出**（通过） |
| 待推 diff 的凭据形状扫描（JWT / `sk-` / `AKIA` / PEM 头） | **0 命中** |
| `.env` 实际值是否泄漏进待推 diff（逐个值比对，只报键名不打印值） | 8 个值**零命中** |

**推送路径（值得记，因为不是默认那条）**：`github.com` 直连与走本机代理（端口 31180/31181 **仍在监听**）**都不可达**，HTTPS push 返回 500；而 `example.com`、`api.github.com` 均 200 ⇒ 不是断网，是**该域不可达**。`~/.ssh/config` 已把 `github.com` 映射到 `ssh.github.com:443`，SSH 认证通过（`Hi shing26!`），故用**会话级 URL 重写**推送、不动持久配置：

```bash
git -c url."git@github.com:".insteadOf="https://github.com/" push origin main
```

**结果**：`49db4ed..c7e64d3` 推送成功，本地与 origin 同步。CI run **36318228339 —— success**，五 job 全绿：`panel-contract` 5s ｜ `provenance` 7s ｜ `python` 14s ｜ `java` 33s ｜ `shell` 4s。注解只有早已在案的 Node20 弃用提醒（非失败）。

**这条为什么重要**：本项目有「本机绿≠CI 绿」前科（2026-09-16：单测真发 HTTP，本机有网关故绿、CI 无网关即红）。本轮新增 16 个 Python 测试 + 2 个 Java 测试并改了 /state 契约，**从未在 CI 跑过**——现在跑了，首验即过，没有出现"本机绿≠CI 绿"。


---

## 修补记录（2026-09-28：批次一 · 让"发生过的降级"可复核）

来源：《OpsPilot 完整系统化改进计划-20260928》批次一（零活体依赖项，全部门闩本机绿；CI 待推送后复验）。
定位同轮定为 [ADR-0013](../../docs/adr/0013-trust-base-for-agents-not-an-agent.md)（agent 的可信底座）。

### 四项改动

| 项 | 改了什么 | 为什么非改不可 |
|---|---|---|
| **OP-A5** | 审计业务行增 `degrade_level` 列；档位每次变化落 `ev=degrade_transition`（`from`/`to`/`cause`）；状态机在计数器变化处（enter/exit/llmFailure/llmSuccess/manual*）与 `current()` 观察转移 | 复核时实测出一个**会自证的假证据**：`mode=es_only` 有两个来源（L1 降级 / 检索腿超时后 `effectiveMode`），单看它把"检索腿挂了"读成"降级状态机生效"——而那正是本项目最想证明的事。另：压测停止后负载回落那一瞬已无新请求，若只在 `current()` 观察，录屏里的"回落 L0"会永久丢失 |
| **OP-A7** | 调用方关联键 `X-Trace-Id`（8–64 位词表，空白=未提供，非法即 400）+ MDC + 审计行 `trace_id`；`/v1` 请求体零改动 | 服务端自生的 `request_id` 客户端拿不到（`FeedbackRequest` 注释早已自陈），既有 `fingerprint` 是**内容派生**的"问题身份"——被测 agent 分三步问不同错误码时会散成三个指纹，串不成一次 incident |
| **OP-R3** | L2 语义缓存写入改投递即返回（原 `.get(5, SECONDS)` 在答案已下发后同步等） | 那是纯缓存写入的等待，却计入用户可见的端到端时延；并经测试确认写的是**专属缓存集合**而非检索主集合 |
| **OP-R5（零成本判别）** | `HybridSearchServiceTest` 补"两腿不相交"用例 | 既有两条 hybrid 用例的 qdrant 桩都返回空列表 ⇒ 服务层**从未**证明过"向量腿的 chunk 进了 fused→Rerank 池"。而评测报告里 `hybrid` 与 `vector_only` 读数逐位相同，"融合生效但没改变 top-3"与"融合根本没接线"在指标层不可分——本条把后一种解释当场排除 |

### 变异验证（改坏必红，全部实测）

| 变异 | 结果 |
|---|---|
| 摘掉 `exit()` 里的转移观察 | `DegradationTransitionTest` 红，且缺的正是 `L1→L0(load_subsided)` 那一条 |
| `X-Trace-Id` 校验正则放宽为 `.*` | `RequestIdFilterTest` 3 处红（400 → 200），含长度/字符集边界矩阵 |
| 把 `refuse_set.jsonl` 的 24 改成 25 | 面二门闩红并点名 `production-readiness:236` 真值 24 |

### 同代门闩补的两处空洞（OP-R6）

- `refuse_set.jsonl`（24 条）此前**未登记**于 `doc_numbers.json`，而本文档正文引用它 → 已登记 `refuse_samples`。
- `gate_matrix.{md,json}` 与 `observed_probe.{md,json}` **不在** `scripts/pack_evidence.py` 的 REGISTRY，而本文档称"代价量化四脚本全部出数" → 已补四条（前提档 `KEY`）。

### 第 5 处（CI 复验时查出）：证据包把机器本地隐藏文件当成证据

推送后比对两侧读数时发现：**同一提交**本机证据包 **47 产物**、CI 干净检出 **46 产物**。差的那一个是
`docs/adr/.mimosa/hook-status/sess_*.json`——本工作区 Mimosa 安全钩子的状态文件，落在被登记的
`docs/adr/` 目录里，于是被目录项展开一并扫进了归档。

它之所以躲过此前所有复核：`.mimosa/` 在 `.gitignore:34` 里 ⇒ `git status` 干净、人工复核也看不见。
而归档的用途恰恰是"给第三方在干净检出里复核"——多出来那条既核不了、又让两侧数字对不上，
**"干净检出可复核"这条存在理由被打破**。

修法：目录项展开时跳过**隐藏段**（任一路径段以 `.` 开头），并把跳过项**显式登记**进 MANIFEST §2
（静默少一条与"没生成"长得一样，本档纪律禁静默空洞）；显式登记的单文件不受此规则约束
（`logs/.alert-producer.lock` 是刻意登记的）。**变异验证**：去掉 `_hidden` 过滤 → 新增用例必红。
修后本机亦为 46 产物、跳过 4 项（3 项本机独有 + 1 项隐藏）。

### 一处自我纠错

改面板表头时先写成"ev 六类"（凭印象），点算 `AuditService` 的 `ev` 取值后实为**七类**（chat/search/auth/admin/invalid/feedback/degrade_transition）——且该表头在我改动前**已经陈旧**（feedback 加入后没跟）。已按点算值改准。教训与门闩系列同源：**类目数要点算，不能凭印象**。

### 门禁终态（本机）

`mvn -B test` 168/168（连跑两遍，30 个测试类集合逐项一致）｜ `offline` pytest **140 passed** ｜ `provenance --check` OK（423 chunks / 59 样本 / 64 篇）｜ `doc_numbers --check` OK（23 条 33 处）｜ 面板契约四层一致 ｜ `bash -n` 全脚本通过 ｜ 证据包 smoke **46 产物** + `sha256sum -c` 46/46 OK + 同代自检通过。

### CI 首验（run `36369276252`，五 job 全绿）

`5b7e15f` 推送后 CI **success**，五 job 全绿：`java` 30s ｜ `python` 14s ｜ `provenance` 7s ｜ `panel-contract` 5s ｜ `shell` 4s。

CI 侧读数（**不是本机读数**）：`Tests run: 168, Failures: 0, Errors: 0, Skipped: 0` ｜ `139 passed` ｜ `OK 23 条文档数字与产物一致（33 处引用全部命中且相符）` ｜ 面板契约四层一致 ｜ 9 个脚本语法通过 ｜ 证据快照自洽。Python 侧首验时是 139——**第 5 处修复的用例是首验之后才加的**，故本机现为 140，下次 CI 复验应对齐。

**本机绿＝CI 绿，未出现"本机绿≠CI 绿"**：这批改动动了审计行的字段与 `log()` 签名、新增 18 个用例，此前从未在 CI 跑过（本项目有 2026-09-16 那次前科）。


---

## 修补记录（2026-09-28 续：批次二 · 活体窗口）

来源：《OpsPilot 完整系统化改进计划-20260928》批次二（需活体栈的六项）。窗口：2026-09-28 11:31–11:41，
两次进程实例（正常模型 / 失败注入），全程只用**默认配额**（未抬高；用量 2900/5000，见下），
`git_sha=5b7e15f`（批次一）+ 本轮改动（未提交时的本机口径）。

### 这轮最重要的一件事：**降级状态机第一次在真实负载下被触发**

审计里 7 条 `degrade_transition`（`python scripts/audit_timeline.py` 可复现），链式连续、cause 全在词表内：

| 时刻 | 转移 | cause | 来源 |
|---|---|---|---|
| 11:34:01 | `L0 → L1` | `inflight` | **唯一指纹压测**：48 并发、在途峰值 **137** ≥ 触发线 40 |
| 11:34:04 | `L1 → L2` | `llm_failure` | 同轮上游并发上限（0.4s 内 41 次 `too many concurrent streams`，随后 76 次 429）三连败达阈 |
| 11:36:34 | `L2 → L0` | `cooldown_expired` | 冷却 60s 到期半开 |
| 11:38:03 / 11:40:10 | `L0 → L2` | `llm_failure` | **失败注入**（`LLM_MODEL=__opspilot_probe_invalid__` → 上游 404） |
| 11:39:04 / 11:41:12 | `L2 → L0` | `cooldown_expired` | 同上半开 |

**判据三重交叉全中**：① `/state` 采样 worst 达 L1/L2；② 在途峰值 137 ≥ 40（阈值由 `/state` 现读）；
③ 审计行 `degrade_level=L1` 的**管线行** 13/13 满足 `mode=es_only` **且** `stage_ms.vector==0`，**反例 0**。
判据③里那条 `vector==0` 是本轮修复的价值兑现点：同一窗口的 **L0 管线行里有 5 条 `mode=es_only`**
（检索腿超时所致）——按旧口径（只看 `mode`）它们会被读成"降级生效"，正是本轮要消除的假证据。

**L2 期请求的精确口径**：单条请求 `llm_calls +0`、`sop_fallbacks +1`、meta 帧 `degradation_level="L2"`——
"熔断即零 LLM 调用"在活体上成立。**回滚闸门**照计划执行：等 `cooldown_s==0` 后打探测，
`level==L0` 且 `failures` 只 +1（未达阈不重开），半开语义与 `DegradationRecoveryTest` 一致。

### 一处方法论纠正（比结果更值钱）

首轮 25→500 的六档扫描**测不到降级**，根因不是"负载不够"而是**量具选错**：热点池只 5 条查询 →
预热后几乎全走缓存；同指纹被 Single-Flight 折成一个 leader；`wait_time` 令占空比只剩 0.05–0.2；
且 locust `stream=True` 却**不消费 SSE body**。故 09-25 那条曲线测的是"缓存回放 + 连接层"，
不是在途。唯一指纹场景（占空比≈1、每请求唯一指纹）把在途从 **11** 抬到 **137**，一次就过线。
**nonce 必须纯字母**：指纹归一化会掩码数字与 UUID，带数字的 nonce 归一化后所有请求同指纹，
又会被折成一个 leader（这条已写进 `locustfile.py` 的函数注释，防下一个人重踩）。

### 其余四项（同窗口）

| 项 | 结果 |
|---|---|
| **OP-R5 三模式消融** | `offline/eval/reports/mode_ablation.md`：聚合读数与已入库报告**逐位一致**（最大偏差 **0.0**）。**id 级对照推翻了"融合无用"的读法**：hybrid 与 vector_only 的 top-3 在 exact 桶 **21/34 题不同**、semantic 桶 **4/25 题不同**，且 ES 腿在最终 top-3 里贡献 exact 102 条 / semantic 73 条 ⇒ 解释 (b)"融合没接线"被**排除**，"测不出增益"的真相是**指标饱和**（exact 桶三模式全 1.0/1.0/1.0，无余量）。**不改默认配置** |
| **OP-R1 答案接地** | 生成质量探针 **12/12 PASS**，chat 实调 14 次（预算 ≤15）。接地报告落盘（answers=11 / codes=16 / **无据 0** / rate 100%）并进证据包；新增 `offline/eval/answer_eval.py --check` 作 CI 门（只断言"同代 + 零无据"，**不**断言 rate 数值） |
| **OP-A7 X-Trace-Id 活体** | 合法头 → 200 且审计行带 `trace_id`（同一请求的 `degrade_transition` 行与 chat 行共享 `trace_id`+`request_id`，正是"多步可关联"的实证）；非法头（含空格 / 长度 1）→ **400** 且落 `ev=invalid`；响应体按面分流（非 /v1 为 `{code,message}`） |
| **OP-A6 安全不变式** | 窗口内 `dedup_guard` 行 **0**、跨租户 `src_tenant` 行 **0**；L2 行携带 `stage_ms` 的 **0** 条（`null=没测` 的语义在 2643 行高压下未被破坏） |

### 本轮新记录的债务（不顺手改）

`IllegalStateException: AsyncContext after error` ×2（locust 收尾那一秒，客户端已断开）——属"错误后仍尝试写"
的收尾竞态，**无客户可见影响**，已按本表纪律写成 OPS §5 的可判读触发线行，不在批次二范围内修。

### 2026-09-28 复跑：一次完整过程（不录屏，只走通全链路）

按本仓自己的口径把过程走了一遍——`demo.sh` 预检 → `acceptance_a2.py` → `acceptance_a3.py` → 降级那一幕。

| 环节 | 结果 |
|---|---|
| 预检 `scripts/demo.sh` | **13 项全 PASS**（含"jar 不早于源码/配置"） |
| **A2 十项** | **10/10 PASS**（A2-1 SSE / A2-3 L1 / A2-4 L2 / A2-5 风暴 / A2-6 降级 / A2-8 权限 / A2-8b 密级 / A2-8c 租户矩阵 / A2-9 跨租户并发 / A2-10 留痕） |
| **A3 八项** | **8/8 PASS**（含 A3-3 双路对照、A3-6 TTFT client 1.151s / server 1126ms） |
| 降级那一幕 | 唯一指纹压测 u=48：在途峰值 **151**、`L0→L1(inflight)`；同轮上游并发上限 → `L1→L2(llm_failure)`；`L2→L0(cooldown_expired)`。L1 管线行签名 18/18 全中、反例 0 |
| 注入腿 + 回滚闸门 | 三条判据全中：`failures=3/cooldown=60 → L2` ｜ L2 期请求 `llm_calls +0 / sop_fallbacks +1` ｜ 冷却 61s 后探测 → `L0`、failures 只 +1 不重开 |
| 全段时间线 | `python scripts/audit_timeline.py`：**17 条转移**，链式连续、cause 全在词表内；三种来源可区分——`inflight`（A2 的 500 并发风暴也真触发了 L1，不只是手动锁；以及两次唯一指纹压测）、`llm_failure`（上游 429/并发上限 + 注入的 404）、`manual`/`manual_clear`（A2-6） |

**这轮暴露并修掉的两件事**：

1. **一处"宣称大于断言"**：`acceptance_a3.py` 的 A3-3 断言是 `>=`（正确），但**名字与注释写作"双路优于纯向量"**，而它自己的读数两侧逐位相等。已把措辞收紧为"双路不劣于纯向量（逐位相等亦算通过）"，并在注释里指向 `mode_ablation.md` 讲清"融合生效但指标饱和"。
2. **两条本机操作纪律**（已写进 OPS §7）：① **停进程按端口杀，别按应用自报 pid**——按 pid 杀要先 `source .env`，缺它 `localapi.login` 抛错 ⇒ 变量为空 ⇒ `taskkill` 空转且不报错；② **启动成功以 `Started OpsPilotApplication` 日志为准**——只看 `/health` 或 `/state` 会读到**上一个还活着的实例**。本轮就因此把"三种配额覆写方式都无效"当成结论（实际是旧实例幽灵，新进程早已 `APPLICATION FAILED TO START`）；干净单实例复测确认 `OPSPILOT_QUOTA_DAILY_LIMIT` 生效，且重负载轮次须一并抬高（唯一指纹压测 ~2000 请求会吃掉同一主体当日预算）。

### 门禁终态与用量

`mvn -B test` 168/168 ｜ `offline` pytest 140 ｜ `provenance --check` OK ｜ `doc_numbers --check` OK ｜
面板契约四层一致 ｜ `bash -n` 全通过 ｜ 证据包 smoke（含本轮 5 个新登记项）自洽。
**CI 复验（push `e91a6be`，run `36375199303`）**：五 job 全绿（`java` 33s ｜ `python` 17s ｜ `provenance` 8s ｜ `panel-contract` 4s ｜ `shell` 5s）。
其中 **`provenance` job 新增第 4 步**（`Grounding report ⟷ corpus same-generation + zero-ungrounded gate`）并首次运行通过：
`OK 答案接地同代且零无据（answers=11 codes=16 rate=100%，语料摘要与 PROVENANCE 相符）`。
CI 侧其余读数与本机一致：`Tests run: 168, Failures: 0` ｜ `145 passed` ｜ 证据包 **51 产物** + `同代自检：通过` + `证据快照自洽（51 项）`。

**配额**：本轮全程默认上限 5000/主体，用掉 **≈2900**（唯一指纹场景占空比≈1，30s 发出 ≈2700 请求——
这是该场景的设计后果：要压出在途就得放弃思考时间；故报告里的 rps 不是容量上限，是"不等"的结果）。


---

## 修补记录（2026-09-28 续：探索性验收后的四项修复）

来源：一次 persona 驱动的探索性验收（read-only，探针落在仓外临时目录）报了 11 条发现，主控逐条复核其中
6 条承重结论**全部成立**，用户拍板先修 ①②③④（其余三类建议登记为边界）。全程 mock 后端——**上游 DashScope
当日欠费**（三路全返 `code=Arrearage`），按本项目"别把降级态当基线"的纪律，live 一律未跑。

| 项 | 修了什么 | 实测证据（修前 → 修后） |
|---|---|---|
| **① CORS** | `CorsConfig` 曾把 `"http://localhost:"` 这类**前缀**直接喂给 Spring 的 `allowedOriginPatterns`（那个 API 是 **glob 匹配**，无 `*` 即字面量精确匹配）⇒ 任何真实 origin 都不命中，而 `isAllowedOrigin()`（startsWith）却判"允许"——**同一份白名单两套判据互相矛盾**。改为 glob 为权威形态、前缀集合由它**派生**（不可能再分叉），并补上 `X-Trace-Id` 到 `allowedHeaders`（本仓 2026-09-28 新增的调用方关联键，漏了它带该头的跨源请求照样被预检拒） | `OPTIONS Origin: http://localhost:3000` **403 Invalid CORS request → 200 + `Access-Control-Allow-Origin`**；带 `Access-Control-Request-Headers: authorization,content-type,x-trace-id` → 200 且 ACAH 全回显；`http://evil.example.com` 仍 **403** |
| **② 错误形状** | 声明只有两种（`/v1` 信封 + 其余 `{code,message}`），实测跑着**四种**：非 /v1 的 401 走 `sendError` 落 Spring 默认体（`{timestamp,status,error,path}`）、登录 401 是 `@ExceptionHandler` **返回** `ResponseStatusException` 被 Spring 渲染成 `application/problem+json`（还回显 `instance`）。新增 `gateway/ErrorBodies` 作**单点出口**，`JwtAuthFilter`/`RequestIdFilter`/`GlobalExceptionHandler`/`OpenAiErrorAdvice`/`AuthController` 全部改走它 | 四个受守卫面 + 登录面（错口令）无凭证请求：`401 application/json keys=['code','message']`，**五处全中**；再无 `timestamp`/`path`/`problem+json` |
| **③ 回指澄清** | 新增 `gateway/ClarificationGate` + 编排分支：单轮系统遇到"刚才那个怎么办"这类**依赖上文**的短句，在**检索前**澄清而不是硬检索（修前实测会命中一篇无关复盘并答得自信——`51204_BACKUP_LAYER_MISSING`）。判据三重合取 + 无强标识符前提（≤8 字 ∧ 首部回指短语 ∧ 其后只剩提问尾巴），零 LLM、无 refs、审计 `mode=clarify` + `refused=true`、不落 `stage_ms` | `chat("刚才那个怎么办")`：**0 refs + 含"单轮"说明**（修前 3 refs、给无关复盘）；`chat("上面说的第二步呢")` 同样澄清；正常问题仍 **3 refs** 正常作答 |
| **④ /v1 面 400 留痕** | `OpenAiErrorAdvice` 的 `unreadable()` 与 `status()` 补 `audit.logInvalid`：此前同面 415 落、400 不落（与非 /v1 面 400 类全落不对称） | 打一发畸形体 400 → 审计行 **+1**，`{"ev":"invalid","path":"/v1/chat/completions","msg":"请求体解析失败"}` |

**变异验证（四条，各改坏必红后还原）**：① CORS 常量改回前缀形态 → `CorsConfigTest` 3 处红（含 Spring 真实匹配器返回 `null`——正是原始 bug 形态）；② 非 /v1 改回 `sendError` → body 形状用例红；③ 去掉"尾巴只能是提问词"判据 → `ClarificationGateTest` 2 处红（含 `继续优化索引` 被误澄清）；④ 删掉 400 留痕 → `OpenAiErrorAdviceTest` 红。

**这次收敛顺手修掉的两处"自己刚埋的"**：
- **`X-Trace-Id` 没进 `allowedHeaders`**：① 修 CORS 时才发现——同一个洞的第二种形态（浏览器要发的头被预检拒）。
- **澄清门放在了缓存之后**：第一版把门写在 `runPipeline` 里（缓存命中会先回放）⇒ 修前落进 L1 的旧答案继续生效，**实测"清缓存才生效"**。门判的是"输入形态"，与缓存状态无关，故移到**缓存之前**（仅 L2 期让位 SOP），并把"必须在 `try` 之内"写进注释——放到 try 外会让异常路径漏掉 `singleFlight.finish`，同指纹后续请求会阻塞在永不完成的 future 上。

**两条新操作纪律（都写进 OPS §7）**：**打 jar 前必须先停服务**——Windows 上 JVM 锁着 `target/*.jar` 时 `spring-boot:repackage` 覆盖不了它，会**失败并留下被截断的 jar**（实测 84MB → 217KB，此后怎么起都是错的东西）；以及本轮新确认的"停进程按端口杀、启动以日志为准"。

**门禁终态**：`mvn -B test` **188/188**（原 168 → +20 用例）｜ pytest 145 ｜ doc_numbers 23 条 33 处 ｜ provenance OK ｜ 面板契约四层一致 ｜ `bash -n` 全通过 ｜ 证据包 smoke 51/51 自洽。
**CI 复验**（push `4a2940a`，run `36407216611`）：五 job 全绿，CI 侧读数与本机一致（`Tests run: 188, Failures: 0` ｜ `145 passed` ｜ 23 条 33 处 ｜ 接地门 OK ｜ 51 个产物）。

**本轮未修、如实留档的两项**（都不是"忘了"）：
- **门控只有一道 Top-1 阈值闸**（探索性验收 F5）：mock 上 `今天天气怎么样` top_rerank **0.695**、`zzzqqqxxyyy 完全不存在的东西` **0.823**，均过阈 → 不拒答、给出自信但不相关的答案；纯 ASCII 乱串 0.0 才拒答。**但** `min-relevance: 0.2` 是按 **live 精排分数分布**标定的（ADR-0012），mock 的分数字段不可比，故"live 是否同样漏拒"**未验**（当日上游欠费）。它的真身是与后端无关的**设计观察**：对"共词但域外"的输入缺第二道闸。建议方向（待 live 可用后评估）：无可识别领域词 → 走澄清而非硬答。
- **`stage_ms` 的 sub-ms 段口径**（F6）：`rrf` 恒 0（纯计算）、mock 下 `rerank` 也常 0，与"0=未调用"的绝对表述冲突——已在 `CONTEXT.md` 分段耗时词条补限定（"是否调用以 `mode`/`fast_path` 为准"），未改字段本身。

### F5 触发线兑现（2026-10-03，live 恢复后首验）

上游欠费解除（三路探活 PASS）后，按 OPS §5 那行登记的兑现条件做了 live 复测：固定域外样本集五条，
live 精排（`gte-rerank-v2`）下 top-1 分别为 **0.0061 / 0.1348 / 0.1618 / 0.0655 / 0.1168**——
**0/5 过阈（阈值 0.2），全部被门正确拒答 → 触发线未触发，维持"不立项第二道闸"**。
mock 那组 0.695/0.823 确认为 IDF 覆盖量具的假象（正如 ADR-0012"按 live 分布标定"的判断）。
对照读数：`50012_DB_TIMEOUT 怎么排查` 走快路径（fast_path=true，top_rerank 0.0=未调精排），
live 全链路 3 refs / TTFT 1.3s / `trace_id` 落审计。另：live 上 `/search` 对任意乱串仍返回 n=3
（零召回分支依旧只由"腿全挂"触发，低相关度拒答才是实际行使的路径——与既有口径一致）。


---

## 修补记录（2026-10-03：live 模式下的第二轮验收与批次 A/B）

上一轮（2026-09-28）验收跑在 mock 后端上——上游 DashScope 欠费（三路 `code=Arrearage`），按"别把降级态
当基线"的纪律 live 一律未跑。欠费解除后补跑了 live 全程验收，结论与上轮**有出入**：mock 与 live 对
"域外 query 是否漏拒"给出相反答案（见下），这本身就是本项目"数字必须以 live 为准"的又一次实证。

### live 验收的结论

| 项 | 结论 |
|---|---|
| 核心承诺 | 兑现：带真实错误码的排障问答 10 次生成全部可执行；**错误码接地率 100%**（V9 判据 3/3）；诱导附和被明确反驳且逐条有据；编造错误码被正确拒答；跨面 `X-Trace-Id` 串链与反馈闭环实测可用 |
| 上轮四项修复 | 在 live 上全部成立（CORS 200+ACAO、错误体两形状、澄清门、/v1 400 留痕） |
| **F5 门控单阈值闸** | **live 复测未触发**：五条域外样本的 top rerank 为 0.0061–0.1618，全部 <0.2 → 全部正确拒答。上轮 mock 的 0.695/0.823 确认为 IDF 覆盖量具的假象（与 ADR-0012"按 live 分布标定"一致）。台账 §5 已带读数收口 |
| **F-1（新真缺陷）** | 缓存拒答回放翻转审计语义——拒答写进 L1（2h TTL），重发同 query 走回放，而 `replay()` 硬编码 `refused=false` 且取 payload 的 `maxAuthLevel`（拒答写缓存时存的是请求者级别）⇒ 同一拒答在审计里变成"未拒答、触达密级 3"（本机 audit 实测 3 行）。`daily_usage` 的 refuse_rate 靠 `grep '"refused":true'` 统计 ⇒ **事故期重发同症状 query 的拒答被系统性低估**。已修：见批次 A |
| F-2（两条触发线兑现） | 答案编造语料外命名并挂引用标号（4 项实测 0 命中）⇒ 兑现 OPS §5.1「V9 覆盖边界」与「引用洗白」两行触发线，两行已转入"已立项（第一步已落地）" |
| F-3 / F-4 / F-5 / F-6 | 口语极值被误拒（golden 缺口语覆盖）、拒答入 L1 的 2h 窗口、fingerprint 无形状校验、追问无强标识符时直接死在一句话拒答 |

### 批次 A（提交 `5630a6a`，CI 五 job 全绿）

F-1（`AnswerPayload.refused` 入存储合同 + 拒答 `maxAuthLevel=0` + `replay()` 照实落审计；回归锁 + 变异验证实测改坏必红）；
F-5（`fingerprint` 加 `@Pattern("[0-9a-f]{32}")`，**存在性仍宽容**；新增 MockMvc 用例锁"400 + INVALID_REQUEST + 审计留痕"全链——直调 controller 锁不住 MVC 校验层）；
F-2 第一步（`PromptAssembler` 规则 7 **无条件注入**；warn-only 探针，实测对 4 项编造物全部点名、对语料内真实键零误报）；
F-4/F-6 登记（CONTEXT 缓存域 L1 词条补"门控拒答入 L1"边界 + OPS §5 触发线；DEMO 新增「怎么问才对」指引）。

> 运维侧须知（F-1 的连带效应）：`daily_usage` 的 0.10 阈值**语义不变**，但读数在修复后会**上升**（以前漏计的回放拒答被算进来了）。

### 批次 B（提交 `7c5b272`，CI 五 job 全绿）

F-3：`SEMANTIC` 清单纳入口语极值两条（59→61 样本），live 重跑 `build_golden` + `evaluate` 同批。**阈值 0.2 不动**（ADR-0012 纪律）。
**核心指标重定基线**：es_only 语义 Hit@1 64%→**59.3%**、Hit@3 92%→**88.9%**、MRR 0.767→**0.722**；hybrid 88%→**85.2%**、Hit@3 100% 不变、MRR 0.940→**0.920**；`hybrid ≡ vector_only` 依旧；精确桶 1.0/1.0/1.0 与越狱 0 泄漏不变。
`doc_numbers` 连锁改字（golden 59→61、语义 25→27、语义 hit@1/hit@3/MRR 各处）**且门闩扩面**（golden 三条补 CONTEXT 引用位）；4 条 registered pattern 因叙事改写而 0 命中——"0 命中必须报红"的机制如预期报警，同批改写 pattern 并实测命中。
历史叙事 `0.813→0.767`（423 chunks 时的实测对）**原样保留**不改历史去迎合新量（`test_historical_narrative_numbers_are_not_gated` 的反向边界正以它为前提）。

**一处如实记录的状态**：两条口语极值样本的 hybrid hit@3 = 100%（检索能找到），但门控按 top-1 相关度仍 <0.2 拒答——即"评测集已盯住、门控仍未放行"。这不是本批要解决的（动阈值须以 live 分布标定为据），但它是 F-3 想改善的体验痛点仍在的证据。

门禁终态：mvn **190/190** ｜ pytest **145** ｜ doc_numbers **23 条 36 处** ｜ provenance OK（golden=61）｜ 面板契约四层 ｜ 证据包 51/51。
