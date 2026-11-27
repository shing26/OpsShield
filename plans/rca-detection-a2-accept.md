# RCA A2：CUSUM 新增检出验收契约（草案 v0）

> 状态：**FROZEN（2026-11-27，Lead「按草案冻结 A2 并开始 A2-1」）**。
> 冻结后如放宽任一 AC 或改 §4 网格，须先改本文件并重新签收。
> 上位：`plans/rca-roadmap.md`（路线 A 的 A2'）
> Task ID：`rca-detection-a2`；基线 `main@6180be0`
> 判定：**走链路**（新检测方法 + 可能的产品结论，按高一档）。

## 1. 只回答一个问题

独立的 CUSUM 检测器，能否在 SS 与 OB 两套 dev 上同时达到：

1. 覆盖 ≥0.80、误报 ≤5%；
2. 相对 v2 冻结检测器**新增**检出（OB ≥24 个 case、SS ≥1 个 case），
   同时丢失受控（两套均 ≤8 个 case）；
3. 分故障不崩（任一故障不得比 v2 低 >10pp；3σ 更高时同时以 3σ 为参照）；
4. 中位延迟 ≤60s、P90 ≤180s。

只看 dev；rep 4–5 与 RE1-TT 均不读、不跑、不评分。

## 2. 起点（为什么 A1 失败后要换机制）

v2 冻结检测器（`detect.py` 默认路径）在 dev 上是：

- SS：68/75 = 0.907，误报 1/75；
- OB：36/75 = 0.480，误报 31/75。

A1 在 v2 报警之上加 C/D 过滤器，合规集 **0/16**。逐 case 对账（OB）：
`none→det = 0`，`false_alarm→det = +12`，`det→miss = −11`，净 **+1**。
因此 A1 不是“阈值没调好”，而是**过滤器不产生新报警事件**，只能重选择。

本契约换的是**报警生成规则**，不是报警后过滤：

- 复用 v2 的每指标 robust-z（滚动基线、材料性下限不变）；
- 用单侧 CUSUM 累积持续的正向偏离，自己产生报警；
- v2 只作为对照与“新增检出”的基线，**不并入、不兜底**。

> 核心预期：CUSUM 能把“单点未过 v2 阈值、但持续偏移”的 case 变成检出；
> 风险是 OB 的注入前持续噪声也可能被累积，导致误报或提前报警。

## 3. 数据与划分

| 用途 | 数据集 | 规模 | 说明 |
| --- | --- | ---: | --- |
| 选参与门槛 | RE1-SS dev + RE1-OB dev | 75 + 75 | rep 1–3 |
| 保留 | rep 4–5 | — | **本次不得使用** |
| 保留 | RE1-TT | 125 | **本次不读、不跑、不评分** |

- dev 每故障 15 个 case（3 reps × 5 services）；SS/OB 各 5 类故障。
- SS 的 dev case 实际只有约 360s 注入前数据；OB 同时存在约 360s 与 2100s
  注入前数据的 case。报告必须写实际前窗，不得假设都有 600s。
- v2 复现护栏：SS 必须复现 0.907 / 1 个误报；OB 必须复现 0.480 / 31 个误报。
  不一致即 A2-1 未完成，A2 不得进入网格。

## 4. 方法（预注册，冻结后不得增删）

### 4.1 独立 CUSUM 检测器

对每个 case、每个指标臂（`resource` / `sli`）：

1. 复用 v2 的 `z_frame(case, arm)`：300s 滚动中位数/MAD，8% 材料性下限，
   得到每个指标的正向 robust-z `z_m(t) ≥ 0`。**不读标签**。
2. 对每个指标列计算单侧 CUSUM：

   `S_m(t) = max(0, S_m(t-1) + z_m(t) - k)`

   `z_m(t)` 为 NaN 时按 0 处理（不累积）；`S_m` 初始为 0。
3. 指标报警：`S_m(t) >= h`；报警后把该指标的 `S_m` 重置为 0。
4. 服务报警：同一时刻至少有 `min_metrics` 个指标报警，记该服务一次报警。
5. 臂报警：该臂内最早的服务报警；两臂合并取最早。
6. 只看 `inject - PRE_S` 到 `inject + POST_S` 的报警；首次报警按 §4.3 分类。

固定常量沿用 v2：`BASELINE_S=300`、`MATERIALITY_FLOOR=0.08`、
`PRE_S=600`、`POST_S=600`、`HORIZON_S=300`。CUSUM 不使用 v2 的
20/8 绝对阈值，也不使用 A1 的 C/D/P 过滤。

### 4.2 预注册网格（16 配置 × 2 套 dev）

- `k ∈ {0.5, 1.0}`（漂移容忍，z 单位）
- `h ∈ {6, 8, 10, 12}`（累积报警阈值，z 单位）
- `min_metrics ∈ {1, 2}`（同一服务同时报警的指标数）

