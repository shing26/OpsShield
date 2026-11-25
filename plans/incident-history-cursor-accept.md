# 验收契约：事故历史游标分页

> 来源：2026-11-25 用户选定 S2 `incident-history-cursor`（历史游标分页）。
> **状态：已冻结（2026-11-25）。** 基线 `main` @ `a272fd3`；
> 本分支只承载契约；实现分支为 `codex/incident-history-cursor-impl`（契约合并后从 main 切出）。
> 用户已签收三项口径：顺序唯一事实源移到服务端、游标绑定 `mine/status`、客户端上限 1000 条
> 且搜索只覆盖已加载页。

## Task ID

`incident-history-cursor`

## 现状与缺口（2026-11-25 实测，基线 `a272fd3`）

已有能力：

- `GET /api/v1/copilot/incidents?mine=&status=` 返回本租户最近 **60** 条，
  `ORDER BY updated_at DESC, incident_id DESC`；
- `mine` 与 `status` 已在服务端筛选（ADR-0033），但前端只用 `mine`；
- 索引 `(tenant, updated_at)` 与 `(tenant, status)`（V5）。

缺口：

1. **没有游标**：`MAX_LIST=60` 是硬上限，第 61 条以后的事故永远看不到。
2. **前端多处 60 截断**：`refreshIncidents` 与单条回填都 `slice(0, 60)`，`length > 60` 直接截断。
3. **顺序有两个事实源**：服务端按 `updated_at`，前端又按 `slaRank` 二次排序；
   一旦分页，追加页会被再次排序，列表在视觉上乱跳。
4. **筛选与分页混在一起**：`open`/`closed` 在客户端过滤；分页后一页可能被过滤成空页。
5. **计数会撒谎**：顶栏「进行中 N」按已加载数组统计，分页后只代表"已加载的进行中"。

## 判定口径

- 「游标」= **键集（keyset）**，不是 `OFFSET`。排序键为
  `(sla_rank ASC, updated_at DESC, incident_id DESC)`：
  `sla_rank` = `closed→2 / breached→0 / due_soon→1 / 其余→2`，
  与现前端 `slaRank()` 逐字一致。
- **顺序的唯一事实源移到服务端**：客户端不再对服务端返回的页做二次排序；
  本地新建的草稿可以置顶，但不得重排服务端页内顺序。
- 游标不透明：`base64url(v1|rank|epochMicros|incidentId|mine|status)`，无 padding。
  - `rank`：0..2；`epochMicros`：`updated_at` 的微秒值（不是毫秒，避免同毫秒并列丢行）；
  - `mine`：`true|false|空`；`status`：`open|closed|空`；
  - **筛选绑定**：游标里的 `mine/status` 必须与本次请求一致，否则 400；
  - 非法版本 / 段数不对 / rank 越界 / id 形状非法 → 400，不得 500。
- 游标**不含 tenant**，也**不作为授权依据**；tenant 永远只来自 JWT。
  用 A 租户的游标请求 B 租户，只会得到 B 租户的页，不会泄漏 A 的任何行。
- **快照语义**：游标取到后，某行被更新或 SLA 档位变化（`updated_at` / `sla_rank` 改变），
  它可能不在后续页出现；刷新即最新。这是键集分页对可变排序键的已知边界，
  不是重复/丢行的 bug。不得为此引入快照表或分布式锁。
- 默认 `limit=60`，上限 100；非数字或越界 → 400。
- `openCount` 为**本租户进行中总数**（`status='open'`），不受 `mine`/`status`/游标影响；
  顶栏用它，不再用已加载数组统计。
- 搜索仍是**客户端对已加载页**的过滤；全量搜索另开切片，不在本契约内。

## Allowed paths

```
plans/incident-history-cursor-accept.md
src/main/resources/db/migration/V11__incident_history_cursor.sql
src/main/java/com/opspilot/incident/**
src/main/resources/static/index.html
src/test/java/com/opspilot/incident/**
src/test/java/com/opspilot/auth/FlywayMigrationTest.java
offline/tests/test_console_contract.py
docs/CONTEXT.md
docs/adr/0038-incident-history-cursor.md
docs/repo-map.md
README.md
offline/doc_numbers.json
```

## Acceptance criteria

1. **迁移**：新增 V11，只加索引
   `(tenant, updated_at, incident_id)`，不改 V1–V10；`FlywayMigrationTest` 空库/已有库均通过。
   索引服务于租户扫描与并列时的 `incident_id` 次序；`sla_rank` 排序在租户内完成。
2. **API**：`GET /api/v1/copilot/incidents` 新增 `cursor` 与 `limit`；
   响应新增 `nextCursor`（字符串或 `null`）、`hasMore`（布尔）、`openCount`（整数）。
   既有 `incidents` 数组与每行字段不变；无 `cursor` 时行为等价于第一页。
3. **键集正确性**：用 7 条以上、含**相同 `updated_at`** 与三种 `sla_rank` 的种子数据，
   以 `limit=2` 逐页走到 `nextCursor=null`；并集必须等于全量、无重复、无遗漏，
   顺序与 `(sla_rank, updated_at DESC, incident_id DESC)` 逐项一致。
