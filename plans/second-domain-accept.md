# 验收契约（薄切片草案，未冻结）：第二个参考实现——数据库值班领域包（Domain Pack）

> **状态：冻结（2026-11-17 人审签收；未实现；实现授权已授予，派独立会话）。** 本契约只
> 冻结"做什么、怎么验"，不包含任何实现。判据、Allowed paths、验证命令在 Lead 书面签收前都
> **不是**冻结面；冻结后不得再改，改判据走「契约修订记录」并由 Lead 重新批准。
>
> 判定：**必须走完整验收链路**——本改动触碰语料来源与 L2 静态 SOP 的段名判据，属"业务逻辑"
> 档。生成与裁决分离：契约由设计会话撰写；实现须由**另一会话在独立 worktree** 完成；裁决由
> **全新会话**的 `verifier` subagent（Read/Grep/Glob/Bash）亲自复跑，实现方贴的日志与自评不
> 构成证据；合并前须人审四问签收。
>
> **薄切片口径（Lead 2026-11-16 决定）**：本契约只做**语料来源 + SOP/诊断段名**两件事。
> Prompt/文案外置**整体推迟**到下一份契约；本契约零改动 `PromptAssembler` / `ChatOrchestrator` /
> `SseEvents` / `OpenAiChatSink` / `MockEngine`。

## Task ID

`second-domain-accept`

## 证据绑定（契约起草时）

- **main tip（本稿更新）**：`04acce6`（2026-11-16，PR #83 签收）；设计稿基于 `7df3c03`
  （PR #86 领域词门闩），`7df3c03..04acce6` 对 `domain/`、`ingest/`、`action/`、`config/`
  **零改动**，故 AC-2/AC-5/AC-6 的事实不变。
- **设计分支**：`design/second-domain`，已把 `origin/main` 合入（merge `4a86bc6`；2026-11-17
  再合入 `04acce6`，不 rebase）。
- **契约/ADR 的位置**：`plans/second-domain-accept.md` 与
  `docs/adr/0031-second-reference-implementation.md` 是**先合入 main 的输入**；实现分支从
  包含它们的 main 切出，因此它们**不出现在实现 diff 里**。契约提交单独登记，不用它反推实现
  HEAD（见 AC-9）。
- **验证目标 SHA**：实现分支 head（`git rev-parse HEAD`）。
- 起草时 `git status --short`：空（干净 worktree）。
- 验证必须在**独立干净 worktree** 中进行，禁止共享工作区。

## 前置事实（评审可复核）

| 事实 | 位置 |
| --- | --- |
| SOP 段名焊在底座 | `src/main/java/com/opspilot/ingest/IngestionRunner.java:491`（`contains("止损操作")`） |
| 诊断段名/排除词焊在底座 | `src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java:40`、`:44` |
| 语料路径默认值写了两遍 | `IngestionRunner.java:90`、`src/main/java/com/opspilot/config/MockIdfInitializer.java:29` |
| 切分器不产出 `action_class` | `offline/chunkers/markdown_tree_chunker.py`（front matter 只映射 service/env/auth_level/error_codes/tenant） |
| 切分器已支持第二语料根 | `offline/chunkers/build_chunks.py` 的 `--corpus` / `--out`（两参数均限制在 `offline/` 内） |
| 语料中的 DB 内容 | 41 个 chunk 带 50012/50013/50101/50141；5 篇 DB 文档（rb-001、rb-002、ac-508、pm-001、pm-002） |
| 领域词门闩**已存在** | `scripts/check_domain_words.py`（PR #86 / `7df3c03` 合并）；扫描面为 `storm/auth/cache/store/metrics/llm.VerbatimGuard/resilience`（除 `SopFallbackService`），**不含 `ingest/` 与 `action/`** |
| 门闩的两条既有注释例外 | `auth/UserAdminCli.java:205` 的 `sre-full`、`metrics/AuditService.java:129` 的 `kubectl`；默认模式 `EXEMPT` 退 0，`--strict` 因这两条退 1 |
| `action/` 的 L0 工具词仍在底座 | `ReadOnlyCommandPolicy.java:63-110`（`TOOLS` / `VERBS`） |

