# RCA v3 验收契约（草案 v1）

> 状态：**FROZEN（2026-11-27，Lead 签收"按草案冻结 v3"）**。基线
> `codex/rca-v2@4b6c842`；实现分支 `codex/rca-v3`。冻结后如需放宽任一 AC，
> 须先改本文件并重新签收。
> Task ID：`rca-v3`
> 判定：**走链路**（新方法 + 新数据 + 可能的产品结论，按高一档）。

## 1. 目标

v2 的最终结论是：定位在 RE1-SS 测试集上有 +22pp 的**探索性**增益，但按契约
§3.3 合规的等权 RRF 在 dev 上 0/36 达标；检测误报 6%。v3 只回答一个问题：

**把两臂信号换成合规的“置信加权秩融合”后，定位增益能否在第三个系统
RE1-TT（Train Ticket）上复现？**

v3 **不解决检测的产品化**。检测只做零样本迁移诊断，不作为产品门槛；
端到端同理。v3 的结论只覆盖“检测起点已知时的定位”。

## 2. 数据与划分

| 用途 | 数据集 | 规模 | 说明 |
| --- | --- | --- | --- |
| 选参 | RE1-SS dev + RE1-OB dev | 75 + 75 | 只用 rep 1–3；v2 已冻结 |
| 纯 holdout | RE1-TT | 125 | 5 服务 × 5 故障 × 5 rep；**不参与任何调参** |

- RE1-TT 的 125 个 case 全部作为 test，不切 dev：v3 不在 TT 上选任何参数。
- 在 V3-2 一次性评估之前，不得查看 TT 的任何聚合指标；V3-0 只做结构审计。
- TT 的服务名预期为 `ts-*`（含连字符），指标列预期形如 `ts-auth-service_cpu`。
  `to_service` 必须按最后一个 `_` 切分；该修复对 OB/SS 的 14,170 个列名逐列
  等价（已做只读校验），由 AC-3 回归锁定。

## 3. 方法

### 3.1 定位：置信加权秩融合（`dual-arm-rrf`）

对每个 case：

1. 资源分 `res[s]`、SLI 分 `sli[s]`：与 v2 相同（只读滚动基线、正向 robust-z、
   P90 持续性、8% 材料性下限；缺测记 0）。
2. 显著性门控：`max(res) ≥ τ_res`、`max(sli) ≥ τ_sli`；τ 只在 SS/OB dev 上选。
3. 两臂都显著：

   ```
   w_res = max(res) / (max(res) + max(sli))
   w_sli = max(sli) / (max(res) + max(sli))
   score(s) = w_res / (60 + rank_res(s)) + w_sli / (60 + rank_sli(s))
   ```

   服务排序只由两臂的**秩**决定；`w_*` 是每臂一个标量，来自 §3.3 已经用于
   门控的同一个 max-score 统计量。这是“带臂置信度的秩融合”，不是分数级加权。
4. 仅一臂显著 → 用该臂；都不显著 → `max(res, sli)`。
5. 时相乘子：异常起始更早加权（first-mover boost，幅度在 dev 上选）。
6. 拓扑扣减：SS/OB 用现有调用图；TT 没有可用调用图，扣减禁用（见 §11 风险）。

候选网格（只在 SS/OB dev）：

- `τ_res, τ_sli ∈ {3,5,8}`
- `callee_discount ∈ {0.5,0.8}`（TT 不生效）
- `first_mover_boost ∈ {0.3,0}`
- `k = 60` 固定，`fusion = conf_rrf` 固定；`weighted` / `max` 不再作为候选。

选择规则：

1. SS dev 与 OB dev **都**必须满足「任一故障 Top-1 相对 3σ 下降 ≤5pp」；
2. 在合规集合里最大化两套 dev 的平均 Top-1；
3. 再比 MRR，再比最差分故障差值；仍打平则取默认参数。

