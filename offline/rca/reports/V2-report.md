# RCA v2 结果（双检测臂 + 自适应融合 + RE1-SS 外部验证）

> 契约：`plans/rca-v2-accept.md`（FROZEN 2026-11-26）
> 分支：`codex/rca-v2`；测试集评估时的 HEAD：`360a52ad2516151b834251524d8b7f33d92d0e85`
> 最终裁决：**FAIL（按冻结契约）**；裁决记录见契约 §11，验收证据绑定复验时的 HEAD
> 数据：RCAEval RE1-OB（回归护栏）+ RE1-SS（外部验证，Sock Shop）

## 结论（先说结果）

**v2 按冻结契约 FAIL，不接产品面。** Lead 已裁决「按草案冻结」，即 §3.3 是硬约束，
`weighted` 分数加权不属于其允许的融合方式。据此有两个独立失败原因：

1. **合规融合族在 dev 选参阶段就没有可行配置（主因）。** §3.3 要求两臂都显著时
   必须用 RRF 或等价秩融合。`V2-rank-sweep.json` 的 36 个 RRF 配置中，**0 个**
   满足 §3.4 的分故障非回归约束；最佳 RRF 聚合 Top-1 0.8667（比 3σ 的 0.840
   高 2.7pp，低于 AC-4 要求的 5pp），且 delay 0.6667（比 3σ 的 0.800 低 13.3pp）。
   合规方法在 dev 上已被淘汰，因此没有可送测试集的 v2 方法；AC-4 / AC-5 / AC-7
   均无合规证据。
2. **检测误报超线（AC-6）。** 双臂合并覆盖率 84%、中位 17.5s、P90 26.9s 达标，
   但误报 **3/50 = 6.0% > 5%**。

**探索性结果（不合规，不计入 AC）**：已实现的 `weighted` 变体确实在 RE1-SS
测试集上跑出 Top-1 **0.940** vs 3σ **0.720**（+22pp，配对 95% CI [+10pp, +36pp]，
McNemar p=0.0034），五类故障均不低于 3σ，端到端 **0.800** vs **0.640**
（+16pp，CI [+6pp, +26pp]）。这是真实数字，但它违反 §3.3；只能作为 v3 的假设来源，
不能作为 v2 的验收证据。

**决策：不接产品面。** 若继续，须另立 v3 契约：先做合规秩融合（或先改 §3.3 并
重新签收），并换用新 holdout（RE1-TT 或新切分）；RE1-SS 测试集不得复用。

## 1. 数据、环境与证据

### 1.1 两套数据（实测，不是契约里的估计）

| 项 | RE1-OB | RE1-SS |
| --- | --- | --- |
| cases | 125（5 服务 × 5 故障 × 5 rep） | 125（carts/catalogue/orders/payment/user × 5 故障 × 5 rep） |
| 指标列 | 49–59 | 57–63 |
| 时间步 | 721 / 2679 / 4201 | 607 / 647 / 721 |
| 实际时长 | 12.0 min（75）/ 44.6 min（1）/ 70.0 min（49） | 10.1–12.0 min（123 个 12.0） |
| 注入前可用 | 360s（75）/ 2100s（50） | 360s（125） |
| 缺测 | 11/125 cases 有 NaN（error/load 列） | 25/125 cases 有 NaN（`*_error` 为主） |

**契约里的「RE1-OB 70 分钟/case」不准确**：125 个 case 里只有 49 个是 70 分钟，
75 个是 12 分钟、1 个 44.6 分钟。v1 的 `M2-report.md` 写「4201 时间步」也只对其中 49 个成立。
这直接影响检测前窗：RE1-OB 有 75 个 case 只有 360s 注入前数据，与 RE1-SS 相同。

`latency-50` / `latency-90` 两套数据**都有**（RE1-OB 也有，不只是 RE1-SS）；
`*_workload`（SS）/ `*_load`（OB）只算流量，不计分。

### 1.2 provenance 与可复现

- 两套各 251 文件（index + 125 × 2），`download.py --verify` 均返回 0。
- `cases.parquet` sha256 = `ec0008c90d0c97076bc98370ac824c3c5a49be95d00bd6d6044ea8061861d271`
- `PROVENANCE-re1-ss.json` sha256 = `267b0cbbed68b51450b628759a93e46f6687569222033fc890ec1a43b4e95945`
- `PROVENANCE-re1-ob.json` sha256 = `bedd290022a9840218d4d086589491fa73d32f957f3b5079d7b187f0d215740c`

