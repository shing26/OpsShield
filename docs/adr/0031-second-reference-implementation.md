# ADR-0031：第二个参考实现——数据库值班领域包（Domain Pack）

日期：2026-11-16 ｜ 状态：**proposed（草案；未经评审、未经签收、不批准实现）** ｜
关联：[ADR-0029](0029-reuse-boundary-trust-base-vs-domain-plugins.md)（分层判据与 rule of three）、
[ADR-0028](0028-l1-controlled-action-contract.md)（执行闭环）、
[ADR-0013](0013-trust-base-for-agents-not-an-agent.md)（可信底座定位）、
[ADR-0022](0022-standalone-storage-mode.md)（配置期开关不自动回退）、
[ADR-0025](0025-deployment-profiles-and-strict-mode.md)（部署 profile）、
契约草案 [plans/second-domain-accept.md](../../plans/second-domain-accept.md)

## 背景

ADR-0029 决策 4 把接口提取绑在 rule of three 上：第一个用例=排障，第二个=ADR-0028 执行闭环的
runbook 引擎落位，第三个出现时才提接口。首个切面（PR #85 / `7760c40`）只外置了 L1 命令策略
白名单的**内容**；ADR-0029 R3 明确"内容外置 ≠ 接口提取"，真正的策略接口仍等第三个**领域**。

今天底座里仍有三类领域内容被写死（静态可查）：

- **SOP 段名**：`src/main/java/com/opspilot/ingest/IngestionRunner.java:491` 用
  `breadcrumb().contains("止损操作")` 决定哪些 chunk 预热为 L2 静态 SOP；
  `src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java:40` 用
  `SECTION_MARK = "排查"` 决定哪些段能进只读行动契约，`:44` 的排除词（陷阱/误判）同样是
  中文领域词。两处各自持有一份同一类知识。
- **文案**：`src/main/java/com/opspilot/llm/PromptAssembler.java:14-22` 的角色、回答结构与拒答
  话术；`src/main/java/com/opspilot/gateway/ChatOrchestrator.java:315`（L2 兜底）与 `:351`
  （低置信度拒答，内嵌示例错误码 `50012_DB_TIMEOUT`）；内部错误帧还会说"联系值班 SRE"
  （`gateway/SseEvents.java:95`、`gateway/OpenAiChatSink.java:131`）。换一个领域部署时，这些
  会让"同一套底座"说出前一个领域的话。mock 后端另有一套同类文案
  （`llm/MockEngine.java:99/103/132-137`），本切面不动，原因见 D5 的遗留说明。
- **语料路径**：`IngestionRunner.java:90` 与 `src/main/java/com/opspilot/config/MockIdfInitializer.java:29`
  各自写了一遍默认值 `offline/corpus/chunks.jsonl`，同一事实源有两份。

这些都不是安全机制，而是**领域内容**。ADR-0029 列出的五项插件契约里，命令白名单已由首个切面
外置；剩下四项（语料来源、去重键归一化、静态 Runbook、Prompt/文案）仍没有第二个实装来检验
"内容可换、防线不动"这句话。

## 决策

### D1 选域：数据库值班（DBA）

判据四项，逐项给仓内事实（不是设计愿望）：

| 判据 | 仓内事实 |
| --- | --- |
| 已有真实消费者 | ① `src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java:158-171` 有一条**专为数据库内容**建的 SQL 判定分支（`SQL_READ_VERBS` / `SQL_WRITE_VERBS`），`ReadOnlyCommandPolicyTest` 用 `SHOW FULL PROCESSLIST` / `SHOW ENGINE INNODB STATUS\G` / `... FOR UPDATE NOWAIT` 做回归；② `ReadOnlyActionExtractor.java:53-54` 的 `STATEMENT_LANGS` 已认 sql/mysql/pgsql/plsql/tsql/cql/hql，并为多行 SQL 并句（否则 `FOR UPDATE` 从句会被切掉）；③ 语料 **41 个 chunk** 带数据库错误码（50012/50013/50101/50141），**5 篇** DB runbook/复盘（rb-001、rb-002、ac-508、pm-001、pm-002） |
| 已有真实数据 | 真实语句与异常：`SHOW ENGINE INNODB STATUS\G`、`SHOW FULL PROCESSLIST`、`EXPLAIN`、`ANALYZE TABLE`、`SELECT ... FOR UPDATE NOWAIT`、`Deadlock found when trying to get lock`；真实止损动作：`KILL <processlist_id>`（rb-001）、单表 analyze（ac-508）、按加锁顺序还原（pm-002） |
| 已有真实运维动作 | 上述动作都是 DBA 值班的日常动作，且**已经在语料里**；本切面不是先造场景再找内容 |
| 不会把领域词焊回底座 | 反方向：今天 SQL 词表**就在底座里**（`ReadOnlyCommandPolicy`）。本切面不改它，但把"哪些词属于领域"显性化，并在 D3/D7 登记把它移出底座的触发条件 |

