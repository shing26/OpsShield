# 验收契约：告警/事故外发（IM 通道，可靠投递）

> 来源：2026-11-27 用户指令「起 alert-push 契约」。
> **状态：已签收（2026-11-27）。** 基线 `main = e2c2fdb`。
> 契约冻结；实现分支 `codex/alert-push-impl` 从契约合并后的 `main` 切出。
> 本切片取代 ADR-0037 决策 4 的「单次尝试、不重试」与决策 6 的「不做失败重试」；
> 不改 ADR-0034 的评论未读水位，也不改 ADR-0036 的单实例调度边界。
> **R1（2026-11-27，集成重编号）：** 契约合并后 `main` 已占用 ADR-0044（RCA 入口迟滞），
> 本切片 ADR 重编号为 `0045-alert-push-outbox.md`；仅编号变化，语义与验收不变。

## Task ID

`alert-push`

## 现状与缺口（基线 `e2c2fdb`）

已有能力：

- `incident_notifications`（V10，ADR-0037）：按 `(tenant, recipient)` 落站内通知，
  与事故变更同事务；前端铃铛 30s 轮询。
- `IncidentWebhookDispatcher`（ADR-0037）：事务提交后入有界队列，**单线程单次尝试**，
  3s 超时、不重试；队列满直接丢弃并计数；静默期只放行 P1。
- 事故详情已有深链 `/?inc=<incidentId>`（`index.html` 的 `handleDeepLink`）。

缺口：

1. **会丢**：进程重启、队列满、上游 5xx/超时都直接丢失，没有任何持久化待发记录。
2. **不可重试**：429 不读 `Retry-After`，4xx/5xx 不区分，失败后无法人工补发。
3. **只有一条全局 webhook**：不能按优先级/事件类型路由到不同频道，也没有投递审计。
4. **建单不外发**：`createInternal` 只落库，不产生通知；P1 告警建单后，
   除操作者外没人知道，on-call 群收不到。
5. **无签名**：接收端无法验证请求来自 OpsPilot；URL/密钥也没有防误配校验。

## 判定口径

- 「外发」= 把**事故生命周期事件**送到 IM 频道；不是把每条原始告警都推送。
- v1 事件：
  - `created`：新建事故且优先级达到 `created-min-priority`（默认 P2）。
  - `assigned` / `sla_due_soon` / `sla_breached` / `reopened`：
    与站内通知同一业务事件；`SLA`/`reopened` 的多收件人在同一频道合并为一条。
- 「谁收」：频道级广播，文本带负责人 `sub`；不做个人私聊，不猜 open_id/邮箱。
- 「频率」：一个业务事件对同一频道只入一行；每频道默认 5 条/秒，不聚合、不摘要。
- 「静默期」：沿用 `22:00-08:00`（`Asia/Shanghai`，可配），非 P1 不外发；
  P1 不受限。被抑制的事件不建投递行，但必须有指标与结构化日志。
- 「去重」：`(tenant, event_key, channel_id)` 唯一。
  `created` 的 `event_key = "c:" + incidentId`；
  站内通知的 `event_key = "n:" + dedupe_key`（SLA/reopened 天然合并多收件人）。

## 待签收决定

1. **v1 只做 IM**：`generic` / `feishu` 两种 webhook 格式；邮件、短信、Web Push、
   个人 IM、评论 @ 外发均不在本切片。
2. **共享频道路由**：按 `min-priority` 与 `kinds` 选频道；不做值班轮转、自动升级、
   回执/ack。文本里带负责人，人自己判断找谁。
3. **新增建单外发**：P1/P2 建单即入 outbox；不新增站内 `created` 通知行，
   铃铛语义不变，避免给创建者自己制造未读。
4. **投递语义**：at-least-once；最大 8 次；指数退避 `1s × 2^(n-1)`（±20% jitter，
   上限 5min）；429 优先读 `Retry-After`；非 429 的 4xx 直接 `dead`。
5. **兼容旧配置**：`webhook-url`/`webhook-format` 映射为 `default` 单通道；
   新旧配置同时出现直接启动失败，避免"到底发哪"不可解释。
6. **安全**：非 loopback 必须 `https`；禁止重定向；可选 host allowlist；
   可选 HMAC-SHA256 签名；secret 只从环境变量来，不入库、不进日志/错误串。
7. **运维面**：新增平台只读投递查询 + 死信手动重试端点；不改前端面板。

## Allowed paths

```
plans/alert-push-accept.md
src/main/resources/db/migration/V12__incident_push_outbox.sql
src/main/java/com/opspilot/incident/**
src/main/java/com/opspilot/gateway/AdminController.java
src/main/resources/application.yml
src/test/java/com/opspilot/incident/**
src/test/java/com/opspilot/auth/FlywayMigrationTest.java
docs/adr/0045-alert-push-outbox.md
docs/ops/alert-push.md
docs/CONTEXT.md
docs/repo-map.md
README.md
offline/doc_numbers.json
```