## 切面选型（五件契约：动两件、等两件、一件不动）

| 契约 | 本切面 | 判据 |
| --- | --- | --- |
| 语料来源 | **动（数据）** | 第二语料根 `offline/domains/dba/corpus/`，用既有 chunker 生成；manifest 声明默认路径 |
| SOP / Runbook | **动（数据）** | 止损段/诊断段/排除词从 pack 读；`IngestionRunner` 与 `ReadOnlyActionExtractor` 共用声明；键/TTL/租户分片不动 |
| Prompt / 文案 | **等** | 本切面零改动生成面与错误文案；触发条件=薄切片通过独立验收后另立契约 |
| 指纹归一化 | **等** | 第三种规则（保留 MySQL errno/SQLSTATE）是上下文相关的，不能表达为闭集形状；DBA 探针只用应用错误码 |
| 命令族 | **不动** | 首个切面已外置内容；本契约**命令判定机制零改动**，只改 `ReadOnlyActionExtractor` 的段名来源 |

**这次不解锁**：Prompt/文案外置、指纹归一化接口、SPI/`ServiceLoader`、独立 Maven 模块、同进程
多域、按租户切换域、热加载/HTTP 上传、DBA L1 家族、L0 工具集合变更、`dba` 角色/账号维度、
把 `ingest/` 或 `action/` 加进现有词法门闩的扫描面。

## 变更面（预计实现文件 14 个 + 1 组语料目录）

**代码 / 配置（8）**

```
src/main/java/com/opspilot/domain/DomainPack.java                 （新增）
src/main/java/com/opspilot/domain/DomainRegistry.java             （新增）
src/main/java/com/opspilot/ingest/IngestionRunner.java
src/main/java/com/opspilot/config/MockIdfInitializer.java
src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java    （只改段名来源）
src/main/resources/application.yml                                （仅 opspilot.domain）
src/main/resources/domain/sre/domain.json                         （新增）
src/main/resources/domain/dba/domain.json                         （新增）
```

**测试（3）**

```
src/test/java/com/opspilot/domain/DomainPackTest.java             （新增）
src/test/java/com/opspilot/domain/DomainContentTest.java          （新增）
offline/tests/test_domain_pack.py                                 （新增）
```

**文档同步（3）**

```
README.md                                                         （单测数现算同步）
docs/repo-map.md                                                  （domain/ 与 offline/domains/ 包地图 + 单测数）
OPS.md                                                            （§5 债务行：薄切片状态与下一契约顺序）
```

**语料目录（1 组，按组计不按篇计）**

```
offline/domains/dba/corpus/**                                     （≥5 篇 .md + chunks.jsonl）
```

`README.md`、`docs/repo-map.md` 的单测数变化由 `offline/doc_numbers.py` 现算强制同步；若实现
未新增 `@Test`，视为 AC-6 未兑现。

## Allowed paths

- `src/main/java/com/opspilot/domain/DomainPack.java`、`DomainRegistry.java`（新增，路径与类名固定）
- `src/main/java/com/opspilot/ingest/IngestionRunner.java`（只改语料默认路径与止损段来源）
- `src/main/java/com/opspilot/config/MockIdfInitializer.java`（只改语料默认路径来源）
- `src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java`（只改诊断段名/排除词来源；
  `extract` 与只读判定逻辑不动）
- `src/main/resources/application.yml`（**只允许**新增 `opspilot.domain` 一行及注释）
- `src/main/resources/domain/sre/domain.json`、`src/main/resources/domain/dba/domain.json`
- `offline/domains/dba/corpus/**`（新增；`.md` 与 `chunks.jsonl` 均入库）
- `src/test/java/com/opspilot/domain/DomainPackTest.java`、`DomainContentTest.java`（新增）
- `offline/tests/test_domain_pack.py`（新增）
- `README.md`、`docs/repo-map.md`、`OPS.md`（文档门闩与债务行同步）
- 验证临时产物**只许**落在 `offline/staging/`（`.gitignore` 覆盖）或系统临时目录，**不入库**

