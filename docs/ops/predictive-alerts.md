# 统计基线预警：运维与使用说明

> 能力与决策见 [ADR-0030](../adr/0030-telemetry-consumption-and-baseline-alerting.md)。
> 本文只讲**怎么跑、看哪里、什么时候它会不灵**。

## 它是什么，不是什么

一句话：**读 Prometheus 里已经存在的时序，用中位数/MAD 判断"这阵子相对它自己的历史是否异常"，
异常时把判断当成一条告警打进既有编排链。**

| 是 | 不是 |
| --- | --- |
| 消费侧能力：只读 `/api/v1/query_range` | 采集平台：不新增 scrape 配置、不建指标库、不采集拓扑 |
| 可解释判定：每条预警自带可手算复核的数字 | 黑盒模型：无 ML 推理、无外部 LLM 调用 |
| 事前的"偏离提示" | 预测：不外推趋势、不给未来值 |
| 把异常送进检索链 | 根因分析、自动处置 |

**它读什么**：`OPSPILOT_PROM`（默认 `http://127.0.0.1:9090`）上的 Prometheus HTTP API。
**它不读什么**：不读 `/api/v1/admin/state` 之外的业务面（只在非 dry-run 时读一次配额），
不读日志、不读拓扑、不读审计库。

## 判定式

对每条规则，取窗口内的一段序列：

```
基线窗口 = 最近 --lookback（不含最近 --recent-points 个点）
med      = median(窗口内样本)
MAD      = median(|x − med|)
current  = median(最近 --recent-points 个点)     # 抗单点抖动
z        = 0.6745 · (current − med) / MAD
delta    = current − med
```

告警条件（**双阈值**，缺一不发）：

```
|z| ≥ --z-threshold   且   |delta| ≥ 规则.min_abs_delta
且方向与规则配置一致（above 只认 delta > 0，below 只认 delta < 0，both 不设限）
```

**MAD = 0 的退化情形**（序列恒定或近恒定，z 无定义）：只按 `|delta| ≥ min_abs_delta` 判定，
并在台账与正文标 `method=degenerate_mad_zero`。不会除零，也不会静默放过。

样本不足（`< --min-samples`）→ 不判定，记 `insufficient_samples`。

## 默认规则集

| id | 读的序列 | 方向 | `min_abs_delta` |
| --- | --- | --- | --- |
| `ttft-p99` | `histogram_quantile(0.99, sum(rate(aiops_ttft_seconds_bucket[5m])) by (le))` | above | 0.5 |
| `request-p99-success` | `histogram_quantile(0.99, sum(rate(aiops_request_seconds_bucket{outcome="success"}[5m])) by (le))` | above | 0.5 |
| `cache-hit-rate` | `aiops_cache_hit_rate` | below | 0.10 |
| `inflight` | `aiops_inflight` | above | 2 |
| `sop-fallbacks-rate` | `sum(rate(aiops_sop_fallbacks_total[5m]))` | above | 0.01 |

计数器一律在 PromQL 里先 `rate()`——判定拿到的已是每秒速率，Python 侧不再除窗口。

自定义规则用 JSON 覆盖（**必须自带聚合到单条序列**，否则运行期会被拒并记 `rule_shape`）：

```json
[
  {"id": "es-only-rate", "expr": "sum(rate(aiops_es_only_requests_total[5m]))",
   "direction": "above", "min_abs_delta": 0.05,
   "service": "opspilot-gateway", "metric_cn": "ES 单路请求速率"}
]
```

```bash
python offline/predictive_alerts.py --rules my-rules.json --once --dry-run
```

## 怎么跑

```bash
# 只判定、只留痕，不发任何请求（也不读配额面，可完全离线）
python offline/predictive_alerts.py --once --dry-run

# 跑一轮，命中即发
python offline/predictive_alerts.py --once

# 常驻（默认 60s 一轮）
python offline/predictive_alerts.py

# 指到别的 Prometheus / 换告警主体
python offline/predictive_alerts.py --prometheus http://127.0.0.1:9090 --user sre-watcher
```

全部参数见 `python offline/predictive_alerts.py --help`。与判定相关的默认值：

| 参数 | 默认 | 语义 |
| --- | --- | --- |
| `--lookback` | `6h` | 基线窗口长度（支持 `90s` / `5m` / `6h`） |
| `--step` | `60` | `query_range` 步长（秒） |
| `--min-samples` | `20` | 基线有效样本下限 |
| `--z-threshold` | `3.5` | modified z 阈值（Iglewicz–Hoaglin 推荐值） |
| `--recent-points` | `3` | 当前值 = 最近 N 点的中位数 |
| `--consecutive` | `2` | 连续 N 轮命中才发（`--once` 恒按 1） |
| `--cooldown` | `1800` | 同 `规则:服务` 的冷却秒 |
| `--max-per-hour` | `6` | 每主体每小时最多发出条数 |
| `--quota-reserve` | `50` | 配额保留线 |

