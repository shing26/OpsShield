# RCA A1：检测新特征验收契约（草案 v0）

> 状态：**FROZEN（2026-11-27，Lead「按草案冻结 A1」）**。冻结后如需放宽任一 AC，
> 须先改本文件并重新签收。
> 上位：`plans/rca-roadmap.md`（路线 A 的 A1）
> Task ID：`rca-detection-a1`；基线 `main@3b70825`
> 判定：**走链路**（新方法 + 可能的产品结论，按高一档）。

## 1. 只回答一个问题

在 v2 冻结检测器之上加**新特征**（跨服务一致性、持续时间、调用链上下文），
能否让 SS 与 OB **两套 dev 同时**达到「覆盖 ≥80% 且误报 ≤5%」？

只看 dev；不碰 rep 4–5，不碰 RE1-TT。

## 2. 起点（为什么这道门槛不低）

v2 冻结参数（resource=20 / SLI=8 / sustain=3 / 材料性 8%）的实测：

| 集合 | 覆盖率 | 误报 |
| --- | ---: | ---: |
| RE1-SS dev | 0.907 | 1/75（1.3%） |
| RE1-OB dev | **0.480** | **31/75（41.3%）** |
| RE1-SS test | 0.840 | 3/50（6.0%） |
| RE1-TT（v3 诊断） | 0.304 | 67.2% |

参数明显过拟合 SS。A1 要回答的就是：新特征能不能把这个差距补上，
还是说明“单一全局阈值”这条路本身不成立。

## 3. 数据与划分

| 用途 | 数据集 | 规模 | 说明 |
| --- | --- | --- | --- |
| 选参与门槛 | RE1-SS dev + RE1-OB dev | 75 + 75 | rep 1–3 |
| 保留 | rep 4–5 | — | **本次不得使用**；后续一次性 test 另行冻结 |
| 保留 | RE1-TT | 125 | **本次不读、不跑、不评分** |

## 4. 方法（预注册，冻结后不得增删判据）

三个新判据只读指标与拓扑，不读任何标签：

1. **C 跨服务一致性**：同一 10s 桶内，同一指标族中 `z ≥ z0` 的不同服务数
   `≥ k`。动机：注入故障沿依赖传播，随机尖峰多为单服务。
2. **D 持续时间**：触发服务在最近 `W` 秒内超阈桶占比 `≥ p`。
   动机：跨系统误报大量来自瞬时尖峰（v3 TT 已证）。
3. **P 调用链上下文**：异常服务与其拓扑邻居（callers/callees）得分的相关
   `≥ ρ`。SS/OB 有调用图，**TT 没有**——因此 P 只记录、**不作为门槛**，
   否则 A2 无法迁移。

组合规则（固定）：保留 v2 报警当且仅当 `C or D` 成立；P 只进报告。

预注册网格（16 配置 × 2 套 dev）：

- `z0 ∈ {3.5, 4.0}`，`k ∈ {3, 4}`
- `W ∈ {60, 120}` 秒，`p ∈ {0.6, 0.8}`
- v2 阈值、sustain、材料性下限**不改**

选择规则：

1. 合规集 = 两套 dev 都满足覆盖 ≥0.80 且误报 ≤0.05；
2. 合规集内最大化 `min(SS 覆盖, OB 覆盖)`；
3. 再比两套平均误报（低者优），再比 P90 延迟，仍打平取默认
   `(z0=4.0, k=3, W=120, p=0.8)`。

**合规集为空即 A1 FAIL**，不进入任何 test/TT。

## 5. Allowed paths

- `offline/rca/detect.py`（新增过滤开关，默认关闭以保 v2 复现）
- `offline/rca/detect_features.py`（新增，C/D/P 计算）
- `offline/rca/tests/**`
- `offline/rca/reports/A1-report.md` 与新的运行目录
- `plans/rca-detection-a1-accept.md`（本文件）
- `plans/rca-roadmap.md`（仅回填 A1 结论）
- `docs/adr/0045-*.md`（新增，Proposed）
- `docs/repo-map.md`（仅 `offline/rca/` 行）
- `README.md`（**仅** ADR 计数两处，若新增 ADR 导致门闩点名）

