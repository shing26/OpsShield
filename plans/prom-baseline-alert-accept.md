# 验收契约：遥测消费与统计基线预警（谱系表 ①② 边界修订）

> 按 `docs/验收链路规范.md` 走完整链路。判定：**必须走**——本契约引入新的告警判定逻辑
> （业务逻辑）、新的对外数据读取面（内部 API 消费）、并修改产品边界声明（谱系表 ①② 与
> 债务闹钟表），属"业务逻辑 / 内部 API"档。
>
> 生成与裁决分离：本文件只冻结契约，**不含实现**。实现由独立 worktree 的 Implementer 完成，
> 裁决由全新会话的 `verifier` subagent 完成。合并前须人审签收。

## Task ID

`prom-baseline-alert-accept`

## 证据绑定（契约冻结时）

- 基线 HEAD SHA：`8fb09ac5c64b7fbe622a4759a9890353ae51c436`（main）
- 基线 `git status --short`：仅 4 项未跟踪（`.claude/worktrees/`、`.dsh/`、
  `求职投递准备计划.md`、`简历项目经历草案.md`），与本任务无关；实现分支须无其它改动。
- 基线 Java 测试：**438 passed, 0 failed, 0 errors, 0 skipped**（`mvn -o test`，随 PR #79 合入）
- 基线 Python 测试声明：**481** 个 `def test_`（`grep -rh "^def test_" offline/tests/*.py`）
- 基线红队：`scripts/redteam.sh` **32/32 通过、0 SKIP、0 泄漏**
- 基线 ADR：**29** 篇（`docs/adr/*.md`）

### 事实前提（契约据此写成，Implementer 不得凭猜测改动）

1. **方向已由 ADR-0029 预留，不是新开方向**。ADR-0029「后果」段原文：
   「未来故障预测（Prometheus 拉取面）以插件形态进入，不动底座」，且「谱系表联动：故障预测若立项，
   需另行 ADR 修订 `docs/positioning-aiops.md` 谱系表 ①②行（"永久非目标"→"不自建采集，
   消费既有时序"），本 ADR 不预支该修订」。本契约即该修订的落地。
2. **插件形态是硬约束**。ADR-0029 决策 1 的分层判据：底座包（`storm`/`resilience`/`auth`/
   `cache`/`store`/`metrics`/`action` 机制层）不含领域词汇；本任务**不改任何 Java 代码**，
   实现落在 `offline/`，与既有 `alert_producer.py`（ADR-0011 自举告警源）同构。
3. **既有投递接缝可直接复用**。`offline/alert_delivery.py`（ADR-0024）已提供
   `AlertSession` / `AlertDelivery` / `DeliveryPolicy` / `Gate`（DRY_RUN、HOUR_BUDGET、
   SNAPSHOT_QUOTA、COOLDOWN）/ `QuotaSnapshot` / `TransportFailureHandling`；
   `offline/localapi.py:49` 的 `assert_local` 是出口白名单（仅 http + 解析后仍是环回）。
   本任务复用，**不改这两个文件**。
4. **Prometheus 已在栈内**。`ops/alerting/prometheus.yml` 抓宿主 8081 的 `/actuator/prometheus`
   （ADR-0016），retention 默认 30d；既有 `offline/slo_report.py:82` 的 `PromClient` 已有
   `/api/v1/query` 与可注入 `opener`（测试不触网）的先例。本任务**不新增 scrape 配置**。
5. **告警编排链已存在**。`POST /api/v1/copilot/chat/stream` 接受 `source=alert`（ADR-0004/0011），
   `alert_producer.py:232` 是现成调用范例；审计业务行记录 `source` 字段，`source` 是客户端自述
   元数据、**不参与鉴权**（ADR-0011 安全边界）。
6. **术语表纪律**：`docs/positioning-aiops.md:13` 曾以「CONTEXT.md 术语表无此词条（有词条必先入
   术语表，反向可验）」作为 ② 未覆盖的证据。实现后该证据失效，故本任务须在
   `docs/CONTEXT.md` 增补词条，并保持 `scripts/check_public_copy.py --check` 绿。
7. **门闩会因新增 ADR 与测试自动要求改数**：`adr_count` 29→30（README「完整 **29 篇**」、
   `docs/repo-map.md`「29 项架构决策」）；`python_test_declarations` 481→实现后的实际声明数
   （README 徽章 `Probes-481_Passed` 与「Python 481 条 test 函数」两处）。这是**被门闩要求**的
   改字，不是顺手改文案。

