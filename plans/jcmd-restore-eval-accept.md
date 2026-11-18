# 契约：jcmd 只读能力恢复评估（#91 后续切片）

## 背景与触发

- Issue #91 按 A 方案删除了 `jcmd` 死面（PR #95，squash `d3af41d`）：数据文件 `verbs`
  去 `jcmd` 键（9 家族 / 78 动词 / 56 工具不变），`KNOWN_TOOLS` 62、`VERB_TOOLS` 9，
  15 条形态矩阵全拒，红队 cc-004~006 锁死「契约不得出现 jcmd」。
- 签收时登记的遗留边界：`jcmd` 的独有能力（如 `VM.system_properties`）暂不可达；
  **恢复须另立扩权评审**。本契约即该评审切片。
- 性质：**评估切片**——交付物是 Lead 决策 + 文档落档（ADR-0017 附记 / OPS 表）；
  **不含任何机制改动**。若决策选 B（恢复），机制实现须在本契约 R1+ 修订中追加实现判据后
  另派实现，不得由本 R0 直接授权。

## 事实基线（2026-11-19 实测）

1. **不可达的根因未消失**：`ReadOnlyCommandPolicy` 的动词按**第二 token** 收敛
   （`kubectl get`、`redis-cli info` 形态），而 `jcmd` 的诊断形态是
   `jcmd <pid> <SubCommand>`——第二 token 是数字 PID，第三 token 才是命令
   （且形如 `VM.system_properties`，大写开头）。原 10 家族全形态不可达即因此；
   恢复不是"把键加回数据文件"而是**机制级改动**（schema v2 或家族特判）。
2. **PR #95 的锁会挡住恢复**：`ReadOnlyCommandPolicyTest.jcmdRemovedFromClosedSet`
   15 条矩阵把 `jcmd 1234 VM.version` 等锁为全拒（AC-3 权威矩阵）；红队
   cc-004~006 断言 `actions_not_containing: ["jcmd"]`；runner.py 逐字 token 边界检查。
   恢复须改这三处锁，属**扩权**，人审等级升为高。
3. **现有工具的覆盖映射（评估核心）**：jcmd 常用诊断子命令与白名单内既有工具的
   等价关系——
   - `Thread.print` ≈ `jstack <pid>`（已在 tools）
   - `VM.system_properties` / `VM.flags` ≈ `jinfo <pid>` / `jinfo -flags <pid>`（已在 tools）
   - `GC.class_histogram` ≈ `jmap -histo <pid>`（已在 tools）
   - `GC.heap_info` ≈ `jmap -heap <pid>` / `jstat -gc <pid>`（已在 tools）
   - `VM.command_line` ≈ `ps -fp <pid>` / `cat /proc/<pid>/cmdline`（已在 tools）
   - `VM.uptime` / `VM.version` ≈ `jinfo <pid>`（已在 tools）
   - 真正无等价物的：`VM.native_memory`（需 NMT 预埋）、`ManagementAgent.*`（属配置变更，
     本就不该进只读面）、`GC.run`（**写语义**，永不放行）。
4. **语料零使用**：`offline/corpus/` 中 jcmd 已零出现（#95 清理后），rb-115/rb-103
   已只读化为既有工具形态。

## 选项（Lead 三选一）

### A：维持现状 + 映射落档（推荐）

- L0 白名单不恢复 `jcmd`；把第 3 条覆盖映射写进 ADR-0017 附记（或独立小节），
  语料侧补一条「jcmd 诊断 → 既有工具等价命令」的 runbook 映射段（仅文档/语料，
  过既有护栏与逐字性判据）。
- **代价**：≈0 机制风险；**收益**：排障指引闭环，SRE 不会因 jcmd 被拒而无路可走。
- 落档改动面：`docs/adr/0017`、`OPS.md`（债务闹钟表，如有）、语料 1 个文件 + 派生。

### B：选择性恢复（扩权，需机制改动）

- `jcmd` 以**复合动词家族**回归（对标 redis-cli 先例）：子命令闭集（如
  `VM.version` / `VM.uptime` / `VM.flags` / `VM.command_line` / `VM.system_properties` /
  `Thread.print` / `GC.class_histogram` / `GC.heap_info` / `help`），
  `GC.run` / `ManagementAgent.*` / `JVMTI.*` 永不入集。