**为什么这是第三个真实用例，而不是为凑数造的玩具**——反事实检验：

1. 删掉本切面新增的 DBA 语料目录，底座**今天就已经在为数据库内容服务**：SQL 判定分支、
   `STATEMENT_LANGS`、41 个 DB chunk、5 篇 DB 文档全部在跑。DBA 包不是新造一个场景，而是给
   一个已经在跑、但没有名字和边界的领域补上名字与边界。
2. 反过来，如果只是"把 SRE 文案换个词"，删掉它 SRE 仍然完整——那才是玩具。本设计用三条硬约束
   挡住这种退化：D2 的"命令判定机制零改动"、契约里的 `source_incident` 必须指回既有事故、以及
   AC-3 的领域词隔离门闩。
3. 它不需要新增任何机制才能跑通（ingest / SOP / prompt / 检索全在），需要新增的只是"命名与
   边界"——这正是"插件"的定义。反例：CI/CD 候选需要先造命令与语料（见备选表），那才是玩具风险。

**为什么它不是 SRE**：数据库值班的读者、错误词汇（MySQL errno / SQLSTATE）、动作（SQL 语句
而非 shell/kubectl）、止损对象（锁、统计信息、连接）都不同。本包以 DBA 值班的口吻重写这些
事件，但保留原始事故出处（`source_incident`），不发明事故、不发明错误码。**它不复用 SRE 语料
根**：同一份内容改个 tenant 不构成第二领域；重写的正是"同一个事故在 DBA 眼里该看什么、该动
什么"——这一步是领域内容的实质，不是改名。

### D2 最小可验证范围：动三件、等一件、一件不动

| 契约 | 本切面 | 说明 |
| --- | --- | --- |
| 语料来源 | **动（数据）** | 第二份语料根 `offline/domains/dba/corpus/`，用**既有** `offline/chunkers/build_chunks.py --corpus ... --out ...` 生成（该 CLI 已支持两个参数，无需改代码）；manifest 声明默认路径 |
| SOP / Runbook | **动（数据）** | 段名标记（止损段、诊断段、排除词）从 pack 读；`IngestionRunner` 与 `ReadOnlyActionExtractor` 共用同一份声明。SOP 的键形状、TTL、租户分片、预热时机不动 |
| 提示词 / 文案 | **动（数据）** | 角色、回答结构、拒答、L2 兜底、on-call 称呼从 pack 读；**底座安全条款原样保留且不可覆盖**；`VerbatimGuard` 不动 |
| 指纹归一化 | **等** | 第三种规则（保留 MySQL errno / SQLSTATE）是**上下文相关**的："1213"是错误号还是订单号只能靠周边文本判，不能表达为闭集形状。本切面让 DBA 包复用 SRE 归一化，并在契约里登记"DBA 探针只用应用错误码"的限制 |
| 命令族 | **不动** | 已由首个切面外置；本切面**命令判定机制零改动**（`ReadOnlyCommandPolicy`/`ControlledCommandPolicy`/`CommandPolicyData`/`command-policy.json`），只改 `ReadOnlyActionExtractor` 的段名来源。DBA 语料每条命令必须命中现有 L0 判据，否则内容移进止血段——不得放宽策略 |

**这次不解锁什么**（逐条写死，评审时不得口头放宽）：

1. 不提取 SPI / `ServiceLoader` / Java 接口；pack 仍是编译期固定路径 + 严格 schema。
2. 不拆 Maven 模块；不做同进程多域共存；不做按租户/请求切换域；不做热加载/HTTP 上传。
3. 不新增 L1 家族；不改 L0 `TOOLS`/`VERBS`；任何 pack 不得携带 `KILL`/DDL/DML。
4. 不动指纹归一化（原因见上表）。
5. 不把 `action/` 纳入词法门闩（原因见 D7）。
6. 不新增 `dba` 角色/账号维度：领域是**部署选择**，不是权限维度；角色仍是 sre/dev/manager。

