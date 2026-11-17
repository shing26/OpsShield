# ADR-0031：第二个参考实现——数据库值班领域包（薄切片）

日期：2026-11-16 ｜ 状态：**proposed（薄切片草案；未经评审、未经签收、不批准实现）** ｜
关联：[ADR-0029](0029-reuse-boundary-trust-base-vs-domain-plugins.md)（分层判据与 rule of three）、
[ADR-0028](0028-l1-controlled-action-contract.md)（执行闭环）、
[ADR-0013](0013-trust-base-for-agents-not-an-agent.md)（可信底座定位）、
[ADR-0022](0022-standalone-storage-mode.md)（配置期开关不自动回退）、
[ADR-0025](0025-deployment-profiles-and-strict-mode.md)（部署 profile）、
`scripts/check_domain_words.py`（ADR-0029 R1 门闩，PR #86 已合并）、
契约草案 [plans/second-domain-accept.md](../../plans/second-domain-accept.md)

## 背景

ADR-0029 决策 4 把接口提取绑在 rule of three 上：第一个用例=排障，第二个=ADR-0028 执行闭环的
runbook 引擎落位，第三个出现时才提接口。首个切面（PR #85 / `7760c40`）只外置了 L1 命令策略
白名单的**内容**；ADR-0029 R3 明确"内容外置 ≠ 接口提取"。R1 的词法门闩也已由 PR #86
（`7df3c03`）落地：`scripts/check_domain_words.py` 扫干净底座包，`action/` 与 `ingest/` 暂不在
扫描面内。

今天底座里仍有三类领域内容被写死（静态可查）：

- **SOP 段名**：`src/main/java/com/opspilot/ingest/IngestionRunner.java:491` 用
  `breadcrumb().contains("止损操作")` 决定哪些 chunk 预热为 L2 静态 SOP。
- **诊断段名 / 排除词**：`src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java:40`
  用 `SECTION_MARK = "排查"` 决定哪些段能进只读行动契约，`:44` 的排除词（陷阱/误判）同样是
  中文领域词。它与 SOP 段名是同一类知识的第二份副本。
- **语料路径**：`IngestionRunner.java:90` 与 `src/main/java/com/opspilot/config/MockIdfInitializer.java:29`
  各自写了一遍默认值 `offline/corpus/chunks.jsonl`，同一事实源有两份。

Prompt/文案里也有同类焊点（`PromptAssembler` 的角色/结构/拒答、`ChatOrchestrator:315/351`、
`MockEngine` 的 mock 文案），但**本 ADR 不把它们放进这一刀**：提示词与逐字护栏、拒答口径、
面板协议头纠缠最深，先把"领域包"的形状用一个不碰安全文案面的最小切片证明出来，再另立契约
处理文案。否则第一次提接口就同时动语料、SOP 与生成面，回归锁会一次碎太多。

## 决策

### D1 选域：数据库值班（DBA）

判据四项，逐项给仓内事实（不是设计愿望）：

| 判据 | 仓内事实 |
| --- | --- |
| 已有真实消费者 | ① `src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java:158-171` 有一条**专为数据库内容**建的 SQL 判定分支（`SQL_READ_VERBS` / `SQL_WRITE_VERBS`），`ReadOnlyCommandPolicyTest` 用 `SHOW FULL PROCESSLIST` / `SHOW ENGINE INNODB STATUS\G` / `... FOR UPDATE NOWAIT` 做回归；② `ReadOnlyActionExtractor.java:53-54` 的 `STATEMENT_LANGS` 已认 sql/mysql/pgsql/plsql/tsql/cql/hql，并为多行 SQL 并句（否则 `FOR UPDATE` 从句会被切掉）；③ 语料 **41 个 chunk** 带数据库错误码（50012/50013/50101/50141），**5 篇** DB runbook/复盘（rb-001、rb-002、ac-508、pm-001、pm-002） |
| 已有真实数据 | 真实语句与异常：`SHOW ENGINE INNODB STATUS\G`、`SHOW FULL PROCESSLIST`、`EXPLAIN`、`ANALYZE TABLE`、`SELECT ... FOR UPDATE NOWAIT`、`Deadlock found when trying to get lock`；真实止损动作：`KILL <processlist_id>`（rb-001）、单表 analyze（ac-508）、按加锁顺序还原（pm-002） |
| 已有真实运维动作 | 上述动作都是 DBA 值班的日常动作，且**已经在语料里**；本切面不是先造场景再找内容 |
| 不会把领域词焊回底座 | 反方向：今天 SQL 词表**就在底座里**（`ReadOnlyCommandPolicy`）。本切面不改它，但把"哪些段名属于领域"移出去，并在 D7 登记 SQL 词表的移出触发条件 |

