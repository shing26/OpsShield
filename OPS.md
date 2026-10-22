# OpsShield 运维速查（OPS）

> 读者 = 管理员。演示话术在 [docs/DEMO.md](docs/DEMO.md)，架构叙事在 README/ADR——本文只放**日常要做的事**。
> 命名：对外项目名 **OpsShield**；`opspilot` 是内部工程代号（包名/配置前缀/`/v1` model 名），代码与配置零改动。

## M0/M1 门禁索引（先看这里）

README 的定位口径已改成「单机可复现的生产级机制参考实现」——**能不能上线不靠这段话判断，靠门禁**。门禁的单一事实源是 [docs/ops/production-gate.md](docs/ops/production-gate.md)；本文只放**日常要做的动作**。

| 批次 | 范围 | 兑现的门禁 |
| --- | --- | --- |
| **M0（G0 收尾，3–4 天）** | 让系统「可被托付地坏」：部署默认值（绑定地址 / 占位凭据 / 请求体上限 / 面板输出转义）、备份调度与龄期告警、告警到人、失败请求证据 | 门禁 1 / 2 / 3（判据见门禁文件 §2） |
| **M1（G1 发布，1–2 周）** | 把「单机」做成「可发布」：准入控制、优雅停机、发布/回滚、延迟分位与 SLO、依赖升级、审计留存、值班 5 分钟动作 | 门禁 4–10 全量（判据见门禁文件 §2） |

怎么用（三条纪律）：

1. **提交前**跑 fast 门禁、**发布前**跑 full 门禁 + 演练；门禁的 fast/full 判定与复核命令在门禁文件 §1，六类演练步骤与判据在 §3。
2. **值班被叫醒**先看门禁文件 §5「5 分钟判据」定位到层，再回本文按症状找动作——先定位，再动手。
3. 门禁的当前状态与最近一次复核日期**只记在门禁文件 §4**，本文不复述：复述一遍就多一个会漂移的真相源（这类漂移在本项目里已经出过两次台账事故）。

## 0. 脚本索引（按用途分组；逐脚本职责与坑见 [docs/repo-map.md](docs/repo-map.md)）

| 分组 | 脚本 | 一句话 |
| --- | --- | --- |
| **服务生命周期** | `scripts/quickstart.sh` | 三条命令入口：随机凭据 .env → Docker 全栈 → seed 账号 |
| | `scripts/start_gateway.sh` | 起停网关（占用预检 + `Started` 判据；端口/堆可 env 覆盖） |
| | `scripts/backup.sh` / `scripts/user_admin.sh` / `scripts/seed_demo_users.sh` | 备份 / 账号 CLI（容器形态先停网关）/ 演示账号 |
| | `scripts/console_token.sh` / `scripts/gen_tokens.py` | 取面板 JWT / 红队畸形 token（A2 依赖，勿删） |
| **门禁与契约检查** | `scripts/check_panel_contract.sh` | 面板↔后端字面量契约 + actuator 暴露面（CI 独立 job） |
| | `scripts/check_coverage.py` | 覆盖率棘轮（CI-only：本机目录名含长破折号量不了） |
| | `offline/doc_numbers.py` / `offline/provenance.py` | 文档数字现算比对 / 报告↔语料同代门闩 |
| | `scripts/check_public_copy.py` | 对外面用词门闩（canonical 词不得漏到 README + 页面文案；词表从 CONTEXT.md 现取，见 §15） |
| | `scripts/pack_evidence.py` | 证据一键打包（逐项注明出处） |
| **故障注入与演示** | `scripts/demo.sh` / `scripts/demo_self_alert.sh` | 演示预检（全 PASS 才开演）/ 自举闭环一幕到底 |
| | `scripts/watchdog_alerts.py` | 真实 Docker 告警源（容器翻转 → source=alert） |
| | `scripts/alert_bridge.py` | Alertmanager webhook → 编排链路的宿主桥（外部监控 adapter 的第一版，见 §13） |
| | `scripts/audit_timeline.py` / `scripts/check_upstream.py` | 审计时间线回放 / DashScope 三路探活（核色前置） |

## 1. 账号生命周期（不再手工签 token）

所有用户操作走 CLI。**⚠️ 并发口径按后端形态分**（2026-09-16 实测更正，事故 `51103_H2_CONCURRENT_WRITE_CORRUPTION`）：

- **宿主裸进程形态（G0 口径，2026-10-22 收紧）**：默认**不再**用 AUTO_SERVER 并存——CLI 与运行中网关争同一个 H2 文件锁，
  `scripts/user_admin.sh` 检测到网关在跑（端口监听或 health 200）会直接拒绝（exit 2），除非显式 `FORCE=1`（仅演练库/确认端口不是本项目时用）。
  维护动作走「停网关 → CLI → 起网关」或 HTTP 管理面（`/admin/backup`）；AUTO_SERVER 只能在 `H2_DB_URL` 里显式写出来（知情选择）。
- **schema 版本化（G1，2026-10-22）**：账号库建表由 **Flyway V1**（`src/main/resources/db/migration/V1__users.sql`）负责，`spring.sql.init` 已关闭；空库启动即建表，已有库会幂等执行 V1 并记 `flyway_schema_history`。加列/改约束必须新增 V2+，禁止再改 V1（校验会拒绝）。
- **恢复旧备份后**：H2 文件回到备份时刻，`flyway_schema_history` 也一起回退；网关启动时 Flyway 会按历史补迁移。若备份来自更老版本，先看 `flyway_schema_history` 再决定前滚/回滚，不要手工删表。
- **容器形态**：`./data` 是 bind mount，**H2 的锁文件跨挂载点不可靠**——CLI 与容器内网关同时写会
  损坏账号库（本次实测：CLI 写成功后停容器，下次打开即 MVStore chunk 损坏、恢复工具也救不回）。
  **正确做法：先 `docker compose stop gateway` 再跑 CLI，或走 HTTP 管理面（`/admin/backup` 等）。**
- 动手前先备份：`bash scripts/backup.sh`（账号库是唯一不可再生数据）。

```bash
export DEMO_PASSWORD='…'   # 或任何一次性环境变量；口令绝不进命令行参数

# 入职：
DEMO_PASSWORD=<新口令> bash scripts/user_admin.sh add --user alice --tenant tenant-internal --level 1 --role sre --password-env DEMO_PASSWORD
# 调密级：改行需先 disable 再 add（CLI 不提供 UPDATE level，防误操作扩散）
# 改密（自动吊销该用户全部存量 token）：
DEMO_PASSWORD=<新口令> bash scripts/user_admin.sh passwd --user alice --password-env DEMO_PASSWORD
# 离职（即时生效，24h 内的旧 token 立刻 401）：
bash scripts/user_admin.sh disable --user alice
# 误删回滚：
bash scripts/user_admin.sh enable --user alice     # 注意：enable 也 bump token_ver，旧 token 不回活
# 审计视角的账号清单：
bash scripts/user_admin.sh list
```

> 前提：jar 已构建（`mvn package -DskipTests`）。首次使用先跑 `bash scripts/seed_demo_users.sh`。
> **容器模式**（compose full profile）：宿主机 CLI 无法共享容器持有的 H2 文件锁/网络，改用
> `docker compose exec gateway java -cp app.jar -Dloader.main=com.opspilot.auth.UserAdminCli org.springframework.boot.loader.launch.PropertiesLauncher <同参数>`；备份 zip 经 `./backup`↔`/app/backup` 卷映射落宿主 `backup/`（QA P1-3 修复后可见；映射缺失时 `backup.sh` 的存在性校验会报红）。

## 2. 口令分发纪律

- `JWT_SECRET` / `DEMO_PASSWORD` / `H2_DB_PASSWORD` / `REDIS_PASSWORD` / `QDRANT_API_KEY` / `ES_PASSWORD` / `DASHSCOPE_API_KEY` **只存在于每台部署机的 `.env`**；`.env` 已 gitignore。
- 新人拿口令：走公司密码管理器分享，**不发群、不进任何文件提交**。示例值一律是 `change-me-*` 占位（Mimosa 约束：源码/示例/测试零可用凭据字面量；单测 fixture secret 是特例，说明见 ADR-0005）。
- 换 `JWT_SECRET` = 全量 token 作废，全员重新 login，无需其它操作。

## 3. 配额与 429

- `/chat/stream` 每用户 **5000 次/天**（防失控循环，不防去重风暴——500 并发风暴只算穿透者的账）。
- 触发 429 的处理：确认是脚本失控还是真实高频；确需上调改部署 env：`-Dopspilot.quota.daily-limit=20000`（或 `OPSPLOT_QUOTA_DAILYLIMIT` 的 relaxed-binding 形式）。
- `/search` 与登录、管理端点不吃配额（评测路径专用）。

## 4. 每日用量与拒答处置 SOP

cron（Linux 部署版；本机手动跑同样有效）：

```cron
0 9 * * * cd /opt/opspilot && python3 scripts/daily_usage.py --date yesterday \
  --threshold-refuse 0.10 >> logs/usage.cron.log 2>&1
```

输出一行 JSON 进 `logs/usage.log`（schema 锁定，可 jq/Loki 消费）：

```json
{"date":"2026-09-10","total_requests":3847,"unique_users":42,"refuse_rate":0.073,"l3_hits":12,"top_user":"alice:212"}
```

**`refuse_rate > 0.10`（脚本退出码 1）时按此排查：**

1. `grep '"refused":true' logs/audit.jsonl | tail -100` 看被拒 query 原文，归类三种根因：
   - **语料落后**（新故障类型没 runbook）→ 补文档 → 触发一次 `POST /api/v1/admin/reingest`；
   - **min-relevance 误杀**（真相关但 rerank 分低）→ 调 `-Dopspilot.retrieval.min-relevance=0.15` 重启观察，两日仍高再回 0.2；
   - **Prompt/模型侧**（live 后端异常、降级期 SOP 空手）→ 看 `/metrics` 的 `degradation_level` 与 `llm_rate_limited`。
2. 24h 未收敛 → 按 ADR-0006「回滚」条目将别名指回上一版物理库（admin 端点尚未实现，属记录在案欠账；手动 curl ES `_aliases` / Qdrant `update_aliases` 即可）。
3. `l3_hits` 突增另说：那是合规视角要人肉过目的（谁在密集查生产配置级内容），找对应 sub 聊聊。

## 5. 知识库运维与债务闹钟表

- 语料改动 → `cd offline && .venv/Scripts/python chunkers/build_chunks.py` → `POST /api/v1/admin/reingest`（level≥3；busy 时 409，结果看 `/metrics` 的 `reingest_busy/reingest_last`）。零空窗，白天可操作（实测 448 chunks 24.4s，live、服务端日志口径；mock 后端 8.0s；live+账户限流最坏 ~6min）。**动语料后 `build_golden.py` 必须与 `evaluate.py` 同批跑**——只改语料不重生成评测集，会让报告与语料悄悄分叉（旧 golden 的 `exact-50012` 就是这样与入库语料不自洽的）。
- **语料命名纪律**（2026-10-07 撞号事故后立）：md 的 doc_id 由**文件名前缀**（`[a-z]+-\d+`，如 `rb-102-*.md` → `rb-102`）派生，frontmatter 里的 `doc_id` 只是文档性记录、不参与索引。两个文件取同一前缀 = 两篇内容**静默混入同一 doc_id**——引用与 golden 的期望文档从此歧义（实测：rb-101~105 段被新旧两批共占，10 篇在索引里只占 5 个身份，chunks 行数不变、一切照常绿）。`build_chunks.py` 已加 doc_id 唯一性断言 + 回归锁，新语料落盘前先核前缀未占用。
- **同代性不必靠记性**（2026-09-18 起）：`cd offline && python provenance.py --check` 当场判定"报告是否仍是当前语料的报告"（内容摘要判据，零凭据零网络），CI 的 `provenance` job 跑的就是它。漏跑评测时它会点名哪个输入变了并给出修复命令：`python eval/build_golden.py && python eval/evaluate.py`；`evaluate.py` 会自动刷新出处登记 `eval/reports/PROVENANCE.json`。
- **交接/对外给证据**：`python scripts/pack_evidence.py`（加 `--zip` 出压缩包）产出自述快照到 `_archive/evidence/`，入口 `MANIFEST.md` 逐项写明出处与"当前模式下能否复核"。默认**不含**本机运行态证据（`logs/`）；要看运行史用 `--include-local-evidence`——**该档含查询内容与租户标识，别原样对外发**。
- `scripts/gen_tokens.py` **是红队畸形 token 签发器**（A2-8b/8c 与 demo.sh 预检依赖）——不是遗留脚本，勿删。
- 旧 `demo_tokens.txt` 体系已死；合法凭据一律 login 换取。

