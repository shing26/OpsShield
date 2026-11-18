# 验收契约（草案，未冻结）：行动契约产额——同文档诊断叶保位 + 两份 runbook 只读化

> **状态：冻结（2026-11-18 人审签收；未实现；实现授权已授予）。** 本契约只写"做什么、怎么验"，不包含实现。
> 判定：**必须走完整验收链路**——本改动触碰检索选槽（引用组成）与行动契约产额，属"业务逻辑 /
> 对外 API（done 帧 refs/actions）"档。生成与裁决分离：契约由设计会话撰写；实现须由**另一会话
> 在独立 worktree** 完成；裁决由**全新会话**的 `verifier` 亲自复跑，实现方日志与自评不构成证据；
> 合并前须人审四问签收。
>
> **本切片性质：修复产额，不扩权。** 不新增命令/工具/动词，不放宽 L0 策略，不改 L1，不改
> `final-top-k`。只做两件事：① 引用数不变的前提下，让同文档的诊断叶进入 Top-3；② 把两份
> runbook 的只读排查命令写成可抽取的围栏块。

## Task ID

`command-yield-accept`

## 证据绑定（契约起草时）

- **基底**：main tip `13b6e63`（2026-11-18，PR #93 签收）。
- **起草时 `git status --short`**：干净；仅剩与本次无关的未跟踪文件（`.dsh/`、`.claude/agents/`、
  两份个人文档）。
- **触发**：Issue #89；`docs/ops/case-study-self-dogfood.md`（2026-11-17 自证）；
  `docs/ops/evidence/dogfood-2026-11-17.jsonl`（哈希链 PASS）。
- **失败事实**：`request_id=94a408b1`（`MVStoreException…`）Top-1 `rb-115`、`refs=3`、`actions=0`；
  `request_id=b479c325`（`Port 8091 was already in use.`）Top-1 `rb-103`、`refs=3`、`actions=0`。

### 起草时事实核查（纠正 Issue #89 的初判）

Issue #89 初判为"检索包含 + chunk 粒度 + 段名策略"。复核代码与语料后，**初判不完整**：

1. **`rb-115` 的第三步 chunk 没有任何围栏块**（`fences=0`，5 段行内代码）。
   命令是行内代码：`java -cp h2-<版本>.jar org.h2.tools.Recover …`、`cp …`、
   `bash scripts/seed_demo_users.sh`。即使该 chunk 进入 Top-3：
   - `ReadOnlyActionExtractor` 只抽围栏块 → 仍然 `actions=0`；
   - `java` / `cp` 不在 L0 `TOOLS`，`bash <script>` 的负载不是 `-c` 形态 → 策略也会拒。
   故"只差引用"不成立；**引用、命令形态、策略准入三件事同时不满足**。
2. **`rb-103` 的「处置」段有 2 个围栏块，但都是"示例输出"**（`端口 8081 已被占用…`），
   不是命令；命令是行内代码（`bash scripts/start_gateway.sh`、`netstat …`、`taskkill …`）。
   该段按 ADR-0017 整段不进契约，且其中含 `taskkill`（写），不应放宽。
3. **检索参数现状**：`final-top-k: 3`、`rerank-top-k: 20`（`application.yml`）；
   `HybridSearchService` 在 rerank 路径只从候选前 20 里取前 3。
4. **jcmd 与本契约无关**（语料零使用，另见 Issue #91）。

## 切面选型（三案）

| 候选 | 形态 | 结论 |
| --- | --- | --- |
| A. 抬 `final-top-k` 3→4/5 | 全局增加引用槽 | **否决**：改变成本/延迟与历史评测口径，需重登 OPS §5 数字；且不能解决命令形态与策略准入（rb-115 仍 0） |
| B. 同文档诊断叶保位 | 引用数仍为 3，把"文档标题/段头"这类低信息槽替换为同文档诊断叶 | **采纳**：不动 `final-top-k`、不跨文档、权限面不变；直接命中 OPS §5 已登记的"叶排第 4 名之后"根因 |
| C. 语料只读化 | `rb-103` 增「排查步骤」；`rb-115` 增只读预检步骤；命令用围栏块 | **采纳**：命令必须先是"策略准入的只读命令"，否则任何选槽都无米下锅；这是语料作者面的真实缺陷 |

