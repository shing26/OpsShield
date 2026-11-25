# RCA 薄切片结果（M2/M3）

> 契约：`plans/rca-benchmark-accept.md`（FROZEN 2026-11-26；R1 修订待签收）
> 数据：RCAEval RE1-OB，125 cases（5 服务 × 5 故障 × 5 重复），49–59 指标，4201 时间步，70 分钟
> 划分：rep 1–3 开发（75）/ rep 4–5 测试（50）；测试集只跑一次
> 代码：`codex/rca-benchmark`

## 结论（先说结果）

1. **作为一个通用定位器，v1 没有超过 3σ。** 测试集 Top-1 +2.0pp（p=1.00），Top-3 −6.0pp（p=0.51），MRR −0.007。差异都在噪声范围内。
2. **但它不是均匀地差。** 资源类故障（cpu/disk/mem）Top-1 +20~30pp；delay 故障从 100% 掉到 20%。聚合指标掩盖了这次崩塌——dev 上已有同样信号（delay 40% vs 100%），是选参准则只看聚合值导致的。
3. **它更适合作为"资源故障流水线"的后半段。** 配一个资源型在线检测器（测试集覆盖 60%、误报 2/50、中位延迟 14s），**端到端 Top-1 = 56%（漏检与误报都记失败）**；一旦正确检出，条件 Top-1 = 93.3%。同一检测器下 3σ 的端到端只有 24%。
4. **决策：不接产品面。** 下一版必须是「资源 + SLI 双检测臂 + 故障类型自适应定位」，并在**新系统**（RE1-SS）上做外部验证，不能再用 RE1-OB 测试集调参。

## 1. 实验设置与环境

- 候选集：**该 case 遥测中出现的全部服务**，随 case 变化——dev 13–19 个，test 13–14 个（含 `main` / `redis` / `frontend-external` / `PassthroughCluster` 等干扰项）。与 RCAEval 的服务折叠口径一致。这是比草案"5 候选"更严格的设定，见契约 R1。
- 指标：Top-1 / Top-3 / MRR + 95% bootstrap CI；随机基线的理论值随候选数变化（13 候选：Top-1 7.7%、Top-3 23.1%；14 候选：7.1% / 21.4%）。
- 环境：Python 3.11.9；numpy 2.4.6；pandas 2.3.3；pyarrow 21.0.0；pytest 9.2.0。
- 数据摘要：`cases.parquet` sha256 = `ec0008c90d0c97076bc98370ac824c3c5a49be95d00bd6d6044ea8061861d271`；`PROVENANCE.json` 覆盖 251 个文件（index + 125 × 2），逐文件 sha256；标签 125/125 与索引一致。
- 基线：
  - `random`：固定 seed 的确定性随机；
  - `static-threshold`：只看 SLI（error / latency）越过正常窗 p99；
  - `3sigma`：正常窗均值/标准差，故障窗取最大绝对偏离；
  - `baro`：复刻 RCAEval/BARO 口径——逐指标 robust-z（IQR），取窗口最大，再按服务折叠；
  - `mad-z`：契约字面要求的 robust-z（1.4826 × MAD），取窗口最大有符号 z。

## 2. Oracle-onset（注入点已知，全部 case）

### 开发集 75 cases

| method | Top-1 | Top-3 | MRR | mean rank |
| --- | ---: | ---: | ---: | ---: |
| topo-first | 0.787 | 0.867 | 0.840 | 1.88 |
| 3sigma | 0.693 | 0.893 | 0.809 | 1.65 |
| baro | 0.253 | 0.853 | 0.539 | 2.47 |
| static-threshold | 0.187 | 0.547 | 0.417 | 4.16 |
| mad-z | 0.107 | 0.720 | 0.411 | 3.08 |
| random | 0.080 | 0.200 | 0.220 | 7.95 |

### 测试集 50 cases（一次性）

| method | Top-1 | Top-3 | MRR | mean rank |
| --- | ---: | ---: | ---: | ---: |
| 3sigma | 0.680 | 0.920 | 0.803 | 1.64 |
| topo-first | 0.700 | 0.860 | 0.796 | 2.08 |
| static-threshold | 0.200 | 0.560 | 0.425 | 4.00 |
| baro | 0.160 | 0.860 | 0.487 | 2.54 |
| mad-z | 0.020 | 0.820 | 0.388 | 2.96 |
| random | 0.060 | 0.220 | 0.217 | 7.62 |

