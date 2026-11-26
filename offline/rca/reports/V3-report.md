# RCA v3 结果（置信加权秩融合 + RE1-TT 第三系统外部验证）

> 契约：`plans/rca-v3-accept.md`（FROZEN 2026-11-27）
> 分支：`codex/rca-v3`；方法代码冻结：`23edeee`（TT 结果绑定本文件所在 commit 的 HEAD）
> 数据：RE1-SS/RE1-OB dev 选参；RE1-TT 125 cases 纯 holdout

## 结论（先说结果）

1. **v3 定位主门槛通过。** RE1-TT 125 cases 上 `dual-arm-rrf` Top-1 **0.744**，
   3σ **0.448**：**+29.6pp**，配对 95% CI **[+18.4, +40.0]pp**，McNemar
   p=1.24e-6。五类故障全部不低于 3σ，最差 mem **+16pp**。AC-4 / AC-5 PASS。
2. **合规秩融合不是性能最优。** 同一 TT 上 `dual-arm`（v2 的 weighted 分支，
   不合规）Top-1 **0.800**，比 `dual-arm-rrf` 高 **5.6pp**；秩融合是为了满足
   §3.3 的合规折中。SS dev 上则相反（rrf 0.960 > weighted 0.947），说明差距
   随系统变化，不能把任一方当成普适最优。
3. **检测零样本迁移失败。** v2 冻结阈值在 TT 上：覆盖 **30.4%（38/125）**、
   误报 **84/125 = 67.2%**、中位 17s、P90 31.3s。检测仍不可产品化；这是 v3 的
   诊断项，不是门槛。
4. **v3 不接产品面。** 定位泛化成立，但检测未解决；端到端仍不可用。

## 1. 数据与解析

### 1.1 RE1-TT 实测

| 项 | 值 |
| --- | --- |
| cases | 125（5 服务 × 5 故障 × 5 rep） |
| 真因服务 | `ts-auth-service` / `ts-order-service` / `ts-route-service` / `ts-train-service` / `ts-travel-service` |
| 指标列 | 198–238 |
| 候选服务 | **64**（每个 case 相同） |
| 时间步 | 721 / 848 / 961 |
| 时长 | 12.0 / 14.1 / 16.0 min |
| 注入前可用 | 360s / 480s |
| 缺测 | 120/125 cases 有 NaN（共 170,420 个单元格） |
| traces / logs / root-cause 文件 | 无 |

`to_service` 从旧的 `split("_")[0]` 改为 `rsplit("_", 1)[0]`：对 OB/SS 的
**14,170 个列名逐列等价**（只读校验），对 TT 把 `ts-auth-service_cpu` 正确折叠为
`ts-auth-service`，不产生 `"ts"` 桶。`audit.py` 对 125/125 通过：标签、
`inject_time`、时间步、指标列数、resource/SLI 族一致。

### 1.2 provenance 与环境

- 251 文件 / 125 cases；`download.py --suite re1-tt --verify` 返回 0。
- `cases.parquet` sha256 = `ec0008c90d0c97076bc98370ac824c3c5a49be95d00bd6d6044ea8061861d271`
  （与 OB/SS 同一份索引；本次复用本地文件，provenance 的 `source` 记为 `unknown`）。
- `PROVENANCE-re1-tt.json` sha256 = `2061086fe83cb39f0f2f5b1655ee6893dcb1648d2fdeec13d35686e4f4c98868`
- 250/251 文件逐文件 `source = hf-mirror.com`；主源 HuggingFace 当时
  `getaddrinfo failed`。
- 本机 Windows 注册表有一个失效代理（127.0.0.1:31180/31181）；下载时需
  `NO_PROXY=*`，否则 `urllib` 会 `WinError 10061`。`--verify` 不走网络。
- 环境：Python 3.11.9；numpy 2.4.6；pandas 2.3.3；pyarrow 21.0.0；pytest 9.2.0。

## 2. 方法（dev 选参，未看 TT）

### 2.1 置信加权秩融合（`dual-arm-rrf`）

两臂都显著时：

```
w_res = max(res) / (max(res) + max(sli))
w_sli = max(sli) / (max(res) + max(sli))
score(s) = w_res / (60 + rank_res(s)) + w_sli / (60 + rank_sli(s))
```

服务排序只由两臂的**秩**决定；`w_*` 是每臂一个标量，来自 §3.3 已用于显著性
门控的同一个 max-score 统计量。仅一臂显著时用该臂；都不显著时取
`max(res, sli)`；first-mover boost 与 v2 相同。TT 没有调用图，拓扑扣减禁用。

### 2.2 V3-1 选参与门槛

