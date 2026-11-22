# 验收契约：运行面板告警入口（告警落表 + 一键建单）

> 来源：2026-11-22 用户指令「接着按这个顺序起契约」——第 3 条：
> 运行面板的告警入口。
> **状态：已冻结（2026-11-22）。** 冻结点 `0c50ff9`（main 基线；契约文件随 `93d44be` 带入）；实现分支 `codex/alert-intake-panel`。
> 用户已确认保留口径：`30 天 / 每租户 500 条`。依赖前两条已合并，按此顺序合并。

## Task ID

`alert-intake-panel`

建议分支：`codex/alert-intake-panel`

## 现状与缺口（2026-11-22 实测）

已有能力：

- `scripts/alert_bridge.py` 把 Alertmanager 分组摊平成逐条请求，派生稳定 `alertId`
  （`alertname|job|error_code|status` 四元组）与 `am-…` 关联键（`X-Trace-Id`）；
- 网关对带 `alertId` 的请求用 `SHA256("alert-id|"+alertId)` 做身份，审计行带
  `source=alert`、`fp`、`trace_id`、`q`；
- 事故单支持告警深链 `?alert=&source=alert&title=&q=&service=&errorCode=&env=` 预填新建；
- 运行面板有 `/admin/state`、`/admin/audit/recent`（200 条进程内环，带 `truncated`）。

缺口：

1. **告警没有留存**：唯一记录是审计 JSONL 与 200 条内存环；面板无法回答
   "今天进来了哪些告警、各自来了几次、有没有人接手"。重启后环清空。
2. **告警到事故单要手工**：值班人得自己复制 `alertId`、点深链、填表；
   同一条告警重复点会建出多张单（`clientId` 是前端随机 UUID）。
3. **面板看不到告警维度**：现有面板只有请求/轨迹/降级，没有"告警 → 事故单"的入口。

## 判定口径

- **数据源是真实的告警请求本身**，不新增无鉴权端点：写入点是 `ChatOrchestrator`
  收到 `source=alert ∧ alertId≠null` 时的一次 upsert。这与 ADR-0008（每条共享路径都有
  权限维度）一致——告警仍由桥以人身份投递，不开机器账号。
- **告警身份沿用桥的四元组**（`alertId`），服务端不重新发明。`firing` 与 `resolved`
  是两个身份（桥已如此），本切片不合并它们。
- **建单必须幂等**：用 `clientId = "alert-" + alertId` 复用既有
  `(tenant, client_id)` 唯一索引；重复点返回同一条事故单（200），不产生第二张。
- **留存是有界的**：默认保留 30 天且每租户最多 500 条，超界由定时任务清理；
  面板必须显示"保留窗口"，不得让人误以为是永久台账。

**不做**：Alertmanager 原生 UI 复刻、告警静默/抑制、告警升级策略、IM 推送、
跨租户告警共享、按告警自动诊断（仍需人点）。

## Allowed paths

```
plans/alert-intake-panel-accept.md
src/main/resources/db/migration/V8__alert_intake.sql
src/main/java/com/opspilot/alert/**
src/main/java/com/opspilot/gateway/ChatOrchestrator.java        # 仅新增告警 upsert 调用（best-effort）
src/main/java/com/opspilot/gateway/AdminController.java         # 仅新增 /alerts/recent 与 /alerts/{id}/incident
src/main/java/com/opspilot/incident/**                          # 仅新增按 alertId 建单的幂等入口
src/main/java/com/opspilot/config/OpsPilotProperties.java       # 仅新增 alert.retention 段
src/main/resources/application.yml                              # 仅新增 opspilot.alert.*
src/main/resources/static/index.html
src/test/java/com/opspilot/alert/**
src/test/java/com/opspilot/auth/FlywayMigrationTest.java        # 仅新增 V8 断言
offline/tests/test_console_contract.py
offline/tests/test_alert_bridge.py                              # 仅新增"桥产出的 alertId 与网关落表一致"用例
docs/CONTEXT.md
docs/adr/0035-alert-intake.md
docs/repo-map.md
README.md
offline/doc_numbers.json                                        # 仅当测试计数变化时同步
```

## Acceptance criteria

1. **迁移**（起草时为 V8；按实际合入顺序顺延，冻结时确认最终版本号）：
   新建 `alert_intake`：
   `tenant / alert_id / trace_id / service / env / error_code / summary / alert_status /
   first_seen / last_seen / seen_count / incident_id`；
   主键 `(tenant, alert_id)`；索引 `(tenant, last_seen)`、`(tenant, incident_id)`。
   `FlywayMigrationTest` 在空库与已有库上均通过。
2. **写入 best-effort**：`ChatOrchestrator` 在 `source=alert ∧ alertId≠null` 时调用
   `AlertIntakeService.record(...)`：
   - 已存在则 `seen_count+1`、`last_seen=now`、刷新 `summary/service/env/error_code`；
   - 不存在则插入，`first_seen=last_seen=now`、`seen_count=1`；
   - **任何异常只记 warn 日志，不得让诊断请求失败**（告警台账坏了不能拖垮排障）。
