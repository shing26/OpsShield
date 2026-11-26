# RCA v2 验收契约（草案 v1）

> 状态：**FROZEN（2026-11-26，Lead 签收"按草案冻结 v2"）**。基线 `codex/rca-benchmark@58260c1`；
> 实现分支 `codex/rca-v2`。冻结后如需放宽任一 AC，须先改本文件并重新签收。
> Task ID：`rca-v2`
> 基线：`codex/rca-benchmark@58260c1`（v1 已签收）；本任务从该点开 `codex/rca-v2`。
> 判定：**走链路**（新方法 + 新数据 + 可能接产品面，按高一档）。

## 1. 目标

v1 的边界已经清楚：资源类故障能打（检出后 Top-1 93.3%），但 delay 故障从 100% 掉到 20%，
且资源型检测器只覆盖 60%。v2 只解决这两件事，不扩边界：

1. **双检测臂**：资源臂（cpu/mem/diskio）+ SLI 臂（latency/error），覆盖 delay/loss/disk；
2. **故障类型自适应定位**：资源信号显著时用资源臂，否则用 SLI 臂；两臂都有信号时做排序融合；
3. **换系统验证**：在 **RE1-SS（Sock Shop）** 上做外部验证，RE1-OB 只作回归护栏。

一句话验收口径：**在 RE1-SS 测试集上，v2 的聚合 Top-1 比 3σ 高 ≥5pp，且没有任何故障类型比 3σ 低 >5pp。**

## 2. 数据与划分

| 用途 | 数据集 | 规模 | 说明 |
| --- | --- | --- | --- |
| 回归护栏 | RE1-OB | 125 cases（5 服务 × 5 故障 × 5 reps） | 70 分钟/case；v1 已用测试集，v2 **不得**用它选参 |
| 外部验证 | RE1-SS | 125 cases（carts/catalogue/orders/payment/user × 5 故障 × 5 reps） | 12 分钟/case；57–63 指标 |

- 划分（两套一致）：`rep 1–3` 开发（各 75），`rep 4–5` 测试（各 50）。
- **RE1-SS 测试集只跑一次**；所有阈值、权重、融合方式只在 dev（RE1-OB dev + RE1-SS dev）上选。
- RE1-SS 的 `latency-50` / `latency-90` 是两个独立指标列；`workload` 是流量，不作为异常分。

## 3. 方法

### 3.1 指标族解析（必须双系统兼容）

```
resource: *_cpu, *_mem, *_diskio
sli:      *_latency, *_latency-50, *_latency-90, *_error
traffic:  *_load, *_workload        # 只用于可视化/门控，不直接计分
```

服务名折叠沿用 RCAEval 口径：`<service>_<indicator>` → `service`，`*-db` 折回宿主服务
（`carts-db` → `carts`）。Sock Shop 的 `front-end` 保持连字符。

### 3.2 双检测臂

- 两臂共用：只读滚动基线（无未来信息）、正向 robust-z、材料性下限 8%、连续 3 点越线。
- 资源臂：`RESOURCE` 族；阈值在 dev 上按「误报率 ≤ 10% 前提下最大化检出」选。
- SLI 臂：`SLI` 族（RE1-SS 用 `latency-90` + `error`）；同一选参准则。
- 合并：任一臂报警即报警；分别报单臂与合并的覆盖率、误报、延迟。
- 观察窗：`pre = min(600s, 注入前可用长度)`——RE1-OB 为 600s，RE1-SS 为 360s；
  报告必须写清每套数据的实际前窗，禁止把 6 分钟前窗和 10 分钟前窗的误报率直接横比。

### 3.3 自适应定位

对每个 case：

1. 资源分 `res[s]`、SLI 分 `sli[s]`（P90 持续性，同 v1）；
2. 显著性门控：`max(res) ≥ τ_res`、`max(sli) ≥ τ_sli`（τ 在 dev 上选）；
3. 融合：
   - 仅一臂显著 → 用该臂；
   - 两臂显著 → RRF 融合（`1/(60+rank)`，与检索层同一思想），或等价的秩融合；
   - 都不显著 → 退回 `max(res, sli)`；
4. 拓扑扣减：若某服务的被调方在任一臂上更强，则扣减该服务的 SLI 分量（不扣资源分量）；
5. 时相乘子：异常起始更早加权（沿用 v1，幅度在 dev 上选）。

**Sock Shop 调用图**（caller → callee）：

```
front-end → carts, catalogue, orders, user
orders    → carts, payment, shipping, user
carts     → carts-db
catalogue → catalogue-db
user      → user-db
```

`*-db` 折回宿主后，实际参与扣减的边是：`front-end → {carts, catalogue, orders, user}`、
`orders → {carts, payment, shipping, user}`。

### 3.4 选参准则（v1 的教训）

- 只用 dev；候选参数必须同时满足：
  - 聚合 Top-1 不低于当前最好；
  - **任一故障类型 Top-1 相对 3σ 下降 ≤ 5pp**（v1 的 delay −80pp 不允许重演）；
- 选参结果、候选网格、淘汰理由写入报告；测试集结果不参与任何回退。

## 4. Allowed paths

- `offline/rca/**`（含 `download.py` / `dataset.py` / `methods.py` / `detect.py` / `topology.py` /
  `run.py` / `report.py` / `tests/**` / `reports/V2-report.md` / 新运行目录）
- `plans/rca-v2-accept.md`（本文件）
- `docs/adr/0040-rca-v2-dual-arm.md`（新增，Proposed）
- `docs/repo-map.md`（仅更新 `offline/rca/` 行 + ADR 计数 39→40）
- `.gitignore`（仅在需要新增 RE1-SS 忽略项时）

