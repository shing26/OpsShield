# 验收契约：门禁 7 覆盖口径改为「只计 up=1 的抓取点」

> 按 `docs/验收链路规范.md` 走完整链路。判定：**必须走**——本契约修改门禁 7 的**判定口径**
> （证据语义），会直接改变生产门禁的结论与历史周报的数字，属"业务逻辑 / 证据口径"档；
> 拿不准按高一档处理。
>
> 生成与裁决分离：本文件先于实现冻结；实现在独立 worktree（`feat/slo-coverage-up1`）；
> 裁决由全新会话的 `verifier` subagent 完成（亲自重跑全部命令）；合并前须人审四问签收。

## Task ID

`slo-coverage-up1-accept`

## 背景事实（契约据此写成，实现方不得凭猜测改动）

1. 现行口径 `count_over_time(up{job="opspilot"}[1d]) >= 240` 统计的是**抓取点个数**：
   `up` 序列无论值为 0 还是 1 都会被计数。2026-11-16 实测：网关自 11-09 05:00（UTC）停到
   11-15 16:10（UTC），期间 Prometheus 仍每 15s 抓一次（`up=0`），生成的
   `docs/ops/slo-weekly-2026-11-15.json` 把 **7/7 天全判为覆盖**——一个全程宕机的系统也能
   凑满门禁 7 的 30 天。这是假绿风险，Lead 决定改为只计 `up=1`。
2. `up` 的取值只有 0/1，因此 `sum_over_time(up[1d])` 即"当天 up=1 的抓取点数"，
   无需子查询；序列缺失时返回空（`None`），仍按"无数据"判缺失，不得当成 0。
3. 历史周报 `docs/ops/slo-weekly-2026-11-05.{json,md}` 是既有证据，**不重生成、不改写**；
   其旧文案（"个抓取点"）登记为已知残留。只有 2026-11-15 那份在本次重生成。
4. 阈值 240（≈15s 间隔下连续 1 小时）不变；`check_observation.py` 的聚合与断档判据不变。
5. **未完结的 UTC 日不可判定**：Prometheus 对未来的求值时间会钳到"现在"，对窗口末日尚在
   进行的日子求值，得到的是一个仍在增长的瞬时值（2026-11-15 实测：同一查询 179 → 300 → …，
   数小时内可跨越门槛）。故报表只判"已满 24h 的 UTC 日"，末日由 `last_complete_day()` 裁剪（R2）。

## 证据绑定（契约冻结时）

- main tip：`c0b971a`（门禁 6/7 时钟改为系统任务之后）
- 实现分支：`feat/slo-coverage-up1`
- 验证目标 SHA：`feat/slo-coverage-up1` 的 **head**（含实现提交）；契约提交须是它的祖先
  （`git merge-base --is-ancestor <契约提交> HEAD`），两条都给。Verifier 须绑定并给出
  `git status --short`（worktree 干净）。
- 冻结时主工作区 `git status --short`：仅既有 4 项未跟踪，与本任务无关。

## Allowed paths

- `offline/slo_report.py`
- `offline/tests/test_slo_report.py`
- `docs/ops/slo-weekly-2026-11-15.json` / `docs/ops/slo-weekly-2026-11-15.md`（重生成）
- `docs/ops/gate6-7-30day-observation.md`
- `docs/repo-map.md`
- `README.md`（测试计数随新增测试现算更新）
- `plans/slo-coverage-up1-accept.md`（本契约）

**登记为已知残留、不得改动**：`docs/ops/slo-weekly-2026-11-05.json` / `.md`（历史证据，
旧文案保留）。

## Acceptance criteria

**AC-1 覆盖只计 up=1。** `scrape_coverage()` 的逐日查询改为
`sum_over_time(up{job="opspilot"}[1d])`，`>= MIN_SCRAPES_PER_DAY`（240）判覆盖；
空序列（`None`）仍判缺失。代码审查点：查询串、阈值常量未变、`None` 分支保留。

**AC-2 口径可自证。** 周报 JSON 的 `coverage` 增字段 `scrape_metric`，值为
`up{job="opspilot"}==1`；Markdown 门槛文案与缺失 notes 改为"个 **up=1** 抓取点"
（避免下一任读者再把"抓取点在"当成"应用在采"）。

