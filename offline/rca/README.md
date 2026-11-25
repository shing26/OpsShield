# RCA 研究切片（M0）

> 契约：`plans/rca-benchmark-accept.md`（FROZEN，2026-11-26）。
> 本目录是**离线研究工具**，不改产品运行时、不新增端点。

## 它解决什么

现有网关只做「知识检索 + 只读护栏」，看不见系统遥测。本切片补前半段：
在公开故障数据集上做**服务级根因定位**，用外部数据与基线证明方法有效，再决定是否接回产品面。

## 数据

- 来源：`RCAEval`（HuggingFace `phamquiluan/RCAEval`，MIT）。
- 当前：RE1-OB，125 cases = 5 服务 × 5 故障 × 5 重复；49 个指标、4201 个时间步。
- 候选集：遥测中出现的全部服务（含 `redis` / `main` / `frontend-external` 等干扰项），
  不是只在那 5 个注入服务里选。

## 环境

隔离环境，不碰全局 Python：

```bash
python -m venv offline/rca/.venv
offline/rca/.venv/Scripts/python.exe -m pip install -r offline/rca/requirements.txt
```

## 下载与校验

```bash
python offline/rca/download.py --suite re1-ob
python offline/rca/download.py --suite re1-ob --verify
```

产物落 `offline/rca/data/`（gitignored），`PROVENANCE.json` 记录逐文件 sha256。

## 跑评测

开发集（rep 1–3）：

```bash
python offline/rca/run.py --split dev --methods random --seed 7
```

测试集（rep 4–5）只在最终评估时跑：

```bash
python offline/rca/run.py --split test --methods all --seed 7
python offline/rca/detect.py --split test
```

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
M1：静态阈值 / 3σ / BARO 口径 robust-z 基线。
M2：`topo-first`——材料性下限 + P90 持续性 + 拓扑扣减 + 时相加权。
M3：全量 125 cases + 一次性测试集 + 报告（结论见 `reports/M2-report.md`）。

**一句话结论**：v1 在测试集上**没有超过 3σ**（Top-1 +2pp，p=1.0；Top-3 −6pp），
且 delay 故障从 100% 掉到 20%。因此**不接产品面**；下一版需按故障类型自适应，
并在新数据集（RE1-SS）上做外部验证。

## 边界

- 只做**检测与定位**，不做故障预测、RUL、性能调优。
- 服务级粒度，不到实例/代码行。
- 症状≠根因：指标里混有调用方 latency/error，M2 的拓扑扣减是核心机制。
