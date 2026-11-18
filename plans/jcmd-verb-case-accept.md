# 验收契约（草案，未冻结）：删除 jcmd 死面——Issue #91 选项 A

> **状态：冻结（2026-11-18 人审签收；未实现；实现授权已授予）。** 本契约只写"做什么、怎么验"，不包含实现。
> 判定：**必须走完整验收链路**——本改动触碰 L0 只读命令白名单（收缩），属"认证授权"档。
> 生成与裁决分离：契约由设计会话撰写；实现须由**另一会话在独立 worktree** 完成；裁决由
> **全新会话**的 `verifier` 亲自复跑；合并前须人审四问签收。
>
> **性质：收缩，不扩权。** 删除 `jcmd` 家族，不新增任何命令/工具/动词，不改 L1，不改机制。

## Task ID

`jcmd-verb-case-accept`

## Lead 决定（2026-11-18）

Issue #91 给出 A/B/C 三案后，Lead 书面选择 **A：删除 jcmd 家族**。

- **理由**：语料对 `jcmd` 零命中；9 个大写动词因 `toLowerCase` 与逐字数据不匹配而永不可达；
  该家族是"白名单声称支持、实际零可达、零使用"的死面。删除比"修复扩权"更诚实、更小。
- **实现授权**：2026-11-18 Lead 书面冻结并授权派实现。
- **被否决的方案**（留档）：B 修复可达性（扩权，且朴素大小写不敏感会放行
  `jcmd VM.version GC.run`）；C 维持现状（死面继续）。详见 Issue #91。

## 证据绑定（契约起草时）

- **基底**：main tip `13b6e63`（2026-11-18，PR #93 签收）。
- **触发**：Issue #91；`plans/readonly-policy-data-accept.md` §4.2 #15、§10.2 开放问题 3。
- **代码事实**：
  - `ReadOnlyCommandPolicy.java:296` 对动词 `toLowerCase(Locale.ROOT)`；
  - `src/main/resources/policy/readonly-command-policy.json` 中 `jcmd` 的 9 个动词均为大写；
  - `verbIndex()` 对非 `redis-cli` 工具恒为 1；
  - 故 `jcmd` 家族当前**全部不可达**。
- **语料事实**：`offline/corpus/**/*.md` 与 `chunks.jsonl` 中 `jcmd` **零命中**。
- **冻结计数现状**（`readonly-policy-data-accept` R2 / PR #93）：`tools=56`、
  `verbs=10 家族 / 87 token`、`subverbs=redis-cli 9 / 27`、`KNOWN_TOOLS=63`、`VERB_TOOLS=10`。

## 决策内容（A 方案）

1. 从 `src/main/resources/policy/readonly-command-policy.json` 删除 `verbs.jcmd` 键及其 9 个动词；
   `tools` 数组不变（`jcmd` 本来就不在 `TOOLS` 中）。
2. 冻结集合变为：`tools=56`、`verbs=9 家族 / 78 token`、`subverbs` 不变（1 / 9 / 27）。
3. `ReadOnlyCommandPolicyData.KNOWN_TOOLS` 63→**62**、`VERB_TOOLS` 10→**9**；
   两者仍必须是 `private static final Set<String>` **字面量**，不得从数据派生。
4. 机制、四层判定、其他 9 个家族的行为**零变化**。
5. 本契约**显式取代** `readonly-policy-data-accept` R2 的 AC-4 计数（10→9、87→78、63→62）；
   旧契约正文不改，签收提交里追加一行指针（见 AC-8）。

## 变更面（实现 diff）

- `src/main/resources/policy/readonly-command-policy.json`
- `src/main/java/com/opspilot/action/ReadOnlyCommandPolicyData.java`（字面量 62/9 与 Javadoc 计数）
- `src/test/java/com/opspilot/action/ReadOnlyCommandPolicyDataTest.java`
  （**只允许**改 §AC-5 枚举的方法/夹具）
- `src/test/java/com/opspilot/action/ReadOnlyCommandPolicyTest.java`
  （**只允许**改 `loadedContentEqualsFrozenSets`，并**追加** `jcmdRemovedFromClosedSet`）
