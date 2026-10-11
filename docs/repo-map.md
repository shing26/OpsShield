# 仓库地图与文档分工

> **读者 = 接手的人 / 维护 Agent**（不是访客——访客看 [README.md](../README.md)）。
> 本文由 README 的「目录与文档地图」整节移入（2026-09-27），因为那份内容是维护者向的：
> GitHub 访客扫不到底，而接手的人需要它。**新文档一律先进 `docs/`，确实属于必读门面才升到根**
> （见文末「收纳规矩」第 4 条——本文自己就是按这条办的）。

## 文档分工（根目录只留 README/OPS/LICENSE；开发态备忘收在 docs/）

| 你在做什么 | 打开哪份 |
| --- | --- |
| 判断这项目值不值得看 | [README.md](../README.md)（展厅：定位/指标/架构/快速开始） |
| 起服务 / 跑演示 / 录屏 | [DEMO.md](DEMO.md)（六幕主线 + 幕⑦/幕⑧，前置 `scripts/demo.sh` 自检） |
| 日常运维：开号、离职、配额、告警、债务闹钟 | [OPS.md](../OPS.md)（管理员速查——**债务与触发线的单一事实源是它的 §5 / §5.1**） |
| 对齐领域词汇（Query / Chunk / 指纹…） | [CONTEXT.md](CONTEXT.md)（术语表——**改名词先改这里**） |
| 定位自证：AIOps 谱系 / 与朴素 RAG 的差异 / 与 agent 的关系 | [positioning-aiops.md](positioning-aiops.md)（五域三分类表 + 五条机制级差异） |
| 已经做完什么、证据在哪 | [docs/ops/production-readiness-2026-09-12.md](ops/production-readiness-2026-09-12.md)（生产就绪度台账） |
| 缺陷与验证的原始记录 | [docs/qa/](qa/)（三份台账：Persona 测评 / 模块复验 / 自举闭环验收） |
| 架构决策与否决理由 | [docs/adr/](adr/) |

## 目录职责

