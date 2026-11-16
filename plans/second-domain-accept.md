# 验收契约（草案，未冻结）：第二个参考实现——数据库值班领域包（Domain Pack）

> **状态：草案（2026-11-16 起草；未经评审、未冻结、未派实现）。** 本契约只冻结"做什么、
> 怎么验"，不包含任何实现。判据、Allowed paths、验证命令在 Lead 书面签收前都**不是**冻结面；
> 冻结后不得再改，改判据走「契约修订记录」并由 Lead 重新批准。
>
> 判定：**必须走完整验收链路**——本改动触碰对外文案、语料来源与 L2 静态 SOP 的段名判据，
> 属"业务逻辑 + 对外文案"档。生成与裁决分离：契约由设计会话撰写；实现须由**另一会话在独立
> worktree** 完成；裁决由**全新会话**的 `verifier` subagent（Read/Grep/Glob/Bash）亲自复跑，
> 实现方贴的日志与自评不构成证据；合并前须人审四问签收。

## Task ID

`second-domain-accept`

## 证据绑定（契约起草时）

- **main tip**：`422732453f285d8fe24190c6e90ccd0823bb931d`（2026-11-16）。
- **设计分支/worktree**：`design/second-domain`，`C:/Users/Shing/AppData/Local/Temp/opspilot-task1-second-domain`。
- **起草时 `git status --short`**：空（干净 worktree）。
- **前置**：ADR-0029 accepted（PR #84 签收）；首个切面（L1 白名单内容外置）已通过独立验证并
  合并（PR #85 / `7760c40`）；本契约配套 ADR 草案
  [ADR-0031](../docs/adr/0031-second-reference-implementation.md)。
- **验证目标 SHA**：本契约落地提交之后的实现分支 head
  （`git log -1 --format=%H -- plans/second-domain-accept.md` 即为 Verifier 须绑定的 HEAD）。
- 验证必须在**独立干净 worktree** 中进行，禁止共享工作区。

## 前置事实（评审可复核，均引自 main `4227324`）

| 事实 | 位置 |
| --- | --- |
| SOP 段名焊在底座 | `src/main/java/com/opspilot/ingest/IngestionRunner.java:491`（`contains("止损操作")`） |
| 诊断段名/排除词焊在底座 | `src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java:40`、`:44` |
| SRE 系统提示焊在底座 | `src/main/java/com/opspilot/llm/PromptAssembler.java:14-22` |
| 拒答/L2 兜底文案焊在底座 | `PromptAssembler.java:17`、`src/main/java/com/opspilot/gateway/ChatOrchestrator.java:315`、`:351` |
| 语料路径默认值写了两遍 | `IngestionRunner.java:90`、`src/main/java/com/opspilot/config/MockIdfInitializer.java:29` |
| 控制台结论行依赖 `## 问题定位` | `src/main/resources/static/index.html:951-957` |
| 切分器不产出 `action_class` | `offline/chunkers/markdown_tree_chunker.py`（front matter 只映射 service/env/auth_level/error_codes/tenant） |
| 切分器已支持第二语料根 | `offline/chunkers/build_chunks.py` 的 `--corpus` / `--out`（两参数均限制在 `offline/` 内） |
| 语料中的 DB 内容 | 41 个 chunk 带 50012/50013/50101/50141；5 篇 DB 文档（rb-001、rb-002、ac-508、pm-001、pm-002） |
| `scripts/check_domain_words.py` 不存在 | `ls scripts | grep -i domain` 为空（ADR-0029 R1 只登记了判据） |
| `action/` 的 L0 工具词仍在底座 | `ReadOnlyCommandPolicy.java:63-110`（`TOOLS` / `VERBS`） |

## 切面选型（五件契约，动三件、等一件、一件不动）