**明确不在 Allowed paths**：`docs/adr/0031-second-reference-implementation.md` 与
`plans/second-domain-accept.md`（已先合入 main，实现不得改）、`scripts/check_domain_words.py`、
`offline/tests/test_check_domain_words.py`、`.github/workflows/ci.yml`、`src/main/java/com/opspilot/llm/**`、
`src/main/java/com/opspilot/gateway/**`、`src/main/resources/domain/**/prompt.json`（本切面不存在
这个文件）。

## Acceptance criteria

**AC-1 变更面封闭（只统计实现文件）。** `git diff --name-only origin/main...HEAD` 的每一项都
必须落在 Allowed paths 内，且必须恰为下列 14 个实现文件 + 语料目录组：

- 8 个代码/配置：`DomainPack.java`、`DomainRegistry.java`、`IngestionRunner.java`、
  `MockIdfInitializer.java`、`ReadOnlyActionExtractor.java`、`application.yml`、
  `domain/sre/domain.json`、`domain/dba/domain.json`；
- 3 个测试：`DomainPackTest.java`、`DomainContentTest.java`、`offline/tests/test_domain_pack.py`；
- 3 个文档：`README.md`、`docs/repo-map.md`、`OPS.md`；
- 语料组：`offline/domains/dba/corpus/**`（≥5 篇 `.md` + `chunks.jsonl`）。

**契约与 ADR 不出现在实现 diff 里**：`plans/second-domain-accept.md` 与
`docs/adr/0031-second-reference-implementation.md` 的改动必须为零（它们是先合入 main 的输入）。

以下路径**零 diff**（硬）：`src/main/java/com/opspilot/action/**`（`ReadOnlyActionExtractor`
除外）、`auth/**`、`cache/**`、`retrieval/**`、`store/**`、`storm/**`、`resilience/**`、
`llm/**`、`gateway/**`、`src/main/resources/policy/command-policy.json`、`offline/corpus/**`、
`offline/eval/**`、`offline/chunkers/**`、`scripts/**`、`.github/**`、
`src/test/java/com/opspilot/{action,llm,ingest,gateway}/**`、`pom.xml`、`Dockerfile`、
`docker-compose.yml`。
`application.yml` 的 diff 只允许出现 `opspilot.domain` / `OPSPILOT_DOMAIN` 与注释行。

**AC-2 SRE 包字段等价（默认路径不能变）。** 未设 `OPSPILOT_DOMAIN` 时：
- `DomainPack.id()=="sre"`；`corpusPath()=="offline/corpus/chunks.jsonl"`；
  `stopLossMarkers()==["止损操作"]`；`diagnosticMarkers()==["排查"]`；
  `diagnosticExcludes()==["陷阱","误判"]`；
- `DomainPack` **只有**这五个字段，没有 prompt/onCallName/文案槽位；
- 既有测试**零改动、零弱化**：`ReadOnlyCommandPolicyTest` 18 条、
  `ControlledCommandPolicyTest` 8 条、`ActionOpsServiceTest` 13 条、
  `ReadOnlyActionExtractorTest` 10 条、`VerbatimGuardTest` 10 条、`PromptAssemblerTest` 4 条
  全过；这些**测试文件零 diff**——兼容靠旧构造器重载（test-only，委托到 `DomainPack.sre()`），
  生产 bean 走带 `DomainPack` 参数的注入构造器；
- `mvn -o test` 全绿；`bash scripts/redteam.sh` 32/32、0 泄漏、0 SKIP。

**AC-3 复用现有领域词门闩，不重复实现。**
- `python scripts/check_domain_words.py` 退 **0**；输出恰好 2 条 `EXEMPT`（`auth/UserAdminCli.java:205`
  的 `sre-full`、`metrics/AuditService.java:129` 的 `kubectl`），两条例外原样保留；
- `python scripts/check_domain_words.py --strict` 退 **1**，且只命中上述两条——这是既有状态，
  **不属于本切面**；实现不得改脚本、测试或 CI；
- `git diff --name-only origin/main...HEAD | grep -E 'scripts/check_domain_words.py|offline/tests/test_check_domain_words.py|\.github/workflows/ci.yml'`
  零命中；
- 直接 grep 钉住已移走的字面量：`"止损操作"`、`"排查"`、`"联系值班 SRE"` 在
  `src/main/java/com/opspilot/ingest/` 与
  `src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java` 零命中；