| 债务 | 触发线（到点必须做，未触发不做） | 当下缓解 |
|---|---|---|
| 真增量 Ingest | chunks > 2000 或 live 全量重灌 > 10min（`reingest_last` 时间戳可测） | blue/green 原子切流支撑每日任意时刻全量重建；`refuse_rate` 为前置健康信号 |
| LLM 供应商抽象（`llm/LlmProvider` 接口） | 出现**真实的第二家供应商**需求（mock/live 双模不算），且其检索栈可同规格替代（embedding 维度/rerank 变更需重灌评估，走 ADR-0006 blue/green） | LLM 腿本就 OpenAI 兼容：baseUrl/model 走配置、硬编码仅路径段 `/compatible-mode`，换供应商≈改配置；数据层耦合由 [ADR-0002](docs/adr/0002-dashscope-one-stop-1024-dim.md) 在案（2026-09-19 外部评审"抽接口"建议经核实不采纳，理由见台账） |
| 中间件 TLS | 过等保/ISO 审查，或中间件跨机/跨 VPC 部署 | 凭据认证 + 127.0.0.1 环回绑定（P3 已落） |
| 多实例 | 团队 >200 人或持续峰值 QPS > 50 | 单实例虚拟线程（实测 500 并发收敛）；先调 JVM 堆压榨单机 |
| 外部 IdP | 公司强推统一 SSO / 禁用自建口令 | H2+bcrypt 结构下加 `/auth/login/oidc` 映射入口即可，不侵入校验链 |
| 断言语态结构化门控 | 任何一次**被真人/QA 复现、且落在探针正则覆盖之外**的断言式事故编号回答（防断言语态现为软约束层：prompt 规则 6 + 正则探针，上限=探针选词，见 ADR-0010） | 立项输出结构化门控：模型按标注输出置信、渲染层依标注 gating，不再依赖句式正则；泛化症状探针在 `offline/qa_gen_quality_probes.py` 锁最恶劣形态 |
| 处置闭环（对目标系统的自动执行/自愈） | 两组析取项：①产品方向从"建议"转"执行"；②接入真实告警流 **+** 可审计的 runbook 执行通道（审批链/HITL 门，见 ADR-0009 只读面扩展） | 只读立场为承诺非疏漏：拒答门控 + 全链路溯源保证人在回路。**2026-09-16 状态注**：②中的"告警流"一半已满足（自举源，ADR-0011），但"执行通道"未建、①也未发生 → **本项未触发**，仍按待还债记录。**注**：AIOps 谱系 ①遥测采集/②异常检测=**永久非目标**，不列本表（边界声明见 README/面板谱系块） |
| 跨指纹 incident 关联（同一根因、多条不同指纹告警的**关联**——不是归并） | **触发线已兑现（2026-10-17）→ 同日定案并实施**。判据：真实告警流里"同一根因天然是多个指纹"已实证（[qa §3.1](docs/qa/2026-10-17-alertmanager-intake.md)），而外部告警源接入使"人工归并成本可测"成为可观测项。**证据面改名**：原写 `logs/alert-producer.jsonl`，实测该源 33 行同文本只给出 **1 个** fp（看不出"碎"）——真正的证据面是 `logs/alert-bridge.jsonl`（`incident_id` 分组 + `alert_id` 逐条） | 同指纹域收敛已覆盖（500 并发 → LLM 1 次）；跨指纹**关联**已落——同一事故的 N 条告警共享一个 `am-…` 分组身份、落在审计行 `trace_id`，可整段取出，见 [ADR-0019](docs/adr/0019-incident-boundary-owned-by-upstream.md) 与 qa §7。**归并（N→1）明确未做且未立项**：它要先回答"拿哪条告警的文案当 query"，本仓已否决过一次 |
| L1 降级期的门控降级（摘除 Rerank 后无相关性分数可依，相关性门控失效、只剩零召回拒答） | 出现一次"L1 期**误拒或漏拒**造成真实事故"且被真人/QA 复现 → 立项标定 RRF 分数分布并给出 L1 专用阈值 | 取舍与理由见 [ADR-0012](docs/adr/0012-degradation-cost-semantics.md)：RRF 分数无量纲（只依赖排名），照搬 L0 在 Rerank 分布上标定的 `min-relevance: 0.2` 会变成拍脑袋拒答，而未标定阈值造成的**误拒是静默的**（用户只看到拒答，看不到本可作答）——比现状更糟。零召回仍拒答，故并非"完全不门控"；L1≡es_only 的同构关系已由单测锁定 |
| L2 写入重复 embed（同一请求 embed 两次：检索腿一次、L2 写入一次） | 出现两组析取项任一：① **embedding 调用成本成为瓶颈**（日调用量翻倍触账单异常或上游限流）；② 原判据 `stage_ms.l2_store` 占比 >10% 在 G1（2026-10-22）后失效——L2 写入已异步化，该字段只剩"入队开销"；暂由①（上游调用量/账单）兜底，G2 补 L2 写入的独立 embedding 计数后恢复可测 | 成本已量化：实测一条 10.2s 请求里 `l2_store=232ms`（约 2.3%），延迟上不划算；真正的动机在**上游调用次数减半**。修法要向 `QdrantSearchService` 的返回面回传查询向量（并在 es_only/降级无向量时回退到 embed），接口面确有改动，故按触发线挂起不预防施工。**注**：原代码注释曾写"复用检索时已算好的向量"而实际未复用——那是**宣称失实**，已于 2026-09-25 改为如实描述（那个才是缺陷，已修） |
| 行动契约的 top-3 产额（[ADR-0017](docs/adr/0017-read-only-action-contract.md)：契约只从**本次引用的 3 个 chunk** 抽，`final-top-k: 3`） | 出现下述任一：①troubleshoot 页的**空契约状态在真实使用或演示里被指认**为"不好用"（真人/QA 复现，能指到具体 query）；②产品侧给出**明确产额下限**（如"≥X% 查询要带命令清单"）→ 到点二选一动工：抬 `final-top-k`（连带重跑评测、重登现役口径那组数字：精确 Top-1 100% / 语义 Hit@3 100% / 语义 Top-1 88.2%），或同文档兄弟段扩张（须先做"给未引用原文开放行口"的显式权限复核，并重跑段名闸与四层策略的变异测试） | 实测 **6/11** 固定查询形状出非空契约（2026-10-10，live，`/admin/cache/flush` 清 L1+L2 后逐个跑，11 个读到 `cache_hit=none`，共 9 条动作）；11 个目标文档**全都有 2~3 个可抽的排查段叶 chunk**，故**语料不是瓶颈、top-3 才是**——命中的查询都引用了编号叶（`排查步骤 > 第二步：定位慢 SQL`），落空那 5 个的三槽被文档标题、段头（`排查步骤`）与症状摘要占满，命令所在的叶排在第 4 名之后。"契约 ⊆ 引用"是构造保证，**多给命令必然要动它**，故不预防施工。判据见 OPS §12 末条：看 `done` 帧 `refs` 面包屑里有没有"第N步"字样的叶，没有就是本条、与策略松紧无关 |

| SSE 异步上下文错误后继续写（2026-09-28 压测窗口观测到 2 次） | 出现一次**客户可见**的"响应已开始但中途静默截断"且被复现——判据：能指到具体请求的 SSE 流在 done 帧前断掉，且伴随 `IllegalStateException: AsyncContext after error` | 观测到的 2 次都发生在**客户端断开之后**（locust 收尾那一秒；同轮 2700 请求里 2 次），不影响任何已成功响应；属"错误后仍尝试写"的收尾竞态，非数据正确性问题（审计行与缓存写入都在错误之前完成）。原文见 `logs/run-113138.log`；本轮为"记录 + 触发线"而非修复（不在批次二范围内，且无客户可见影响） |

| `/actuator/health` 匿名可读（返回 `{"status":"UP"}`） | 出现"只读面口径必须逐面声明"的审计要求，或该端点被用于**探测网关存活以绕过守卫**（判据：外部脚本据它判定"服务在跑"而跳过 401 面） | 平台门禁已覆盖 `/admin/**`；actuator 面只回聚合状态、信息量≈0，且 demo.sh 依赖它做预检（属**有意保留**）。2026-09-28 探索性验收提出"口径未声明"，此处如实登记 |

| 域外/无信息量 query 的第二道门（2026-09-28 探索性验收 F5：mock 实测 `今天天气怎么样` top_rerank 0.695、`zzzqqqxxyyy 完全不存在的东西` 0.823 均过阈 → 不拒答、给出自信但不相关的答案） | **live 可用后**按固定域外样本集（含通用中文单字的样本）复测：若 live 精排下 top-1 仍 ≥ `min-relevance`（0.2），或任一此类回答被真人/QA 复现为"误导性回答" → 立项"第二道闸"（无可识别领域词 → 走澄清而非硬答） | `min-relevance: 0.2` 是按 **live 精排分数分布**标定的（ADR-0012），mock 的 IDF 覆盖分不可比，故本次不改阈值、不立项——口径与"误拒是静默的"同源：改动必须以 live 读数为准。**2026-10-03 live 复测（触发线兑现）**：上游恢复后按固定样本集实测——`今天天气怎么样` top_rerank **0.0061**、`系统卡了怎么办` **0.1348**、`zzzqqqxxxyyy…` **0.1618**、`帮我写一首关于秋天的诗` **0.0655**、`明天股市会涨吗` **0.1168**，**0/5 过阈（阈值 0.2），全部正确拒答 → 未触发，维持不立项**。mock 那组 0.695/0.823 确认为 IDF 覆盖量具的假象。对照：`50012_DB_TIMEOUT 怎么排查` 走快路径命中（top_rerank 0.0=未调精排）。已在 readiness 修补记录留档 |

| 门控拒答写入 L1（TTL 2h）导致语料更新后短窗回放旧拒答（2026-10-03 登记，live QA F-4） | reingest 补上新语料后，同 query（同指纹）在 2h 内仍收拒答回放**且造成漏检**——判据：能指出具体 query 与重灌时间点（审计行 `cache_hit:L1` + `refused:true` 晚于 reingest） | 与"SOP 直出不写 L1"（A2-6）同一取舍家族：拒答入缓存换"同 query 重发零成本"，代价窗上限 = TTL。运维 SOP：reingest 后用**改变措辞**的 query 验证新语料（不同指纹绕开旧条目）；若要根治可缩短拒答类条目 TTL，未到触发线不做 |

| 静态 SOP 缺 `auth_level` 维度（ADR-0020 理由③ 登记：`IngestionRunner.warmSop` 只按 tenant 分区、`SopFallbackService.lookup` 不收 `authLevel`，L2 直出时 level-2 止损段对 level-1 主体可达） | 出现一次"低密级主体在 L2 静态 SOP 直出中读到高于自身 `auth_level` 的止损段"且被真人/QA 复现（判据：能指到具体 query 与 `auth_level` 组合的审计行）→ 立项给 `warmSop` 分区键与 `lookup` 签名加 `auth_level` 维度 | 只读立场不豁免：它是 ADR-0008 在 L2 静态 SOP 面上的缺口。**在它修掉之前，对外不得宣称"静态 SOP 也按密级隔离"**（ADR-0020 已写此口径）；L2 直出只在降级期发生，故暴露面受限 |

### 5.1 历史遗留项的触发线收敛（2026-09-17）

> 下面这批此前只有"另排期 / 待酌情 / 记录在案"而**没有可到点执行的触发条件**——按本项目纪律，
> 没有触发线的欠账等于"永远不做且无人知道"。本次逐条补触发线或明确降级为当前非目标。
> （CORS 注释失实一项属"宣称必须真"，已当场修正 `config/CorsConfig.java`，不占表位。）