组合 **B + C**。不新增接口、不新增命令族、不放宽任何白名单。

## 变更面（实现 diff）

实现文件（**恰 7 个**）：

- `src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java`（只新增一个 `public static`
  帮助方法，供跨包选槽判断"该 chunk 是否有可抽取的只读围栏命令"；仅帮助方法，不构成接口/SPI；
  `extract` 行为不变）
- `src/main/java/com/opspilot/retrieval/HybridSearchService.java`（选槽后处理）
- `src/main/java/com/opspilot/metrics/OpsMetrics.java`（新增计数器
  `aiops.retrieval.diagnostic_leaf_swap`，仅 Prometheus）
- `src/test/java/com/opspilot/retrieval/HybridSearchServiceTest.java`（只追加）
- `src/test/java/com/opspilot/action/ActionYieldGoldenTest.java`（新增；语料级 golden）
- `src/test/java/com/opspilot/action/ReadOnlyActionExtractorTest.java`（**只允许** §AC-6a 枚举的
  一处误报修正 + 两条变异测试）
- `README.md`、`docs/repo-map.md`（Java 单测数与 badge 同步；实现文件 7 个 + 文档 2 个，见下）

语料与派生（**只允许这 5 个路径出现 diff**；R3 勘误，原 4 + `grounding_report.json`）：

- `offline/corpus/runbooks/rb-115-h2-corruption-recovery.md`
- `offline/corpus/runbooks/rb-103-port-occupied-wrong-service.md`
- `offline/corpus/chunks.jsonl`（由 `offline/chunkers/build_chunks.py` 确定性重生成）
- `offline/eval/reports/PROVENANCE.json`（由 `offline/provenance.py --stamp` 重登记）
- `offline/eval/reports/grounding_report.json`（由 `offline/qa_gen_quality_probes.py V2 V4 V9`
  在 memory+mock 活体栈按新语料重生成；R3 新增）

数字引用（**只允许这 4 个路径出现 diff**）：

- `OPS.md`（`chunks` 计数与 §5 触发线条目）
- `docs/DEMO.md`（`chunks` 计数）
- `README.md`
- `docs/repo-map.md`

**明确不在 Allowed paths**：`application.yml`（`final-top-k` 必须保持 3）、
`src/main/resources/policy/**`、`ReadOnlyCommandPolicy.java`、`ReadOnlyCommandPolicyData.java`、
`CommandPolicyData.java`、`ControlledCommandPolicy.java`、`ActionOpsService.java`、
`src/main/java/com/opspilot/llm/**`、`src/main/java/com/opspilot/gateway/**`、
`offline/eval/golden_dataset.jsonl`（必须零 diff：样本由错误码与固定语义表派生，本次不改）、
`offline/eval/reports/eval_report.json`、`offline/action_contract_probe.py`、
`offline/tests/**`、`scripts/**`、`.github/**`、`docs/adr/**`。

## Acceptance criteria

**AC-1 变更面封闭。**
- 实现文件 `git diff --name-only origin/main...HEAD -- src/main src/test` 恰为 6 个
  （3 个 main + 3 个 test；其中 `ActionYieldGoldenTest.java` 为新增测试，
  `ReadOnlyActionExtractorTest.java` 为 R2 授权追回的既有测试；R3 勘误，
  `README.md`/`docs/repo-map.md` 另计）。
- 语料派生 diff 只允许出现在上述 4 个路径；`offline/eval/golden_dataset.jsonl` 零 diff。
- 禁用路径零 diff（见上）。