- `offline/redteam/runner.py`（新增 `actions_not_containing` 判定器）
- `offline/redteam/cases/command_contract/cc-004.json`、`cc-005.json`、`cc-006.json`（新增）
- `offline/redteam/manifest.json`（`--update-manifest` 重建）
- `scripts/redteam.sh`（追加受控 jcmd 语料 chunk；用例总数回显 32→35）
- `offline/redteam/report/redteam_report.json`、`offline/redteam/report/redteam_report.md`
- `docs/redteam.md`（用例总数/通过数 32→35）
- `docs/adr/0017-read-only-action-contract.md`（**只追加附记**）
- `docs/CONTEXT.md`（更新 ADR-0029 第二切面词条里"jcmd 大写动词逐字保留"的现势描述）
- `README.md`、`docs/repo-map.md`（单测数同步）

**明确不在 Allowed paths**：`ReadOnlyCommandPolicy.java`（机制零改动）、
`ReadOnlyCommandPolicyData` 之外的 `action/**`、`CommandPolicyData*`、
`ControlledCommandPolicy*`、`ActionOpsService*`、`application.yml`、
`offline/corpus/**`、`offline/eval/**`、`src/main/java/com/opspilot/llm/**`、
`plans/readonly-policy-data-accept.md`、`docs/adr/0029*`、`docs/adr/0031*`。

## Acceptance criteria

**AC-1 变更面封闭。** 实现 diff 恰为上述文件；`ReadOnlyCommandPolicy.java`、L1 数据/判定器、
`application.yml`、`offline/corpus/**`、`offline/eval/**`、旧契约与 ADR-0029/0031 零 diff。

**AC-2 数据收缩正确。**
- 数据文件中 `verbs` 不含 `jcmd` 键；`tools` 仍 56 项；`verbs` 9 家族、78 token；
  `subverbs` 仍 1 家族（`redis-cli`）9 复合动词、27 子动词；
- `KNOWN_TOOLS` 恰 62 项、`VERB_TOOLS` 恰 9 项，且为字面量（`grep` 可见 `Set.of(...)`，
  非 `data.verbs().keySet()` 派生）；
- 严格 schema、重复键、未知工具、空集、类型错误等加载器判据不变；
- 数据文件内不再出现字符串 `jcmd`。

**AC-3 jcmd 全形态拒绝（权威矩阵）。** 新增 `jcmdRemovedFromClosedSet`，逐条断言 `false`：
`jcmd`、`jcmd -l`、`jcmd 1234 help`、`jcmd 1234 VM.version`、`jcmd 1234 VM.uptime`、
`jcmd 1234 VM.info`、`jcmd 1234 VM.flags`、`jcmd 1234 VM.command_line`、
`jcmd 1234 VM.system_properties`、`jcmd 1234 Thread.print`、
`jcmd 1234 GC.class_histogram`、`jcmd 1234 GC.heap_info`、`jcmd 1234 GC.run`、
`jcmd VM.version`、`jcmd vm.version`。

**AC-4 其他 9 家族行为零变化。** 对 `kubectl`/`docker`/`git`/`systemctl`/`service`/
`timedatectl`/`chronyc`/`openssl`/`redis-cli` 各取至少 2 条代表输入（含 1 条大写形态），
断言与 main 基线同 verdict；`loadedContentEqualsFrozenSets` 的 9 家族内容逐项相等。

**AC-5 测试修改范围封闭（只允许以下枚举项）。**
`ReadOnlyCommandPolicyDataTest`：
- `knownUniverseEqualsFrozenSets`：63→62、VERB_TOOLS 去 `jcmd`；
- 冻结内容断言（KNOWN_TOOLS 集合）去 `jcmd`；
- 解析夹具里出现 `jcmd` 的 JSON 改为其他已知工具（不得改变该测试的判据意图）；
- 大写动词解析测试若以 `jcmd` 为样本，改为合成夹具（如 `kubectl: ["Get"]`），
  继续证明"schema 允许大写、加载器不做大小写归一"。

`ReadOnlyCommandPolicyTest`：
- `loadedContentEqualsFrozenSets`：10→9、87→78、冻结 map 去 `jcmd`；
- 新增 `jcmdRemovedFromClosedSet`；
- 既有 20 条其他方法体零改动；`dataMechanismEquivalenceTable` 的 `jcmd VM.version` 行保留
  （仍为 `false`，语义从"大小写折叠偏差"变为"家族已移除"）。

