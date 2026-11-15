# 验收契约：PR #75 反馈质量闭环（指标出口 + 指纹级复核队列）

> 按 `docs/验收链路规范.md` 走完整链路。判定：**必须走**——本 PR 改动对外 API 行为
> （`POST /api/v1/copilot/feedback` 新增指标出口、拒绝路径语义收紧）与内部指标面
> （`/actuator/prometheus` 新增 `aiops.feedback` 序列），并新增一条人机协同处置链路
> （复核队列），属"业务逻辑 / 内部 API"档。
>
> 生成与裁决分离：本契约由实现方冻结，**不含新实现**（实现已在分支上，2026-11-05）。
> 裁决由**全新会话**的 `verifier` subagent 完成（tools 限定 Read/Grep/Glob/Bash），
> 验证命令由 Verifier 亲自重跑，实现方贴的日志与自评不构成证据。合并前须人审四问签收。

## Task ID

`feedback-loop-accept`

## 证据绑定（契约冻结时）

- **main tip**：`2154727ef1989db5928eba306d22856640224570`（#80 合入后）
- **PR head（冻结时）**：`dc06129a093025bbe2f720277c0ce0c901c975b4`（`feat/feedback-loop`，
  已合入 main 至 `2154727`，GitHub mergeable、CI 11 job 全绿）
- **验证目标 SHA**：本契约落地提交之后的 `feat/feedback-loop` head——
  `git log -1 --format=%H -- plans/feedback-loop-accept.md` 即为 Verifier 须绑定的 HEAD SHA。
  证据必须写"HEAD SHA + `git status --short`"，且 worktree 干净。
- **冻结时 `git status --short`**：仅 4 项未跟踪（`.claude/worktrees/`、`.dsh/`、
  `求职投递准备计划.md`、`简历项目经历草案.md`），与本任务无关；验证须在独立干净 worktree 中进行。

## 变更面（冻结时实测：11 个文件）

```
.gitignore
README.md
docs/ops/feedback-loop.md
docs/ops/roadmap.md
docs/repo-map.md
offline/feedback_review.py
offline/tests/test_feedback_review.py
src/main/java/com/opspilot/gateway/FeedbackController.java
src/main/java/com/opspilot/metrics/OpsMetrics.java
src/test/java/com/opspilot/gateway/FeedbackControllerTest.java
src/test/java/com/opspilot/metrics/OpsMetricsTest.java
```

## Allowed paths

- `plans/feedback-loop-accept.md`（本契约；冻结后不得再改）
- 验证过程的临时产物**只许**落在 `offline/staging/`（`.gitignore` 覆盖）或系统临时目录，**不入库**
- **实现/测试/文档零改动**：本契约是纯验证契约，任何为让判据通过而修改文件的动作都触发 Forbidden

## Acceptance criteria

**AC-1 变更面封闭。** `git diff --name-only origin/main...HEAD` 恰为 12 个文件
（上述 11 个 + 本契约），多一个或少一个都 FAIL。

**AC-2 指标契约（0 基线 / tag 收口 / 不进面板）。**
`src/main/java/com/opspilot/metrics/OpsMetrics.java`：
(a) `aiops.feedback` 在构造时以 `verdict=up`、`verdict=down` **两个 tag 值预注册**（0 基线）；
(b) `feedback(String)` 对 `up`/`down` 之外的任何值（含 `null`）**静默忽略，不新建 tag**；
(c) `snapshot()` 不含任何 feedback 键。抓取体（Prometheus 文本格式）中必须同时出现
`verdict="up"` 与 `verdict="down"` 两条序列。证据：`OpsMetricsTest` 两条新测试
（`feedbackCountersArePreregisteredAndTagBounded`、`prometheusScrapeShowsBothFeedbackBaselines`）
与代码审查点。

**AC-3 审计与指标同生同死。** `FeedbackController`：
(a) 正常路径先 `audit.logFeedback` 后 `metrics.feedback`（审计是事实、指标是派生，顺序不得反）；
(b) 无身份 / `auth_level<1` 的 403 路径、`fingerprint` 非法的 400 路径，
**审计与指标两个出口都不得落痕**（`verifyNoInteractions` 双向）。证据：`FeedbackControllerTest`
5 条测试。

**AC-4 指标只进 Prometheus，不进面板契约。** `bash scripts/check_panel_contract.sh` 十层一致；
`/state` 的 snapshot 键集未因本 PR 变化（AC-2(c) 的另一面）。

**AC-5 复核队列判据。** `offline/feedback_review.py` 与 `offline/tests/test_feedback_review.py`
（23 条测试）全过，且测试必须钉子以下口径（逐条核对测试存在且断言有效，不只数条数）：
(a) 只有 up 票的指纹**不进**队列；(b) 未知 verdict 只计数、不建桶；
(c) 无 `fp` 的反馈行不被塞进 unknown 桶；(d) P1（同指纹 down≥2）/ P2（作答被否）/
P3（仅拒答被否）分档正确；(e) `ev=chat` 上下文缺失时**显式写缺失**而不是留白；
(f) 告警台账富化（`alert_id` / `ref_docs`）；(g) 可疑文档按判负票数聚合且报表明说
"相关性排序，非根因判定"；(h) 坏行容忍与轮转片拼合；(i) "空队列"（退 0）与
"审计无任何反馈"（退 2）区分；(j) `--json --stdout` 形态；(k) `--stdout` 不落盘；
(l) 表格转义；(m) 确定性（同输入重跑内容一致）。