**AC-2 选槽保位规则（唯一允许的引用组成变化）。**
在 `HybridSearchService` 选出 `top` 之后、返回 `SearchOutcome` 之前执行，规则必须**同时**满足：
1. 只对 `top[0].docId()` 生效；不引入其他文档的 chunk；
2. 仅当 `top` 中该文档没有任何 chunk 属于"可抽取诊断叶"（面包屑第二段命中
   `DomainPack.diagnosticMarkers`、不含 `diagnosticExcludes`，且围栏块内至少一条命令通过
   `ReadOnlyCommandPolicy.isReadOnly`）时触发；
3. 候选叶必须来自该文档、来自本次候选池（rerank 路径取 rerank 候选池，fast path 取 fused 池）、
   属于诊断段、且围栏块内**至少一条命令**通过 `ReadOnlyCommandPolicy.isReadOnly`；
4. 替换槽位：优先替换 `top` 中该文档里面包屑少于 3 段的最低排名 chunk（文档标题 / 段头）；
   若不存在，替换该文档中非诊断段的最低排名 chunk；**至多替换 1 个槽**；
5. 引用数 `top.size()` 不变；其余 chunk 顺序与内容不变；被替换槽位保持原下标；
6. 记录 `aiops.retrieval.diagnostic_leaf_swap` 计数（仅 Prometheus，不进 `/state` 面板）；
7. 同一输入确定性地得到同一结果（叶选择按 rerank 分、再 rrf 分、再 `chunk_id` 升序）。

**AC-3 未触发时行为不变。**
- 若 Top-3 已含诊断叶，或候选池无可抽取诊断叶，或槽位不满足上述条件 → `top` 原样返回；
- 既有 `HybridSearchServiceTest` 断言零删改；新增测试覆盖触发/不触发/无候选/跨文档候选拒绝。

**AC-4 语料只读化（作者面）。**
- `rb-115` 在「排查步骤」下新增一个 h3 小节（如 `### 第四步：只读预检（先取证再评估）`），
  围栏块内只放**已通过 L0 策略**的只读命令；不得删除或改写原「第三步」的
  `java … Recover` 与「止损操作」；不得把 `java`/`cp`/`bash` 写进白名单。
- `rb-103` 新增 `## 排查步骤` 小节（位于「处置」之前），围栏块内只放只读命令
  （候选：`netstat -ano | grep :8091`、`curl -s -o /dev/null -w "%{http_code}" http://localhost:8091/actuator/health`）；
  「处置」段逐字不动。
- 新写入围栏块的每一条命令都必须能通过 `ReadOnlyCommandPolicy.isReadOnly`；由
  `ActionYieldGoldenTest` 逐条断言（失败则换命令，不得放宽策略）。
- 不得发明事故、错误码或 `source_incident`；只补只读排查命令与步骤标题。

**AC-5 金标准（端到端，memory + mock）。**
在 memory 存储 + mock LLM 的活体栈上回放三条自证查询：
- `MVStoreException: File is corrupted - unable to recover a valid set of chunks` →
  Top-1 `rb-115`、`refs=3`、`actions>=1`；
- `Web server failed to start. Port 8091 was already in use.` →
  Top-1 `rb-103`、`refs=3`、`actions>=1`；
- `网关起不来，端口被别的项目占了，怎么确认连的是不是自己` →
  Top-1 `rb-103`、`refs=3`、`actions>=1`（第三与第二可共用同一命令集）。
- 每条 `action.command` 必须是其 `ref` chunk 原文的逐字子串；
- 每条 `action.ref` 的 `doc_id` 必须等于该请求 Top-1 文档；不得出现跨文档 action；
- `actions` 编号连续 `1..N`；空命令/空 ref 一律 FAIL。