候选网格 36 组（`conf_rrf`；τ_res/τ_sli ∈ {3,5,8}²；discount ∈ {0.5,0.8}；
boost ∈ {0.3,0}），要求 **SS dev 与 OB dev 都**满足「任一故障 Top-1 相对 3σ
下降 ≤5pp」，再最大化两套平均 Top-1。

选中 **τ_res=3 / τ_sli=8 / discount=0.5 / boost=0.3**：

| dev | dual-arm-rrf | 3σ | 差值 | 最差分故障 |
| --- | ---: | ---: | ---: | ---: |
| RE1-SS（75） | 0.960 | 0.840 | +12.0pp | +0.0 |
| RE1-OB（75） | 0.893 | 0.693 | +20.0pp | +0.0 |

两套均 **36/36** 配置满足分故障约束。配对：

- SS dev：+12.0pp，CI [+4.0, +20.0]pp，p=0.0117；
- OB dev：+20.0pp，CI [+9.3, +30.7]pp，p=0.00073。

## 3. RE1-TT 一次性结果（125 cases）

### 3.1 定位（AC-4 / AC-5）

| method | Top-1 | Top-3 | MRR | mean rank |
| --- | ---: | ---: | ---: | ---: |
| **dual-arm-rrf** | **0.744** | 0.864 | 0.818 | 2.30 |
| dual-arm（weighted，不合规） | 0.800 | 0.944 | 0.876 | 1.51 |
| topo-first | 0.544 | 0.616 | 0.615 | 10.01 |
| 3sigma | 0.448 | 0.816 | 0.647 | 2.73 |
| baro | 0.152 | 0.472 | 0.372 | 5.14 |
| mad-z | 0.064 | 0.200 | 0.228 | 7.93 |
| static-threshold | 0.032 | 0.072 | 0.089 | 35.54 |
| random | 0.024 | 0.056 | 0.084 | 30.32 |

配对（逐 case，n=125）：

| 对比 | 差值 | 95% bootstrap CI | 独对/独错 | McNemar p |
| --- | ---: | --- | --- | ---: |
| dual-arm-rrf vs 3σ，Top-1 | +0.296 | [+0.184, +0.400] | 48 / 11 | 1.24e-6 |
| dual-arm-rrf vs 3σ，Top-3 | +0.048 | [−0.032, +0.128] | 17 / 11 | 0.345 |

分故障 Top-1：

| fault | dual-arm-rrf | 3σ | 差值 |
| --- | ---: | ---: | ---: |
| cpu | 0.960 | 0.360 | +0.600 |
| delay | 0.680 | 0.440 | +0.240 |
| disk | 0.560 | 0.320 | +0.240 |
| loss | 0.560 | 0.320 | +0.240 |
| mem | 0.960 | 0.800 | +0.160 |

**AC-4 PASS；AC-5 PASS（五类全部高于 3σ，最小 +16pp）。**

### 3.2 检测（AC-7，诊断，非门槛）

v2 冻结阈值（resource=20 / SLI=8 / sustain=3 / 材料性下限 8%），TT 上不做任何
调整：

| 臂 | 覆盖率 | 误报 case | 未报警 | 中位延迟 | P90 |
| --- | ---: | ---: | ---: | ---: | ---: |
| resource | 0.216 (27/125) | 10 | 85 | 19.0s | 210.0s |
| sli | 0.320 (40/125) | 79 | 5 | 17.0s | 35.6s |
| **合并** | **0.304 (38/125)** | **84 (67.2%)** | 2 | **17.0s** | **31.3s** |

另有 1 个 case 在 +403s 迟到报警（超过 300s horizon），记检测失败。
合并的 38 + 84 + 2 + 1 = 125；单臂计数允许同一 case 同时出现“检出”和“误报”。

**判读**：误报 67.2%，远高于 5%；64 个候选服务把「任一服务报警」的误报放大，
且 TT 的正常尖峰没有系统无关的判据。检测仍是 v4 的独立研究问题。

## 4. AC 判定表