**mad-z 的崩塌本身是结果**：MAD 在许多指标上为 0（或极小），退化成 `scale=1` 后噪声列拿满分；BARO 的 IQR 更稳。契约要求的 MAD 基线在本数据上不可用，这一点必须写进后续方法选择。

### 配对比较（测试集，topo-first vs 3sigma）

| 指标 | 差值 | 95% bootstrap CI | 不一致对（topo 独对 / 3σ 独对） | McNemar 精确 p |
| --- | ---: | --- | ---: | ---: |
| Top-1 | +0.020 | [−0.180, +0.220] | 13 / 12 | 1.0000 |
| Top-3 | −0.060 | [−0.180, +0.060] | 3 / 6 | 0.5078 |

**判读：不能声称优于 3σ。** Top-1 的 +2pp 在 50 个样本上完全不可区分。

## 3. 分故障拆解（测试集 Top-1）

| fault | n | topo-first | 3sigma | 差值 |
| --- | ---: | ---: | ---: | ---: |
| cpu | 10 | 0.800 | 0.500 | +0.300 |
| mem | 10 | 1.000 | 0.800 | +0.200 |
| disk | 10 | 1.000 | 0.700 | +0.300 |
| loss | 10 | 0.500 | 0.400 | +0.100 |
| **delay** | 10 | **0.200** | **1.000** | **−0.800** |

dev 上同一模式已经出现：delay 0.400 vs 1.000（−0.600），cpu +0.400、mem +0.133、disk +0.333、loss +0.200。

**根因**：`topo-first` 的打分是 `resource + 0.25 × sli`，且只取**正偏离**、带 8% 材料性下限。delay 故障只抬高 latency，不抬高资源指标；真实服务的 latency 增幅一旦低于材料性下限，就会被另一个服务的资源小抖动压过去。这不是随机误差，是方法先验与故障类型的错配。

## 4. 消融（dev，固定 `pos_p90 / res_plus / floor=0.08`）

| 配置 | Top-1 | Top-3 | MRR |
| --- | ---: | ---: | ---: |
| 无拓扑、无时相 | 0.787 | 0.840 | 0.835 |
| 拓扑整体扣减 | 0.787 | 0.853 | 0.837 |
| 拓扑整体扣减 + 时相乘子 | 0.787 | 0.867 | 0.839 |
| 拓扑只扣 SLI + 时相乘子 | 0.787 | 0.853 | 0.836 |
| floor=0.05 | 0.760 | 0.867 | 0.826 |
| floor=0.10 | 0.787 | 0.853 | 0.838 |

**判读**：拓扑与时相各自只值 1~1.4pp Top-3，Top-1 不动。真正起作用的是「正偏离 + P90 持续性 + 材料性下限」；拓扑/时相在当前数据上不是主要贡献，但也没有害处。floor 在 0.05–0.10 是平台，不是单点过拟合。

## 5. 检测轨（AC-7）

在线模拟：只用注入点之前的 300s 滚动基线；资源指标正向 robust-z > 8 连续 3 点报警；注入前 10 分钟到注入后 10 分钟为观察窗。阈值与持续性在 dev 上按「误报率 ≤ 10% 前提下最大化检出」选出，测试集只跑一次。

| split | 检出率 | 误报 | 未报警 | 检测失败合计 | 延迟中位数 | 延迟 P90 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| dev (75) | 0.587 | 2 | 29 | 31 | 17s | 30.3s |
| test (50) | 0.600 | 2 | 18 | 20 | 14s | 34.1s |

**失败口径**：未报警与注入前误报都记失败；误报 case 不参与后续定位评分（记满盘皆输）。

测试集分故障检出率：cpu 100%、mem 90%、disk 80%、loss 30%、**delay 0%**。

**这是设计使然**：检测器只看资源指标，delay/loss 不改变资源指标。检测覆盖率与定位能力是耦合的——检出的 30 个 case 恰好是 topo-first 擅长的资源类。