**AC-6 回归锁原样保持。**
- `ReadOnlyCommandPolicyTest`（20 条）、`ReadOnlyCommandPolicyDataTest`（12 条）、
  `ActionOpsServiceTest`（13 条）、`ControlledCommandPolicyTest`（8 条）、
  `CommandPolicyDataTest`（10 条）、`ReadOnlyActionExtractorTest`（10 条）、
  `ActionDispatchServiceTest`（15 条）全过且断言零删改；
- `bash scripts/redteam.sh` 32/32、0 泄漏、0 SKIP；
- `bash scripts/smoke.sh` 通过；`python offline/action_contract_probe.py` 不运行也可（无活体
  ES/Qdrant 时），但 `offline/action_contract_probe.py` 与 `offline/tests/` 零 diff；
- `aiops.guard.action_commands_rejected` 语义不变（判拒即递增）。

**AC-6a 误报修正的范围（R2 新增，唯一授权改动）。**
`ReadOnlyActionExtractorTest.liveCorpusSweepIsVerbatimAndSectionSafe` 的"写操作/警告段黑名单"
当前扫描**整条 breadcrumb**；rb-115 的 H1 标题是「…排查与处置手册…」，chunker 把 H1 放进每级
breadcrumb，导致任何 rb-115 诊断叶都误报。允许的修正**仅限**：
- 把黑名单检查从整条 `a.breadcrumb()` 改为**段名层级**（按 `" > "` 切分后的第二段；
  少于两段即 FAIL）；`止损/修复/处置/误判/陷阱` 五个词一个不少；
- 新增两条变异测试：
  (a) `disposalSectionStillExcluded`：面包屑 `Doc > 处置` + 围栏 `ps aux` → 抽不出 action；
  (b) `docTitleWithDisposalWordDoesNotBlockDiagnosticLeaf`：面包屑
  `…排查与处置手册… > 排查步骤 > 第四步` + 围栏 `ps aux` → 能抽出 action；
- `acts.size() >= 38`、`acts.size() <= 120`、`rejected >= 5`、逐字性断言全部不动；
- 该文件其他方法体零改动；不得删除或弱化任何断言。

**AC-7 全量测试与文档门闩。**
- `mvn -o test` 全绿；`python -m pytest offline/tests -q` 全绿；
- `cd offline && python chunkers/build_chunks.py` 重跑后 `chunks.jsonl` **零 diff**（确定性）；
- `python offline/doc_numbers.py --check` 退 0（含新的 chunk 数、单测数）；
- `python offline/provenance.py --check` 退 0；`python scripts/check_public_copy.py --check` 退 0；
- `cd offline && python eval/answer_eval.py --check` 退 0（接地报告与新语料同代）；
- `bash scripts/check_panel_contract.sh` 退 0；
- README 的 `Java_Tests-<n>_Passed` badge 与 `grep -rE '^\s*@Test\b' src/test --include=*.java`
  的实际值一致；`docs/repo-map.md` 同步。

**AC-8 不扩权 / 不改 L1 / 不改检索参数。**
- `application.yml` 零 diff（`final-top-k` 仍为 3）；
- L0 数据文件与三张表的冻结集合零 diff；L1 数据文件/判定器零 diff；
- `grep -rn "ServiceLoader\|plugin" src/main/java` 零命中；
- 不新增工具/动词/子动词，不放宽任何白名单。

**AC-9 证据绑定。** 证据包写明实现 HEAD SHA（= Verifier 绑定的分支 head）、验证时
`git status --short`（须为空）与独立 worktree 路径；Verifier 亲自重跑全部 Verification
commands，禁止引用实现方日志或自评。

**AC-10 可观测。** 新计数器只在 Prometheus 暴露；`/state` 面板契约零 diff；
`stage_ms` 字段结构不变；`refs` 数量恒为 3。

## Verification commands（Verifier 亲自重跑，禁止引用实现方日志）