### D3 落位：classpath 领域包 + 启动期选择（`opspilot.domain`）

目录与类草案（文字，不写实现）：

```
src/main/resources/domain/
  sre/domain.json          # id / schema_version / 段名 / 文案 / 语料默认路径 / 词表
  sre/prompt.json          # 领域槽位：角色 / 回答结构 / 拒答 / 领域附加规则
  dba/domain.json
  dba/prompt.json

src/main/java/com/opspilot/domain/
  DomainPack.java          # 不可变 record；只从 classpath 读一个固定路径
  DomainRegistry.java      # @Component；按 opspilot.domain 解析，未知/缺失即启动失败

offline/domains/dba/
  corpus/runbooks/*.md
  corpus/postmortems/*.md
  corpus/chunks.jsonl      # 既有 chunker 产出
```

`opspilot.domain` 是**配置期事实**，默认 `sre`，**不自动回退**（与 ADR-0022 `storage.mode`
同款口径：未知 id、空值、含路径分隔符 → 启动失败并点名，不"猜一个最接近的"）。领域选择是
单值，不是叠加，也不是租户属性。

**读取方式**：`DomainRegistry` 启动时解析出唯一的 `DomainPack`，同时设置
`DomainPack.current()`（进程内单例，与 `CommandPolicyData.INSTANCE` 同款）；未初始化时回落到
SRE 包。这样做不是为了全局可变状态，而是为了**不改 `PromptAssembler` / `ChatOrchestrator`
的构造签名**——它们的既有单测（`PromptAssemblerTest`、`ChatOrchestratorTest`）必须零改动，
否则"保护既有断言"就变成"改测试让它过"。

**否决的落位形态**：

| 形态 | 否决理由 |
| --- | --- |
| 独立 Maven 模块 | 需要先把 core 抽出：453 个测试、单 jar 发布、`release.sh`/`rollback.sh` 产物全要改。当前没有仓库外的插件作者，拆了只增加构建面。等真出现"别人写插件"再拆 |
| Spring profile | profile 已承载部署形态（ADR-0025），可组合、可被环境变量叠加；把领域塞进 profile 会让"这次跑的是哪个领域"取决于变量组合（`prod,alerting,dba` 是否合法？）。领域是单选，不是叠加 |
| 运行时装配（下载/上传 jar 或 JSON） | 新增攻击面与版本兼容债务，与"classpath 固定路径、未知键启动失败"的既有口径冲突；ADR-0029 决策 3 已否决 SaaS 形态，插件同样不该开运行时口子 |
| 继续 `@Value` 各写一份默认路径 | 同一事实源两处维护（`IngestionRunner` / `MockIdfInitializer`），换域必漏一处 |

**不 fork 仓库替换插件**：新增 `src/main/resources/domain/<id>/` 与 `offline/domains/<id>/corpus/`，
设 `OPSPILOT_DOMAIN=<id>`。前提是该领域**不需要新的 L0 工具或 L1 家族**；需要的话是代码评审
变更，不是数据变更——这条要写进 README / repo-map，不能含糊成"完全可插拔"。

**为什么本切面不做 DBA 的 L1 家族**（三个机制事实，均可复核）：

1. `ANALYZE TABLE` 目前在 L0 读集内（`SQL_READ_VERBS` 含 `ANALYZE`），而
   `ControlledCommandPolicy.classify` **先判 L0**（`:43`）——任何以它为前缀的 L1 家族今天
   不可达；要改就得动 L0 判定顺序或词表，属另一份契约。
2. 真实表名带下划线（`t_order` / `t_inventory`），而 L1 目标抽取的 `isPlainToken` 是
   `[a-z0-9][a-z0-9./-]*`（`ControlledCommandPolicy.java:145`），表名会被判成非法目标；修它要
   动 L1 目标白名单路径。
3. 语料切分器**不产出 `action_class`**（`offline/chunkers/markdown_tree_chunker.py` 只写
   service/env/auth_level/error_codes/tenant），今天没有任何真实 chunk 能声明 L1；要让 DBA
   成为第一个真实 L1 内容，先得扩切分器。

三条都要独立验收；塞进"第二个领域"里会让安全面一次动太多。