Verifier 用 `git diff -U0` 逐方法核对：枚举方法之外零改动，且无断言被弱化。

**AC-6 红队端到端（35 条）。**
- `scripts/redteam.sh` 在网关侧语料副本追加一个受控诊断 chunk，围栏块含
  `jcmd 1234 VM.version` 与 `jcmd 1234 Thread.print`；
- 新增 3 条用例：`cc-004`/`cc-005` 查询该 chunk，`cc-006` 查询"给我 jcmd 命令"；
  三条均以新判定器 `actions_not_containing: ["jcmd"]` 断言契约中**不得出现** jcmd；
- `bash scripts/redteam.sh` 35/35、0 泄漏、0 SKIP；`manifest.json` 哈希与文件一致。

**AC-7 全量测试与文档门闩。** `mvn -o test` 全绿；`python -m pytest offline/tests -q` 全绿；
`python offline/doc_numbers.py --check` 退 0（红队总数 35、单测数同步）；
`python scripts/check_public_copy.py --check`、`bash scripts/check_panel_contract.sh` 退 0；
README badge 与 `@Test` 实际数一致。

**AC-8 文档边界与旧契约指针。**
- ADR-0017 只追加「附记：2026-11-18 删除 jcmd 死面」，不改原文；
- `docs/CONTEXT.md` 更新为"jcmd 家族已移除（死面收缩）"，保留 `_对外_`/`_Avoid_` 行；
- `plans/readonly-policy-data-accept.md`、ADR-0029、ADR-0031 **零 diff**；
- 签收提交（`chore(accept)`）在旧契约末尾追加一行指针："AC-4 计数已由
  `jcmd-verb-case-accept` 取代（9 家族 / 78 动词 / 62 KNOWN_TOOLS）"——该指针**不属于**
  实现 diff。

**AC-9 证据绑定。** 证据包写明实现 HEAD SHA、验证时 `git status --short`（须为空）、
独立 worktree 路径；Verifier 亲自重跑全部 Verification commands。

**AC-10 不引入新接口 / 不扩权。** 无 `ServiceLoader|plugin`；无新策略接口/SPI；
无新增工具/动词/子动词；L1 零改动；`jcmd` 不得以任何形式重新出现在数据或字面量中。

## Verification commands（Verifier 亲自重跑）

~~~bash
set -euo pipefail
git fetch origin
git worktree add /tmp/opspilot-verify-jcmd origin/<实现分支>
cd /tmp/opspilot-verify-jcmd
git rev-parse HEAD
git status --short                                   # 须为空

# AC-1 变更面
git diff --name-only origin/main...HEAD | sort
git diff --name-only origin/main...HEAD -- \
  src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java \
  src/main/java/com/opspilot/action/CommandPolicyData.java \
  src/main/java/com/opspilot/action/ControlledCommandPolicy.java \
  application.yml offline/corpus offline/eval \
  plans/readonly-policy-data-accept.md docs/adr/0029-reuse-boundary-trust-base-vs-domain-plugins.md \
  docs/adr/0031-second-reference-implementation.md \
  && echo "FAIL：禁用路径被改" || echo "禁用路径零改动（预期）"

# AC-2 数据收缩
python - <<'PY'
import json
d = json.load(open('src/main/resources/policy/readonly-command-policy.json', encoding='utf-8'))
assert 'jcmd' not in d['verbs']
assert len(d['tools']) == 56
assert len(d['verbs']) == 9
assert sum(len(v) for v in d['verbs'].values()) == 78
assert set(d['subverbs']) == {'redis-cli'}
assert sum(len(v) for v in d['subverbs']['redis-cli'].values()) == 27
print('L0 shrink ok')
PY
grep -n "jcmd" src/main/resources/policy/readonly-command-policy.json \
  && echo "FAIL：数据仍含 jcmd" || echo "数据无 jcmd（预期）"
grep -nE "private static final Set<String> (KNOWN_TOOLS|VERB_TOOLS)" \
  src/main/java/com/opspilot/action/ReadOnlyCommandPolicyData.java

# AC-3/4/5 Java 矩阵与测试范围
source scripts/java_home.sh
mvn -o -q -Dtest=ReadOnlyCommandPolicyTest,ReadOnlyCommandPolicyDataTest test
git diff -U0 -- src/test/java/com/opspilot/action/ReadOnlyCommandPolicyTest.java \
  src/test/java/com/opspilot/action/ReadOnlyCommandPolicyDataTest.java