**AC-6 不自我循环（红线）。** 代码**不写语料、不改排序/权重、不按 `request_id` 归因**；
`docs/ops/feedback-loop.md` 的「不做的事」四条在；`.gitignore` 覆盖 `offline/staging/`
（复核队列是派生报表、含审计原文，不入库）。

**AC-7 全量测试。** `mvn -o test`：**440 passed / 0 failed / 0 error / 0 skipped**；
`python -m pytest offline/tests -q`：**561 个 `def test_`（参数化展开 621 例）全过**。
红队 job（`scripts/redteam.sh` 或 CI `redteam` job）绿。

**AC-8 文档门闩。** `python offline/doc_numbers.py --check` 退 0（Java 440 / Python 561 与产物
现算一致）；`python scripts/check_public_copy.py` OK；`bash scripts/check_panel_contract.sh` OK。

**AC-9 CI 状态（辅助证据）。** PR #75 head 对应 CI run 的 11 个 job 全 pass
（java / static-gate / panel-contract / provenance / python / shell / security / sbom / smoke /
benchmark / redteam）。CI 状态只作辅助，主证据是 Verifier 本地亲跑。

**AC-10 证据绑定。** 证据包写明 HEAD SHA（= 契约落地后的分支 head）与验证时
`git status --short`（须为空），且注明 worktree 路径。

## Verification commands（Verifier 亲自重跑，禁止只引用实现方日志）

> 必须用**独立干净 worktree**（共享工作区会被并发会话污染——2026-11-09 #77 验证已实证一次）。

~~~bash
git fetch origin
git worktree add /tmp/opspilot-verify-75 origin/feat/feedback-loop
cd /tmp/opspilot-verify-75

# 证据绑定（AC-10）
git rev-parse HEAD
git log -1 --format=%H -- plans/feedback-loop-accept.md   # 须等于上面的 HEAD
git status --short                                         # 须为空

# AC-1 变更面
git diff --name-only origin/main...HEAD | sort
git diff --name-only origin/main...HEAD | wc -l            # 须为 12

# AC-2 / AC-3 / AC-5 / AC-7（Java 440）
source scripts/java_home.sh
/e/java/apache-maven-3.9.14/bin/mvn -o test 2>&1 | tail -20   # 须 440 passed, 0 failed

# AC-7 / AC-5（Python）
python -m pytest offline/tests -q                          # 须全过（621 例）
python -m pytest offline/tests/test_feedback_review.py -q  # 23 条
grep -c "^def test_" offline/tests/*.py | awk -F: '{s+=$2} END {print s}'   # 须为 561

# AC-8 门闩
python offline/doc_numbers.py --check
python scripts/check_public_copy.py
bash scripts/check_panel_contract.sh

# AC-2 代码审查点（逐条读，不只看测试名）
grep -n "aiops.feedback\|feedbackUp\|feedbackDown" src/main/java/com/opspilot/metrics/OpsMetrics.java
grep -n "feedback" src/main/java/com/opspilot/metrics/OpsMetrics.java | grep -i snapshot || echo "snapshot 无 feedback 键（预期）"
grep -n "logFeedback\|metrics.feedback" src/main/java/com/opspilot/gateway/FeedbackController.java

# AC-6 红线
grep -n "不写语料\|不改排序\|request_id" docs/ops/feedback-loop.md
grep -n "staging" .gitignore

# AC-5(i) 空审计退 2（用显式空目录，避免读到本机 logs）
mkdir -p /tmp/empty-audit
python offline/feedback_review.py --audit /tmp/empty-audit --stdout >/dev/null 2>&1; echo "exit=$?"   # 须为 2
# 跑完后 git status --short 仍须为空（证明未写语料/未落盘入库）

# AC-9 CI（辅助）
gh pr checks 75
~~~

## Forbidden

- 禁止为使判据通过而修改任何实现 / 测试 / 文档（契约落地后 `plans/feedback-loop-accept.md` 也冻结）；
- 禁止改 / 删 / 弱化断言换通过；禁止跳过测试且不记录；禁止关 lint / typecheck 消警；
- 禁止实现方自宣通过——Verifier 结论必须来自亲自重跑的命令输出；
- 禁止提交任何凭据、token 或审计原文；`offline/staging/` 产物一律不入库；
- 禁止在共享工作区验证（须独立干净 worktree）；禁止引用实现方转述的日志当证据。

## 处置路径（人审签收后执行）

判据全过 → 人审四问无异议 → 书面签收 → squash 合并 PR #75（保留分支）
→ 另起一次 `chore(accept)` 提交，把签收记录追加到本契约末尾（不改判据）。
任一条 FAIL → 退回实现方，契约不变，修复后从同一契约重验。
