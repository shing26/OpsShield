# R4：入口鲁棒性（B0）结果

> 契约：`plans/rca-entry-robustness-accept.md`（FROZEN 2026-11-27）
> 分支：`codex/rca-entry-robustness`；方法参数固定为 v3 冻结值
> 数据：RE1-SS dev 75 + RE1-OB dev 75；RE1-TT 本次未读取、未运行

## 结论（先说结果）

**AC-3 未通过：δ=120s 时两套 dev 都超过 5pp 阈值 → 按契约 §7，路线 B 作废，
转路线 A（v4 检测）。**

| dev | δ=0 | δ=120s | 下降 | 门槛 | 判定 |
| --- | ---: | ---: | ---: | --- | --- |
| RE1-SS | 0.960 | 0.747 | **−21.3pp** | ≤5pp | FAIL |
| RE1-OB | 0.893 | 0.840 | **−5.3pp** | ≤5pp | FAIL |

分故障条款同样不通过：SS 的 delay −40.0pp、loss −46.7pp、mem −13.3pp；
OB 的 loss −13.3pp（均超过 ≤10pp）。

## 1. 主曲线

| δ (s) | SS rrf | SS Δ | SS 3σ | SS miss | OB rrf | OB Δ | OB 3σ | OB miss |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 0 | 0.960 | — | 0.840 | 0 | 0.893 | — | 0.693 | 0 |
| 30 | 0.947 | −1.3 | 0.613 | 0 | 0.867 | −2.7 | 0.333 | 0 |
| 60 | 0.853 | −10.7 | 0.133 | 0 | 0.880 | −1.3 | 0.120 | 0 |
| 120 | 0.747 | **−21.3** | 0.040 | 0 | 0.840 | **−5.3** | 0.027 | 0 |
| 300 | 0.920 | −4.0 | 0.040 | 2 | 0.813 | −8.0 | 0.013 | 0 |

δ=300s 只作记录。SS 在 300s 回升而 60–120s 最低，是非单调的，不能读成
“迟滞越久越好”（见 §4）。

## 2. 分故障（δ=0 → δ=120s）

| fault | SS δ=0 | SS δ=120 | Δ | OB δ=0 | OB δ=120 | Δ |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| cpu | 1.000 | 0.933 | −6.7 | 0.933 | 0.933 | 0.0 |
| delay | 1.000 | 0.600 | **−40.0** | 1.000 | 0.933 | −6.7 |
| disk | 0.933 | 0.933 | 0.0 | 1.000 | 0.933 | −6.7 |
| loss | 0.867 | 0.400 | **−46.7** | 0.533 | 0.400 | **−13.3** |
| mem | 1.000 | 0.867 | **−13.3** | 1.000 | 1.000 | 0.0 |

延迟类与丢包类最先崩：它们的主要证据就在故障起始的几十秒内，入口后移直接把
证据切掉。CPU/内存类有持续性，掉得少。

## 3. 3σ 为什么塌得更快

3σ 在 δ=30s 就掉到 0.613/0.333，δ=120s 剩 0.040/0.027。机制是双向的：

- 基线窗口 `normal` 的定义是“入口之前的全部数据”，入口后移会让故障段进入基线，
  标准差被抬高，z 值整体缩小；
- 显著性门控按绝对阈值判断，z 缩小后不再触发，退回到弱信号排序。

`dual-arm-rrf` 只按秩融合、且权重来自门控用的 max 值，对“整体尺度缩小”更钝，
所以在高 δ 下反而比 3σ 稳（SS δ=300s：0.920 vs 0.040）。这不是路线 B 的胜利，
因为它的绝对下降已经超过门槛。

## 4. 本实验的固有混淆（写清，不用来翻案）

δ 移动的不只是“已知起点”，还移动了**基线边界**：`normal` 从 δ=0 的约 12 分钟
缩到 δ=300s 的约 5 分钟，且其中包含故障段。因此本实验测的是
“**朴素迟滞实现**（基线=入口前全部数据）”，不是“固定长度尾随基线 + 迟滞入口”。

