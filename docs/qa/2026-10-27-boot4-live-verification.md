# Boot 4 迁移后：live 中间件 + benchmark 对照验证（2026-10-27）

> 目的：Boot 4.1.1 迁移（[PR #51](https://github.com/shing26/OpsShield/pull/51)）的验证此前只覆盖 memory/mock 形态；
> 本文补上 **live 中间件（Redis + Elasticsearch + Qdrant）** 下的端到端与 benchmark 对照，
> 回答两个问题：① live 存储路径在 Boot 4 / Jackson 3 / Redisson 4.8 / Flyway 12 下是否正常；
> ② 与 memory/mock 基线相比，检索质量与准入行为是否发生回归。

## 0. 结论（先看结论）

| 判据 | memory/mock 基线 | live（ES+Qdrant+Redis） | 结论 |
|---|---|---|---|
| benchmark 门禁（10 项） | 10/10 PASS | **10/10 PASS** | 无回归 |
| 精确码 Hit@1 / Hit@3 / MRR（三模式） | 100% / 100% / 1.000 | **100% / 100% / 1.000** | 持平 |
| 安全：越狱高密级泄漏 | 0 | **0** | 持平（硬 0） |
| 准入 burst=12 | 4×200 / 8×429 / 0×5xx | **4×200 / 8×429 / 0×5xx** | 持平 |
| 语义 Hit@1（hybrid） | 67.65% | 64.71% | −2.9pp（未入门禁，见 §4） |
| 语义 Hit@1（es_only） | 14.71% | **70.59%** | 真实 ES 评分优于内存词法腿 |
| Redisson L1 缓存 | — | 第二次同问 `cache_hit=L1, retrieval_ms=0` | live 命中 |
| 搜索延迟（hybrid，n=30） | — | p50 **16.5ms** / p95 **30.7ms** | 远低于 4.5s leg 预算 |

## 1. 环境与口径

- 网关：Boot **4.1.1**，JDK 21，`STORAGE_MODE=live`，`-Xms256m -Xmx448m`；
- 中间件（docker compose，全部仅绑 127.0.0.1）：
  - Redis `redis:7-alpine`（`volatile-lru`，凭据来自 `.env`）；
  - Qdrant `qdrant/qdrant:v1.12.4`（API key 认证）；
  - Elasticsearch `8.14.3`（xpack 认证；**本次为省内存把堆降到 256m**，见 §6）；
- 模型后端保持 **mock**（`DASHSCOPE_MODE=mock`）：embedding/rerank/LLM 与基线同口径，
  这样差异只来自**存储路径**；真实 DashScope 的 live 证据见 `offline/eval/reports/PROVENANCE.json`；
- 语料：448 chunks，经 `POST /api/v1/admin/reingest` 灌入 ES（448 docs）与 Qdrant（448 pts）；
- 账号：`sre-full`(L3/platform)、`sre-limited`(L1)、`sre-acme`(tenant-acme L3) 由 `UserAdminCli` 播种；
- 基线：仓库内 `offline/eval/reports/benchmark.json`（CI 的 memory/mock 基准，Boot 4 迁移前后一致）。

## 2. 方法

```text
# 1) 起中间件（本机 compose；ES 堆经 override 压到 256m）
docker compose up -d redis qdrant
docker compose -f docker-compose.yml -f <es-smoke-override> up -d elasticsearch

# 2) 网关 live 模式（mock 模型，端口 18082）
STORAGE_MODE=live DASHSCOPE_MODE=mock SERVER_PORT=18082 ... java -jar target/opspilot-gateway-1.0.0.jar

# 3) 灌数 + 跑与 CI benchmark.sh 相同的评测脚本
curl -X POST /api/v1/admin/reingest
OPSPILOT_BASE=http://127.0.0.1:18082 python eval/evaluate.py
OPSPILOT_BASE=http://127.0.0.1:18082 python eval/gate_matrix.py
OPSPILOT_BASE=http://127.0.0.1:18082 python eval/benchmark.py --burst 12
```

评测产物先复制到临时目录，跑完 `git checkout -- offline/eval/reports` 还原入库的 live 证据（避免 mock 数字顶掉 live 证据）。

## 3. benchmark 对照（核心表）

| 维度 | 基线（memory/mock） | live（ES+Qdrant+Redis） | 判定 |
|---|---|---|---|
| 精确 Hit@1（es_only / vector_only / hybrid） | 100% ×3 | 100% ×3 | ✅ |
| 精确 Hit@3 | 100% ×3 | 100% ×3 | ✅ |
| 精确 MRR | 1.000 ×3 | 1.000 ×3 | ✅ |
| 语义 Hit@1 es_only | 14.71% | 70.59% | ↑（真实 ES 评分） |
| 语义 Hit@1 vector_only | 64.71% | 64.71% | = |
| 语义 Hit@1 hybrid | 67.65% | 64.71% | −2.94pp，未入门禁 |
| 语义 Hit@3 hybrid | 88.24% | 85.29% | −2.95pp，未入门禁 |
| 安全泄漏（5 条越狱） | 0 | 0 | ✅ |
| 准入 12 并发（ok/429/其它） | 4 / 8 / 0 | 4 / 8 / 0 | ✅ |
| benchmark verdicts | 10/10 | 10/10 | ✅ |

`benchmark.json` 中 `pass=true` 两侧一致；门禁只硬约束「精确码检索、泄漏=0、429 必须出现且 5xx=0」，这些全部满足。

## 4. 语义指标的解读（不掩盖差异）

- `es_only` 从 14.7% 升到 70.6%：内存词法腿只是哈希近似，真实 ES（BM25）本来就该更好；
- `hybrid` 降 2.9pp：RRF 融合里 ES 腿的排序变了，与内存腿的组合结果自然不同；该指标**不在** benchmark 门禁里
  （语义召回受 embedding 口径影响，mock 下不适合做硬阈值）；
- 真实 DashScope embedding 的 live 语义指标仍是既有 `eval_report.json`（未在本轮重跑，避免覆盖 live 证据）；
- `gate_matrix`（阈值 0.2）两侧均为误拒 0.0% / 漏拒 100.0%：mock rerank 分分布不区分域外 query，
  这是 `docs/benchmark.md` 早已写明的**已知边界**，不是本次迁移引入。

## 5. Redisson 4.8 / Flyway 12 / Jackson 3 的 live 证据

- **Redisson 4.8.0**：启动日志 `Redisson 4.8.0`、`24 connections initialized`、`storage=live：Redis 已连接`；
  同一 query 连续两次 `/chat/stream`：第一次 `cache_hit=none, retrieval_ms=17`，第二次 **`cache_hit=L1, retrieval_ms=0`**
  ——L1 缓存（Redisson 承载）在 live 下真实命中；
- **Redis 键空间**：73 个键、**全部带 TTL**（`expires=73`），前缀覆盖 `quota:`（当日配额）、`storm:`（风暴窗口）、`sop:`（SOP TTL），
  与 `volatile-lru` 策略要求一致；
- **Flyway 12**：H2 迁移 4 个版本全部 applied（V1–V4），日志 `Successfully applied 4 migrations`；
- **Jackson 3**：SSE 帧、审计 JSONL、`/admin/state` 解析全部正常（benchmark 脚本的 JSON 消费面即是证据）；
- 已知告警：Redisson 4.8 启动时打印 `password setting is deprecated. Use password setting in Config instead.`
  （`StoreConfig` 的旧式 password 设置；功能正常，列为后续清理项）。

## 6. 环境说明（诚实记录临时改动）

- 本机内存紧张（16GB，宿主常驻 Edge/ChatGPT/另一 demo 栈），为让 ES 起得来：
  - ES 堆从 compose 默认 512m 降到 **256m**（override 文件仅放在 gitignored 的 `.workbuddy/` 下）；
  - 验证期间**临时暂停**了同机的 `shoppilot-demo-*` 容器（另一项目的 demo 栈），验证结束后已全部重新启动；
  - 本仓 alerting profile（prometheus/alertmanager）暂停后已恢复；opspilot 中间件在验证结束后停止；
- 因此本轮数字是「单机、低堆、无并发背景负载」下的对照，不等于生产容量结论。

## 7. 残余风险

1. Qdrant/ES 均为单节点、无副本；本轮未测节点故障切换；
2. 本轮 embedding/rerank 仍是 mock，真实 DashScope 的语义质量与延迟未重测；
3. Redisson 4.8 的 password 设置 API 告警属清理项（不影响功能）；
4. benchmark 门禁阈值按 mock 校准，live 数字只用于对照，不替换门禁基线。

## 8. 复现命令（照抄可跑）

```bash
# 起中间件（内存足够时可省 override）
docker compose up -d redis qdrant elasticsearch
# live 网关
STORAGE_MODE=live DASHSCOPE_MODE=mock SERVER_PORT=18082 SERVER_ADDRESS=127.0.0.1 \
ES_HOST=127.0.0.1 QDRANT_HOST=127.0.0.1 REDIS_HOST=127.0.0.1 REDIS_PORT=6379 \
java -Xms256m -Xmx512m -jar target/opspilot-gateway-1.0.0.jar
# 灌数 + 评测
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18082/api/v1/admin/reingest
OPSPILOT_BASE=http://127.0.0.1:18082 python offline/eval/evaluate.py
OPSPILOT_BASE=http://127.0.0.1:18082 python offline/eval/gate_matrix.py
OPSPILOT_BASE=http://127.0.0.1:18082 python offline/eval/benchmark.py --burst 12
```

---

来源：Boot 4.1.1 迁移（#51）+ SCA 重基线（#52）后的补充验证；原始产物（live/baseline 报告、日志、延迟样本）
保留在 gitignored 的 `.workbuddy/audit/live-verify/`。