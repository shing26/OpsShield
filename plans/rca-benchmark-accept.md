# RCA 薄切片验收契约（草案 v1）

> 状态：**FROZEN（2026-11-26，Lead 签收"按草案冻结"）**。基线 `main@d8c47e8`；实现分支 `codex/rca-benchmark`。
> 冻结后如需放宽任一 AC，须先改本文件并重新签收，不得在实现中静默调整。
> Task ID：`rca-benchmark`
> 判定：**走链路**（新增子系统；虽只动 `offline/`，但产出的是方法结论、后续要接回产品面，按高一档处理）。
> 角色：Implementer 在独立分支/worktree 工作；Verifier 为独立 subagent，按第 5 节亲自重跑。

## 1. 目标与边界

在现有「知识检索 + 只读护栏」网关之上，补上前半段的**检测 + 定位**能力，并在公开故障数据集上产出**可与外部基线对比、可复现**的指标。

第一期只做离线研究工具，**不改产品运行时**：不新增端点、不改 `src/`、不动现有语料与门禁。

一句话验收口径：**同一个公开测试集、同一套指标，本方法与至少 4 个基线对比，结论按实际写。**

## 2. 数据集与任务

- 数据：`RCAEval` 的 **RE1-OB**（HuggingFace `phamquiluan/RCAEval`，MIT）。
- 规模：125 cases = 5 个根因服务 × 5 类故障 × 5 次重复；每 case 49 个指标、4201 个时间步（约 70 分钟，注入点在正中）。
- 根因服务：`adservice` / `cartservice` / `checkoutservice` / `currencyservice` / `productcatalogservice`。
- 故障类型：`cpu` / `mem` / `disk` / `delay` / `loss`。
- 任务：给定故障后的遥测，输出**根因服务排序**。
- 双轨评测：
  - **Oracle-onset**：以 `inject_time` 为异常起点（对齐 RCAEval 基线口径，便于横比）。
  - **Detected-onset**：方法自行判定异常起点，额外报**检测延迟**（注入 → 首次告警，秒）。
- 数据划分：`repetition 1–3` 用于开发/选参，`repetition 4–5` 为测试集；**测试集只在最终评估时跑**。

## 3. Allowed paths

- `offline/rca/**`（新增：下载、解析、方法、评测、测试、报告）
- `plans/rca-benchmark-accept.md`（本文件）
- `docs/adr/0037-rca-detector-slice.md`（新增，状态 Proposed）
- `.gitignore`（仅新增 RCA 数据/venv 忽略规则）
- `docs/repo-map.md`（仅新增一行 `offline/rca/` 目录职责，M3 时改）

## 4. Acceptance criteria（冻结后不得静默放宽）

- **AC-1 数据可复现**：`python offline/rca/download.py --suite re1-ob` 从公共地址拉取 `cases.parquet` + 125 个 case，落 `offline/rca/data/`（gitignored），并写 `offline/rca/data/PROVENANCE.json`（含源 URL、文件数、逐文件 sha256）。
- **AC-2 标签一致**：解析出的 `root_cause_service` 与 `cases.parquet` 逐条一致，**125/125**；不一致即失败，不做模糊匹配。
- **AC-3 无泄漏**：所有基线与方法的统计量只来自**注入前窗口**；测试集（rep 4–5）不参与任何阈值、权重、拓扑先验的选择；特征中不得出现 case 名、故障名、注入时间等标签侧字段。
- **AC-4 指标完整**：报 **Top-1 / Top-3 / MRR**，附 95% bootstrap CI；候选集只有 5 个，**Top-5 退化（恒为 100%），不报**。逐 case 明细落 CSV。
- **AC-5 基线齐备**：至少 4 个基线同一测试集跑完——random（固定 seed）、静态阈值（error/latency）、3σ、robust-z（MAD）、IsolationForest（可后置）。每个基线给出同一套指标。
- **AC-6 本方法可证伪**：方法 = robust-z 服务聚合 + first-mover（异常起始更早加权）+ 拓扑下游扣减（调用方 SLI 异常不应顶替被调方根因）。与**最强基线**做配对比较（McNemar 或 bootstrap 差值 CI），结论按实际写；若未超过，必须写"未超过"并分析原因。
- **AC-7 检测轨**：Detected-onset 与 Oracle-onset 分表；报检测延迟的中位数/P90，以及检测失败（未在窗口内触发）的 case 数。
- **AC-8 命令可复现**：报告含完整命令、Python 与依赖版本、数据 sha256；固定 seed 下重跑指标一致。
- **AC-9 测试**：`python -m pytest offline/rca/tests -q` 全绿；覆盖 parquet/CSV 解析、注入点切分、Top-k/MRR、bootstrap、拓扑扣减、**无泄漏断言**。
- **AC-10 不越界**：`git diff --stat` 不含 `src/**`、不含既有 `offline/*.py`；不改/删/弱化任何现有测试。