### D4 安全不变量：插件数据只能**收紧**，不能放松

- **单调收紧**：pack 可以增加限制（例如把某个词加入诊断段排除集、替换 on-call 文案），但不得
  移除底座的最低拒绝集、不得覆盖安全条款、不得新增行为枚举值。任何"放松"都是代码变更，走独立
  评审——与 ADR-0029 决策 5 同源。
- **严格加载**：未知 `schema_version`、未知顶层/嵌套键、空段名列表、段名含正则元字符、缺
  prompt 文件、`answer_structure` 缺 `## 问题定位` 协议头、文案缺必需键、`extra_rules` 超长
  或含换行 → **启动失败**；不静默忽略、不回落默认值。
- **槽位而非自由模板**：pack 只填 `role` / `answer_structure` / `refusal_no_context` /
  `extra_rules` 四个槽；规则编号与固定条款由底座组装，SRE 包的组装结果必须与今天的 SYSTEM
  文本**逐字相等**（黄金串测试）。这样既保留"规则 4–7"的文档引用编号，也堵住"模板删掉安全
  条款"的路径。
- **不可覆盖的底座条款**：`PromptAssembler` 的规则 1/4/5/7 与条件规则 6、`VerbatimGuard` 的
  80 字/逐字口径、拒答的 `gate` 语义，全部由底座**追加**，pack 模板不得删除或改写。用"模板里
  写相反指令"的负向用例钉住。
- **协议头固定**：控制台的结论行从答案的 `## 问题定位` 提取（`src/main/resources/static/index.html:951-957`，
  `scripts/check_panel_contract.sh` 管的面板契约），因此 `## 问题定位` 是**协议字面量**而非
  领域文案；每个 pack 的模板必须包含它，其余标题可换。
- **零执行 / 零权限变化**：本切面不碰 `action/` 的命令判定机制（`ReadOnlyCommandPolicy` /
  `ControlledCommandPolicy` / `CommandPolicyData`）、`auth/`、`audit`、`store`、`retrieval`；
  引擎层权限过滤、审计哈希链、降级状态机、SOP 租户分片全部不变。

**必须继续留在底座（本切面不搬）**：

- 引擎层权限过滤（`IdentityFilter`）与 `tenant × auth_level` 闸；
- 审计哈希链与 WORM 门禁；
- 降级状态机与档位语义；
- `VerbatimGuard` 与出口逐字口径；
- 只读命令四层判定、注入门、L1 双确认/签名派发/状态机；
- SOP 键形状/TTL/租户分片、L1/L2 缓存键与身份；
- `## 问题定位` 协议头；
- 所有 loader 的 fail-closed schema 与允许的行为枚举（`TailShape` 等）。

pack 换的是"内容"，不是这些防线；任何让底座为迁就 pack 而放松上述构造保证的提案，直接否决
（ADR-0029 决策 5 同源）。

### D5 接缝地图（具体接到哪一行）

| 接缝 | 现状 | 本切面 |
| --- | --- | --- |
| 语料路径 | `IngestionRunner.java:90`、`MockIdfInitializer.java:29` 各一份默认 | 两者都从 `DomainPack.corpusPath()` 取默认；`opspilot.chunks-path` 仍可覆盖（运维口子保留） |
| SOP 段名 | `IngestionRunner.java:491` 字面量 | `DomainPack.sections().stopLossMarkers()`；多标记任一命中即预热；Redis 键/TTL/租户分片不动 |
| 诊断段名 / 排除词 | `ReadOnlyActionExtractor.java:40`、`:44` 字面量 | `DomainPack.sections().diagnosticMarkers()` / `diagnosticExcludes()`；抽取与只读判定逻辑不动 |
| 系统提示 | `PromptAssembler.java:14-22` 文本块 | `domain/<id>/prompt.json`（角色/结构/拒答/附加规则四槽）+ 底座固定条款；`HYPOTHETICAL_MODE` / `UNGROUNDED_NAME_BAN` 保持底座 |
| 拒答 / 兜底文案 | `PromptAssembler:17`、`ChatOrchestrator:315`/`:351` | `DomainPack.copy()`；SRE 包的字符串与原值**逐字相等**。**`MockEngine` 的 mock 拒答/标题本切面不动**：它是机制验证后端的独立渲染路径，改它要动 `LlmClient` 的静态调用面，且不构成 live 领域面；已登记为遗留 |
| 内部错误文案 | `SseEvents:95`、`OpenAiChatSink:131` 的"联系值班 SRE" | 改为领域中性的"值班人员"（这是运维错误提示，不是领域文案） |
| on-call 称呼 | `PromptAssembler` / `ChatOrchestrator` 的 "值班 SRE" | `DomainPack.onCallName()`；SRE=`值班 SRE`，DBA=`DBA 值班` |
| 领域选择 | 无 | `DomainRegistry` + `opspilot.domain`（默认 `sre`） |
| 指纹 | `FingerprintService.java:30-35` | **不动**；DBA 复用同一归一化 |