**为什么这是第三个真实用例，而不是为凑数造的玩具**——反事实检验：

1. 删掉本切面新增的 DBA 语料目录，底座**今天就已经在为数据库内容服务**：SQL 判定分支、
   `STATEMENT_LANGS`、41 个 DB chunk、5 篇 DB 文档全部在跑。DBA 包不是新造一个场景，而是给
   一个已经在跑、但没有名字和边界的领域补上名字与边界。
2. 反过来，如果只是"把 SRE 文案换个词"，删掉它 SRE 仍然完整——那才是玩具。本设计用三条硬约束
   挡住这种退化：命令判定机制零改动、契约里的 `source_incident` 必须指回既有事故、以及
   `check_domain_words.py` 的领域词门闩。
3. 它不需要新增任何机制才能跑通（ingest / SOP / 检索全在），需要新增的只是"命名与边界"——这
   正是"插件"的定义。反例：CI/CD 候选需要先造命令与语料（见备选表），那才是玩具风险。

**为什么它不是 SRE**：数据库值班的读者、错误词汇（MySQL errno / SQLSTATE）、动作（SQL 语句
而非 shell/kubectl）、止损对象（锁、统计信息、连接）都不同。本包以 DBA 值班的口吻重写这些
事件，但保留原始事故出处（`source_incident`），不发明事故、不发明错误码。**它不复用 SRE 语料
根**：同一份内容改个 tenant 不构成第二领域；重写的正是"同一个事故在 DBA 眼里该看什么、该动
什么"——这一步是领域内容的实质，不是改名。

### D2 薄切片范围：只动两件

| 契约 | 本切面 | 说明 |
| --- | --- | --- |
| 语料来源 | **动（数据）** | 第二份语料根 `offline/domains/dba/corpus/`，用**既有** `offline/chunkers/build_chunks.py --corpus ... --out ...` 生成（该 CLI 已支持两个参数，无需改代码）；manifest 声明默认路径 |
| SOP / Runbook | **动（数据）** | 止损段、诊断段、排除词从 pack 读；`IngestionRunner` 与 `ReadOnlyActionExtractor` 共用同一份声明。SOP 的键形状、TTL、租户分片、预热时机不动 |
| Prompt / 文案 | **等** | 本切面**零改动** `PromptAssembler` / `ChatOrchestrator` / `SseEvents` / `OpenAiChatSink` / `MockEngine`。触发条件：薄切片通过独立验收、领域包形状被证明后，另立文案契约 |
| 指纹归一化 | **等** | 第三种规则（保留 MySQL errno / SQLSTATE）是**上下文相关**的："1213"是错误号还是订单号只能靠周边文本判，不能表达为闭集形状。DBA 探针只用应用错误码 |
| 命令族 | **不动** | 已由首个切面外置；本切面**命令判定机制零改动**（`ReadOnlyCommandPolicy`/`ControlledCommandPolicy`/`CommandPolicyData`/`command-policy.json`），只改 `ReadOnlyActionExtractor` 的段名来源。DBA 语料诊断段命令必须命中现有 L0 判据，否则内容移进止血段 |

**这次不解锁什么**（逐条写死，评审时不得口头放宽）：