> 必须用**独立干净 worktree**；`mvn` 路径按验证机环境调整（本机 `D:\java\apache-maven-3.9.14`
> 或 `E:\java\apache-maven-3.9.14`）。`.sh` 用 Git Bash 跑。

~~~bash
set -euo pipefail
git fetch origin
git worktree add /tmp/opspilot-verify-command-yield origin/<实现分支>
cd /tmp/opspilot-verify-command-yield

# AC-9 证据绑定
git rev-parse HEAD
git status --short                                   # 须为空

# AC-1 变更面
git diff --name-only origin/main...HEAD | sort
git diff --name-only origin/main...HEAD -- src/main src/test
git diff --name-only origin/main...HEAD -- offline/eval/golden_dataset.jsonl \
  && echo "FAIL：golden 不应改" || echo "golden 零 diff（预期）"
git diff --name-only origin/main...HEAD -- \
  src/main/resources/policy src/main/java/com/opspilot/llm \
  src/main/java/com/opspilot/gateway application.yml docs/adr scripts \
  offline/action_contract_probe.py offline/tests .github \
  && echo "FAIL：禁用路径被改" || echo "禁用路径零改动（预期）"

# AC-2 选槽接线与计数器
grep -n "diagnostic_leaf_swap" src/main/java/com/opspilot/metrics/OpsMetrics.java
grep -n "diagnosticLeafSwap\|diagnostic_leaf_swap\|DomainPack" \
  src/main/java/com/opspilot/retrieval/HybridSearchService.java
test "$(grep -c 'final-top-k: 3' src/main/resources/application.yml)" = 1

# AC-3 未触发行为不变（单测）
source scripts/java_home.sh
mvn -o -q -Dtest=HybridSearchServiceTest test

# AC-4 语料命令逐条过 L0
mvn -o -q -Dtest=ActionYieldGoldenTest test

# AC-5 金标准（memory + mock 活体栈）
mvn -o -DskipTests package
JAVA_BIN="${JAVA_BIN:-$(dirname "$(command -v java)")/java}"
PORT=18110
STORAGE_MODE=memory DASHSCOPE_MODE=mock SERVER_PORT=$PORT SERVER_ADDRESS=127.0.0.1 \
JWT_SECRET="$(head -c 48 /dev/urandom | base64 | tr -dc a-zA-Z0-9)" DASHSCOPE_API_KEY="" \
"$JAVA_BIN" -Xms128m -Xmx512m -jar target/opspilot-gateway-1.0.0.jar > /tmp/yield-app.log 2>&1 &
APP=$!; trap 'kill $APP 2>/dev/null || true' EXIT
export OPSPILOT_BASE="http://127.0.0.1:$PORT"
for _ in $(seq 1 60); do curl -fsS --max-time 2 "$OPSPILOT_BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
cd offline
python - <<'PY'
import json, localapi
base = __import__("os").environ["OPSPILOT_BASE"]
token = localapi.login("sre-full")
cases = [
    ("MVStoreException: File is corrupted - unable to recover a valid set of chunks", "rb-115"),
    ("Web server failed to start. Port 8091 was already in use.", "rb-103"),
    ("网关起不来，端口被别的项目占了，怎么确认连的是不是自己", "rb-103"),
]
for q, doc in cases:
    done = localapi.chat(base, token, q)["done"]
    refs, actions = done.get("refs") or [], done.get("actions") or []
    assert len(refs) == 3, (q, len(refs))
    assert actions, (q, "actions=0")
    assert any(r.get("doc_id") == doc for r in refs), (q, "Top-1 doc 不符")
    for a in actions:
        ref = a.get("ref")
        assert ref, (q, "空 ref")
        assert a.get("command"), (q, "空命令")
        assert [r for r in refs if r.get("chunk_id") == ref], (q, "action 不在 refs")
    print("PASS", doc, "refs=3 actions=%d" % len(actions))
PY
cd ..