**禁止修改**：v2 冻结阈值与其默认行为、`methods.py`、`M2/V2/V3/R4` 报告、
v1–v3 与 B0 的已提交运行目录与断言、`src/**`、`pom.xml`、
`offline/requirements-dev.txt`。

## 6. Acceptance criteria

- **AC-1 无标签**：C/D/P 与过滤路径不读 `root_cause_service` / `fault` /
  case 名；新增标签不变性测试（改标签不改报警）。
- **AC-2 SS dev 覆盖**：合并覆盖率 ≥0.80。
- **AC-3 SS dev 误报**：≤0.05（故障前窗口内的报警，按 v2 口径 `latency < 0`）。
- **AC-4 OB dev 覆盖**：合并覆盖率 ≥0.80。
- **AC-5 OB dev 误报**：≤0.05。
- **AC-6 分故障非回归**：两套 dev 中，任一故障类型覆盖率 ≥0.70，且不得比
  v2 冻结检测器低 >10pp。
- **AC-7 延迟**：两套 dev 的中位报警延迟 ≤60s、P90 ≤180s；
  超 `HORIZON_S` 的迟到报警单列，不得计为检出。
- **AC-8 未越界**：rep 4–5 与 TT 均未被读取；v2 阈值与默认行为未改；
  `git diff --name-only main...HEAD` 只落在 §5。
- **AC-9 可复现**：pytest 全绿无 skip；固定 seed 重跑一致；报告含版本、
  完整命令、HEAD SHA 与 `git status --short`。
- **AC-10 独立验收**：独立 Verifier 逐条重跑 AC-1..AC-9。

## 7. Verification commands（Verifier 亲自重跑）

```powershell
offline/rca/.venv/Scripts/python.exe -m pytest offline/rca/tests -q
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --suite re1-ss --split dev --arms both --filter both
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --suite re1-ob --split dev --arms both --filter both
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --suite re1-ss --split dev --arms both
git rev-parse HEAD
git status --short
```

（第三行是不带过滤的 v2 复现护栏，必须与 v2 dev 数字一致；
网格中其余配置的调用方式以实际实现为准写进报告。）

## 8. 判定矩阵

| AC-2/3（SS） | AC-4/5（OB） | 结论 |
| --- | --- | --- |
| PASS | PASS | **A1 成立**：可进入 A2（跨系统一次性 holdout 设计） |
| 任意 | FAIL | **A1 失败**：写负结果，检测不产品化；不得用 TT 或 rep 4–5 抢救 |
| FAIL | 任意 | 同上；分故障/延迟不达标同样按失败记录 |

## 9. Forbidden

- 在 rep 4–5 或 TT 上跑任何配置、看任何聚合指标。
- 看到 dev 结果后再增删特征、改网格、改选择规则或改分母。
- 用 per-system commissioning（v3 附录 A 已证失败）替代跨系统判据。
- 改 v2 阈值口径或默认行为；把“默认关闭的过滤器”偷偷打开。
- 把 dev 通过说成“检测已产品化”或“端到端可用”。

## 10. 风险（写进报告，不许回避）

1. **起点差在多系统，不在阈值**：OB dev 41.3% 误报说明正常-故障分布重叠；
   新特征若只压误报不保覆盖，A1 会以“合规集为空”收场——这是可接受结论。
2. **SS 只有 12 分钟**：`W=120s` 已占故障段的 1/3，D 判据在 SS 上会偏严。
3. **拓扑不可迁移**：P 只对 SS/OB 可用；A2 若上 TT，P 必须缺席，
   因此 A1 不能把 P 当门槛。
4. **样本**：每套 75，分故障每类 25；概率估计的 CI 约 ±10pp，
   覆盖 0.80 的门槛本身就有抖动，报告须给 CI。