1. **不解锁 Prompt/文案外置**：生成面、拒答口径、面板协议头都不碰；`VerbatimGuard` 不动。
2. 不提取 SPI / `ServiceLoader` / Java 接口；pack 仍是编译期固定路径 + 严格 schema。
3. 不拆 Maven 模块；不做同进程多域共存；不做按租户/请求切换域；不做热加载/HTTP 上传。
4. 不新增 L1 家族；不改 L0 `TOOLS`/`VERBS`；任何 pack 不得携带 `KILL`/DDL/DML。
5. 不动指纹归一化（原因见上表）。
6. 不把 `ingest/` 或 `action/` 加进现有词法门闩的扫描面（见 D7；`ingest/` 的纳入是后续小改动）。
7. 不新增 `dba` 角色/账号维度：领域是**部署选择**，不是权限维度；角色仍是 sre/dev/manager。

### D3 落位：classpath 领域包 + 启动期显式注入（`opspilot.domain`）

目录与类草案（文字，不写实现）：

```
src/main/resources/domain/
  sre/domain.json          # id / schema_version / corpus 默认路径 / 三个段名集合
  dba/domain.json

src/main/java/com/opspilot/domain/
  DomainPack.java          # 不可变 record；字段仅 id / corpusPath /
                           #   stopLossMarkers / diagnosticMarkers / diagnosticExcludes
  DomainRegistry.java      # @Component；按 opspilot.domain 解析并把 DomainPack 注入消费者

offline/domains/dba/
  corpus/runbooks/*.md
  corpus/postmortems/*.md
  corpus/chunks.jsonl      # 既有 chunker 产出
```

`opspilot.domain` 是**配置期事实**，默认 `sre`，**不自动回退**（与 ADR-0022 `storage.mode`
同款口径：未知 id、空值、含路径分隔符 → 启动失败并点名）。领域选择是单值，不是叠加，也不是
租户属性。

**显式注入，没有静默回落**：

- 生产路径只有一条：`DomainRegistry` 在启动期解析出唯一的 `DomainPack`，由 Spring 构造器
  注入 `IngestionRunner` / `MockIdfInitializer` / `ReadOnlyActionExtractor`。解析失败即启动
  失败——不存在"没配置就用 SRE"的全局默认。
- **不设** `DomainPack.current()` 之类的全局单例；未初始化状态在类型上不可表达（消费者拿不到
  pack 就构造不出来）。
- **旧构造器保留为显式重载**：旧签名委托到 `this(..., DomainPack.sre())`，`DomainPack.sre()`
  从 `domain/sre/domain.json` 严格解析（不是硬编码常量）；重载在 Javadoc 标注
  **test-only / 兼容路径**。生产 bean 只走带 `DomainPack` 参数的构造器（该构造器标
  `@Autowired`，由 `DomainRegistry` 供给 pack）。
- 这样既有测试文件**零 diff**（不用改构造器调用），断言也更不可能被"顺手改绿"；它不是全局
  fallback——没有调用点能"忘了传 pack 而悄悄拿 SRE"。
- 未知 id、空值、缺 manifest、坏 schema → `IllegalStateException`，点名 id 与字段。

**否决的落位形态**：

| 形态 | 否决理由 |
| --- | --- |
| 独立 Maven 模块 | 需要先把 core 抽出：453 个测试、单 jar 发布、`release.sh`/`rollback.sh` 产物全要改。当前没有仓库外的插件作者，拆了只增加构建面。等真出现"别人写插件"再拆 |
| Spring profile | profile 已承载部署形态（ADR-0025），可组合、可被环境变量叠加；把领域塞进 profile 会让"这次跑的是哪个领域"取决于变量组合（`prod,alerting,dba` 是否合法？）。领域是单选，不是叠加 |
| 运行时装配（下载/上传 jar 或 JSON） | 新增攻击面与版本兼容债务，与"classpath 固定路径、未知键启动失败"的既有口径冲突；ADR-0029 决策 3 已否决 SaaS 形态 |
| 全局静态 `current()` + 未初始化回落 SRE | 静默回落会让"配错了"看起来像"跑对了"；与 ADR-0022"不自动回退"的口径冲突。兼容单测用显式夹具，不用全局默认 |

