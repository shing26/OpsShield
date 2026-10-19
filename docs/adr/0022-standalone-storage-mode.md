# 零依赖存储模式：显式开关、内存实现复用同一套权限过滤

状态：accepted（2026-10-19）

上下文：今天要跑起来这个系统，门槛是 **Docker + 全栈 compose**（`scripts/quickstart.sh`：
redis / qdrant / elasticsearch + 网关镜像，首建 2–5 分钟）。评审者或新同事想"先看一眼它是干什么的"，
得先过这道门。两个实测事实说明这件事的边界在哪：

1. **模型层已经不依赖外部**：`opspilot.dashscope.mode = auto|live|mock`（带枚举校验）在没有 API key 时
   自动切 `MockEngine` 词法后端，启动摘要里打印 `mode=live|mock`。硬依赖只剩 **ES + Qdrant + Redis**。
2. **换掉这三件不是"加个实现"**：`HybridSearchService` 直接依赖具体类 `EsSearchService` /
   `QdrantSearchService`（无接口接缝）；`RedissonClient` 有 8 个消费面——L1 缓存、滑动窗口（去重计数）、
   配额水位、**登录失败计数（401/429 锁）**、SOP 预热、健康探针、ingest、审计关联。其中登录失败计数与
   配额是**安全/成本面**，不是便利功能。

决策：**新增一个零依赖存储模式，用显式开关触发，不自动回退。**

1. **开关**：`opspilot.storage.mode = live|memory`，**默认 `live`**（缺中间件即启动失败，与今天行为一致）。
   形状照抄 `dashscope.mode`（枚举校验 + 启动摘要打印），不新造一套 Spring profile 机制。
2. **不自动回退**：启动探测到中间件缺失时，**不静默切换**，只打印一条可执行的提示
   （"如需零依赖体验，加 `--opspilot.storage.mode=memory`"），然后照常失败。
3. **覆盖面**：memory 模式下 ES / Qdrant / Redis **三者全部**由内存实现承接——只盖检索不盖 Redis，
   就达不到"什么都没装也能跑"，而半零依赖最容易误导人（以为不用装东西，最后卡在 redis 上）。
4. **复用同一套权限过滤与只读策略**：内存实现**不得另写一份** `tenant × auth_level` 过滤或命令只读
   判定，必须复用既有代码路径。否则演示出来的权限隔离与生产不是一回事。
5. **显式标注**：启动摘要、页面、审计里标 `backend=in-memory`；README 明写该环境**不承诺任何检索
   质量数字**（沿用现有对 mock 后端的口径）。

理由：

① **两种信号的性质不同**：key 的有无是**配置期事实**，一次定死；中间件可达性是**运行期事实**，会抖。
把 `auto` 套到会抖的信号上，会出现"同一台机器、同一份代码、两次启动跑在不同后端"，而页面与审计里的
数字长得一模一样——这正是这个仓库反复在防的那类**假绿**（`liveness.py` 坚持校验 `OpsShield` 身份、
health 探针不信别人家的 UP，都是为这个立的规矩）。Docker Desktop 刚启动时中间件慢三秒是典型场景，
自动回退会让你得到一个跑在 448 条内存语料上的实例，而它自己认为一切正常。
② **显式开关让"我这次跑的是内存后端"变成一条可 grep 的事实**。
③ **第 4 条不是洁癖**：内存实现若自带一份过滤逻辑，它就成了一条**绕开权限引擎的旁路**；而权限隔离
恰恰是这个系统最贵的能力（ADR-0008）。

备选与否决：

| 被否项 | 理由 |
| --- | --- |
| `storage.mode=auto`（探不到就切内存） | 见理由 ①：把配置期信号的处理方式套到运行期信号上。 |
| 只盖检索、Redis 仍要求 | 达不到目标场景；且"半零依赖"会误导人卡在 redis 上。 |
| 把它做成降级档 Level 3 | 降级档的语义是"同样的链路在压力/故障下的取舍"，而内存实现是**换了一条链路**。记成 L3 会污染 `degrade_transition` 审计与可用性叙事。 |
| 静态演示页（预生成答案 JSON，不起网关） | 直接撞 ADR-0009 的诚实性红线：那是假数据演示。 |
| 不做，只把 compose 门槛写清楚 | 保留选项，但不解决"电脑上什么都没装"这个场景。 |

后果：

- **接缝**：检索腿与缓存层要先引入接口或 `@ConditionalOnProperty` 装配点——动的是核心类，
  这一步要单独评审。
