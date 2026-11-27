# 验收契约：告警历史键集分页

> 来源：2026-11-27 用户确认路线「先 `alert-history-cursor`，再单独起 Boot 4.2 / ES 9 兼容性 spike」。
> **状态：已冻结（R2，2026-11-27）。** 基线 `main` @ `471ab5f`；
> 本分支只承载契约；实现分支为 `codex/alert-history-cursor-impl`（契约合并后从 main 切出）。

## Task ID

`alert-history-cursor`

## 现状与缺口（2026-11-27 实测，基线 `9620189`）

已有能力：

- `GET /api/v1/admin/alerts/recent?since=&limit=`：
  `WHERE last_seen > since ORDER BY last_seen DESC, alert_id DESC`，`limit` 被静默夹到 `1..200`；
  响应 `{alerts, nextSince, retention_days, max_per_tenant, truncated}`；平台门禁
  `role=platform ∧ auth_level>=3`（ADR-0035）。
- 面板 `loadAlerts()` 用 `alertSince` 水位轮询增量，按 `(tenant, alertId)` 合并去重，
  最多保留 **100** 条后直接截断；`truncated=true` 被锁存成文案「仅显示最近 N 条」。

缺口：

1. **老告警永远看不到**：`since` 是增量水位，不是分页游标；首屏 `since=0&limit=50`
   只拿到最新 50 条，保留窗口（30 天 / 每租户 500 条）里的其余行没有任何读取路径。
2. **100 条是硬天花板**：面板 `S.alerts.length > 100` 直接截断，`truncated` 只是警告，
   用户无法继续加载。
3. **排序不是全序**：`ORDER BY last_seen DESC, alert_id DESC` 少了 `tenant`；
   同一 `last_seen` 且同名 `alertId` 分属两个租户时顺序不稳定，分页会重复/漏行。
4. **轮询有静默空洞**：两次轮询之间新增超过 `limit` 条时，`recent` 只返回最新 50 条，
   水位直接抬到最新值，中间那段既不展示也不会再被拉到。

## 判定口径

- **新端点，不改旧语义**：新增 `GET /api/v1/admin/alerts/history?cursor=&limit=`；
  `/alerts/recent` 的入参、响应字段与行为**零改动**（面板轮询继续用它）。
- 「游标」= **键集（keyset）**，不是 `OFFSET`。排序键全序：
  `(last_seen DESC, tenant DESC, alert_id DESC)`。
  `tenant` 进入排序只为消除并列，**不是授权依据**；端点仍是平台门禁，跨租户可见性不变。
- 游标不透明：`base64url( v1 | epochMicros | base64url(tenant) | base64url(alertId) )`，无 padding。
  - 时间用**微秒**，且必须取数据库回读行的真实值（`last_seen` 的 `Instant`），不得用毫秒截断；
  - 内层段各自 base64url，避免 tenant / alertId 里的分隔符破坏格式；
  - 非法版本 / 段数不对 / 解不开 / 空段 / 超长（tenant>64、alertId>128）→ 400，不得 500；
  - 日志不得打印游标原文（与 S2 同口径）。
- **无筛选绑定**：本切片没有 tenant/status 过滤参数，游标只承载排序位置，因此不存在
  「游标与筛选不匹配」；将来加筛选时必须把筛选值绑进游标。
- **快照语义**：行在翻页途中被 `touch`（`last_seen` 前移、`seen_count+1`）会移到第一页，
  可能不再出现在后续页；保留任务删掉的行也会消失。刷新即最新。这是键集分页对可变排序键的
  已知边界，不是重复/丢行 bug；不得为此引入快照表或锁。
- 默认 `limit=50`，合法 `1..200`；缺省/越界/非数字 → 400（仅新端点；`/recent` 的夹取行为不动）。
- 客户端最多保留 **1000** 条（与事故列表同口径）；到顶后按钮变
  「已加载最近 1000 条，请用保留窗口缩小范围」，不得无限撑 DOM。

## Allowed paths

```
plans/alert-history-cursor-accept.md
src/main/resources/db/migration/V13__alert_history_cursor.sql
src/main/java/com/opspilot/alert/**
src/main/java/com/opspilot/gateway/AdminController.java
src/main/resources/static/index.html
src/test/java/com/opspilot/alert/**
src/test/java/com/opspilot/auth/FlywayMigrationTest.java
offline/tests/test_console_contract.py
docs/CONTEXT.md
docs/adr/0047-alert-history-cursor.md
docs/repo-map.md
README.md
offline/doc_numbers.json
```