**不 fork 仓库替换插件**：新增 `src/main/resources/domain/<id>/domain.json` 与
`offline/domains/<id>/corpus/`，设 `OPSPILOT_DOMAIN=<id>`。前提是该领域**不需要新的 L0 工具
或 L1 家族**；需要的话是代码评审变更，不是数据变更——这条要写进 README / repo-map，不能含糊
成"完全可插拔"。

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

### D4 安全不变量：数据只能**收紧**，且没有静默默认

- **结构性约束，不靠词表**：段名集合必须非空；`diagnosticMarkers` 与 `stopLossMarkers`
  **不得相交**（否则一个段同时是"可抽取"和"已止血"，段名闸会被自己的数据绕开）；段名不得含
  正则元字符（`.*`、`(`、`|` 等）——匹配是精确子串，不引入求值面。
- **路径约束**：`corpusPath` 必须是 `offline/` 下的相对路径，拒绝绝对路径与 `..`；manifest 只
  从固定 classpath 读，不从 HTTP/租户覆盖。既有的 `opspilot.chunks-path` 运维覆盖仍保留（它
  不来自 pack，也不改变领域/段名的选择，属部署者可信配置）。
- **严格加载**：未知 `schema_version`、未知顶层/嵌套键、空段名列表、段名相交、段名含正则
  元字符、缺 manifest、`corpusPath` 越界 → **启动失败**；不静默忽略、不回落默认值。
- **显式注入**：没有全局 fallback；解析失败即启动失败（D3）。
- **零执行 / 零权限变化**：本切面不碰 `action/` 的命令判定机制、`auth/`、`audit`、`store`、
  `retrieval`、`llm/`；引擎层权限过滤、审计哈希链、降级状态机、SOP 租户分片、逐字护栏全部
  不变。

**必须继续留在底座（本切面不搬）**：

- 引擎层权限过滤（`IdentityFilter`）与 `tenant × auth_level` 闸；
- 审计哈希链与 WORM 门禁；
- 降级状态机与档位语义；
- `VerbatimGuard` 与出口逐字口径；
- 只读命令四层判定、注入门、L1 双确认/签名派发/状态机；
- SOP 键形状/TTL/租户分片、L1/L2 缓存键与身份；
- `## 问题定位` 协议头与全部 Prompt/文案（本切面不碰）；
- 所有 loader 的 fail-closed schema 与允许的行为枚举（`TailShape` 等）。

pack 换的是"内容"，不是这些防线；任何让底座为迁就 pack 而放松上述构造保证的提案，直接否决
（ADR-0029 决策 5 同源）。

### D5 接缝地图（具体接到哪一行）

| 接缝 | 现状 | 本切面 |
| --- | --- | --- |
| 语料路径 | `IngestionRunner.java:90`、`MockIdfInitializer.java:29` 各一份默认 | 两者都从注入的 `DomainPack.corpusPath()` 取默认；`opspilot.chunks-path` 仍可覆盖（运维口子保留） |
| SOP 段名 | `IngestionRunner.java:491` 字面量 | `DomainPack.stopLossMarkers()`；多标记任一命中即预热；Redis 键/TTL/租户分片不动 |
| 诊断段名 / 排除词 | `ReadOnlyActionExtractor.java:40`、`:44` 字面量 | `DomainPack.diagnosticMarkers()` / `diagnosticExcludes()`；抽取与只读判定逻辑不动 |
| 领域选择 | 无 | `DomainRegistry` + `opspilot.domain`（默认 `sre`），构造器注入 |
| Prompt / 文案 | `PromptAssembler`、`ChatOrchestrator`、`MockEngine` | **本切面不动**（见 D2） |
| 指纹 | `FingerprintService.java:30-35` | **不动**；DBA 复用同一归一化 |

### D6 回归锁（实现必须保持）