## Acceptance criteria

1. **迁移**：新增 `incident_push_outbox`：
   `outbox_id BIGINT IDENTITY PK / tenant / event_key VARCHAR(200) / incident_id /
   kind / recipient / priority / title / due_at / channel_id / status /
   attempts / next_attempt_at / last_attempt_at / last_status / last_error /
   sent_at / created_at`；
   唯一键 `(tenant, event_key, channel_id)`；索引 `(status, next_attempt_at)`、
   `(tenant, created_at)`。只新增 V12，不改 V1–V11；`FlywayMigrationTest` 空库/已有库都过。
2. **事务边界**：建单路径（`create` / `createForTenant` / `createInternal`）必须有明确事务；
   `created` outbox 行与事故行同事务。并发 `clientId` 重放不得把
   `DuplicateKeyException` 留在 rollback-only 事务里——重放走独立回查路径，
   返回既有事故且不重复入 outbox。站内通知的 outbox 行与通知行同事务；
   事务回滚后该事故的 outbox 行数为 0。
3. **配置**：`opspilot.incident.notify.channels.<id>` 支持
   `format`（`generic|feishu`）、`url`、`secret`、`min-priority`（默认 `P3`）、
   `kinds`（空=全部）、`quiet-hours`（缺省继承全局）、`enabled`（默认 true）、
   `rate-limit-per-second`（默认 5）。URL 启动时校验：非 loopback 必须 `https`，
   禁止 userinfo/fragment，允许空 URL=禁用该通道；未知 format/非法优先级启动失败。
   旧 `webhook-url` 存在且 `channels` 非空 → 启动失败。
   全局键：`created-min-priority`（默认 `P2`）、`ui-base-url`（默认空=不带深链）、
   `allowed-hosts`（默认空=不限制）、`max-attempts`（8）、`base-backoff-ms`（1000）、
   `max-backoff-ms`（300000）、`request-timeout-ms`（5000）、`batch-size`（20）、
   `poll-interval-ms`（1000）。
4. **路由**：优先级 `P1 > P2 > P3 > 空`；`created` 先过 `created-min-priority`，
   再过频道 `min-priority`；`kinds` 过滤；静默期外非 P1 不建行并记
   `result=suppressed, reason=quiet_hours`。同一 `(tenant, event_key, channel_id)`
   重复入队是 no-op，不是错误。
5. **投递**：`@Scheduled(fixedDelay=1000ms)` 单线程批量取 `pending` 且
   `next_attempt_at <= now`，按 `outbox_id` 顺序；每行先
   `UPDATE ... SET attempts=attempts+1, last_attempt_at=now, next_attempt_at=now+timeout
   WHERE outbox_id=? AND status='pending'`，更新 1 行才发送。
   2xx → `sent`；429 → 读 `Retry-After`（秒或 HTTP-date，封顶 5min）后回 `pending`；
   5xx/408/连接超时/读超时 → 指数退避后回 `pending`；其他 4xx/非法 URL/通道不存在 →
   `dead`；`attempts >= 8` → `dead`。请求线程永不被外发阻塞。
6. **持久性**：outbox 行在进程重启后仍在；重启后未发送的行继续投递。
   `sent`/`dead` 默认保留 90 天，每日清理单次上限 5000；`pending` 永不清理。
7. **载荷**：`generic` 为 `{"text":...,"incidentId":...,"kind":...,"priority":...,
   "recipient":...,"url":...,"deliveryId":...}`；`feishu` 为
   `{"msg_type":"text","content":{"text":...}}`。文本包含事件、事故 ID/标题、
   优先级、负责人、时限、深链 `ui-base-url/?inc=<id>`。
   **禁止**出现 `query`、诊断正文、评论正文、引用片段、secret。
   请求头带 `Idempotency-Key: <event_key>:<channel_id>`；配 secret 时带
   `X-OpsPilot-Timestamp` 与 `X-OpsPilot-Signature: sha256=<hex>`，
   签名原文 `timestamp + "." + rawBody`。
8. **安全**：`HttpClient` 禁止重定向；非 loopback `http` 拒绝；可选
   `allowed-hosts` 非空时 host 必须命中；日志/`last_error` 必须脱敏 URL userinfo/query
   与 secret；`last_error` 截断 500 字符。
9. **运维端点**：`GET /api/v1/admin/notifications/deliveries?tenant=&status=&channel=&limit=`
   平台管理员（role=platform 且 auth_level>=3），`tenant` 必填，`limit` 默认 50/上限 200；
   返回 `{id,tenant,incidentId,kind,priority,channel,status,attempts,lastStatus,
   lastError,nextAttemptAt,sentAt,createdAt}`，不含 URL/secret/载荷。
   `POST /api/v1/admin/notifications/deliveries/{id}/retry?tenant=` 仅允许 `dead`/`pending`，
   重置为 `pending`、`attempts=0`、`next_attempt_at=now`，写审计；`sent` 返回 409，
   跨租户/不存在返回 404。