## Acceptance criteria

1. **迁移**：新增 V13，只加索引
   `(last_seen, tenant, alert_id)`，不改 V1–V12；`FlywayMigrationTest` 空库/已有库均通过。
   全 DESC 排序可由该索引反向扫描满足，不新增排序列。
2. **API**：`GET /api/v1/admin/alerts/history`，平台门禁与 `/alerts/recent` 同口径
   （非平台 403 且审计留痕）；无 `cursor` 即第一页。
   响应 `{alerts, nextCursor, hasMore, retention_days, max_per_tenant}`；
   每行字段与 `/alerts/recent` 的 `alerts[]` **逐字段一致**（前端可复用同一渲染），
   且两个端点都新增 `lastSeenMicros`（`last_seen` 的微秒值；毫秒 `lastSeen` 保留不动）。
   `nextCursor` 无更多页时为 `null`；`hasMore=false` 与 `nextCursor=null` 必须同时成立。
3. **键集正确性**：造 7 条以上、含**相同 `last_seen`**、含**两个租户同名 `alertId`** 的种子，
   以 `limit=2` 逐页走到 `nextCursor=null`；并集等于全量、无重复、无遗漏，
   顺序与 `(last_seen DESC, tenant DESC, alert_id DESC)` 逐项一致。
4. **微秒精度**：同一毫秒内、微秒不同的多行（`last_seen` 相差 <1ms）能被正确分页；
   游标取的是数据库回读值，不因毫秒截断丢行。
5. **非法游标**：乱码、截断、`v2`、段数不对、空 tenant/alertId、内层 base64 非法 → 400；
   日志不得打印游标原文。
6. **边界与门禁**：非平台身份带合法游标请求 → 403（游标不能扩大可见范围）；
   `limit=0/201/abc` → 400；缺省 50。
7. **保留/快照**：把已加载行 `touch` 到最新后继续翻页，它不会在后续页重复出现；
   刷新后出现在第一页。删除中间行后继续翻页不报错、不重复。
8. **前端首屏与轮询**：
   - 首屏改为 `/history?limit=50`，用返回页的 `max(lastSeen)` 初始化 `alertSince`，
     并保存 `nextCursor`/`hasMore`；
   - 轮询继续 `/recent?since=alertSince`，按 `(tenant, alertId)` 合并去重，水位只前进；
   - 展示顺序统一为 `(lastSeenMicros DESC, tenant DESC, alertId DESC)`，与后端一致；
     不得用毫秒 `lastSeen` 排序；`lastSeenMicros` 缺失时才退回 `lastSeen * 1000`；
   - 去掉 `S.alerts.length > 100` 截断；客户端上限 1000，到顶后按钮显示上限文案。
9. **「加载更多」**：告警表下方新增按钮；点击用 `cursor` 拉下一页并**追加**，
   按 `(tenant, alertId)` 去重，不重排已加载页；`hasMore=false` 时按钮显示
   「已到保留窗口起点」并禁用；加载中禁用并显示「加载中…」。
10. **轮询空洞修复**：`/recent` 返回 `truncated=true` 时，不得再锁存成
    「仅显示最近 N 条」。面板改为重载 `/history` 第一页并 `toast` 明确提示
    「新告警超过单次窗口，列表已刷新到最新」；旧页丢失可接受，但**不得静默留洞**。
11. **重置**：切换身份、401、登出时同时清空 `S.alerts`、`alertSince`、`nextCursor`、
    `hasMore`；不得用上一身份的游标继续翻页。
12. **不回归**：`/alerts/recent` 只允许新增 `lastSeenMicros`（附加字段），
    既有字段的删除/改名/语义与 `limit` 夹取行为不变；一键建单、平台门禁、审计、
    保留清理、事故/通知/推送语义零改动；面板契约十层、`check_public_copy.py --check`、
    `doc_numbers.py --check` 通过。
13. **文档**：ADR-0047（键集 / 微秒 / 快照边界 / 与轮询的分工）；
    `docs/CONTEXT.md` 增加「告警回看（Alert History Browse）」词条并说明
    **只覆盖保留窗口、不是永久历史**；`docs/repo-map.md` 与 README 的 ADR/测试计数同步。
