# 0045. 告警/事故外发：持久化 outbox + 多通道 + 有界重试

- 状态：已实现（2026-11-27；契约 `plans/alert-push-accept.md` 已签收）
- 日期：2026-11-27
- 取代：ADR-0037 决策 4「单次尝试、不重试」与决策 6「不做失败重试」
- 不改：ADR-0034 的评论未读水位、ADR-0036 的单实例调度边界、ADR-0037 的站内通知语义

## 背景

ADR-0037 的群机器人外发是「事务提交后入有界队列，单线程单次尝试，3s 超时」。
它能保证请求线程不被外发拖住，但有三个不可接受的事实：

1. **会丢**：进程重启、队列满、上游 5xx/超时都直接丢失；没有任何持久记录。
2. **不可解释**：429 不读 `Retry-After`，4xx/5xx 不区分，失败后无法人工补发。
3. **只有一条全局 webhook**：不能按优先级/事件类型路由，也没有投递审计；
   建单本身不外发，P1 告警建单后 on-call 群收不到。

## 决策

### 1. 外发事实持久化到 `incident_push_outbox`

业务事务（事故 insert、站内通知 insert）**同事务**写 outbox 行；HTTP 只在独立
`@Scheduled` 线程发生。行内只存定位信息（事件、事故 ID、标题、优先级、时限、
负责人、频道），不存 query、诊断正文、评论正文、引用片段或 secret。

唯一键 `(tenant, event_key, channel_id)`：

- `created` → `event_key = "c:" + incidentId`；
- 站内通知 → `event_key = "n:" + dedupeKey`（SLA/reopened 的多收件人在同一频道自然合并）。

重复入队是 no-op。写入用 JDBC savepoint 隔离唯一键冲突：PostgreSQL 在唯一键
冲突后会中止整个事务，单纯 catch 异常会让后续语句全部失败。

### 2. 建单也要有明确事务

`createInternal` 用 `TransactionTemplate` 包住「事故 insert + 审计 + created outbox」。
并发 `clientId` 重放时，Template 先完整回滚，再在事务外独立回查既有事故；不会把
`DuplicateKeyException` 留在 rollback-only 事务里，也不会重复入 outbox。

### 3. 多通道路由

`opspilot.incident.notify.channels.<id>` 支持 `format`（`generic|feishu`）、`url`、
`secret`、`min-priority`、`kinds`、`quiet-hours`、`enabled`、`rate-limit-per-second`。
旧 `webhook-url` 映射为 `default`；新旧同时配置启动失败。URL 在启动期校验：
非 loopback 必须 https、禁 userinfo/fragment、可选 host allowlist；未知 format /
非法优先级 fail-fast。

路由顺序：`created-min-priority` → 频道 `min-priority` → 频道 `kinds` → 静默期。
静默期内非 P1 不建行，记 `result=suppressed, reason=quiet_hours`；P1 不受限。

### 4. 有界重试与死信

单线程轮询按 `outbox_id` 取到期 pending；发送前先用
`UPDATE ... WHERE outbox_id=? AND status='pending'` 认领（`attempts+1`）。

- 2xx → `sent`；
- 429 → 读 `Retry-After`（秒或 RFC1123 HTTP-date，封顶 5min）；
- 5xx / 408 / 连接或读取超时 → 指数退避 `1s × 2^(n-1)`（±20% jitter，封顶 5min）；
- 其他 4xx / 非法 URL / 频道被删 → `dead`；
- `attempts >= max-attempts`（默认 8）→ `dead`。

请求线程永不发 HTTP；进程重启后 pending 行继续投递。`sent`/`dead` 默认保留 90 天，
每日清理单次上限 5000；pending 永不清理。单实例假设沿用 ADR-0036，不引入分布式锁。

### 5. 安全边界

- `HttpClient` 禁止重定向；非 loopback http 启动期拒绝；可选 `allowed-hosts`。
- 请求头 `Idempotency-Key: <event_key>:<channel_id>`。
- 可选 HMAC-SHA256：`X-OpsPilot-Timestamp` + `X-OpsPilot-Signature: sha256=<hex>`，
  签名原文 `timestamp + "." + rawBody`。secret 只从环境变量来，不入库。
- 日志与 `last_error` 统一脱敏 URL userinfo/query 与 secret，截断 500。

### 6. 运维面与指标

平台管理员可只读查询投递（`GET /api/v1/admin/notifications/deliveries?tenant=`）、
手动重试 `dead`/`pending`（`POST .../{id}/retry?tenant=`，写审计）。响应不含
URL/secret/载荷；sent→409，跨租户/不存在→404。

新增指标：

- `aiops_incident_push_total{channel,result}`（`ok|retry|dead|suppressed`）
- `aiops_incident_push_pending{channel}`
- `aiops_incident_push_latency_seconds{channel}`
- `aiops_incident_push_last_success_timestamp{channel}`

旧 `aiops.incident.notify.webhook{result}` 保留，仅对 `default` 通道映射
`ok|fail|suppressed`；不再产生 `dropped`（`dropped` 序列保留 0 基线，避免旧查询断档）。

## 否决项

- **MQ / Redis / 分布式锁**：本切片是单实例（ADR-0036）。引入中间件会把「一条告警
  发不出去」变成需要运维另一套基础设施才能解释的问题；outbox 表复用已有 H2/PostgreSQL，
  重启恢复与死信排查都在同一处。
- **请求线程/事务内直发 HTTP**：上游慢会把事故建单与站内通知一起拖住；事务回滚还会
  造成「消息发了、事故没建」的不可逆事实。
- **无限重试**：持续 4xx 或错误配置会制造永久噪音与连接占用；8 次后进死信，
  由人决定补发或修配置。
- **不做持久化、只加重试**：进程重启仍会丢；没有 `attempts/last_error` 就没有
  死信排查的证据面。
- **个人私信 / 猜 open_id / 邮箱**：`users` 表没有这些身份，猜测会把消息发给错误的人；
  v1 只做频道级广播，文本里带负责人 `sub`。
- **邮件 / 短信 / Web Push / 评论 @ 外发**：不在本切片；频道广播已覆盖 on-call 群。
- **改前端深链或面板**：深链 `/?inc=<incidentId>` 已存在；本切片只加管理 API。

## 后果

- 外发从「best-effort」变成「可查询、可重试、可重启恢复」；代价是多一张表和
  一个轮询任务。
- 投递是 at-least-once：接收端应用 `Idempotency-Key` 去重；OpsPilot 不承诺 exactly-once。
- `incident_push_outbox` 只增不改旧表；回滚代码后旧路径恢复，表可保留（旧代码不读）。