**AC-3 回归测试（改坏必红）。**
(a) 新增：单日总抓取 5760、其中 up=1 仅 100 → 判缺失（旧口径的误判形态被锁死）；
(b) 新增：up=1 恰为 240 → 覆盖；239 → 缺失（边界两侧都断言）；
(c) 新增：断言实际发出的查询串包含 `sum_over_time(up` 且不包含 `count_over_time(up`；
(d) 既有 coverage / 渲染 / main 流程测试随 fixture 更新后全部通过，测试断言不得删弱。

**AC-4 周报重生成（诚实反映停机）。** 窗口 11-09..11-15（UTC）中，11-09~11-14 应用全程不在线
（`up=0`），11-15 应用自 **16:11:15Z** 上线、当日其余时间无断开，日终累计约 **1875** 个
`up=1` 抓取点（≫ 240）。因此重生成后：`coverage.covered == ["2026-11-15"]`、`coverage.missing`
为 11-09~11-14 共 **6** 天；`python scripts/check_observation.py` 门禁 7 报 **1/30 且无断档**
（与历史周报并集后仅 11-15 一天，断档从首个覆盖日起算）；门禁 6 维持 4/24 不变。
重生成必须在 **2026-11-16T00:00Z 之后**执行（见 AC-9：末日未满 24h 不得判定）。
Verifier 用 `--stdout --json` 重跑并与已提交 JSON 的 `coverage.covered` 比对（允许 notes 措辞
差异，不允许集合差异），并记录重跑时点（UTC）以证明窗口末日已完结。

**AC-5 文档与数字同步。** 观察文档与 `docs/repo-map.md` 写明覆盖定义（up=1）；
README 的 Python 测试函数数与实际 `grep -c "^def test_" offline/tests/*.py` 一致；
`python offline/doc_numbers.py --check` 退 0。

**AC-6 全量测试。** `python -m pytest offline/tests -q` 全过（函数数 = 561 + 本次新增 5 条
= 566：覆盖口径 3 条 + 末日防呆 2 条）；
Java 无改动，`mvn -o test` 不作为本契约前置（CI java job 覆盖）。

**AC-7 变更面封闭。** `git diff --name-only origin/main...HEAD` 恰为 Allowed paths 中的
8 个文件（7 实现/文档 + 本契约）；历史周报零改动。

**AC-8 证据绑定。** 证据包写明 HEAD SHA 与干净 worktree 的 `git status --short`。

**AC-9 未完结的 UTC 日不得参与判定（末日防呆）。** 生成前把窗口末日裁到"最后一个已满 24h 的
UTC 日"（`last_complete_day()`）：请求末日未完结时以该日为末日重新取整窗、`window.requested_end`
记原始请求末日、`coverage.notes` 追加一条"末日未完结"说明；请求末日已完结时不裁剪，
`requested_end == end`。回归测试覆盖两侧：UTC 为 11-16T02:00Z 时请求 `--end 2026-11-16` →
实判末日 11-15；UTC 为 11-16T00:05Z 时请求 `--end 2026-11-15` → 不裁剪。
理由：Prometheus 对未来求值时间会钳到"现在"，未完结的末日写进周报后数小时内就会翻转
（2026-11-16 实测：同一命令 20 分钟后 `covered` 从 `[]` 变 `["2026-11-15"]`）。

## Verification commands（Verifier 亲自重跑）

~~~bash
git fetch origin
git worktree add --detach /tmp/opspilot-verify-slo origin/feat/slo-coverage-up1
cd /tmp/opspilot-verify-slo
git rev-parse HEAD
contract=$(git log -1 --format=%H -- plans/slo-coverage-up1-accept.md)
git merge-base --is-ancestor "$contract" HEAD && echo "contract-ancestor-ok"
git status --short                                            # 须为空

# AC-7 变更面
git diff --name-only origin/main...HEAD | sort
git diff --name-only origin/main...HEAD -- docs/ops/slo-weekly-2026-11-05.json docs/ops/slo-weekly-2026-11-05.md | wc -l   # 须为 0

# AC-1/AC-2 代码审查
grep -n "sum_over_time\|count_over_time\|MIN_SCRAPES_PER_DAY\|scrape_metric" offline/slo_report.py
grep -n "up=1\|up{job=\"opspilot\"}==1" offline/slo_report.py