- 需解决：① PID 形态的判定路径（schema v2 加 `argPattern` 或家族特判，二选一须在
  R1 拍板）；② 大小写纪律（loader 不折叠，token 逐字入库——上次的坑）；③ 改三处锁
  （15 条矩阵改为"闭集内 true / 闭集外 false"的**正负混合矩阵**，cc-004~006 改为
  `actions_not_containing: ["GC.run", "ManagementAgent"]` 或逐命令白名单断言）；
  ④ 计数同步（家族 9→10、`KNOWN_TOOLS` 62→63 等）与 doc_numbers/README/repo-map。
- **代价**：机制改动 + 扩权评审 + 锁改签收；**收益**：仅在第 3 条映射无法满足的
  场景（NMT 等）有意义。

### C：语料侧替代（不改 L0，仅内容）

- 不动 ADR/机制，只做选项 A 的语料映射段（无 ADR 附记）。**最小改动**，但
  「jcmd 独有能力」的评估结论不留档，后续会被重复问起。

## 推荐理由

推荐 **A**：覆盖映射显示 jcmd 的诊断能力在白名单内已基本闭环，真正独有的
`VM.native_memory` 属 niche 且需 NMT 预埋；而 B 的机制成本（PID 形态判定 + 三处锁改 +
扩权签收）与收益不成比例。A 的落档让评估结论可追溯，避免 #91 式重复评审。

## Acceptance criteria 草案（仅 A/C 落档部分；B 若选须 R1 追加）

- **AC-1 变更面封闭**：`git diff --name-only origin/main...HEAD` 恰为
  Allowed paths（ADR-0017、OPS.md 如需、语料文件及其 chunks 派生、PROVENANCE、
  grounding_report、数字引用文档）；src/ 零 diff。
- **AC-2 映射完备**：ADR 附记逐条列出 jcmd 只读子命令 → 既有工具的等价命令，
  并明确「无等价物清单」（VM.native_memory 等）与「永不放行清单」（GC.run、
  ManagementAgent.*）。
- **AC-3 锁不弱化**：15 条矩阵、cc-004~006、KNOWN_TOOLS=62 / VERB_TOOLS=9
  零改动；语料新增段落过 ReadOnlyActionExtractor 逐字性/段名安全测试。
- **AC-4 同代**：`chunks.jsonl` 重生成零意外 diff；`doc_numbers` /
  `provenance` / `answer_eval --check` / `check_public_copy` / `check_panel_contract`
  全退 0；grounding report 同代重生成（如语料变更）。
- **AC-5 可观测**：单测数不变（纯文档/语料切片不新增 Java 测试；如语料段落触发
  抽取器新行为，以既有测试锁为准，不新增白名单形态）。

## Verification commands 草案

```bash
git rev-parse HEAD; git status --short          # 须为空
git diff --name-only origin/main...HEAD | sort  # AC-1
git diff --name-only origin/main...HEAD -- src/ && echo "FAIL：src 不应改" || echo "src 零 diff（预期）"
cd offline && python chunkers/build_chunks.py && cd ..
git diff --exit-code -- offline/corpus/chunks.jsonl   # 确定性重生成零 diff（预期）
python offline/doc_numbers.py --check
python offline/provenance.py --check
cd offline && python eval/answer_eval.py --check && cd ..
python scripts/check_public_copy.py --check
bash scripts/check_panel_contract.sh
E:\java\apache-maven-3.9.14\bin\mvn.cmd -o test   # 492/0/0/0（数量以实测为准）
python -m pytest offline/tests -q                 # 656 passed（数量以实测为准）
```

> Windows 控制台跑 Python 判据须 `PYTHONUTF8=1`（GBK 假红，#94 R3 教训）；
> 语料变更后红队 cc-004~006 受控 chunk 的检索命中须复跑红队确认。

## 开放问题（须 Lead 拍板，未拍板不实现）

- **Q1：选 A / B / C？**（本契约 R0 推荐 A）
- **Q2（仅当选 A）**：ADR-0017 附记 vs OPS §5 触发线，映射表落在哪一处？
  草案倾向 ADR-0017（单一事实源），OPS 只留指针。
- **Q3（仅当选 B）**：PID 形态判定走 schema v2（数据驱动）还是家族特判（代码驱动）？
  以及三处锁的改法授权（矩阵正负混合 + cc-004~006 断言改写）需在 R1 逐条冻结。

## 风险与开放问题（最多 5 条）

