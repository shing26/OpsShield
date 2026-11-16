# ADR-0029：可复用边界——信任底座与领域插件的显式分层

日期：2026-11-10 ｜ 状态：accepted（2026-11-16 评审；Lead 已签收。首个切面已落地——L1 命令策略
内容外置，PR #85；其余分层仍未落地，契约见 [plans/command-policy-data-accept.md](../../plans/command-policy-data-accept.md)）｜
关联：[ADR-0013](0013-trust-base-for-agents-not-an-agent.md)（可信底座定位）、
[ADR-0015](0015-distributed-primitives-interface-abstraction.md)（接口抽象纪律）、
[ADR-0028](0028-l1-controlled-action-contract.md)（执行闭环——第一个被插件化的需求）、
[ADR-0022](0022-standalone-storage-mode.md)（零依赖试跑）

## 背景

项目目标升级：从"OpsShield 这一个排障网关"升级为"**可复用的网关/agent 信任底座**"，OpsShield
退为第一个参考实现。这不是推翻 ADR-0013 的定位，而是把"底座"的适用域从"排障场景的底座"
扩大为"跨场景的底座"——底座的三处稀缺性（引擎层权限硬过滤、逐字出口护栏 + 溯源合同、
同指纹风暴收敛）没有一个字依赖"排障"这个领域。

这条缝今天**事实上存在、文档上不存在**：包结构里 `storm/`、`resilience/`、`auth/`、`cache/`、
`store/`、`metrics/`、`llm/VerbatimGuard` 几乎不含领域词汇；而语料（`offline/corpus/chunks.jsonl`）、
去重键归一化规则（掩时间戳/UUID 保留错误码）、L1 白名单前缀（kubectl/systemctl）、Prompt 模板、
静态 Runbook、预设问题都焊在 SRE 场景上。缝没画出来的风险在 ADR-0028 落地时兑现：
runbook 执行引擎与动作白名单天然要回答"谁是底座、谁是插件"——不先画缝，就会做出
焊死在排障场景的执行引擎。

**为什么不提前抽象**：ADR-0015 的纪律——接口由真实第二个用例逼出来，不提前背抽象成本。
本 ADR 不批准任何"现在就重构"的动作，只批准边界判据与演进规则；第一刀留给 ADR-0028
的真实需求。

## 决策

1. **分层判据（可静态判定）**：一个模块属于**底座**，当且仅当它的判据与逻辑不含任何
   领域词汇（错误码、产品命令、领域文案）；属于**领域插件**，当它向底座**提供**领域内容。
   判据落点：底座包（`storm`/`resilience`/`auth`/`cache`/`store`/`metrics`/`action` 的机制层）
   与领域内容之间出现词汇渗透即分层失守——此判据未来可升级为 CI 词法门闩（领域词表 +
   底座包 grep），在第一个插件真正落地时再立，本 ADR 只登记判据。

2. **插件契约五件事**（底座只认接口，不认实现）：

   | 插件须提供 | 底座对应的接口点 | 现状 |
   | --- | --- | --- |
   | 语料来源（chunk + 元数据：tenant/auth_level/action_class 声明） | `ingest/IngestionRunner` 只读 chunks → 派生索引；ADR-0001 的 JSONL 分界 | 已是接缝，未声明为契约 |
   | 去重键归一化策略（掩什么、留什么） | `storm/FingerprintService` | 归一化规则与错误码耦合，待提为策略接口 |
   | 命令策略白名单（L0 工具白名单 + L1 前缀闭集） | `action/ReadOnlyCommandPolicy`、`ControlledCommandPolicy` | 判定机制是底座，白名单内容是插件数据 |
   | 静态兜底提供方（Runbook） | `resilience/SopFallbackService` | 内容焊死在 SRE 语料 |
   | Prompt / 对外文案模板 | `llm/PromptAssembler` | SRE 文案，待提为模板契约 |

3. **复用形态：框架，不做多租户 SaaS 产品**。复用方式是"自己跨场景部署 + 他人 fork 后
   换插件"，不是"别人开通账号用"。否决 SaaS 形态的理由：部署边界、文档、版本兼容、
   数据隔离的持续债务与当前时间预算冲突，且会把 ADR-0025 strict 模式的"部署者自担"
   口径冲掉。框架形态下 OpsShield 保持第一参考实现的地位不变。

4. **演进规则：rule of three，绞合式演进**。不批准一次性重写；某个接口点在**第三个真实
   用例**需要它时才提取为契约（第一个用例=排障，第二个=ADR-0028 执行闭环的 runbook
   引擎落位，第三个出现时才提接口）。每次提取独立走验收链路。

5. **复用不变量：安全叙事不随复用弱化**。引擎层权限过滤、审计哈希链、降级状态机、
   逐字护栏、只读命令契约这五项构造保证在换插件时**不得降级**——插件换的是"内容"，
   不是"防线"。任何让底座为迁就插件而放松构造保证的提案，直接否决（与 ADR-0008
   "权限是数据层不变量"同源）。

## 备选与否决