逐文件来源：

- **RE1-SS**：主源 HuggingFace 当时被代理 DNS 挡住（`getaddrinfo failed`），
  251 个文件实际来自 `hf-mirror.com`；provenance 的 `probed_sources` 记录了主源
  失败原因，`files[*].source` 逐文件记录了实际 URL。
- **RE1-OB**：v1 的 legacy manifest 只有哈希、没有逐文件 source。本次用
  HuggingFace 主源强制重下了同一套 251 个文件做字节校验：**251/251 的 SHA-256
  与现有数据一致**，随后用新 manifest 回填 `files[*].source = huggingface.co`，
  并用 `--verify` 复核通过。因此 OB 数据也是主源原字节；此前「两套都来自
  hf-mirror」的表述对 OB 不成立，已更正。

- 环境：Python 3.11.9；numpy 2.4.6；pandas 2.3.3；pyarrow 21.0.0；pytest 9.2.0。
- 固定 seed 重跑（dev，两次）`results.csv` 逐字节一致。
- 数据与 venv 均 gitignored，不入库。

### 1.3 数据审计

`audit.py` 对两套各 125/125 校验通过：索引标签、`inject_time.txt` 与索引一致、
时间步/指标列数与索引一致、每个 case 至少 1 个 resource 与 1 个 SLI 列。

## 2. 冻结口径与 dev 选参

### 2.1 检测（V2-1）

契约固定：只读滚动基线、正向 robust-z、材料性下限 8%、连续 3 点越线、服务级任一指标越线。
唯一可调项是两臂阈值，20 个候选（resource ∈ {8,12,20,30} × SLI ∈ {8,12,20,30,50}），
在 RE1-SS dev（75）上按「覆盖率 ≥80% 且误报 ≤5%」选（比契约 §3.2 的 ≤10% 更严，
对齐里程碑 V2-1 的出口）。

选中 **resource=20 / SLI=8**：RE1-SS dev 覆盖率 **0.907**、误报 **1/75（1.3%）**、
中位 17.5s、P90 28.2s。resource=30 同分（取更低阈值）；resource=12 覆盖率同为 0.907
但误报 6/75（8%），被 5% 约束淘汰。

同一组参数在 RE1-OB dev：合计覆盖率 **0.480（36/75）**、误报 **31/75**
（resource 臂 0、SLI 臂 31）。早期草稿写 0.493，是因为 `counts_as_detection`
把超过 300s horizon 的迟到报警也记成检出（summary 37 vs CSV 36）；该函数已修正，
并按同一口径重跑，见 §7 的 dev 运行目录。
这是 §6 的核心限制，不是选参失误——在 OB 上把 SLI 阈值提到 20–30 可以消掉误报，
但 RE1-SS dev 覆盖率会掉到 0.61–0.63，同时违反 AC-6。

### 2.2 定位（V2-2）

候选网格 108 组（fusion ∈ {weighted, rrf, max} × τ_res/τ_sli ∈ {3,5,8}² ×
callee_discount ∈ {0.5,0.8} × first_mover_boost ∈ {0.3,0}），硬约束：SS dev 上
任一故障 Top-1 不得比 3σ 低 >5pp。**网格当时没有把 §3.3 的融合方式约束编码进去**，
所以 `weighted` 被当成候选并胜出——这是流程缺陷，不是合规证据。

选中**默认值**（weighted / τ=3,3 / discount=0.5 / boost=0.3）：SS dev Top-1 **0.947**
（3σ 0.840，+10.7pp，CI [+2.7, +18.7]pp，p=0.0386），最差分故障差值 **0.000**。
多组参数在 Top-1 上完全打平，选默认值以避免对 0.002 MRR 这类噪声调参。

OB dev 作为护栏：`dual-arm` 0.893 vs 3σ 0.693、v1 `topo-first` 0.787。

**合规性（关键）**：上述选中配置的 `fusion=weighted`。按冻结契约 §3.3，两臂都
显著时必须用 RRF 或等价的**秩**融合；`weighted`（分数加权）与 `max`（取大）
都不是秩融合，均不合规。把网格收紧到 RRF 的 36 个配置后：**0 个**满足 §3.4
的分故障非回归约束；最佳 RRF 聚合 Top-1 0.8667（+2.7pp），delay 0.6667
（−13.3pp）。因此合规方法在 dev 上就不成立，`weighted` 的结果只能标为探索性。

## 3. 测试集结果（RE1-SS，rep 4–5，一次性）