10. **可观测**：新增
    `aiops_incident_push_total{channel,result}`（`ok|retry|dead|suppressed`）、
    `aiops_incident_push_pending{channel}`、`aiops_incident_push_latency_seconds{channel}`、
    `aiops_incident_push_last_success_timestamp{channel}`。
    旧 `aiops.incident.notify.webhook{result}` 保留，仅对 `default` 通道映射
    `ok|fail|suppressed`；不再产生 `dropped`。
11. **不回归**：站内通知的字段、排序、已读、30s 轮询与去重语义不变；
    事故列表/详情/评论/转交/SLA 响应不变；现有五条 Playwright E2E 全绿；
    `mvn -o test`、`pytest offline/tests`、面板契约、对外用词、文档数字、
    provenance、answer_eval 全绿。
12. **文档**：ADR-0045 记录 outbox/多通道/重试/安全决策与否决项；
    `docs/ops/alert-push.md` 给出 Feishu/generic 配置、签名校验、死信排查与手动重试；
    `docs/CONTEXT.md` 增「外发投递（outbox）」词条；`docs/repo-map.md`、`README.md`
    与 `offline/doc_numbers.json` 同步。

## Verification commands（Verifier 亲自重跑）

```bash
cd "D:/OpsPilot — AIOps"
git rev-parse HEAD && git status --short
E:/java/apache-maven-3.9.14/bin/mvn.cmd -o test
D:/OpsPilot\ -\ AIOps/offline/.venv/Scripts/python.exe -m pytest offline/tests -q
E:/Git/bin/bash.exe scripts/check_panel_contract.sh
D:/OpsPilot\ -\ AIOps/offline/.venv/Scripts/python.exe scripts/check_public_copy.py --check
D:/OpsPilot\ -\ AIOps/offline/.venv/Scripts/python.exe offline/doc_numbers.py --check
D:/OpsPilot\ -\ AIOps/offline/.venv/Scripts/python.exe offline/eval/provenance.py --check
cd offline && D:/OpsPilot\ -\ AIOps/offline/.venv/Scripts/python.exe eval/answer_eval.py --check
```

活体验收（本机 Python HTTP stub，不接受实现方自评）：

1. 起真 fat jar，配置一个 `generic` 通道指向 stub；通过 API 建 P1 事故 →
   2s 内收到 1 次 POST，载荷含 `incidentId`、`?inc=` 深链，且不含 `query`/诊断正文。
2. stub 前两次返回 500、第三次 200 → 同一 outbox 行 `attempts=3`、最终 `sent`，
   且只产生一次成功投递。
3. stub 返回 429 + `Retry-After: 2` → 下一次尝试不早于 2s；4xx → `dead` 且不再重试。
4. 入队后立即杀进程，重启 → pending 行继续投递并 `sent`（持久性）。
5. 静默期配置下建 P2 → 0 次 POST、指标 `suppressed`；建 P1 → 1 次 POST。
6. 两个频道（一个 `feishu`、一个 `generic`）→ 同一事件各 1 条；
   删掉其中一个频道配置后，其 pending 行变 `dead`，另一个照常投递。
7. 管理端点：平台账号能查 `dead` 行并手动 retry 成功；跨租户查询/重试 404；
   非平台账号 403。`last_error` 不含 URL/secret。
8. `git status --short` 干净，`e2e/artifacts` 不产生，日志无 secret。

## Forbidden

- 禁止把 `query`、诊断正文、评论正文、引用片段、secret 写进 outbox、payload 或日志。
- 禁止在请求线程/事务内直接发 HTTP；禁止用队列满静默丢弃替代持久化。
- 禁止新增邮件 / 短信 / Web Push / 个人 IM / 评论 @ 外发；禁止猜 open_id/邮箱。
- 禁止引入 Redis/MQ/分布式锁；禁止改 ADR-0036 的单实例边界。
- 禁止改 V1–V11；禁止改站内通知字段/排序/已读语义；禁止改 ADR-0034 水位。
- 禁止改/删/弱化既有断言；禁止跳测试不记录；禁止关闭 lint/typecheck 消警。
- 禁止 `git add -A`；合并时只暂存 Allowed paths。

## 回滚

单分支交付。V12 只新增 outbox 表；旧代码不读该表。`git revert` 后恢复 ADR-0037 的
单次尝试外发，站内通知与事故功能不受影响。配置默认关闭外发；删除 `channels` 即停。

## 人审签收

**状态：已签收（2026-11-27）。** 用户书面「签收」，八项「待签收决定」按原样确认。
契约冻结，可实现；实现者与 Verifier 不得为同一会话，合并须另行书面授权。
