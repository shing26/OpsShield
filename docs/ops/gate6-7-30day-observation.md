# 门禁 6/7 补证：30 天观察清单与周报/月报模板

> **用途**：把 [production-gate.md](production-gate.md) 里门禁 6（准入控制）与门禁 7（延迟分位与 SLO）的
> 「黄（部分）」状态，用 **30 天连续运行证据 + 固定周报/月报**推向「绿」。
> **不承诺日期**：这两条只能由真实流量与运行时长回答，代码侧已无待办（判据见 production-gate §2 第 6/7 行）。
>
> 读者：发布决定人、值班 SRE。执行周期：Day 0 准备 → 每日 5 分钟 → 每周一 15 分钟 → 第 30 天收口。

---

## 0. 完成判定（先写清"怎样算绿"）

| 门禁 | 现状 | 转绿需要 | 证据落点 |
|---|---|---|---|
| 6 准入控制 | 黄（部分）：单次 live 20 并发样本通过 | **≥ 20–30 条对照样本**（多并发档 × 加载/对照租户 × 跨周），且 5xx=0、对照租户降级档不亮、429 带 Retry-After | 周报 §准入对照 + `docs/ops/live-concurrency-*.json` |
| 7 延迟分位与 SLO | 黄（部分）：分位与抓取已接线，样本仅 ~24s | **连续 30 天抓取留存** + 一份固定格式**月报**（含 SLI 表与 SLO 判定） | 月报 `docs/ops/slo-monthly-YYYY-MM.md` + Prometheus 数据 |

**两条门禁都要求**：证据必须能指到「路径 + 时间点 + 可重跑命令」，不接受口头结论（production-gate §1.2）。

---

## 1. Day 0：一次性准备（半天）

- [ ] 部署形态确认：live 栈（Redis + ES + Qdrant + 真实 DashScope key），网关以 systemd/compose 常驻；
- [ ] Prometheus 抓取 `opspilot` 目标为 UP，且保留期 ≥ 30 天（默认 15d 需调到 30d 或接远端存储）；
- [ ] 告警规则加载：`ops/alerting/rules/opspilot.yml`（含 `opspilot:slo_*` 记录规则与 burn-rate 告警）；
- [ ] 备份计划任务/服务在跑（门禁 1 已绿，确认仍绿）；
- [ ] 基线快照：
  - `bash scripts/production_gate.sh --fast` 全绿；
  - `python scripts/check_upstream.py` 三路通过（LLM/embedding/rerank）；
  - 一次 `python scripts/live_concurrency.py --concurrency 20 --seconds 10` 基线样本；
- [ ] 建观察 issue（本仓用 GitHub Issues），把 §3 周报模板作为每周评论。

**Day 0 产物**：技术栈与版本、Prometheus 保留期截图/配置、基线样本 JSON、issue 链接。

---

## 2. 每日 5 分钟（建议 cron 09:30 或值班交接时）

| # | 检查 | 命令 / 读法 | 异常动作 |
|---|---|---|---|
| 1 | 进程与就绪 | `curl -fsS localhost:8081/actuator/health/readiness` → `status:UP` | 非 UP：按 runbook「网关不可达」5 分钟判据 |
| 2 | 降级档位 | `aiops_degradation_state`（0=L0,1=L1,2=L2）+ `aiops_inflight` | 常亮 L1/L2 超过 10 分钟：按 runbook「降级常亮」 |
| 3 | 错误面（24h） | `sum(increase(aiops_requests_failed_total[24h]))`；`sum(increase(aiops_admission_rejected_total[24h]))` | 5xx>0 需在周报登记事件；429 属受控背压 |
| 4 | 延迟分位（近 1h） | `histogram_quantile(0.95, sum(rate(aiops_ttft_seconds_bucket[1h])) by (le))`；请求耗时 `aiops_request_seconds` 同法 | TTFT P95 > 3s（SLO 线）记事件 |
| 5 | 备份龄 | `python scripts/check_backup_age.py`（或看 `OpsPilot-Backup` Last Result） | age > 26h 视为备份停摆（门禁 1 复核条件） |
| 6 | 告警送达 | 查收飞书/通知渠道最近 24h 是否有真实告警；无告警属正常，但要确认 `up{job="opspilot"}==1` | 抓取目标 DOWN 但面板绿 = 监控失效，按 runbook 人工验证业务面 |
| 7 | 审计链 | `python scripts/verify_audit_chain.py` | 验链失败：立即升级，禁止继续写审计 |
| 8 | 用量行 | `python scripts/daily_usage.py --date yesterday` 追加 `logs/usage.log` | 拒答率超阈值会以非 0 退出并告警 |

