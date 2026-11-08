# 验收契约：PR #76（门禁 6/7 证据工具化）——关闭处置

> 按 `docs/验收链路规范.md` 走链路。**特殊情况**：#76 的三个 commit 内容已通过 #77
> （squash `eac0c5e`）逐字节进入 main——对 merge-base `cb9cdc5` 的内容 diff 在 main 上为空
> （实现方 2026-11-09 盘点；Verifier 须独立复核此祖先关系，不采信本句）。
> 因此本契约验证的不是"代码是否该合"，而是**"关闭 #76 后 main 完整、门禁 6/7 工具链在 main 上达标"**。

## Task ID

`gate67-tooling-accept`

## 证据绑定

- PR #76 head：`origin/feat/gate67-observation-tooling`（tip `8910e42`）
- main：`origin/main`（tip `eac0c5e`，含 #77）
- merge-base：`cb9cdc5`

## Acceptance criteria

1. **内容包含关系成立**：#76 分支相对 merge-base 触碰的 15 个文件，其内容与 main 完全一致
   （`git diff origin/main origin/feat/gate67-observation-tooling -- <file>` 为空），
   或差异仅为 #77 之后的演进（Java 测试计数 428→429，main 值更新）。
2. **工具链在 main 上可用**：`offline/slo_report.py`、`scripts/check_observation.py`、
   `scripts/live_concurrency.py`（schema 2）存在于 main 工作区且测试通过。
3. **门禁 6/7 判定口径在 main 上自洽**：`judge_sample` 对"无 429"判 UNPROVEN 非 FAIL
   （c4 档预期）；`check_observation.py` 对空目录退 2、对合成断档退 1（由既有测试覆盖）。
4. **证据产物完整**：三份 live 样本（c4/c8/c20）与首份周报（slo-weekly-2026-11-05）在 main 上，
   且样本关键字段（retry_after_ok、degradation.level）与周报判定一致。
5. **关闭 #76 不丢内容**：`gh pr close 76` 后，main 无需任何补丁提交。

## Verification commands（Verifier 亲自重跑）

~~~bash
git fetch origin
git merge-base --is-ancestor cb9cdc5 origin/main && echo MB-OK   # merge-base 在 main
git diff origin/main origin/feat/gate67-observation-tooling --name-only   # 须恰为 13 文件
# 逐文件内容核对（判据 1）：差异须仅出在 README.md / docs/repo-map.md 且为 428↔429 计数
git diff origin/main origin/feat/gate67-observation-tooling
git show origin/main:offline/slo_report.py | head -1   # 判据 2：文件在
git show origin/main:scripts/check_observation.py | head -1
grep -c "schema.*2\|retry_after_s" scripts/live_concurrency.py   # schema 2 在 main
cd offline && python -m pytest tests/test_slo_report.py tests/test_check_observation.py tests/test_live_concurrency.py -q   # ≥90 passed
python -m pytest tests -q   # 全量 527
cd .. && bash scripts/check_panel_contract.sh
# 判据 4：样本与周报一致
python - <<'PY'
import json
for c in (4, 8, 20):
    d = json.load(open(f"docs/ops/live-concurrency-2026-11-05-c{c}.json"))
    assert d["schema"] == 2 and d["degradation"]["level"] == "L0"
    print(c, d["load"]["rejected_429"], d["load"]["retry_after_ok"])
w = json.load(open("docs/ops/slo-weekly-2026-11-05.json"))
assert [s["verdict"] for s in w["samples"]].count("PASS") == 2
print("weekly consistent")
PY
git status --short   # 证据绑定
~~~

## Forbidden

- 禁止为使判据通过而修改任何文件（本契约是纯验证 + 关闭处置，实现已冻结）；
- 禁止在未复核祖先关系前引用实现方盘点结论；
- 禁止提交任何凭据；
- 关闭 #76 时不删除分支上任何 commit（历史保留）。

## 处置路径（人审签收后执行）

判据全过 → `gh pr close 76 --comment`（说明内容已经 #77 进入 main，附本契约路径）→ 不删分支。

## 人审签收（2026-11-09）

- 问题 1–3：Verifier 证据包判定满足（命令全亲跑退 0、六判据 PASS 实证、Forbidden 零触发）；
  其对并发会话污染的当场检测与干净 worktree 重跑作废，方法论合规。
- 问题 4：无新遗留；处置路径（close 不合并不删分支）本身经签收确认。
- 签收人：Lead（shing26）。签收即授权：关闭 PR #76，附契约说明，不删分支。