| 备选 | 否决原因 |
| --- | --- |
| 一次性重写为通用框架 | 全部回归锁失效重来——与 ADR-0015 否决"直接上分布式实现"同一裁决 |
| 不分层，每个新场景 copy 一份仓库 | 安全修复要同步 N 份，构造保证分叉失守；ADR-0028 的红队门禁重复 N 次 |
| 现在就提取全部五件事的接口 | 第二个用例（执行闭环）还没落地，接口全凭想象——正是 ADR-0015 否决的"只写文档不提接口"的反向错误：为不存在的用例提接口 |
| 做成多租户 SaaS 网关产品 | 见决策 3；且"JWT 即 Key"的轻认证叙事与 SaaS 账号体系冲突 |

## 后果

- 正面：ADR-0028 的执行引擎有了落位依据（机制进 `action/` 底座，Runbook 内容与白名单
  走插件契约）；未来故障预测（Prometheus 拉取面）以插件形态进入，不动底座；
  面试叙事从"一个排障项目"升级为"一套可换域的信任底座 + 第一个参考实现"。
- 代价：本 ADR 只立判据不改代码，短期无产物可跑——这是刻意的（避免为不存在的用例
  提前抽象）；可核验形式是"每个提取动作有独立 ADR/契约记录"，不是"今天有接口文件"。
- 文档门闩：ADR 总数 28 → 29，README 与 repo-map 的 `adr_count` 引用随本篇合入同步改
  （`offline/doc_numbers.py` 现算比对，改字是被门闩要求的）。
- 谱系表联动：故障预测若立项，需另行 ADR 修订 `docs/positioning-aiops.md` 谱系表
  ①②行（"永久非目标"→"不自建采集，消费既有时序"），本 ADR 不预支该修订。

  > **修订注（2026-11-11，[ADR-0030](0030-telemetry-consumption-and-baseline-alerting.md) 合入时追加）**：
  > 上述修订**已完成**——谱系表 ①② 已改为「已覆盖（消费侧）／已覆盖（统计基线预警）」，
  > 采集平台与 ML 黑盒检测/趋势外推/根因仍非目标。本行原文保留以记录预支关系，
  > 「本 ADR 不预支该修订」一句至此失效。

## 评审结论（2026-11-16）

评审执行：设计会话（Claude Code）；评审依据：本 ADR 五条判据 + 仓内代码事实逐条核对。
结论：**accepted，无否决项**；四项修订注（R1–R4）随本篇生效。**签收：Lead（shing26），
2026-11-16 于 PR #84 书面签收**，状态自此生效。评审只裁决边界与第一个切面，
**评审本身不批准实现代码**；首个切面后经独立验证落地（PR #85），其余分层仍未落地。契约见
[plans/command-policy-data-accept.md](../../plans/command-policy-data-accept.md)。

| 决策 | 结论 | 依据与修订 |
| --- | --- | --- |
| 1 分层判据 | **接受，落点收窄（R1）** | 判据本身成立且可静态判定；但 `action/` 的"机制层"必须读作**注入/链式门 + 判定顺序 + 尾部校验形状**，不是整个包。命令家族特有的尾部校验（kubectl 旗标、systemctl 宾语）在家族策略接口提取前仍会含领域词，不能纳入词法门闩 |
| 2 五件插件契约 | **接受（R2）** | 五项清单仍是正确的最小集。第 3 行"机制是底座、内容是插件数据"本轮落为**数据文件契约**：只外置 L1 前缀/旗标/宾语闭集，不提取插件 SPI，不新增命令家族 |
| 3 框架形态 | **接受，不修订** | "自己跨场景部署 + fork 换插件"与 [ADR-0025](0025-deployment-profiles-and-strict-mode.md) 的部署者自担口径一致；SaaS 形态的否决理由（部署边界/版本兼容/数据隔离债务）未因时间推移改变 |
| 4 rule of three | **接受，补判据（R3）** | **内容外置 ≠ 接口提取**。L1 白名单内容已有三个仓内真实用例（见 R3），外置内容不消耗 rule of three；真正的策略接口仍等第三个**领域**（第二参考实现）出现 |
| 5 安全不变量 | **接受（R4）** | 五项构造保证逐项绑定到既有回归锁（见 R4）；首个切面明确"换内容、不换防线"，任何放宽闭集或跳过红队的实现直接判 FAIL |

### R1（决策 1 落点收窄）

- **当前分层失守点清点**（静态可查）：`action/ReadOnlyCommandPolicy` 的 `TOOLS`/`VERBS`
  字面量；`action/ControlledCommandPolicy` 的 `L1_PREFIXES`/`KUBECTL_ROLLOUT_FLAGS`/
  `SYSTEMCTL_UNITS` 字面量；`resilience/SopFallbackService` 的兜底 Runbook 内容。
  `llm/` 是**混合包**而非底座包：底座部分是 `VerbatimGuard`；`PromptAssembler` 是领域内容，
  `LlmClient` 的 mock 路径还复用了 `【参考上下文】`/`[参考N]` 格式标记——模板与协议格式已纠缠，
  故 `llm/` 整包不进入先期门闩，只单列 `VerbatimGuard`。