5. **时间盒**：两周内出 A1 结论；超时按“A1 未通过”记录，不无限打磨。

## 11. 里程碑

| 阶段 | 内容 | 出口 |
| --- | --- | --- |
| A1-1 | C/D/P 特征 + 过滤开关（默认关）+ 单测 | AC-1、v2 复现护栏 |
| A1-2 | 16 配置 × 2 套 dev 网格 + `A1-report.md` + ADR-0045 | AC-2..AC-7 |
| A1-3 | 独立 Verifier | AC-8..AC-10；按 §8 判 A1 去留 |

## 12. Lead 裁决记录（2026-11-27，冻结时）

> 本条只解释既有条款，不放宽、不改写 AC-1..AC-10。

1. **“超阈”的统一口径**：C 与 D 中的“超阈”都指 `z ≥ z0`，**不使用** v2 的
   绝对阈值（resource=20 / SLI=8）。这样网格变量只有一个，C/D 可比。
2. **C 按臂计算**：resource 与 SLI 各自统计异常服务数，不跨臂合并；
   两臂的过滤各自生效，合并报警仍取“最早通过过滤的报警”。
3. **D 的桶定义**：某服务在某时刻“异常” = 该臂内任一指标 `z ≥ z0`；
   占比按**时间加权**滚动窗口 `W` 秒计算，不按行数（采样间隔可能不均）。
4. **过滤语义**：按时间顺序扫描候选报警，取第一个“通过 C 或 D”的事件；
   被滤掉的事件不产生报警，允许报警时间后移；超过 `HORIZON_S` 仍记失败，
   不得回填更早的报警。
5. **P 的窗口**：固定 120 秒，只记录相关系数，不参与任何判定；
   无拓扑邻居时记 `null`。
6. **默认关闭是硬要求**：`--filter off` 必须逐 case 复现 v2 冻结结果
   （SS dev 0.907 / 1.3%，OB dev 0.480 / 41.3%）；不一致即 A1-1 未完成。
7. **判定来源**：§8 只由 AC-2..AC-5 决定；AC-1/6/7/8/9/10 是完整性门槛，
   任一不过都不得判“A1 成立”。

## 13. 执行记录（非契约变更，2026-11-27）

> 以下均为事实记录或实现偏差披露，**不改 AC-1..AC-10，也不改判定矩阵**。

1. **Allowed paths 追加（与 §5 冲突，需 Lead 认可）**：新增 ADR-0045 后
   `doc_numbers.py --check` 要求 `docs/repo-map.md` 的 `docs/adr/` 行同步计数与
   条目（该行被 `offline/doc_numbers.json` 登记为 `adr_count` 引用点）。
   因此实际修改了这一行，超出 §5 的“仅 `offline/rca/` 行”。
   三条选择：改门闩登记（削弱既有门闩）、让门闩红、或认可这次一行变更。
   当前取第三种，并在 `A1-report.md` §5 的 AC-8 行如实标注。
2. **样本数事实更正**：§10.4 写“每故障 25”有误；dev 是 3 reps × 5 services =
   **每故障 15**。报告与 ADR 已按 15 陈述，阈值与判定不变。
3. **P 的实现偏差**：契约写 callers/callees，`topology.py` 只暴露 callees，
   实现只用 callees。P 不参与任何判定（§4 已预注册为“只记录”），
   对覆盖率/误报无影响；此处披露以免被误读为完整实现。
4. **迟到报警**：AC-7 要求“超 HORIZON 单列”。全网格 32 条（OB 4 个 case）
   已写入 `reports/20261126T213426Z_a1-grid/late-alarms.json` 与报告 §3，
   均 `detected=False`，未计入覆盖率。
5. **机制论证更正**：初版报告/ADR 称“过滤后覆盖上限 = v2 的 0.480”，这是错的
   （实测 0.493）。逐 case 对账后改为“过滤器不新增事件，只做重选择：
   FA→检出 +12、检出→miss −11，净 +1”。**FAIL 判定不变**。