| 契约 | 本切面 | 判据 |
| --- | --- | --- |
| 语料来源 | **动（数据）** | 第二语料根 `offline/domains/dba/corpus/`，用既有 chunker 生成；manifest 声明默认路径 |
| SOP / Runbook | **动（数据）** | 止损段/诊断段/排除词从 pack 读；`IngestionRunner` 与 `ReadOnlyActionExtractor` 共用声明；键/TTL/租户分片不动 |
| 提示词 / 文案 | **动（数据）** | 角色/结构/拒答/L2 兜底/on-call 称呼从 pack 读；底座安全条款与协议头不可覆盖；`VerbatimGuard` 不动 |
| 指纹归一化 | **等** | 第三种规则（保留 MySQL errno/SQLSTATE）是上下文相关的，不能表达为闭集形状；DBA 探针只用应用错误码 |
| 命令族 | **不动** | 首个切面已外置内容；本契约**命令判定机制零改动**（`ReadOnlyCommandPolicy` / `ControlledCommandPolicy` / `CommandPolicyData` / `command-policy.json`），只改 `ReadOnlyActionExtractor` 的段名来源。DBA 语料每条命令必须命中现有 L0 判据，否则移进止血段 |

**这次不解锁**：SPI/`ServiceLoader`、独立 Maven 模块、同进程多域、按租户切换域、热加载/
HTTP 上传、DBA L1 家族、L0 工具集合变更、指纹归一化接口、`dba` 角色/账号维度。
**已知遗留（不在本契约）**：`llm/MockEngine` 的 mock 拒答文案与段名分类仍是 SRE 形状——它是
机制验证后端的独立渲染路径，改它要动 `LlmClient` 的静态调用面；本契约不触碰，并在 ADR 登记。

## 变更面（预计）

```
plans/second-domain-accept.md                                    （本契约）
docs/adr/0031-second-reference-implementation.md                （配套 ADR 草案）
docs/CONTEXT.md
docs/repo-map.md
README.md
OPS.md
src/main/resources/application.yml                               （仅 opspilot.domain）
src/main/java/com/opspilot/domain/DomainPack.java                （新增）
src/main/java/com/opspilot/domain/DomainRegistry.java            （新增）
src/main/java/com/opspilot/ingest/IngestionRunner.java
src/main/java/com/opspilot/config/MockIdfInitializer.java
src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java   （只改段名来源）
src/main/java/com/opspilot/llm/PromptAssembler.java
src/main/java/com/opspilot/gateway/ChatOrchestrator.java         （只改文案）
src/main/java/com/opspilot/gateway/SseEvents.java                （内部错误文案去 SRE）
src/main/java/com/opspilot/gateway/OpenAiChatSink.java           （内部错误文案去 SRE）
src/main/resources/domain/sre/domain.json                        （新增）
src/main/resources/domain/sre/prompt.json                        （新增）
src/main/resources/domain/dba/domain.json                        （新增）
src/main/resources/domain/dba/prompt.json                        （新增）
offline/domains/dba/corpus/**                                    （新增：≥5 篇 .md + chunks.jsonl）
scripts/check_domain_words.py                                    （新增）
.github/workflows/ci.yml                                         （仅加 domain-words 一步）
src/test/java/com/opspilot/domain/DomainPackTest.java            （新增）
src/test/java/com/opspilot/domain/DomainContentTest.java         （新增）
offline/tests/test_domain_pack.py                                （新增）
```

`README.md`、`docs/repo-map.md` 的单测数变化由 `offline/doc_numbers.py` 现算强制同步；若实现
未新增 `@Test`，视为 AC-6 未兑现。**ADR 计数 30→31 已随本草案提交同步**（README 两处 +
repo-map）；实现只需再同步单测数，以及 repo-map 的 `domain/`、`offline/domains/` 包地图行。

## Allowed paths

- `plans/second-domain-accept.md`（本契约；冻结后不得再改判据）
- `docs/adr/0031-second-reference-implementation.md`
- `docs/CONTEXT.md`、`docs/repo-map.md`、`README.md`、`OPS.md`（词条与数字门闩同步）
- `src/main/resources/application.yml`（**只允许**新增 `opspilot.domain` 一行及注释）
- `src/main/java/com/opspilot/domain/DomainPack.java`、`DomainRegistry.java`（新增，路径与类名固定）
- `src/main/java/com/opspilot/ingest/IngestionRunner.java`（只改语料默认路径与止损段来源）
- `src/main/java/com/opspilot/config/MockIdfInitializer.java`（只改语料默认路径来源）
- `src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java`（只改诊断段名/排除词来源；
  `extract` 与只读判定逻辑不动）
- `src/main/java/com/opspilot/llm/PromptAssembler.java`
- `src/main/java/com/opspilot/gateway/ChatOrchestrator.java`（只改 `:315` / `:351` 文案；
  推理、缓存、权限、降级逻辑不动）