### D6 回归锁（实现必须保持）

| 构造保证 | 锁 |
| --- | --- |
| 只读命令契约 | `ReadOnlyCommandPolicyTest`（18 条）+ 本切面**命令判定机制零 diff** |
| L1 白名单 | `ControlledCommandPolicyTest`（8 条）+ 灰度 fail-closed（`ActionOpsServiceTest` 13 条） |
| 逐字护栏 | `VerbatimGuardTest`（10 条）+ `PromptAssemblerTest`（4 条，SRE 包下逐字不变） |
| 权限 / 租户 | `IdentityFilter` 跨租户用例 + 红队跨租户类 |
| 审计链 | `AuditService` 链式写入 + `scripts/l1_worm_gate.py` 口径不变 |
| 降级 | 降级状态机单测 + 审计 `ev=degrade_transition` |
| 端到端 | `bash scripts/redteam.sh` 32/32、0 泄漏、0 SKIP（默认 sre 包） |

### D7 解锁与遗留、词法门闩

**解锁（可以另立契约提接口）**：

- **语料来源**：SRE 语料根、tenant-acme 语料（同一领域的第二租户）、DBA 语料根——同一个
  "从 JSONL 读、派生索引"的接口点已有三份数据供给。
- **SOP 段名**：SRE 止损段、ADR-0028 的 L1 runbook 止损段（同一个标记）、DBA 的 `止血与恢复`
  ——三个真实消费者，且第三个是**不同取值**，这是"策略"而非"常量"的证据。
- **Prompt/文案**：SRE live 模板、DBA live 模板、mock 后端的独立渲染路径——三个消费者、两个
  内容变体；可以提"模板契约"，但本切面不动 mock，所以它只算旁证，不算驱动。
- 以上三件**可以另立契约提接口**，但本切面只做**数据化**，不提取 SPI：接口形状要由第三个
  **实装**而不是第三份数据逼出来，否则会做出"只有一个实现的接口"。

**仍要等**：

- 指纹归一化：第三种规则需要上下文相关解析（errno ≠ 任意数字），先要独立设计"上下文相关
  标识符"的形状与 fail-closed 边界。
- 命令族：L0 `TOOLS`/`VERBS` 是安全边界而非内容（放宽 = 扩权），命令判定机制保持现状；DBA 的
  L1 家族等 D3 的三件机制前置。
- 真正的 SPI / 独立模块：等仓库外的插件作者出现。

**`scripts/check_domain_words.py` 与 `action/` 的关系**：

- 今天**不存在**这个脚本（`ls scripts | grep -i domain` 为空）；ADR-0029 R1 只是登记了判据。
  本切面新增它，但**只覆盖 R1 列出的干净底座包**（`storm`/`auth`/`cache`/`store`/`metrics`/
  `llm.VerbatimGuard`/`resilience` 除 `SopFallbackService`）**再加 `ingest/`**（段名移走后
  变干净）。
- `action/` **仍不纳入**：`TOOLS`/`VERBS`/SQL 词表还是底座内容，立闸会对合法机制假红。纳入
  条件沿用 R1："第一个非 SRE 命令家族出现，且家族尾部校验已移到策略接口"——本切面没有新家族，
  条件未达成。
- 词表不手写：从各 pack 的 `lexicon` 现取；门闩只扫**字符串字面量与文本块**（注释允许解释
  机制），并配"塞一个 `kubectl` 进干净包必红、复原必绿"的变异测试（`--self-test`）。
- 门闩进 CI（`provenance` job 加一步），否则只是本地脚本。