| 项（来源） | 触发线 | 当下缓解 |
|---|---|---|
| 别名回滚 admin 端点（ADR-0006 承诺"可秒级回退"，端点未实现） | 首次出现"重灌后需要回滚"的真实需求，或任何一次 reingest 质量事故 | 物理库名带时间戳 + 手工改别名指向即秒级回退（见上表 SOP 第 2 步）；**无端点 ≠ 无退路** |
| 外部监控 adapter（评估 M3 剩余项） | 三组析取项任一成立即到期：① 拿到**可读且被授权**的外部告警/监控 API（Alertmanager / 云监控 webhook，有文档化契约与可用测试凭据）；② 产品方向转为含外部集成，且 ≥1 个外部消费者书面要求以 webhook 接入；③ 一次真实故障因缺外部告警接入而被漏掉（判据：该故障落在自举源盲区内，且复盘结论指向"没接外部源"）。**2026-09-21 改写**：原触发线"出现真实告源"不可判读——没有任何时刻会让它到期，等于没有到期日，违反本表自订纪律。**【2026-10-17 触发线①已兑现·已落第一版】**：Alertmanager 的 webhook 契约是公开文档化、本机自建即被授权的告警源，①成立。已落地的形态是**宿主 bridge**（`scripts/alert_bridge.py`）而非网关端点——本轮目的是观测"指纹在真实告警标签下碎在哪"，不是上新能力，故产品面零改动、不开新的鉴权维度（理由见 §13）。**仍未做（如实登记）**：桥是单实例、无外部多实例共享态；「提升为网关原生端点」是另一个决策，触发线见下条 | 自举源 + Docker 告警源 + Alertmanager 指标告警源三源并存；形态与边界见 §13 |
| 告警接入从 bridge 提升为网关原生端点（`/api/v1/alerts/webhook`） | 两组析取项任一成立即到期：① §13 台账里的指纹分布证明确有"真实标签导致的误收敛"需要服务端介入（判据：能指到具体 `fp` 与两条本该不同指纹的告警）；② 出现**桥形态跑不动**的真实部署需求（多实例部署 / 需要网关原生鉴权与配额归属，判据：桥的单实例锁成为实际瓶颈而非纸面限制） | 桥已覆盖单机全部需求；提升为端点要先发明"机器调用者的身份与配额归属"——JWT 是给人（和 agent）用的，Alertmanager 不是账号。这是真实的地基工作，不做在证据之前。**2026-10-17 补注**：方案 C 修的是"误分裂"（碎），**不是**本条触发线①的"误收敛"（本该不同的指纹撞在一起），故这次修复**没有**触碰本条触发线。**2026-10-20 复核·仍未触发**：本机 `logs/alert-bridge.jsonl` 中 25 条带 `alert_id` 且有网关 `fp` 的有效投递记录里，同一 `fp` 对应不同 `alert_id` 的组数为 0；部署仍是单实例，且尚无实际多实例部署或单实例锁造成等待、拒绝、漏告警的证据。故不创建 C3 issue，不进入 spec/tickets/实现 |
| 指纹契约与真实告警文案的错配（Alertmanager 模板把 `$value` 渲染进注解，同一条告警每次重发都是新指纹 ⇒ L1 与单飞双双失效） | **触发线已兑现（2026-10-17 首次实测即复现）→ 当日定案并实施"方案 C：服务端拆身份"**。实证见 [qa/2026-10-17-alertmanager-intake.md](docs/qa/2026-10-17-alertmanager-intake.md) §3。**判据不是"再做一次实验"**，而是先回答"治不治得了别人写的规则"——方案 A 只在自家规则里成立，故排除；选定 C，使服务端不再从人类可读文案里推断身份。**仍未做（如实登记）**：§3.5 的同身份降级/失败重复穿透是独立问题（见下一行）。跨指纹关联已于当日同轮落成（上一行 / qa §7） | 规则与桥都保持原职：规则照旧把 `$value` 渲染进文案，桥不替网关归一化，只多派生一个与 message 无关的 `alert_id`（四元组 alertname/job/error_code/status）。人工提问与既有自举源不传该字段，逐字回退到原文本指纹，行为不变。**产品面只多一个可选 body 字段**（`ChatRequest.alertId`），无新端点、无新鉴权维度；契约与验收见 §13 / qa §6，定案见 [ADR-0018](docs/adr/0018-alert-identity-split-from-query-fingerprint.md) |
| 降级/失败路径的告警重复穿透（L1 只缓存"成功且非 SOP"的答案；来源：qa 2026-10-17 §3.5） | 出现一次**真实告警风暴窗**且重复穿透造成可测成本——判据：同一 `alert_id` 在 ≤15 分钟内被投递 ≥3 次、`cache_hit=none`，且该窗内 `degradation_level≠L0` 或 `sent_ok=false` 占多数，并能指到配额/上游调用/延迟任一读数 | 本轮**刻意不动**：这是"降级期答案要不要进 L1"的独立取舍（SOP 不入是 A2-6 的既有决策），与方案 C 的"身份碎"是两回事。当下单飞只能折**并发**同身份请求，折不了 Alertmanager 的**顺序重发**；观测面 = 台账 `alert_id` 分组 + `cache_hit` |
| 复盘 → 知识回灌（真实排障结论沉淀为可检索语料） | 两组析取项任一成立即到期：① **同一根因的故障被人工复盘 ≥2 次**；② 外部监控 adapter 触发后同批处理。**【2026-10-06 触发线①兑现·已完成第一次真实回灌】**：2026-10-04 宿主 OOM 连杀容器**两次**（同一根因），2026-10-06 写成复盘 `rb-102` 入语料——第二次就该沉淀复用，本条正是这么做的。同批复盘共 5 篇（rb-101 本机辖区清单 / rb-102 容器 OOM / rb-103 端口占用 / rb-104 面板旧缓存 / rb-105 截断 jar），**全部是本机真实发生过的事故**（签名/命令/处置均当晚实测），走 `build_chunks → reingest → evaluate` 全链路入库（448 chunks，live 24.4s 零空窗），并接上**真实 Docker 告警源** `scripts/watchdog_alerts.py`（容器状态翻转 → `source=alert` 走完整编排链，实测 exit 137 告警 → 检索命中 rb-102）。**仍未做（如实登记）**：回灌仍是**人工离线**写 md → 重灌，运行期依旧**无"结论→语料"写路径**（`IngestionRunner` 只读 `chunks.jsonl`；全仓唯一写它的仍是 `build_chunks.py`）——要不要做运行期自动回灌是新决策，不在本触发线范围内；告警看门狗也未进 CI 常驻（与 liveness 同批，待部署环境接线） | 语料现含 68 篇复盘/Runbook（5 篇真实 + 63 篇 fixture），运行期**无"结论→语料"写路径**不变。**陷阱提醒修订**：rb-101~105 **是**做过的故障（当晚实录），其余 63 篇仍是喂检索的 fixture——两者在语料里并存，检索时不区分，但对外引用证据时必须分得清 |
| 拒答路径 TTFT（persona-eval P2-6） | 拒答延迟进入对外 SLO，或被真人/QA 作为体验问题复现 | 拒答在 LLM 前 return（不烧 token）；慢在检索自身耗时，属检索优化非生成链 |
| metrics 观测缺口：5xx 计数 / 延迟分位 / 别名指向 / H2 健康 / 最近成功备份时间 | 任一项成为排障瓶颈——判据：再次出现"**只能靠翻日志才能判断**"的事故（2026-09-17 的登录 500 正是此类） | 面板已吸收依赖灯 / 降级档位 / 配额水位 / 在途组；`daily_usage` 有磁盘哨兵 |
| 超长 query 软上限（persona-eval P2） | 出现超长输入导致的成本或延迟事故，或对外提供公网入口 | 拒答门控 + 单主体日配额 5000 + LLM 调用超时 |
| 引用样式漂移（`[参考1]` 与 `【参考2】`并存） | 下游客户端需要按样式解析引用时（当前形态是给人读） | 溯源以结构化 `refs` 数组为准，样式只在呈现层 |
| cache / ingest / chunk / config 四包无直接单测（台账 §1 如实记录） | 四包任一出现回归，且现有间接证据（依赖探活 / 集成活体 / 逐包验收）未能定位到该包 | 该四包的运行证据由探活与集成活体承担，非零覆盖 |
| "网关整体不可用时生产者无法自证"（自举已知盲区） | 需要"网关挂了也要告警"的场景出现（多实例 / 生产 SLA）——与上表"多实例"同批处理 | **【2026-10-05 触发线已兑现·已建外部探测】**：投产决策触发该线，新增 `scripts/liveness.py`（只探不修）。设计要点由当晚实测反推：① **身份先于存活**——本机 8080 上的另一个 Spring Boot 应用（nexus-web）`/actuator/health` 同样回 `{"status":"UP"}`+200，故只探 health 的探针会在 OpsPilot 全死时**报健康（假绿）**；现改用 `/` 的 `OpsShield` 锚点做身份、health 做存活，两者都满足才算健康，身份不符单列 `wrong_service`（处置是查端口被谁占，不是查本项目）② **连续 2 次才判死 + 恢复即清零**（单次抖动就报警的探针，人会很快学会忽略它）③ **环回白名单**（只该探本机，否则脚本自身成 SSRF 面）。回归锁 `offline/tests/test_liveness.py` 16 例，变异验证三条：去身份校验→`wrong_service` 例红；去阈值→两例红；去白名单→**真的对外发出了请求**。**仍未做**：投递渠道（重启/告警要人工决策，仓内无渠道）；连续运行形态（cron / 任务计划程序）待按部署环境接线 |
| 告警生产者未纳入 CI（台账 §7 判定为合理边界） | CI 引入常驻服务栈（compose 进 CI）时 | 纯函数级回归锁 23 用例已在 CI 内，覆盖判定 / 闸门 / 鉴权 / 容错 |
| 文档数字陈旧（README/OPS/DEMO 引用的数字与随附产物分叉） | **已收敛（2026-09-21）**：`offline/doc_numbers.py` 按 `doc_numbers.json` 每次**现算**产物真值比对文档字面量，已进 CI 的 `provenance` job。立项依据是实测：README 写「121 用例」而 `@Test` 声明与 surefire 执行数**同为 131**——面一（报告↔语料）上线后同一缺陷类又发生了一次而无人看见。**已知边界**：live 实测时长（如重建 22.3s）与并发实测值含波动、无法确定性派生，刻意不登记，仍属人工纪律 | 面一 `offline/provenance.py` 管"报告↔语料"；面二管"文档↔产物"（术语见 `CONTEXT.md` 评测域） |
| 真增量 Ingest 的阈值口径（ADR-0006 曾写"语料上千"） | **已收敛（2026-09-17）**：以本表"真增量 Ingest"行为准（chunks > 2000 **或** live 全量 > 10min）；ADR-0006 已加注指向本表 | 与上表同项，不再单列 |
| 单测偶发红：`ChatOrchestratorTest` 的审计断言（2026-09-20 CI 一次，2026-09-27 CI 又一次——换了用例名 `verbatimDumpFromLlmIsMaskedAtExitAndCacheStaysClean`） | **已收敛（2026-09-27）**——触发线兑现：第二次出现即定位真实根因并修掉，不再挂起 | 真实根因**不是**"审计没写"，而是**竞态**：编排里 `sink.done()` **先于** `audit.log()`（三条出口路径皆然），而 `awaitSuccess` 只等到 sink 的 latch（由 `done()` 落下）⇒"等 sink 收尾 → verify 审计"必然可能输给调度。2026-09-20 那次只修了**误导性报错**（先断言 `sink.error` 为空再 verify），**没修竞态本身**，所以它必然会再来。**修法**：审计断言改用 `verify(audit, timeout(2s))` 轮询等待交互。**本机复现 + 变异验证**：在 `done→audit` 之间插 400ms 延时后，不轮询 → 3 个用例红且消息与 CI 一字不差；改轮询后同一延时下 9/9 绿 |
| 接地判据 V9 的覆盖边界（只管错误码，不管步骤编号/配置名/服务名的无据断言） | 出现一次"答案里**非错误码**的断言无据"且被真人/QA 复现——判据：该事故落在 V9 词法盲区（`pm-*`/`rb-*` 步骤编号、配置项名等），即现有判据结构上抓不到 | 已覆盖错误码：它是精确符号、可做集合运算、无"有据与否"的解释歧义。步骤编号/配置名要纳入，须先给出"有据"的**可判读**定义，否则会引入误报噪音、把门闩变成噪声源（本表纪律：不可判读的触发线等于没有）。**引用正确率**（标号是否真的支撑该句）另属一类，需异构裁判模型或人工标注，不并入本项——判据与裁判同源则无证据价值。**【2026-10-03 触发线已兑现·已立项第一步】**：live QA 复现——`50041_PAY_GATEWAY_502` 的答案里出现语料 0 命中的指标名（`gateway_upstream_latency_p99`/`upstream_conn_fail_rate`）、配置键（`pay.gateway.fallback.enabled=true`）、预案名（《支付通道切换预案》）。已完成：① `PromptAssembler` 加**规则 7（无条件注入）**点名这三类形状（规则 1 的笼统"禁止编造"被绕过）；② `qa_gen_quality_probes` 加 **warn-only** 探针（形如配置键/指标名/书名号的 token 回查语料，实测对上述 4 项全部点名、对语料内真实存在的键零误报）。**剩余**：要把它从 warn 升为**门禁**，仍须先给出"有据"的可判读定义（本表纪律），归"断言语态结构化门控"在案债务 |
| 引用洗白 / 出口引用-内容对齐校验（来源：`docs/qa/2026-09-11-persona-eval.md` P1-5，标"第五轮未复现，待复现再立项"） | 再次出现"答案的 `[参考N]` 标号与所指段落**并不支撑**该句断言"且被真人/QA 复现——判据：能指到具体某条答案的某个标号与它引的段落不匹配（不是"答案整体偏了"，那类归 V9 与门控） | **此前不在任何账上**（2026-09-27 复核时查出：QA 台账标了"待复现"，但本表没有对应行——正是本表纪律所指的"没有触发线的欠账"）。第五轮之后未再复现，故按触发线挂起而非现在做：该判据要判"标号是否支撑句子"，**判据本身需要裁判**，与 V9 的集合运算不是一类；且裁判与选手同源则无证据价值（需异构模型或人工标注小集），成本远高于 V9。**【2026-10-03 触发线已兑现·已立项第一步】**：live QA 实测复现同一形态——上述答案把上述 4 项无据内容挂在 `[参考1]` 下，而该标号指向的 chunk 只有标题块。已做：warn-only 探针同时点名"**语料外命名**"与"**该标号指向的段落是否含被引断言的关键词**"（后者需人工判读，故仍不进围禁）。**剩余**：升门禁前需异构裁判或人工标注小集——同上一行的定义缺口，本轮只推进到"可被发现、可点名" |

| Console 档位切换历史面板（**2026-09-28 定形**：只做离线时间线 + 录屏） | 需要在**无录屏场合**按时间回放档位切换——判据：有人要在现场/无录像环境复盘"切换发生在第几秒"，而看实时面板已经不够 | 面板已有实时档位灯 + 审计事件游标流，实验与演示现场的变化由录屏捕获；档位切换的**权威历史**是 14 天滚动的 `logs/audit.jsonl`（`ev=degrade_transition` 行，见 `CONTEXT.md` 降级域）。面板 ring 仅 200 条（`AuditService.RING_CAP`），在高并发那一刻**必然轮转**——用它做历史是选错量具。本项属 ADR-0009 只读面的扩展，届时须连 `scripts/check_panel_contract.sh` 一并扩 |
| 解析 W3C `traceparent`（OpenTelemetry 生态） | 出现**真实的 OTel 消费方**——判据：有调用方发 `traceparent` 且要求按它串链路，或需要跨服务传播 trace | 现只认自述型 `X-Trace-Id`（8–64 位词表，校验成本 ≈ 0）。W3C 格式固定 55 字符、含版本/标志位，多一层解析与校验分支而当前**无任何消费方**——按"不预防性施工"挂起 |

## 6. 公开 push 门闩（已执行记录：2026-09-10 清洗并首推 private）

**已了结**：历史 token 残留已于首推前用 `git filter-repo --invert-paths --path scripts/demo_tokens.txt` 抹除（验证 `git log --all -S "eyJhbGci"` 空），仓库以 **private** 推至 `github.com/shing26/opspilot-aiops`。**转公开前动作**：人工过一遍 README/ADR/报告渲染（本项目文档惯例是数字必须真），确认后用 `gh repo edit --visibility public` 切换。以下命令保留作再犯时的标准程序。

> **复核口径注（2026-09-13 转公开前门闩）**：自本节把验证串写进正文起，上述 `-S` 命令会命中本文件自身（命中物是命令示例文本，非凭据）。再复核改用：`git grep "eyJhbGci[A-Za-z0-9_-]\{40,\}" $(git rev-list --all)` ——期望**空输出**（真凭据必带长签名段；本行示例含字符类，不满足该模式故不自匹配）。