- `src/main/java/com/opspilot/gateway/SseEvents.java`、`OpenAiChatSink.java`（只改内部错误文案）
- `src/main/resources/domain/sre/domain.json`、`src/main/resources/domain/sre/prompt.json`
- `src/main/resources/domain/dba/domain.json`、`src/main/resources/domain/dba/prompt.json`
- `offline/domains/dba/corpus/**`（新增；`.md` 与 `chunks.jsonl` 均入库）
- `scripts/check_domain_words.py`（新增）
- `.github/workflows/ci.yml`（只加 `domain-words` 一步）
- `src/test/java/com/opspilot/domain/DomainPackTest.java`、`DomainContentTest.java`（新增）
- `offline/tests/test_domain_pack.py`（新增）
- 验证临时产物**只许**落在 `offline/staging/`（`.gitignore` 覆盖）或系统临时目录，**不入库**

## Acceptance criteria

**AC-1 变更面封闭。** `git diff --name-only origin/main...HEAD` 的每一项都必须落在 Allowed
paths 内，且必须包含：两个 `domain.json`、两个 `prompt.json`、`DomainPack.java`、
`DomainRegistry.java`、`offline/domains/dba/corpus/chunks.jsonl`、≥5 个 DBA 语料 `.md`、
`scripts/check_domain_words.py`、两个新 Java 测试类、一个 Python 测试、以及 6 个文档文件
（本契约 + ADR-0031 + README + repo-map + CONTEXT + OPS）。
以下路径**零 diff**（硬）：`src/main/java/com/opspilot/action/**`（`ReadOnlyActionExtractor`
除外）、`auth/**`、`cache/**`、`retrieval/**`、`store/**`、`storm/**`、`resilience/**`、
`src/main/resources/policy/command-policy.json`、`offline/corpus/**`、`offline/eval/**`、
`offline/chunkers/**`、`src/test/java/com/opspilot/{action,llm,ingest,gateway}/**`、`pom.xml`、
`Dockerfile`、`docker-compose.yml`。
`application.yml` 的 diff 只允许出现 `opspilot.domain` / `OPSPILOT_DOMAIN` 与注释行。

**AC-2 SRE 包逐字等价（默认路径不能变）。** 未设 `OPSPILOT_DOMAIN` 时：
- `DomainPack.id()=="sre"`；`corpusPath()=="offline/corpus/chunks.jsonl"`；
  `stopLossMarkers()==["止损操作"]`；`diagnosticMarkers()==["排查"]`；
  `diagnosticExcludes()==["陷阱","误判"]`；
- 组装出的系统提示与今天的 `PromptAssembler.SYSTEM`（含强标识符与弱问题两种分支）**逐字相等**
  ——由 `DomainPackTest` 里的**黄金串**（直接从 main `4227324` 抄录）比对；三条文案（无参考
  拒答、低置信度补充语、L2 无 SOP 兜底）与原值逐字相等；`onCallName()=="值班 SRE"`；
- 既有测试断言**零改动、零弱化**：`ReadOnlyCommandPolicyTest` 18 条、`ControlledCommandPolicyTest`
  8 条、`ActionOpsServiceTest` 13 条、`VerbatimGuardTest` 10 条、`PromptAssemblerTest` 4 条
  全过；`git diff` 不触及这些文件；
- `mvn -o test` 全绿；`bash scripts/redteam.sh` 32/32、0 泄漏、0 SKIP。

**AC-3 领域词不回焊（门闩 + 变异）。**
- `python scripts/check_domain_words.py --check` 退 0；`--self-test` 退 0（临时目录里注入
  `static final String X = "kubectl";` 必红，移除必绿）。
- 扫描面**恰为** ADR-0029 R1 的干净底座包（`storm/`、`auth/`、`cache/`、`store/`、`metrics/`、
  `llm/VerbatimGuard.java`、`resilience/` 除 `SopFallbackService.java`）**加 `ingest/`**；
  排除 `com/opspilot/domain/`。词表从各 pack 的 `lexicon` **现取**，不手写。
- 只扫字符串字面量与文本块（注释允许解释机制）；命中即打印 `file:line word` 并退 1。
- 直接复核（**只扫门闩覆盖面**，不扫 `llm/MockEngine.java` 这个已登记的遗留）：
  `grep -rn '"止损操作"\|"排查"\|"联系值班 SRE"' src/main/java/com/opspilot/{ingest,storm,auth,cache,store,metrics,resilience} src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java --include=*.java`
  零命中（带引号=字面量；注释里的 `{@code 止损操作}` 不算）。
