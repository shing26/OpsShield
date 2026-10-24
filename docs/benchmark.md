# OpsShield 评测基准（公开版）

> 一键复现：`bash scripts/benchmark.sh`（起 memory/mock 活体栈，零真金成本）。
> 本页数字由 CI 门禁与产物对账（doc_numbers）——漂移即红，不是一次性宣传数字。
> 完整机器可读产物：[benchmark.json](../offline/eval/reports/benchmark.json)。

## 为什么可以信

每个维度都有"必须发生/绝不发生"的机器判据，低于基线的 PR 在 CI 直接被拦：

| 维度 | 实测（mock 口径） | 门禁判据 |
|---|---|---|
| 检索·精确码 Hit@1（es_only） | 100% | =100%，跌破即回归 |
| 检索·精确码 Hit@1（vector_only） | 100% | =100% |
| 检索·精确码 Hit@1（hybrid） | 100% | =100% |
| 安全·越狱高密级泄漏（5 条 prompt，L1 账号） | 0 条 | =0，泄 1 条即拦 |
| 削峰·12 并发准入 | 429 与 200 并存、5xx=0 | 必须出现 429（全过=削峰失效）且 5xx=0 |

完整数字（MRR/语义召回/门控混淆矩阵/逐判据明细）：
[benchmark.json](../offline/eval/reports/benchmark.json) ｜ 人读版 [benchmark.md](../offline/eval/reports/benchmark.md)。

## 方法

- **检索质量**：golden 集（59 条，含期望文档真值）打 `/search`，3 种检索模式分别算
  HitRate@1/@3 与 MRR。精确码用例（34 条）验证"给错误码就有准确文档"的主张；
- **越权/越狱**：5 条诱导 prompt 以 L1 账号打检索面，断言引擎层密级过滤零泄漏——
  安全维度没有"可容忍退化"；
- **门控代价**：golden + 拒答集（24 条）在 `/search` 上离线算混淆矩阵，
  量化生产阈值 0.2 下的误拒/漏拒——门控有两类错误，方向相反，都要可见；
- **削峰**：12 并发**独立** query 打 `/chat/stream`（独立指纹才测得到准入而非合并），
  断言租户许可生效且无 5xx；
- **口径**：全部在 memory/mock 活体栈上跑——CI 可复现、零真金成本，阈值按 mock 校准；
  mock rerank 的分分布不区分域外 query，门控判据只在 live 口径判（数据保留在报告里）。

## 数据集（全部合成、已脱敏审计）

- **构成**：合成语料 `chunks.jsonl`（448 chunks）+ runbooks + golden 评测集（59 条）+
  拒答集（24 条）；全部为项目自造内容（虚构服务 order-service、虚构错误码 50012_DB_TIMEOUT 等）；
- **脱敏审计**：43 个文件扫描 0 敏感命中（邮箱/私钥块/口令赋值/公网 IP/长十六进制/JWT 形态/手机号）；
- **指纹**：[dataset_manifest.json](../offline/eval/dataset_manifest.json) 逐文件 sha256，可验篡改；
- **许可**：[CC BY 4.0](../offline/DATA-LICENSE)（数据）｜ MIT（代码）；
- 审计器：[`dataset_audit.py`](../offline/eval/dataset_audit.py)，任何语料变更后重跑即认证。

## 口径与边界（诚实声明）

- 语义检索指标依赖 embedding 后端：mock（词法哈希）与 live（DashScope）数字不同，
  报告里记录 backend 供核对；对外叙事的语义召回引用 **live 实测**（见 README），
  本基准的回归门禁只锁**后端无关**的判据（精确码检索/越权/准入）与按 mock 校准的阈值；
- 门控误拒/漏拒在 mock 口径不判（同上），live 数据在 benchmark.json；
- 全量降级演练与真实上游 429 在 fault_drill/长窗口压测，本基准不冒充。