**禁止修改**：`offline/rca/reports/M2-report.md` 与 v1 的四个已提交运行目录（只读历史证据）。

## 5. Acceptance criteria（冻结后不得静默放宽）

- **AC-1 数据可复现**：`download.py --suite re1-ob` 与 `--suite re1-ss` 均可拉取并写**各自**的
  provenance（不得互相覆盖）；主源 HuggingFace，失败可回落 `hf-mirror.com` 且记录实际来源；
  每套 251 个文件 / 125 cases；`--verify` 退出 0；两套各 125/125 标签一致、125/125 `inject_time` 一致。
- **AC-2 无泄漏**：所有选参只用 dev；RE1-SS 测试集只跑一次；特征路径不读
  `root_cause_service` / `fault` / case 名；测试覆盖全部方法与双检测臂的标签不变性。
- **AC-3 v1 回归护栏**：用 v1 方法重跑 RE1-OB 测试集（seed 7），六个方法的
  Top-1/Top-3/MRR 与 `M2-report.md` 完全一致（逐 case `results.csv` 除时间戳/路径外一致）。
- **AC-4 聚合增益（RE1-SS 测试）**：v2 的 Top-1 ≥ 3σ 的 Top-1 + 5pp；配对 bootstrap 差值 CI 下界 > 0。
- **AC-5 分故障非回归（RE1-SS 测试）**：五种故障中，任一类 v2 的 Top-1 不得比 3σ 低超过 5pp。
  这是 v2 的**核心门槛**；不满足即判 v2 失败。
- **AC-6 检测（RE1-SS 测试）**：双臂合并覆盖率 ≥ 80%，误报 case 比例 ≤ 5%（在各自前窗内），
  延迟中位数 ≤ 30s、P90 ≤ 60s；同时报资源臂/SLI 臂/合并三行。
- **AC-7 端到端（RE1-SS 测试）**：以检测起点做定位，漏检与误报均记失败；
  v2 的端到端 Top-1 ≥ 3σ + 5pp，附配对 CI。
- **AC-8 可复现与不越界**：`pytest offline/rca/tests -q` 全绿（无 skip）；`report.py --check` 通过；
  报告含 Python/依赖版本、两套数据 sha256、完整命令；固定 seed 重跑指标一致；
  `git diff --name-only main...HEAD` 不含 `src/`、`pom.xml`、`offline/requirements-dev.txt`。
- **AC-9 独立验收**：独立 Verifier 逐条重跑 AC-1..AC-8，证据绑定 HEAD SHA + `git status --short`。

## 6. Verification commands（Verifier 亲自重跑）

```powershell
offline/rca/.venv/Scripts/python.exe offline/rca/download.py --suite re1-ob --verify
offline/rca/.venv/Scripts/python.exe offline/rca/download.py --suite re1-ss --verify
offline/rca/.venv/Scripts/python.exe -m pytest offline/rca/tests -q
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-ob --split test --methods all --seed 7
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-ss --split test --methods all --seed 7
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --suite re1-ss --split test
offline/rca/.venv/Scripts/python.exe offline/rca/report.py --dir <run_dir> --check
git rev-parse HEAD
git status --short
```

## 7. 判定矩阵（防止"赢了聚合、输了故障类型"）

| AC-4 聚合增益 | AC-5 分故障非回归 | AC-6 检测 | 结论 |
| --- | --- | --- | --- |
| PASS | PASS | PASS | v2 成立；可另立 M4 契约接产品面 |
| FAIL | PASS | PASS | v2 是"不回退的改进"，不接产品面；保留为候选 |
| 任意 | **FAIL** | 任意 | v2 失败，不接产品面；报告写清原因 |

## 8. Forbidden

- 用 RE1-SS 测试集（rep 4–5）或 RE1-OB 测试集选参、回退、挑 seed。
- 改/删 v1 的 `M2-report.md`、四个已提交运行目录、既有测试或断言。
- 改 `src/**`、`pom.xml`、`offline/requirements-dev.txt`。
- 把标签字段（服务名、故障名、注入时间）引入特征或检测。
- 声称"故障预测 / 性能优化"；v2 仍只覆盖检测与定位。

## 9. 里程碑

| 阶段 | 内容 | 出口 |
| --- | --- | --- |
| V2-0 | RE1-SS 下载 + 双 provenance + 指标族解析 + Sock Shop 拓扑 | 25 cases 端到端，AC-1/2/3 过 |
| V2-1 | 双检测臂 + dev 选参（误报约束） | RE1-SS dev 覆盖率 ≥ 80%，误报 ≤ 5% |
| V2-2 | 自适应融合定位 + 分故障非回归选参 | dev 上无故障类型下降 > 5pp |
| V2-3 | RE1-SS 测试集一次性评估 + 报告 + ADR-0040 + Verifier | AC-4..9 判定 |

## 10. 风险（写进报告，不许回避）

1. **RE1-SS 只有 12 分钟**：前窗 6 分钟，检测基线比 RE1-OB 更紧；误报率不能与前窗 10 分钟的 OB 直接横比。
2. **`*-db` 折叠**：`carts-db` 的指标会被算到 `carts` 头上；这是 RCAEval 口径，但会让宿主服务更容易"看起来异常"。
3. **`latency-50/90` 是分位数列**：`latency-90` 更敏感也更容易抖动；必须用同一材料性下限约束。
4. **样本小**：每套测试只有 50 cases；分故障只有 10 个/类，CI 会很宽；用配对比较，不拿单点下结论。
5. **v1 测试集已用掉**：v2 的任何"更好"只能由 RE1-SS 测试集证明；RE1-OB 只作回归护栏。
