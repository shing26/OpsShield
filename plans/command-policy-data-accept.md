# 验收契约：L1 命令策略白名单内容外置（ADR-0029 首个切面）

> **状态：冻结（2026-11-16 人审签收；首个切面实现已通过独立验证并合并，PR #85 / `7760c40`；契约判据未改）。** 本契约只冻结"做什么、怎么验"，不包含任何实现。
> 判定：**必须走完整验收链路**——本改动触碰 L1 白名单闭集与只读命令契约两条安全构造保证，
> 属"业务逻辑 / 认证授权"档。生成与裁决分离：契约由设计会话撰写；实现须由**另一会话在独立
> worktree** 完成；裁决由**全新会话**的 `verifier` subagent（Read/Grep/Glob/Bash）亲自复跑，
> 实现方贴的日志与自评不构成证据；合并前须人审四问签收。

## Task ID

`command-policy-data-accept`

## 证据绑定（契约起草时）

- **main tip**：`c0b971ad5c32a796cad85e54ea6acaff655db896`（2026-11-16）
- **验证目标 SHA**：本契约落地提交之后的实现分支 head
  （`git log -1 --format=%H -- plans/command-policy-data-accept.md` 即为 Verifier 须绑定的 HEAD）。
- **起草时 `git status --short`**：仅 3 项未跟踪（`.dsh/`、`求职投递准备计划.md`、
  `简历项目经历草案.md`），与本任务无关；验证须在独立干净 worktree 中进行。

## 切面选型（三选一，rule of three 口径）

| 候选 | 第三个真实用例 | 本轮结论 | 提取后必须保持的回归锁 | 词法门闩何时立 |
| --- | --- | --- | --- | --- |
| ① **L1 命令策略白名单内容 → 数据**（机制留 `action/`） | **已有三个仓内消费者**：语料 `action_class` 声明（内容确认）／`ControlledCommandPolicy` 前缀·旗标·宾语闭集／`opspilot.l1.allowed-targets` 灰度配置（空值 fail-closed）。三者都是"内容由数据供给、判定留代码"的同一接口点 | **选它，但只外置 L1 内容**；不提取 SPI、不新增命令家族、L0 `TOOLS`/`VERBS` 暂留（内容量大且绑 18 条单测 + 活体探针，等加载器形状被证明后再另立契约） | L1 前缀/旗标/宾语闭集、逐字性、审计链、灰度 fail-closed、红队 32/32 | **不在本契约。** `action/` 仍含命令家族特有的尾部校验，现在立闸会假红或逼出空抽象；当**第三个领域**（非 SRE 命令家族）出现、且家族校验已移到策略接口后，与接口提取同一契约交付。干净底座包（`storm`/`resilience` 机制层/`auth`/`cache`/`store`/`metrics`/`llm.VerbatimGuard`）的词法门闩可独立另立，不阻塞本切面 |
| ② 去重键归一化策略 → `storm/FingerprintService` 策略接口 | **不存在。** 只有两个用例：查询指纹（`normalizeError`）与告警身份（`identity`，刻意不归一化）。第二个用例恰恰证明"归一化是内容策略"，但没有第三个归一化消费者；且改归一化会同时改 L1 缓存键、SingleFlight 组键、SSE meta、反馈归档键 | **再等**。先登记为待提取：等第三个领域带来第三种"掩什么/留什么"的规则（例如非文本告警身份）再提接口 | `FingerprintServiceTest`（9 条）、缓存命中语料、跨租户键隔离、审计列形状 | 不适用（`storm/` 已在干净底座包范围内，可先行立闸） |
| ③ Prompt/文案模板 → `llm/PromptAssembler` 模板契约 | **不存在。** 只有 `PromptAssembler` 一个模板源；SOP 兜底文案、拒答文案是内容但不在同一接口点；第三个消费者没有出现。`LlmClient` 的 mock 路径还复用 `【参考上下文】`/`[参考N]` 格式标记，模板与协议格式已纠缠；规则 6/7 的注入条件与 `hasStrongIdentifier` 强耦合，接口形状未明 | **再等**。提前模板化会把逐字护栏、拒答口径与注入条件拆散，存在弱化防线的风险 | `VerbatimGuardTest`（10 条）、`PromptAssemblerTest`（4 条）、红队 jailbreak 5 条、`qa_gen_quality_probes` | 不适用 |

**为什么是现在**：① 的三个真实消费者已经在仓内存在（不是为想象中的第三个领域提前抽象），
本轮外置的是**内容**不是**接口**，因此不消耗 ADR-0029 决策 4 的接口额度；②③ 的第三个消费者
尚不存在，提前提取正是 ADR-0015 否决的"为不存在的用例提接口"。

