# 验收契约：事故持久化 + 429 Retry-After 业务文案

> 来源：2026-11-20 用户指令「把事故从 localStorage 迁到后端表（真正多端可见、可分配），
> 以及补 429 Retry-After 的业务文案」。按 `docs/验收链路规范.md` 走链路。
> **契约冻结时间**：2026-11-20，冻结点 `415a811`（main）。实现分支 `codex/incident-persistence`。

## Task ID

`incident-persistence`

## 背景与判定口径

当前「事故」只存在于浏览器 `localStorage`：换设备不可见、无法分配、清缓存即丢。
本轮把它升级为服务端业务对象，并让 429 的等待时间从"猜一句话"变成服务端给出的真实数值。

**不做**：多轮会话、事故删除、跨租户共享、通知/工单系统对接。
**不允许**：用前端兜底逻辑伪造服务端能力（例如本地补一个 ID 冒充服务端记录）。

## Allowed paths

```
plans/incident-persistence-accept.md
src/main/resources/db/migration/V5__incidents.sql
src/main/java/com/opspilot/incident/**
src/main/java/com/opspilot/auth/AuthService.java
src/main/java/com/opspilot/gateway/AuthController.java
src/main/java/com/opspilot/store/KvStore.java
src/main/java/com/opspilot/store/MemoryKvStore.java
src/main/java/com/opspilot/store/RedisKvStore.java
src/main/java/com/opspilot/auth/UserStore.java          # 仅新增同租户可分配用户查询
src/main/java/com/opspilot/metrics/AuditService.java   # 仅新增 ev=incident 辅助写入器
src/main/resources/static/index.html
src/test/java/com/opspilot/incident/**
src/test/java/com/opspilot/auth/AuthServiceTest.java
src/test/java/com/opspilot/gateway/AuthControllerTest.java
src/test/java/com/opspilot/store/MemoryKvStoreTest.java
src/test/java/com/opspilot/store/RedisKvStoreTest.java
offline/tests/test_console_contract.py
offline/tests/test_public_copy.py
scripts/check_public_copy.py                         # 别名以 canonical 为前缀时先抹别名再判
scripts/check_panel_contract.sh                        # 仅把新 Controller 纳入路径契约
docs/CONTEXT.md
docs/adr/0032-incident-persistence.md
docs/design-mockups/**                           # 设计资产（HTML/PNG）随前端重构带入；本切片仅同步 README 描述
docs/repo-map.md
README.md
```

## Acceptance criteria

1. **表与索引**：`V5__incidents.sql` 建立 `incidents`，含
   `incident_id/tenant/status/owner/created_by/priority/created_at/updated_at/closed_at/closed_by/close_note`、
   `diagnosis_json/timeline_json`（CLOB）与 `client_id`；索引覆盖 `(tenant, updated_at)`、`(tenant, status)`、
   唯一 `(tenant, client_id)`。`FlywayMigrationTest` 通过。
2. **租户硬隔离**：所有 SQL 带 `tenant = ?`，tenant 只来自 JWT；跨租户 `GET/PATCH` 返回 404，
   不泄漏"该 ID 存在但无权"。列表只返回本租户。
3. **创建幂等**：`POST` 带 `clientId`（前端 UUID）；同租户重复提交返回同一条记录（200），
   不产生第二条。缺 `clientId` 时服务端生成，不做客户端信任。
4. **分配是真实关系**：`GET /api/v1/copilot/incidents/assignees` 只返回本租户未禁用用户；
   `PATCH owner` 必须是本租户用户，否则 400；分配后另一个会话可见。
5. **改状态的权限**：`close/reopen/reassign/priority` 仅 `created_by`、当前 `owner` 或
   `role=platform` 可做；其余同租户成员只能更新 `diagnosis/timeline`（协作），越权 403 且留审计。
   `closeNote` 属于结单动作，同权限；已结单记录除单独提交 `{"status":"open"}` 外全字段冻结。
6. **前端不再用 localStorage 存业务数据**：仅保留 `opspilot.incidents.active` 记住当前选中项；
   登录后从 API 拉取；写入失败必须 toast 明示，不得静默成功。
7. **多端一致**：同一账号在两个浏览器上下文登录，A 建单/诊断/结单/分配后，B 刷新可见同一状态。
8. **429 语义**：
   - 登录锁定（5 次/15 分钟）响应带 `Retry-After` 秒数，取自失败计数键的真实剩余 TTL；
   - 并发准入 429 已带 `Retry-After`，本轮把该值透传到 UI；
   - 前端文案显示具体"约 N 分 M 秒后重试"；无 header 时只写"请稍后重试"，**不得编造秒数**。
9. **不回归**：admin 门禁、L1 默认关闭、只读命令逐字展示、拒答/截断状态判定不变；
   `scripts/check_panel_contract.sh` 十层一致；`check_public_copy.py --check` 通过。

## Verification commands（Verifier 亲自重跑）

```bash
cd "D:/OpsPilot — AIOps"
git rev-parse HEAD && git status --short          # 证据绑定
E:/java/apache-maven-3.9.14/bin/mvn.cmd -o test   # 全绿
PYTHONUTF8=1 python -m pytest offline/tests -q    # 全绿
E:/Git/bin/bash.exe scripts/check_panel_contract.sh
python scripts/check_public_copy.py --check
python offline/doc_numbers.py --check
```

