# ES 出站连接池瓶颈收口（压测三段实证 + 一条设计级发现）

> 2026-11-02。回答 [concurrency_sweep.md](concurrency_sweep.md)（2026-09-25）留下的两个未解现象：
> RPS 平台 ~280 与「在途峰值恒 11、降级全程 L0 未触发」。
> 原始扫描产物：[bottleneck_before.md](bottleneck_before.md) / [bottleneck_after.md](bottleneck_after.md) /
> [bottleneck_prod_admission.md](bottleneck_prod_admission.md)（sweep.py --report 直出，未手工编辑）。

## 0. 两个先于一切的上游事实（读表前提）

1. **9/25 报告的 280 RPS 在当前形态不可复现**：该扫描早于 G1 准入控制（2026-10-22 上线，
   全局 32 / 单租户 4 permits）。现在单租户在途被钉在 4，429 是设计内的保护行为——
   「并发-延迟曲线」的斜率问题变成了「准入参数下允许多少」的问题，两者不可混读。
2. **unique（场景 C）live 跑撞供应商 QPM**：每请求真实调用 embedding/rerank/LLM，
   实测 ~8 RPS 就开始吃 DashScope 429（日配额未耗尽）。因此本轮把上游换成本地 stub
   （[dashscope_stub.py](../dashscope_stub.py)，延迟按 live 画像标定 80/150/500+流式），
   把「网关自身容量」与「供应商限额」分离；stub 向量为确定性哈希，语料以其重新 ingest。

## 1. 实证缺陷：ES 出站连接池吃库默认（per-route=10）

**现象**：准入放开（500/500）后，u=25 档在途峰值**恰好钉死在 10**——
`RestClient.builder()` 未设连接池，elasticsearch-rest-client 8.14.3 默认 total=30 / per-route=10，
单 ES 主机即单路由，10 个连接封顶检索段并发。

**修复**：`opspilot.es.pool-max-total / pool-max-per-route` 进配置（默认 100/100，
`EsConfig.applyHttpConfig` 显式设置），测试与生产同路径（`OutboundTimeoutTest` 三条新断言：
值流入生产连接池、配置驱动非硬编码、默认值不得退回 10）。

**效果（u=25 档，同协议同 stub）**：

| 池上限 | 在途峰值 | RPS | P50 | P99 | 失败率 |
| --- | --- | --- | --- | --- | --- |
| 10/10（库默认） | 10 | 7.9 | 2.3s | 3.8s | 0% |
| 100/100（新默认） | 16 | 8.7 | 2.3s | 3.1s | 0% |

在途 10→16、P99 -18%。**诚实口径**：RPS 仅 +10%——池修复解除了检索段并发上限，但
unique 冷路径还有别的串行环节（见 §3），这是"修掉一个真实缺陷"而不是"吞吐翻倍"的故事。

## 2. 降级状态机首次在负载下全程行使（准入放开形态）

| 档位 | u=25 | u=100 | u=300 |
| --- | --- | --- | --- |
| 在途峰值 | 16 | 41 | 22 |
| 期间最高降级档 | L0 | **L1** | **L2** |
| 失败率 | 0% | 0% | 0% |

inflight 阈值 40 触发 L1、LLM 熔断触发 L2，全程 0 失败——README「降级在真实负载下
行使过」在准入放开的观测形态下再次复现。

## 3. 设计级发现：生产准入参数下，inflight 触发线不可达

恢复生产准入（32/4）后：三档在途峰值恒为 4（单租户 permits），**429 拒绝 66%–97%**，
全程 L0——`inflight-threshold: 40` 在「单租户 4 permits」下**数学上不可达**（全局 32 也够不到），
即降级状态机的 inflight 触发线当前只剩 LLM 失败/429 路径可用。

这是参数间的交互矛盾，不是单点缺陷：准入按"不排队直接拒"保护进程，降级按"在途超阈"保护下游，
两者各自正确，合起来 L1-inflight 变成死代码。**未改行为**——改准入让路还是改触发线口径
（如按 permits 占用率触发）是产品决策，登记给 owner（OPS §5 待补一行）。

## 4. 债务实证与新登记

- **每 unique 请求 3 次 embedding**（stub 调用计数：3 embed + 1 rerank + 1 chat）：
  L2 读 + 混合检索 + L2 写——OPS §5「L2 写入重复 embed」实测坐实，另有一次写入期重复
  可一并省（约 160ms/请求）。
- **残余 ~1.1s 延迟缺口**：组件之和（3×80 embed + 150 rerank + 800 LLM + ES/Qdrant 毫秒级）
  ≈ 1.2s，端到端 P50 2.3s——缺口待归因（候选：h2c 升级往返、串行检索腿、SSE 帧调度），
  留给下一轮，不写入任何对外数字。

## 5. 复现

~~~bash
# stub（终端 A）
python offline/load/dashscope_stub.py
# 网关（终端 B；基线段加 ES_POOL_MAX_TOTAL=10 ES_POOL_MAX_PER_ROUTE=10 回放库默认）
DASHSCOPE_BASE_URL=http://127.0.0.1:8082 DASHSCOPE_API_KEY=stub DASHSCOPE_MODE=live \
OPSPILOT_QUOTA_DAILY_LIMIT=300000 [ADMISSION_GLOBAL_PERMITS=500 ADMISSION_TENANT_PERMITS=500] \
scripts/run.sh --opspilot.ingest=true
# 扫描（终端 C）
cd offline && python load/sweep.py --scenario unique --levels 25,100,300 --duration 15s \
  --report bottleneck_before   # / bottleneck_after / bottleneck_prod_admission
~~~

> 注：stub 向量语料与 live 向量语料不通用；切回 live 后须以 `--opspilot.ingest=true` 重新灌一次。
