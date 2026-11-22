# 验收契约：事故单责任流转（转交 / 我的 / SLA 提醒）

> 来源：2026-11-22 用户指令「接着按这个顺序起契约」——第 1 条：
> 事故单的责任流转（分配 / 转交 / SLA 提醒）。
> **状态：已冻结（2026-11-22）。** 冻结点 `0c50ff9`（main 基线；契约文件随 `93d44be` 带入）；实现分支 `codex/incident-responsibility`。
> 用户已确认 SLA 口径：`P1=30m / P2=2h / P3=8h / 空优先级=不设时限`。

## Task ID

`incident-responsibility`

建议分支：`codex/incident-responsibility`

## 现状与缺口（2026-11-22 实测）

已有能力（ADR-0032，PR #98 已合入）：

- `incidents.owner` 字段；`GET /api/v1/copilot/incidents/assignees` 只列本租户启用用户；
- `PATCH owner` 校验存在性/启用/租户，跨租户 400；
- 结单/重开/改负责人/改优先级仅 `created_by`、当前 `owner` 或 `role=platform`；
- 每次 `assign` 落 `ev=incident action=assign` 审计。

缺口（本切片要补的）：

1. **没有转交记录**：改 `owner` 是就地覆盖，查不出"谁在什么时候把单子交给谁、为什么"。
   审计行只有 `action=assign` 与操作人，没有 from/to/理由。
2. **没有"我的"视图**：`IncidentStore.list` 只按 `(tenant, updated_at)` 取最近 60 条，
   前端左栏是"本租户全部"。值班人无法只看自己负责的单。
3. **没有时限**：事故没有 due 时间，也没有任何超时提醒；优先级只是标签。
4. **没有站内提醒**：分配发生后，被分配人只有在下次刷新列表时才知道。

## 判定口径

- 「转交」= 一次带 `from → to` 与理由的责任变更，是**事实**，必须 append-only，不能靠时间线快照。
  时间线是客户端整段覆盖写（ADR-0032 已记该边界），拿它当责任凭证会在并发下丢行。
- 「SLA」= 按优先级给出的**响应时限**。本切片只在**界面显示**（右栏事实 + 左栏排序）
  与**审计留痕**，不做主动推送：**不做 IM/邮件/短信，也不做浏览器通知**。
  推送是独立切片，需先定"谁收、频率、静默期"，不能顺手塞进这一条。
- 租户仍是硬隔离维度：tenant 只来自 JWT，所有新 SQL 带 `tenant = ?`。

**不做**：IM/邮件/短信推送、升级到上级/值班表、多级审批、跨租户转交、工时统计。

## Allowed paths

```
plans/incident-responsibility-accept.md
src/main/resources/db/migration/V6__incident_responsibility.sql
src/main/java/com/opspilot/incident/**
src/main/java/com/opspilot/config/OpsPilotProperties.java      # 仅新增 Incident/SLA 配置段
src/main/java/com/opspilot/metrics/AuditService.java           # 仅新增 incident 动作词表值
src/main/resources/application.yml                             # 仅新增 opspilot.incident.*
src/main/resources/static/index.html
src/test/java/com/opspilot/incident/**
src/test/java/com/opspilot/auth/FlywayMigrationTest.java       # 仅新增 V6 断言
offline/tests/test_console_contract.py
docs/CONTEXT.md
docs/adr/0033-incident-responsibility.md
docs/repo-map.md
README.md
offline/doc_numbers.json                                       # 仅当测试计数变化时同步
```

## Acceptance criteria

1. **迁移**（起草时为 V6；按实际合入顺序顺延，冻结时确认最终版本号）：
   新建 `incident_assignments`（append-only）：
   `id BIGINT AUTO_INCREMENT PK / tenant / incident_id / from_owner / to_owner / reason /
   assigned_by / assigned_at`；索引 `(tenant, incident_id, assigned_at)`。
   `incidents` 增加 `due_at TIMESTAMP NULL` 与 `sla_state VARCHAR(16) NOT NULL DEFAULT 'ok'`
   （`ok|due_soon|breached`）。`FlywayMigrationTest` 在空库与已有 V5 库上均通过。