- `grep -rn "ServiceLoader\|plugin" src/main/java` 零命中（Java 源码里用"领域包"，不写英文
  `plugin`）；
- `grep -rn 'Files\.\|Path\.of\|Socket\|URL' src/main/java/com/opspilot/domain/` 零命中
  （pack 只从 classpath 读）；
- `grep -rn 'DomainPack\.current' src/main/java` 零命中（无全局单例/静默回落）。

**AC-4 领域选择显式且 fail-closed。** `DomainPackTest` 逐条钉住：
- 未设 / 空串 → `sre`（默认，不自动回退）；`dba` → dba；未知 id、含 `/` 或 `..`、含首尾空白、
  大写（如 `DBA`）→ 启动失败并点名 id；
- 缺 manifest、未知 `schema_version`、未知顶层/嵌套键、空段名列表、
  `diagnosticMarkers` 与 `stopLossMarkers` 相交、段名含 `.*`/`(`/`|` 等正则元字符、
  `corpusPath` 为绝对路径或含 `..` 或不在 `offline/` 下 → `IllegalStateException`，
  不静默忽略、不回落默认值；
- **生产注入路径**：`DomainRegistry` 解析后由构造器注入
  `IngestionRunner` / `MockIdfInitializer` / `ReadOnlyActionExtractor`；无
  `DomainPack.current()`、无未初始化回落；未初始化状态在类型上不可表达；
- **旧构造器是显式重载**：旧签名委托到 `this(..., DomainPack.sre())`；`DomainPack.sre()` 从
  `domain/sre/domain.json` 严格解析，重载在 Javadoc 标注 test-only / 兼容路径。生产 bean 只走
  带 `DomainPack` 参数的注入构造器（标 `@Autowired`，由 `DomainRegistry` 供给）。既有测试文件
  零 diff；
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
- `ReadOnlyActionExtractor` 在 dba 包下抽取 `## 核查步骤`，不抽取 `## 止血与恢复`；段名集合
  相交时启动失败（AC-4）；
- **Prompt/文案不变**：`PromptAssemblerTest` 4 条原样通过，DBA 包下系统提示仍是 SRE 文案——
  这是薄切片的**已知限制**，不在本契约的判据内（`DomainContentTest` 不得断言 DBA 文案）；
- **MockEngine 不变**：mock 拒答/标题仍是 SRE 形状，`DomainContentTest` 不得用 mock 答案
  文案做 DBA 断言；
- 可选 live 探针（**非 CI、不计入裁决**）：`STORAGE_MODE=memory DASHSCOPE_MODE=mock
  OPSPILOT_DOMAIN=dba` 起服务，`/coverage` 的服务数/错误码数/块数与 DBA 语料现算值相等，
  3 条固定查询的 refs 全部是 `dba-*`；拒答文案仍是 SRE 口径（已知限制，不作断言）。

**AC-7 回归锁原样保持。** AC-1 的零 diff 清单 + 以下测试全过：只读契约 18 条、L1 白名单 8 条、
灰度 fail-closed 13 条、段名闸 10 条、逐字护栏 10 条、Prompt 4 条、`AuditService` 链式写入、
`ev=degrade_transition`、`scripts/l1_worm_gate.py` 口径不变、红队 32/32（默认 sre 包）、
领域词门闩退 0（2 条既有注释例外）。

**AC-8 全量测试与文档门闩。** `mvn -o test` 全绿；`python -m pytest offline/tests -q` 全绿；
`python offline/doc_numbers.py --check` 退 0（ADR 数 31、单测数现算）；`python offline/provenance.py
--check` 退 0（SRE 语料未动）；`python scripts/check_public_copy.py --check` OK；
`bash scripts/check_panel_contract.sh` OK；`python scripts/check_domain_words.py` 退 0。
**本切面不改门闩脚本/测试/CI**（`scripts/**` 与 `.github/**` 零 diff）。