**背景**：`scripts/demo_tokens.txt`（合法 demo token）自初始提交 `75d0aea` 起被跟踪、`fc8317f` 摘除。这些 token 在现网**已实战失效**——P2 起过滤器要求 `tver` claim 匹配 DB，旧 token 无此 claim 一律 401——但公开仓库里躺着"形似有效凭据"的字符串本身就是钓鱼素材与审计事故，物理抹除是观感与合规要求，不只是风控要求。

```bash
# ① 本地 bundle 备份（此时尚无 remote；操作不可逆，先备份）
git bundle create ../opspilot-backup-$(date +%F).bundle --all

# ② 一次性安装
pip install git-filter-repo

# ③ 从全部历史外科手术式移除该文件（token 只存在于此文件，无需 replace-text）
git filter-repo --invert-paths --path scripts/demo_tokens.txt

# ④ 验证归零（必须无输出；注意 filter-repo 重写全部 hash，v1.0.0/v1.1.0 tag 会重指引，属预期）
git log --all -S "sre_l1=" -- scripts/demo_tokens.txt

# ⑤ 首次推送正常 push 即可（清洗先于任何 push，因此不存在 force 场景）。
#    filter-repo 会移除 remote 记录：重新 git remote add 后再推。
```

> 若已有他人 clone（本文件编写时尚无）：通知全员**重新 clone**，禁止在旧历史上继续 merge。

## 7. OpenAI 兼容面（/v1）

**用途定位**：/v1 是网关的标准协议出口（ADR-0007），价值在"任何标准客户端可直连"的兼容性证明与
"两协议面共享一条编排链路"的架构叙事——**它不依赖任何特定前端**。演示主形态是 curl 直播
（DEMO 幕⑦）；LobeChat/Dify 等如临时起意想点验兼容性，按下面连接配置自行接入即可（本仓库不再捆绑 UI 容器，2026-09-11 评估：UI 壳仅呈现"会答对的对话框"，与"运维系统可视化"诉求零交集，撤除止损）。

**API Key = 用户 JWT**（不是共享密钥）：

```bash
set -a && . ./.env && set +a    # DEMO_PASSWORD 只从 .env 来，缺这行必报 traceback
cd offline && .venv/Scripts/python.exe -c "import sys;sys.path.insert(0,'.');import localapi;print(localapi.login('sre-full'))"
```

**curl 直播**：`curl -N http://localhost:8081/v1/chat/completions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"model":"opspilot","stream":true,"messages":[{"role":"user","content":"how to fix 50012_DB_TIMEOUT"}]}'` → role 首帧 → content 增量 → 「## 参考来源」→ stop → `[DONE]`。浏览器型客户端 Base URL 填 `http://localhost:8081/v1`（宿主视角，容器服务名仅对 server 模式有意义）。

**故障排查**：401→token 过期或账号被禁用（重新 login；这正是吊销跨面生效的表现，其错误体为 OpenAI 标准 error JSON——QA P2-3 修复后不再回显 path）；连接被拒→`docker compose ps gateway`；无流式→看 `/api/v1/admin/metrics`（平台凭证）的 `reingest_busy` 与 audit `via=openai` 行；CORS→确认环回 origin（CorsConfig 仅放行 localhost/127.0.0.1 任意端口 + compose 内 `gateway:*`）。

**audit `via` 字段**：`sse`（原生面）/`openai`（/v1 面）/`search-api`（检索端点）；旧日志行无此字段，`daily_usage.py` 按 `.get()` 解析天然兼容。按面统计：`grep -c '"via":"openai"' logs/audit.jsonl`。

**调用方关联键 `X-Trace-Id`（2026-09-28）**：任何面都可带此请求头——8–64 位 `[A-Za-z0-9_-]`，**空白按未提供**处理，**非法即 400** 并落 `ev=invalid` 审计行（形状按面分流：`/v1` 保持 OpenAI error 信封）。它经 MDC 落到审计行 `trace_id`，用途是把上层 agent 的多步调用串成一次 incident——**只做关联，不改检索、不注入上下文、不存会话**（本系统无状态单轮，见 ADR-0007 修订注 / ADR-0013）。两个 id 各管一段，别混：

```bash
grep '"request_id":"a1b2c3d4"' logs/audit.jsonl      # 单次请求回查（服务端自生 8 位，服务端权威）
grep '"trace_id":"agent-run-7f3a"' logs/audit.jsonl   # 跨步链路（调用方给的，串一次 incident）
```

**错误体形状（2026-09-28 收敛）**：全仓只有两种，且由 `gateway/ErrorBodies` **单点定义**——`/v1` 面 `{"error":{message,type}}`（401 带 `code=invalid_api_key`、429 带 `rate_limit_error`），其余面 `{code,message}`（含登录面）。此前实测同一提交里跑着**四种**：还混着 Spring 默认体的 `timestamp/path` 与登录面的 `application/problem+json`——写客户端的人得为四种各写一条解析分支。自查一行（应看到 `code`，不该看到 `timestamp`/`type/title/instance`）：

```bash
curl -si http://localhost:8081/api/v1/copilot/search -X POST -H 'Content-Type: application/json' -d '{"query":"x"}' | tail -1
```

**档位切换复核**（不复盘不宣称）：档位每变一次落 `ev=degrade_transition`（`from`/`to`/`cause` 三字段，`cause` 有限词表见 `CONTEXT.md` 降级域），审计业务行另有 `degrade_level` 列标明该请求所处档位。**`mode=es_only` 不能单独当降级证据**——那个字符串有两个来源（L1 降级 / 检索腿超时）：

```bash
python scripts/audit_timeline.py          # 时间线：切换序列 + cause 词表校验 + 链式连续性
grep '"ev":"degrade_transition"' logs/audit.jsonl
grep '"cause":"inflight"\|"cause":"llm_failure"' logs/audit.jsonl   # 只看负载/上游导致的
# L1 的机器判据：mode=es_only **且** stage_ms.vector==0（单看 mode 会把"腿超时"读成"降级生效"）
grep '"degrade_level":"L1"' logs/audit.jsonl | grep '"mode":"es_only"' | grep '"vector":0'
```

**2026-09-28 实测结果（本轮首次真正触发）**：`offline/load/reports/l1_trigger.md`——唯一指纹场景 48 并发 30s，在途峰值 **137**（触发线 40），审计落 `L0→L1 cause=inflight`；同轮上游对 48 并发返回 429，再落 `L1→L2 cause=llm_failure`；60s 冷却到期回 `L0 cause=cooldown_expired`。**两条复现配方**（都要活体栈 + live key）：

```bash
# ① L1：唯一指纹场景（nonce 必须**纯字母**——数字/UUID 会被指纹归一化掩码掉，那样所有请求同指纹、
#    被 Single-Flight 折成一个 leader，在途永远到不了触发线）
cd offline && python load/sweep.py --levels 48 --duration 30s --scenario unique --report l1_trigger
# ② L2：可控失败注入（`LLM_MODEL` 覆写 → 不存在的模型名 → 上游 404 → 连续失败达阈）
#    **必须**先清缓存，否则近似 query 会被 L2 语义缓存回放掉、根本不走到 LLM（实测踩过：failures 卡在 2）
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/admin/cache/flush
LLM_MODEL=__opspilot_probe_invalid__ java -jar target/opspilot-gateway-1.0.0.jar
# ③ 回滚闸门（在 ①②之间必做）：等 /state 的 cooldown_s==0 且 level=="L0" 再起压，
#    否则残留熔断态会让压测的 worst 直接读到 L2、L1 实验作废
```

**启动与停机的五条纪律（2026-09-28 / 10-04 实测踩到）**：

1. **停进程按端口杀，别按应用自报的 pid**：`netstat -ano | grep :8081 | awk '{print $5}' | sort -u` 取 pid 再 `taskkill //F //PID <pid>`。
   按 pid 杀的前置是先 `source .env`——缺它 `localapi.login` 会抛错 ⇒ 变量为空 ⇒ `taskkill` **空转且不报错**（我连踩两次）。
2. **启动成功以日志为准**：`grep -q "Started OpsPilotApplication" logs/run-*.log`。
   只看 `/actuator/health` 或 `/state` 会读到**上一个还活着的实例**——本轮因此把"三种配额覆写方式都无效"当成了结论，
   实际是旧实例幽灵（新进程早已因 `Port 8081 was already in use` 而 `APPLICATION FAILED TO START`）。
3. **打 jar 前必须先停服务**：Windows 上 JVM 锁着 `target/*.jar` 时 `spring-boot:repackage` 覆盖不了它——**失败并留下一个被截断的 jar**（实测 84MB → 217KB），此后怎么起都是错的东西。顺序永远是：按端口停 → `mvn package` → 起。
4. **本机 8081 常年被占，网关请起 8091，并把 `OPSPILOT_BASE` 一起导出**（2026-10-04 实测）：
   ```bash
   export OPSPILOT_BASE=http://localhost:8091     # 与 -Dserver.port=8091 成对，缺一即踩坑
   ```
   `offline/localapi.py` 的 `BASE` **默认就是 8081**（`OPSPILOT_BASE` 覆盖口是 2026-09-24 为此加的）。漏导这个变量时，
   脚本会去打 8081 上那个**无关进程**，表现为 `Connection refused / WinError 10061`——而网关其实好好地活着（`/actuator/health` 200）。
   实测因此把"网关挂了"误判了两次，绕了两小时。**判据先分清三种状态**（同 E1「判据失效」纪律）：
   | 现象 | 先判什么 |
   |---|---|
   | 端口无监听 | 进程真没了 → 按 1 杀干净再起 |
   | 端口在听、连接被拒 | **客户端打错端口**（查 `BASE` 与 `OPSPILOT_BASE`），不是服务挂了 |
   | health 200 但 chat 全 500 | 去日志找中间件/Redis 错误 → 见第 5 条 |
5. **宿主内存不足时，容器会被 OOM 杀掉**（2026-10-04 实测：`exit 137/255` 连环出现），现象是网关日志刷
   `WriteRedisConnectionException` / `RedisTimeoutException: PING`，chat 全部 500，而 `/actuator/health` 可能还是 200。
   **处置顺序**：① 先看宿主可用内存（`powershell -NoProfile -c "…FreePhysicalMemory/1MB"`，<2GB 就先腾）→
   ② 停掉不用的栈（本机最肥的常是 `ollama` 类模型服务容器）→ ③ 重启僵死的中间件容器（实测 ES 立刻恢复、
   Qdrant 约 45s 完成 collection 重解析，health 才会回 200）→ ④ 最后重启网关。
   注意 Redis 容器**没有挂卷**：重启它会清空当日配额与 SOP 预热（L1/L2 缓存随之冷启动），审计 JSONL 在盘上不受影响。
   **JVM 被外部杀掉时日志没有 OOM 异常**——日志停在启动横幅就断，说明是进程被杀而非内部错误，别顺着 JVM 内存方向查。
6. **端口定案 = 8081，启动一律走 `scripts/start_gateway.sh`**（2026-10-05 定案）。此前每次启动都要现场探测端口，
   因为本机有别的项目在抢：实测 `8080`=nexus-web、`8091`=ShopPilot 的 biz-mock（**它的 `/actuator/health` 回 503**，
   极易被误读成"自己网关半死"）、`8092` 占用；8080/8091/8092 都不是本项目的。**规范端口仍是 8081**
   （`application.yml` 默认、`docker-compose.yml` 映射、README 与本文档三处同口径），不为绕开邻居而改端口——
   改端口会让文档与 compose 分叉，代价大于收益。
   `scripts/start_gateway.sh` 把三件曾经踩坑的事做死：① **端口占用预检并点名占用者**（含命令行，一眼认出是不是自己的旧实例）
   ② jar 缺失即拒启（打 jar 前必须停服，见纪律 3）③ **以日志里的 `Started OpsPilotApplication` 为唯一成功判据**。
   覆盖口在 `.env`（不入库）：`SERVER_PORT`（默认 8081）、`GATEWAY_HEAP`（默认 1024m，内存紧张时调小）。
   脚本同时 export `OPSPILOT_BASE`，避免第 4 条那种"漏导导致打错端口"。

**形态取舍（在案，勿顺手改回容器）**：本机 Docker 拉不到 base 镜像（`maven:3.9-eclipse-temurin-21` 超时），
故**网关走宿主裸进程 jar**，中间件仍走容器——这是 2026-09-16 实测后的取舍，不是遗漏。`scripts/quickstart.sh`
（容器全栈）仍是"clone 到任何机器"的可复现入口；本机演示/生产形态走 `start_gateway.sh`。

**外部存活探测怎么接线**（2026-10-05 新增，见 §5.1 盲区行）：`scripts/liveness.py` 只探不修，
出口是**退出码 + `logs/liveness.jsonl`**，投递渠道需自行选（本机建议接 Windows 任务计划程序）：

```bash
# 一次性探活（给计划任务）：退出码 0=健康 / 1=异常 / 2=地址非法
python scripts/liveness.py --once
# 常驻轮询（每 30s，连续 2 次不健康判死）
python scripts/liveness.py --interval 30
```

四态的**处置动作各不相同，别互串**：`healthy` 无事 ｜ `down` 查中间件（多半是 Redis/ES 被 OOM 杀，纪律 #5）
｜ `wrong_service` **去查端口被谁占了**（本机 8091/8080 常被别的项目占，纪律 #6）｜ `unreachable` 进程真没了，按纪律 #1 重启。

**能力边界（勿对外宣传）**：/v1 目前只有 `chat/completions`（stream=true）与 `models`；文件上传/语音/多模态对应端点未实现，任何客户端里点了即报错——协议面的演示只用文本对话。**归因纪律**：外部客户端出现"响应已返回但界面异常"时，先 `tail logs/audit.jsonl` 定位归属（有行且正常=该客户端渲染层的事），再排查——2026-09-11 在嵌入 webview 实测过此判据。