4. **筛选绑定**：`mine=true` 发出的游标配 `mine=false` 使用 → 400；
   `status=open` 与 `status=closed` 互换 → 400；错误消息点名"游标与筛选条件不匹配"。
5. **非法游标**：乱码、截断、`v2`、rank=3、空 id → 400；日志不得打印游标原文。
6. **租户边界**：B 租户用 A 的游标请求，只返回 B 的行；游标不能扩大可见范围。
7. **分页上限**：`limit` 缺省 60、`1..100` 合法；`0`、`101`、非数字 → 400。
8. **前端**：
   - 左栏底部有「加载更多」；点击追加下一页，按 `id` 去重，**不重排**服务端页；
   - 切换 `全部/进行中/已结束/我的` 时重置游标并重拉第一页；
   - `进行中/已结束` 改为服务端 `status=` 筛选，`我的` 改为 `mine=true`，两者可叠加；
   - 去掉所有 60 截断；客户端最多保留 1000 条，到顶后按钮变"已加载最近 1000 条，
     请用筛选缩小范围"，不得无限撑 DOM；
   - 顶栏计数用 `openCount`；空态区分"本租户没有事故单"与"已加载页无匹配"；
   - 从通知/告警按 id 打开未加载的事故时，单条回填按 id 去重，不破坏游标状态。
9. **不回归**：创建 / 详情 / PATCH / 转交 / 评论 / 通知 / SLA 扫描语义与响应字段不变；
   通知铃与评论未读水位不动；面板契约十层一致；
   `check_public_copy.py --check` 与 `doc_numbers.py --check` 通过。
10. **性能边界（登记，不在本切片实现）**：单租户事故 > 5000 条或列表 p95 > 200ms 时，
    再评估把 `sla_rank` 物化为列并建复合索引；本切片不提前施工。

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

1. 造 130 条同租户事故（脚本或 SQL），`limit=60` 第一页 60 条、`nextCursor` 非空；
2. 连续翻到最后一页，并集 130 条、无重复；与按 `sla_rank, updated_at, id` 独立排序的结果一致；
3. 把某条未加载的事故改为 `breached` 后继续翻页：它不会凭空出现在后续页（快照语义），
   刷新后出现在第一页；
4. `mine=true` 的游标配 `mine=false` → 400；乱码游标 → 400；
5. 前端「加载更多」追加 60 条、列表顺序不跳；切「进行中」后只剩 open 且游标重置；
6. 顶栏「进行中」在只加载第一页时仍显示全租户真实总数；
7. 从通知打开一条未加载的事故 → 能打开、列表不出现重复行、再次加载更多不重复。

## Forbidden

- 禁止 `LIMIT ? OFFSET ?` 或任何 offset 翻页。
- 禁止客户端对服务端返回的页再次排序（本地草稿置顶除外）。
- 禁止把 tenant 写进游标或用游标做授权判断。
- 禁止改变事故创建 / PATCH / 转交 / 评论 / 通知的既有语义与字段。
- 禁止改 V1–V10；禁止 `git add -A`。
- 禁止无限增长 DOM；禁止用"加载全部历史"规避分页。
- 禁止改/删/弱化断言换通过；禁止跳测试不记录。

## 回滚

V11 只加索引；API 只加字段与可选参数。旧客户端不传 `cursor` 时行为不变；
`git revert` 后启动与既有功能不受影响。

## 人审签收

**状态：已签收（2026-11-25）。** 三项口径按原样确认：
① 顺序唯一事实源移到服务端（客户端不再二次排序）；
② 游标绑定 `mine/status`，不匹配即 400；
③ 客户端上限 1000 条、搜索只覆盖已加载页。
实现者与 Verifier 不得为同一会话。
本次签收只授权实现，不含合并授权；合并须另行书面签收。

## 签收记录（2026-11-25，PR #104）

**结论：通过。** 用户书面签收，授权 squash 合并。

### 人审四问

1. **证据充分性：无异议**——独立 Verifier 在 `7bed32a` 亲跑全部命令与活体验收；
   实现方自评与其日志未作为裁决证据。P1（告警打开未加载事故重置游标）由 Verifier 复现，
   修复后由同一 Verifier 复验关闭。
2. **判据纪律：无异议**——未改/删/弱化断言，未跳测试（`Skipped: 0`）；
   130 条键集逐页、筛选绑定、跨租户、非法游标、快照语义均由 Verifier 亲自重放。
3. **变更面封闭：无异议**——`7c3eeab..7bed32a` 共 13 个文件，全部在 Allowed paths 内；
   V1–V10 无 diff，创建/PATCH/转交/评论/通知语义零 diff。
4. **遗留边界：接受为已知项**——见下。

### 已知边界（接受）

- 快照语义：行在翻页途中被更新或改档，可能不在后续页出现；刷新即最新。
- 客户端最多保留 1000 条；搜索只覆盖已加载页，全量搜索另开切片。
- 不物化 `sla_rank`：单租户 >5000 条或列表 p95 >200ms 再评估。
- 告警/通知按 id 打开未加载事故走单条回填，不重置游标。
- 未在 >5000 条或 p95 >200ms 条件下实测性能；AC10 只要求登记触发线。

### 合并授权

用户于 2026-11-25 书面回复「签收」，授权 squash 合并 PR #104。