这条限制由冻结设计本身决定，**不改变 AC-3 的 FAIL 判定**，也不能据此重算或重标
本次结果。若还要评估路线 B，必须以新契约、预先声明的固定基线长度重跑——
那是另一个问题，且不能在看到本次结果后再决定窗口参数。

## 5. 通过项

- **AC-1 δ=0 复现**：两套 dev 各 600 行 `(method, case)` 与 v3 dev 运行目录
  `rank` / `predicted` / `top1` / `top3` **0 处不一致**；聚合值 SS 0.960 /
  OB 0.893 精确复现。
- **AC-2 扫描完整**：10 格全部产出，每格 n=75；SS δ=300s 有 2 个 miss，
  按“故障窗时间跨度 <60s”计入分母、未跳过。
- **AC-5 无越界**：TT 未读取；δ 扫描结果未回写任何方法参数；
  `methods.py` / `detect.py` 未修改。
- **AC-6 可复现**：`pytest offline/rca/tests -q` 67 passed / 0 skipped；
  10 个运行目录 `report.py --check` 全部 OK；固定 seed 重跑一致。

## 6. 环境与证据

- Python 3.11.9 / numpy 2.4.6 / pandas 2.3.3 / pyarrow 21.0.0 / pytest 9.2.0
- 运行目录（SS）：`20261126T190821Z_re1-ss_dev_entry0s` … `…190952Z_re1-ss_dev_entry300s`
- 运行目录（OB）：`20261126T191142Z_re1-ob_dev_entry0s` … `…191336Z_re1-ob_dev_entry300s`
- 汇总：`20261126T191020Z_entry-sweep_re1-ss_dev.json`、
  `20261126T191306Z_entry-sweep_re1-ob_dev.json`、
  `20261126T191353Z_entry-sweep_re1-ob_dev.json`（后两份分别覆盖 30/60 与 120/300）

## 7. 复现命令

```powershell
$env:OPENBLAS_NUM_THREADS='1'
offline/rca/.venv/Scripts/python.exe -m pytest offline/rca/tests -q
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-ss --split dev --methods all --seed 7 --entry-offset 0 30 60 120 300
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-ob --split dev --methods all --seed 7 --entry-offset 0
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-ob --split dev --methods all --seed 7 --entry-offset 30 60
offline/rca/.venv/Scripts/python.exe offline/rca/run.py --suite re1-ob --split dev --methods all --seed 7 --entry-offset 120 300
offline/rca/.venv/Scripts/python.exe offline/rca/report.py --dir <run_dir> --check
```

（OB 拆成三次是内存约束下的等价执行；一次跑完五档在内存充足时同样有效。）

## 8. 证据绑定

- 验收对象 HEAD：`ff53d47a61d3658e25416b4ff600fd757377605a`
- 该 HEAD 上 `git status --short`：**空**（独立 Verifier 复验开始与结束时各取一次，
  两次均为空）
- 本节于该 HEAD 之后补录：此前版本漏写这两项，触发 AC-6 FAIL；补录只加本节，
  实现、测试与全部运行产物未改（后续提交仅文档）。

TT 未读取的自检（在仓库根执行，输出即证据）：

```powershell
offline/rca/.venv/Scripts/python.exe -c "import glob,os,pandas as pd; ds=sorted(glob.glob('offline/rca/reports/*_entry*s')); rows=0; tt=0; cs=set()
for d in ds:
    f=pd.read_csv(os.path.join(d,'results.csv')); rows+=len(f); cs|=set(f['case'])
    tt+=int(f['case'].astype(str).str.contains('re1tt').sum())+int(f['truth'].astype(str).str.startswith('ts-').sum())
print(len(ds), rows, len(cs), tt)"
```

本次输出：`10 6000 150 0`——10 个运行目录、6000 行、150 个唯一 case
（SS 75 + OB 75）、TT 命中 0。