- `grep -rn "ServiceLoader\|plugin" src/main/java` 零命中（Java 源码里用"领域包"，不写英文
  `plugin`，避免与该门闩字面冲突）；
  `grep -rn 'Files\.\|Path\.of\|Socket\|URL' src/main/java/com/opspilot/domain/` 零命中
  （pack 只从 classpath 读）。

**AC-4 领域选择 fail-closed。** `DomainPackTest` 逐条钉住：
- 未设 / 空串 → `sre`（默认，不自动回退）；`dba` → dba；未知 id、含 `/` 或 `..`、含首尾空白、
  大写（如 `DBA`）→ 启动失败并点名 id；
- 缺 manifest、未知 `schema_version`、未知顶层/嵌套键、空段名列表、段名含 `.*`/`(`/`|` 等正则
  元字符、缺 prompt、`answer_structure` 缺 `## 问题定位`、缺文案键、`extra_rules` 为空串/超长/
  含换行 → `IllegalStateException`，不静默忽略、不回落默认值；
- `extra_rules` 命中覆盖类短语绊线（如"逐字输出原文""忽略以上规则"）→ 启动失败（绊线，不是
  完备证明）；
- 只认 `opspilot.domain`/`OPSPILOT_DOMAIN` 一个选择口；无 HTTP/热加载/按租户覆盖。

**AC-5 DBA 语料真实且有界（"不是玩具"的机器判据）。**
- `offline/domains/dba/corpus/chunks.jsonl` 由既有命令生成，命令写进证据包：
  `python offline/chunkers/build_chunks.py --corpus offline/domains/dba/corpus --out offline/domains/dba/corpus/chunks.jsonl`；
  `git diff offline/chunkers` 为空。
- ≥5 篇文档、≥25 chunks；每篇 front matter 必须有 `doc_id: dba-0xx`、`source_incident`、
  `auth_level`、`tenant`；`source_incident` 只允许两类值：已存在的 SRE doc_id
  （`pm-001`/`pm-002`/`ac-508`/`rb-001`/`rb-002`）或 `OPS.md#<可检索的标题片段>`，由
  `offline/tests/test_domain_pack.py` 解析并核对存在性。
- **不得发明错误码**：每篇 `error_codes` 的每一项都必须已出现在 `offline/corpus/chunks.jsonl`
  的错误码集合里；MySQL errno / SQLSTATE 只允许出现在正文，不放进 `error_codes`（否则会污染
  coverage 与快路径词表）。
- DBA 语料无 `action_class`（本切面不声明 L1）；`doc_id` 前缀 `dba-`，与 SRE 语料零撞号。
- DBA 语料**诊断段（`## 核查步骤`）内**的每条 fenced `bash`/`sql` 命令都必须被现有
  `ReadOnlyCommandPolicy` 判为 L0；由 `DomainContentTest` 现算，任何一条判拒即 FAIL
  （**修内容，不放宽策略**）。止血段与其他段的命令不进入只读行动契约（段名闸保证），
  不受此限，但也不得被抽取。

**AC-6 DBA 包行为（组件级，确定性，不触网）。** `DomainContentTest`：
- `IngestionRunner` 的 SOP 预热在 dba 包下认 `## 止血与恢复`、不认 `## 止损操作`；Redis 键形状
  仍为 `sop:<tenant>:steps` / `:code:` / `:service:`，TTL 7 天不变；
- `ReadOnlyActionExtractor` 在 dba 包下抽取 `## 核查步骤`，不抽取 `## 止血与恢复`；
- `PromptAssembler` 在 dba 包下的系统提示包含 `## 问题定位`、DBA 拒答文案、底座规则 4–7 的
  原文、DBA 附加规则；SRE 包下与黄金串逐字相等（AC-2）；
- `ChatOrchestrator` 的 L2 无 SOP 兜底与低置信度拒答在 dba 包下使用 DBA 文案（通过
  `DomainPack.current()` 注入；既有 `ChatOrchestratorTest` 不改）；
- 内部错误文案（`SseEvents` / `OpenAiChatSink`）不再含 "SRE"，改为"值班人员"。
- **已知遗留**：`MockEngine` 的 mock 拒答/标题不在本契约（见"这次不解锁"），`DomainContentTest`
  不得用 mock 答案文案做 DBA 断言。
