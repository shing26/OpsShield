# ADR-0035：告警台账与一键建单

- 状态：已采纳（2026-11-22）
- 相关：ADR-0008（权限维度是数据层不变量）、ADR-0019（告警身份由上游派生）、
  ADR-0032（事故持久化）、ADR-0033（责任流转）、
  契约 [plans/alert-intake-panel-accept.md](../plans/alert-intake-panel-accept.md)

## 背景

告警桥（`scripts/alert_bridge.py`）已经把 Alertmanager 载荷逐条摊平、以人身份投递到
既有 chat 链路，服务端也用 `alertId` 做了稳定指纹。但**告警本身没有留存**：
唯一记录是审计 JSONL 与 200 条进程内环，重启即空；面板回答不了"今天进来了哪些告警、
各自来了几次、有没有人接手"。从告警到事故单仍需人工复制 `alertId`、点深链、填表，
重复点击还会因前端随机 `clientId` 建出多张单。

本切片补三件事：**告警落表**、**面板入口**、**一键建单**。不做 Alertmanager 原生 UI、
静默/抑制、升级策略、IM 推送、跨租户共享或按告警自动诊断。

## 决策

### 1. 写入点是已鉴权的 chat 请求，不开新端点

`ChatOrchestrator` 在 `source=alert ∧ alertId≠null` 时调用
`AlertIntakeService.record(...)` 做一次 upsert。告警桥仍以人身份投递，**不新增无 JWT 的
机器端点**（ADR-0008：每条共享路径都必须有权限维度）。这也意味着告警台账的数据源是
"真实打到编排链路的请求"，不是另建一条旁路采集。

### 2. 身份是 `(tenant, alert_id)`，不是 `alert_id`（R1）

`alertId` 由桥按 `alertname|job|error_code|status` 四元组派生，**不含租户**
（`scripts/alert_bridge.py:173-186`）；而面板按 AC4 跨租户可见。起草时把它当全局唯一身份
是错的：两个租户跑同名规则、同 job、同错误码会得到同一个 `alertId`，同一个 URL 无法判定
建给谁。修订后：

- 台账主键是 `(tenant, alert_id)`；`tenant` 只来自 JWT，两个租户各占一行。
- 建单端点是 `POST /admin/alerts/{alertId}/incident?tenant=<tenant>`，`tenant` **必填**；
  缺失返回 400，错误信息点明"同一告警在不同租户是不同事故"。
- `(tenant, alertId)` 未命中台账返回 404，不凭空建单。
- 建单租户 = **告警行所属租户**，不是平台管理员的 JWT 租户。为此
  `IncidentService` 新增**内部**入口 `createForTenant(operator, targetTenant, req)`：
  复用既有创建路径与幂等键，`created_by` / `owner` 仍记实际操作人 `sub`，
  且**不映射任何公开端点**。
- 前端「建事故单」必须带该行的 `tenant`，不得自行猜测。

### 3. 写入 best-effort：台账坏了不能拖垮排障

`record(...)` 捕获全部异常并只记 warn；`ChatOrchestrator` 调用点再包一层 try/catch。
已存在则 `seen_count+1`、`last_seen=now`、刷新 `summary/service/env/error_code`；
不存在则插入 `seen_count=1`。`summary` 截 200 字、`trace_id` 截 64。
**不把告警写入失败升级成诊断失败**——排障是主链路，台账是副产物。

### 4. 一键建单幂等且字段只来自台账行

`clientId = "alert-" + alertId` 复用既有 `(tenant, client_id)` 唯一索引；首次 201 并回写
`alert_intake.incident_id`，重复调用 200 且返回同一 `id`。事故单的
`source=alert / alertId / service / errorCode / env / query=summary` 全部取自台账行，
请求体只允许 `priority`。已存在事故单时以库里的 `incident_id` 为准，不信任请求体。
`errorCode` 与 `alertStatus` 当前不由桥随请求发送，故如实落空，**不从文案猜测**。

### 5. 有界留存，阈值只来自配置

`@Scheduled` 每日一次：删除 `last_seen < now-30d` 的行；每租户超过 500 条时删最旧的。
阈值来自 `opspilot.alert.retention-days` / `opspilot.alert.max-per-tenant`，不写死；
清理动作落 `ev=admin action=alert_retention`。面板必须显示保留窗口，不能让人误以为是
永久台账。

### 6. 面板沿用既有视觉语言

「告警入口」位于「排障成果流」之前。列为最近时间 / 租户 / 告警 / 次数 / 状态 / 关联事故单；
有 `incident_id` 显示「打开事故单」，否则「建事故单」；空态写明数据源与保留窗口，
不摆示例数字；建单失败 toast 明示。不引入新依赖，轮询频率不变。

`GET /admin/alerts/recent` 的 `since` 是**新告警水位，不是分页游标**（R2）：它只用于面板
轮询拿增量，首屏以 `since=0` 取最近 `limit` 条。`truncated=true` 表示窗口内还有更老的告警，
面板必须在区块标题旁显示「仅显示最近 N 条」，不得静默丢弃；历史全量回看需要
`(last_seen, alert_id)` 复合游标或独立分页端点，**另立契约**，本切片不做。

## 后果

- **V9 只新增一张表**，不改既有表；失败 `git revert` 即可。`ChatOrchestrator` 的写入是
  best-effort，回滚后旧代码不读该表，启动与排障链路不受影响。
- **跨租户可见但事故单仍按租户隔离**：平台身份可以为任一租户的告警建单，但自己的左栏
  只显示 JWT 租户的事故单。跨租户建单后明确 toast「已为 <tenant> 建单 <id>，切换到该租户
  身份可查看」，不假装成功，也不把事故列表扩成跨租户可见（R2）。
- **`errorCode` / `alertStatus` 可能为空**：桥当前不随请求发送这两个字段；台账不伪造，
  面板以 `—` 呈现。若将来需要，应先扩展 ChatRequest 契约，而不是在服务端解析 query。
- **台账读是平台级跨租户查询**：门禁与 `/state` 同口径（`role=platform ∧ level>=3`），
  越权 403 且留痕；单次上限 200，超出以 `truncated` 显式告知。
- **清理阈值调小会缩短可回看窗口**：这是部署选择，面板显示的窗口来自同一配置，
  不会出现"前端说 30 天、后端只留 7 天"的分叉。

## 备选方案

- **把 `alertId` 当全局主键**：实现更简单，但两个租户会共享一行/一个 URL，
  建单归属无法判定，且破坏租户隔离。R1 已否决。
- **让桥新增一个无 JWT 的写入端点**：会绕过 ADR-0008 的权限维度；写入只能发生在已鉴权的
  chat 请求内，否决。
- **建单信任请求体的 service/errorCode/query**：前端一改就能伪造事故内容，且与台账对不上；
  字段只从台账行取，否决。
- **台账写入与诊断同事务 / 强一致**：台账失败会连带诊断失败，违背"告警台账坏了不能拖垮
  排障"；best-effort 是刻意的边界。
- **永久留存 / 只在前端文案写保留窗口**：前者让表无界增长，后者是假承诺；
  阈值必须来自配置并由后端清理。
- **按告警自动诊断**：仍然需要人点；自动诊断涉及成本、权限与"谁对结论负责"，
  明确推迟。