1. 语料新增映射段可能触发 `ReadOnlyActionExtractor` 对既有工具新形态的抽取
   （如 `jinfo -flags <pid>`），需金标准/变异性测试确认无新的误放行。
2. 选项 A 的映射段若写进语料，`chunks_sha256` 变化会连带 grounding report /
   PROVENANCE 同代重生成（与 #94 R3 同一流程）。
3. 选项 B 若被选中，本 R0 的 AC 草案全部作废，须 R1 重写——评估切片的产出
   不应被直接复用为实现授权。
4. ADR-0017 附记的"现势以本附记为准"链条已有两层（2026-11-18 删除附记），
   再加一层后建议 Lead 考虑收敛为独立 ADR 或状态表，避免附记链过长。
5. `jinfo <pid>` 无参数打印 sysprops+flags 的行为在 JDK 17+ 有弃用告警，
   映射表须标注 JDK 版本口径，避免指引给出deprecated命令。

## 处置路径（契约冻结后）

1. 人审本契约 → 书面冻结（判决：A / B / C + 是否派实现）。
2. A/C 的落档实现派**另一会话、独立 worktree**，只改 Allowed paths。
3. 全部判据由**全新会话**的 `verifier` 在干净 worktree 亲跑。
4. 人审四问 → 书面签收 → squash 合并；另起 `chore(accept)` 追加签收记录。
5. 若选 B：R1 重写为机制契约后按完整验收链路重走。

任一条 FAIL → 退回实现方，契约不变，修复后从同一契约重验。

## 修订记录

### R0（2026-11-19，草案）

- 初始草案：#91 A 方案遗留的「jcmd 独有能力恢复」评估；给出 A 维持现状 + 映射落档 /
  B 选择性恢复（扩权）/ C 语料侧替代 三选一，推荐 A。
- **未冻结、未实现**；等待 Lead 人审与 Q1-Q3 拍板。

### R1（2026-11-19，冻结：Lead 选 A）

- **Lead 决定**：Q1 选 **A 维持现状 + 映射落档**（书面"Q1:选A"）；B/C 否决留档。
- **Q2 裁定**：Lead 委托判断，按草案倾向定为 **ADR-0017 为映射表单一事实源**，
  OPS §5 只留一行指针指向 ADR；签收时如不同意可改判（只影响落档位置，不影响判据）。
- **Q3 不适用**（未选 B）。
- **落档形态细化（判据澄清，非改动）**：
  - ADR-0017 新增附记「2026-11-19 jcmd 恢复评估（Lead 选 A）」：逐条映射表
    （契约事实基线第 3 条）+ 无等价物清单（`VM.native_memory`，需 NMT 预埋）+
    **永不放行清单**（`GC.run` / `ManagementAgent.*` / `JVMTI.*`，写或配置语义）+
    JDK 版本口径注（`jinfo <pid>` 无参打印 sysprops+flags 在 JDK 17+ 有弃用告警）。
  - OPS §5 追加一行指针（不改触发线判据）。
  - 语料新增 **`offline/corpus/runbooks/rb-116-jvm-runtime-diagnostics.md`**
    （rb-1xx 本机系列顺延）：映射段中 jcmd 形态**只作行内文本**（不进围栏块，
    否则会被抽取并被 L0 拒、诊断叶无 actions），既有工具等价命令用**围栏块**
    （过 L0 产生 actions，金标准/逐字性判据不受影响）。
- **Allowed paths（冻结）**：`docs/adr/0017-read-only-action-contract.md`、`OPS.md`、
  `offline/corpus/runbooks/rb-116-jvm-runtime-diagnostics.md`、
  `offline/corpus/chunks.jsonl`（确定性重生成）、
  `offline/eval/reports/PROVENANCE.json`（--stamp 重登记）、
  `offline/eval/reports/grounding_report.json`（如语料变更，按 #94 R3 流程
  memory+mock 重生成）、`docs/DEMO.md`、`README.md`、`docs/repo-map.md`
  （后三者仅 chunks/文件计数与测试数同步）。
- **明确不在 Allowed paths**：`src/**`（含三处锁：15 条矩阵、cc-004~006、
  KNOWN_TOOLS/VERB_TOOLS）、`offline/redteam/**`、`scripts/**`、
  `offline/eval/golden_dataset.jsonl`（零 diff）、`application.yml`、其余契约文件。