**AC-9 证据绑定（实现 HEAD，不绑契约提交）。** 证据包写明：
- 实现分支 HEAD SHA（`git rev-parse HEAD`）；
- 验证时 `git status --short`（须为空）与独立 worktree 路径；
- DBA 语料生成命令与产物行数；
- **契约提交单独登记**：本契约与 ADR-0031 合入 main 的提交 SHA（设计分支的 `docs(adr)` +
  merge），仅作输入来源记录；**不得**用
  `git log -1 --format=%H -- plans/second-domain-accept.md` 反推实现 HEAD。
Verifier 亲自重跑全部命令。

## Verification commands（Verifier 亲自重跑，禁止引用实现方日志）

> 必须用**独立干净 worktree**。`mvn` 路径按验证机环境调整（本机为
> `/e/java/apache-maven-3.9.14/bin/mvn`）。

~~~bash
git fetch origin
git worktree add /tmp/opspilot-verify-second-domain origin/<实现分支>
cd /tmp/opspilot-verify-second-domain

# AC-9 证据绑定（实现 HEAD；契约/ADR 只作为输入来源单独登记）
git rev-parse HEAD
git status --short                                         # 须为空

# AC-1 变更面（只统计实现文件；契约/ADR 零 diff）
git diff --name-only origin/main...HEAD | sort
git diff --name-only origin/main...HEAD | grep -E '^(plans/second-domain-accept\.md|docs/adr/0031-second-reference-implementation\.md)$' \
  && echo "FAIL：契约/ADR 被实现分支改动" || echo "契约/ADR 零改动（预期）"
git diff --name-only origin/main...HEAD | grep -E '^src/main/java/com/opspilot/(action|auth|cache|retrieval|store|storm|resilience|llm|gateway)/' \
  | grep -v 'ReadOnlyActionExtractor.java' && echo "FAIL：禁用 Java 路径被改" || echo "禁用 Java 路径零改动（预期）"
git diff --name-only origin/main...HEAD | grep -E '^(offline/corpus|offline/eval|offline/chunkers|scripts|\.github)/' \
  && echo "FAIL：语料/评测/切分器/门闩/CI 被改" || echo "语料/评测/切分器/门闩/CI 零改动（预期）"
git diff -U0 -- src/main/resources/application.yml

# AC-3 复用现有门闩（不修改）
python scripts/check_domain_words.py
echo "gate_exit=$?"                                          # 须为 0
python scripts/check_domain_words.py --strict
echo "strict_exit=$?"                                        # 须为 1，且只命中两条既有注释例外
grep -rn '"止损操作"\|"排查"\|"联系值班 SRE"' \
  src/main/java/com/opspilot/ingest \
  src/main/java/com/opspilot/action/ReadOnlyActionExtractor.java \
  --include=*.java || echo "零命中（预期）"
grep -rn "ServiceLoader\|plugin" src/main/java || echo "零命中（预期）"
grep -rn 'Files\.\|Path\.of\|Socket\|URL' src/main/java/com/opspilot/domain/ || echo "零命中（预期）"
grep -rn 'DomainPack\.current' src/main/java || echo "零命中（预期）"

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

# AC-2 既有测试文件零改动（兼容靠旧构造器重载，断言不得改）
git diff --name-only origin/main...HEAD \
  | grep -E '^src/test/java/com/opspilot/(action|llm|ingest|gateway)/' \
  && echo "FAIL：既有测试文件被改" || echo "既有测试文件零改动（预期）"
~~~

## Forbidden

- 禁止改 `scripts/check_domain_words.py`、`offline/tests/test_check_domain_words.py`、
  `.github/workflows/ci.yml`；门闩是**复用**，不是重写。两条例外（`sre-full` / `kubectl`）
  原样保留。
- 禁止在本切片做 Prompt/文案外置：不改 `PromptAssembler`、`ChatOrchestrator`、`SseEvents`、
  `OpenAiChatSink`、`MockEngine`、`domain/**/prompt.json`。
- 禁止新增/放宽任何 L0 或 L1 命令、改 `ReadOnlyCommandPolicy` / `ControlledCommandPolicy` /
  `CommandPolicyData` / `command-policy.json`；DBA 语料里判不过的命令只能移进止血段。
- 禁止 `DomainPack.current()` 或任何"未初始化回落 SRE"的全局默认；生产路径只走
  `DomainRegistry` 显式注入。