| 口径（测试集） | topo-first | 3sigma |
| --- | ---: | ---: |
| Oracle 起点，全部 50 | 0.700 | 0.680 |
| 检测起点，全部 50（漏检/误报记错） | **0.560** | 0.240 |
| 检测起点，仅正确检出的 30 个 | **0.933** | 0.400 |
| 条件 MRR（仅正确检出） | 0.948 | 0.643 |

**判读**：端到端 56% 是这条流水线目前真实的水平——距注入 14 秒（中位）给出根因，六成 case 有输出。它还不能叫"通用定位"，但已经能叫"资源故障定位"。

## 6. 错误分析（测试集 topo-first，15 个 Top-1 错误）

- delay 10 个里错 8 个、loss 10 个里错 5 个、cpu 10 个里错 2 个。
- 按真值：`productcatalogservice` 错 5（判成 frontend 2 / cartservice 2 / main 1）；`currencyservice` 错 4（cartservice 2 / adservice 1 / paymentservice 1）；`cartservice` 错 3（frontend 2 / checkoutservice 1）；`adservice` 错 2；`checkoutservice` 错 1。
- `emailservice` 在 3σ 下是最大误报源：dev 23 个错误里 12 个判成它，测试集 16 个错误里 8 个。`topo-first` 的材料性下限把它压住了——这是 Top-1 提升的主要来源之一。
- dev 上 topo-first 共 16 个错误：delay 9、loss 6、cpu 1。

## 7. 决策

1. **不把 v1 接进网关**（M4 不启动）。理由是测试集上没有非回归证据，且 delay 类型明确退化。
2. **保留整条离线评测资产**：数据 provenance、5 个基线、逐 case 结果、检测轨、报告校验器。它们是下一版的对照组。
3. **v2 假设**（需另立契约）：
   - 双检测臂：资源臂（已有）+ SLI 臂（latency/error 的在线基线），覆盖 delay/loss；
   - 定位按故障类型自适应：资源异常显著时走 `topo-first`，否则走 SLI 主导评分；
   - 选参准则加入**分故障非回归约束**（任一故障类型 Top-1 下降 > 10pp 即淘汰），不再只看聚合 Top-1；
   - 在 **RE1-SS**（新系统）上做外部验证，RE1-OB 测试集不再用于选参。

## 8. 复现

```powershell
python -m venv offline/rca/.venv
offline/rca/.venv/Scripts/python.exe -m pip install -r offline/rca/requirements.txt
offline/rca/.venv/Scripts/python.exe offline/rca/download.py --suite re1-ob --verify
offline/rca/.venv/Scripts/python.exe -m pytest offline/rca/tests -q
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --split test --methods all --seed 7
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --split test
offline/rca/.venv/Scripts/python.exe offline/rca/report.py --dir <run_dir> --check
```

产物目录：`reports/20261125T201149Z_re1-ob_test/`、`reports/20261125T201219Z_re1-ob_detection_test/`。

## 9. 独立 Verifier 复核记录（2026-11-26）

首次验收被拒收，阻塞项与修复：

| 拒收项 | 修复 |
| --- | --- |
| AC-7：误报 case 被当成命中，`detected_top1_all` 抬高 2pp | 误报与未报警统一记失败；端到端从 0.580/0.260 修正为 **0.560/0.240**；新增 `detection_failures`（test=20） |
| AC-8：报告缺 Python 版本与数据 sha256 | §1 补齐 Python/依赖版本与 `cases.parquet` sha256 |
| 报告数字错误（§6 的 23/9、§4 的 floor=0.10、§5 的 58/26） | 逐项更正；数字改由本次重跑产物生成 |
| AC-5：契约字面要求 MAD，实际 BARO 用 IQR | 新增 `mad-z` 基线；结果证明 MAD 在本数据上退化（test Top-1 0.020），作为结论保留 |
| AC-9：无泄漏测试只覆盖 random | 新增 `test_leakage_methods.py`：全部方法 + 检测轨对 truth/fault/name 免疫 |
| AC-4 / AC-10：候选集与 repo-map 行数与冻结文本不一致 | 契约 R1 修订（候选集 13–19/13–14；repo-map 允许同步 ADR 计数），**待 Lead 签收** |