14. **性能边界（登记，不在本切片实现）**：跨租户台账总量 > 50,000 行或
    `/alerts/history` p95 > 200ms 时，再评估分区/归档或物化排序键；本切片不提前施工。

## Verification commands（Verifier 亲自重跑）

```bash
cd "D:/OpsPilot — AIOps"
git rev-parse HEAD && git status --short
E:/java/apache-maven-3.9.14/bin/mvn.cmd -o test
E:/Git/bin/bash.exe scripts/check_panel_contract.sh
offline/.venv/Scripts/python.exe -m pytest offline/tests -q
offline/.venv/Scripts/python.exe scripts/check_public_copy.py --check
offline/.venv/Scripts/python.exe offline/doc_numbers.py --check
```

活体验收（Verifier 亲自操作）：

1. 造 13 条同租户告警 + 2 条跨租户同 `alertId`，其中至少 3 条 `last_seen` 相同、
   2 条相差 <1ms；`limit=5` 逐页翻到 `nextCursor=null`，并集 15 条、无重复，
   与独立按 `(last_seen, tenant, alertId)` 排序一致；
2. 把某条未加载行 `touch` 到最新后继续翻页 → 不重复、不报错；刷新出现在第一页；
3. 乱码 / `v2` / 截断游标 → 400；非平台身份 + 合法游标 → 403；
4. 连续两次轮询之间注入 60 条新告警 → `recent` 返回 `truncated=true`，
   面板自动重载第一页并有 toast，不出现「仅显示最近 N 条」死胡同；
5. 点「加载更多」追加 50 条、顺序不跳；到保留窗口起点后按钮禁用；
6. 切身份后列表清空、游标清空，不会拿旧游标继续翻。

## Forbidden

- 禁止 `LIMIT ? OFFSET ?` 或任何 offset 翻页。
- 禁止改动 `/alerts/recent` 的入参、响应字段、夹取行为与轮询语义。
- 禁止把游标当授权凭据；禁止因游标内容放宽平台门禁。
- 禁止在日志/审计里打印游标原文。
- 禁止客户端对服务端页做二次重排（合并新行按同一排序键归位除外）。
- 禁止「一次拉全量」规避分页；禁止无限增长 DOM。
- 禁止改 V1–V12；禁止 `git add -A`。
- 禁止改/删/弱化断言换通过；禁止跳测试不记录。

## 回滚

V13 只加索引；新端点与前端按钮均为增量。回滚实现提交后，
`/alerts/recent` 与既有面板轮询不受影响；旧客户端不会调用 `/alerts/history`。

## 人审签收

**状态：已签收（2026-11-27）。** 用户书面回复「签收」，四项口径按原样确认：

1. 新增 `/alerts/history` 而不是给 `/alerts/recent` 加 `cursor` 双模式（旧端点零改动）。
2. 排序键为 `(last_seen DESC, tenant DESC, alert_id DESC)`，时间用微秒。
3. 轮询 `truncated=true` 时重载第一页 + 提示，接受「旧页丢失但不留洞」。
4. 客户端上限 1000 条；搜索/筛选不在本切片。

实现者与 Verifier 不得为同一会话。本次签收只授权实现，不含实现 PR 的合并授权；
实现 PR 合并须另行书面签收。

## R1 修订（2026-11-27，仅编号）

契约合并后 `main` 前移到 `686abf5`：RCA A1 切片先占用 ADR-0046
（`0046-rca-a1-detection-features.md`）。本切片的 ADR 顺延为 **0047**，
其余判定口径、Allowed paths 范围与验收项**零变更**；实现与验证按本 R1 文本执行。

## R2 修订（2026-11-27，补微秒排序字段）

独立 Verifier 在 `c3f31d9` 发现 P1：后端按微秒排序，但 API 行只暴露毫秒 `lastSeen`，
前端轮询合并后按毫秒重排，导致同一毫秒内的既有行在**无新增告警**时也换位，违反 AC8
「不得对服务端页二次排序」。

修订（仅此一项）：`alertView` 在两个端点的每行新增 `lastSeenMicros`；
前端 `alertOrder` 改用 `lastSeenMicros` 作为首键，毫秒 `lastSeen` 仅用于显示与保留窗口判断。
既有字段、端点语义、游标格式与其余验收项**零变更**。