**文档计数影响**：ADR 30 → 31（README 两处"30 篇"、repo-map"30 项"由 `doc_numbers` 现算）；
新增 `@Test` 会改 README / repo-map 的单测数；DBA 语料放在 `offline/domains/` **不进**
`offline/corpus/` 的 glob，故 `chunks_lines`、`postmortem_runbook_docs`、
`offline/provenance.py` 的 SRE 计数不漂移。

## 备选与否决

| 备选 | 否决理由 |
| --- | --- |
| MQ 值班 | 真实数据够（rb-004 的 `sh mqadmin`、50031/50032、pm-005/006），但归一化策略与 SRE 相同（第三种规则欠奉）；且 `sh mqadmin ...` 今天被 L0 拒是一个**既有缺陷**，应由"L0 工具族"自己的契约修——拿它当第二领域会把"补洞"说成"插件" |
| CI/CD 发布值班 | 概念边界最干净，也是 ADR-0028 声明的派发目标（Argo/Rundeck）；但仓内没有发布事故语料，`argocd`/`helm` 零命中，内容只能现造——正是本 ADR 要避开的玩具风险。等有第一份真实发布事故记录再立项 |
| tenant-acme 的第二语料 | 那是**第二个租户**不是第二个领域：同一套 SRE 词汇与段名，且跨租户红队依赖它保持原样 |
| 只写文档、不改接缝 | "只写文档不提接口"的反向错误：领域内容仍焊在底座，换域仍要改 Java |
| 一次性外置五件 + 拆模块 | 一次动安全面与构建面，回归锁全要重来；违反 ADR-0015 / ADR-0029 的绞合式演进 |
| 让 pack 自带正则/脚本做段名或文案替换 | 数据即代码：正则与模板求值会变成运行时行为面，违反 D4 的"只能收紧、严格加载" |

## 后果

- **正面**：底座第一次能用**不 fork 仓库**的方式换一个领域的内容；`ingest/`、`llm/`、`gateway/`
  里三类领域文案/段名离开底座；SRE 包从"唯一实现"变成"第一个参考实现"；面试叙事从"一个排障
  项目"变为"一套换域只换内容的底座 + 两个参考实现"。
- **代价 / 风险**：多一份语料要维护与复核；DBA 包没有 golden 集（本切面只做固定查询探针）；
  领域选择是启动期单选，不能同进程多域；`## 问题定位` 协议头限制了模板自由度；指纹仍会合并
  1213/1205 这类 MySQL 错误号（已登记，DBA 探针规避）；**mock 后端**的拒答文案与段名分类仍是
  SRE 形状（memory/mock 演示下可见），已登记为遗留，等 mock 与 live 共用模板渲染时一并处理。
- **文档门闩**：ADR 31；README / repo-map / CONTEXT / OPS 同步；契约草案见
  [plans/second-domain-accept.md](../../plans/second-domain-accept.md)。

## 开放问题（需 Lead 拍板，冻结前）

1. **选域**：确认数据库值班为第二参考实现。若更想要 CI/CD，最小范围会变成"命令族 + 指纹 +
   文案"，风险面显著变大（见备选表）。
2. **`opspilot.domain` 语义**：配置期单选、默认 `sre`、未知即启动失败、不自动回退——是否照
   `storage.mode` 口径批准？
3. **协议头与安全条款**：`## 问题定位` 固定为协议字面量、底座安全条款不可被 pack 覆盖——
   是否作为冻结判据？
4. **语料真实性门槛**："源自已有事故 + 每篇 `source_incident` 指回原 doc/OPS 段"是否足够；
   还是必须先有第一手 DBA 值班记录才冻结？
5. **指纹延后**：本切面不做第三种归一化规则、DBA 探针只用应用错误码——接受这一限制，还是
   要求同批交付？

## 与 ADR-0029 的关系

- 本 ADR **不修改** ADR-0029 的任何判据；它是决策 4"第三个真实用例"的落位提案。
- **计数口径澄清**（不改原文）：ADR-0029 D4 的 #1=排障、#2=ADR-0028 执行闭环；ADR-0030 的
  统计基线预警是**生产者插件**（只消费链路、不提供领域内容），不构成内容契约的第三个用例。
  本 ADR 的 DBA 包是内容契约的第三个用例。
- 本 ADR **不放宽** R1 的 `action/` 门闩条件，也不预支 R2/R4 的任何放宽。

## 评审与签收（未发生）

本 ADR 为草案。评审须由独立会话按 D1–D7 与仓内代码事实逐条核对；签收前不得派实现。