# AC-6 回归锁
mvn -o test 2>&1 | tail -40
python -m pytest offline/tests -q
DEMO_PASSWORD="${DEMO_PASSWORD:?}" bash scripts/redteam.sh
bash scripts/smoke.sh

# AC-7 门闩
cd offline && python chunkers/build_chunks.py && cd ..
git diff --exit-code -- offline/corpus/chunks.jsonl && echo "chunks 重生成零 diff（预期）"
python offline/doc_numbers.py --check
python offline/provenance.py --check
cd offline && python eval/answer_eval.py --check && cd ..
python scripts/check_public_copy.py --check
bash scripts/check_panel_contract.sh
ACTUAL=$(grep -rE '^\s*@Test\b' src/test --include=*.java | wc -l | tr -d ' ')
grep -q "Java ${ACTUAL} 单测" README.md
grep -q "单测与集成（${ACTUAL} 用例" docs/repo-map.md
grep -q "Java_Tests-${ACTUAL}_Passed" README.md

# AC-8 不扩权
grep -rn "ServiceLoader\|plugin" src/main/java || echo "零命中（预期）"
git diff --name-only origin/main...HEAD -- src/main/resources/policy \
  src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java \
  src/test/java/com/opspilot/action/ReadOnlyCommandPolicyTest.java \
  && echo "FAIL：L0 策略被改" || echo "L0 策略零改动（预期）"
~~~

补充口径：

- `localapi` 的登录/会话用法以 `offline/localapi.py` 现有函数为准（实现方可按实际签名调整脚本，
  但三条查询、`refs=3`、`actions>=1`、逐字性四项判据不变）。
- 活体栈端口冲突时换端口重跑，不改变判据；首次启动失败按环境问题登记后重跑一次。
- `redteam.sh` 与 `smoke.sh` 需要 `DEMO_PASSWORD`（按脚本要求临时设置，不回显、不入库）。

## Forbidden

- 禁止抬 `final-top-k` / 改 `rerank-top-k` / 改 ADR-0017 的选槽口径；
- 禁止跨文档选叶、跨租户选叶、增加引用数、改变 Top-1 文档；
- 禁止新增/删除/放宽任何工具、动词、子动词或 L0/L1 策略；禁止把 `java`/`cp`/`bash` 加进白名单；
- 禁止修改既有断言、跳测试不记录、关 lint / typecheck / 文档门闩消警；
- 禁止改 `offline/eval/golden_dataset.jsonl`、`offline/tests/**`、`scripts/**`、`.github/**`；
- 禁止在 runbook 里发明事故、错误码、`source_incident`；只允许补只读排查命令与步骤标题；
- 禁止把「处置」「止损操作」段里的写命令搬进契约或白名单；
- 禁止实现方自宣通过；Verifier 结论必须来自亲自重跑的命令输出；
- 禁止在共享工作区验证；禁止提交凭据、审计原文或 `offline/staging/` 产物。

## 风险与开放问题（最多 5 条，需 Lead 拍板）

1. **是否接受引用组成变化（数量不变、Top-1 不变）？** 推荐接受：这是 OPS §5 已登记触发线的
   最小兑现方式；代价是 refs 的 chunk 组成可能变化，历史"引用集差集"对照用例需重跑确认。
2. **保位规则只限 Top-1 文档是否足够？** 推荐是：跨文档补位会改变"Top-1 是谁"的产品承诺，
   且越权面更大；先解 Top-1 产额。
3. **`rb-115` 新增的只读命令选哪些？** 推荐 `ls -la data`、`stat data/users.mv.db`、
   `jps -l`、`grep -c` 之类的取证命令；最终以"逐条过 L0"为准，不得为凑命令放宽策略。
4. **`rb-103` 的 `curl -w "%{http_code}"` 是否被 L0 接受？** 以实现方实测为准；不接受就换
   `curl -s -o /dev/null http://localhost:8091/actuator/health`，判据不变。