- **冻结**：判据口径未变（AC-1…AC-5 为 A/C 落档子集，语料围栏纪律为澄清）；
  授权派实现。

### R1 补充（2026-11-19，冻结后边界勘误：计数行同步）

- **触发**：实现方实测——`offline/doc_numbers.json` 登记 `postmortem_runbook_docs`
  （runbooks + postmortems 文件数），唯一 cited_in 为 `docs/positioning-aiops.md:15`
  的「68 篇复盘/Runbook」；新增 rb-116 后真值变 69，`doc_numbers --check` 必 FAIL，
  而该文件不在 R1 冻结的 Allowed paths 内。属「Allowed paths 与检索型判据自相矛盾」
  的已知同型问题（与 #94 R2/R3 同源）。
- **Lead 裁定**：Allowed paths **补进 `docs/positioning-aiops.md`**，仅限
  「68 篇」→「69 篇」计数行，不改口径文字；`doc_numbers.json` 仍不在 Allowed paths
  （登记表是判据本体，改它等于削门闩，禁止）。
- **判据未变**：AC-1…AC-5 口径不变；本次只修"检索型计数引用与变更面冻结的冲突"。

## 人审签收（2026-11-19）

- **签收人**：Lead（shing26，书面"签收 PR #97"）；**合并**：squash → `1673517`
  （分支 `feat/jcmd-restore-docs-impl` 保留，head `e8844d8`）。
- **验证历程**：单轮独立 verifier。实现由另一会话在独立 worktree 完成
  （自报全绿，不构成证据）；verifier 在干净 worktree `opspilot-verify-jcmd-restore`
  亲跑 AC-1…AC-5 全部 PASS，验证首尾 `git status --short` 为空；CI 11/11 全绿。
- **关键证据**（verifier 亲跑）：变更面 8 文件恰为 Allowed paths 子集、src/** 与
  三处锁（15 条矩阵 / cc-004~006 / KNOWN_TOOLS=62 / VERB_TOOLS=9）零 diff；
  `build_chunks.py` 确定性零 diff（chunks=459）；`doc_numbers`（33 条/48 处）、
  `provenance`（chunks=459、golden=68、语料 70 篇）、`answer_eval --check`
  （answers=11 / codes=21 / rate=100%）、`check_public_copy`、`check_panel_contract`
  全退 0；`mvn -o test` 492/0/0/0；`pytest` 656 passed；红队 35/35、SKIP 0、泄漏 0；
  接地报告 `chunks_sha256` 与 PROVENANCE、文件实算三方一致。
- **人审四问**（签收即表示无异议）：
  1. **证据充分性**：无异议——verifier 亲跑、实现方日志未被采信、证据可重跑。
  2. **判据纪律**：无异议——评估切片判据（AC-1…AC-5）未动；三处锁零改动；
     rb-116 围栏纪律实测满足（围栏内 jcmd 计数=0，命令全在白名单）。
  3. **变更面封闭**：无异议——恰 8 文件；R1 补充的 `docs/positioning-aiops.md`
     仅计数行；禁用路径零 diff；`doc_numbers.json` 未动。
  4. **遗留边界**：**接受为已知项**——见下"登记的偏离与边界"。
- **禁令复核**：未以改/删/弱化断言换通过、未跳测试不记录、未关检查消警、
  实现方未自宣通过、未绕过人审、本次合并有书面签收。

### 登记的偏离与边界

- **V4 语态门 mock 口径 2 项 FAIL**：与 #94 R3 同一已知形态产物（mock-template
  固定答案无 hedge）；V9 接地 PASS（codes=21、无据 0），live 口径的 V4 门不弱化。
- **验证程序回灌（R1，不改判据）**：① 本机系统 `bash` 指向 WSL
  （`C:\Windows\System32\bash.exe`）会无限挂起，`check_panel_contract` 与红队须用
  `E:\Git\bin\bash.exe` 全量执行；② verifier 复跑红队会重生成
  `offline/redteam/report/*`（时间戳/耗时/action 计数 diff，35 项无翻转）——验证后
  须按 HEAD 还原，`offline/redteam/**` 保持冻结零 diff。
- **决策留档链**：jcmd 能力口径现由 ADR-0017 附记三层承载（2026-11-18 删除附记 →
  2026-11-19 评估附记）；如再演进，建议收敛为独立 ADR 或状态表，避免附记链过长
  （契约风险条 4 的既有建议，未变）。