| 路径 | 是什么 | 入库 |
| --- | --- | --- |
| `src/main/java/com/opspilot/` | 在线面：`gateway`(协议/编排) `retrieval` `llm` `resilience` `auth` `cache` `storm` `metrics` `health` `ingest` `chunk` `config` | ✅ |
| `src/test/java/` | 单测与集成（229 用例，`@Test` 声明数） | ✅ |
| `docs/adr/` | 17 项架构决策（每份含否决项与后果）——**改架构先写 ADR** | ✅ |
| `docs/qa/` | 红队缺陷台账 / 模块复验台账（缺陷与验证的单一事实源） | ✅ |
| `docs/ops/` | 生产化就绪度台账（现行状态置顶 + 历史归档） | ✅ |
| `LICENSE` | MIT 许可（**根级唯一许可文件**；不纳入证据快照，理由见 README §许可） | ✅ |
| `offline/chunkers/` | Python 切分管道（OpenAPI AST / 标题树 / 错误码三切分器 + `build_chunks.py`） | ✅ |
| `offline/corpus/` | 语料源（openapi / runbooks / postmortems）+ 生成物 `chunks.jsonl` | ✅ |
| `offline/eval/` | golden dataset、`evaluate.py`（评测并自动刷新出处登记）、评测报告 | ✅ |
| `offline/eval/gate_matrix.py` | **门控混淆矩阵 + 阈值扫描**：误拒率/漏拒率 + 0.1/0.2/0.3/0.4 四档。走 `/search` 取门控信号，故**一次遍历算完任意阈值**（无需重启网关）且不含 L1/L2 缓存（排除"缓存回放绕过门控"的混淆） | ✅ |
| `offline/eval/refuse_set.jsonl` | 门控矩阵的"应拒答"半集（幽灵错误码 + 域外提问）；**位于 `corpus/` 之外**（探针词进语料会污染评测指标），其"幽灵码确实不在语料里"的前提由测试现算锁死 | ✅ |
| `offline/eval/observed_probe.py` | **真实输入探测（G4）**：在系统自己跑出的 query 上测三件**无需真值**的事（零召回率 / 快路径命中率 / 门控拒答率）。query 集不入库，报告只含聚合 | ✅ |
| `offline/provenance.py` | **报告↔语料同代判据**（面一）：CI 门闩 `--check` + 出处登记 `--stamp`（内容摘要，非 mtime） | ✅ |
| `offline/doc_numbers.py` | **文档数字↔产物同代判据**（面二）：按 `doc_numbers.json` 每次**现算**产物真值比对文档字面量（不存快照、无 `--stamp`） | ✅ |
| `offline/load/` | Locust 压测脚本与报告 | ✅ |
| `offline/load/sweep.py` | **并发-延迟曲线**：扫 25/50/100/200/300/500 六档出 P50/P95/P99 + 失败率 + RPS，标出**首次 L1 触发档**与各档**实到并发/在途峰值**。原始 locust 产物留本地（`.gitignore`），只入汇总报告 | ✅ |
| `offline/load/cache_savings.py` | **缓存节省账**：受控混合负载 → 命中/未命中延迟差 × 命中率 = 单请求平均节省。归因靠本脚本自己的负载（`hits/total_requests` 的分母含 Single-Flight follower，是浑的） | ✅ |
| `offline/tests/` | pytest（切分器不变量 + 告警生产者判定/闸门 + 本地 HTTP 契约 + 行动契约探针的判据极性/变异，**不触网**由 fixture 强制） | ✅ |
| `offline/requirements-dev.txt` | 开发/验证依赖的**锁定版本**（运行时代码零第三方依赖，全 stdlib） | ✅ |
| `offline/localapi.py` | **验收/评测脚本的本机 HTTP 单点**（SSRF 白名单 + token 路径解析；基址可用 `OPSPILOT_BASE` 覆盖）——新增脚本复用它，别再造轮子 | ✅ |
| `offline/acceptance_a2.py` / `acceptance_a3.py` | A2 机制验收 / A3 综合验收 | ✅ |
| `offline/qa_gen_quality_probes.py` | 生成质量门禁的 live 六道（V2/V3/V4/V5/V7/V9）+ chat 预算闸；V9 复用 V2/V4 答案故不占预算。V1/V6/V8 分属 Java 单测、ZSET 用例、文档核对 | ✅ |
| `offline/grounding.py` | **答案接地判据**（V9 的纯函数实现）：答案中的错误码是否都落在本轮 refs 覆盖内——防幻觉链路的**事后**环（前几道管"没证据就不答"，这项管"答了的都有据"）。词法复用 `chunkers/errorcode.py`，不另立副本 | ✅ |
| `offline/console_client.py` | 控制台演示客户端（SSE 打字机 / 风暴模拟） | ✅ |
| `offline/alert_producer.py` | **自举告警生产者**（[ADR-0011](adr/0011-self-bootstrapped-alert-source.md)）：读运行态真相面 → 命中即以 `source=alert` 回打自身链路 | ✅ |
| `offline/action_contract_probe.py` | **只读行动契约的活体复核探针**（[ADR-0017](adr/0017-read-only-action-contract.md)）：把单测已锁的四条不变式搬到线上流量——`actions` 帧形状 / 命令逐字于来源 chunk / 契约⊆引用（含段名闸、写操作段黑名单、编号连续）/ 破坏性形态绊线 / 缓存回放保真 / 跨租户不出界（含 tenant-acme 零围栏块⇒契约必须空）/ 拒绝计数器单调。**自己也只读**：只 login+chat+actuator，`/admin` 一条不打（连 cache/flush 也不打——没有判据需要它），HTTP 复用 localapi。判据极性由 `tests/test_action_contract_probe.py` 用合成坏响应逐条钉住 | ✅ |
| `scripts/` | 运维与入口脚本，逐个见下表 | ✅ |
| `data/` | H2 用户主库（含凭据散列） | ❌ |
| `logs/` | 运行日志 + `audit.jsonl` 合规审计 | ❌ |
| `backup/` | `backup.sh` 产出，按 7/14 天策略自清 | ❌ |
| `_archive/` | **历史归档区**（本机留档，不入库）：外部评估报告、一次性演练产物；目录内有说明 | ❌ |
| `target/` | Maven 构建产物 | ❌ |

## `scripts/` 一览

