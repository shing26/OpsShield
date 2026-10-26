# OpsShield 评测基准报告

> commit 7d37e9f ｜ 生成于 2026-10-24T12:56:24+00:00 ｜ 后端 embedding=mock-lexical-hash（阈值按 mock 校准）
> 总判定：**PASS**（10/10 判据通过）

## 检索质量（golden 集 HitRate/MRR）

| 模式 | 精确 Hit@1 | 精确 MRR | 语义 Hit@1 | 语义 Hit@3 | 语义 MRR |
|---|---|---|---|---|---|
| es_only | 100.00% | 1.000 | 14.71% | 23.53% | 0.191 |
| vector_only | 100.00% | 1.000 | 64.71% | 88.24% | 0.750 |
| hybrid | 100.00% | 1.000 | 67.65% | 88.24% | 0.770 |

## 安全（越权/越狱）

越狱用例 5 条，高密级内容泄漏 **0** 条（引擎层过滤，泄漏必须恒 0）。

## 门控代价（threshold=0.2，生产默认）

误拒率 **0.00%**（应作答却拒）｜ 漏拒率 **100.00%**（应拒却作答）

## 削峰（准入探针）

burst=12：200x4，429x8（受控背压），5xx/连接错误x0

## 判据明细

| 判据 | 实测 | 基线 | 结果 |
|---|---|---|---|
| retrieval.es_only.exact_hit@1 | 1.0 | >= 1 | PASS |
| retrieval.es_only.exact_mrr | 1.0 | >= 1 | PASS |
| retrieval.vector_only.exact_hit@1 | 1.0 | >= 1 | PASS |
| retrieval.vector_only.exact_mrr | 1.0 | >= 1 | PASS |
| retrieval.hybrid.exact_hit@1 | 1.0 | >= 1 | PASS |
| retrieval.hybrid.exact_mrr | 1.0 | >= 1 | PASS |
| safety.jailbreak_leaked | 0 | == 0（硬 0） | PASS |
| admission.rejected_429 | 8 | >= 1 | PASS |
| admission.other_5xx | 0 | == 0 | PASS |
| admission.ok | 4 | >= 1 | PASS |

> 复现：bash scripts/benchmark.sh（起 memory/mock 栈 + 播种账号 + 全流程）。阈值基线见 offline/eval/benchmark_thresholds.json，按 mock 实测校准。
