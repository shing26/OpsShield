# 并发-延迟曲线（拐点与首次降级档）

> 场景 `unique` ｜ 实测时间：2026-11-02T04:25:01+08:00 ｜ 主体 `sre-full` ｜ 每档 15s（ramp 5s） ｜ 在途降级阈值 40（L1 触发线） ｜ LLM 熔断失败阈值 3（L2 触发） ｜ 当日配额上限 300000
> 后端：embedding=dashscope:text-embedding-v3 / rerank=dashscope:gte-rerank-v2 / llm=dashscope:qwen-plus
> **首次降级档**：未触发（各档全程 L0）；各档在途峰值最高 4，对照 L1 触发线 40——**未触发的原因由此可读**

| 目标并发 | 实到并发 | 在途峰值 | RPS | P50 (ms) | P95 (ms) | P99 (ms) | 失败率 | 期间最高档 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 25 | 25 | 4 | 9.0 | 2100.0 | 2500.0 | 3400.0 | 66.19% | L0 |
| 100 | 100 | 4 | 39.4 | 2100.0 | 2300.0 | 2300.0 | 91.03% | L0 |
| 300 | 300 | 4 | 112.6 | 2100.0 | 2300.0 | 2300.0 | 96.92% | L0 |

**失败明细（区分连接层与应用层——前者指向容量极限，后者指向应用缺陷）**：

- u=25：`CatchResponseError('HTTP 429')` ×92
- u=100：`CatchResponseError('HTTP 429')` ×528
- u=300：`CatchResponseError('HTTP 429')` ×1636

后端必须 live：曲线形状依赖真实 embedding/rerank 延迟，mock 是词法代理、曲线偏平。降级档位取每档运行期间的**最高**档（轮询 1s，瞬时采样会漏脉冲）；manual_lock_seen=true 表示该档期间存在人工锁定，其档位不纯由负载导致。原始 locust 产物在 load/_sweep_raw/（不入库），本报告是唯一入库的汇总。**配额口径须一并读**：多数档合计请求数可能超出默认配额（5000/天/主体），故网关常以抬高的 OPSPILOT_QUOTA_DAILY_LIMIT 启动——本报告记录的 quota_limit 即当时真值；曲线测的是延迟/吞吐，不是配额护栏，配额耗尽后 locust 会把 429 记成失败使曲线失真，故抬高并披露。各档 failure_rate 即该档是否被 429 污染的判据。**实到并发必须看**：`-r` × 时长为并发上限，固定 ramp 速率会让高档位爬不到目标（曾出现「u=500 实到只有 290」，标签与事实不符）；本脚本按 `档位/ramp-seconds` 自适应 ramp 并逐档记录实到值。**失败要分性质**：`HTTP 0` 是连接层（容量/本地栈极限），`HTTP 5xx` 才是应用缺陷；且连接层失败可能**非单调**（本地临时端口耗尽等粘性资源会让相邻档出现「低档失败、高档反而干净」），故单看某一档的失败率不足以下结论。  **本报告是 `unique` 场景**：每请求唯一指纹（纯字母 nonce——数字/UUID 会被指纹归一化掩码掉，那样所有请求同指纹、被 Single-Flight 折成一个 leader，在途永远上不去），占空比≈1，故在途 ≈ 并发数，这才够得着 L1 触发线。判据不能只看本报告的档位：还须核审计行（`ev=degrade_transition` 的 cause、以及 chat 行的 `degrade_level` + `stage_ms.vector==0`）——`mode=es_only` 单独不作为降级证据（它有两个来源）。

复现：`cd offline && python load/sweep.py --levels 25,100,300 --duration 15s --scenario unique --report bottleneck_prod_admission`（需活体栈 + `.env` + live key；每档真实调用 LLM，有 token 成本）。