- **必须补"两套实现行为一致"的对照测试**：同一条 query 在 live 与 memory 下，低密级侧的引用集都
  不得出现高密级 chunk（权限过滤是构造保证，不是两套实现的各自承诺）。
- **数字口径**：memory 环境不得进入任何"核心指标"表；`doc_numbers.py` 登记的产物数字仍以 live/随附
  产物为准。
- **规模**：检索腿约 300–500 行、Redis 替身约 400–700 行，加装配接缝与对照测试，是一次一到两天的
  改造，且**不阻塞**统一工作台（ADR-0021）与输入辅助面的改造，可独立排期。

相关：ADR-0005（账号体系与凭据边界，登录失败计数的语义）、ADR-0008（每条共享路径都有权限维度）、
ADR-0009（只读可观测面与诚实性红线）、ADR-0012（降级代价语义，本模式刻意不并入）；操作面见 OPS。

## 注记（2026-10-19 落地实测）

五条决策一字未改地实现了。下面记的是**实现里才撞出来、正文没写到**的东西，以及实测判定。

**两条接缝的形态**：

- **存储面**收成一个窄接口 `store/KvStore`（15 个操作：`getString`/`setString`/`deleteByPrefix`/
  `increment`/`readLong`/`delete`/`windowRegister`/`mapPut`/`mapGet`/`mapIsEmpty`/`mapValues`/
  `setAdd`/`setMembers`/`exists`/`backend`），两个实现 `RedisKvStore` / `MemoryKvStore`，由
  `StoreConfig` 按 `@ConditionalOnProperty` 装配。八个 Redisson 消费面（L1、滑动窗口、配额水位、
  **登录失败计数（401/429 锁）**、SOP 预热、健康探针、ingest、审计关联）全部改吃这个接口——其中
  登录失败计数与配额是**安全/成本面**，故内存实现必须复刻语义而非"够用就行"（决策 3 的理由在实现
  里第一次变得可执行）。`MemoryKvStoreTest` 9 例锁的正是这些语义：TTL 仅建于创建、闭区间窗口裁剪、
  成员去重、`mapValues` 无顺序承诺。
- **检索面**收成一个接口 `retrieval/RetrievalLeg`，ES 与 Qdrant 各自实现它，内存腿（`InMemoryLegs`
  的词法腿 + 向量腿）实现同一接口，由 `RetrievalWiring` 提供 `lexicalLeg`/`vectorLeg` 命名 bean，
  `HybridSearchService` 按 `@Qualifier` 注入。**决策 4（复用同一套权限过滤）落地为 `retrieval/IdentityFilter`**：
  `TENANT_FIELD`/`AUTH_LEVEL_FIELD` 常量与 `matches()` 的 fail-closed 判据是**单一事实源**，检索与
  覆盖范围共用——内存实现无法另写一份过滤，因为过滤逻辑根本不在它手里。`IdentityFilterContrastTest`
  5 例就是决策后果要求的"两套实现行为一致"对照测试。

**一个正文没写、但必须做的属性归一化**：`Storage` 这个 record 把 `null → "live"`（缺省即历史行为），
且 `storage()` 访问器带 null-guard。理由是 `@ConfigurationProperties` 的 relaxed-binding 在缺键时给
`null` 而不是默认值——不归一化，`mode` 判空散落在装配点，等于把"默认 live"这条决策拆成好几处。规范
构造器加 `@ConstructorBinding`，另留一个 8 参便捷构造器给单测。

**落地实测咬到的三个坑（都已在代码 / `application.yml` 注释里留档）**：

1. **Boot 的 `ElasticsearchRestClientHealthIndicator` 是"按 classpath"注册的**（不是按 `RestClient`
   bean）——"memory 模式不建 ES 客户端"挡不住它，它照样 ping `localhost:9200` 把 `/actuator/health`
   拖成 **DOWN**。**处置**：`management.health.elasticsearch.enabled: false`。关掉它不损失任何能力：
   本项目自己的 `elasticsearchClusterHealth` 做同一件事且更准（走别名、带 socket 超时、报 docs 计数），
   而 `demo.sh` / `liveness.py` / compose healthcheck 读的正是聚合状态。
2. **`RedisAutoConfiguration`（Lettuce）同族问题**——它按 `spring.data.redis.*` 建 `RedisConnectionFactory`，
   Boot 据此注册 `RedisHealthIndicator`，**无条件 ping Redis**，memory 模式下同样把 health 拖成 DOWN。
   本项目从不使用 `RedisConnectionFactory`（唯一消费者是 RedissonClient），故与
   `RedissonAutoConfigurationV2`（它会在 memory 模式下照样**急连** Redis）一起在 `spring.autoconfigure.exclude`
   里排除。**这两个排除不是顺手加洁癖，而是零依赖路径的存活前提**：health 判 DOWN，整条路径会被
   `demo.sh` / `liveness.py` 判"没起来"。