**环境注记（历史踩坑，若重接 LobeChat 会用到）**：`lobehub/lobe-chat` 在 `mem_limit:512m` 下 node 堆 ~256M、pdfjs 初始化 OOM 崩溃循环，需 2g；Windows Git Bash 的 `curl -d` 发中文按 GBK 出局（客户端 locale 陷阱），脚本发中文一律走 python。

## 8. 备份与恢复（只备份不可再生的东西）

| 数据 | 性质 | 策略 |
|---|---|---|
| H2 `./data/users.mv.db`（口令散列 + token_ver 吊销状态） | **唯一不可再生** | `scripts/backup.sh` 每日（网关活着走 `POST /admin/backup`、DB 所有者在线 `BACKUP TO` 事务一致；网关停了走 CLI 嵌入式。**禁止 cp 热拷运行中的库文件**） |
| `logs/audit.jsonl` | 合规留痕，logback **180 天**滚动（G0）会回收 | backup.sh 一并 tar（本地保 14 天） |
| ES / Qdrant | **派生索引，不备份**——chunks.jsonl 在 git（ADR-0001），恢复=reingest（实测 448 chunks 24.4s，live、服务端日志口径；mock 8.0s） | 无需动作 |

**审计防篡改（G1，2026-10-22）**：每行带 `prev_hash`/`hash`（SHA-256 链，首行 `GENESIS`），链头同步落 `logs/.audit-head`；校验：

```bash
python scripts/verify_audit_chain.py logs/audit.jsonl --head logs/.audit-head
```

它能证明**内容未被改/未被中间插入**；**不能**证明尾部未被删除——那需要异地只追加副本（如 S3 Object Lock/远端 syslog）。

异地副本（G1 模板，未接真实远端）：把仓库外目录挂到 `/mnt/audit-offsite`，配 `/etc/opspilot/audit-offsite.env` 的 `AUDIT_OFFSITE_DIR`，由 `ops/systemd/opspilot-audit-offsite.{service,timer}` 每日 02:30 执行 `scripts/audit_offsite.sh`。脚本复制后按副本再验一次链、生成 `SHA256SUMS` 并落地只读；真正的对象锁/远端不可变存储仍由挂载侧保证。

升级前的旧行没有 hash，校验器会标为 legacy 并允许其出现在链首，不会假装旧行已被覆盖。

cron（Linux 部署）：

```cron
0 2 * * * cd /opt/opspilot && bash scripts/backup.sh >> logs/backup.cron.log 2>&1
```

（Windows 本机：用 `scripts/install_windows_tasks.ps1` 注册 `OpsPilot-Backup`（每日 02:00，StartWhenAvailable 补跑），
不要靠手敲。G0 起备份成功后写 `backup/.last-success.json`（sha256/size/verified），网关启动后经
`aiops_backup_last_success_timestamp_seconds` 暴露，`OpsPilotBackupStale` 规则在 >26h 时告警。）

**恢复流程（H2 2.x Restore 要求目标库不存在=天然防误覆盖）**：

```bash
# 容器模式（compose full；./data 卷映射下宿主 CLI 与容器共用同一物理库文件，网关必须真停以让出文件锁）
docker compose stop gateway
mv data/users.mv.db data/users.broken-$(date +%F)            # 移走损坏库（留取证现场）
bash scripts/user_admin.sh restore --from backup/users-<日期>.zip
docker compose start gateway
# 裸进程模式同理：停宿主 java 进程 → mv → restore → 重启 jar
# 起服务后验证：/api/v1/admin/health 正常 + login 成功
```

> QA P2-8 注：本节此前通篇裸机命令（`Get-Process java | Stop-Process`），容器化部署照抄必炸且
> 与 §1 的容器声明自相矛盾——恢复前先确认自己跑的是哪种模式，停错对象等于没停。

**恢复完整性演练（不需要等灾难，网关可不停）**：`bash scripts/restore_drill.sh`（G0）——自动选最新 `users-*.zip`（含加密产物）、核对
`.last-success.json` 的 sha256、恢复到独立演练库、用 CLI `list --to-db` 校验账号数 >0 且每行 token_ver 可读、最后清理演练库并打印 PASS/FAIL。
它恢复的只是账号库，**不会**复活已吊销凭据——但生产恢复后必须强制全员重新登录（token_ver 回退的影响见门禁文件 §3.5）。

**网络注（G0 更新）**：默认已去掉 AUTO_SERVER；CLI 需在网关停止时以嵌入式方式打开同一文件。若部署仍显式启用 AUTO_SERVER，注意它走 Windows 防火墙常拦（LAN IP+随机端口），且 H2 TCP 无 TLS——只应在 `H2_DB_URL` 里显式开启。Windows Git Bash 里跑 `docker compose exec gateway ls /app/backup` 这类容器内绝对路径命令需前置 `MSYS_NO_PATHCONV=1`（否则 MSYS 会把 /app/... 改写成宿主路径）。

## 9. Ops Console 运维面板（只读可观测面，ADR-0009）

**它是什么**：浏览器开 `http://localhost:8081/`，点顶栏「运行面板」视图即得（Boot 默认资源处理器同源 serve，**零构建、零 CDN、无外部依赖**）。**2026-10-19 起 `/` 是统一工作台**（ADR-0021）：一份 HTML、两个视图，默认视图是排障台（§12），本节说的是第二个视图。把已有的 `/admin/state` 快照与 `/admin/audit/recent` 事件流渲染成**六块 + 一条 KPI 头条**，其中**「请求处理轨迹」是主角**——计数器回答不了"这台机器刚才替我做了什么、花在哪儿"，而审计行里本来就带着全部事实（`q` / `mode` / `cache_hit` / `degrade_level` / `refused` / `max_level` / `took_ms` / `stage_ms`）。KPI 条（请求总数 / LLM 调用 / 风暴收敛请求 / 缓存命中 / 每请求 LLM 调用）、「运行状态」（build 指纹 + 三依赖灯 + live/mock + reingest）、「降级状态机」（L0/L1/L2 分段档位 + 熔断计数与冷却倒计时 + MANUAL LOCK 徽标 + 在途/阈值 + 配额条）、「请求处理轨迹」（每笔请求一张卡，**全部字段取自审计行、无推断无补算**：查询原文、检索模式、缓存档、降级档、实际生效的权限上限、主体、指纹，附 `stage_ms` 分段耗时条——ES 词法 / Qdrant 向量 / RRF 融合 / 精排 / L2 写入（G1 起为异步入队开销） / LLM 生成，段宽=该段真实耗时、零值段不占宽度；拒答笔左边框标红并显式写"置信度门控拒答"、缓存命中笔标绿）、「请求到达率」（**采样持久化在浏览器 localStorage，刷新/关页/重启都不丢**；时间窗 5 分钟 / 1 小时可切，切换只改聚合粒度不改采样；每格 = 该时间桶内 `total_requests` 的真实增量之和，**不做补间**；图例标注每格秒数、窗口合计与峰值）、「数据流」（13 个真计数 + Single-Flight 在途指纹）、「事件流」（ev 七类全量流水，含鉴权/非法/降级切换/跨租户回放告警）。**分区数以 `index.html` 的 `<h2>` 为准，README「Ops Console」节同源描述**。

**已撤除的两块（2026-10-06，用户判断"没什么作用"）**：原面板上的「能力边界」与「AIOps 谱系定位」两块**静态**声明已从面板移除，连同承载它们的 `<details>` 折叠区与 `BOUNDS`/`SPECTRUM` 两个常量数组。理由：一字不变地挂在监控屏上不产生任何信息，还和活数据抢注意力。**内容没有丢**——仍在 README「AIOps 谱系定位」五域表与本文档 §5 债务闹钟表里。**该自证的地方是文档，不是值班屏幕。**

**视觉口径（2026-10-05 重做，勿回退）**：① **颜色只承载状态**——绿/黄/红仅出现在依赖灯、降级档位、事件级别三处，装饰性色彩一律不用；② **字体分职**——标签正文用系统无衬线，等宽只留给数字、键名与日志行（原版全页等宽，是"极客工具"观感的主因）；③ **文档性文字墙折叠**，仪表盘让给活数据。④ **CJK 标题不加字距**——汉字是等宽方块，`letter-spacing` 照搬拉丁大写标题只会让中文松垮，字距只留给纯拉丁片段；⑤ **块级间距走刻度**（`--s1..s5` = 4/8/12/16/24），组件内部微观间距按手感值给（日志行内边距 10px 是对的，硬套刻度只会变松）；⑥ **可点的东西都要有 hover 与 `:focus-visible` 焦点环**——面板是值班时长时间盯的屏幕，键盘可达不是装饰。**诚实性红线不变**：速率柱不做补间/平滑，所有图形来自真实计数器，无插值动画、无假数据——被换掉的只是皮肤与信息层级，数据面一字未动（`scripts/check_panel_contract.sh` 四层契约仍是权威）。

**实测基线（改样式前先量，别凭感觉）**：KPI 卡 111px 等高、依赖卡 39px 等宽等高、计数格 77px 等高、块间距统一 16px、`#7D8894` on `#0E1117` = 5.24:1 / on `#161B22` = 4.80:1（均过 WCAG AA，压线的是后者）。这些是回归基准：改动后若均匀性被打破，先查几何再谈好看。

> **重做时踩到的坑（2026-10-05 自曝并已修）**：视觉重做一度把事件流的 `badge-l1` 类丢了——那是**缓存命中（L1/L2）的语义标注**，让"这条是回放不是新算的"在事件流里一眼可见。它不是装饰、门闩也查不到（契约只比对路径与键，不比对 CSS 类），**靠人眼发现**。教训：**语义标注不算功能、不进契约，但丢了就是丢了**——重做数据面时逐项对一遍，别只顾着查"字段有没有消费"。
>
> 另有一处**有意**移除：计数墙不再显示原始指标键名（旧版每格是「请求 total_requests」）。这正是"极客感"的主要来源，且运维看的是量级不是字段名；但**契约门闩仍要求键名字面量存在于 HTML 源码**（`const keys = [...]` 块），所以机器校验照旧，人眼不再显示——若日后要恢复显示，只改 `renderTiles` 的标签行即可。

**它为什么长这样**：它是 LobeChat 撤壳的反面答案——撤的是聊天壳（承载不了运维真相），建的是仪表盘（把 JSON 真相摆出来），**不新增状态源、不承载对话交互**。所以"好看"的边界也在这儿：皮肤可以重做，状态源不行。

**速率图的三条诚实性纪律**（ADR-0009"无假动画/无假数据"在这段的具体化，改图时别破坏）：
1. **只画真实增量**——格高 = 该时间桶内 `total_requests` 的真实增量之和，不补间不平滑。
2. **两类空白必须可区分**——真的是零 → 实心矮条；**采集中断**（网关不可达/标签页关闭）→ 斜纹条。把"没数据"画成"零"是监控里最常见也最有害的一种撒谎。
3. **计数归零要显式**——`total_requests` 是进程内计数器，网关重启即归零；检测到回退就丢弃旧序列并在图例标注，**而不是画出一段假的悬崖**。

**已知边界（如实登记）**：采样是**浏览器侧**的，所以序列只从"某个页面开始打开"起才有数据——历史留存靠 localStorage 传递，跨浏览器/无痕窗口不共享。真正的服务端时序要等 `/state` 侧提供历史序列（触发线：需要跨会话/多人共享的监控视图时）。

**缓存纪律（2026-10-05 补）**：`index.html` 走 `spring.web.resources.cachecontrol.no-cache`——面板是**部署形态的一部分**，不是可缓存的静态库；改完版重新部署后拿到旧 HTML 会让运维以为改动没生效（本轮连续两次撞到）。无此配置时浏览器靠 `Last-Modified` 启发式缓存。代价≈0：单文件 HTML、零外部依赖，没有第二份可缓存产物。

**接入**：运行面板视图与右上角健康抽屉需 **role=platform ∧ auth_level≥3** 的 JWT（数据端点全部受守卫；
HTML 壳本身匿名可开、零信息）。**页面侧的凭据入口只有一个**——右上角账号胶囊里填用户名 + 口令 →
`POST /api/v1/auth/login`（ADR-0021 决策 4）。token 存 **`sessionStorage`**：刷新不掉线、关标签页即失效。
**必须诚实标注**：它不是安全升级（同源脚本照样读得到），只是**生命周期从 24h 收窄到"这一次标签页"**。
页面启动时会主动清掉旧的 `opspilot.jwt`——页面侧的"粘贴 token"入口已退役。

非平台身份（如 `sre-acme`：level 3 但 role=sre）在抽屉与运行面板视图里看到的是**锁定态**，不是空数据；
**门禁一字不动**，也没有为这个页面新开非 admin 的状态端点。排障台视图不受影响：任意有效 JWT 都能发起查询。

`scripts/console_token.sh` 仍然在，但它服务的是 **curl / 探针 / 自动化**，不是页面：

```bash
bash scripts/console_token.sh            # 打印 role=platform（sre-full）的 JWT，给脚本用
bash scripts/console_token.sh --copy     # 顺带放进 Windows 剪贴板
```

脚本内部即"source .env → `localapi.login('sre-full')`"（`DEMO_PASSWORD`/`JWT_SECRET` 只从 `.env` 来，不落盘）。
token 只在**本机终端**打印。**别把输出转发到聊天或工单**——它是可用的运维凭据。