| AC | 内容 | 结果 | 证据 |
| --- | --- | --- | --- |
| AC-1 | TT 251 文件 / 125 cases，独立 provenance，verify 0，标签一致 | **PASS** | `--verify` 0；`audit.py` 125/125 |
| AC-2 | 选参只用 SS/OB dev；TT 不参与调参；特征不读标签 | **PASS** | `V3-rank-sweep.json` 只读 dev；TT 在 V3-2 前未排名；61 tests |
| AC-3 | v1 六方法在 OB 测试集与 `M2-report.md` 一致 | **PASS** | `regression.py`：300 行 / 6 方法一致 |
| AC-4 | TT Top-1 ≥ 3σ + 5pp，配对 CI 下界 > 0 | **PASS** | 0.744 vs 0.448；+29.6pp，CI [+18.4, +40.0] |
| AC-5 | 任一分故障不比 3σ 低 >5pp | **PASS** | 五类 +16pp ~ +60pp |
| AC-6 | TT 125/125 解析正确，64 候选，无 `"ts"` 桶 | **PASS** | `audit.py`；`to_service` 14,170 列等价 |
| AC-7 | 检测诊断已报告（非门槛） | **PASS** | 覆盖 30.4% / 误报 67.2% / 中位 17s |
| AC-8 | 测试全绿、`report.py --check`、版本/sha/命令、不越界 | **PASS** | 61 passed；`--check` 0；diff 不含 `src/`/`pom.xml`/`requirements-dev.txt` |
| AC-9 | 独立 Verifier 逐条重跑 | 待复验 | 证据绑定最终 HEAD + `git status --short` |

## 5. 发现、限制与后续

1. **定位增益在第三个系统复现，而且更大。** TT 候选从 OB/SS 的 11–19 个涨到
   64 个，3σ 掉到 0.448；`dual-arm-rrf` 仍有 0.744。相对增益 +29.6pp 是 v1/v2/v3
   中最大的，但绝对 Top-1 低于 SS dev 的 0.960。
2. **秩融合 vs 分数加权没有普适赢家。** TT 上 weighted 0.800 > rrf 0.744；
   SS dev 上 rrf 0.960 > weighted 0.947；OB dev 两者 0.893 持平。v3 选 rrf 是
   合规要求，不是性能结论。
3. **检测零样本迁移失败。** 67.2% 误报不是阈值差一点，而是 OB/TT 的正常尖峰与
   故障 z 分布重叠；v2 的逐 case 分位校准与按系统 commissioning 原型也都失败
   （见契约附录 A）。检测需要新特征（跨服务一致性、持续时间、调用链上下文），
   不是再调一次阈值。
4. **TT 拓扑缺失。** 数据无 traces/logs/root-cause，也没有 Train Ticket 调用图；
   v3 在 TT 上禁用拓扑扣减。因此 v3 验证的是“双臂 + 秩融合”，不是拓扑先验。
5. **NaN 处理是必要条件。** 120/125 个 TT case 有缺测；`nan_aware=True` 把缺测
   当无证据（记 0），v1 的 NaN 传播会在 TT 上大面积失败。
6. **样本与 CI。** 125 cases、25 个/故障；Top-1 的 CI 仍有 ±10pp 量级，分故障
   CI 更宽。结论只用配对比较，不拿单点下结论。
7. **范围。** v3 只覆盖“检测起点已知时的定位”；端到端不可用。不得声称
   “故障预测 / 性能优化 / 检测已产品化”。
8. 后续（若继续）：v4 只做检测，先定义可证伪的跨系统判据（例如跨服务一致性 +
   持续时间），在 SS/OB dev 上过门槛后再碰 TT；不要在 TT 上调阈值。

## 6. 复现命令

```powershell
$env:NO_PROXY='*'   # 本机失效代理；仅下载需要
offline/rca/.venv/Scripts/python.exe offline/rca/download.py --suite re1-tt
offline/rca/.venv/Scripts/python.exe offline/rca/download.py --suite re1-tt --verify
offline/rca/.venv/Scripts/python.exe offline/rca/audit.py --suite re1-tt
offline/rca/.venv/Scripts/python.exe -m pytest offline/rca/tests -q
offline/rca/.venv/Scripts/python.exe offline/rca/regression.py
offline/rca/.venv/Scripts/python.exe offline/rca/tune_rank.py --suite re1-ss --guard re1-ob --fusion conf_rrf
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-tt --split all --methods all --seed 7
offline/rca/.venv/Scripts/python.exe offline/rca/detect.py --suite re1-tt --split all --arms both
offline/rca/.venv/Scripts/python.exe offline/rca/report.py --dir offline/rca/reports/20261126T171939Z_re1-tt_all --check
```

证据目录：

- 定位（TT）：`reports/20261126T171939Z_re1-tt_all/`（`results.csv` / `summary.json`）
- 检测（TT）：`reports/20261126T172101Z_re1-tt_detection_all/`
- 选参：`reports/V3-rank-sweep.json`
- dev：`reports/20261126T171403Z_re1-ss_dev`、`reports/20261126T171404Z_re1-ob_dev`
- v1 护栏：`reports/20261125T201149Z_re1-ob_test/` 与 `reports/M2-report.md`（只读）
