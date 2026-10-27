# Boot 4 迁移四条残余风险收口（2026-10-27）

> 来源：[live 验证台账](2026-10-27-boot4-live-verification.md) §7 的四条残余风险（issue [#54](https://github.com/shing26/OpsShield/issues/54)）。
> 本文逐条给结论与证据；代码/脚本改动随 PR 入库。

## 0. 结论

| # | 残余风险 | 处置 | 结果 |
|---|---|---|---|
| ① | 单节点无副本、未测故障切换 | 新增 `scripts/failover_drill.py`，对 ES/Qdrant/Redis 逐项停-观测-恢复 | **三组件全 PASS**（降级可见、无 5xx、恢复自动） |
| ② | 真实 DashScope 语义/延迟未重测 | live 存储 + 真实 embedding/rerank 重跑 evaluate/gate_matrix | **精确 100%、语义 vector/hybrid 88.24%、0 泄漏**；冷缓存 p50 389ms |
| ③ | Redisson 4.8 `password setting is deprecated` | `StoreConfig` 改 `Config.setPassword` | **启动告警 0 条**，连接/缓存功能不变 |
| ④ | benchmark 阈值只按 mock 校准 | 新增 `benchmark_thresholds_live.json` + 报告标注阈值来源 | **live 门禁 18/18 PASS** |

## ① 中间件单点故障演练

脚本：[`scripts/failover_drill.py`](../../scripts/failover_drill.py)。它不是"证明中间件不挂"，而是钉住**挂掉时的行为契约**：

- 组件健康必须变 DOWN、readiness 明确非 UP（不得假装健康）；
- 检索不得 5xx（允许降级为单腿）；Redis 故障不得**静默切 memory**（`storage` 仍须 `live`）；
- 网关进程不得退出；
- 容器恢复后健康与检索必须自动回满。

实测（live 栈，真实 DashScope；命令：`python scripts/failover_drill.py --base http://127.0.0.1:18082`）：

| 注入 | 故障期 /search | readiness | 恢复健康 | 恢复检索 | 判定 |
|---|---|---|---|---|---|
| 停 ES | **200，3 条结果**（走 Qdrant 单腿） | 503 DOWN | 35.0s | 35.3s | PASS |
| 停 Qdrant | **200，3 条结果**（走 ES 单腿） | 503 DOWN | 10.9s | 11.1s | PASS |
| 停 Redis | 200，3 条结果（搜索热路径不依赖 KV） | 503 DOWN | 0.4s | 0.6s | PASS |

补充观测：三次故障期间 `admin/health` 对应组件均为 `DOWN`、`storage` 始终 `live`（无静默回退），网关未退出。
**范围声明**：这验证的是「单节点故障时优雅降级」，不是高可用——多副本/跨节点切换不在本仓范围。

## ② 真实 DashScope 语义指标与延迟

环境：`STORAGE_MODE=live` + `DASHSCOPE_MODE=live`（text-embedding-v3 / gte-rerank-v2 / qwen-plus），448 chunks 经 `/admin/reingest` 灌入 ES/Qdrant。

| 模式 | 精确 Hit@1 | 精确 MRR | 语义 Hit@1 | 语义 Hit@3 | 语义 MRR |
|---|---|---|---|---|---|
| es_only | 100% | 1.000 | 70.59% | 91.18% | 0.794 |
| vector_only | 100% | 1.000 | **88.24%** | 100.00% | 0.936 |
| hybrid | 100% | 1.000 | **88.24%** | 100.00% | 0.936 |

- 越狱 5 条用例泄漏 **0**；
- 门控矩阵 @0.2：误拒 **8.8%**、漏拒 **8.3%**；
- 与 2026-10-24 入库的 live 证据（`offline/eval/reports/eval_report.json`）对照：vector/hybrid 语义 Hit@1 同为 88.24%，
  es_only 70.59% 亦一致——迁移未改变真实后端下的检索质量；
- **延迟**（/search hybrid，真实 embedding+rerank）：
  - 冷缓存（12 条新 query）：p50 **389ms**、p95 **443ms**、max 506ms；
  - 热缓存（golden 集 15 条，L1 命中）：p50 218ms、p95 572ms；
  - 远低于 `retrieval.leg-timeout-ms=4500` 的降级预算。

## ③ Redisson 4.8 弃用告警清理

`StoreConfig` 从 per-connection 的 `useSingleServer().setPassword(...)` 改为 `Config.setPassword(...)`（Redisson 4.8 推荐位置，语义等价）：

~~~java
Config cfg = new Config();
cfg.setPassword(password == null || password.isBlank() ? null : password);
cfg.useSingleServer().setAddress("redis://" + host + ":" + port);
~~~

验证：新 jar 启动日志 `password setting is deprecated` 计数 **0**；`storage=live：Redis 已连接`、24 条连接初始化正常；
真实后端下同一 query 连续两次 `/chat/stream`：`cache_hit=none, retrieval_ms=145` → `cache_hit=L1, retrieval_ms=0`。

## ④ live 阈值与门禁

新增 `offline/eval/benchmark_thresholds_live.json`（按真实后端实测校准）：

| 维度 | live 阈值 | 观测 | 余量 |
|---|---|---|---|
| 精确 Hit@1/MRR（三模式） | 1 / 1 | 1 / 1 | 0 |
| 语义 Hit@1 es_only | ≥ 0.6 | 0.706 | +10.6pp |
| 语义 Hit@1 vector/hybrid | ≥ 0.8 | 0.882 | +8.2pp |
| 语义 MRR es_only / vector / hybrid | ≥ 0.7 / 0.88 / 0.88 | 0.794 / 0.936 / 0.936 | — |
| 门控误拒 / 漏拒 @0.2 | ≤ 0.15 / ≤ 0.15 | 0.088 / 0.083 | — |
| 准入 429 / ok / 5xx | ≥1 / ≥1 / =0 | 8 / 4 / 0 | — |

跑法：`python eval/benchmark.py --thresholds eval/benchmark_thresholds_live.json`（报告会写明 `thresholds_source`）。
实测 **18/18 判据 PASS**（mock 基线是 10 条；live 多出 8 条语义/门控判据）。

注：语义/门控判据只在真实 embedding 后端起判（`benchmark.py` 按 backend 自动判断）；
用 memory 存储 + mock 词法腿跑 live 阈值必然 es_only 语义不过——这是**口径保护**，不是缺陷。

## 环境说明（诚实记录）

与上一轮相同：本机内存紧张，验证期间 ES 堆降到 256m、临时暂停同机 `shoppilot-demo-*` 栈；结束后 demo 栈与
opspilot alerting profile 已恢复，opspilot 中间件停止。数字口径为「单机、低堆、无并发背景负载」。

## 复现命令

~~~bash
# 中间件
docker compose up -d redis qdrant elasticsearch
# live + 真实 DashScope
STORAGE_MODE=live DASHSCOPE_MODE=live SERVER_PORT=18082 SERVER_ADDRESS=127.0.0.1 \
ES_HOST=127.0.0.1 QDRANT_HOST=127.0.0.1 REDIS_HOST=127.0.0.1 REDIS_PORT=6379 \
java -Xms256m -Xmx512m -jar target/opspilot-gateway-1.0.0.jar

# 灌数后跑评测（cwd=offline）
python eval/evaluate.py
python eval/gate_matrix.py
python eval/benchmark.py --thresholds eval/benchmark_thresholds_live.json

# 故障切换演练（cwd=仓库根）
python scripts/failover_drill.py --base http://127.0.0.1:18082
~~~

---

原始产物（演练 JSON、live benchmark、报告副本、延迟样本）保留在 gitignored 的 `.workbuddy/audit/live-verify/`。
