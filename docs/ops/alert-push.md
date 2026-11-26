# 告警/事故外发运维手册（ADR-0045）

本文只讲**外发到 IM 频道**：怎么配、怎么验签、怎么排查死信、怎么手动重试。
站内通知（铃铛）不受本文配置影响，始终开启。

## 1. 最小配置（generic）

```yaml
opspilot:
  incident:
    notify:
      created-min-priority: P2          # 建单外发下限；P1/P2 建单即入队
      ui-base-url: https://ops.example.com   # 空=载荷不带深链
      quiet-hours: 22:00-08:00          # 全局静默期；P1 不受限
      timezone: Asia/Shanghai
      channels:
        oncall:
          format: generic
          url: ${ONCALL_WEBHOOK_URL}
          secret: ${ONCALL_WEBHOOK_SECRET}   # 可选；空=不签名
          min-priority: P2
          kinds: created,assigned,sla_due_soon,sla_breached,reopened
          enabled: true
          rate-limit-per-second: 5
```

`kinds` 为空 = 全部事件；`min-priority` 默认 `P3`；频道 `quiet-hours` 为空时继承全局。
URL 为空表示该频道禁用。

旧配置仍然可用：`webhook-url` + `webhook-format` 会映射成一个名为 `default` 的通道。
**新旧同时配置会启动失败**——避免「到底发哪」不可解释。

### 飞书（feishu）

```yaml
      channels:
        feishu-oncall:
          format: feishu
          url: ${FEISHU_BOT_WEBHOOK}
          secret: ${FEISHU_SIGN_SECRET}
```

飞书载荷是标准文本消息：

```json
{"msg_type":"text","content":{"text":"[OpsPilot] 事故通知 · 新建\n事故：INC-... ..."}}
```

如果机器人开启了「签名校验」，把签名密钥填到 `secret`；OpsPilot 会发
`X-OpsPilot-Timestamp` / `X-OpsPilot-Signature`。若飞书侧要求它自己的
`timestamp + "\n" + secret` 签名算法，需要在网关/适配层转换——本切片只发
OpsPilot 的统一签名头。

## 2. 载荷与安全

`generic` 载荷：

```json
{
  "text": "[OpsPilot] 事故通知 · 新建\n事故：INC-1127-0042 支付网关 502\n优先级：P1\n负责人：t1-alice\n时限：...\n链接：https://ops.example.com/?inc=INC-1127-0042",
  "incidentId": "INC-1127-0042",
  "kind": "created",
  "priority": "P1",
  "recipient": "t1-alice",
  "url": "https://ops.example.com/?inc=INC-1127-0042",
  "deliveryId": 42
}
```

**不会出现**：query 原文、诊断正文、评论正文、引用片段、secret。

请求头：

- `Idempotency-Key: <event_key>:<channel_id>`（接收端应按它去重）
- `X-OpsPilot-Timestamp: <epoch seconds>`（仅配了 secret）
- `X-OpsPilot-Signature: sha256=<hex>`（仅配了 secret）

验签（Python 示例）：

```python
import hashlib, hmac

def verify(secret: bytes, timestamp: str, raw_body: bytes, header: str) -> bool:
    expected = "sha256=" + hmac.new(
        secret, timestamp.encode() + b"." + raw_body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, header)
```

时间戳建议允许 ±5min 偏差；超出即拒绝，防重放。

### 网络限制

- 非 loopback 必须 `https`；`http` 只允许 `localhost` / `127.0.0.0/8` / `::1`。
- 启动期拒绝 URL userinfo 与 fragment。
- 运行期禁止跟随重定向（3xx 视为失败进死信）。
- 可选 `allowed-hosts: alerts.example.com,feishu.example.com`；不命中启动失败，
  运行期再校验一次。

## 3. 投递语义

- 一个业务事件对同一频道只入一行；`created` 用 `c:<incidentId>`，
  站内通知用 `n:<dedupeKey>`（SLA/reopened 多收件人天然合并）。
- 单线程轮询，按 `outbox_id` 顺序；每频道默认 5 条/秒。
- 2xx → `sent`；429 → `Retry-After`；5xx/408/超时 → 指数退避；
  其他 4xx/非法 URL/频道不存在 → `dead`；最多 8 次。
- `sent`/`dead` 保留 90 天；`pending` 永不清理。进程重启后 pending 自动续投。

## 4. 排查死信

只读查询（平台管理员，`role=platform` 且 `auth_level>=3`）：

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8081/api/v1/admin/notifications/deliveries?tenant=tenant-1&status=dead&limit=50"
```

响应每行：

```json
{"id":42,"tenant":"tenant-1","incidentId":"INC-1127-0042","kind":"created",
 "priority":"P1","channel":"oncall","status":"dead","attempts":8,
 "lastStatus":404,"lastError":"HTTP 404","nextAttemptAt":"...","sentAt":null,
 "createdAt":"..."}
```

不含 URL/secret/载荷。`lastError` 已脱敏并截断 500。

手动重试（只允许 `dead`/`pending`；`sent` 返回 409；跨租户/不存在 404）：

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8081/api/v1/admin/notifications/deliveries/42/retry?tenant=tenant-1"
```

重试会把行重置为 `pending`、`attempts=0`、`next_attempt_at=now`，并写 admin 审计。

### 常见原因

| `lastStatus` / `lastError` | 含义 | 处理 |
| --- | --- | --- |
| `404` / `HTTP 404` | webhook 路径错或机器人被删 | 修 URL 后重试 |
| `401/403` | 机器人 token 失效 | 换 URL/secret 后重试 |
| `429; retry after Ns` | 上游限速 | 等自动重试；持续则降频或加频道 |
| `channel not configured or disabled` | 频道被删/禁用 | 恢复配置后重试，或让死信保留 |
| `host not allowed` | URL host 不在 allowlist | 改 URL 或 allowlist 后重试 |
| `HttpTimeoutException` | 上游慢/不可达 | 检查网络；确认 `request-timeout-ms` |

### 指标

```promql
sum by (channel,result) (rate(aiops_incident_push_total[5m]))
aiops_incident_push_pending
histogram_quantile(0.95, rate(aiops_incident_push_latency_seconds_bucket[5m]))
time() - aiops_incident_push_last_success_timestamp
```

`aiops.incident.notify.webhook{result}` 仍保留给 `default` 通道（旧面板兼容），
但不再产生 `dropped`。