## 变更面（预计）

```
plans/command-policy-data-accept.md
README.md
docs/CONTEXT.md
docs/ops/l1-action-contract.md
docs/repo-map.md
src/main/java/com/opspilot/action/CommandPolicyData.java          （新增：加载器/记录）
src/main/java/com/opspilot/action/ControlledCommandPolicy.java
src/main/resources/policy/command-policy.json                     （新增：插件数据）
src/test/java/com/opspilot/action/CommandPolicyDataTest.java      （新增）
src/test/java/com/opspilot/action/ControlledCommandPolicyTest.java
```

`README.md` 与 `docs/repo-map.md` 仅因 Java `@Test` 声明数变化被 `offline/doc_numbers.py`
强制同步；若实现未新增用例（不满足 AC-4），视为契约未兑现。

## Allowed paths

- `plans/command-policy-data-accept.md`（本契约；冻结后不得再改判据）
- `src/main/java/com/opspilot/action/CommandPolicyData.java`（新增，类名与路径固定）
- `src/main/java/com/opspilot/action/ControlledCommandPolicy.java`（只改内容来源与加载接线）
- `src/main/resources/policy/command-policy.json`（新增，唯一数据文件）
- `src/test/java/com/opspilot/action/CommandPolicyDataTest.java`（新增）
- `src/test/java/com/opspilot/action/ControlledCommandPolicyTest.java`（只增等价性用例）
- `docs/ops/l1-action-contract.md`（白名单单一事实源改指数据文件）
- `docs/CONTEXT.md`、`docs/repo-map.md`、`README.md`（词条与数字门闩同步）
- 验证临时产物**只许**落在 `offline/staging/`（`.gitignore` 覆盖）或系统临时目录，**不入库**

## Acceptance criteria

**AC-1 变更面封闭。** `git diff --name-only origin/main...HEAD` 恰为上述 10 个文件
（9 个实现/文档 + 本契约），多一个或少一个都 FAIL。

**AC-2 数据是白名单内容的唯一事实源。** `ControlledCommandPolicy.java` 中不得再出现任何
领域命令字面量：
- `grep -nE '"[^"]*(kubectl|systemctl|nginx|redis|mysql|order-api|inventory-api|--namespace)' src/main/java/com/opspilot/action/ControlledCommandPolicy.java` 必须**零命中**；
- `targetOf` 的前缀识别必须从加载后的前缀集合派生，不得保留 `"kubectl"` / `"systemctl restart"`
  的硬编码分支；
- `-n` 旗标同样来自数据（`grep -n '"\-n"' ...` 零命中）。
注释可以解释机制，但**代码字符串**里不得有上述领域词。

**AC-3 数据 schema 严格且 fail-closed。** `src/main/resources/policy/command-policy.json`
固定为：

```json
{
  "schema_version": 1,
  "families": {
    "kubectl-rollout": {
      "prefixes": ["kubectl rollout restart", "kubectl rollout undo", "kubectl rollout status"],
      "flags": ["-n", "--namespace"]
    },
    "systemctl-unit": {
      "prefixes": ["systemctl restart"],
      "units": ["nginx", "redis", "mysql", "order-api", "inventory-api"]
    }
  }
}
```

`CommandPolicyData` 加载器必须满足（逐条有测试）：
(a) 未知 `schema_version`、未知顶层/家族键、未知家族 id、重复前缀/旗标/宾语 → **启动期抛异常**，
不静默忽略、不回落默认值；
(b) 前缀必须是**固定字面 token 序列**：全小写、单空格分隔、无首尾空白、不含
`; | & $ ` ( ) < > " ' \ * ? [ ] { } ~ ! #` 等元字符；不得含通配/正则；
(c) 任一行前缀不得是另一行的真前缀（如 `kubectl rollout` 与 `kubectl rollout restart`），
以保证匹配与文件顺序无关；
(d) 宾语匹配 `^[a-z0-9][a-z0-9-]*$`；旗标匹配 `^--?[a-z][a-z0-9-]*$`，不接受 `--flag=value` 形态；
(e) 空前缀集、空宾语集（家族存在时）、空旗标集（家族存在时）→ 启动失败；文件缺失/不可读
→ 启动失败；
(f) 只从 classpath 读取固定路径；**不得**新增环境变量/HTTP 上传/按租户覆盖/热加载面。