**三态排障**（文案各不同，别互窜）：
- 401 → token 过期/无效：页面弹「凭证已失效」遮罩并**停止轮询**，点它回到右上角账号胶囊重新接入。
- 403 → 该 token 账号**不是 platform 角色**（密级高≠可信，见 ADR-0008）；sre-acme 是 level 3 但 role=sre，正属此态，不是 bug。抽屉与运行面板视图渲染锁定态并**停轮询**（不拿 403 反复打端点），排障台照常可用；换 sre-full 即可。
- 网关不可达 → 降频轮询并显"网关不可达"，先 `docker compose ps gateway`。

**读它的关键位（演示底幕）**：
- 顶栏 build 指纹（version·jvm·uptime·pid）——`build-info` goal 注入，传达"这是被部署过的服务"。
- 「降级状态机」区：L0/L1/L2 灯 + 熔断 `K/3` + 冷却倒计时 + `MANUAL LOCK` 徽标（区分手动锁 vs 自动熔断）。幕⑥指着它讲。
- 「数据流」区：`Single-Flight 在途组` + 计数墙（相邻两次真值求差显示 `+n`，速率是算出来的、非动画）。幕④风暴瞬间指"在途组跳 1、收敛墙爬、LLM 只 +1"。
- 配额水位条：当日 `used/limit`（读 `quota:<sub>:<date>`，**只 GET 不 INCR**——打开面板看不消耗配额）。

**契约防漂移**（改名即红，两道闸）：`scripts/check_panel_contract.sh`（CI 独立 job，零服务）比对 HTML fetch 路径↔Controller `@GetMapping`、OpsMetrics 键↔面板 tiles、`/state` 键集双向；`demo.sh` 第 7 检是 live 键集合断言。Java 侧改任一被面板消费的键而不同步 HTML，CI 点名 `面板引用了后端不存在的键`。

**运维预期**：面板是进程内 ring buffer（最近 200 条审计事件，重启清零）+ 游标轮询（前台 2s / 标签页隐藏自动降 30s）——**成功读路径不落审计**（实测轮询 60s，audit.jsonl 行增量 0），所以面板自身不会污染它展示的证据流；事件缓冲轮转/服务重启导致的缺口以 `truncated=true` 显式标 ⚠️，绝不做连续假象。轮询周期是前端常量 `cadence()`，调它不改后端。

## 10. 中间件异常后的恢复动作（2026-09-17 实测）

Docker Desktop 重启（升级/崩溃自恢复）会给本机留下三类**看起来正常、实际不可用**的坑，
三者都不报错、都让"容器健康"与"服务可用"背离，各有一条固定恢复动作：

| 症状 | 判据（怎么确认不是应用 bug） | 恢复动作 |
| --- | --- | --- |
| **引擎本身 500**（2026-10-06 实测，比上两行更早一级） | `docker ps` 报 `request returned 500 Internal Server Error for API route .../containers/json`；容器、端口、health 全无从谈起；宿主内存**充裕**（排除 OOM） | 先 `wsl -t docker-desktop` 强杀发行版再启 Docker Desktop；仍 500 则**全量重启**（`Stop-Process -Name 'Docker Desktop','com.docker.backend'` → 启 `D:\Docker\Docker Desktop.exe`，本机装在 D 盘非 C 盘）。恢复后中间件按 `restart: unless-stopped` 自启，网关客户端连接实测**自愈**（~2 分钟内 health 回 UP，无需重启网关——与下一行的"不自愈"形态不同） |
| 宿主端口代理失效 | 容器内 `redis-cli ping` 通、`docker exec … curl localhost:9200` 通，但**宿主**连 `127.0.0.1:6379/9200/6334` 被接受后零字节返回（裸 socket `PING` 收到 `b''`）；网关启动报 `RedisTimeoutException: Command execution timeout for command: (AUTH)` | `docker compose restart <service>`（实测 redis / elasticsearch / qdrant 三个都需各自重启一次；`restart` 会重建端口映射） |
| 长跑网关的中间件连接不自愈 | 中间件起来之后网关仍 `health=DOWN`、检索返回空、**登录 500**（根因是登录路径要写 Redis 的 `auth:fail` 计数器，连接已失效 → `WriteRedisConnectionException`），实测持续 3 分钟以上未自愈 | 重启网关进程（客户端连接重建）；数据在命名卷里，重启后核对 `health.es.value` / `health.qdrant.value` 是否仍等于语料数即可确认无损 |

**顺序**：先修端口代理（`restart` 中间件）→ 确认宿主侧协议可达 → **再**重启网关。
反过来做会得到"起来了但仍然 DOWN"的假象（客户端在端口还是坏的时候完成初始化）。

**注**：登录 500 这个形态曾经把人往"账号库坏了"上带——判别法是看 `logs/app.log` 里那条异常的类名：
`WriteRedisConnectionException` 指向 Redis 连接，不是 `JdbcSQLNonTransientConnectionException`（那才是账号库）。

## 11. SLI/SLO 承诺表（承诺线 + 多久核一次、怎么核）

> 与 README「核心指标（实测）」的分工：README 给访客看**实测值**，本表给管理员**承诺线与核色动作**。
> 表里凡可从库内产物确定性派生的数字，由 `offline/doc_numbers.py` 在 CI 守着（改表不改产物即红）；
> live 实测类（TTFT / 可用性 / 幻觉率）含波动，按既有纪律**不登记**，靠人工按频次核色。

| SLI | SLO（承诺线） | 实测 | 核色方式与频次 |
| --- | --- | --- | --- |
| 精确符号 Top-1 命中率 | 100% | **100%** | 每次动语料或换 embedding 后端：`cd offline && python eval/build_golden.py && python eval/evaluate.py`，看报告 `modes.hybrid.exact.hit@1` |
| 语义 Top-3 召回率 | 100% | **100%** | 同上，看 `modes.hybrid.semantic.hit@3` |
| 权限泄漏率 | 0 | **0** | 每次动权限链（filter / 租户 / 密级）：跑 `evaluate.py` 的 jailbreak 段，看 `jailbreak.leaked` |
| 幻觉率（错误码接地） | 0 | 待 live 补 | 每次 live 回归：`cd offline && python qa_gen_quality_probes.py V9`（复用 V2/V4 答案，零额外 chat 预算） |
| 降级期失败率 | 0 | 待 live 补 | 每次压测：`offline/load/sweep.py` 六档，看各档 Failure Count |
| TTFT P95 | < 3s | 待 live 补 | 同上；热点命中口径另见 `offline/load/reports/l1_hit_latency.md` |
| 可用性 | ≥ 99.5% | 待 live 补 | 同上（失败请求数 / 总请求数） |

**核色前置（每次核色前先跑，不通过就别开始）**：

```bash
set -a; . ./.env; set +a          # 让 DASHSCOPE_API_KEY 进入环境
python scripts/check_upstream.py  # 三路（LLM / embedding / rerank）全通过才 exit 0
```

**为什么这一步不是可选自检**：本系统的降级设计会**静默掩盖**上游故障，两条路径都返回 HTTP 200
且都不报错——embedding 断 → 向量腿 `degraded` → 检索退化为 `es_only`；LLM 连续失败达阈 → 熔断 L2
→ 全部请求直出静态 SOP。**只有 `mode` 字段才看得出**。于是核色会在一个已经降级的系统上跑完，
产出一整套看似正常、实则无效的数字。2026-09-24 真踩过：`cache_savings` 首跑报出"命中率 0.0%"，
看着像"缓存无效"，实际是 26 条请求全走了 L2 SOP 兜底（该路径按设计不写 L1）——该报告已作废。
账户欠费时三路返回 `code=Arrearage`，脚本会点名到该 code 并以非 0 退出。

**已有随附产物的 SLI**（核色时直接看产物，不靠记忆）：门控的误拒/漏拒率与阈值扫描 → `offline/eval/reports/gate_matrix.md`；真实输入上的零召回率与快路径命中率 → `offline/eval/reports/observed_probe.md`；热点命中延迟 → `offline/load/reports/l1_hit_latency.md`。表内"待 live 补"的几项属**生成面**指标，三路探活通过后按右侧命令补跑。

**为什么是这几条**：每条各对应一条核心主张——检索得准、不越权、不编造、过载不拒服务、快。
刻意**不承诺"平均延迟"**这类聚合量：聚合会把长尾藏起来，而排障场景的体验恰恰由长尾决定。

**降级期的核色口径**（见 [ADR-0012](docs/adr/0012-degradation-cost-semantics.md)）：L1 期的检索质量按
`es_only` 那列核（语义 Hit@1 由 88.2% 退到 70.6%，同构关系有单测锁定），且门控退为**零召回级**——
故"幻觉率 = 0"这条在 L1 期的保障强度是弱化的，核色时必须注明当时档位，否则数字会被读成 L0 口径。

## 12. 排障查询页与只读行动契约（ADR-0017）

**入口**：浏览器开 `http://localhost:8081/`——**默认视图就是排障台**（旧地址 `/troubleshoot.html` 是薄壳，会跳过来，书签与文档里的老链接不失效；ADR-0021 决策 1）。**凭据在右上角账号胶囊里换**：用户名 + 口令 → `POST /api/v1/auth/login`（口令只走请求体、用完即弃，不落盘；localStorage 只记**用户名**，token 存 `sessionStorage`）。**门禁是任意有效 JWT**，不是 platform-only：login 成功即身份确认（ADR-0020 第 1 条），所以这个视图谁都能用，不与运行面板共用同一道门。接入后胶囊折叠成「● <用户名>」，点开可「切换」。

**报告卡是四层黄金流**（自上而下，改动前先读 ADR-0020）：① 运行态指标顶栏（`cache L1/L2` · 降级档位 · 快路径 · 检索 · TTFT · 总耗时）→ ② **只读行动契约置顶**（编号步骤 + 等宽命令 + 来源面包屑 + 复制按钮，卡片头固定「只读 · 网关不执行」）→ ③ Markdown 诊断正文（`[参考N]` 角标）→ ④ **带序号的参考来源条**（`[1]`/`[2]`/`[3]`，与正文角标一一对应，悬停角标高亮对应来源）。左栏另有 4 个**生产场景预设** chips（`MySQL 连接池耗尽` / `支付网关 502` / `订单创建超时 · 慢 SQL` / `模糊追问`）——**都是单身份场景，一律不改模式**（ADR-0021 决策 5）。未接入时点预设会把输入挂起并打开账号胶囊，接入成功后自动发起，不必再点一遍。

**对照模式（已降为高级入口）**：右上角「**高级 · 权限隔离演练**」→ 勾选「用两个身份各问一次」并接入身份 B → 画布由单栏切为 `1fr 1fr` 双栏并排，底部挂**差集对照表**——`引用集差集`/`命令条数`/`拒答` 这类差异行加琥珀左边框，`cache_hit`/降级档位/快路径/检索耗时 一致行保持灰字。判据是**引用集差集**（低密级侧不该出现高密级 chunk），不是"两边措辞不同"；时间行按抖动容差判同，3ms 的 TTFT 差不当作分叉。跨身份/跨租户的对照用例只挂在这个入口下，**不进左栏预设**。实测分叉用例：`支付网关 502 50041_PAY_GATEWAY_502 排查` → `sre-full` 引 `rb-005 / pm-007`，`sre-limited` 引 `order-center-api / rb-004 / rb-006`，两侧 `cache_hit`/档位/快路径/检索耗时全一致 ⇒ 分叉发生在检索引擎层，不是模型发挥。

**覆盖范围与拒答导航（ADR-0023 决策 5，2026-10-19 加）**：左栏预设下方常驻一张**覆盖范围**卡片——按调用者身份**现算**的服务数 / 错误码数 / 语义块数 + 最近重建时间（`GET /api/v1/copilot/coverage`），点「展开」列出全部服务名与错误码，**点一个就填进输入框**（省掉手敲服务名/错误码；**只填不发**——替用户把查询发出去是越权）。它不占配额、不落审计（只读元数据，与 `/admin/state` 同款口径），且**清单本身就是越权面**：给 `sre-acme` 列出 tenant-internal 的服务名等于把语料边界泄出去，故过滤与检索走同一份身份条件（`EsSearchService.identityFilters` 单一事实源）。拒答时同一份清单里**最接近的标识符**会出现在报告卡里（`done.near_miss` 只带 `service` + `errorCodes`，**不带正文**——把低置信度的 chunk 正文摆出来，用户就会拿它当答案用，而那几段可能是**别的故障**的手册，等于作废「拒答」这个信号）；零召回时如实写「没有接近的标识符可导航」，并给一个「上报覆盖缺口」按钮走**既有** `POST /api/v1/copilot/feedback`（`verdict=down`，按 fingerprint 归档，落审计 `ev="feedback"`，不占配额）。点标识符**不自动重发**：重发会再吃一次配额与审计，且「接近」不等于「命中」，值不值得再问由人定。澄清态（`gate=clarify`）**不渲染**这个块——那是"你问得不清楚"，不是"知识库没有"。

**它不是"建议执行"**：网关只描述该跑什么，自己不执行、不推导写操作、没有任何执行回路（ADR-0013）。命令文本全部从本次引用的 chunk 原文逐字抽出，判据是四层只读策略（工具白名单 / 子动词闭集 / 外壳负载递归 / 旗标与重定向），认不出的形态一律判拒并计数。**复制出去由运维自己动手**，与本网关无关。

**判拒怎么看**：`aiops_guard_action_commands_rejected` 只在 `/actuator/prometheus`，不在 `/state` 面板契约里。它涨 ≠ 有问题，只说明"这次抽到的命令里有判拒的"；反过来说，**这条数长期为 0 而 action 数很多**，才该怀疑策略被改成了恒真（`ReadOnlyActionExtractorTest` 的扫描下限就是干这个的）。