> 复现口径：「只跑一次」指**不得用测试集选参/回退/挑 seed**。独立 Verifier 按 AC-9
> 重跑**同一条冻结命令**属于复现验证，允许；但它必须与这里提交的逐 case 结果一致，
> 任何参数改动都不允许。
>
> **合规性声明（重要）**：本节所有数字都来自 `weighted` 融合。按 Lead 裁决与
> 契约 §3.3，`weighted` 不是合规融合方式，因此这些数字是**探索性证据**，
> 不用于 AC-4 / AC-5 / AC-7 的判定。合规的 RRF 族已在 dev 上 0/36 达标
> （见 §2.2）。

### 3.1 定位（探索性；不计入 AC-4 / AC-5）

> 表中的 `dual-arm` 是默认 `fusion=weighted` 的探索分支，不是契约合规方法。

| method | Top-1 | Top-3 | MRR | mean rank |
| --- | ---: | ---: | ---: | ---: |
| **dual-arm** | **0.940** | **1.000** | **0.970** | **1.06** |
| topo-first | 0.740 | 0.900 | 0.825 | 1.72 |
| 3sigma | 0.720 | 0.960 | 0.825 | 1.54 |
| baro | 0.400 | 0.980 | 0.664 | 1.84 |
| static-threshold | 0.340 | 0.520 | 0.516 | 3.92 |
| mad-z | 0.280 | 0.960 | 0.577 | 2.12 |
| random | 0.060 | 0.200 | 0.231 | 6.62 |

配对（逐 case，n=50）：

| 对比 | 差值 | 95% bootstrap CI | 独对/独错 | McNemar p |
| --- | ---: | --- | --- | ---: |
| dual-arm vs 3σ，Top-1 | +0.220 | [+0.100, +0.360] | 12 / 1 | 0.0034 |
| dual-arm vs 3σ，Top-3 | +0.040 | [+0.000, +0.100] | 2 / 0 | 0.5000 |

分故障 Top-1（探索性）：

| fault | dual-arm | 3σ | 差值 |
| --- | ---: | ---: | ---: |
| cpu | 1.000 | 0.800 | +0.200 |
| delay | 1.000 | 0.800 | +0.200 |
| disk | 1.000 | 0.700 | +0.300 |
| loss | 0.700 | 0.500 | +0.200 |
| mem | 1.000 | 0.800 | +0.200 |

**探索性：聚合 +22pp、五类全部不低于 3σ（最小 +20pp）；但融合方式不合规，
不计入 AC-4 / AC-5。**

### 3.2 检测（AC-6）

阈值 resource=20 / SLI=8，sustain=3，材料性下限 8%；观察窗 `min(600s, 注入前可用)`，
RE1-SS 实际为 360s。

| 臂 | 覆盖率 | 误报 case | 未报警 | 中位延迟 | P90 |
| --- | ---: | ---: | ---: | ---: | ---: |
| resource | 0.240 (12/50) | 1 | 37 | 15.5s | 20.0s |
| sli | 0.860 (43/50) | 2 | 5 | 20.0s | 34.2s |
| **合并** | **0.840 (42/50)** | **3 (6.0%)** | 5 | **17.5s** | **26.9s** |

判定：覆盖率 84% ≥ 80% **过**；中位 17.5s ≤ 30s **过**；P90 26.9s ≤ 60s **过**；
**误报 6.0% > 5% 未过**。

合并覆盖率（84%）低于 SLI 单臂（86%）不是笔误：合并取「最早报警」，resource 臂
在 1 个 case 上的注入前误报被当成首次报警，遮蔽了 SLI 臂随后的真报警；该 case
按 v1 口径（误报记失败）不检出。3 个误报里 1 个来自 resource 臂、2 个来自 SLI 臂。

### 3.3 端到端（探索性；不计入 AC-7）

以检测起点替代注入点做定位；漏检与误报都记失败。

| method | 端到端 Top-1（全 50） | 条件 Top-1（检出的 case） |
| --- | ---: | ---: |
| dual-arm | **0.800** | 0.952 |
| 3sigma | 0.640 | 0.762 |
| topo-first | 0.580 | 0.690 |

配对：dual-arm vs 3σ **+0.160**，CI **[+0.060, +0.260]**，独对 8 / 独错 0，
McNemar p=0.0078。**探索性：端到端 +16pp；同样因融合方式不合规，不计入 AC-7。**
（配对 CI 由同一次运行的逐 case 结果事后计算，没有重跑测试集。）

## 4. AC 判定表