**AC-4 判定行为逐字等价（不能靠"测试变绿"证明）。**
`ControlledCommandPolicyTest` 既有 5 条测试的断言**不得删除或弱化**，另新增至少 3 条：
(a) 加载后的前缀列表、kubectl 旗标集合、systemctl 宾语集合与契约 AC-3 的冻结集合**逐项相等**；
(b) 下表 14 个输入的 verdict 与当前实现（main `c0b971a`）逐项相等：

| 输入 | `chunkDeclaresL1` | 期望 verdict |
| --- | --- | --- |
| `kubectl get pods -n prod` | false | `L0_READ` |
| `cat /var/log/syslog \| grep error` | false | `L0_READ` |
| `kubectl rollout restart deployment/order-api -n prod` | false | `REJECT` |
| `kubectl rollout restart deployment/order-api -n prod` | true | `L1_WRITE_LOW` |
| `kubectl rollout undo deployment/order-api -n prod` | true | `L1_WRITE_LOW` |
| `kubectl rollout status deployment/order-api -n prod` | true | `L1_WRITE_LOW` |
| `systemctl restart nginx` | true | `L1_WRITE_LOW` |
| `systemctl restart hacked-unit` | true | `REJECT` |
| `kubectl rollout restart deployment/x -n prod; rm -rf /tmp` | true | `REJECT` |
| `kubectl rollout restart deployment/x && cat /etc/passwd` | true | `REJECT` |
| `kubectl rollout restart $(echo deployment/x)` | true | `REJECT` |
| `kubectl rollout restart deployment/x > /tmp/pwn` | true | `REJECT` |
| `kubectl rollout restart deployment/x --kubeconfig=/tmp/evil` | true | `REJECT` |
| `systemctl restart nginx extra` | true | `REJECT` |

(c) `targetOf` 对 `kubectl rollout restart deployment/order-api -n prod` → `deployment/order-api`；
`systemctl restart nginx` → `nginx`；非法/缺宾语 → `null`（拒）。

**AC-5 五道回归锁原样保持。** 以下文件**不得出现在 diff 中**，且测试全过：
`src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java`、`ActionOpsService.java`、
`src/test/java/com/opspilot/action/ReadOnlyCommandPolicyTest.java`、
`src/test/java/com/opspilot/action/ActionOpsServiceTest.java`、`offline/corpus/**`、
`src/main/java/com/opspilot/llm/**`、`src/main/java/com/opspilot/resilience/**`。
- **L0 只读契约**：`ReadOnlyCommandPolicyTest` 18 条全过；
- **灰度 fail-closed**：`ActionOpsServiceTest` 13 条全过，`opspilot.l1.allowed-targets` 空值仍全拒；
- **逐字性**：`ActionOpsService.containsVerbatim` 的 `not_verbatim` 语义不变；
- **审计链**：`action_proposed` 审计行仍含 op_id、逐字命令、ref、proposer；`scripts/l1_worm_gate.py` 口径不变；
- **红队**：`bash scripts/redteam.sh` 32/32 通过、0 泄漏、0 SKIP。

**AC-6 全量测试与文档门闩。** `mvn -o test` 全绿（Java 声明数 = 440 + 新增数，`doc_numbers`
现算比对）；`python -m pytest offline/tests -q` 全绿；`python offline/doc_numbers.py --check`
退 0；`python scripts/check_public_copy.py` OK；`bash scripts/check_panel_contract.sh` OK。

**AC-7 不新增接口/不扩权。** `grep -rn "ServiceLoader\|plugin" src/main/java` 零命中；
无 `src/main/java/**/plugin/**` 新包；无新命令家族（加载结果与 AC-3 冻结集合相等即证）；
`application.yml` 不在 diff 中。

**AC-8 证据绑定。** 证据包写明 HEAD SHA（= 契约落地后的分支 head）、验证时
`git status --short`（须为空）与独立 worktree 路径；Verifier 亲自重跑全部命令。

## Verification commands（Verifier 亲自重跑，禁止引用实现方日志）

> 必须用**独立干净 worktree**（共享工作区会被并发会话与未跟踪文件污染）。

~~~bash
git fetch origin
git worktree add /tmp/opspilot-verify-policy-data origin/<实现分支>
cd /tmp/opspilot-verify-policy-data

# AC-8 证据绑定
git rev-parse HEAD
git log -1 --format=%H -- plans/command-policy-data-accept.md   # 须等于上面的 HEAD
git status --short                                               # 须为空

# AC-1 变更面
git diff --name-only origin/main...HEAD | sort
git diff --name-only origin/main...HEAD | wc -l                  # 须为 10