- 可选 live 探针（**非 CI、不计入裁决**）：`STORAGE_MODE=memory DASHSCOPE_MODE=mock
  OPSPILOT_DOMAIN=dba` 起服务，`/coverage` 的服务数/错误码数/块数与 DBA 语料现算值相等，
  3 条固定查询的 refs 全部是 `dba-*`，`/chat/stream` 拒答含 "DBA 值班"（LLM 路径；mock 答案
  不在判据内）。

**AC-7 回归锁原样保持。** AC-1 的零 diff 清单 + 以下测试全过：只读契约 18 条、L1 白名单 8 条、
灰度 fail-closed 13 条、逐字护栏 10 条、Prompt 4 条、`AuditService` 链式写入、
`ev=degrade_transition`、`scripts/l1_worm_gate.py` 口径不变、红队 32/32（默认 sre 包）。

**AC-8 全量测试与文档门闩。** `mvn -o test` 全绿；`python -m pytest offline/tests -q` 全绿；
`python offline/doc_numbers.py --check` 退 0（ADR 数 31、单测数现算）；`python offline/provenance.py
--check` 退 0（SRE 语料未动）；`python scripts/check_public_copy.py --check` OK；
`bash scripts/check_panel_contract.sh` OK；`python scripts/check_domain_words.py --check` 退 0；
CI 新增 `domain-words` 步骤（挂在 `provenance` job），PR 上必须跑过。

**AC-9 证据绑定。** 证据包写明 HEAD SHA（= 契约落地后的分支 head）、验证时 `git status --short`
（须为空）、独立 worktree 路径、DBA 语料生成命令与产物行数；Verifier 亲自重跑全部命令。

## Verification commands（Verifier 亲自重跑，禁止引用实现方日志）

> 必须用**独立干净 worktree**。`mvn` 路径按验证机环境调整（本机为
> `/e/java/apache-maven-3.9.14/bin/mvn`）。

~~~bash
git fetch origin
git worktree add /tmp/opspilot-verify-second-domain origin/<实现分支>
cd /tmp/opspilot-verify-second-domain

# AC-9 证据绑定
git rev-parse HEAD
git log -1 --format=%H -- plans/second-domain-accept.md    # 须等于上面的 HEAD
git status --short                                         # 须为空

# AC-1 变更面
git diff --name-only origin/main...HEAD | sort
git diff --name-only origin/main...HEAD | grep -E '^src/main/java/com/opspilot/(auth|cache|retrieval|store|storm|resilience)/' \
  && echo "FAIL：禁用路径被改" || echo "禁用路径零改动（预期）"
git diff --name-only origin/main...HEAD | grep -E '^(offline/corpus|offline/eval|offline/chunkers)/' \
  && echo "FAIL：语料/评测/切分器被改" || echo "语料/评测/切分器零改动（预期）"
git diff --name-only origin/main...HEAD | grep -E '^src/main/java/com/opspilot/action/' \
  | grep -v 'ReadOnlyActionExtractor.java' && echo "FAIL：action/ 其他文件被改" || echo "action/ 仅允许文件（预期）"
git diff -U0 -- src/main/resources/application.yml

# AC-3 领域词门闩 + 变异
python scripts/check_domain_words.py --check
python scripts/check_domain_words.py --self-test
grep -rn '"止损操作"\|"排查"\|"联系值班 SRE"' \
  src/main/java/com/opspilot/{ingest,storm,auth,cache,store,metrics,resilience} \
  src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java \
  --include=*.java || echo "零命中（预期）"
grep -rn "ServiceLoader\|plugin" src/main/java || echo "零命中（预期）"
grep -rn 'Files\.\|Path\.of\|Socket\|URL' src/main/java/com/opspilot/domain/ || echo "零命中（预期）"