5. **OPS §5 触发线是否标记为"已兑现"？** 推荐在 `OPS.md` §5 该行补一句"2026-11 由
   command-yield 契约兑现"，不改阈值与历史数字。

### Lead 决定（2026-11-18）

- **Q1 引用组成变化**：**接受**——`refs` 数量保持 3，只允许同文档诊断叶保位。
- **Q2 仅 Top-1 文档**：**接受**——不跨文档补位，Top-1 文档承诺不变。
- **Q3/Q4 两份 runbook 的只读排查段**：**允许修改**——新增命令必须逐条通过 L0，
  不得为凑命令放宽策略；原「第三步」「处置」「止损操作」逐字保留。
- **Q5 未决时的默认**：冻结按"不标记已兑现"执行——`OPS.md` §5 只更新与本次相关的
  事实性数字，不追加"已兑现"结论；如需标记，另起文档提交。
- **实现授权**：2026-11-18 Lead 书面冻结并授权派实现。

## 处置路径（契约冻结后）

1. 人审本契约 → 书面冻结（判决：派实现 / 退回修订 / 不派）。
2. 派实现：**另一会话、独立 worktree**，只改 Allowed paths。
3. 全部判据由**全新会话**的 `verifier` 在干净 worktree 亲跑。
4. 人审四问（证据充分性 / 判据纪律 / 变更面封闭 / 遗留边界）→ 书面签收 → squash 合并。
5. 另起 `chore(accept)` 提交追加签收记录，不改判据。

任一条 FAIL → 退回实现方，契约不变，修复后从同一契约重验。

## 修订记录

### R0（2026-11-18，草案）

- 初始草案：Issue #89 的产额修复；组合「同文档诊断叶保位」+「两份 runbook 只读化」。
- **纠正 Issue 初判**：`rb-115` 第三步 chunk 无围栏块，且 `java`/`cp`/`bash` 不在 L0 白名单，
  故"只差引用"不成立；本契约把命令形态与策略准入一并纳入。
- **未冻结、未实现**；等待 Lead 人审。

### R1（2026-11-18，冻结）

- **Lead 决定**：接受 Q1/Q2 的引用组成变化（refs 仍为 3、仅 Top-1 同文档保位）；
  允许修改 `rb-115`/`rb-103` 的只读排查段。
- **冻结**：Lead 书面冻结并授权派实现；判据未改；实现未开始。

### R2（2026-11-18，冻结后修订：误报修正授权）

- **触发**：实现方实测 `ReadOnlyActionExtractorTest.liveCorpusSweepIsVerbatimAndSectionSafe`
  失败——该断言扫描整条 breadcrumb，而 rb-115 的 H1 标题含「处置」，导致任何 rb-115 诊断叶
  必被误判为"写操作段"。
- **修订**：授权把该黑名单检查改为**段名层级**（`" > "` 第二段），五个黑名单词不变；新增
  两条变异测试证明"处置段仍拒、标题含处置不误伤"。其余断言与阈值零改动。
- **同时澄清**：① `ReadOnlyActionExtractor` 的帮助方法为 `public static`（跨包调用，不构成
  接口/SPI）；② AC-2 的触发条件统一为"可抽取诊断叶"（诊断段 + 至少一条过 L0 的围栏命令），
  与 AC-3 一致。
- **判据未变**：refs 仍为 3、仅 Top-1 同文档保位、金标准 actions>=1；本次只消除测试误报。

### R3（2026-11-18，冻结后修订：接地报告同代门闩）

- **触发**：PR #94 的 CI `provenance` 作业运行 `cd offline && python eval/answer_eval.py --check`
  失败——`grounding_report.json` 的 `chunks_sha256` 仍是旧语料（报告
  `156c7af5…` vs 新语料 `9059b6ce…`）。契约原 Verification commands 漏了这条既有 CI 门闩。