# AC-3 测试
python -m pytest offline/tests/test_slo_report.py -q
python -m pytest offline/tests -q
grep -c "^def test_" offline/tests/*.py | awk -F: '{s+=$2} END {print s}'   # 与 README 一致

# AC-4 周报内容与可重跑性（Prometheus 在 127.0.0.1:9090 运行中，只读查询）
python - <<'PY'
import json, io
d = json.load(io.open("docs/ops/slo-weekly-2026-11-15.json", encoding="utf-8"))
print(d["coverage"]["covered"], d["coverage"]["missing"])
assert d["coverage"]["covered"] == ["2026-11-15"]
assert len(d["coverage"]["missing"]) == 6, d["coverage"]["missing"]
assert d["coverage"]["scrape_metric"] == 'up{job="opspilot"}==1'
assert d["window"]["end"] == d["window"]["requested_end"] == "2026-11-15"
PY
python offline/slo_report.py --kind weekly --end 2026-11-15 --stdout --json > /tmp/weekly-rerun.json
python - <<'PY'
import json, io
a = json.load(io.open("docs/ops/slo-weekly-2026-11-15.json", encoding="utf-8"))["coverage"]["covered"]
b = json.load(io.open("/tmp/weekly-rerun.json", encoding="utf-8"))["coverage"]["covered"]
assert a == b, (a, b)
print("rerun consistent:", b)
PY
python scripts/check_observation.py; echo "exit=$?"    # 须报门禁 7 = 1/30、断档 0、门禁 6 4/24；exit 2
grep -n "last_complete_day\|requested_end\|未完结" offline/slo_report.py

# AC-5 文档门闩
python offline/doc_numbers.py --check
python scripts/check_public_copy.py
git status --short                                            # 仍须为空
~~~

## Forbidden

- 禁止重生成或改写 `docs/ops/slo-weekly-2026-11-05.*`（历史证据）；
- 禁止修改阈值 240、`check_observation.py` 的聚合/断档判据、门禁 6 的任何口径；
- 禁止删 / 弱化既有测试断言，禁止为通过而跳测试；
- 禁止把 `up=0` 的抓取点计入覆盖（本契约的全部意义即此）；
- 禁止提交凭据或审计原文；禁止在共享工作区验证（须独立 worktree）。

## 处置路径

判据全过 → 人审四问 → 书面签收 → squash 合并 PR（保留分支）→ `chore(accept)` 提交追加签收。
任一 FAIL → 退回实现方，从同一契约重验。

## 契约修订记录

### R2（2026-11-16，AC-4 事实前提证伪 + 新增 AC-9；实现侧加末日防呆）

- **触发**：独立 Verifier 判 AC-4 **FAIL**（其余 AC 全过）。复测证实：验证时 UTC 仍为
  **2026-11-15T16:55Z**，窗口末日 11-15 的 UTC 日尚未结束；Prometheus 对未来求值时间钳到
  "现在"，故 `23:59:59Z` 与当前时刻返回同值。原 AC-4 把"生成瞬间的 112 点"当成"末日值"，
  而网关自 16:11:15Z 起持续在线，日终约 1875 点——11-15 **应判覆盖**，已提交的"7 天全缺失"
  在约 20 分钟后被真实数据推翻。原 Verification 内联注释"2/30、断档 5 天"亦为陈旧笔误。
- **修订**：AC-4 期望改为 `covered == ["2026-11-15"]`、`missing` 6 天、门禁 7 = 1/30 无断档，
  并要求重生成发生在 2026-11-16T00:00Z 之后；新增 AC-9（未完结 UTC 日不得判定，末日裁到
  最后一个已满 24h 的 UTC 日并记 `requested_end`）；AC-6 函数数更新为 566。阈值 240、
  `check_observation.py` 判据、门禁 6 口径均未改动。

### R1（2026-11-16，措辞澄清，判据不变）

- **触发**：原"验证目标 SHA = 契约落地提交"与"契约先冻结、实现后提交"顺序冲突——
  契约提交是分支 HEAD 的父提交，二者不可能相等。实现方在派 Verifier 前发现并修正。
- **修订**：验证目标改为分支 **HEAD**（含实现提交）；契约提交须为其祖先
  （`git merge-base --is-ancestor`）。AC-1…AC-8 全部判据、阈值、期望集合均未改动。