> 每日只做"看 + 记"，不在这一步做优化或改配置。任何动作都要留痕（周报"事件与处置"）。

---

## 3. 每周一 15 分钟（周报模板，复制到观察 issue）

### 3.1 本周证据采集（固定 3 组准入样本）

每组 = 一次加载压测 + 一次对照租户探测，共 6 条对照记录；4 周累计 ≥ 24 条（满足门禁 6 的 20–30 条）。

~~~bash
# 第 N 周：三个并发档（4 / 8 / 20），每档 10s；产出 docs/ops/live-concurrency-YYYY-MM-DD-cN.json
python scripts/live_concurrency.py --concurrency 4  --seconds 10 --out docs/ops/live-concurrency-YYYY-MM-DD-c4.json
python scripts/live_concurrency.py --concurrency 8  --seconds 10 --out docs/ops/live-concurrency-YYYY-MM-DD-c8.json
python scripts/live_concurrency.py --concurrency 20 --seconds 10 --out docs/ops/live-concurrency-YYYY-MM-DD-c20.json
~~~

同时导出本周 SLO 分位（Prometheus 查询后贴表）：

~~~promql
# 可用性（近 7d，5xx 口径）
sum(rate(aiops_requests_failed_total[7d])) / clamp_min(sum(rate(aiops_requests_total[7d])), 1e-9)
# TTFT P95/P99（近 7d）
histogram_quantile(0.95, sum(rate(aiops_ttft_seconds_bucket[7d])) by (le))
histogram_quantile(0.99, sum(rate(aiops_ttft_seconds_bucket[7d])) by (le))
# 准入拒绝与在途峰值
sum(increase(aiops_admission_rejected_total[7d]))
max_over_time(aiops_inflight[7d])
# 错误预算燃烧（记录规则）
opspilot:slo_app_failure_ratio:1h
~~~

### 3.2 周报模板

~~~markdown
## 门禁 6/7 观察周报 · Week N（YYYY-MM-DD ~ YYYY-MM-DD）

**窗口**：N 天；部署 commit：<sha>；后端：live(dashscope)；Prometheus 保留期：30d

| 指标 | 本周 | 累计（Day 0 起） | SLO/判据 | 结论 |
|---|---|---|---|---|
| 请求总数 | | | — | |
| 5xx / 连接错误 | | | =0 | |
| 429（准入拒绝） | | | 有背压、非故障 | |
| 可用性（剔除 429） | | | ≥ 99.5% | |
| TTFT P50 / P95 / P99 | | | P95 < 3s | |
| 请求耗时 P95 / P99 | | | 观测项 | |
| 降级档位时长（L1/L2） | | | 事件驱动 | |
| 缓存命中率（L1/L2） | | | 观测项 | |
| 错误预算燃烧（1h/6h） | | | 无持续 >阈值 | |
| 备份最近成功时间 / age | | | < 26h | |
| 告警送达（次数/场景） | | | 真实到人 | |
| 审计链校验 | | | PASS | |

### 准入对照（本周 3 组，逐组填）

| 组 | 并发 | 加载租户 ok/429/5xx | Retry-After 存在 | 对照租户 ok/429 | 对照租户降级档 | 判定 |
|---|---|---|---|---|---|---|
| c4  | 4 | | | | | |
| c8  | 8 | | | | | |
| c20 | 20 | | | | | |

判定规则：加载租户必须出现 429（否则削峰无效）；5xx 必须为 0；对照租户必须 200 且降级档为 L0；
`aiops_admission_rejected_total` 必须增长（与 429 数一致）；in-flight 不得超过全局 permits。

### 事件与处置（有则填，无则写"无"）

| 日期 | 症状 | 定层（入口/状态/上游/客户端） | 影响面 | 处置 | 留痕 |
|---|---|---|---|---|---|

### 本周结论

- 门禁 6 进度：__/24 条对照样本；未通过项：
- 门禁 7 进度：连续 __/30 天；月报素材是否齐：
- 下周动作（≤3 条）：
~~~

### 3.3 每周还要顺手做的两件事

- [ ] 检索质量抽检（语料未变时每周 1 次即可）：`cd offline && python eval/evaluate.py`，看 `exact.hit@1=1.0`、`jailbreak.leaked=0`；
- [ ] 上游三路健康：`python scripts/check_upstream.py`（欠费/429 会以非 0 退出，避免在已降级系统上核色）。

---

## 4. 第 30 天：月报与门禁复核

### 4.1 月报模板（固定格式，存 `docs/ops/slo-monthly-YYYY-MM.md`）