**活体复核（契约的不变式在线上怎么核）**：`cd offline && python action_contract_probe.py`（前置：栈已起、`.env` 已 source）。它把 ADR-0017 的不变式搬到 live 流量上逐条核——`done` 帧带 `actions` 键、每条命令**逐字于 `ref` 指向的 chunk 原文**、契约 ⊆ 引用（含段名闸、写操作段黑名单、编号 1..N）、破坏性形态绊线、同一 query 重发后 actions 逐条相同且 `cache_hit` 非 none、**跨租户不出界**（拿内部租户也有的错误码问 `sre-acme`，其语料零围栏块 ⇒ 契约必须空）、拒绝计数器单调不减。**自己也只读**：只打 login + chat/stream + actuator/prometheus，`/admin` 一条不打——连 `cache/flush` 也不打，没有判据需要它。跑完末尾几行 `INFO` 是各形状产额，**不是门禁**——产额是上面那条软上限的事。跑红时先看 `FAIL` 行名分类（`ac2` 逐字 / `ac3` 闭包 / `ac4` 段名闸 / `ac5` 红线 / `ac6` 回放 / `ac7` 租户 / `ac8` 指标），再看 detail 里的 chunkId；判据极性由 `offline/tests/test_action_contract_probe.py` 用合成坏响应逐条钉着（五项变异实测全红、复原全绿），所以跑红基本不是探针抽风，是被测面真变了。

**契约为空是正常的**：段名闸只认标题含「排查」的段，`rb-101~105` 本机辖区手册把命令写在「处置」段（那些段整体含写操作），按设计整段不进契约——空表照样下发（`actions: []`），不是链路断了。要覆盖某个手册，去语料里把该段标题改成含「排查」。

**活体复核（左栏预设与覆盖范围）**：`cd offline && python preset_probe.py`（同样前置：栈已起、`.env` 已 source）。三组判据：**P-1** 预设 query **从 `index.html` 现读**并与期望表对齐——改了页面不改探针就红（防止"页面改了、承诺没人核"）；**P-2** 每个预设的 `gate` 精确断言，命令条数**只锁有无不锁产额**（沿用上面 action_contract_probe 的同一条取舍，锁死会随 top-3 扰动假红），并额外断言**澄清态不得带 `near_miss`**；**P-3** `GET /api/v1/copilot/coverage` 的三个计数与两份清单必须与 `offline/corpus/chunks.jsonl` 按 (tenant, `auth_level ≤ level`) **现算的结果逐项相等**，外加「低密级 ⊆ 高密级」与「外部租户清单里不出现语料中内部独有的服务」——后者的「内部独有」**取自语料而非取自回包**，从回包取差集会让断言恒真（自证式空转门闩）。**自己也只读**：login + chat/stream + coverage，`/admin` 一条不打。判据极性由 `offline/tests/test_preset_probe.py` 钉着（五路变异实测全红、复原全绿）。

**报"某条我明明在原文里看到却没出现在清单里"时**：先看它是不是在非「排查」段里，再看是不是撞了已知的保守偏差（SQL 字符串字面量含写关键字、PromQL 无命令形态、awk 的 `$(NF)` 撞命令替换判据、进程替换的合法读用法如 `diff <(sort a) <(sort b)`、ssh 旗标闭集外的 `-v`/`-o`/`-L` 等形态）——全部在 ADR-0017 的软上限表里登记着，是取舍不是缺陷。

**2026-10-11 安全评审批的口径变化**：纯读工具的旗标级写面收紧——`env` 移出白名单（它是执行器，`env rm` 首词会过任何白名单）、`sort -o`/`openssl x509 -out` 落盘、`date -s`/`dmesg -C`/`journalctl --vacuum-*`/`hostname <名>`/`ifconfig` 判拒；进程替换 `<(…)`/`>(…)`/`=(…)` 与 `$(…)` 同罪判拒；`ssh` 只认闭集内取值旗标，host 之后的负载全量递归校验；占位符掩码只认纯 `<名>` 段（`<in>out` 的 `>` 是真重定向）。语料零翻转（现役 41 条契约原样成立），判拒计数从此会把这些新形态也算进去——判拒上涨 ≠ 故障，对照本条与 ADR-0017 的评审批段读。

**报"这次怎么一条命令都没有"时**（不是链路问题，是 top-3 引用集的问题）：契约只从**本次引用的三个 chunk** 里抽（`final-top-k: 3`，契约 ⊆ 引用是构造保证）。实测 11 个固定查询形状里 6 个非空，落空那 5 个引用的全是文档标题、段头（`排查步骤`）和症状摘要这类 chunk——命令确实在语料里，住在 `排查步骤 > 第N步` 的**编号叶**里，只是排在第 4 名之后没被引用。看 `done` 帧的 `refs` 面包屑就能当场判：引用的三段里有没有"第N步"字样的叶，没有就是本条，与策略松紧无关。页面对这种情形渲染的是**单行紧凑 Callout**（固定 34px：`本次引用切片不含可执行代码围栏——属引用集物理性质，非链路故障`，详细解释挂 hover title），**刻意不区分成因**、也不占正文高度——把它渲染成"链路故障"或一个大空盒子都是误导（ADR-0020 第 7、8 条）。这是登记在案的软上限（ADR-0017 软上限表 + §5 闹钟表「行动契约的 top-3 产额」一行），两个候选修法各有代价，未到触发线不动。

## 13. 告警侧（Prometheus / Alertmanager / bridge）

**它是什么**：外部监控 adapter 的第一个真实输入端。此前所有"真实告警"的**来源**都是本机自己的运行态
（§5.1 的自举生产者、`watchdog_alerts.py` 的 Docker 状态翻转）；这一段补的是"由本系统管不着的组件
产生、经标准协议投递进来"的告警。**定调：探测性研究产物，不是产品接入面**——网关不加端点、
不开新的鉴权维度（见 §5.1 那一行的状态注）。

**四件东西与它们的分工**：

| 组件 | 在哪 | 干什么 |
| --- | --- | --- |
| Prometheus | 容器（`alerting` profile） | 抓宿主 8081 的 `aiops_*`，按 `ops/alerting/rules/opspilot.yml` 判**九条规则**（业务三条 + 自监控六条：up/备份龄/审计写失败/在途/Redis down/磁盘余量） |
| Alertmanager | 容器（`alerting` profile） | 按 `group_by` 收敛后投递：主 receiver → 宿主 8089（bridge）；子路由 `continue` → 宿主 8090（notify，人类出口） |
| `alert_bridge.py` | **宿主**（与网关同形态） | 收 `/api/v2/alerts` → 逐条摊平 → 打 `/api/v1/copilot/chat/stream`；**全败返 503**（让 AM 重试），部分失败 200+failed |
| `notify.py` | **宿主**（G0 新增；G1 多渠道路由） | 收同一份 Alertmanager webhook → 按 `OPSPILOT_HUMAN_CHANNEL=auto` 自动识别 Slack / 飞书自定义机器人 / 企微，或 Telegram（token+chat_id）；识别不了按 raw 转发 relay。未配置则落 `logs/human-alerts.jsonl` + WARN，**不静默丢** |

**为什么 bridge 在宿主**：它复用 `offline/localapi.py` 的 `assert_local`——出口白名单只允许 http + 环回，
且校验解析后的 IP。容器里的 localhost 指向容器自己，够不到宿主网关；为部署整齐去松那条 SSRF 防线
不划算。所以是**容器回连宿主**，两容器都必须有 `extra_hosts: host.docker.internal:host-gateway`
（Linux 的 Docker 不默认提供这个名字，缺了不报错、只静默投递失败）。

**起法（顺序有讲究：先网关，再桥，最后监控容器——否则规则 fire 时没人接）**：

```bash
bash scripts/start_gateway.sh                                  # ① 宿主网关（8081）
python scripts/alert_bridge.py                                 # ② 宿主桥（8089，排障输入）
python scripts/notify.py --port 8090                           # ③ 宿主人类通知（8090；未配 webhook 则落 JSONL）
docker compose --profile alerting up -d                        # ④ 监控容器（9090 / 9093）
```

桥自己读仓库根的 `.env`（`load_env_file`，**不覆盖显式 env**）取 `DEMO_PASSWORD`，故不再依赖
`set -a && . ./.env` 这段 bash 语法——本机没有可用 bash 也能起（2026-10-17 补）。

**人类出口（G0，2026-10-22）**：此前唯一 receiver 是 bridge，而 bridge 的终点是被监控的网关本身——
"网关自己挂了"时，告警与排障同时消失，没有人会收到任何东西。现在 Alertmanager 的第二个 receiver
指向 `notify.py`（宿主 8090，Docker Desktop 下经 `host.docker.internal` 回连；原生 Linux Docker 的
`host-gateway` 指向网桥 IP，环回绑定不可达——这是 bridge 既有的同一条边界，跨过它需要改监听面并评审）。
`OPSPILOT_HUMAN_WEBHOOK` 为空不是错误：notify 会把每条告警写进 `logs/human-alerts.jsonl` 并 WARN，
"有人工通道但没配"与"没有人工通道"必须能区分。

**渠道配置（G1，2026-10-22）**：默认 `OPSPILOT_HUMAN_CHANNEL=auto`，粘贴 Slack/飞书自定义机器人/企微
webhook URL 即可，不用写 relay；Telegram 另填 `OPSPILOT_TELEGRAM_BOT_TOKEN` 与
`OPSPILOT_TELEGRAM_CHAT_ID`；飞书自定义机器人若开启签名校验，另填 `OPSPILOT_FEISHU_SECRET`
（否则飞书回 code=19021，notify 会判失败）。飞书 App ID/Secret 本版不支持（还需要
tenant_access_token、明确 chat_id 与机器人权限）；微信个人号/QQ 个人号无稳定官方 API。
systemd 形态用 `ops/systemd/opspilot-notify.service` + `/etc/opspilot/notify.env`。

`alerting` profile 是**独立**的：基础栈（redis/qdrant/es）没起也能单独把这两个容器拉起来，
它们只会看到 target down。反过来说，只跑基础栈时这两个容器不会被启动（不占那约 400MB）。

**四条核色命令**（"跑通了没有"就靠这四条，别靠感觉）：

```bash
curl -s localhost:9090/api/v1/targets | python -c "import json,sys; t=json.load(sys.stdin)['data']['activeTargets']; print([(x['labels']['job'], x['health']) for x in t])"
curl -s localhost:9090/api/v1/rules   | python -c "import json,sys; g=json.load(sys.stdin)['data']['groups']; print([(r['name'], r['state']) for grp in g for r in grp['rules']])"
curl -s localhost:9093/api/v2/alerts  # Alertmanager 手里的活告警
curl -s localhost:8089/healthz        # 桥在不在听
```

**台账在哪、读什么**：`logs/alert-bridge.jsonl` 每行一条处置记录，字段
`group_key / status / alertname / job / error_code / query / alert_id / alert_id_basis / fp / cache_hit / deduplicated / ref_docs / http / sent_ok`。
**`fp` 的分布就是这段接线的研究产出**——真实告警标签经指纹归一化后，哪些该收敛的收敛了、
哪些被 `<N>`/`<HEX>` 掩码意外抹平了，都在这一列里。`skip` 字段回答"为什么这条没发"：
`empty_query`（注解空）/ `hour_budget` / `quota_reserve` / `group_cap`（单组超限，带 `dropped` 计数）。
**`alert_id` 与 `alert_id_basis` 成对出现**：前者是网关消费的哈希身份，后者是哈希前的
`alertname|job|error_code|status` 四元组——对账"这条 `fp` 是哪条规则来的"靠它，不必反推哈希。

**2026-10-17 首次实机验收的结论与它引出的债务**：三条规则全部走通（触发手段全走真实故障），
台账与 [qa/2026-10-17-alertmanager-intake.md](docs/qa/2026-10-17-alertmanager-intake.md) 在案。
**同时实证了一处契约错配**：Alertmanager 的规则模板把 `{{ $value }}` 渲染进注解，
于是同一条告警每次重发的文案都带一个新数字——而指纹直接吃 query 全文，
`<N>` 归一化那条防线**只保护自产告警文案、保护不到外部模板渲染的文案**。后果是
同一条告警的 L1 缓存永远 miss、SingleFlight 永不合并。**这不是桥的实现缺陷**（桥只拼接
`summary` + `error_code`，数值来自规则自己的模板），是"指纹契约 vs 真实告警文案"的错配。

**处置（2026-10-17 当日定案并实施，方案 C = 服务端拆身份）**：不动规则、不让桥替网关做归一化，
而是把"这条告警是什么"改成由调用方显式给出——桥从 `alertname/job/error_code/status` 四元组
定长派生 `alert_id`（**与 message 无关**），网关把它当**不透明身份**算指纹；L1 键与 SingleFlight
组键改吃这个指纹，不再自己哈希 query。人工提问与既有自举源不传该字段，逐字回退到原文本
指纹，行为不变。契约由 body 第五个字段承载：**线路名是 `alertId`（camelCase，Jackson 默认
命名策略）**，可选、空白按未提供、形状非法即 400。**注意别写成 `alert_id`**——snake_case 不会
报错，而是被静默忽略、身份退回文本指纹（2026-10-17 live 验收实测抓到，qa §6）。**产品面只多
一个可选 body 字段，无新端点、无新鉴权维度**。台账字段随之新增 `alert_id`（哈希后的身份）与
`alert_id_basis`（哈希前四元组，供对账）——台账键是桥自己的落盘约定，与 HTTP 线路名无关。

**仍未解决（独立决策，别混进方案 C）**：台账 §3.5 实证了"**同身份在降级/失败路径也会重复穿透**"——
`runPipeline` 的 L2 分支与 LLM 失败 catch 都在写 L1 之前 return/抛出，L1 只缓存"成功且非 SOP"
的答案。拆身份解决的是"身份碎"，**不解决"路径不写缓存"**；那是"降级期答案要不要入缓存"的另一场
取舍（A2-6 已定 SOP 不入），触发线见 §5.1 新增行。**动规则或动指纹前先读 §5.1**。