| 脚本 | 用途 |
| --- | --- |
| `quickstart.sh` | 公开入口：一键冷启动（预检 buildx / compose 插件） |
| `run.sh` | 开发态起服务（加载 `.env` + `mvn spring-boot:run`，可透传 `--opspilot.ingest=true`） |
| `start_gateway.sh` | **jar 形态起网关（本机生产/演示形态，OPS §6 形态取舍）**：端口占用预检并点名占用者 → jar 缺失拒启 → 轮询到日志 `Started` 才算成（不信 `/actuator/health`，它可能属于别的项目）。覆盖口 `SERVER_PORT`（默认 8081）/ `GATEWAY_HEAP` |
| `java_home.sh` | JDK 探测跨平台单点，被 `run.sh` 与 `start_gateway.sh` 共用（PATH 默认是 18，两者都要 21+） |
| `console_token.sh` | 取面板凭证（`role=platform` 的 JWT，`--copy` 进剪贴板）；只在本机终端打印，不写盘 |
| `demo.sh` | 演示前自检（健康 / 权限 / 面板契约 / live 键集合等七检） |
| `demo_self_alert.sh` | 自举告警闭环的一键演练（起生产者 → 观察收敛 → 命中自写复盘；对应 DEMO 幕⑧） |
| `backup.sh` | users 备份 + audit 打包（HTTP 优先，容器内 CLI 兜底） |
| `user_admin.sh` | 账号生命周期 CLI：`add` / `disable` / `passwd` / `backup` |
| `seed_demo_users.sh` | 幂等初始化四个账号：三演示角色（含跨租户矩阵靶）+ 告警主体 `sre-watcher` |
| `gen_tokens.py` | 生成红队畸形 token 样本（合法账号走 login，不预签 token） |
| `check_upstream.py` | **核色前置**：DashScope 三路（LLM / embedding / rerank）探活，全通过才 exit 0——降级会静默掩盖上游故障，故核色前必跑 |
| `liveness.py` | **外部存活探测**（补 OPS §5.1 盲区）：身份（`/` 的 `OpsShield` 锚点）+ 存活（health）双信号，只探不修，退出码给 cron/任务计划程序。**身份先于存活**是实测反推的——本机另一个 Spring Boot 应用的 health 同样回 `{"status":"UP"}`，只探 health 会在本应用全死时**报健康** |
| `watchdog_alerts.py` | **真实 Docker 告警源**（ADR-0011 的输入从"造的"变"真的"）：监视容器状态翻转（exited/unhealthy），翻转即以 `source=alert` 走完整编排链（指纹/收敛/检索）；**首见即刚死也算事故**（否则看门狗冷启动恰逢故障时静默漏报）。复用 `localapi.assert_local` 不自带 HTTP 客户端；自带 `.env` 装载（计划任务环境无人 source）。只观测不重启 |
| `check_coverage.py` | 覆盖率棘轮（读 jacoco 产物按 LINE 设闸、BRANCH 只报不闸；门槛贴实测值留 1pp 抖动余量） |
| `daily_usage.py` | 从审计日志聚合当日用量（cron 友好） |
| `check_panel_contract.sh` | 面板↔后端字面量契约（CI 零依赖，后端改名即红） |
| `py.sh` | Python 解释器三档探测（跨平台单点，被多个脚本复用） |
| `pack_evidence.py` | 证据链一键打包：产出自述快照（`MANIFEST.md` 逐项注明出处与"当前模式能否复核"）；出包前自检"报告↔语料同代"，分叉即拒绝（`--allow-stale` 可显式放行并留痕） |

## 收纳规矩（防止再乱）

1. 新证据与报告 → `offline/*/reports/`；DoD 要求入库，且**数字必须与正文同代**（E1 教训：语料扩后旧报告会让 README 数字陈旧）。**2026-09-18 起由 `offline/provenance.py` 在 CI 强制**——语料变了不重跑评测，`provenance` job 直接转红，不再靠人发现。**2026-09-21 补上另一面**：面一只保证"报告↔语料"同代，不保证"人抄进文档的数字"是对的（实测：README 曾写「121 用例」而当时实际已是 131——现量以登记表为准，本句不再复述具体数字）。故新增 `offline/doc_numbers.py`：按登记表现算产物真值比对文档字面量，同批进 CI。登记表只收**可从产物确定性派生**的数字——live 实测时长等含波动的读数刻意不登记（会假红的门闩比没有门闩更快被关掉）。
2. 一次性产物、外部评估、过时台账 → `_archive/`，git 忽略，不污染根视图。证据快照产物（`scripts/pack_evidence.py`）也落这里。
3. 例行数据备份 → `backup/`，交给 `backup.sh` 的 7/14 天策略，勿手工堆积。
4. 根目录只留四份文档 + 构建入口；**新文档先进 `docs/`**，确实属于必读门面才升到根。
5. 改架构或口径 → 先更新 ADR / 术语表，再改代码；改完回来同步本地图。
6. 动语料 → 同批重跑 `build_golden.py` + `evaluate.py`（`provenance --check` 会拦住漏做的那一步）。

> **2026-09-27 一次自纠**：上述第 2、4 条此前被 9 个 JVM 崩溃/回放日志（`hs_err_pid*.log` / `replay_pid*.log`）违反——
> 它们躺在根目录、全仓零引用、也不属"构建入口"。已删除。**根视图的整洁靠人守，没有门闩**（这类杂物过不了任何门闩，
> 因为它们本来就被 `*.log` 忽略）——这条记在这里，是提醒接手的人：`ls` 一眼看过去不像"四份文档 + 构建入口"时，就是它又乱了。