## 5. Verification commands（Verifier 亲自重跑，不采信实现方日志）

```bash
python offline/rca/download.py --suite re1-ob --verify
python -m pytest offline/rca/tests -q
python offline/rca/run.py --split test --methods all --seed 7
python offline/rca/report.py --check
git rev-parse HEAD
git status --short
```

证据必须绑定 `HEAD SHA + git status --short`；任一 AC 不满足即验收失败。

## 6. Forbidden（任一发生即失败）

- 改 `src/main/**` 或 `src/test/**`。
- 改/删/弱化现有测试与断言，或跳过测试不记录。
- 用测试集调参、按 case 名/故障名特判、把标签泄漏进特征。
- 把 `offline/rca/data/**`、venv、下载产物入库。
- 声称"故障预测/性能优化"能力；本切片只覆盖**检测与定位**。
- 向 `pom.xml` 或 `offline/requirements-dev.txt` 引入运行时依赖。

## 7. 里程碑

| 阶段 | 内容 | 出口 |
| --- | --- | --- |
| M0 | 下载 + 解析 + Top-k/MRR + random baseline | 25 cases 端到端跑通，AC-1/2/4/9 过 |
| M1 | 静态阈值 / 3σ / MAD / IForest | 全部基线同表可复现，AC-5 过 |
| M2 | robust-z + first-mover + 拓扑扣减 | 测试集配对比较，AC-3/6 过 |
| M3 | 全量 125 cases + 检测轨 + 报告 + ADR + repo-map | AC-7/8/10 过，Verifier 签收 |
| M4 | 定位结果接回网关做告警增强/解释层 | **另立契约**（改产品面） |

## 8. 非目标

- 故障预测、RUL、容量预测。
- 日志/链路多模态融合（RE1 仅指标；RE2 留到 M4 之后再议）。
- 在线部署、HA、K8s、真实压测。

## 9. 已知风险（写进报告，不许回避）

1. **候选只有 5 个**：Top-3 的下界就有 60%，随机基线 Top-3 恒为 60%。必须报 Top-1 与 MRR 为主，Top-3 只作辅助。
2. **症状≠根因**：49 个指标混有调用方 latency/error，简单取最大偏离会把上游症状判成根因；拓扑扣减是核心机制，不是装饰。
3. **样本小**：5 reps，CI 会宽；用配对比较与逐故障类型分解，不拿单点提升下结论。
4. **数据只有指标**：定位粒度只能到服务级，不能到实例/代码行；报告需显式声明。

## 10. 执行记录（非契约变更）

- 2026-11-26：ADR 编号 `0037` 已被 `0037-incident-notification.md` 占用，本切片 ADR 实际落为
  `docs/adr/0039-rca-detector-slice.md`。仅为编号避让，Allowed paths 的意图不变，AC 未放宽。

## 11. R1 修订（2026-11-26，**已签收**）

独立 Verifier 首次验收时发现冻结文本与实现口径不一致。以下修订均为**收紧或事实同步**，
未放宽任何质量门槛：

- **AC-4 候选集**：草案假设"5 个候选 / Top-5 退化"。实际按 RCAEval 口径取该 case 遥测中
  出现的全部服务（dev 13–19、test 13–14）。这比 5 候选更难；随机基线从 20%/60% 降到
  ~6–8%/20–23%。主表仍只报 Top-1/Top-3/MRR。
- **AC-5 基线口径**：契约字面写 robust-z(MAD)，BARO 实现用 IQR。保留 `baro`（IQR，外部可比），
  新增 `mad-z`（字面 MAD）作为第五个基线；`mad-z` 在本数据上退化（test Top-1 0.020），如实报告。
- **AC-7 失败口径**：未报警与注入前误报都记检测失败；误报 case 不参与定位评分（记满盘皆输）。
- **AC-10 路径**：`docs/repo-map.md` 允许"新增 `offline/rca/` 一行 + 同步 ADR 计数 38→39"，
  其余仍禁止改动。

**签收记录**：Lead 于 2026-11-26 回复「签收 R1」。AC-4 / AC-5 / AC-7 / AC-10 的判定以本节为准。
