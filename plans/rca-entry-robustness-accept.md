# RCA 入口鲁棒性验收契约（草案 v0）

> 状态：**FROZEN（2026-11-27，Lead 签收「要按草案冻结」）**。冻结后如需放宽
> 任一 AC，须先改本文件并重新签收。
> 上位文档：`plans/rca-roadmap.md`（路线 B 的 B0）
> Task ID：`rca-entry-robustness`；基线 `main@e2c2fdb`
> 判定：**走链路**（新实验参数 + 决定产品方向，按高一档）。

## 1. 只回答一个问题

v3 的定位增益在“检测起点由外部告警给出、比真实注入晚 δ 秒”时还剩多少？

这是路线 B 的成立前提。**不回答**检测怎么做，也不做产品接线。

## 2. 数据与划分

| 用途 | 数据集 | 规模 | 说明 |
| --- | --- | --- | --- |
| 扫描 | RE1-SS dev + RE1-OB dev | 75 + 75 | rep 1–3；与 v3 选参同一集合 |
| 只读护栏 | RE1-TT | 125 | **本次不读、不跑、不评分**；保留为后续一次性 holdout |

不新增数据、不新增系统。δ 是实验条件，不是待选参数。

## 3. 方法

1. 入口偏移：`normal = time < inject_time + δ`，`faulty = time >= inject_time + δ`；
   其余切窗逻辑不变。
2. 方法参数**固定为 v3 冻结值**：`dual-arm-rrf`，τ_res=3、τ_sli=8、
   callee_discount=0.5、first_mover_boost=0.3、k=60、nan_aware=True。
3. δ ∈ {0, 30, 60, 120, 300} 秒，两套 dev × 五个 δ = 10 格，每格 n=75。
4. 缺证据规则：若偏移后 `faulty` 窗口 < 60 秒（故障已结束或几乎无观测），
   该 case 记为 **miss** 并计入分母，不得跳过、不得补窗。
5. 同格同时报告 `3sigma` 作为对照，用于确认“增益是否只是基线一起塌”。

**禁止**：用本实验反推 δ、阈值、融合参数或 seed；δ 是自变量，不是被优化量。

## 4. Allowed paths

- `offline/rca/dataset.py`、`offline/rca/run.py`（增加入口偏移入口，默认 0）
- `offline/rca/tests/**`（新增偏移与缺证据规则的单测）
- `offline/rca/reports/R4-report.md` 与新的运行目录
- `plans/rca-entry-robustness-accept.md`（本文件）
- `docs/adr/0044-*.md`（新增，Proposed）
- `docs/repo-map.md`（仅 `offline/rca/` 行）
- `plans/rca-roadmap.md`（仅回填 B0 结论）

**禁止修改**：`offline/rca/methods.py` 的方法默认参数、`detect.py` 的阈值、
`M2-report.md` / `V2-report.md` / `V3-report.md`、v1–v3 已提交运行目录与断言、
`src/**`、`pom.xml`、`offline/requirements-dev.txt`。

## 5. Acceptance criteria

- **AC-1 δ=0 复现**：两套 dev 上 δ=0 的逐 case 结果与 v3 dev 运行目录一致，
  聚合值 SS 0.960 / OB 0.893 完全复现（Top-1、Top-3、MRR）。
- **AC-2 扫描完整**：10 格全部产出，每格 n=75；miss 按 §3.4 计数并单列，
  无 skip、无补窗、无静默丢 case。
- **AC-3 主门槛（B0）**：δ=120s 时，`dual-arm-rrf` 的 Top-1 相对该套 δ=0
  下降 ≤5pp（SS 与 OB 各自满足）；且五种故障中任一类下降 ≤10pp。
- **AC-4 曲线完整**：报告 δ=0/30/60/120/300 全部 Top-1、miss 数与
  `3sigma` 对照；δ=300s 只陈述事实，不设门槛。
- **AC-5 无越界**：TT 目录未被读取（用命令记录说明）；δ 扫描结果未用于
  改动任何方法参数；无新增标签进入特征路径。