~~~markdown
# SLO 月报 · YYYY-MM

> 窗口：YYYY-MM-DD ~ YYYY-MM-DD（连续 N 天）
> 部署：<commit> / <jar sha256>；后端：live(dashscope)；数据源：Prometheus 30d + logs/audit.jsonl + 周报 N 份

## 1. 可用性与流量

| SLI | 30 天值 | SLO | 判定 |
|---|---|---|---|
| 请求总数 / 成功率（剔除 429） | | ≥ 99.5% | |
| 5xx / 连接错误 | | = 0 | |
| 429 比例 | | 受控背压 | |
| TTFT P50/P95/P99 | | P95 < 3s | |
| 请求耗时 P95/P99 | | 观测项 | |
| 降级期失败率 | | = 0 | |
| 权限泄漏（抽检） | | = 0 | |
| 幻觉率（抽检，可选） | | = 0 | |

## 2. 门禁 6 判定（准入）

- 对照样本：__ 条（≥ 20–30）→ 通过/不通过
- 5xx=0：是/否；对照租户降级档：L0（是/否）；429 均带 Retry-After：是/否
- 结论：**绿/黄/红** + 证据文件列表

## 3. 门禁 7 判定（SLO）

- 连续抓取天数：__/30；Prometheus 保留期：__d
- SLO 表逐条 → 测量装置 → 30 天实测（贴 §1 表）
- 月报是否按固定格式产出：是/否
- 结论：**绿/黄/红**

## 4. 事件复盘摘要（本月）

| 日期 | 事件 | 影响 | 根因 | 改进项 |
|---|---|---|---|---|

## 5. 结论与遗留

- 门禁 6：__；门禁 7：__
- 遗留风险（含单节点无 HA 的边界复述）：
~~~

### 4.2 写回门禁复核记录

在 `docs/ops/production-gate.md` §4 追加两行（**不覆盖旧行**，格式照现有行）：

~~~markdown
| YYYY-MM-DD | G1 | 6 | full | **绿**（或黄，列边界） | 30 天观察期内 24 条对照样本：加载租户 429+Retry-After、5xx=0、对照租户 L0；证据：月报 §2 + live-concurrency-*.json |
| YYYY-MM-DD | G1 | 7 | full | **绿**（或黄，列边界） | 连续 30 天抓取；月报 `docs/ops/slo-monthly-YYYY-MM.md`：可用性 x%、TTFT P95 y ms、0×5xx；证据：Prometheus 30d 保留 + 月报 |
~~~

> 注意：§4 的纪律是「每行只写单条门禁」，不接受一行覆盖多条。

---

## 5. 证据目录与命名

| 类型 | 路径 | 频率 |
|---|---|---|
| 准入样本 JSON | `docs/ops/live-concurrency-YYYY-MM-DD-cN.json` | 每周 3 个 |
| 周报 | 观察 issue 评论（模板见 §3.2） | 每周 1 次 |
| 月报 | `docs/ops/slo-monthly-YYYY-MM.md` | 第 30 天 |
| 每日用量 | `logs/usage.log`（gitignored） | 每日 |
| Prometheus 数据 | 保留期 30d（或远端存储） | 连续 |
| 门禁复核行 | `docs/ops/production-gate.md` §4 | 第 30 天 |

---

## 6. 失败处理（黄转红条件）

- 单周 5xx > 0 且未在 24h 内定位 → 本周记「红事件」，门禁 7 判定不得为绿；
- 备份龄 > 26h 或验链失败 → 门禁 1/9 复核立即重开，观察期暂停；
- 对照租户在加载期出现降级档 ≠ L0 → 门禁 6 判定不得为绿（这是"单人压垮全站"的直接证据）；
- 抓取目标 DOWN 但业务面正常 → 按"监控失效"处理（runbook §5.1），恢复前产出的自动数字不算证据。

---

## 7. 节奏日历

| 时间 | 动作 | 产物 |
|---|---|---|
| Day 0 | §1 准备 + 基线样本 + 建观察 issue | 基线 JSON / issue |
| 每日 | §2 八项检查 | usage.log / 异常记录 |
| 每周一 | §3 三组准入 + 周报评论 | 3×JSON + 周报 |
| Day 30 | §4 月报 + 门禁复核行 | `slo-monthly-YYYY-MM.md` + production-gate §4 两行 |

---

**与既有文档的关系**：门禁判据以 [production-gate.md](production-gate.md) 为准；值班动作以 [OPS.md](../../OPS.md) 为准；
本文件只提供"30 天怎么跑、证据放哪、周报/月报长什么样"。三者冲突时以 production-gate 为准。
