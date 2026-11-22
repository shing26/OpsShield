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
src/main/resources/db/migration/V9__alert_intake.sql
src/main/java/com/opspilot/alert/**
src/main/java/com/opspilot/gateway/ChatOrchestrator.java        # 仅新增告警 upsert 调用（best-effort）
src/main/java/com/opspilot/gateway/AdminController.java         # 仅新增 /alerts/recent 与 /alerts/{id}/incident
src/main/java/com/opspilot/incident/**                          # 仅新增按 alertId 建单的幂等入口
src/main/java/com/opspilot/config/OpsPilotProperties.java       # 仅新增 alert.retention 段
src/main/resources/application.yml                              # 仅新增 opspilot.alert.*
src/main/resources/static/index.html
src/test/java/com/opspilot/alert/**
src/test/java/com/opspilot/auth/FlywayMigrationTest.java        # 仅新增 V9 断言
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
4. **面板读取**（R2 修订，见「契约修订记录」）：`GET /api/v1/admin/alerts/recent?since=<lastSeenMs>&limit=50`，
   要求 `role=platform ∧ authLevel>=3`（与 `/admin/state` 同门禁，越权 403 且留痕）。
   返回 `{alerts:[...], nextSince, retention_days, max_per_tenant, truncated}`；
   按 `last_seen DESC` 排序，**跨租户可见并带 `tenant` 字段**（与面板既有口径一致）。
   无告警时返回空数组，不造样例数据。
   **`since` 是"新告警水位"，不是分页游标**（R2）：它只用于面板轮询拿增量，
   首次打开以 `since=0` 取最近 `limit` 条。当 `truncated=true`（窗口内还有更老的告警）时，
   **前端必须在区块标题旁显式显示"仅显示最近 N 条"**，不得让运维误以为已看到全部；
   `truncated` 必须被前端消费，不能静默丢弃。
   历史全量回看不在本切片范围（需要 `(last_seen, alert_id)` 复合游标或独立分页端点，另立契约）。
5. **一键建单**（R1 修订，见「契约修订记录」）：`POST /api/v1/admin/alerts/{alertId}/incident?tenant=<tenant>`，
   body 可选 `{priority}`；语义：
   - **`tenant` 必填**：`alert_id` 是桥按 `alertname|job|error_code|status` 四元组派生的
     32 位十六进制，**不含租户**（`scripts/alert_bridge.py:173-186`）；而本端点跨租户可见，
     同一 `alertId` 在两个租户各有一行。缺 `tenant` 无法判定该给谁建单 → **400**，
     且错误信息必须点明"同一告警在不同租户是不同事故"。
   - `(tenant, alertId)` 必须命中 `alert_intake` 的一行；未命中 **404**（不造样例、不凭空建单）。
   - 建单以**告警行所属租户**为准，而不是平台管理员的 JWT 租户——平台身份可跨租户运维，
     但事故单归属必须落在告警真正的租户上。为此 `IncidentService` 需要一个
     显式目标租户的建单入口（内部复用既有创建路径，`created_by` 仍记实际操作人 `sub`）。
   - 以 `clientId="alert-"+alertId` 幂等（33 字符，未超 `clientId` 上限 64）；
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
   - 「建事故单」请求必须带上该行的 `tenant`（R1）；缺少时前端不得自行猜测；
   - **跨租户建单后左栏不出现该单是预期行为**（R2）：事故列表按 JWT 租户隔离，
     平台身份给别的租户建单后，左栏只显示本租户事故单；此时必须 toast 明示
     "已为 <tenant> 建单 <id>，切换到该租户身份可查看"，不得假装成功、也不得跨租户展示。
   - 空态文案明确写数据源与保留窗口，不摆示例数字；
   - 建单失败 toast 明示；成功后左栏出现该单并可一键跳转。
     （后半句仅适用**同租户**建单；跨租户按上一条处理。）
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

单分支交付。V9 只新增一张表；失败时 `git revert` 即可。`ChatOrchestrator` 的写入是
best-effort 且包在 try/catch 内，回滚后旧代码不读该表，启动与排障链路不受影响。

## 人审签收

**状态：Lead 已书面签收（2026-11-22）。** 签收人：shing26；签收语：“按顺序起草契约”后的逐条确认。

### Verifier 证据包

- **独立 Verifier**：`/root/verify_alert_intake`（另一模型、独立 detached worktree），**两轮**复验。
- **首轮 FAIL（head `b9745e1`）**：4 个 P1——
  P1-1 `trace_id` 被空串覆盖（`trunc(null)→""` 让 `COALESCE` 形同虚设）；
  P1-2 `clientId="alert-"+alertId` 幂等键未被测试锁住（重复建单用例被 `incidentId` 短路）；
  P1-3 `truncated` 未被前端消费，>50 条老告警静默不可见；
  P1-4 AC5（允许跨租户建单）与 AC7（左栏出现该单）在跨租户路径自相矛盾。
- **契约 R2**（`923417f`）：裁决 P1-3/P1-4 的口径——`since` 是**新告警水位**而非分页游标，
  `truncated=true` 必须显式标注"仅显示最近 N 条"；AC7 的"左栏出现"限定为**同租户**，
  跨租户建单必须 toast 明示租户与单号，**不**把事故列表改成平台跨租户可见（禁止扩权）。
- **终轮 PASS（head `c7187f3`）**：4 个 P1 全部关闭，反向实验证明新测试非恒真。

### 命令结果（Verifier 亲跑）

`mvn -o test` → `Tests run: 554, Failures: 0, Errors: 0, Skipped: 0`；
`pytest offline/tests -q` → 674 passed（`collect-only` 亦 674）；
`check_panel_contract.sh` 十层一致；`check_public_copy --check`、`doc_numbers --check` 均退 0；
聚焦 `AlertIntakeServiceTest + AlertIntakeBestEffortTest` 16/16。

### 四处 P1 的关闭证据（Verifier 独立运行态复现）

- **P1-1**：桥投递带 `X-Trace-Id` → 同一 `alertId` 不带 trace 再打 chat →
  `traceId` 保留 `am-198d2c69…`、`service/env` 保留、`seenCount=2`；
  首次无 trace 的记录 `traceId=None`（不是空串）。
- **P1-2**：`incidentId` 初始为 null 时 8 并发建单 → `1×201 + 7×200`、`distinct_ids=1`、1 张单；
  查库 `client_id == "alert-"+alertId` 逐字对应。
- **P1-3**：造 60 行 → `truncated=true`；Edge 实测标题为
  `… 保留 30 天 / 每租户 500 条 · 仅显示最近 50 条`，表体 50 行，`CONSOLE_ERRORS: []`。
- **P1-4**：跨租户建单 toast `已为 tenant-acme 建单 INC-1122-8562，切换到该租户身份可查看`；
  平台左栏不含该单、`sre-acme` 左栏含该单。

### 反向验证（证明测试非恒真）

| 实验 | 改法 | 结果 | 复原 |
|---|---|---|---|
| 1 | `truncOrNull` 改回 `trunc` | **2 failed**（trace 被空串覆盖、首写落空串） | 16/16 绿 |
| 2 | `clientId` 改成 `null` | **2 failed**：并发 `expected:<1> but was:<8>`、`client_id` 断言失败 | 16/16 绿 |
| 3 | 移除「仅显示最近」 | **1 failed**：Python 静态契约 | 674 全绿 |

### AC1–AC8 + R1 + R2

全部**满足**。要点：V9 建表（12 列、复合主键、两个索引）、空库/V8→V9/已有库三条迁移路径；
best-effort 写入（改坏表名后诊断仍 200 且日志有 warn）；`(tenant, alert_id)` 双租户各一行、
同 `alertId` 不串单；面板门禁 403 留痕、跨租户带 `tenant`、`last_seen DESC`、空数组不造样例；
一键建单 400/404/201/200 四态齐备，字段只来自台账（恶意 body 被忽略），
建单租户取告警行租户；保留策略 30 天 / 500 条走配置，清理落 `ev=admin action=alert_retention`；
前端区块位置、`tenant` 参数、空态文案、失败 toast、`truncated` 标注齐备；
`/admin/state`、`/admin/audit/recent` 形状不变，审计环仍 200 条。

### 人审四问（Lead 签收即表示无异议）

1. **证据充分性**：无异议——两轮独立 Verifier 亲跑，实现方日志与自评未作为证据；
   P1-1 的覆盖路径、P1-2 的并发建单、P1-3 的浏览器渲染均由 Verifier 亲自复现。
2. **判据纪律**：无异议——无改/删/弱化断言、无跳测试、无自宣；
   3 组反向实验均"改坏必红、复原全绿"。
3. **变更面封闭**：无异议——`923417f..c7187f3` 共 8 文件全部在 Allowed paths；
   `src/main/java/com/opspilot/incident` 零改动；R2 明确禁止的"事故列表跨租户可见"未发生。
4. **遗留边界**：**接受为已知项**——见下。

### 登记的偏离与边界

- **AC1 的 V9 与 R1/R2 修订**：契约原文的 AC5 缺少租户维度、AC7 与 AC5 在跨租户路径冲突，
  均已通过 R1/R2 书面修订并由 Verifier 按新判据验收。修订只补判据、未放宽安全语义。
- **`errorCode` / `alertStatus` 当前落空**：桥的 `ChatRequest` 只发
  `query/source/service/env/alertId`，台账不从文案反推、不伪造，面板显示 `—`。
  如需真实值应先扩展 ChatRequest 契约，不在本切片处理。
- **`since` 只支持前向增量**：历史全量回看需要 `(last_seen, alert_id)` 复合游标或独立分页端点，
  另立契约；本切片以 `truncated` + 显式文案保证"不静默藏起"。
- **`S.alertTruncated` 只置真、不自动复位**（除身份切换）：方向保守（不隐瞒），登记为已知项。
- **README 徽章曾漂移**（537 vs 554）：已在签收前修正；`doc_numbers.py` 不覆盖徽章，
  登记为门闩覆盖缺口，后续可把徽章纳入 `cited_in`。

### 禁令复核

未以改/删/弱化断言换通过、未跳测试不记录、未关检查消警、实现方未自宣通过、
未绕过人审、未为告警桥新开无 JWT 端点。本次签收不含合并授权，分支保留。

## 契约修订记录

### R1（2026-11-22，Lead 批准）：一键建单补 `tenant` 维度

- **触发事实**：起草时把 `alertId` 当成全局唯一身份，但 `scripts/alert_bridge.py:173-186`
  的 `derive_alert_id` 只哈希 `alertname|job|error_code|status` 四元组，**不含租户**；
  而 AC4 明确要求面板跨租户可见。两个租户跑同名规则、同 job、同错误码时 `alertId` 相同，
  于是 `POST /alerts/{alertId}/incident` 无法判定建给谁，且 `(tenant, alert_id)` 主键下
  两行会共享同一个 URL。
- **修订**：AC5 的路径改为 `POST /api/v1/admin/alerts/{alertId}/incident?tenant=<tenant>`，
  `tenant` 必填；建单租户以告警行所属租户为准，而非平台管理员 JWT 租户。
  为此允许 `IncidentService` 新增一个**内部**目标租户建单入口（仍复用既有创建逻辑与幂等键）。
- **未放宽的判据**：幂等（`clientId="alert-"+alertId`）、201/200 语义、
  字段只来自 `alert_intake`、已存在时以库中 `incident_id` 为准——均原样保留。
- **不改变的部分**：AC1–AC4、AC6–AC8 与 Forbidden 全部不变。

### R2（2026-11-22，Lead 批准）：分页语义与跨租户左栏范围

- **触发事实**：首轮独立 Verifier 运行态实测两处契约自相矛盾/未定义：
  ① `since` 同时被当"增量水位"和"分页游标"用，超过 `limit` 的老告警永久不可见，
  且 `truncated` 前端未消费；
  ② AC5 允许平台跨租户建单，但 AC7 字面要求"成功后左栏出现该单并可一键跳转"——
  事故列表按 JWT 租户隔离，跨租户建单时这两条不可同时满足。
- **修订 ①（分页）**：明确 `since` 是**新告警水位**、不是分页游标；`truncated=true` 时
  前端必须在标题旁显示"仅显示最近 N 条"。历史全量回看另立契约，本切片不做。
- **修订 ②（跨租户）**：AC7 的"左栏出现该单"限定为**同租户**建单；跨租户建单必须 toast
  明示"已为 <tenant> 建单 <id>"，**不**把事故列表改成平台跨租户可见（那会扩大权限面，
  与 ADR-0008 的"每条共享路径都有权限维度"冲突）。
- **未放宽的判据**：AC5 的 400/404/201/200/字段来源/幂等，AC4 的门禁与跨租户可见性，
  AC6 的保留策略——均原样保留。
