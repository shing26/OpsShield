# RCA 研究切片（v1 / v2）

> 契约：v1 `plans/rca-benchmark-accept.md`；v2 `plans/rca-v2-accept.md`（均 FROZEN）。
> 本目录是**离线研究工具**，不改产品运行时、不新增端点。

## 它解决什么

现有网关只做「知识检索 + 只读护栏」，看不见系统遥测。本切片补前半段：
在公开故障数据集上做**服务级根因定位**，用外部数据与基线证明方法有效，再决定是否接回产品面。

## 数据

- 来源：`RCAEval`（HuggingFace `phamquiluan/RCAEval`，MIT）。主源不通时回落 `hf-mirror.com`，
  实际来源逐文件写进 provenance。
- **RE1-OB**（回归护栏）：125 cases = 5 服务 × 5 故障 × 5 重复；49–59 个指标。
  时长并非全是 70 分钟：49 个 4201 步 / 75 个 721 步 / 1 个 2679 步（1s 采样）。
- **RE1-SS**（外部验证，Sock Shop）：125 cases = carts/catalogue/orders/payment/user ×
  cpu/delay/disk/loss/mem × 5 重复；57–63 个指标，约 10–12 分钟/case（607–721 步）。
- 划分：rep 1–3 dev（各 75），rep 4–5 test（各 50）。**RE1-SS 测试集只跑一次**。
- 候选集：遥测中出现的全部服务（含 `redis` / `main` / `frontend-external` 等干扰项），
  不是只在那 5 个注入服务里选。
- 缺测：`*_error` 等列有部分 NaN（RE1-SS 25/125 cases、RE1-OB 11/125 cases）；
  v2 定位把缺测当「无证据」（记 0），v1 方法保留原 NaN 传播语义。

## 环境

隔离环境，不碰全局 Python：

```bash
python -m venv offline/rca/.venv
offline/rca/.venv/Scripts/python.exe -m pip install -r offline/rca/requirements.txt
```

## 下载与校验

```bash
python offline/rca/download.py --suite re1-ob
python offline/rca/download.py --suite re1-ss
python offline/rca/download.py --suite re1-ob --verify
python offline/rca/download.py --suite re1-ss --verify
```

产物落 `offline/rca/data/`（gitignored），每套写各自的 `PROVENANCE-<suite>.json`
（251 文件 = index + 125 × 2），记录逐文件 sha256 与实际来源。

## 数据审计

```bash
python offline/rca/audit.py --suite re1-ss
python offline/rca/audit.py --suite re1-ob
```

校验索引标签、`inject_time`、时间步与指标族覆盖（resource / sli 每 case 至少一列）。

## 跑评测

开发集（rep 1–3）：

```bash
python offline/rca/run.py --suite re1-ss --split dev --methods all --seed 7
```

测试集（rep 4–5）只在最终评估时跑：

```bash
python offline/rca/run.py --suite re1-ss --split test --methods all --seed 7
python offline/rca/detect.py --suite re1-ss --split test
```

快速冒烟（每服务 × 每故障 × rep1 = 25 cases）：加 `--reps 1`。

## 选参（只用 dev）

```bash
python offline/rca/tune_detect.py --suite re1-ss --guard re1-ob
python offline/rca/tune_rank.py --suite re1-ss --guard re1-ob
```

产物：`reports/V2-detect-sweep.json`、`reports/V2-rank-sweep.json`。
检测按「误报 ≤ 5% 且覆盖率 ≥ 80%」选阈值；定位按「任一故障类型不比 3σ 低 >5pp」选融合参数。

## v1 回归护栏

```bash
python offline/rca/regression.py
```

用 v1 六个方法重跑 RE1-OB 测试集，逐 case 对齐已提交的 v1 运行目录，并与
`reports/M2-report.md` 的表格字符串逐字比对。

输出：`offline/rca/reports/<timestamp>_<suite>_<split>/{results.csv,summary.json}`。

## 测试

```bash
python -m pytest offline/rca/tests -q
```

## 校验一次运行

```bash
python offline/rca/report.py --dir offline/rca/reports/<run> --check
```

它从逐 case 结果重算 Top-1/Top-3/MRR 与 CI，不信任 `summary.json` 自报。

## 当前状态

M0：下载 + 解析 + Top-1/Top-3/MRR + random 基线。
M1：静态阈值 / 3σ / BARO 口径 robust-z（IQR）/ 字面 MAD 基线。
M2：`topo-first`——材料性下限 + P90 持续性 + 拓扑扣减 + 时相加权。
M3：全量 125 cases + 一次性测试集 + 报告（结论见 `reports/M2-report.md`）。

**一句话结论**：v1 在测试集上**没有超过 3σ**（Top-1 +2pp，p=1.0；Top-3 −6pp），
且 delay 故障从 100% 掉到 20%。因此**不接产品面**；下一版需按故障类型自适应，
并在新数据集（RE1-SS）上做外部验证。

v2（`codex/rca-v2`）：双检测臂（resource + SLI）、`dual-arm` 自适应融合定位、
RE1-SS 外部验证；RE1-OB 只作回归护栏。dev 上：

- 检测（RE1-SS dev，阈值 20/8，sustain=3）：覆盖率 0.907、误报 1/75、中位 18s、P90 28s。
  同一组阈值在 RE1-OB dev 上 SLI 臂误报 31/75——**跨系统不可直接迁移**，报告必须分列。
- 定位（RE1-SS dev）：`dual-arm` Top-1 0.947 vs 3σ 0.840（+10.7pp，CI 下界 +2.7pp），
  五种故障均不低于 3σ；RE1-OB dev 上 0.893 vs 3σ 0.693。
- 测试集结论见 `reports/V2-report.md`（一次性）。

## 边界

- 只做**检测与定位**，不做故障预测、RUL、性能调优。
- 服务级粒度，不到实例/代码行。
- 症状≠根因：指标里混有调用方 latency/error，M2 的拓扑扣减是核心机制。