## Allowed paths（本任务唯一允许触碰面）

- `docs/adr/0030-telemetry-consumption-and-baseline-alerting.md`（新增）
- `offline/predictive_alerts.py`（新增）
- `offline/tests/test_predictive_alerts.py`（新增）
- `docs/ops/predictive-alerts.md`（新增，运维/使用说明）
- `docs/positioning-aiops.md`（**仅**谱系表 ①② 两行、`### A. 永久非目标` 的 ①② 两行、
  第 18 行一句话口径、第 104 行重开条件段）
- `docs/ops/roadmap.md`（**仅** `## 明确不做` 第 1 条 + 一行修订注记）
- `OPS.md`（**仅** §5 债务闹钟表里含「AIOps 谱系 ①遥测采集/②异常检测=永久非目标」的那一句注）
- `docs/adr/0013-trust-base-for-agents-not-an-agent.md`（**仅**追加修订注记，不改决策正文）
- `docs/qa/2026-10-22-ops-readiness-review.md`（**仅**追加一行修订注记，不改原文断言）
- `docs/CONTEXT.md`（**仅**新增本任务词条）
- `README.md`（**仅** ADR 计数与 Python test 计数两处数字，见事实前提 7）
- `docs/repo-map.md`（**仅** ADR 计数与新增模块的目录行）
- `plans/prom-baseline-alert-accept.md`（本契约；Verifier 可追加证据段，Lead 可追加修订注记）

## Acceptance criteria（逐条可判，判据即证据）

### AC-1 ADR-0030 落库，边界修订有据

新增 `docs/adr/0030-telemetry-consumption-and-baseline-alerting.md`，必须包含：

1. **决策五条**：(a) 不自建采集——不新增 scrape 配置、不建指标库、不做拓扑，只经 Prometheus
   HTTP API 读既有时序；(b) 可解释统计基线——稳健中位数/MAD 偏离度，给出基线值、偏离倍数、
   样本数，**不用黑盒模型**；(c) 预警走既有编排链——命中后以 `source=alert` POST 到既有
   `/api/v1/copilot/chat/stream`，不新增后端端点、不新增指标、不改权限模型；
   (d) 插件形态——实现在 `offline/`，底座零改动（ADR-0029 决策 1）；
   (e) 谱系表 ①② 修订口径「不自建采集，消费既有时序」。
2. **否决项**至少覆盖：做采集平台/指标库、上黑盒 ML 异常检测、新增后端告警端点、
   把预警做成自动处置（撞 ADR-0013/谱系 ⑤）。
3. **明示未做的边界**：不做趋势外推/预测模型、不做根因分析、不做自动处置；并写明
   重开条件指向 `OPS.md` §5 对应行（按行标题引用，不写裸行号）。
4. **谱系联动**：写明本 ADR 修订了 `docs/positioning-aiops.md` 谱系表 ①② 行，并引用 ADR-0029
   的预支条款。

**判据**：文件存在；四个要点逐条可在文中定位；`python offline/doc_numbers.py --check` 的
`adr_count` 派生值为 30 且两处引用相符。

### AC-2 拉取面：只读、只本机、可注入、不触网测试

`offline/predictive_alerts.py` 提供 Prometheus 时序读取，必须满足：

1. 仅用 **stdlib**（`urllib`/`json`），**零第三方依赖**（与 `slo_report.py` 同口径）。
2. 走 `/api/v1/query_range`（需要一段历史求基线），不是 instant query。
3. 出口经 `localapi.assert_local`（http + 环回 + 解析后 IP 仍环回）；非本机地址被拒。
4. `opener` 可注入；**测试不得触网**（用 fake opener 返回固定 payload）。
5. 错误分类为"拿不到证据"而非 0：不可达 / `status != success` / 结果形状不对 → 抛明确异常，
   **不得**把无数据当正常值。

**判据**：新增单测覆盖 ①非环回被拒 ②`status=error` 抛异常 ③空结果不等于 0 ④成功解析。
负面判据：`grep -n "import requests\|import numpy\|import scipy\|import pandas" offline/predictive_alerts.py`
零命中。

### AC-3 统计基线：可解释、双阈值、退化情形显式

基线判定必须是可复算的确定性算术：