共 16 配置；两套 dev 各跑一遍，共 32 次运行。默认打平配置为
`(k=1.0, h=8, min_metrics=1)`。

### 4.3 逐 case 状态与“新增/丢失”口径

对每个 case，首次报警 `a`：

- `detected`：`inject <= a <= inject + HORIZON_S`
- `false_alarm`：`a < inject`
- `late`：`a > inject + HORIZON_S`（不计检出）
- `none`：窗口内无报警

v2 状态由冻结的 `detect.py --arms both` 在同一 75 个 case 上复现。
相对 v2：

- **新增检出**：v2 为 `none / false_alarm / late`，A2 为 `detected`
- **丢失检出**：v2 为 `detected`，A2 为 `none / false_alarm / late`

报告必须给出完整 4×4 转移表，不能只报净差。

### 4.4 选择规则

合规集 = 同时满足 AC-2..AC-7 的配置；**合规集为空即 A2 FAIL**。

合规集内依次比较：

1. 最大化 `min(SS 覆盖, OB 覆盖)`；
2. 最小化两套平均误报；
3. 最小化两套 P90 延迟；
4. 仍打平取 `(k=1.0, h=8, min_metrics=1)`。

不得在看到 dev 结果后增删配置、改分母、改状态口径或改选择规则。

## 5. Allowed paths

- `offline/rca/detect_cusum.py`（新增，独立 CUSUM 检测器与 `--sweep`）
- `offline/rca/tests/**`
- `offline/rca/reports/A2-report.md` 与新的 A2 运行目录
- `plans/rca-detection-a2-accept.md`（本文件）
- `plans/rca-roadmap.md`（仅回填 A2 结论）
- `docs/adr/0047-*.md`（新增，Proposed；若冻结前编号被占用则顺延并在 §13 记录）
- `docs/repo-map.md`（仅 `docs/adr/` 计数行与 `offline/rca/` 行）
- `README.md`（**仅** ADR 计数两处）

**禁止修改**：`detect.py`、`methods.py`、v1–v3/B0/A1 的已签收报告、运行目录
与断言、`src/**`、`pom.xml`、`offline/requirements-dev.txt`。A2 不得靠改 v2
默认阈值或默认行为实现。

## 6. Acceptance criteria

- **AC-1 无标签**：CUSUM 只读指标与指标族，不读 `root_cause_service` /
  `fault` / case 名；新增标签不变性测试（改标签不改报警）。
- **AC-2 SS dev 覆盖**：合并覆盖率 ≥0.80。
- **AC-3 SS dev 误报**：≤0.05（首次报警 `< inject`）。
- **AC-4 OB dev 覆盖**：合并覆盖率 ≥0.80。
- **AC-5 OB dev 误报**：≤0.05。
- **AC-6 新增检出**：相对 v2，SS 新增 ≥1 个 case 且丢失 ≤8 个 case；
  OB 新增 ≥24 个 case 且丢失 ≤8 个 case。A2 必须自己产生报警，不能用
  v2 报警兜底。
- **AC-7 分故障非回归**：两套 dev 中，任一故障类型覆盖率不得比 v2 冻结检测器
  低 >10pp；若该故障的 3σ 覆盖率高于 v2，则同时不得比 3σ 低 >10pp。
  不设绝对 0.70 下限，绝对覆盖率如实报告（v2 自身在 OB cpu 等故障上低于 0.70）。
- **AC-8 延迟**：两套 dev 的中位报警延迟 ≤60s、P90 ≤180s；超
  `HORIZON_S` 的迟到报警单列，不得计为检出。
- **AC-9 未越界**：rep 4–5 与 TT 均未被读取；v2 阈值与默认行为未改；
  `git diff --name-only main...HEAD` 只落在 §5。
- **AC-10 可复现**：pytest 全绿无 skip；固定 seed 重跑一致；报告含版本、
  完整命令、HEAD SHA 与 `git status --short`。
- **AC-11 独立验收**：独立 Verifier 逐条重跑 AC-1..AC-10。

## 7. Verification commands（Verifier 亲自重跑）

```powershell
$env:PYTHONIOENCODING='utf-8'
offline/rca/.venv/Scripts/python.exe -m pytest offline/rca/tests -q
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --suite re1-ss --split dev --arms both
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --suite re1-ob --split dev --arms both
offline/rca/.venv/Scripts/python.exe offline/rca/detect_cusum.py --suite re1-ss --split dev --arms both --sweep
offline/rca/.venv/Scripts/python.exe offline/rca/detect_cusum.py --suite re1-ob --split dev --arms both --sweep
git rev-parse HEAD
git status --short
```

`--sweep` 必须一次跑完 16 配置并写出 `grid.json`；报告还要给出选中配置的单次
复跑命令。Verifier 必须从逐 case CSV 独立重算 AC-2..AC-8，不得采用实现方
贴出的聚合数字。

## 8. 判定矩阵

