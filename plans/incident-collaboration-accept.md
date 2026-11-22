# 验收契约：事故单团队协作（评论与 @）

> 来源：2026-11-22 用户指令「接着按这个顺序起契约」——第 2 条：
> 诊断结果的团队协作（评论与 @）。
> **状态：已冻结（2026-11-22）。** 冻结点 `636bb5c`；实现分支 `codex/incident-collaboration`。
> 用户已确认：评论**完全不可变**（不做作者撤回），依赖第 1 条 `incident-responsibility` 的语义。

## Task ID

`incident-collaboration`

建议分支：`codex/incident-collaboration`

## 现状与缺口（2026-11-22 实测）

已有能力：

- 同租户成员可写 `diagnosis` / `timeline`（ADR-0032），但**都是整段覆盖写**；
- `timeline` 最多 80 条，超出从头部截断（`addTimeline`）；
- 事故单没有"人对人说话"的位置：想补一句"这条命令我执行过了，连接数已回落"，
  只能塞进时间线或结单备注。

缺口：

1. **没有独立于时间线的协作记录**。时间线是客户端合并后整段 PATCH，
   两个协作者同时补记录时**后写赢**（ADR-0032 已登记该边界），拿它当讨论区会丢话。
2. **没有 @**：无法把一条补充指给具体的人。
3. **没有未读**：被 @ 的人只能靠翻事故单发现。

## 判定口径

- 「评论」= 挂在事故单上的**追加式**记录，服务端 insert-only，不由客户端整段覆盖。
  这是与时间线的根本区别：时间线是**状态快照**，评论是**对话事实**。
- 「@」= 在评论正文里点名**同租户启用用户**，落库时解析成 `mentions` 数组。
  解析失败（不存在/跨租户/已禁用）→ 400 并点名，**不静默丢弃**（否则被 @ 的人永远不知道）。
- **本切片不把评论喂给检索/LLM**。系统仍是单轮无会话（ADR-0007）：
  评论是给人看的，不是新的上下文来源。违反这条会让"可溯源"变成"可被同事的话污染"。
- 提醒只做**站内计数**（左栏未读角标 + 事故单内高亮），**不做推送**。

**不做**：评论编辑/删除、富文本、附件、表情回应、线程回复、IM 推送、跨租户 @。

## Allowed paths

```
plans/incident-collaboration-accept.md
src/main/resources/db/migration/V7__incident_comments.sql
src/main/java/com/opspilot/incident/**
src/main/resources/static/index.html
src/test/java/com/opspilot/incident/**
src/test/java/com/opspilot/auth/FlywayMigrationTest.java       # 仅新增 V7 断言
offline/tests/test_console_contract.py
docs/CONTEXT.md
docs/adr/0034-incident-comments.md
docs/repo-map.md
README.md
offline/doc_numbers.json                                       # 仅当测试计数变化时同步
```

## Acceptance criteria

1. **迁移**（起草时为 V7；按实际合入顺序顺延，冻结时确认最终版本号）：
   新建 `incident_comments`：
   `comment_id BIGINT AUTO_INCREMENT PK / tenant / incident_id / author / body /
   mentions_json CLOB / created_at`；索引 `(tenant, incident_id, created_at)`。
   `FlywayMigrationTest` 在空库与已有库上均通过。
2. **追加语义**：`POST /api/v1/copilot/incidents/{id}/comments`，body `{body, mentions?}`。
   每次请求只 **INSERT 一行**，不读改写既有行；同一事故并发 10 条评论必须 10 条都在
   （用并发测试证明，而不是"看起来没问题"）。
3. **读取**：`GET /api/v1/copilot/incidents/{id}/comments?since=<commentId>` 返回
   `{comments:[...], nextSince}`，按 `created_at, comment_id` 升序，单次上限 100。
   跨租户 404，与既有 `GET /{id}` 同口径。
4. **正文约束**：`body` 去首尾空白后 1..2000 字，空或超长 400；
   单事故评论上限 200 条，超出 409 并提示"请在结单备注里汇总"。
5. **@ 解析**：`mentions` 以正文里的 `@用户名` 为准由**服务端解析**，客户端传的数组只作提示、
   不作为授权依据；被点名者必须是本租户启用用户，否则 400 并返回具体用户名。
   同一评论内去重，最多 10 人，超出 400。`mentions` 落库为 JSON 数组。
6. **权限**：任何同租户、`authLevel>=1` 的用户都可评论（与"协作者可写诊断"同口径）；
   不需要 `created_by` / `owner` / `platform`。越权（跨租户）一律 404，不泄漏存在性。
   评论**不改变**事故状态、负责人、优先级，也不写 `timeline`。
7. **不可变**：本切片不提供编辑/删除端点；直接调用不存在的 `DELETE` 必须 404/405，
   不得被通用兜底变成"静默成功"。
8. **前端**：事故单在「诊断分析」之后、处置记录之前新增「讨论」卡片：
   倒序显示最近 20 条，自己的评论与 @ 到我的评论有明确区分；
   输入框支持 `@` 补全（数据源复用 `GET /assignees`）；发送失败 toast 明示且**保留草稿**。
   左栏事故单条目显示未读角标（本会话内基于 `commentId` 水位，不新增服务端已读表）。
9. **不回归**：评论**不得**进入检索、LLM、指纹、缓存键；`localStorage` 仍只存
   `opspilot.incidents.active`（未读水位用 `sessionStorage`，键名 `opspilot.comments.seen`）；
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

Playwright（Edge，临时脚本，验完即删）：

1. `sre-full` 在事故单里发一条含 `@sre-limited` 的评论 → 立即出现在「讨论」；
2. 刷新页面 → 评论仍在，顺序与时间正确；
3. `sre-limited` 登录 → 该事故单有未读角标；打开后角标消失；能发评论；
4. `sre-limited` 发 `@不存在的人` → 400，输入框内容**仍在**，有明确 toast；
5. 同租户第三个用户（若未播种则用 API 造）不能改事故元数据，但能评论（权限未被放宽）；
6. 跨租户账号 `sre-acme` 直接 `GET` 该单评论 → 404；
7. 检索回归：评论内容里的独特字符串不出现在任何诊断答案或引用里。

## Forbidden

- 禁止改/删/弱化任何断言换通过；禁止跳测试不记录。
- 禁止把评论拼进 query、prompt、指纹或缓存键。
- 禁止用时间线承载评论（会回到"后写赢"并丢话）。
- 禁止为评论新增编辑/删除/软删；需要撤回时下一轮单独设计。
- 禁止新增推送通道或浏览器通知。
- 禁止 `git add -A`；合并时只暂存 Allowed paths。

## 回滚

单分支交付。V7 只新增一张表，不修改既有表；失败时 `git revert` 即可，
旧代码不读该表，启动与既有功能不受影响。

## 人审签收

**状态：待签收。** 签收前不得开始实现；实现者与 Verifier 不得为同一会话。