3. **装载时机**：内存索引装载一度挂在 `ApplicationRunner`（**Tomcat 开始服务之后**），于是有一个窗口
   检索为空、排障台对任何 query 都回"无相关参考"——**看起来像拒答，实际是索引还没进内存**。移到
   `IngestionRunner.afterPropertiesSet`（`InitializingBean`）后窗口消失。代价如实登记：启动多花约
   0.2s（448 块，170–204ms），换来"服务一旦可访问，检索就是满的"。

**模型轴 vs 存储轴的诚实对照（正文决策 5 的一个反例边界）**：ADR 预期"memory 环境不承诺检索质量"，
实测发现**拒答门控的行为会随模型轴变、与存储轴无关**——`DASHSCOPE_MODE=mock` 下域外提问
（"今天天气怎么样"）**不被拒答**（词法后端给任何文本都打正分），同一份内存语料换 `DASHSCOPE_MODE=live`
立刻恢复拒答（3 个 `near_miss`）。这是 mock 后端 IDF 覆盖分的**假象**（§5 债务表"域外/无信息量 query 的
第二道门"一行已记同源现象），**不是内存实现的缺陷**。README 的零依赖试跑块据此写清：想要完整演示用
`memory + live`，想完全离线接受这条差异。

**数字口径（决策后果第 3 条的落地）**：memory 环境**未进入任何核心指标表**；`doc_numbers.py` 登记的
产物数字仍以 live / 随附产物为准。`/api/v1/admin/health` 在 memory 模式下自报
`{"redis":{"detail":"in-memory kv…"},"qdrant":{"detail":"448 pts (in-memory)"},"es":{"detail":"448 docs (in-memory)"},"storage":"memory","status":"UP"}`，
启动摘要打印 `storage=memory backend=in-memory`——"这次跑的是哪条链路"是可 grep 的事实。

**门禁与活体复核**：`mvn -B test` **294/294**（新增 `MemoryKvStoreTest` 9 / `StoreWiringTest` 5 /
`IdentityFilterContrastTest` 5 / `InMemoryIndexTest` 8，另改 6 个既有测试类接新接缝）；
`pytest offline/tests/` **304 passed**；`check_panel_contract.sh` / `doc_numbers.py --check` /
`provenance.py --check` 全绿。**两模式活体实测**：memory + `DASHSCOPE_MODE=mock`、三个中间件**全停**时
网关启动、`/actuator/health`=UP、索引装载 448 块 170–204ms、`preset_probe.py` **27/27 PASS**；live 形态
恢复后 `demo.sh` 全 PASS、`preset_probe.py` 27/27。

**未做（如实登记）**：memory 实现**继承了** `IngestionRunner.warmSop` 的既有 P0（SOP 只按 tenant 分区，
不按 auth_level 过滤，见 [ADR-0020](0020-troubleshoot-console-operator-surface.md)）——内存模式**不得**
被宣称"SOP 已按密级过滤"。

**两条边界（2026-10-19 code-review 补记，不改上面的决策）**：

1. **决策 4 的"共享过滤"只覆盖检索面**：`IdentityFilter` 是检索腿与覆盖范围的单一事实源，但 **L2 语义
   缓存的 memory 侧**在 `L2SemanticCacheService.lookupMemory()` 里另写了一份 `tenant` 等值 + `max_auth_level ≤`
   的 Java 判据（live 侧那份在 Qdrant 的 filter DSL 里，两后端本就无法共用同一段代码）。现有
   `IdentityFilterContrastTest` 只锁检索面，**未锁 L2 缓存面**。触发线：出现一次"L2 缓存串租户/串密级
   命中"且被复现 → 立项抽一份两后端都能调的可见性判据，或给 L2 缓存面补对照测试。
2. **决策 5 的"审计里标 backend"未落地**：启动摘要（`StartupConfigSummary`）与 `/api/v1/admin/health`
   都标了 `backend=in-memory`，但**审计行没有**存储后端标记——单看 `logs/audit.jsonl` 一行，分不清它
   产生于 live 还是 memory。触发线：出现一次"审计面需要按后端分账"的需求（如 memory 试跑期的数字被
   混入 live 口径）→ 给审计行加 `storage` 列。