| 构造保证 | 锁 |
| --- | --- |
| 只读命令契约 | `ReadOnlyCommandPolicyTest`（18 条）+ 本切面**命令判定机制零 diff** |
| L1 白名单 | `ControlledCommandPolicyTest`（8 条）+ 灰度 fail-closed（`ActionOpsServiceTest` 13 条） |
| 段名闸 | `ReadOnlyActionExtractorTest`（10 条；DBA pack 下只换段名来源，判定不变） |
| 逐字护栏 / Prompt | `VerbatimGuardTest`（10 条）+ `PromptAssemblerTest`（4 条，**零 diff**） |
| 权限 / 租户 | `IdentityFilter` 跨租户用例 + 红队跨租户类 |
| 审计链 | `AuditService` 链式写入 + `scripts/l1_worm_gate.py` 口径不变 |
| 降级 | 降级状态机单测 + 审计 `ev=degrade_transition` |
| 端到端 | `bash scripts/redteam.sh` 32/32、0 泄漏、0 SKIP（默认 sre 包） |
| 领域词 | `python scripts/check_domain_words.py` 退 0（复用 main 的门闩，**不修改**） |

### D7 解锁与遗留、词法门闩

**解锁（可以另立契约提接口）**：

- **语料来源**：SRE 语料根、tenant-acme 语料（同一领域的第二租户）、DBA 语料根——同一个
  "从 JSONL 读、派生索引"的接口点已有三份数据供给。
- **SOP / 段名**：SRE 止损段、ADR-0028 的 L1 runbook 止损段（同一个标记）、DBA 的
  `止血与恢复`——三个真实消费者，且第三个是**不同取值**，这是"策略"而非"常量"的证据。
- 以上两件**可以另立契约提接口**，但本切面只做**数据化**，不提取 SPI：接口形状要由第三个
  **实装**而不是第三份数据逼出来。

**仍要等**：

- **Prompt/文案**：等薄切片验收后另立契约；`PromptAssembler` 的角色/结构/拒答、面板协议头、
  mock 文案都原样留在底座。
- 指纹归一化：第三种规则需要上下文相关解析（errno ≠ 任意数字），先要独立设计。
- 命令族：L0 `TOOLS`/`VERBS` 是安全边界而非内容（放宽 = 扩权），命令判定机制保持现状。
- 真正的 SPI / 独立模块：等仓库外的插件作者出现。

**`scripts/check_domain_words.py`（PR #86 已合并，本切面复用、不修改）**：

- 现有扫描面是 `storm` / `auth` / `cache` / `store` / `metrics` / `llm/VerbatimGuard` /
  `resilience`（除 `SopFallbackService`），**不含 `ingest/` 与 `action/`**。
- 现有两条例外是**既有注释**里的举例，原样保留：
  `auth/UserAdminCli.java:205` 的 `sre-full`、`metrics/AuditService.java:129` 的 `kubectl`。
  因此默认模式退 0（2 条 `EXEMPT`），而 `--strict` 仍会因这两条退 1——这是既有状态，**不属于
  本切面**，本切面不改脚本、不改测试、不改 CI。
- 本切面只证明：移走段名后，`ingest/` 与 `ReadOnlyActionExtractor` 里那三个字面量零命中
  （直接 grep，见契约 AC-3）。把 `ingest/` 纳入门闩扫描面是**后续小改动**，不在本切片。
- `action/` 仍不纳入：`TOOLS`/`VERBS`/SQL 词表还是底座内容，立闸会对合法机制假红。纳入条件
  沿用 R1："第一个非 SRE 命令家族出现，且家族尾部校验已移到策略接口"——本切面没有新家族。

**文档计数影响**：ADR 数 31（已随本草案同步）；新增 `@Test` 会改 README / repo-map 的单测数；
DBA 语料放在 `offline/domains/` **不进** `offline/corpus/` 的 glob，故 `chunks_lines`、
`postmortem_runbook_docs`、`offline/provenance.py` 的 SRE 计数不漂移。

## 备选与否决