- **修订**：Allowed paths 增加 `offline/eval/reports/grounding_report.json`；AC-7 与
  Verification commands 增加 `cd offline && python eval/answer_eval.py --check`；
  报告由 `offline/qa_gen_quality_probes.py V2 V4 V9` 在 memory+mock 活体栈按新语料重生成。
- **勘误**：AC-1 原文「5 个（4 个 Java + 1 个新增测试）」与实现事实不符——实际 6 个
  （3 个 main + 3 个 test，含 R2 授权修改的 `ReadOnlyActionExtractorTest.java`）；
  已按 `origin/main...feat/command-yield-impl` 的实测变更面更正，判据口径不变。
- **判据未变**：refs=3、actions>=1、逐字性、变更面与安全边界均不变；本次只补"报告同代"。

## 人审签收（2026-11-19）

- **签收人**：Lead（shing26，书面"签收 PR #94"）；**合并**：squash → `f5100ce`
  （分支 `feat/command-yield-impl` 保留，head `da3508d`）。
- **验证历程**：两轮独立 verifier，均绑定 HEAD + `git status --short` 首尾为空。
  ① 实现轮（head `f673270`）：干净 worktree 亲跑全部 Verification commands，
  AC-1…AC-10 全部 PASS；`mvn -o test` 491/0/0/0、`pytest` 656 passed、
  金标准 rb-115 `actions=4` / rb-103 `actions=2` / 中文查询 `actions=2`（refs 均 3）。
  ② R3 增量轮（head `da3508d`）：实现代码自 `f673270` 零 diff（src/main src/test 恰 6 个）；
  接地报告 `chunks_sha256` 与当前语料、PROVENANCE 三方一致（`9059b6ce…`），
  `chunks_lines=450`、`answers=11`、`codes_total=21`、`codes_ungrounded=0`；
  `answer_eval --check` 亲跑 exit 0；CI 11/11 全绿（run 44347713146，含 provenance）。
- **人审四问**（签收即表示无异议）：
  1. **证据充分性**：无异议——两轮 verifier 亲跑、实现方日志未被采信、证据可重跑。
  2. **判据纪律**：无异议——refs=3、Top-1 同文档保位、金标准 actions>=1 判据未动；
     R3 只补"报告同代"门闩，未放宽任何既有阈值。
  3. **变更面封闭**：无异议——恰 15 个路径 = 原 14 + `grounding_report.json`；
     `golden_dataset.jsonl` 零 diff；禁用路径零 diff。
  4. **遗留边界**：**接受为已知项**——见下"登记的偏离与边界"。
- **禁令复核**：未以改/删/弱化断言换通过、未跳测试不记录、未关检查消警、
  实现方未自宣通过、未绕过人审、本次合并有书面签收。

### 登记的偏离与边界

- **V4 mock 形态失败（不在 R3 判据内）**：mock-template 答案恒以「## 问题定位」开头、
  无 hedge 语态，V4-colloquial/V4-formal 在 mock 口径下 FAIL。V4 是 live 软上限判据
  （脚本 docstring：上限=探针选词，升级触发线见 OPS 债务闹钟表/ADR-0010），R3 只要求
  报告由 V2/V4/V9 在 memory+mock 重生成 + `answer_eval --check` 同代零无据。
  live 口径的 V4 门保持原状、不弱化、不豁免。
- **报告 backend=mock**：报告自身 `known_boundary` 已声明 mock 下接地判据恒真；
  R3 门闩证明的是"报告与当前语料同代且零无据"，不是 live 接地质量。
- **既有残余风险（上轮已登记，本轮未变化）**：`topRelevance` 在选槽前计算（换下标 0 时
  置信度取替换前）；rerank 候选池未进 Top-3 的叶 rerank 分常为 0，排序实际由
  RRF/`chunk_id` 兜底。
- **勘误**：AC-1 变更面 5→6 个实现文件、"语料与派生"4→5 个路径，均已随本提交更正，
  判据口径不变。