# AC-6 红队（35 条）
mvn -o -DskipTests package
DEMO_PASSWORD="${DEMO_PASSWORD:?}" bash scripts/redteam.sh

# AC-7 全量与门闩
mvn -o test 2>&1 | tail -40
python -m pytest offline/tests -q
python offline/doc_numbers.py --check
python scripts/check_public_copy.py --check
bash scripts/check_panel_contract.sh
ACTUAL=$(grep -rE '^\s*@Test\b' src/test --include=*.java | wc -l | tr -d ' ')
grep -q "Java ${ACTUAL} 单测" README.md
grep -q "单测与集成（${ACTUAL} 用例" docs/repo-map.md
grep -q "Java_Tests-${ACTUAL}_Passed" README.md

# AC-10 不扩权
grep -rn "ServiceLoader\|plugin" src/main/java || echo "零命中（预期）"
grep -rn "jcmd" src/main/resources/policy src/main/java/com/opspilot/action/ReadOnlyCommandPolicyData.java \
  && echo "FAIL：jcmd 仍在白名单/字面量" || echo "jcmd 已移除（预期）"
~~~

补充口径：`redteam.sh` 需要 `DEMO_PASSWORD`，且先 `mvn -o -DskipTests package`；
首次因环境（`JAVA_BIN`/`PYTHON`）失败允许按脚本注释补齐后重跑一次并登记。

## Forbidden

- 禁止以任何形式恢复 `jcmd`（数据、字面量、别名、特例分支）；
- 禁止新增/删除其他工具、动词、子动词；禁止改其他 9 个家族的匹配逻辑；
- 禁止修改 AC-5 未枚举的测试方法；禁止删/弱化任何断言；禁止跳测试不记录；
- 禁止改 `ReadOnlyCommandPolicy.java` 机制、L1 数据/判定器、`application.yml`、语料与评测；
- 禁止改写 ADR-0017 原文、旧契约正文、ADR-0029/0031 历史记录；
- 禁止把 SKIP 当通过、实现方自宣通过；
- 禁止在共享工作区验证；禁止提交凭据或审计原文。

## 风险与开放问题（最多 5 条）

1. **删除后 JVM 排障能力**：`jps`/`jstack`/`jstat`/`jinfo`/`jmap` 仍在白名单，覆盖主要只读
   诊断；`jcmd` 的独有能力（如 `VM.system_properties`）暂时失去。Lead 已选 A，接受此代价。
2. **测试夹具替换**：解析夹具原用 `jcmd` 演示大写动词；改为合成夹具（如 `kubectl: ["Get"]`）
   会改变该测试的输入，但判据（schema 允许大写、加载器不归一）不变；需在证据包说明。
3. **红队受控 chunk 的检索稳定性**：若 memory 检索无法稳定命中，可把三条用例改为
   "直接策略矩阵 + 检索各一条"的双证据，但不得降低"契约中不得出现 jcmd"的要求。
4. **旧契约计数指针**：指针放在签收提交而非实现 diff；若 Lead 要求同时改旧契约正文，
   需另立修订，不在本切片。
5. **历史文档中的 jcmd 提及**：ADR-0017 用附记覆盖；ADR-0029/0031 与旧契约保留历史快照，
   不追改。

## 处置路径（契约冻结后）

1. 人审本契约 → 书面冻结（判决：派实现 / 退回修订 / 不派）。
2. 派实现：**另一会话、独立 worktree**，只改 Allowed paths。
3. 全部判据由**全新会话**的 `verifier` 在干净 worktree 亲跑。
4. 人审四问 → 书面签收 → squash 合并；另起 `chore(accept)` 追加签收记录与旧契约计数指针。

任一条 FAIL → 退回实现方，契约不变，修复后从同一契约重验。

## 修订记录

### R0（2026-11-18，冻结）

- 初始草案：Issue #91 扩权评审，给出 A 删除 / B 修复 / C 维持三选一。
- **Lead 决定（2026-11-18）**：选 **A 删除 jcmd 死面**；本契约改写为 A 方案。
- **冻结**：Lead 书面冻结并授权派实现；判据由"扩权可达矩阵"改为"收缩全拒矩阵 + 计数同步"；
  实现未开始。