- 禁止 SPI / `ServiceLoader` / 独立 Maven 模块 / 同进程多域 / 按租户切域 / 热加载 / HTTP 上传；
  pack 只从 classpath 固定路径读取。
- 禁止新增 `dba` 角色或新的权限维度；领域是部署选择。
- 禁止把 DBA 语料放进 `offline/corpus/`（会漂移 SRE 计数与 `provenance`）。
- 禁止发明事故、错误码或 `source_incident`；每篇必须指回既有事故或 OPS 段落。
- 禁止改 / 删 / 弱化既有断言；禁止跳测试不记录；禁止关 lint / typecheck 消警。
- 禁止实现方自宣通过；Verifier 结论必须来自亲自重跑的命令输出。
- 禁止在共享工作区验证；禁止提交凭据、审计原文或 `offline/staging/` 产物。

## 门闩时序（与 ADR-0029 R1 一致）

- 本契约**复用** main 上已有的 `scripts/check_domain_words.py`（PR #86），不新增、不改动、
  不新增 CI 步骤；判据是 `python scripts/check_domain_words.py` 退 0。
- 现有扫描面**不含 `ingest/` 与 `action/`**。本切面把 `ingest/` 里的段名字面量移走后，它变
  干净，但**把 `ingest/` 加进扫描面是后续小改动**，不在本切片；本切片用直接 grep 钉住那三个
  字面量零命中。
- `action/` 纳入条件沿用 R1："第一个非 SRE 命令家族出现，且家族尾部校验已移到策略接口"。
  本契约没有新家族，条件未达成。

## 处置路径（契约冻结后）

1. 人审本契约 + ADR-0031 → 书面冻结（判决：派实现 / 退回修订 / 不派）。
2. 派实现：**另一会话、独立 worktree**，只改 Allowed paths。
3. 全部判据由**全新会话**的 `verifier` 在干净 worktree 亲跑。
4. 人审四问（证据充分性 / 判据纪律 / 变更面封闭 / 遗留边界）→ 书面签收 → squash 合并。
5. 另起 `chore(accept)` 提交追加签收记录，不改判据。

任一条 FAIL → 退回实现方，契约不变，修复后从同一契约重验。

## 契约修订记录

### R3（2026-11-17，冻结）

## 人审签收（2026-11-18）

- **签收人**：Lead（shing26）；**载体**：PR #92；**合并**：squash → `5bfba3f`
  （分支 `feat/second-domain-impl` 保留，head `12594c8`）。
- **验证历程**：单轮独立 verifier。实现由另一会话在独立 worktree 完成
  （自报 Java 463 / Python 656 全绿，不构成证据）；verifier 在干净 worktree
  `opspilot-verify-second-domain` 亲跑契约 §8，**AC-1…AC-9 全部 PASS**，
  验证前后 `git status --short` 为空。
- **关键证据**（verifier 亲跑）：`mvn -o test` 463/0/0/0；`pytest offline/tests -q`
  656 passed；`check_domain_words.py` 退 0、`--strict` 退 1 且仅两条既有 EXEMPT；
  `doc_numbers` / `provenance` / `check_public_copy` / `check_panel_contract` /
  `redteam`（32/32）全退 0；DBA 语料重生成 SHA256 与入库 `chunks.jsonl` 一致；
  变更面恰 20 个 Allowed paths 文件，契约/ADR/门闩/既有测试/`pom.xml`/CI 零 diff。
- **人审四问**（签收即表示无异议）：
  1. **证据充分性**：无异议——verifier 亲跑、证据绑定实现 HEAD `12594c8`、
     CI 11/11 全绿可复核。
  2. **判据纪律**：无异议——无改/删/弱化断言、无跳测试不记录、无关 lint/typecheck、
     实现方自评未被采信；契约判据 R3 原样未动。
  3. **变更面封闭**：无异议——仅 20 个 Allowed paths 文件；`application.yml` 仅新增
     `domain` 一行及 2 行注释；禁用 Java 路径、`offline/corpus`、`scripts`、`.github`
     零改动。
  4. **遗留边界**：**接受为已知项**——本切片无 Prompt/文案外置与 golden 集（ADR-0031
     已登记）；`DomainContentTest` 未单列 `:code:` / `:service:` 键断言（代码路径零
     diff）；嵌套未知键由根键闭集结构性 fail-closed 覆盖。Issue #89（命令产额为 0）
     合并后另立契约；Issue #91（`jcmd` 大写动词不可达）走独立安全评审。