**跨指纹 incident 关联（2026-10-17 同轮落成，[ADR-0019](docs/adr/0019-incident-boundary-owned-by-upstream.md)）**：
**关联，不是归并**——N 条告警仍各自成答，被串起来的只是"读的时候能不能一次读出来"。事故的边界**由上游定义**
（Alertmanager 按 `group_by` 划出的一个分组的一次生命周期），本系统不重新发明这层判断。桥把它派生成
`am-` + `SHA256(groupKey + "|" + 组内最早 startsAt)` 取前 128 位，随 `X-Trace-Id` 头发给网关
（**产品面零改动**：该头是过滤器层的，覆盖四个面、不碰任何 DTO），落在审计行 `trace_id`；台账另增
`incident_id` / `incident_basis` 两列。配套两行配置：`group_by: ["job"]`（去掉 `alertname`——带着它时分组与
单条告警**同粒度**，等于没关联）与 `group_interval: 1m`（缺省 5m 对"多久拿到答案"太贵）。

读一次事故（三条命令）：

```bash
grep '"trace_id":"am-3a91aa12483574aea5c7ed029b6d7e66"' logs/audit.jsonl   # 这次事故的每一行
grep -c 'am-3a91aa12483574aea5c7ed029b6d7e66' logs/alert-bridge.jsonl      # 桥处理了几条（含 skip 行）
curl -s localhost:9093/api/v2/alerts                                       # 上游此刻认的活告警
```

**代价（实测读数见 qa §7）**：告警要等一个 `group_interval`（实测投递延迟 57–59s）；组更新会把**全体成员**
重发一遍（Alertmanager 的载荷不是增量），而桥不判重——重复真的打到网关才看得见 L1 与单飞，实测那次重复
命中 **L1**；`resolved` 与 `firing` 是两种告警身份，故 resolved 通知各跑一次完整管线。**抑制规则（inhibit）
决定"哪些告警根本不投递"**，被抑制的告警不会出现在事故里——那是上游的判断，不是丢数据。

**改规则不用重启容器**：`ops/alerting/rules/*.yml` 是只读挂载，改完
`curl -X POST localhost:9090/-/reload` 即生效（`--web.enable-lifecycle` 已开，见 compose）。
改 `prometheus.yml` 本身同理；改 `alertmanager.yml` 要 `docker compose restart alertmanager`。

**规则作者契约（改规则前必读）**：`annotations.summary` 是给桥的**自然语言正文**，
`labels.error_code` 是给桥的**精确符号**。桥只拼接、不发明——标签缺失就转发不含错误码的文本，
让系统走语义路。**不要**指望桥去猜错误码：它拿 `alertname`/指标名去凑的形状会被
`EsSearchService.ERROR_CODE` 判据当成强标识符，等于伪造一个锚点。`rules/opspilot.yml` 里
第三条（SOP 兜底）**故意不带** `error_code`，那条是"无标识符告警"的对照样本，别顺手补上。

**与 `watchdog_alerts.py` 的分工**：两者都发 `source=alert`，但输入面不同——看门狗看的是
**容器状态**（进程死没死），桥看的是**指标**（服务活着但行为劣化了）。同一次故障里两者可能
各发一条，指纹不同、不会被 SingleFlight 合并；这是预期，不是重复告警。

**已知边界（如实登记，不假装覆盖）**：
- 桥是**单实例**的（小时上限与配额读数是进程内状态，跑两个 = 双倍速率）。多实例要换共享态，不是加一行配置。
- 单组超过 `--group-cap`（默认 20）条时**尾部丢弃并留痕**。这是桥唯一会"少发告警"的路径，`skip=group_cap` 行是它的证据。
- `resolved` 同样转发、同样计配额。它不豁免——"故障消失了"也是一条值得进审计的真实输入。
- 桥自己挂了没人知道（与自举生产者的固有盲区同族）：`liveness.py` 只探网关，不探桥。

## 14. 零依赖存储模式（`opspilot.storage.mode=memory`，ADR-0022）

**它是什么**：一条**显式开关**触发的替代链路——把 ES / Qdrant / Redis **三者全部**换成进程内实现，
语料从 `offline/corpus/chunks.jsonl` 直接读进内存（本机实测 448 块 **约 0.2s**，170–204ms）。用途是"电脑上
什么都没装也能先看一眼它是干什么的"：新同事、评审者、面试官不必先过 Docker + 全栈 compose 这道门
（`scripts/quickstart.sh` 首建 2–5 分钟）。**它不是降级档 Level 3**——降级档的语义是"同样的链路在
压力/故障下的取舍"，而内存实现是**换了一条链路**，记成 L3 会污染 `degrade_transition` 审计与可用性
叙事（[ADR-0022](docs/adr/0022-standalone-storage-mode.md) 否决项）。

**起法（一行）**：

```bash
STORAGE_MODE=memory DASHSCOPE_MODE=mock bash scripts/start_gateway.sh
# → 打开 http://localhost:8081/ ，右上角账号胶囊填 sre-full + 口令，默认视图=排障台
```

`STORAGE_MODE` 经 `application.yml` 映到 `opspilot.storage.mode`；`DASHSCOPE_MODE=mock` 让模型层
用词法后端（无需 API Key）。想要**完整**演示（含拒答导航），把 `DASHSCOPE_MODE` 换成 `live`——
两种都是"零外部中间件"，差别只在**模型轴**。

**不自动回退**：启动探测到中间件缺失时**不静默切换**，只打印一条可执行的提示
（"如需零依赖体验，加 `--opspilot.storage.mode=memory`"），然后照常失败。理由：key 的有无是
**配置期事实**（一次定死），中间件可达性是**运行期事实**（会抖）——把 `auto` 套到会抖的信号上，
会出现"同一台机器、同一份代码、两次启动跑在不同后端"而页面与审计数字长得一模一样，正是本仓反复
在防的那类**假绿**。

**它为什么可信（不是又一份演示实现）**：内存实现**复用同一份** `tenant × auth_level` 过滤判据
（`retrieval/IdentityFilter`，与 `EsSearchService` 共用）与只读命令策略，**不另写一套**——否则它就成了
绕开权限引擎的旁路。检索仍是**两条腿 + RRF 融合**（词法腿与向量腿打分方式不同，融合是真事）。
"两套实现行为一致"由 `IdentityFilterContrastTest` / `InMemoryIndexTest` 等对照测试锁着。

**怎么看"这次跑的是哪条链路"（不靠人回忆启动命令）**：启动摘要打印
`storage=memory backend=in-memory（…不承诺检索质量）`，日志里另有
`[startup-memory] 内存索引装载完成: 448 chunks, 耗时 Nms`；`GET /api/v1/admin/health`（需 platform 门禁）
自报：

```json
{"redis":{"detail":"in-memory kv…"},"qdrant":{"detail":"448 pts (in-memory)"},
 "es":{"detail":"448 docs (in-memory)"},"storage":"memory","status":"UP"}
```

**诚实边界（必须知道，别把内存后端的读数当 live 读数）**：

- **不承诺任何检索质量数字**。向量是**词法哈希**不是神经语义，精排是**词面覆盖度**不是交叉编码器；
  ADR-0022 的口径是 memory 环境**不得进入任何"核心指标"表**，`doc_numbers.py` 登记的产物数字仍以
  live / 随附产物为准。
- **拒答门控的行为会随模型轴变**，与存储轴无关。实测对照：`DASHSCOPE_MODE=mock` 下域外提问
  （"今天天气怎么样"）**不会被拒答**——词法后端给任何文本都打出正分；同一份内存语料换成真 key
  （`DASHSCOPE_MODE=live`）后，域外提问立刻恢复拒答（3 个 `near_miss` 候选）。这是**模型轴**差异
  （mock 后端 IDF 覆盖分的假象，见 §5 债务表"域外/无信息量 query 的第二道门"一行），不是内存实现的缺陷。

**启动期的代价（如实登记）**：内存索引装载被放在 `IngestionRunner.afterPropertiesSet`，即**在 Tomcat
开始服务之前**同步完成——代价是启动多花约 0.2s（448 块，170–204ms），换来的是"服务一旦可访问，检索就是满的"。
这不是洁癖：装载一度挂在 `ApplicationRunner`（**Tomcat 起来之后**），于是有一个窗口检索为空、排障台对
任何 query 都回"无相关参考"——那看起来像**拒答**，实际是索引还没进内存。装载挪到 `afterPropertiesSet`
后这个窗口消失。

**落地实测咬到的三个坑（都已在代码/`application.yml` 注释里留档，改这块前先读）**：

1. **`RedisAutoConfiguration`（Lettuce）会无条件注册 `RedisHealthIndicator`**——即使本项目从不使用
   `RedisConnectionFactory`（唯一消费者是 RedissonClient），memory 模式下它照样 ping Redis，把
   `/actuator/health` 拖成 **DOWN**，而 `demo.sh` / `liveness.py` / compose healthcheck 都以这个端点
   当存活判据，整条零依赖路径会被判"没起来"。**处置**：`spring.autoconfigure.exclude` 里连它一起排除
   （同批排除 `RedissonAutoConfigurationV2`——它会在 memory 模式下照样急连 Redis）。
2. **`ElasticsearchRestClientHealthIndicator` 是"按 classpath"注册的**（不是按 `RestClient` bean），
   所以"memory 模式不建 ES 客户端"挡不住它，它照样 ping `localhost:9200` 拖垮 `/actuator/health`。
   **处置**：`management.health.elasticsearch.enabled: false`——本项目自己的 `esHealthIndicator`（组件键 `es`）
   做的是同一件事且更准（走别名、带 socket 超时、能报 docs 计数）。
3. **装载时机**（见上"启动期的代价"）：`ApplicationRunner` → `InitializingBean.afterPropertiesSet`。

**零依赖模式下怎么自检**：`demo.sh` 第 1 检硬要求三个中间件容器在跑，**它是 live 形态的预检**——memory
模式跑它会红，属预期。memory 形态用这两条零依赖/只读门禁：

```bash
bash scripts/check_panel_contract.sh     # 零依赖：面板↔后端字面量契约，CI 同源
cd offline && python preset_probe.py      # 只读活体：预设 + 覆盖范围与语料逐项相等（27/27）
```

**它不是什么**：不是"离线模式"——`memory` 只换掉存储，`DASHSCOPE_MODE=live` 下模型层仍要联网；
只有 `memory + mock` 组合才是完全离线。它也不改产品面：网关不加端点、不开新的鉴权维度，页面与协议
一字未动。

## 15. 对外面用词门闩（ADR-0023 决策 1–4）

**它管什么**：本仓的术语是为**精确**而生的（`CONTEXT.md` 逐条论证近义词为什么被否），代价是它们会
漏到不该出现的地方——README 首屏、页面标签。ADR-0023 把这件事拆成**双轨**：

- **内部面**（canonical 照旧）：`docs/CONTEXT.md`、`docs/adr/`、**本文档 `OPS.md`**、代码注释、审计
  列名与 SSE 帧键（`actions`/`gate`/`near_miss`…）。改协议键**不跟**对外别名走。
- **对外面**（用朴素词）：`README.md` 全文 + 页面文案（`index.html` / `troubleshoot.html`）。**只有
  这两个面是"外人会读的面"**——所以本文档与 ADR 里的 canonical 词**是合规的**，别顺手去改它们。

**别名表在 `CONTEXT.md`**（每条的 `_对外_: …` 行），它是**单一事实源**：新增机制时忘登记，反向断言
会提醒。当前这批：行动契约→只读命令清单、变异验证→安全回归测试、单飞→并发合并、指纹→去重键、
对照模式→权限隔离演练、存储模式→零依赖试跑。

**门闩怎么跑**（CI 的 `provenance` job 里，与 `provenance.py` / `doc_numbers.py` 同处）：

```bash
python scripts/check_public_copy.py --check   # 退出码 0=一致 / 1=有 canonical 词漏进对外面
```

**词表不手写**——从 `CONTEXT.md` 的 `_对外_` 行**现取**（登记一个术语 = 同时登记它的禁用面）。
解析不到任何别名时**报错而不是放行**：悄悄失效的门闩比没有门闩更危险（同款纪律见 `doc_numbers.py`）。
**每条 canonical term 都要有一行 `_对外_: …`**——没有对外别名就写 `_对外_: 无对外别名。`，缺行即红
（决策 1 的完备性：没有它，就分不清"有意不设别名"与"忘了登记"）。

**跑红了怎么改**：看 `FAIL` 行点名的 `文件:行` 与 canonical 词，把它换成该词在 `CONTEXT.md` 里的
`_对外_` 别名（例：README 里的「行动契约」→「只读命令清单」）。**若你是在写一个新机制**：先在
`CONTEXT.md` 立条目——能用朴素词见外就登记 `_对外_: <别名>`，确实该用同一个词见外就写
`_对外_: 无对外别名`（后者不进禁用面）。

**反向只报不闸（有意如此）**：README 里**未登记**的加粗机制名候选会以 `INFO` 列出、**不改退出码**。
ADR-0023 决策 4 写的是"宁可漏报不误报"——实测严格过滤仍有若干**领域常用词**噪声（"告警风暴/排障台/
运行面板"），做成硬闸就会逼出"为过闸而改写的奇怪句子"，正是该 ADR 后果段点名要避免的失效方式。

**已知边界（如实登记）**：门闩剥掉 `<style>` 但**保留 `<script>`**——面板的用户可见标签由 JS 字符串
字面量渲染（如"在途合并组"），整段剥 `<script>` 会把页面黑话放行。canonical 词全是中文、代码标识符
全是 ASCII，故保留 script 不引入误报；代价是脚本里的中文**注释**若含 canonical 词也会被算作违规。
HTML 注释同理（非可见文本但会被扫到）——**fail-closed 是刻意的**，改写一句注释比放行一条黑话便宜。
