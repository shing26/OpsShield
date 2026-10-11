# OpsShield

> **面向 AIOps 与 SRE 场景的生产级 LLM 韧性治理与流量防御网关** —— 告警风暴打不穿、模型挂了不雪崩、运维指令零幻觉。

[![CI](https://github.com/shing26/OpsShield/actions/workflows/ci.yml/badge.svg)](https://github.com/shing26/OpsShield/actions)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)]()
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green.svg)]()
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> **命名说明**：OpsShield 是对外项目名；`opspilot` 是内部工程代号（Java 包名 `com.opspilot`、配置前缀 `opspilot.*`、`/v1` 协议的 model 名）——展示名与命名空间解耦是开源基础设施的常见形态，改名不触碰任何代码与配置。

Java 21 虚拟线程 · ES 倒排 + Qdrant 向量双路召回（RRF 融合）· 双层缓存 · 三级自适应降级 · 多租户引擎层硬隔离 · 只读行动契约。

---

## 30 秒版本

大模型进生产排障，撞的是四堵墙，本网关各给一道防线：

| 生产痛点 | 防御机制 | 实测结果 |
| --- | --- | --- |
| **检索不精确**：通用向量检索丢错误码等精确符号，Top-1 不足 60% | 混合检索：ES 倒排 + 向量双路并行 + RRF 融合 + 精排，精确符号走快路径 | **精确码 Top-1 100%、语义 Top-3 100%**；hybrid 把语义 Top-1 **从 70.6% 拉到 88.2%**（68 样本 live） |
| **告警风暴**：一次集群故障掀起数千条同质告警，每条都触发 LLM | 堆栈指纹归一化 + 滑动窗口聚合计数 + 进程内 SingleFlight 合并 | **500 并发同指纹 → LLM 仅 1 次（削峰 99.8%）** |
| **模型依赖**：上游欠费/限流(429)/网络抖动，排障链路跟着雪崩 | 三级自适应降级状态机 + L1/L2 双层缓存 + 静态 Runbook 兜底，冷却后半开自愈 | **降级在真实负载下全链路行使过**（L0→L1→L2→L0 实录）；热点命中 TTFT **p99 16ms** |
| **指令幻觉**：模型编造高危运维命令，值班的人照着执行 | 行动契约从**本次引用的文档原文**逐字抽取（零生成面），四层只读策略过滤 | **契约命令 100% 逐字可溯源**（41/41）；答案错误码接地率 **100%**（live） |