# AC-2 数据单一事实源
grep -nE '"[^"]*(kubectl|systemctl|nginx|redis|mysql|order-api|inventory-api|--namespace)' \
  src/main/java/com/opspilot/action/ControlledCommandPolicy.java || echo "零命中（预期）"
grep -n '"\-n"' src/main/java/com/opspilot/action/ControlledCommandPolicy.java || echo "零命中（预期）"

# AC-3 数据文件与加载器
python -c "import json;d=json.load(open('src/main/resources/policy/command-policy.json',encoding='utf-8'));assert d['schema_version']==1;assert set(d['families'])=={'kubectl-rollout','systemctl-unit'};print('schema ok')"

# AC-4 / AC-5 / AC-6（Java）
source scripts/java_home.sh
/e/java/apache-maven-3.9.14/bin/mvn -o test 2>&1 | tail -30   # 全绿；核对 Tests run 数

# AC-6（Python 与文档门闩）
python -m pytest offline/tests -q
python offline/doc_numbers.py --check
python scripts/check_public_copy.py
bash scripts/check_panel_contract.sh

# AC-5 回归锁：红队端到端（32 条，0 泄漏 / 0 SKIP）
bash scripts/redteam.sh

# AC-5 未被触碰的文件
git diff --name-only origin/main...HEAD | grep -E 'ReadOnlyCommandPolicy|ActionOpsService|offline/corpus|src/main/java/com/opspilot/(llm|resilience)/' \
  && echo "FAIL：禁用路径被改" || echo "禁用路径零改动（预期）"

# AC-7 无新接口/无扩权
grep -rn "ServiceLoader\|plugin" src/main/java || echo "零命中（预期）"
git diff --name-only origin/main...HEAD | grep 'application.yml' && echo "FAIL：配置被改" || echo "配置零改动（预期）"
~~~

## Forbidden

- 禁止新增命令家族、放宽前缀/旗标/宾语闭集，或把数据文件做成可被环境变量/HTTP/租户覆盖的运行时面；
- 禁止改 / 删 / 弱化既有断言；禁止跳测试不记录；禁止关 lint / typecheck 消警；
- 禁止触碰 `ReadOnlyCommandPolicy.java`、`ActionOpsService.java`、`offline/corpus/**`、
  `src/main/java/com/opspilot/llm/**`、`src/main/java/com/opspilot/resilience/**`；
- 禁止在本次改动里提取插件 SPI / `ServiceLoader` / 按领域的策略接口——那是 rule of three 的下一步；
- 禁止实现方自宣通过；Verifier 结论必须来自亲自重跑的命令输出；
- 禁止在共享工作区验证；禁止提交凭据、审计原文或 `offline/staging/` 产物。

## 门闩时序（与 ADR-0029 R1 一致）

- 本契约**不**立 CI 词法门闩：`action/` 仍保留命令家族特有的尾部校验，现在立闸会对合法机制
  假红，或逼出"假装无领域词"的空抽象。
- 本契约用**数据面平等测试**（AC-3 schema 闭集 + AC-4 逐项等价）承担该包内的分层证明。
- `action/` 的词法门闩与**家族策略接口提取**同一契约交付，触发条件=第一个非 SRE 命令家族
  （第三个真实用例）出现；干净底座包的词法门闩可独立另立。

## 处置路径（契约冻结后）

1. 人审本契约 → 书面冻结（判决：派实现 / 退回修订 / 不派）；
2. 派实现：**另一会话、独立 worktree**，只改 Allowed paths；
3. 全部判据由**全新会话** `verifier` 在干净 worktree 亲跑；
4. 人审四问（证据充分性 / 判据纪律 / 变更面封闭 / 遗留边界）→ 书面签收 → squash 合并；
5. 另起 `chore(accept)` 提交追加签收记录，不改判据。
任一条 FAIL → 退回实现方，契约不变，修复后从同一契约重验。

## 人审签收与冻结（2026-11-16）

- **签收人**：Lead（shing26）；**载体**：PR #84。
- **决定**：本契约**冻结**。Allowed paths / Acceptance criteria / Verification commands /
  Forbidden 自此不得改动；如需改，走「契约修订记录」并由 Lead 重新批准。