**V3-1 门槛**：若 SS dev 或 OB dev 任一不满足分故障约束，停止，不跑 TT，
报告写“合规秩融合在 dev 不成立”。

### 3.2 检测：零样本诊断（非门槛）

- 直接使用 v2 冻结的检测器：resource=20 / SLI=8 / sustain=3 / 材料性下限 8%；
  TT 上不做任何阈值调整、不做 per-system commissioning。
- 报告 TT 的覆盖率、误报 case 比例、中位/P90 延迟；**不设 PASS/FAIL**。
  它衡量的是跨系统迁移，不是 v3 的主问题。
- 端到端（检测起点替代注入点）同样只报告，不作为产品结论。

## 4. 前置修复

1. `dataset.to_service`：`column.rsplit("_", 1)[0].replace("-db", "")`。
   OB/SS 14,170 列逐列等价；TT 的 `ts-*` 正确折叠，不产生 `"ts"` 桶。
2. `audit.py --suite` 增加 `re1-tt`；审计每个 case 至少 1 个 resource 与 1 个 SLI，
   且 `candidate_services` 不含 `"ts"`。
3. `download.py --suite re1-tt` 可拉取 251 文件 / 125 cases，写独立 provenance。

## 5. Allowed paths

- `offline/rca/**`（含 `dataset.py` / `methods.py` / `run.py` / `audit.py` /
  `detect.py` / `tune_rank.py` / `report.py` / `tests/**` / `reports/V3-report.md` /
  新运行目录）
- `plans/rca-v3-accept.md`（本文件）
- `docs/adr/0041-rca-v3-third-system.md`（新增，Proposed）
- `docs/repo-map.md`（仅更新 `offline/rca/` 行 + ADR 计数）
- `.gitignore`（仅在需要新增 RE1-TT 忽略项时）

**禁止修改**：`offline/rca/reports/M2-report.md`、`V2-report.md`、v1/v2 的已提交
运行目录、v2 的 `DualArmRanker` 默认行为（`weighted` 保持冻结，供 v2 复现）。

## 6. Acceptance criteria

- **AC-1 TT 数据可复现**：`download.py --suite re1-tt` 拉取 251 文件 / 125 cases；
  写独立 `PROVENANCE-re1-tt.json`；`--verify` 退出 0；125/125 标签与 `inject_time`
  一致。
- **AC-2 无泄漏**：选参只用 SS/OB dev；TT 在 V3-2 之前不参与任何排名或指标计算；
  特征路径不读 `root_cause_service` / `fault` / case 名；测试覆盖
  `dual-arm-rrf` 的标签不变性。
- **AC-3 v1 回归护栏**：`regression.py` 仍与 `M2-report.md` 逐 case 一致
  （300 行 / 6 方法）；`to_service` 修复不得改变 OB/SS 结果。
- **AC-4 TT 聚合增益**：`dual-arm-rrf` 的 Top-1 ≥ 3σ 的 Top-1 + 5pp；
  配对 bootstrap 差值 CI 下界 > 0。
- **AC-5 TT 分故障非回归**：五种故障中，任一类 `dual-arm-rrf` 的 Top-1
  不得比 3σ 低超过 5pp。**这是 v3 的核心门槛。**
- **AC-6 TT 解析正确**：125/125 审计通过；`candidate_services` 包含 5 个真因服务，
  且不含 `"ts"`；每个 case 至少 1 个 resource 与 1 个 SLI 列。
- **AC-7 检测诊断已报告**：TT 上 v2 冻结检测器的覆盖/误报/延迟写入报告；
  不设 PASS/FAIL，但不得省略。
- **AC-8 可复现与不越界**：`pytest offline/rca/tests -q` 全绿（无 skip）；
  `report.py --check` 通过；报告含 Python/依赖版本、TT sha256、完整命令；
  固定 seed 重跑指标一致；`git diff --name-only main...HEAD` 不含 `src/`、
  `pom.xml`、`offline/requirements-dev.txt`。