| AC | 内容 | 结果 | 证据 |
| --- | --- | --- | --- |
| AC-1 | 双 suite 下载 + 各自 provenance，251 文件 / 125 cases，标签与 inject_time 一致 | **PASS** | 两次 `--verify` 返回 0；OB 251/251 逐文件 source 已回填；`audit.py` 两套 125/125 |
| AC-2 | 选参只用 dev；特征不读标签 | **PASS** | `tune_*` 只 `split="dev"`；`test_no_leakage` / `test_leakage_methods`（resource、sli、合并三组参数化） |
| AC-3 | v1 六方法在 RE1-OB 测试集与 `M2-report.md` 完全一致 | **PASS** | `regression.py`：300 行逐 case 一致，表格字符串一致 |
| AC-4 | 聚合 Top-1 ≥ 3σ + 5pp，配对 CI 下界 > 0 | **FAIL** | 合规 RRF 最佳 0.8667（+2.7pp < 5pp）；`weighted` 的 +22pp 不合规，不计入 |
| AC-5 | 任一分故障不比 3σ 低 > 5pp | **FAIL** | 合规 RRF 36 组 **0 组**达标；最差 delay 0.6667（−13.3pp） |
| AC-6 | 覆盖 ≥80%、误报 ≤5%、中位 ≤30s、P90 ≤60s | **FAIL** | 84% / **6.0%** / 17.5s / 26.9s |
| AC-7 | 端到端 Top-1 ≥ 3σ + 5pp，附配对 CI | **FAIL** | 无合规融合方法；探索性 +16pp 不计入 |
| AC-8 | 测试全绿、`report.py --check`、版本/sha/命令、固定 seed 一致、不越界 | **PASS** | 57 passed；`--check` 返回 0；dev 重跑逐字节一致；diff 不含 `src/`/`pom.xml`/`requirements-dev.txt` |
| AC-9 | 独立 Verifier 逐条重跑 | 待复验 | 证据绑定本次最终 HEAD + `git status --short` |

## 5. 判定

判定矩阵要求 AC-4 / AC-5 / AC-6 三项全 PASS 才算「v2 成立」。本次：

- **AC-5（核心门槛）在 dev 选参阶段就 FAIL**：合规 RRF 36 组 0 组满足分故障
  非回归；最差 delay −13.3pp。
- **AC-4 同样无合规证据**：最佳 RRF 仅 +2.7pp，低于 5pp。
- **AC-6 FAIL**：误报 3/50 = 6.0% > 5%。
- **AC-7 依赖上述不合规融合**，不能单独成立。

因此 **v2 不成立；不接产品面**。`weighted` 的 +22pp / +16pp 是真实的探索性结果，
但它不是契约允许的方法；把它写成「v2 通过」就是拿不合规配置冒充验收证据。

- 6.0% 与 5% 只差 1 个 case，但这是契约硬线，不做四舍五入，也不通过换阈值掩盖。
- 绑定约束是契约固定的 sustain=3；已提交的 dev 网格只有 sustain=3 的 20 组，
  本报告不引用未提交的 sustain=5 组合。
- RE1-SS 测试集已经用掉，不再用于任何调参或补救。

## 6. 发现、风险与后续

1. **SLI 臂无法跨系统迁移（最重要）。** 同一阈值在 RE1-OB dev 上 31/75 误报，
   触发列是 `cartservice_latency(-90)` 等正常流量尖峰（z 20–34）。正确方向是
   **每系统基线校准**
   （用系统自身的正常期分布定阈值），但必须因果化：试过的「整段前窗 q99」是
   用尖峰校准尖峰（泄漏），在线滚动版本又因窗口太短压不住尖峰。
2. **v1 的 `topo-first`/`static-threshold` 漏读 `latency-50/-90`。** RE1-OB 的列里
   确实存在 `latency-50/-90`，但 v1 只认 `*_latency` / `*_error`，不认分位列。
   AC-3 要求 v1 逐 case 复现，所以 v1 路径冻结为 `v1_metric_family`；v2 才用完整指标族
   （`test_v1_family_keeps_latency_quantiles_out_of_sli` 锁定了这个差异）。
   v1 的 delay 崩塌有多少来自这个解析缺陷、多少来自方法本身，本报告不做定量归因。