- **禁令复核**：未以改/删/弱化断言换通过、未跳测试不记录、未关检查消警、
  实现方未自宣通过、未绕过人审、本次合并有书面签收。

- **决定**：Lead（shing26）书面签收，契约由草案转为冻结；判据未改。
- **三项签收**：
  1. 批准薄切片范围（语料来源 + SOP/诊断段名），Prompt/文案推迟；
  2. 批准 DBA 语料 `source_incident` 回溯既有 SRE 文档 / `OPS.md` 标题，
     `error_codes` 限定既有集合；
  3. 契约冻结；实现授权授予，派独立会话。
- **实现约束**：实现分支从含本契约的 `main` 切出；实现方不得修改本契约与 ADR-0031；
  验收由全新 `verifier` 会话亲跑，实现方自评不构成证据。

### R2（2026-11-17，草案修订）

- **基底更新**：`origin/main` 前进到 `04acce6`；`7df3c03..04acce6` 对 `domain/`、`ingest/`、
  `action/`、`config/` 零改动，判据未改。
- **范围外显式登记**：2026-11-17 dogfood 发现的命令契约产额缺口（`rb-115`/`rb-103`
  在 Top-3 引用下 `actions=0`）**不在**本薄切片内——本切面只改段名来源，不改检索/chunking。
  另立 [issue #89](https://github.com/shing26/OpsShield/issues/89) 与后续契约；本契约不新增该 AC。

### R1（2026-11-16，薄切片收窄）

- **范围收窄**：只做语料来源 + SOP/诊断段名两件；Prompt/文案整体推迟到下一份契约。
  `DomainPack` 只承载 `id` / `corpusPath` / `stopLossMarkers` / `diagnosticMarkers` /
  `diagnosticExcludes` 五个字段；删除所有 prompt 槽位与文案改动。
- **门闩复用**：删除"新增 `scripts/check_domain_words.py`"和"新增 CI 步骤"的交付；改为复用
  main（PR #86 / `7df3c03`）已有门闩。AC-3 改为 `python scripts/check_domain_words.py` 退 0，
  两条既有注释例外保留；`--strict` 退 1 是既有状态，不属本切面。把 `ingest/` 纳入扫描面
  列为后续小改动。
- **契约旧坑修正**：AC-1 只统计实现文件（14 个 + 语料组），契约/ADR 是先合入 main 的输入、
  不出现在实现 diff；AC-9 绑实现 HEAD，删除"`git log -1 -- plans/...` 必须等于 HEAD"的判据，
  契约提交单独登记。
- **去掉静默回退**：删除 `DomainPack.current()` 未初始化回落 SRE 的设计；生产由
  `DomainRegistry` 构造器注入；既有构造器保留为 test-only 重载（显式委托到
  `DomainPack.sre()`），既有测试文件零 diff、断言零改动。
- **SRE 等价与测试**：AC-2 改为 SRE 字段等价 + 既有 18/8/13/10 测试；不再要求 prompt 黄金串。
- **文档一致**：标题/状态改为薄切片草案；ADR 的 D1–D7、备选、后果、开放问题同步收窄；
  事实表把"门闩不存在"改为"已由 PR #86 合并；扫描面不含 `ingest/`/`action/`"。

### R0（2026-11-16，初始草案；已被 R1 取代）

- 初始草案把语料、SOP、Prompt/文案三件一起做，并计划新增领域词门闩；该范围与交付已被
  Lead 收窄，原文不再有效。
- 起草时事实：SRE 语料 448 chunks、41 个 DB 错误码 chunk、5 篇 DB 文档；`action/` 的 L0
  工具词仍在底座。
- 待 Lead 拍板的开放问题见 ADR-0031「开放问题」四条（选域 / `opspilot.domain` 语义与注入 /
  语料真实性门槛 / 下一份契约顺序）。