1. 基线 = 历史窗口内样本的**中位数** `med` 与**绝对中位差** `MAD = median(|x - med|)`。
2. 偏离度用标准 modified z-score：`z = 0.6745 * (x - med) / MAD`。
3. **双阈值**：告警需同时满足 `|z| >= z_threshold` **且** `|x - med| >= min_abs_delta`。
4. **MAD = 0 的退化情形必须显式处置**：序列恒定或近恒定时 `MAD=0`，此时 z 无定义——
   实现必须走"仅绝对差 + 配置的 `min_abs_delta`"分支，并在告警/台账记录
   `baseline="degenerate_mad_zero"`；**不得**除零、不得静默告警、不得静默放过。
5. **样本不足**：窗口内有效样本数 `< min_samples` → 不判定，记 `skip_reason="insufficient_samples"`。
6. 方向可配：`above` / `below` / `both`；命中方向与配置不符不算告警。
7. 告警文案必须**自带可核验数字**：指标名、当前值、基线中位数、MAD、z 或绝对差、样本数、
   窗口长度。读者可据此手算复核，不需要相信黑盒。

**判据**：单测逐条覆盖——正常序列不报、显著上偏报、显著下偏按方向配置、MAD=0 分支、
样本不足分支、双阈值边界（z 达标但绝对差不足 → 不报）。至少一条用例把
`modified z` 的期望值写成手算常数（不是调用被测函数自证）。
默认规则集与全部默认参数见「默认规则集与判定参数（冻结）」；实现不得增删默认规则。

### AC-4 失败关闭：无数据/无基线一律不告警且留痕

1. Prometheus 不可达 → 本轮不产告警，台账记 `skip_reason="prometheus_unreachable"`，进程退出码 2
   （与 `slo_report.py` 同口径），**不**伪造正常读数。
2. 某条规则查不到序列 → 该规则跳过并留痕，不影响其它规则。
3. 任何跳过都必须进台账（`logs/predictive-alerts.jsonl`），事后能回答"为什么没报"。

**判据**：单测覆盖两类跳过 + 台账字段存在；`--dry-run` 时台账写 `emitted=false` 且不触网发送。

### AC-5 预警走既有编排链，复用全部投递闸门

1. 发送体为 `{"query": <自解释文案>, "source": "alert", "service": <规则服务名>, "env": "local"}`，
   POST 到既有 `/api/v1/copilot/chat/stream`；**不新增任何后端端点**。
2. 复用 `AlertDelivery` 与 `PRODUCER_GATES` 同型的闸门集：**冷却（按 规则:指标）**、
   **每小时上限**、**配额保留线**、**dry-run**；每个 skip 写台账。
3. 单实例锁（复用 `alert_producer.py` 的锁文件形态），防多进程成倍告警。
4. 登录失败按 `LoginFailureMode` 归因（凭据错 → 需人介入，不无限重试）。
5. 告警主体默认复用既有 `sre-watcher`（`--user` 可覆盖）；文档写明换独立主体的做法，
   但**不在本任务内创建账号**（属运维动作）。

**判据**：单测覆盖冷却命中、小时上限、dry-run 三条闸门；源码中发送路径只有
`/api/v1/copilot/chat/stream` 一个 URL；`git diff -- src/main/java src/main/resources` 为空。

### AC-6 文档矛盾消解，全仓无残留断言

以下六处必须一致（口径：**不自建采集，消费既有时序；统计基线预警属已覆盖的消费侧能力**）：

1. `docs/positioning-aiops.md` 谱系表 ① 行：状态从「永久非目标」改为
   「**已覆盖（消费侧）**，采集仍非目标」；② 行改为「**已覆盖（统计基线预警）**，
   ML/根因仍非目标」；两行的事实与证据指针同步改写（证据指向新 ADR、模块与单测）。
2. 同文件 `### A. 永久非目标` 表中 ①② 两行移出（或改写为指向新状态的注），
   并在 `### D. 已兑现` 增一行。
3. 同文件第 18 行一句话口径：从「③④ 两个子域」改为含 ①②（消费侧）的表述。
4. 同文件第 104 行重开条件段：删去「拓扑/指标类遥测在谱系表里是永久非目标」的旧断言，
   改为指向新的 ①② 状态与 `OPS.md` §5 的处置闭环行。
5. `docs/ops/roadmap.md` `## 明确不做` 第 1 条：加修订注记说明改由 ADR-0030 承接
   （统计基线预警，不做黑盒 ML/根因），**不删除原条目**。