2. **转交是唯一写入口**：新增 `POST /api/v1/copilot/incidents/{id}/transfer`，
   body `{toOwner, reason}`。语义与 `PATCH owner` **分离**：
   - `PATCH owner` 保留（兼容），但内部改为走同一转交路径，`reason` 为空时写 `—`；
   - 转交在**同一事务**内写 `incidents.owner` 与 `incident_assignments` 一行，二者要么都成功要么都不写；
   - `toOwner` 必须是本租户启用用户，否则 400；与当前 owner 相同则 409（不做无意义写入）；
   - `reason` 上限 200 字，超长 400。
3. **权限不变**：转交沿用既有责任动作授权（`created_by` / 当前 `owner` / `platform`），
   其他人 403 并落 `ev=incident action=transfer outcome=forbidden`。**不新增角色**。
4. **转交历史可读**：`GET /api/v1/copilot/incidents/{id}/assignments` 返回本单的转交记录
   （倒序，最多 50 条），字段 `fromOwner/toOwner/reason/assignedBy/assignedAt`。
   跨租户返回 404，与既有 `GET /{id}` 同口径。
5. **"我的"筛选在服务端**：`GET /api/v1/copilot/incidents?mine=true` 只返回
   `owner = 调用者 sub` 的记录；`?status=open|closed` 可叠加。无参数时行为与今天完全一致
   （本租户最近 60 条）。非法 `status` 400。
   前端 `refreshIncidents()` 现有的"保留本地更新更晚的记录 + localOnly 回填"合并逻辑
   在 `mine=true` 时**必须关闭 localOnly 回填**，否则刚被转交出去的本地单会重新出现在
   「我的」里（筛选形同虚设）。这一条要有回归用例。
6. **SLA 由优先级派生，且可配置**：默认 `P1=30m / P2=2h / P3=8h / 空优先级=不设时限`，
   全部来自 `opspilot.incident.sla.*` 配置，不写死在代码里。
   `due_at` 在**创建**与**改优先级**时重算；优先级清空则 `due_at=null`。
7. **SLA 提醒幂等**：新增 `@Scheduled` 扫描（默认 60s，可配），把
   `now > due_at - 20%窗口` 置 `due_soon`、`now > due_at` 置 `breached`，
   每次状态跃迁落一条 `ev=incident action=sla outcome=due_soon|breached` 审计。
   **同一状态不重复落审计**；已结单事故不参与扫描。
   单实例内进程内扫描，不加分布式锁（与现有 SingleFlight 同边界，写进 ADR 后果）。
8. **前端可见**：左栏筛选增加「我的」（走服务端 `?mine=true`，不是前端过滤）；
   事故单右栏事实区显示 `时限 / 剩余`，`due_soon` 与 `breached` 用既有 warn/err 语义色；
   页头操作区新增「转交」，弹层含负责人选择与理由输入，并列出最近 3 条转交历史。
   转交失败必须 toast 明示，不得静默。
9. **不回归**：`localStorage` 仍只保留 `opspilot.incidents.active`；admin 门禁、
   L1 默认关闭、只读命令逐字、拒答/截断判定不变；面板契约十层一致；
   `check_public_copy.py --check` 与 `doc_numbers.py --check` 通过。

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

Playwright（Edge，临时脚本，验完即删）：

1. `sre-full` 建单 → 转交给 `sre-limited`（填理由）→ 右栏「负责人」与转交历史同步更新；
2. 刷新页面 → 转交记录仍在（不是前端内存）；
3. `sre-limited` 登录 → 左栏「我的」只剩这一单；「全部」仍能看到同租户其他单；
4. `sre-limited` 再转交回 `sre-full` → 成功；`sre-limited` 转交给不存在用户 → 400 且有 toast；
5. 把优先级从 P1 改为 P3 → 右栏剩余时限随之变长；
6. 直接 `PATCH` 一个已结单事故的 `owner` → 409（结单冻结不回归）。

## Forbidden

- 禁止改/删/弱化任何断言换通过；禁止跳测试不记录。
- 禁止在 SQL 里拼接 tenant，或信任请求体里的 tenant。
- 禁止用客户端时间线充当转交凭证；转交历史只能来自 `incident_assignments`。
- 禁止把 SLA 阈值写进代码常量或前端；必须走配置。
- 禁止新增 IM/邮件推送通道（本切片明确不做）。
- 禁止 `git add -A`；合并时只暂存 Allowed paths。

## 回滚

单分支交付。V6 只新增表与两个可空/有默认值的列，不修改既有列语义；
失败时 `git revert` 本分支提交即可，旧代码读不到新列不影响启动。

## 人审签收

**状态：待签收。** 签收前不得开始实现；实现者与 Verifier 不得为同一会话。