Playwright（Edge，临时脚本，验完即删）：

1. 两个 context 用 `sre-full` 登录 → A 建单并完成诊断 → B 刷新看到同一 `incident_id` 与结论；
2. A 把负责人改为 `sre-limited` → B 刷新看到 owner 变化；
3. A 结单 → B 刷新为「已结单」；
4. 新 context 用 `sre-limited` 打开同一事故 → 可读，尝试结单 → 403 且 UI 有明确提示；
5. 直接 `POST /api/v1/auth/login` 错 5 次 → 第 6 次 429 且 `Retry-After` 为 1..900 的整数；
6. 前端登录锁定文案包含剩余分钟/秒；
7. `localStorage` 中不存在事故数组键，只有 active id。

## Forbidden

- 禁止改/删/弱化任何断言换通过；禁止跳测试不记录。
- 禁止在 SQL 里拼接 tenant 或信任请求体里的 tenant。
- 禁止为通过判据而放宽 admin/L1/只读策略。
- 禁止把 JWT、口令、DB 密码写进代码、测试或文档。
- 禁止引入新的外部依赖或新的错误响应形状（沿用 `ErrorBodies`）。

## 回滚

单分支交付；若验证失败，`git revert` 本分支提交即可。V5 只新增表，不修改既有表。

## 人审签收

**状态：Lead 已书面签收（2026-11-20）。** 签收人：shing26；签收语：“签收 incident-persistence”。

### Verifier 证据包

- **独立 Verifier**：`/root/verify_incident_final`，两轮复验，最终裁决 **PASS**。
- **绑定**：HEAD `415a81180cee010ed8f0a073b1fc74e575bdca40`；
  `git status --short` 29 行；`git diff | git hash-object --stdin`
  验证前后均为 `182068aad1ff36d6a4e511d9142b07a9f84cb676`。
- **命令**：`mvn -o test` 505/0/0/0；`pytest offline/tests` 658 passed；
  面板契约十层一致；公开文案 8 canonical 零泄漏；文档数字 33 条一致。
- **运行态**：API 矩阵 32/32；Playwright 15/15；`pageerror=0`；
  隔离实例端口已停止、临时 H2 副本已删除；主库与 `users.trace.db` 未被写。
- **P1 关闭**：首轮 5 项 + 次轮 1 项（重开夹带 timeline）全部有复现与回归证据；
  次轮重开实测首个 PATCH 仅为 `{"status":"open"}`，状态变 `open`、timeline 含 `reopened`。

### 人审四问（Lead 签收即表示无异议）

1. **证据充分性**：无异议——两轮独立 Verifier 亲跑；实现方日志与自评未作为证据。
2. **判据纪律**：无异议——未改/删/弱化断言；移动端 `.ref-badge` 断言恢复并实测 12px；
   `closeNote` 越权与结单冻结均有新增回归；`Skipped: 0`。
3. **变更面封闭**：无异议——19 个已跟踪修改均在 Allowed paths；
   `docs/design-mockups/**` 已由 R1 披露；`.claude/agents/`、`.dsh/`、根目录两份中文 md
   为预存噪声，合并时不得 `git add -A`。
4. **遗留边界**：接受为已知项——登录锁定第 5 次失败即 429（符合“5 次/15 分钟”）；
   已结单记录选择“全字段冻结、仅可单独重开”的强口径；未做事故删除/通知/跨租户共享。

### 合并授权

已获书面签收，允许提交与开 PR。合并仍按 PR 流程执行；合并时只暂存 Allowed paths，
不要使用 `git add -A`。

## R1 勘误（2026-11-20，两轮独立 Verifier 后）

首轮 Verifier 裁决 **FAIL**，5 个 P1 已修复；第二轮 Verifier 又发现 1 个重开回归。6 个 P1 均已修复并补回归：

1. **`closeNote` 权限绕过**：纳入责任动作权限；协作者改写返回 403，值不变。
2. **切换账号残留跨租户事故**：登录成功先清空 `S.incidents/activeId` 与 `ACTIVE_KEY`；
   `refreshIncidents()` 增加 token 代际校验，飞行中的旧身份响应直接丢弃。
3. **移动端断言被弱化**：`.ref-badge` 恢复 10px 基准，移动端下限恢复 12px，断言重新覆盖该元素。
4. **结单后 API 仍可改元数据**：收紧为“仅显式重开可写”，标题/负责人/优先级/处置结论/诊断/时间线
   在结单后一律 409；重开请求不得夹带其他字段。
5. **设计资产越界**：`docs/design-mockups/**` 补入 Allowed paths，明确为前端重构带入的静态资产，
   不参与事故持久化判据。
6. **已结单事故无法从 UI 重开**：`patchIncident()` 默认夹带 `timelineJson`，与“重开只发 status”
   冲突。重开调用改为 `{content:false}`，先单独 `{"status":"open"}`，成功后再单独持久化时间线；
   补静态回归断言防止再次夹带。

另：登录锁定在第 5 次失败即返回 429（符合“5 次/15 分钟”），比原验收脚本描述的“第 6 次”早一次；
行为不变，以“第 5 次失败起锁定”为准。