- **CI 词法门闩（领域词表 + 底座包 grep）先覆盖干净底座包**：`storm/`、`auth/`、`cache/`、
  `store/`、`metrics/`、`llm/VerbatimGuard`，以及 `resilience/`（排除 `SopFallbackService`）。
  `action/` 在命令家族策略接口提取前**不纳入**——此时立闸只会两个结果：对合法机制假红，
  或逼出"假装无领域词"的空抽象。
- **`action/` 纳入门闩的触发条件**：出现第一个非 SRE 命令家族（第三个真实用例），且家族尾部
  校验已移到策略接口之后。该门闩与策略接口提取同一份契约内交付，不提前。

### R2（决策 2 第 3 行落地口径）

- **底座机制（不动）**：`ReadOnlyCommandPolicy` 的四层注入/只读判定；`ControlledCommandPolicy`
  的三通道顺序（L0 → 单段注入门 → L1 双确认）与尾部校验形状。
- **插件数据（本轮外置）**：L1 前缀闭集、kubectl rollout 旗标闭集、systemctl restart 宾语闭集。
  载体是 classpath 内的一份版本化数据文件，schema 严格、未知键即启动失败、不接受通配/正则/
  运行时上传/按租户覆盖。
- **不提取**：插件 SPI、ServiceLoader、策略接口、新命令家族、L0 `TOOLS`/`VERBS`（L0 内容量大、
  与只读契约的 18 条单测和活体探针绑定，等第一份数据文件把加载器形状证明后再另立契约）。
- 契约冻结后独立走完整验收链路；本 ADR 不预批准实现。

### R3（决策 4 的"第三个真实用例"判据）

L1 白名单内容这一接口点在仓内已有三个真实消费者，故"内容外置"现在可以动：

1. **语料侧声明**：chunk 的 `action_class: L1-write-low` 是文档作者提供的内容确认——白名单判定
   本来就要求"数据 + 机制"两半合取（[ADR-0028](0028-l1-controlled-action-contract.md) 决策 2）；
2. **L1 命令闭集**：`ControlledCommandPolicy` 的前缀/旗标/宾语三段闭集；
3. **灰度目标白名单**：`opspilot.l1.allowed-targets` 已是运行时配置数据，且空值 fail-closed
   （[ADR-0028](0028-l1-controlled-action-contract.md) 评审增补 6 / 裁决 4·A）。

三条都是"策略内容由数据供给、判定机制留在代码"。**接口提取仍未发生**：前缀之后如何校验
（kubectl 旗标 vs systemctl 宾语）仍是机制内的家族分支；把它做成可插拔策略要等第三个领域。
因此本轮不消耗 rule of three 的接口额度。

### R4（决策 5 的回归锁映射）

| 构造保证 | 既有锁（首个切面必须保持） |
| --- | --- |
| 引擎层权限过滤 | `retrieval/IdentityFilter` 单一事实源 + 跨租户用例；红队跨租户类 |
| 审计哈希链 | `AuditService` 链式写入 + `scripts/l1_worm_gate.py`（L1 事件缺 WORM 即 FAIL） |
| 降级状态机 | 降级单测与切档审计 `ev=degrade_transition`（本切面不触碰） |
| 逐字出口护栏 | `VerbatimGuardTest`（10 条）、`llm/PromptAssemblerTest`（4 条） |
| 只读命令契约 | `ReadOnlyCommandPolicyTest`（18 条）；L1 白名单闭集 `ControlledCommandPolicyTest`（5 条）；`scripts/redteam.sh`（32 条 / 0 泄漏 / 0 SKIP） |

首个切面额外要求：`ActionOpsService.containsVerbatim` 的 `not_verbatim` 拒因不变、
`action_proposed` 审计行仍含逐字命令、`opspilot.l1.allowed-targets` 空值仍全拒。

### 签收记录（2026-11-16）

- **签收人**：Lead（shing26）；**载体**：PR #84。
- **决定**：ADR-0029 定为 accepted；首个切面契约（`command-policy-data-accept`）冻结；
  **是否派实现另行决定**——本次签收不构成实现授权。
- **人审四问**（签收即表示无异议）：
  1. **证据充分性**：评审引用的代码事实（`grep -rli plugin src/main/java` 零命中、L1 白名单
     为 Java 字面量、测试条数）可复核；文档门闩 `doc_numbers --check` 退 0、
     `check_public_copy` OK。
  2. **判据纪律**：未改代码 / 测试 / 断言，未以自评替代裁决；契约内验证命令留待实现后由
     独立 Verifier 亲跑。
  3. **变更面封闭**：本 PR 仅 5 个文档文件，未触代码、语料、配置与 `feat/slo-coverage-up1`。
  4. **遗留边界**：实现未开始；`action/` 词法门闩延后至家族策略接口提取；CI 结论不计入本次
     签收。
- **后续（2026-11-16）**：首个切面实现经独立 Verifier ACCEPT，已随 PR #85 合并（squash
  `7760c40`）；实现验收记录见契约「实现验收签收（2026-11-16）」。其余分层仍未落地。