3. **告警不跨租户**：`tenant` 只来自 JWT；`(tenant, alert_id)` 为键，
   两个租户收到同一 `alertId` 是两行。`summary` 截断 200 字，`trace_id` 截断 64。
4. **面板读取**：`GET /api/v1/admin/alerts/recent?since=<lastSeenMs>&limit=50`，
   要求 `role=platform ∧ authLevel>=3`（与 `/admin/state` 同门禁，越权 403 且留痕）。
   返回 `{alerts:[...], nextSince, retention_days, max_per_tenant, truncated}`；
   按 `last_seen DESC` 排序，**跨租户可见并带 `tenant` 字段**（与面板既有口径一致）。
   无告警时返回空数组，不造样例数据。
5. **一键建单**：`POST /api/v1/admin/alerts/{alertId}/incident`，
   body 可选 `{priority}`；语义：
   - 以 `clientId="alert-"+alertId` 调用既有创建路径，幂等；
   - 首次创建返回 201 并写 `alert_intake.incident_id`；重复调用返回 200 与同一 `id`；
   - 事故单的 `source=alert`、`alertId`、`service/errorCode/env`、`query=summary`
     全部来自 `alert_intake`，**不由前端拼装**；
   - 已存在事故单时以库里的 `incident_id` 为准，不信任请求体。
6. **保留策略**：`@Scheduled` 每日一次清理：删除 `last_seen < now-30d` 的行；
   每租户超过 500 条时删除最旧的。阈值来自 `opspilot.alert.retention-days` 与
   `opspilot.alert.max-per-tenant`，不写死。清理动作落 `ev=admin action=alert_retention`。
7. **前端**：运行面板新增「告警入口」区块（在「排障成果流」之前）：
   - 列：最近时间 / 租户 / 告警（`summary` 或 `errorCode`）/ 次数 / 状态 / 关联事故单；
   - 有 `incident_id` 的行显示「打开事故单」，否则显示「建事故单」；
   - 空态文案明确写数据源与保留窗口，不摆示例数字；
   - 建单失败 toast 明示；成功后左栏出现该单并可一键跳转。
8. **不回归**：`/admin/state`、`/admin/audit/recent` 的响应形状不变；审计环仍 200 条；
   面板轮询频率不变；L1 默认关闭；只读命令逐字；拒答/截断判定不变；
   面板契约十层一致；`check_public_copy.py --check` 与 `doc_numbers.py --check` 通过。

## Verification commands（Verifier 亲自重跑）

```bash
cd "D:/OpsPilot — AIOps"
git rev-parse HEAD && git status --short          # 证据绑定
E:/java/apache-maven-3.9.14/bin/mvn.cmd -o test   # 全绿
python -m pytest offline/tests -q                 # 全绿
E:/Git/bin/bash.exe scripts/check_panel_contract.sh
python scripts/check_public_copy.py --check
python offline/doc_numbers.py --check
```

真实告警链路（用桥本身，不用手搓 JSON；载荷格式见
`offline/tests/test_alert_bridge.py` 的 `_payload()/_alert()` 夹具，
或直接用 `ops/alerting/alertmanager.yml` 指向的真实 Alertmanager）：

```bash
# 1) 起隔离实例（memory + mock，临时 H2）
# 2) 用真实 Alertmanager 分组载荷打桥（--dry-run 不算证据，必须真投递）
python scripts/alert_bridge.py --port 18089 --env local
curl -sS -X POST http://127.0.0.1:18089/api/v2/alerts \
  -H 'Content-Type: application/json' --data @<真实载荷>.json
```

Playwright（Edge，临时脚本，验完即删）：

1. 平台身份登录 → 运行面板「告警入口」出现该告警，次数 ≥1，租户正确；
2. 点「建事故单」→ 201，左栏出现事故单，`alertId/service/errorCode` 与告警一致；
3. 再点一次同一告警 → 返回同一 `id`（200），事故单数量不变；
4. 重启实例 → 告警仍在（证明落表，不是内存环）；
5. 非平台身份（`sre-acme`）直接 `GET /api/v1/admin/alerts/recent` → 403 且审计留痕；
6. 无告警时面板为空态，且不出现任何示例数字。

## Forbidden

- 禁止改/删/弱化任何断言换通过；禁止跳测试不记录。
- 禁止为告警桥新开无 JWT 的网关端点（ADR-0008）；写入只能发生在已鉴权的 chat 请求内。
- 禁止让告警写入失败影响诊断响应；禁止把异常吞掉且不留日志。
- 禁止用前端拼装建单字段（`service/errorCode/query` 必须来自服务端告警行）。
- 禁止把告警保留策略写死成常量或只写在前端文案里。
- 禁止 `git add -A`；合并时只暂存 Allowed paths。

## 回滚

单分支交付。V8 只新增一张表；失败时 `git revert` 即可。`ChatOrchestrator` 的写入是
best-effort 且包在 try/catch 内，回滚后旧代码不读该表，启动与排障链路不受影响。

## 人审签收

**状态：待签收。** 签收前不得开始实现；实现者与 Verifier 不得为同一会话。