- **AC-6 可复现**：`pytest offline/rca/tests -q` 全绿无 skip；固定 seed
  重跑指标一致；报告含 Python/依赖版本、完整命令、HEAD SHA 与
  `git status --short`。
- **AC-7 独立验收**：独立 Verifier 逐条重跑 AC-1..AC-6，证据绑定 HEAD SHA。

## 6. Verification commands（Verifier 亲自重跑）

```powershell
offline/rca/.venv/Scripts/python.exe -m pytest offline/rca/tests -q
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-ss --split dev --methods all --seed 7 --entry-offset 0
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-ob --split dev --methods all --seed 7 --entry-offset 120
offline/rca/.venv/Scripts/python.exe offline/rca/report.py --dir <run_dir> --check
git rev-parse HEAD
git status --short
```

（其余 δ 同法；实现应支持一次跑完五个 δ 并输出汇总，命令以实际实现为准写进报告。）

## 7. 判定矩阵

| AC-1 | AC-3 | 结论 |
| --- | --- | --- |
| PASS | PASS | **B 路可继续**：进入 B1 接线（只读告警→Top-3，p95<30s） |
| PASS | FAIL | **B 路作废**：迟滞吃掉增益，转 A（v4 检测） |
| FAIL | 任意 | 先修复现，不得下结论 |

AC-3 的失败必须写清：哪套 dev、哪类故障、δ=120s 掉了多少 pp。

## 8. Forbidden

- 在 TT 上跑任何 δ，或读取 TT 的聚合指标。
- 为了让 AC-3 通过而改 δ 网格、方法参数、缺证据规则或分母。
- 把“δ=300s 仍可用”当作产品结论；它只是记录。
- 修改 v1–v3 已签收的报告、运行目录、断言。
- 声称检测问题已解决，或声称端到端可用。

## 9. 里程碑

| 阶段 | 内容 | 出口 |
| --- | --- | --- |
| R1 | 入口偏移 + 缺证据规则 + 单测 | AC-1/AC-2 可测 |
| R2 | 10 格扫描 + `R4-report.md` + ADR-0044 | AC-3/4/5/6 |
| R3 | 独立 Verifier | AC-7；按 §7 判 B 路去留 |

**时间盒**：一周内出 R2；不出结论就按“B0 未通过”处理，不无限打磨。

## 10. Lead 裁决记录（2026-11-27，冻结时）

> 本条只解释既有条款，不放宽、不改写 AC-1..AC-7。

1. **判定矩阵的适用范围**：§7 只决定“B 路去留”。AC-1/2/4/5/6/7 是完整性门槛，
   任一 FAIL 都不得判“B 路可继续”；此时先修证据或按 FAIL 记录，不许只挑
   AC-3 报喜。
2. **miss 的判据**：以偏移后 `faulty` 窗口的**时间跨度** < 60 秒为准，不用行数；
   采样间隔不一致时按时间跨度计算。
3. **δ 的作用面**：δ 对同格内**所有方法**（含 `3sigma`）同一口径生效，
   不允许只给 `dual-arm-rrf` 偏移、基线保持原窗口。
4. **AC-1 的“一致”**：按 `(method, case)` 比对 `rank` 与 `predicted` 全等；
   聚合值按 1e-9 容差比对。新增列不影响该判定。
5. **CSV 兼容**：允许新增 `miss` / `entry_offset` 列，既有列名与语义不得改。
6. **时间盒**：以 R2 出结论为准；超时按“B0 未通过”处理并写明未完成原因，
   不得以“还没跑完”当作继续投入的理由。

## 11. 执行记录（非契约变更）

- 2026-11-27：新增 ADR-0044 使 `docs/adr/*.md` 真值变为 44，`doc_numbers.py
  --check` 因此点名 `README.md:421` 与 `README.md:468`（原文写 43）。为让门闩
  复绿，Allowed paths 追加一项：`README.md`（**仅**这两处 ADR 计数）。AC-1..AC-7
  与判定矩阵不变。