> 三行数字均为 DashScope live 实测（embedding v3 + rerank v2 + qwen-plus），口径与复现命令见 [证据](#证据与工程治理)。无 key 时自动切本地 mock 后端——同代码路径复现机制正确性，但不承诺本表数字。权限底线：越权与 Prompt 越狱**零泄漏**（引擎层数据过滤，5 条越狱用例 live 实测）。

<details>
<summary><b>核心指标全表</b>（逐项口径与读表须知——引用任何数字前先读这段）</summary>

| 维度 | 目标 | 实测 | 口径 |
| --- | --- | --- | --- |
| 精确符号 Top-1 命中率 | 100% | **100%** | hybrid，34 错误码用例（live） |
| 语义 Top-3 召回率 | >90% | **100%** | hybrid，34 口语化用例（live） |
| 热点命中 TP99 | <50ms | **16ms** | L1 回放 n=200 的**服务端 TTFT**（回放不触外部 API）；端到端 p99=46.3ms 含本机 HTTP 栈开销——产物 [offline/load/reports/l1_hit_latency.md](offline/load/reports/l1_hit_latency.md) |
| 风暴 LLM 触发次数 | 500→1 | **1** | 500 并发同指纹，llm_calls Δ=1（live） |
| 权限泄漏率 | 0 | **0** | 5 条越狱用例，引擎层过滤（live） |
| 场景 A 吞吐 | — | **88.4 req/s · 2492 请求 0 失败 · P50=16ms** | Locust 50 并发 30s（live，冷请求走真实 API；与随附 `locust_a_stats.csv` 一致） |

**读表三条口径**：① 以上均为 **DashScope live** 后端实测；mock 后端（key 留空自动切换，同代码路径）下**不承诺这些数字**。② 「场景 A 吞吐」与「风暴」**不是同一种负载**：前者是混合查询（80% 热 + 20% 冷），后者是**同指纹风暴**（全量去重、请求极快），且该行来自 2026-09-09 的运行（早于 P2 账号体系）——三者的数字不可互相推广。③ **live 独有的论证价值**：评测集显示语义查询 Top-1 `es_only` 仅 70.6% → hybrid 88.2%（语义 MRR 0.794→0.936）——样本越口语、纯词法腿越低，向量路的捞回越值钱；且扩语料/扩样时 `es_only` 腿漂移明显（当前 golden（68 样本，含口语极值与本机辖区系列）下 `es_only` 语义 Hit@3 91.2%、MRR 0.794，hybrid 逐位不变；历史扩语料曾让 `es_only` 语义 Hit@3 由 100% 降至 92%、MRR 0.813→0.767，hybrid 逐位不动）——**混合检索的稳健性正体现在这里**（E1 教训两次实测复现）。报告出处与生成时刻见 `offline/eval/reports/PROVENANCE.json`（内容摘要判据，CI 强制同代）。

</details>

## 核心架构

三个入口（SSE 流式 / OpenAI 兼容面 / 运维面板）共享同一条编排链路——这正是"两个协议面 + 一个面板"语义一致的原因：

```mermaid
flowchart TD
    subgraph ENTRY[三个入口 · 同一编排]
        SSE[SRE 排障客户端<br/>POST /api/v1/copilot/chat/stream · SSE]
        OAI[任意 OpenAI 客户端<br/>POST /v1/chat/completions]
        PANEL[Ops Console 面板<br/>只读 · /admin/state + 审计流]
    end
    SSE -->|JWT| AUTH
    OAI -->|JWT 即 API Key| AUTH
    AUTH[JwtAuthFilter · DB 单真相<br/>每请求查 H2：存在性/禁用/token_ver 吊销<br/>注入 tenant × auth_level × role] --> OC[ChatOrchestrator · 唯一编排]
    PANEL -->|platform 门禁| ADM[AdminController<br/>聚合快照 + 审计事件环]
    OC --> FP[指纹归一化 SHA-256<br/>掩时间戳/UUID · 保留错误码]
    FP --> SF{SingleFlight<br/>组键 tenant+fp+authLevel}
    SF -->|follower| RP[回放 leader 答案<br/>src_tenant 绊线 fail-closed]
    SF -->|leader| HY[混合检索 · 双路并行<br/>虚拟线程 + 超时隔离]
    HY --> ES[ES keyword Top-50<br/>tenant+level 硬过滤]
    HY --> QD[Qdrant 向量 Top-50<br/>tenant+level 硬过滤]
    ES --> RR[RRF k=60 融合]
    QD --> RR
    RR --> RK[Rerank<br/>精确符号快路径跳过]
    RK --> GT{置信度门控<br/>低于阈值或零召回}
    GT -->|拒答| NO[显式拒答 · 跳过 LLM]
    GT -->|通过| LLM[qwen-plus 流式生成<br/>句级防幻觉护栏]
    LLM --> CT[只读行动契约<br/>从引用原文逐字抽取]
    DG[三级降级状态机 L0/L1/L2] -.->|过载摘向量腿 / 熔断直出 Runbook| HY
    DG -.-> LLM
    RP --> OUT[SSE meta/delta/done 或 OpenAI chunk+[DONE]<br/>done 帧恒带 refs + 行动契约]
    NO --> OUT
    CT --> OUT
    OUT --> AUD[审计 logs/audit.jsonl<br/>谁/何租户/何密级/命中来源/分段耗时]
```

三级状态机：**L0 全链路 → L1 高负载（摘向量腿+Rerank）→ L2 熔断（静态 Runbook 直出，零 LLM）**，冷却到期半开自探。降级的代价如实登记：L1 期语义 Top-1 由 88.2% 退到 70.6%、置信度门控退为"零召回级"（[ADR-0012](docs/adr/0012-degradation-cost-semantics.md)）；它在真实负载下被行使过（在途峰值 137，`L0→L1（cause=inflight）→L2（cause=llm_failure）→L0`，产物 [l1_trigger.md](offline/load/reports/l1_trigger.md)）。知识库重建走 blue/green 别名原子切流（[ADR-0006](docs/adr/0006-blue-green-alias-reingest.md)）：448 chunks 重灌 22.3s（live，服务端日志口径）、切流期间探针零中断，失败保留旧库在线。

## 三道防线，展开一点

**① 流量削峰（Traffic Shaping）**——指纹把"同一故障的不同变体"归一（掩时间戳/UUID，保留错误码身份），SingleFlight 让同指纹并发只放行一个 leader、其余回放，权限三元组（tenant+fp+authLevel）保证回放永不跨租户。10 条告警变体并发 → LLM 仅 1 次（[ADR-0003](docs/adr/0003-single-instance-inprocess-single-flight.md)）。

**② 韧性降级（Resilience）**——降级不是 catch 块，是状态机的一等公民：每次档位切换落审计事件（from/to/cause），过载降纯 ES、LLM 连续失败熔断直出预热 Runbook、冷却到期半开自探（曾实测修复过"熔断永不自愈"死锁）。指标以 `aiops.*` 层级名暴露 Prometheus，降级档位是 0/1/2 序数 gauge（[ADR-0016](docs/adr/0016-prometheus-metrics-exposure.md)）。

**③ 只读行动契约（Action Contract）**——答案下方附"下一步该跑什么"的可复制命令清单，但命令**不是模型生成的**：从本次引用的文档原文逐字抽出，四层只读策略把关（工具白名单 / 子动词闭集 / 外壳负载递归校验 / 旗标与重定向），认不出的形态判拒并计数。契约 ⊆ 引用是构造保证——**零幻觉是可静态判定的，不是承诺**（[ADR-0017](docs/adr/0017-read-only-action-contract.md)）。判据本身经变异测试（mutation testing）验证过极性：改坏必红、复原必绿；线上行为由一套只读契约验证套件（contract verification suite）在真实流量上逐条复核，验证工具自身零写操作、连管理端点都不打。

**贯穿三道防线的两条底线**：权限是引擎层数据过滤不是提示词约定（越狱语料实测零泄漏，[ADR-0008](docs/adr/0008-permission-dimensions-on-every-shared-path.md)）；对目标系统零写操作、不设执行回路——它是 agent 的**只读可信底座**，不是第 4 个 agent（[ADR-0013](docs/adr/0013-trust-base-for-agents-not-an-agent.md)）。

> 更完整的自我定位——"这算 AIOps 还是只是 RAG？"（Gartner 五域三分类自证）、与朴素 RAG 的五条机制级差异、给多步 agent 的调用契约——见 **[docs/positioning-aiops.md](docs/positioning-aiops.md)**。

## 技术栈

| 分层 | 选型 |
| --- | --- |
| 网关 | Java 21 + Spring Boot 3.3（Virtual Threads + SseEmitter） |
| 并发 | CompletableFuture + 虚拟线程执行器（双路并行、超时隔离） |
| 缓存/防线 | Redisson + Redis 7（滑动窗口计数、L1 精确缓存、SOP 预热、配额） |
| 风暴收敛 | 进程内 SingleFlight（JDK 并发原语，无外部依赖；ADR-0003） |
| 检索 | Elasticsearch 8（keyword 倒排）+ Qdrant（gRPC 向量） |
| 融合 | 自研 RRF（k=60）+ DashScope gte-rerank-v2 |
| 推理 | DashScope qwen-plus（OpenAI 兼容 SSE） |
| 离线 ETL | Python 3.11（AST/标题树切分） |

## 快速开始（三条命令，零 key 零成本）

```bash
git clone https://github.com/shing26/OpsShield && cd OpsShield
bash scripts/quickstart.sh   # 生成随机凭据 .env（key 留空=mock）→ Docker 全栈 → seed 演示账号
bash scripts/demo.sh         # 预检全 PASS = 机制全链路就绪
```

- **SRE 统一控制台**（监控/审计/降级/契约一屏）：`http://localhost:8081/`
- **交互排障问答页**（贴堆栈 → 流式答案 + 行动契约）：`http://localhost:8081/troubleshoot.html`
- **Prometheus 指标**：`http://localhost:8081/actuator/prometheus`（`aiops.*` 计数器/比率/降级档位 gauge）

<details>
<summary><b>发一次请求（OpenAI 兼容面，API Key = login 换来的 JWT）</b></summary>

```bash
set -a && . ./.env && set +a
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H 'Content-Type: application/json' -d "{\"username\":\"sre-full\",\"password\":\"$DEMO_PASSWORD\"}" \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')

curl -N http://localhost:8081/v1/chat/completions -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"model":"opspilot","stream":true,"messages":[{"role":"user","content":"how to fix 50012_DB_TIMEOUT"}]}'
# → role 首帧 → content 增量帧 → 参考来源与只读行动契约注入 → finish_reason=stop → [DONE]
```

任何 OpenAI 标准客户端填 Base URL `http://localhost:8081/v1` + 上面的 TOKEN 即可直连。协议取舍（无状态单轮、溯源注入位置）见 [ADR-0007](docs/adr/0007-openai-compatible-surface-jwt-as-key.md)。

</details>

<details>
<summary><b>开发者路径</b>（宿主裸进程跑网关，需 JDK21 + Maven；与容器模式二选一，都占 8081）</summary>

```bash
cp .env.example .env   # DASHSCOPE_API_KEY（留空走 mock）、JWT_SECRET、DEMO_PASSWORD

docker compose up -d   # Redis + Qdrant + ES

cd offline && python3 -m venv .venv && .venv/bin/pip install pytest
.venv/bin/python chunkers/build_chunks.py && .venv/bin/python -m pytest tests/

mvn package -DskipTests && bash scripts/start_gateway.sh
bash scripts/seed_demo_users.sh

.venv/bin/python console_client.py "下单报 50012_DB_TIMEOUT 怎么排查"
.venv/bin/python console_client.py --storm --storm-n 500   # 告警风暴演示
```

（打包与启动分两步是硬要求：Windows 上 JVM 锁着 jar 时打包会产出截断 jar——顺序永远是停服 → 打包 → 起。）

</details>

## 证据与工程治理

- **测试面 229 单测（Java）+ 238 用例（Python）**，CI 五 job（java / panel-contract / provenance / python / shell）零 key 零中间件可跑。防线不只靠单测：关键判据全部做**变异测试**（改坏必红、复原必绿），文档数字由**一致性治理**门闩每次现算比对产物（`doc_numbers`——数字与产物分叉即 CI 红），评测报告与语料的同代性用内容摘要钉死（`provenance`，不用 checkout 会刷平的 mtime）。
- **实测产物**：[offline/eval/reports/](offline/eval/reports/)（评测、门控矩阵、真实输入探测）与 [offline/load/reports/](offline/load/reports/)（压测、延迟、缓存节省账）；报告自报 live/mock 后端与生成时刻（`PROVENANCE.json`）。核心指标的历次扩语料基线与漂移实录（`es_only` 腿敏感、hybrid 稳健）都在报告里。
- **红队与缺陷台账**：五轮黑盒测评的完整缺陷-修复-回归锁记录在 [docs/qa/](docs/qa/)——两处 P0（组键缺租户的跨租户回放、admin 门禁把密级当信任域）都是红队真实触发后修复的，非纸面推演。
- **告警源自举（dogfooding）**：把项目自己的运行史当告警源——容器状态翻转 → 告警走完整编排链 → 检索命中自己写的复盘（[ADR-0011](docs/adr/0011-self-bootstrapped-alert-source.md)），实测"造容器 → SIGKILL → 告警 → 命中 rb-102 止损段"闭环。
- **证据一键打包**：`python scripts/pack_evidence.py` 产出逐项注明出处的快照（每个数字来自哪个文件、什么命令、当前模式能否复核）。

<details>
<summary><b>评测与压测：复现命令</b>（需活体栈 + 活体 key；数字底稿即各自产物）</summary>

```bash
cd offline
PY=.venv/bin/python          # Windows venv 为 .venv/Scripts/python.exe；或直接 bash ../scripts/py.sh 探测
$PY eval/build_golden.py     # 68 样本（34 精确码 + 34 语义）
$PY eval/evaluate.py         # 3 模式对比 + 越狱 → eval/reports/（报告自报服务端 live/mock 后端真相）
$PY eval/gate_matrix.py      # 门控误拒/漏拒率 + 阈值扫描 → eval/reports/gate_matrix.md
$PY eval/observed_probe.py   # 真实输入探测（无真值口径）→ eval/reports/observed_probe.md
$PY load/sweep.py            # 并发-延迟曲线（六档）→ load/reports/concurrency_sweep.md
$PY load/cache_savings.py    # 缓存节省账 → load/reports/cache_savings.md
.venv/bin/locust -f load/locustfile.py --headless -u 50 -t 30s --html load/reports/locust_a.html
SCENARIO=storm .venv/bin/locust -f load/locustfile.py --headless -u 500 -t 20s --html load/reports/locust_b.html
$PY load/l1_latency.py       # L1 回放延迟（热点命中口径）→ load/reports/l1_hit_latency.md
```

各脚本的口径与已知边界写在**各自的产物里**（例：`gate_matrix` 走 `/search` 故不含缓存、`cache_savings` 的未命中组必须真生成而非拒答）——引用前先读产物口径段，那是它们的单一事实源。

</details>

## 仓库导航

| 你想… | 去哪 |
| --- | --- |
| 判断这个项目值不值得看 | 本文（读完即可） |
| 了解它的定位边界与差异化 | [docs/positioning-aiops.md](docs/positioning-aiops.md) |
| 起服务 / 跑演示 / 录屏 | [docs/DEMO.md](docs/DEMO.md) |
| 日常运维、脚本索引、**债务触发线** | [OPS.md](OPS.md)（脚本按"服务生命周期 / 门禁检查 / 故障注入与演示"分组索引） |
| 领域词汇 | [docs/CONTEXT.md](docs/CONTEXT.md) |
| 目录地图 / 逐包职责 / 维护者向内容 | [docs/repo-map.md](docs/repo-map.md) |
| 架构决策与否决理由 | [docs/adr/](docs/adr/)（17 篇，每篇含否决项与后果） |

## 许可

[MIT](LICENSE)。