- **未派实现**：实现须另起会话、在独立 worktree 完成；本次签收不构成实现授权。
- **人审四问**（签收即表示无异议）：
  1. **证据充分性**：起草依据的代码事实可复核（`grep -rli plugin src/main/java` 零命中；
     L1 白名单为 `ControlledCommandPolicy` 字面量；`doc_numbers --check` 退 0、
     `check_public_copy` OK）。
  2. **判据纪律**：未改代码 / 测试 / 断言，未以自评替代证据；契约中的验证命令留待实现后由
     独立 Verifier 亲跑。
  3. **变更面封闭**：本 PR 仅 5 个文档文件，未触代码、语料、配置与 `feat/slo-coverage-up1`。
  4. **遗留边界**：实现未开始；`action/` 词法门闩延后至家族策略接口提取；CI 状态不计入
     本次签收。

## 实现验收签收（2026-11-16）

- **签收人**：Lead（shing26）；**载体**：PR #85；**合并提交**：
  `7760c408dd4e37c8c411886611d74d7f044f9095`（squash）。
- **实现 HEAD**：`a5cc37633e5a6941f6bb4e89e02157f1b06c1da1`（实现提交 `a42bb28`；
  merge commit 仅同步 `origin/main`）。
- **独立 Verifier**：另一模型、独立 detached worktree；收尾 `git status --short` 为空；
  Verification commands 全部亲跑退 0：`mvn -o test` 453 passed / 0 failed / 0 error / 0 skipped；
  `python -m pytest offline/tests -q` 621 passed；`bash scripts/redteam.sh` 32/32、SKIP 0、泄漏 0；
  `doc_numbers --check` / `check_public_copy` / `check_panel_contract` 均退 0。裁决 **ACCEPT**。
- **人审四问**（签收即表示无异议）：
  1. **证据充分性**：Verifier 证据包绑定实现 HEAD 与干净 worktree，命令输出可复核；surefire
     汇总另行抽检为 `tests=453 failures=0 errors=0 skipped=0`。
  2. **判据纪律**：既有 `ControlledCommandPolicyTest` 5 条断言未删未弱，新增 3 条；未改 / 删 /
     弱化断言，未关闭检查消警；Verifier 未静默改写 AC-1，而是把字面偏差显式登记；实现方未自宣通过。
  3. **变更面封闭**：实现 diff 恰为 Allowed paths 除契约自身外的 9 项；禁用路径零改动
     （`ReadOnlyCommandPolicy`、`ActionOpsService`、语料、`llm/`、`resilience/`、`application.yml`）。
  4. **遗留边界**：AC-1 字面 10 / 实际 9 按 Lead 裁决登记（见 R2）；AC-8 契约提交 `bd1c195` ≠
     实现 HEAD（squash/切点，契约 blob 一致）；`targetOf` 对非 L1 命令收紧为 `null`（正常流程
     不可达，Lead 确认接受）；红队仅在本地 memory+mock 形态验证；CI 11 job 全绿为辅助证据。
- **决定**：验收签收，squash 合并；签收后由 `chore(accept)` 提交本记录，不改判据。

## 契约修订记录

### R2（2026-11-16，实现验收签收时登记）

- **AC-1 口径**：契约字面「恰为 10 个文件」包含契约自身；契约已随 PR #84 合入 `origin/main`
  （`29de812`），实现 diff 为 9 个文件。Lead 裁决：按 9 个实现文件判定满足；AC-1 原文不改。
- **AC-8 绑定**：契约提交 `bd1c195` 与实现 HEAD `a5cc376` 不同，系 squash 与分支切点造成；
  契约文件 blob 在 `bd1c195` 与 `29de812` 中一致（`798d800`）。验证绑定实现 HEAD。
- **性质**：只登记既有裁决与结构事实，不改判据、不放宽任何安全语义。

### R1（2026-11-16，人审签收后冻结）

- **决定**：Lead 签收，契约由草案转为冻结；判据未改。
- **实现授权**：**未授予**；派实现须另起会话与独立 worktree，或由 Lead 在后续指示中明确授权。
- **ADR 联动**：ADR-0029 状态行改为 accepted（Lead 已签收），见
  [docs/adr/0029-reuse-boundary-trust-base-vs-domain-plugins.md](../docs/adr/0029-reuse-boundary-trust-base-vs-domain-plugins.md)
  的「签收记录（2026-11-16）」。

### R0（2026-11-16，草案）

- 初始草案：ADR-0029 设计评审通过（状态 accepted、待 Lead 签收）后选定切面 ①（仅 L1 内容外置），冻结预计变更面、
  判据与验证命令；**未实现**，等待人审决定是否派实现。
- 起草时事实：`grep -rli plugin src/main/java` 零命中；L1 白名单仍是
  `ControlledCommandPolicy` 的 Java 字符串常量；`src/main/resources` 无领域内容目录。