### 退出码

| 码 | 含义 |
| --- | --- |
| `0` | 正常（含"本轮无预警"与 dry-run） |
| `2` | 依赖不可达：Prometheus 全部查询失败，或非 dry-run 时读不到配额面（拒绝发送，fail-closed） |
| `3` | 配置错误：规则文件非法、`--lookback` 解析失败、参数越界、已有实例持锁 |

常驻模式下单轮失败不会退出进程（记 `[warn]` 后继续）；登录凭据错/主体被锁时退避重试，
连续 3 次后退出并提示人工处理（不会把 15 分钟锁定刷成常锁）。

## 闸门：它不会自己把配额烧穿

命中后走与 `alert_producer.py` 同一套 `AlertDelivery` 闸门，顺序即优先级：

1. **dry-run** —— 只判定与留痕；
2. **每小时上限**（`--max-per-hour`，进程内滚动 3600s）；
3. **配额保留线**（`--quota-reserve`，读 `/admin/state` 的 `runtime.quota`）；
4. **冷却**（键 = `规则id:服务`，`--cooldown` 秒）。

另有**单实例锁** `logs/.predictive-alerts.lock`（按 mtime 判活，`max(120, interval×4)` 秒后
可接管）：冷却、小时预算、连续计数都是进程内状态，跑两个实例 = 双倍告警速率。确认无实例后
可加 `--force` 接管。

## 台账：事后能回答"为什么没报"

`logs/predictive-alerts.jsonl`，追加写，每行一个 JSON：

- 每个**周期**一行汇总：`{"cycle": true, "rules": 5, "queried": …, "fired": …, "emitted": …}`；
- 每条**跳过**一行：`skip` 与 `skip_reason` 同值，便于两套词汇都能读到；
- 每条**发送**一行：`emit: true` + 判定数字（`current` / `median` / `mad` / `z` / `delta` /
  `method` / `baseline` / `n` / `lookback`）+ HTTP 结果与 `fp` / `cache_hit` / `refs`。

`skip_reason` 取值：`prometheus_unreachable`（查不到，或返回错误）、`rule_shape`（结果不是
单条 matrix——表达式没聚合）、`no_data`（该序列此刻不存在）、`insufficient_samples`、
`consecutive_pending`、`cooldown` / `hour_budget` / `quota_reserve` / `dry_run`（闸门）、
`quota_unavailable`、`login_failed`、`delivery_failed`。

> 台账含查询正文与服务名，对外分发前按 `OPS.md` §5 的本地证据口径处理。

## 已知边界（它什么时候不灵）

1. **Prometheus 不可达 → 它什么都不说**。fail-closed 是刻意的：宁可静默也不伪造读数。
   所以"它没报警"**不等于**"一切正常"——先看台账里有没有 `prometheus_unreachable`。
2. **基线是"自身历史"**。业务形态变化（流量台阶、大促、灰度）会让旧基线失真，表现为
   一段时间内的误报或漏报；冷启动（retention 内历史不足）则直接记 `insufficient_samples`。
3. **样本不足不判定**。窗口内有效样本 `< --min-samples` 时跳过，台账留痕。
4. **MAD = 0 时只有绝对差在起作用**。恒定序列的任何变化都靠 `min_abs_delta` 兜；
   阈值配得太小会变成"抖动即告警"。
5. **它不预测、不定位原因**。预警说"偏离了"，原因由检索链路给出、由人确认。
6. **窗口与步长要匹配**：`--lookback 6h --step 60` 约 360 点；把 `--step` 调大而
   `--min-samples` 不降，会长期停在 `insufficient_samples`。

## 与 `alert_producer.py` 的分工

| | `alert_producer.py`（ADR-0011） | `predictive_alerts.py`（ADR-0030） |
| --- | --- | --- |
| 数据源 | 网关自己的 `/admin/state`（健康、降级、计数器增量） | Prometheus 既有时序 |
| 判据 | 静态阈值 / 状态位（`status != UP`、降级 L1/L2…） | 相对自身历史的统计偏离 |
| 文案 | 带错误码（走精确符号快路径） | 不带错误码（纯语义路） |
| 触发时机 | 故障**已发生** | 偏离**已出现**、故障未必发生 |
| 共同点 | 都用 `AlertDelivery` 四道闸门、都发 `source=alert`、都写独立 JSONL 台账 | 同左 |

两者可以同时跑：它们是两个独立进程、两条独立台账、各有一把单实例锁。

## 换告警主体

默认复用既有的 `sre-watcher`（与 `alert_producer.py` 相同）。要换成独立主体：

1. 按 `OPS.md` §13 的账号流程建号并授予读 `/admin/state` 与调 `/chat/stream` 所需的最小角色；
2. `--user <新主体>`；
3. 让该主体的口令经环境变量注入（与既有脚本同一口径，**不要把口令写进文件**）。

建号是运维动作，不在本工具范围内。