6. `OPS.md` §5 那句「AIOps 谱系 ①遥测采集/②异常检测=永久非目标」改为指向 ADR-0030 的新口径；
   `docs/adr/0013-...md` 与 `docs/qa/2026-10-22-ops-readiness-review.md` 各追加一行修订注记
   （历史文档不改写原文断言，只加注消歧义——与 ADR-0011 重编号注记同一纪律）。

**判据**：`grep -rn "永久非目标" docs/ OPS.md` 的剩余命中**逐条可解释**——仅允许出现在
「历史记录 + 修订注记」语境（`docs/adr/0013`、`docs/qa/2026-10-22`、`docs/ops/roadmap.md`
的注记），且每处近旁有指向 ADR-0030 的修订注。`docs/positioning-aiops.md` 中不得再有
把 ①② 断言为「永久非目标」的当前时态表述。

### AC-7 门闩与回归全绿（新增测试不破既有断言）

1. `python -m pytest offline/tests/ -q` 全绿；新增测试文件被收集。
2. `python offline/doc_numbers.py --check`：`adr_count=30`、`python_test_declarations=实际值`，
   两处引用均相符；`doc_gate_entries`/`doc_gate_citations` 不变（本任务不新增 doc_numbers 条目）。
3. `python offline/provenance.py --check` 通过。
4. `python scripts/check_public_copy.py --check` 通过（新增 CONTEXT 词条必须带 `_对外_:` 行，
   且 README/页面文案不得出现其 canonical 名）。
5. `mvn -o test` 仍为 **438 passed / 0 failed / 0 skipped**（本任务不改 Java）。
6. `bash scripts/check_panel_contract.sh` 十层一致。
7. `bash scripts/redteam.sh` 仍 **32/32、0 SKIP、0 泄漏**。
8. `git diff origin/main...HEAD -- src/main/java src/main/resources` 为空。

**判据**：上述八条命令全部退出码 0，输出摘要记入证据包。

### AC-8 文档与可运行性

1. `docs/ops/predictive-alerts.md` 写清：它读什么（Prometheus 既有时序）、不读什么、判定式、
   闸门、如何跑（`--once`/`--dry-run`/常驻）、台账位置、已知边界（Prometheus 不可达、
   MAD=0、样本不足）、与 `alert_producer.py` 的分工。
2. 脚本有 `--help`，退出码约定成文（0 正常 / 2 Prometheus 不可达 / 3 配置错误）。
3. README 与 repo-map 的 ADR 计数同步为 30；repo-map 的目录行登记新模块与 ADR。

**判据**：文档存在且章节齐全；`python offline/predictive_alerts.py --help` 退出码 0 且有
`--once/--dry-run/--prometheus/--user` 等参数。

## 默认规则集与判定参数（冻结）

**为什么写死**：规则集若留给实现方自由发挥，Verifier 无从判断"检出的到底该不该检出"，
AC-3 也就失去了可判性。下列五条默认规则覆盖四种形态（尾延迟 / 比率 / 容量 / 兜底），
规则可由 `--rules <json>` 覆盖，但**默认集就是这五条**。

| id | PromQL | 方向 | `min_abs_delta` | 为什么值得盯 |
|---|---|---|---|---|
| `ttft-p99` | `histogram_quantile(0.99, sum(rate(aiops_ttft_seconds_bucket[5m])) by (le))` | above | 0.5（秒） | 首字延迟尾部分位漂移——静态阈值（SLO 3s）之下就能先看到爬升 |
| `request-p99-success` | `histogram_quantile(0.99, sum(rate(aiops_request_seconds_bucket{outcome="success"}[5m])) by (le))` | above | 0.5（秒） | 成功请求的端到端尾延迟；按 outcome 分桶避免慢失败污染 |
| `cache-hit-rate` | `aiops_cache_hit_rate` | below | 0.10（比率） | 命中率下滑 = 键设计退化/缓存失效，业务仍"正常"但成本在涨 |
| `inflight` | `aiops_inflight` | above | 2（个） | 在途缓慢爬升是容量拐点的前兆，比"降级已发生"早 |
| `sop-fallbacks-rate` | `sum(rate(aiops_sop_fallbacks_total[5m]))` | above | 0.01（次/秒） | 兜底直出激增 = 检索/上游在静默劣化 |

已核对这些序列真实存在：前两条依赖 `OpsMetrics` 的 Timer 已开
`publishPercentileHistogram()`（`OpsMetrics.java:77-81`），后三条为 gauge/counter
（`aiops_cache_hit_rate`、`aiops_inflight`、`aiops_sop_fallbacks_total`）。
**计数器一律在 PromQL 里先 `rate()`**，判定拿到的已是每秒速率，不在 Python 侧再除窗口。