| 备选 | 否决理由 |
| --- | --- |
| 一次做全五件（含 Prompt/文案） | 首刀同时动语料、SOP 与生成面；提示词与逐字护栏/拒答口径/面板协议头纠缠最深，回归锁一次碎太多。先证明 pack 形状，再提单件契约 |
| MQ 值班 | 真实数据够（rb-004 的 `sh mqadmin`、50031/50032、pm-005/006），但归一化策略与 SRE 相同（第三种规则欠奉）；且 `sh mqadmin ...` 今天被 L0 拒是一个**既有缺陷**，应由"L0 工具族"自己的契约修——拿它当第二领域会把"补洞"说成"插件" |
| CI/CD 发布值班 | 概念边界最干净，也是 ADR-0028 声明的派发目标（Argo/Rundeck）；但仓内没有发布事故语料，`argocd`/`helm` 零命中，内容只能现造——正是本 ADR 要避开的玩具风险。等有第一份真实发布事故记录再立项 |
| tenant-acme 的第二语料 | 那是**第二个租户**不是第二个领域：同一套 SRE 词汇与段名，且跨租户红队依赖它保持原样 |
| 只写文档、不改接缝 | "只写文档不提接口"的反向错误：领域内容仍焊在底座，换域仍要改 Java |
| 让 pack 自带正则/脚本做段名替换 | 数据即代码：正则与求值会变成运行时行为面，违反 D4 的"精确匹配、严格加载" |

## 后果

- **正面**：底座第一次能用**不 fork 仓库**的方式换一个领域的语料与段名；`ingest/`、
  `ReadOnlyActionExtractor` 里两处段名词离开底座；SRE 包从"唯一实现"变成"第一个参考实现"；
  薄切片把安全面变化压到最小（命令判定、Prompt、指纹、权限全不动）。
- **代价 / 风险**：多一份语料要维护与复核；DBA 包没有 golden 集（本切面只做固定查询探针）；
  领域选择是启动期单选，不能同进程多域；Prompt/文案仍是 SRE 形状（薄切片明确不碰），
  `MockEngine` 的 mock 文案与段名分类也仍在底座；指纹仍会合并 1213/1205 这类 MySQL 错误号
  （已登记，DBA 探针规避）。
- **文档门闩**：ADR 数 31（已随本草案同步）；README / repo-map 的单测数与包地图、OPS §5 的
  债务行随实现同步；契约草案见 [plans/second-domain-accept.md](../../plans/second-domain-accept.md)。

## 开放问题（需 Lead 拍板，冻结前）

1. **选域**：确认数据库值班为第二参考实现。若更想要 CI/CD，最小范围会变成"命令族 + 文案"，
   且内容只能现造（见备选表）。
2. **`opspilot.domain` 语义与注入**：配置期单选、默认 `sre`、未知即启动失败、生产路径只走
   `DomainRegistry` 构造器注入、无全局 fallback——是否照此冻结？
3. **语料真实性门槛**："源自已有事故 + 每篇 `source_incident` 指回原 doc/OPS 段"是否足够；
   还是必须先有第一手 DBA 值班记录才冻结？
4. **下一份契约的顺序**：Prompt/文案外置、`ingest/` 纳入词法门闩、指纹归一化——先做哪一个？

## 与 ADR-0029 的关系

- 本 ADR **不修改** ADR-0029 的任何判据；它是决策 4"第三个真实用例"的薄切片落位提案。
- **计数口径澄清**（不改原文）：ADR-0029 D4 的 #1=排障、#2=ADR-0028 执行闭环；ADR-0030 的
  统计基线预警是**生产者插件**（只消费链路、不提供领域内容），不构成内容契约的第三个用例。
  本 ADR 的 DBA 包是内容契约的第三个用例。
- 本 ADR **不放宽** R1 的 `action/` 门闩条件，也不预支 R2/R4 的任何放宽；门闩脚本本身不在本
  切片的改动面内。

## 评审与签收（未发生）

本 ADR 为薄切片草案。评审须由独立会话按 D1–D7 与仓内代码事实逐条核对；签收前不得派实现。