3. **实现与契约 §3.2 括注有偏差（已披露）。** 括注写「RE1-SS 用 latency-90 + error」，
   实现用的是完整 SLI 族（`latency-50` + `latency-90` + `error`）。
   在 dev 上把 SLI 收窄到 `latency-90 + error`：覆盖率 0.907→0.893（少 1 个 case）、
   误报仍 1/75、中位 17.5s→17.0s；测试集没有因此重跑或调参，报告数字对应的是**完整族**。
   这是一处已披露的偏差，不改变 v2 的 FAIL 判定。
4. **合并规则会「误报遮蔽真报」。** 合并取最早报警，一个前置误报会让同 case 的
   后续真报警失效（本次少 1 个检出）。是否改成「分臂独立判定 + 取最优」需要另立契约，
   否则会与 v1 的失败口径不可比。
5. **样本小。** 测试集 50、分故障 10 个/类；AC-4 的 CI 已经很宽（±13pp）。
   6.0% vs 5% 的差距在统计上是 1 个 case，但在验收上就是失败。
6. **前窗差异。** RE1-SS 全部 360s；RE1-OB 75 个 360s、50 个 2100s。
   报告已分列，不能把两套误报率直接横比。
7. **检测窗按注入点裁切**（v1 同口径）：裁切只决定可用基线长度，报警判定本身只用
   t 之前的滚动统计；跨 case 不做任何共享统计。
8. **契约内部冲突（已由 Lead 裁决）。** §2 允许「融合方式在 dev 上选」，§3.3 又规定
   两臂显著时必须 RRF 或等价秩融合。Lead 裁决「按草案冻结」，因此 §3.3 优先，
   `weighted` 不合规。这个冲突在实现前没有被识别，是本轮最需要记住的流程教训：
   契约里出现「可调项」与「必须项」并存时，必须在冻结前消歧。
9. 后续（若还要继续）：v3 先做合规秩融合（或先改 §3.3 并重新签收），同时修
   「每系统、因果的阈值校准」，在 **RE1-TT** 或新切分上验证；再谈产品接入。
   不要在 RE1-SS 测试集上重调。

## 7. 复现命令

```powershell
offline/rca/.venv/Scripts/python.exe offline/rca/download.py --suite re1-ob --verify
offline/rca/.venv/Scripts/python.exe offline/rca/download.py --suite re1-ss --verify
offline/rca/.venv/Scripts/python.exe offline/rca/audit.py --suite re1-ob
offline/rca/.venv/Scripts/python.exe offline/rca/audit.py --suite re1-ss
offline/rca/.venv/Scripts/python.exe -m pytest offline/rca/tests -q
offline/rca/.venv/Scripts/python.exe offline/rca/regression.py
offline/rca/.venv/Scripts/python.exe offline/rca/tune_detect.py --suite re1-ss --guard re1-ob
offline/rca/.venv/Scripts/python.exe offline/rca/tune_rank.py --suite re1-ss --guard re1-ob
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-ss --split test --methods all --seed 7
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --suite re1-ss --split test
offline/rca/.venv/Scripts/python.exe offline/rca/e2e.py --dir offline/rca/reports/20261126T101936Z_re1-ss_detection_test
offline/rca/.venv/Scripts/python.exe offline/rca/report.py --dir offline/rca/reports/20261126T101936Z_re1-ss_test --check
```

证据目录：

- 定位（test）：`reports/20261126T101936Z_re1-ss_test/`（`results.csv` / `summary.json`）
- 检测（test）：`reports/20261126T101936Z_re1-ss_detection_test/`（`detection.csv` /
  `detection_summary.json` / `e2e_paired.json`）
- 选参：`reports/V2-detect-sweep.json`、`reports/V2-rank-sweep.json`
- 定位 dev：`reports/20261126T101550Z_re1-ss_dev`、`reports/20261126T101622Z_re1-ob_dev`
- 检测 dev（修正 `counts_as_detection` 后重跑）：
  `reports/20261126T105636Z_re1-ss_detection_dev`、
  `reports/20261126T105649Z_re1-ob_detection_dev`
  （`20261126T101551Z` / `20261126T101601Z` 两个旧目录的聚合数用了旧口径，
  OB 会多算 1 个迟到报警；逐 case CSV 不受影响，聚合以新目录为准。）
- v1 护栏：`reports/20261125T201149Z_re1-ob_test/` 与 `reports/M2-report.md`（只读）

RRF 复核：`V2-rank-sweep.json` 的 `primary.configs` 里筛
`config.fusion == "rrf"`，共 36 组；按 `by_fault` 对 `baseline_3sigma` 逐类作差，
0 组满足「最差差值 ≥ −5pp」。最佳聚合 Top-1 0.8667。