| AC-2/3/6/7（SS） | AC-4/5/6/7（OB） | 结论 |
| --- | --- | --- |
| PASS | PASS | **A2 成立**：可进入 A3 一次性 holdout 设计 |
| 任意 | FAIL | **A2 失败**：写负结果，检测不产品化；不得用 TT/rep 4–5 抢救 |
| FAIL | 任意 | 同上 |

覆盖/误报达标但 AC-6 的新增检出不达标，仍判 **A2 FAIL**：这证明只是换了一个
报警器，没有解决“从 0.48 到 0.80”的缺口。分故障、延迟不达标同样按失败记录。

## 9. Forbidden

- 在 rep 4–5 或 TT 上跑任何配置、看任何聚合指标。
- 看到 dev 结果后再增删 CUSUM 参数、改网格、改状态口径或改分母。
- 把 A2 做成“v2 报警过滤器 + 秘密兜底”或事后并集；A2 必须独立产生报警，
  且 v2 只作对照。
- 用标签、注入时间或 case 名作为报警门槛。
- 改 v2 阈值口径或默认行为；把 A2 默认打开。
- 用 per-system commissioning 替代跨系统判据。
- 把 dev 通过说成“检测已产品化”或“端到端可用”。

## 10. 风险（写进报告，不许回避）

1. **CUSUM 可能仍不新增**：如果 v2 的 300s 基线已经把持续偏移吸收掉，
   CUSUM 只是在同一 z 序列上换阈值；负结果必须如实记录。
2. **OB 的注入前报警可能是持续的**：A1 已观察到 OB 的误报不是单服务瞬态；
   CUSUM 可能把这类噪声累积成更早的误报，反而拉低检出。
3. **样本小**：每套 75 个、每故障 15 个，95% CI 约 ±10pp；dev 达标不等于
   跨系统可迁移。
4. **基线耦合**：本契约故意复用 v2 的 300s 基线与 8% 材料性下限，以隔离
   “决策规则”变量；这不能证明更长的自适应基线有效。
5. **不碰 TT**：A2 即使 dev 成立，也必须另立 A3 一次性 holdout；不得提前
   用 TT 调参或宣传。

## 11. 里程碑

| 阶段 | 交付 | 门槛 |
| --- | --- | --- |
| A2-0 | 本契约冻结 | 已完成（2026-11-27，Lead 书面冻结） |
| A2-1 | `detect_cusum.py` + 单测 + v2 复现护栏 | AC-1、v2 复现一致 |
| A2-2 | 16 配置 × 2 套 dev 网格 + `A2-report.md` + ADR-0047 | AC-2..AC-8 |
| A2-3 | 独立 Verifier | AC-9..AC-11；按 §8 判 A2 去留 |

## 12. Lead 裁决记录（2026-11-27，冻结时）

> 本条只确认 §4 网格与 §6 AC，不放宽 §1 的问题与 §8 的失败判定。

1. **冻结范围**：独立 CUSUM 检测器；`k ∈ {0.5, 1.0}`、
   `h ∈ {6, 8, 10, 12}`、`min_metrics ∈ {1, 2}`，共 16 配置 × 2 套 dev。
2. **新增检出是硬门槛**：OB 相对 v2 新增 ≥24 个 case、SS 新增 ≥1 个 case；
   两套丢失均 ≤8 个。覆盖/误报达标但新增为 0，仍判 A2 FAIL。
3. **执行顺序**：先完成 A2-1（实现 + 单测 + v2 复现护栏），再跑 A2-2 的
   16 配置网格；A2-1 未完成不得看 A2 dev 结果。
4. **保留集**：rep 4–5 与 RE1-TT 继续不读、不跑、不评分。

**签收状态：已冻结（2026-11-27，Lead「按草案冻结 A2 并开始 A2-1」）。**

## 13. 起草记录（非契约变更，2026-11-27）

1. **为什么是独立 CUSUM 而不是过滤器/并集**：A1 已证明过滤器不新增报警事件；
   本契约要求 A2 自己产生报警，v2 只作逐 case 对照。
2. **为什么复用 v2 的 z_frame**：把变量隔离在决策规则上，避免同时改基线、
   材料性下限和阈值导致归因不清。
3. **A2 与原路线图 A2 的关系**：原路线图的“A2 分故障非回归”并入本契约 AC-7；
   本契约对应路线图的 A2'（换机制）。
4. **ADR 编号**：草案预留 `ADR-0047`；若冻结前 `main` 已占用，按最新编号顺延，
   并在冻结记录中披露，不改变任何 AC 或判定。
5. **A2-1 执行记录（2026-11-27）**：新增 `detect_cusum.py` 与
   `test_detect_cusum.py`；全量 `pytest offline/rca/tests -q` 为 81 passed。
   v2 复现护栏独立重跑：SS 0.907 / 误报 1，OB 0.480 / 误报 31。
   A2 的 dev 16 配置网格尚未运行（留到 A2-2）。