- **AC-9 独立验收**：独立 Verifier 逐条重跑 AC-1..AC-8；证据绑定 HEAD SHA +
  `git status --short`。

## 7. Verification commands（Verifier 亲自重跑）

```powershell
offline/rca/.venv/Scripts/python.exe offline/rca/download.py --suite re1-tt --verify
offline/rca/.venv/Scripts/python.exe offline/rca/audit.py --suite re1-tt
offline/rca/.venv/Scripts/python.exe -m pytest offline/rca/tests -q
offline/rca/.venv/Scripts/python.exe offline/rca/regression.py
offline/rca/.venv/Scripts/python.exe offline/rca/tune_rank.py --suite re1-ss --guard re1-ob --fusion conf_rrf
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-tt --split all --methods all --seed 7
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --suite re1-tt --split all --arms both
offline/rca/.venv/Scripts/python.exe offline/rca/report.py --dir <run_dir> --check
git rev-parse HEAD
git status --short
```

## 8. 判定矩阵

| AC-4 聚合增益 | AC-5 分故障非回归 | 结论 |
| --- | --- | --- |
| PASS | PASS | v3 成立：定位增益在第三个系统复现；仍不接产品面（检测未解决） |
| FAIL | PASS | v3 失败：增益不可复现，但无故障类型回退；保留为诊断 |
| 任意 | FAIL | v3 失败：存在故障类型回退，按 v1 的 delay 教训直接否决 |
| AC-6 解析 FAIL | 任意 | 先修解析并重新冻结契约，不得在坏解析上评估 |

## 9. Forbidden

- 在 TT 上选任何参数（含检测阈值、融合参数、拓扑参数、seed 挑选）。
- 在 V3-2 之前查看 TT 的聚合 Top-1/误报等性能指标。
- 改/删 v1 的 `M2-report.md`、`V2-report.md`、已提交运行目录、既有断言。
- 改 `src/**`、`pom.xml`、`offline/requirements-dev.txt`。
- 把标签字段（服务名、故障名、注入时间）引入特征或检测。
- 声称“故障预测 / 性能优化 / 检测已产品化”；v3 只覆盖“已知检测起点时的定位”。

## 10. 里程碑

| 阶段 | 内容 | 出口 |
| --- | --- | --- |
| V3-0 | `to_service` 修复 + TT 下载/provenance + `audit.py` TT | AC-1/3/6 过；不跑排名 |
| V3-1 | SS/OB dev 上 `conf_rrf` 选参 | AC-2 过；两套 dev 分故障约束全过 |
| V3-2 | TT 125 cases 一次性评估 + 检测诊断 | AC-4/5/7 判定 |
| V3-3 | `V3-report.md` + ADR-0041 + 独立 Verifier | AC-8/9 |

## 11. 风险（写进报告，不许回避）

1. **TT 候选服务远多于 OB/SS**：Train Ticket 遥测约 200+ 指标（索引 n_metrics
   198–238），预计 30–40 个候选服务，
   Top-1 的绝对难度更高；AC-4 用相对 3σ 的增益，不使用绝对分数线。
2. **TT 没有调用图**：数据不含 traces/logs/root-cause 文件，`topology.py` 也没有
   Train Ticket 图；V3 在 TT 上禁用拓扑扣减。因此 v3 验证的是
   “双臂 + 秩融合”，不是拓扑先验。
3. **检测大概率迁移失败**：OB 的正常 z 尾部与故障重叠（v2 已证），TT 的
   30–40 个服务会进一步抬高“任一服务报警”的误报。v3 只把它当诊断，不当门槛。
4. **样本**：TT 125 cases、25 个/故障；CI 仍会宽，必须用配对比较。
5. **网络前置**：TT 下载当前被本机网络拒绝（HF 与 hf-mirror 均 connection refused）；
   V3-0 无法开始，直到网络恢复或数据由外部提供。

