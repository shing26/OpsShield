# 验收契约：PR #77（L1 面板确认动线活体验收 + 两缺口修复）

> 按 `docs/验收链路规范.md` 走完整链路。生成与裁决分离：实现已在本分支完成（非本会话重做），
> 本契约冻结后由独立 Verifier 重跑全部验证命令。合并前须人审签收。

## Task ID

`l1-panel-confirm-walkthrough-accept`

## 证据绑定

- HEAD SHA：`886672daf151c08b0ccd1a46544c6d18320fe5c7`
- 分支：`feat/l1-panel-confirm-walkthrough` → PR #77（base: main）
- CI（PR #77）：10 checks passing, 0 failing（2026-11-09 快照；Verifier 须重跑本地命令，不以 CI 面板代替）

## Allowed paths（本 PR 实际触碰面）

- `src/main/java/com/opspilot/action/`（ActionOpsService / ActionOpsController）
- `src/main/resources/static/index.html`
- `src/test/java/com/opspilot/action/ActionOpsServiceTest.java`
- `scripts/check_panel_contract.sh`、`scripts/live_concurrency.py`、`scripts/check_observation.py`
- `offline/slo_report.py`、`offline/tests/`
- `docs/ops/`（周报/样本/走链记录/production-gate/roadmap/blocker-tasks）、`docs/repo-map.md`、`README.md`
- `.claude/launch.json`

## Acceptance criteria（逐条可判）

1. **审计 via 来源标识**：confirm/propose 请求携带 `X-Opshilot-Surface: panel` 时，
   对应 `action_*` 审计行 `via` 为 `action-panel`；header 缺失或为白名单外任意值时 `via` 为 `action-api`。
2. **终态三选一不掺水**：confirm 同步返回终态（dispatched / failed / confirmed），审计
   `action_dispatched` / `action_not_dispatched` 与真实 DB 状态一致（不得无条件写 dispatched）。
3. **failed 态文案分流**：index.html 确认后提示按终态分流——`failed` 明说派发失败与恢复路径，
   不再出现对 failed 态误导的「派发由自动化系统执行」。
4. **面板契约门闩层 10**：`check_panel_contract.sh` 校验面板 fetch 带 surface 头、后端读取该头；
   删除任一侧该检查转红。
5. **回归不破**：全量 Java/Python 测试与既有门闩全绿；本 PR 未弱化/删除任何既有断言
   （diff 中测试改动只允许新增或极性修正，须逐一说明）。
6. **记录与记账**：走链记录 §5 追加修复记录；production-gate §4 行为 append-only（原行未被改写）。

## Verification commands（Verifier 亲自重跑，工作目录 `D:/OpsPilot — AIOps`）

~~~bash
git rev-parse HEAD   # 须为 886672daf151c08b0ccd1a46544c6d18320fe5c7；git status --short 须记录
git diff origin/main...HEAD --stat   # 触碰面核对 Allowed paths
source scripts/java_home.sh && mvn -o test   # Java 全量（≥429，0 fail）
cd offline && python -m pytest tests -q   # Python 全量（≥527 passed）
python doc_numbers.py --check && python provenance.py --check   # offline 内
cd .. && python scripts/check_public_copy.py --check
bash scripts/check_panel_contract.sh   # 须输出"十层一致"
# 判据 1 的实证：Verifier 需读 src/test/java/com/opspilot/action/ActionOpsServiceTest.java
# 的 auditViaDistinguishesPanelFromApi 与 check_panel_contract.sh 的层 10，核对断言极性，
# 并确认 diff 未修改/删除既有断言：
git diff origin/main...HEAD -- src/test/ scripts/check_panel_contract.sh
~~~

## Forbidden

- 禁止改/删/弱化既有断言以换通过（判据 5 的 diff 核对项）；
- 禁止跳过任一验证命令或以其未跑的假设替代实证；
- 禁止在本契约外扩大触碰面（新文件/新目录不在 Allowed paths 即失败）；
- 禁止改写 production-gate §4 历史行（append-only）；
- 禁止提交 token/凭据到证据文件。

## 人审四问（分级留痕，合并签收用）

1. 验证命令全部亲跑且退出码 0？（Verifier 回答，附输出）
2. Acceptance criteria 逐条判在哪份证据？
3. 有无 Forbidden 事件？（任一发生即验收失败）
4. 遗留边界（UI 点击层浏览器走查未做）是否接受为已知项？

## 人审签收（2026-11-09）

- 问题 1–3：Verifier 证据包判定满足（8 命令退出码 0、六判据 PASS 实证、Forbidden 零触发）。
- 问题 4：**接受遗留边界为已知项**——UI 点击层真实浏览器走查未做（代码提取+同形状 HTTP 降级已验），
  作为已知项挂账，待浏览器可用时补查（记录于 walkthrough §3/§5）。
- 签收人：Lead（shing26）。签收即合并授权：PR #77 squash 合并。