判定参数（默认值，可经 CLI 覆盖）：

| 参数 | 默认 | 语义 |
|---|---|---|
| `--lookback` | `6h` | 基线窗口长度（不含最近 `recent_points` 个点） |
| `--step` | `60s` | `query_range` 步长 |
| `--min-samples` | `20` | 基线有效样本下限，不足则跳过并留痕 |
| `--z-threshold` | `3.5` | modified z-score 阈值（Iglewicz–Hoaglin 推荐值） |
| `--recent-points` | `3` | 当前值 = 最近 N 个样本的**中位数**（抗单点抖动） |
| `--consecutive` | `2` | 连续 N 轮命中才发（进程内状态；`--once` 时按 1 轮处理） |
| `--cooldown` | `1800` | 同 `规则:指标` 的冷却秒数 |
| `--max-per-hour` | `6` | 每主体每小时最多发出条数 |
| `--dry-run` | 关 | 只判定与留痕，不发送 |

告警文案模板（必须把可复核数字写进正文）：

```
预测预警：{id} 当前 {current:.3f}，基线中位数 {median:.3f}，MAD {mad:.3f}，
偏离 {z:.2f}σ / 绝对差 {delta:.3f}（样本 {n}，窗口 {lookback}，方法 {method}）。
{metric_cn}出现统计异常，请给出可能原因与排查步骤。
```

其中 `method` 取值 `modified_z` 或 `degenerate_mad_zero`；后者表示 MAD=0 已走绝对差分支
（AC-3 第 4 条）。**文案里不得出现错误码**——这条走语义检索路，与既有三条带错误码的
业务规则形成对照（`ops/alerting/rules/opspilot.yml` 文件头写明混粒度是刻意设计）。

## Verification commands（Verifier 亲自重跑，工作目录 `D:/OpsPilot — AIOps`）

```bash
git rev-parse HEAD && git status --short
git diff origin/main...HEAD --stat
git diff origin/main...HEAD -- src/main/java src/main/resources   # 必须为空
python -m pytest offline/tests/ -q
python -m pytest offline/tests/test_predictive_alerts.py -q
python offline/doc_numbers.py --check
python offline/provenance.py --check
python scripts/check_public_copy.py --check
mvn -o test                                                        # 438 / 0 / 0
bash scripts/check_panel_contract.sh                               # 十层一致
bash scripts/redteam.sh                                            # 32/32 零泄漏
python offline/predictive_alerts.py --help                         # 退出码 0
python offline/predictive_alerts.py --once --dry-run --prometheus http://127.0.0.1:9090
grep -rn "永久非目标" docs/ OPS.md                                  # 逐条可解释，见 AC-6
grep -rn "import requests\|import numpy\|import scipy\|import pandas" offline/predictive_alerts.py
```

AC-1 的 ADR 计数以 `doc_numbers --check` 的现算为准；AC-3 的统计量以单测里的手算常数为准。

## Forbidden

1. 新增任何后端端点、指标、权限维度或数据库表/迁移；
2. 自建采集（新增 scrape 配置、指标入库、拓扑采集）；
3. 引入第三方 Python 依赖或任何 ML 库；
4. 黑盒模型判定（模型推理、外部 LLM 调用）；
5. 把"预测/根因/自动处置"写成已实现——本任务只做统计基线偏离预警；
6. 改/删/弱化任何既有断言或门闩；
7. 触碰 Allowed paths 之外的文件（含新增文件）；
8. 把无数据、样本不足或 MAD=0 当作"正常"静默放过（必须留痕）；
9. 历史文档改写原文断言而不加修订注记；
10. Implementer 自宣通过（裁决只能由独立 Verifier 出具）。

## 人审四问（分级留痕，合并签收用）

1. Verification commands 是否全部亲跑且退出码 0？（Verifier 回答，附输出摘要）
2. AC-1…AC-8 逐条判在哪份证据？特别是 AC-3 的 MAD=0 退化分支与 AC-6 的残留断言核对。
3. 有无 Forbidden 事件？（任一发生即验收失败）
4. 遗留边界是否接受为已知项？至少含：预警只在 Prometheus 可达且有足够历史样本时成立；
   基线是"自身历史"而非跨实例基线；不做趋势外推与根因。

## 人审签收

（待 Verifier 证据包出具后由 Lead 填写；未签收不得合并。）