## 12. 失败时怎么写

- 若 V3-1 在 dev 不成立：报告写“合规秩融合在 dev 失败”，不跑 TT，不作任何
  产品声明。
- 若 V3-2 的 AC-5 失败：按判定矩阵判 v3 失败，写清哪个故障类型、差多少 pp。
- 若只有检测诊断失败：v3 定位结论仍成立，但报告必须写“检测未产品化，
  端到端不可用”。

## 附录 A：草案前的只读原型（非验收证据）

以下数字来自未提交的 scratch 脚本，只在 SS/OB 的 rep 1–3 上运行，未看 TT；
用途仅是判断“这个方法是否值得冻结”。冻结后必须由正式的
`tune_rank.py --fusion conf_rrf` 复现，scratch 数字不构成验收证据。

| 方法（dev） | SS Top-1 | SS delay | SS 最差分故障 | OB Top-1 | OB 最差分故障 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 3σ | 0.840 | 0.800 | — | 0.693 | — |
| 等权 RRF（k=60，契约字面） | 0.840 | 0.533 | −26.7pp | 0.853 | +0.0 |
| 置信加权 RRF（k=60） | 0.960 | 1.000 | +0.0 | 0.880 | +0.0 |

RRF 失败机制：delay 故障时 SLI 臂把真因排第 1（score 600–2500），资源臂把它排
第 3–5（score 12–25）；等权 RRF 让弱臂把真因拖到第 2。置信加权只改“每臂一个
标量权重”，服务排序仍由秩决定，因此属于带臂置信度的秩融合。

检测侧的两个原型都不达标，故 v3 不把检测当门槛：

- 逐 case 因果扩张分位阈值（q99.5、预热 300s）：SS dev 覆盖率掉到 0.213；
- 按系统用无标签前窗做 commissioning：OB dev q99.9 阈值 9.49 仍有 38.7% 误报。

结论：OB 的正常 z 尾部与故障重叠，阈值类方法无法同时保住覆盖与误报；
这是 v4 的独立研究问题，不应阻塞 v3 的定位外部验证。

## 附录 B：独立验收记录（2026-11-27）

> 本附录只记录验收结果，不放宽、不改写 AC-1..AC-9。

独立 Verifier（与实现会话分离，未继承实现方上下文）在
`HEAD=211cea10faae207a01b66d0733577dc618e68183`、`git status --short` 为空的
状态下逐条重跑，判定 **AC-1..AC-9 全部 PASS**，按 §8 矩阵结论为
**v3 成立：定位增益在第三个系统复现；仍不接产品面（检测未产品化）**。

关键复验结果（Verifier 自算，非引用实现方日志）：

- AC-4：0.744 vs 0.448 = +29.6pp，配对 95% CI [+18.4, +40.0]，McNemar
  p=1.24e-6，独对 48 / 独错 11。
- AC-5：cpu +60.0pp、delay +24.0pp、disk +24.0pp、loss +24.0pp、mem +16.0pp；
  最差 +16.0pp，无任一类回退。
- AC-8：61 passed / 0 skipped；固定 seed 重跑 `results.csv`、`detection.csv`
  与已提交版本逐字节一致；TT sha256 重算逐位一致。

Verifier 提出的非阻塞观察已记入 `offline/rca/reports/V3-report.md` §7：
`random` 基线用 case 名做 seed（无可测泄漏）、`inject_time` 切窗的契约措辞
张力、`download.py --verify` 不校验标签、`tune_rank.py --suite` 缺 `choices`
护栏。后两条属护栏加固，留待 v4 或后续维护，不在验收后改动已绑定证据的代码。

**签收状态：已签收（2026-11-27，Lead「签收v3」）。** ADR-0041 状态同步为
已采纳；本契约 AC-1..AC-9 不放宽，后续若改判据须另立契约（v4）。