# AC-5 DBA 语料
python - <<'PY'
import json, pathlib
root = pathlib.Path("offline/domains/dba/corpus")
docs = sorted(root.glob("**/*.md"))
chunks = [json.loads(l) for l in (root / "chunks.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
print("docs=", len(docs), "chunks=", len(chunks))
assert len(docs) >= 5 and len(chunks) >= 25
assert all(c["doc_id"].startswith("dba-") for c in chunks)
assert all("action_class" not in c["metadata"] or c["metadata"]["action_class"] in (None, "") for c in chunks)
print("DBA 语料规模/前缀/无 L1 声明 OK")
PY
python -m pytest offline/tests -q

# AC-2 / AC-4 / AC-6 / AC-7（Java）
source scripts/java_home.sh
mvn -o test 2>&1 | tail -30     # 全绿；核对 Tests run 数

# AC-8 文档与门闩
python offline/doc_numbers.py --check
python offline/provenance.py --check
python scripts/check_public_copy.py --check
bash scripts/check_panel_contract.sh

# AC-7 端到端回归（默认 sre 包）
bash scripts/redteam.sh

# AC-2 既有断言未被改动（硬）
git diff --name-only origin/main...HEAD \
  | grep -E '^src/test/java/com/opspilot/(action|llm|ingest|gateway)/' \
  && echo "FAIL：既有测试被改" || echo "既有测试零改动（预期）"
~~~

## Forbidden

- 禁止新增/放宽任何 L0 或 L1 命令、改 `ReadOnlyCommandPolicy` / `ControlledCommandPolicy` /
  `CommandPolicyData` / `command-policy.json`；DBA 语料里判不过的命令只能移进止血段。
- 禁止让 pack 覆盖底座安全条款、`## 问题定位` 协议头、权限/租户/审计/降级/逐字护栏。
- 禁止 SPI / `ServiceLoader` / 独立 Maven 模块 / 同进程多域 / 按租户切域 / 热加载 / HTTP 上传；
  pack 只从 classpath 固定路径读取。
- 禁止新增 `dba` 角色或新的权限维度；领域是部署选择。
- 禁止把 DBA 语料放进 `offline/corpus/`（会漂移 SRE 计数与 `provenance`）。
- 禁止发明事故、错误码或 `source_incident`；每篇必须指回既有事故或 OPS 段落。
- 禁止改 / 删 / 弱化既有断言；禁止跳测试不记录；禁止关 lint / typecheck 消警。
- 禁止实现方自宣通过；Verifier 结论必须来自亲自重跑的命令输出。
- 禁止在共享工作区验证；禁止提交凭据、审计原文或 `offline/staging/` 产物。

## 门闩时序（与 ADR-0029 R1 一致）

- 本契约**新增** `scripts/check_domain_words.py` 并进 CI（`provenance` job 一步），但扫描面
  **只**覆盖 R1 的干净底座包 + `ingest/`；`action/` **仍不纳入**——`TOOLS`/`VERBS`/SQL 词表
  还是底座内容，立闸会对合法机制假红。
- `action/` 纳入条件沿用 R1："第一个非 SRE 命令家族出现，且家族尾部校验已移到策略接口"。
  本契约没有新家族，条件未达成；DBA 的 L1 家族等 D3 的三件机制前置。
- 门闩只扫字符串字面量与文本块，注释允许解释机制；词表从 pack 现取，不手写；配 `--self-test`
  的变异极性检查。

## 处置路径（契约冻结后）

1. 人审本契约 + ADR-0031 → 书面冻结（判决：派实现 / 退回修订 / 不派）。
2. 派实现：**另一会话、独立 worktree**，只改 Allowed paths。
3. 全部判据由**全新会话**的 `verifier` 在干净 worktree 亲跑。
4. 人审四问（证据充分性 / 判据纪律 / 变更面封闭 / 遗留边界）→ 书面签收 → squash 合并。
5. 另起 `chore(accept)` 提交追加签收记录，不改判据。

任一条 FAIL → 退回实现方，契约不变，修复后从同一契约重验。

## 契约修订记录

### R0（2026-11-16，草案）

- 初始草案：基于 ADR-0029 accepted（首个切面已合并 `7760c40`）与 ADR-0031 草案，选定数据库
  值班为第二参考实现；冻结预计变更面、判据与验证命令；**未实现、未冻结**。
- 起草时事实：`grep -rli plugin src/main/java` 零命中；`scripts/check_domain_words.py` 不存在；
  SRE 语料 448 chunks、41 个 DB 错误码 chunk、5 篇 DB 文档；`action/` 的 L0 工具词仍在底座。
- **待 Lead 拍板的开放问题**见 ADR-0031「开放问题」五条（选域 / `opspilot.domain` 语义 /
  协议头与安全条款 / 语料真实性门槛 / 指纹延后）。
