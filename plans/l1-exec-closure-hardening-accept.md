# 验收契约：L1 执行闭环硬化（N-7 绑定回归锁 / N-9 nonce 清理 / N-10 状态白名单 / N-11 兜底删除 / 文档矛盾）

> 按 `docs/验收链路规范.md` 走完整链路。判定：**必须走**——本契约触碰内部 API 的终态语义
> （回调 status）、降级答案的选择逻辑（N-11）、配置绑定（N-7）、数据库表的生命周期（N-9）
> 与 CI 门禁，均属"业务逻辑 / 内部 API / 数据删除 / 生产依赖变更"档。
>
> 生成与裁决分离：本文件只冻结契约，**不含实现**。实现由独立 worktree 的 Implementer 完成，
> 裁决由全新会话的 `verifier` subagent 完成。合并前须人审签收。

## Task ID

`l1-exec-closure-hardening-accept`

## 证据绑定（契约冻结时）

- 基线 HEAD SHA：`f00187c77c1cd05df002ab67e6c4009e083a0021`（main）
- 基线 `git status --short`：仅 3 项未跟踪（`.dsh/`、`求职投递准备计划.md`、`简历项目经历草案.md`），
  与本任务无关；实现分支须保持工作树无其它改动。
- 基线 Java 测试：**429 passed, 0 failed, 0 errors, 0 skipped**（`mvn -o test`）
- 基线红队：`scripts/redteam.sh` **32/32 通过、0 SKIP、0 泄漏**（2026-11-11 本机实测）

### 事实前提（契约据此写成，Implementer 不得凭猜测改动）

1. **N-7 已定案，不是 fail-open**。两轮对照：
   - 阳性对照：未改脚本 + `OPSPILOT_L1_ALLOWEDTARGETS="deployment/inventory-api"` → 32/32 全绿，
     其中 `l1-self-confirm` 要求 propose 返回 200；
   - 阴性对照：同一脚本把白名单改成命令不使用的目标 → 25 pass / **7 LEAK**，
     `l1-self-confirm` 的 propose 返回 400「目标 deployment/inventory-api 不在灰度白名单」。
   结论：白名单**真校验且 fail-closed**（`ActionOpsService.java:53` 明写"空 = 全拒"），
   文档名 `OPSPILOT_L1_ALLOWEDTARGETS` 在真实 Boot 应用里**确实绑定**。
   绑定机制已单独实测（见 AC-1 的表）：Boot 的 `SystemEnvironmentPropertyMapper` 对
   `opspilot.l1.allowed-targets` 同时接受 `OPSPILOT_L1_ALLOWED_TARGETS` 与剥连字符的
   `OPSPILOT_L1_ALLOWEDTARGETS`，**但仅当属性源名为 `systemEnvironment` 且
   `ConfigurationPropertySources.attach` 已调用时**。
   缺陷是**绑定依赖未写明的隐式映射 + 无回归锁**，不是安全洞。
   **补充事实（契约冻结时逐行核对，推翻初稿）**：代码里的 `@Value` 键**已经是规范属性名**，
   不是原始环境变量名——
   `ActionOpsService.java:59` = `${opspilot.l1.allowed-targets:}`，
   `ActionDispatchService.java:63` = `${opspilot.l1.dispatch-webhook:}`、
   `:64` = `${opspilot.l1.enabled:false}`；错误文案（`:83`/`:110`/`:139`）也已写规范名。
   故 **AC-1 不修改任何 `@Value` 键**（初稿"改四处"作废）。
   唯一仍是裸环境变量名的是 `ActionDispatchService.java:65`
   = `${OPSPILOT_L1_WEBHOOK_ALLOW_PRIVATE:false}`——这是**故意**的：规范名
   `opspilot.l1.webhook-allow-private` 经 `.`→`_`/`-`→`_` 映射为 `OPSPILOT_L1_WEBHOOK_ALLOW_PRIVATE`，
   与文档名一致，两种写法都通；保留裸名不是缺陷，见 AC-1。
2. **`scripts/redteam.sh` 不在 CI**。`2f50b0f` 引用的「redteam 32/32」报告实际生成于父提交
   `6a220d2`，其 `ActionOpsService` 尚无白名单（`allowedTargets` 零命中），`0f75c44` 只改了报告头。
   即：那次引用的通过从未覆盖白名单。
3. `@EnableScheduling` 已在 `OpsPilotApplication.java:15` 打开；现有 `@Scheduled` 范例见
   `src/main/java/com/opspilot/cache/L2SemanticCacheService.java:236`。
4. `KvStore.mapValues` 契约明写**顺序不承诺**（`KvStore.java:76-77`），故 N-11 的"任意两篇"不可救药。
5. `ChatOrchestrator.java:311-319` 已存在 `sop == null` 分支：`metrics.sopMiss()` + 固定兜底文案。
   **N-11 的修复因此是一段删除，不需要新增分支。**

## Allowed paths（本任务唯一允许触碰面）

- `src/main/java/com/opspilot/action/ActionDispatchService.java`（**仅**AC-2）
- `src/main/java/com/opspilot/action/ExecutorCallbackController.java`（**仅**AC-3）
- `src/main/java/com/opspilot/resilience/SopFallbackService.java`（**仅**AC-4）
- `src/test/java/com/opspilot/action/`（可改/新增）
- `src/test/java/com/opspilot/resilience/SopFallbackServiceTest.java`（新增）
- `.github/workflows/ci.yml`
- `scripts/redteam.sh`（**仅**AC-6 所述平台兼容性修复；用例判定逻辑一律不许动）
- `docs/adr/0028-l1-controlled-action-contract.md`（**仅**第 73 行措辞修订 + 一行修订注记）
- `docs/ops/l1-action-contract.md`（§8.5 绑定说明、第 216 行与第 219 行措辞）
- `README.md`（**仅**第 342 行 `Java 429 单测` → `438`，见下方修订注记 R1）
- `docs/repo-map.md`（**仅**第 28 行 `429 用例` → `438`，见下方修订注记 R1）
- `plans/l1-exec-closure-hardening-accept.md`（本契约，Verifier 可追加证据段；Lead 可追加修订注记）

**不在上述清单的文件一律不许改。** 新增文件仅限两处：`src/test/java/com/opspilot/action/` 下的
测试类（AC-1/2/3 的回归锁）与 `src/test/java/com/opspilot/resilience/SopFallbackServiceTest.java`
（AC-4）。不得新增任何 main 代码文件、脚本或文档。

### 修订注记 R1（2026-11-11，契约作者追加；实现开始后、Verifier 介入前）

**起因**：本契约初稿存在自身矛盾。AC-7 要求 `doc_numbers --check` 全绿，而该门闩的
`java_test_declarations` 项以 `grep_count` 从 `src/test/**/*.java` 的 `^\s*@Test\b` **机械派生**
期望值，`cited_in` 指向 `README.md:342` 与 `docs/repo-map.md:28`。本任务按 AC-1…AC-4 新增用例后，
派生值 429 → **438**，两处引用当场转红：

```
java_test_declarations: `README.md:342` 期望值 438，文档写的是 429
java_test_declarations: `docs/repo-map.md:28` 期望值 438，文档写的是 429
```

**这是契约起草缺陷，不是实现缺陷**：能令门闩转绿的只有那两个数字，而初稿把它们列在 Allowed paths
之外，与 Forbidden #3 形成死锁。同源后果是 `offline` 的 6 条 pytest 一并失败
（`test_real_repo_is_green` 等经 `_mirror()` 复制真实仓库，在变异前就先撞上陈旧数字），
修好两处数字即全部转绿。

**处置**：把 `README.md:342`、`docs/repo-map.md:28` 纳入 Allowed paths，**仅允许**把 `429` 改成
`438`，其余一字不动。这**不是**放宽门闩——门闩的派生逻辑、`cited_in` 清单、容差一概不动，
改的是被门闩点名的文档。仓库内已有同形先例：
`docs/ops/production-readiness-2026-09-12.md:218` 记录过 131 → 139 的同类事件，结论明写
「门闩没有被改宽，改的是文档」。

**为什么不选另外两条路**：
- 把 `doc_numbers` 从 AC-7 摘掉 / 加豁免 → 直接触犯 Forbidden #1 与全局规范「关 lint/typecheck 消警」；
- 少写用例以维持 429 → 触犯 AC-7 的 ≥435 下限，且是用削减证据换取门禁变绿。

**连带核对**：`offline/doc_numbers.json` 的 `java_test_declarations.unit` 文案为
「`@Test` 声明数（src/test；无参数化展开，故与 surefire 执行数一致）」，**不含数字**，无需同步。
`README.md` 的 :72/:103/:128/:396 与 `docs/repo-map.md:92` 的 429 均为 HTTP 429 语义，与本门闩无关，
**不在改动范围**。

## Acceptance criteria（逐条可判，判据即证据）

### AC-1 绑定回归锁（N-7）——**不改绑定，只加锁**

> **冻结时的实测结论（推翻了初稿的两个前提，Implementer 必须照此执行）**
> 用项目真实 classpath（Spring Boot 4.1.2）跑探针，复刻 `@Value` 的解析路径
> （`StandardEnvironment` + 名为 `systemEnvironment` 的 source + `ConfigurationPropertySources.attach`）：
>
> | 只设的环境变量 | `${`opspilot.l1.allowed-targets`}` 解析结果 |
> |---|---|
> | `OPSPILOT_L1_ALLOWEDTARGETS`（无下划线，红队与文档写法） | `deployment/inventory-api` ✅ |
> | `OPSPILOT_L1_ALLOWED_TARGETS` | `deployment/inventory-api` ✅ |
>
> 即：**两种拼写都能绑定**。`OPSPILOT_L1_DISPATCHWEBHOOK` / `_DISPATCH_WEBHOOK` 同理。
> 这与红队阳性对照一致，也解释了为什么生产跑得通。
> **反例（必须记住）**：若省掉 `ConfigurationPropertySources.attach(environment)`，两种拼写
> 都返回 `null`——裸 `SystemEnvironmentPropertySource` 只做点/连字符→下划线，
> **不剥连字符**。任何验证用例若漏了这一步，都会得到一个"证明绑定坏了"的假结论。

- **不修改任何 `@Value` 键。** 代码里 `ActionOpsService.java:59`、`ActionDispatchService.java:63`、
  `:64` 已经是规范属性名（已由编译产物常量池核实），`:65` 是故意保留的裸环境变量名且两种写法都通。
  初稿"改四处 `@Value`"**作废**。
- **不新增 `application.yml` 的 `opspilot.l1` 块。** 初稿要求"用 `${OPSPILOT_L1_...:默认值}` 显式映射"
  是**错的**：现状已经绑定成功，再加一层 yml 映射只会新增一个可能与既有映射打架的绑定层
  （yml 显式值会覆盖 env 隐式映射，反而改变语义）。默认值也无需在 yml 重复声明——
  `@Value` 的 `:false` / `:` 默认值已生效。
- **本 AC 的唯一交付物是回归锁**：新增测试类（`src/test/java/com/opspilot/action/` 下）。
  锁的形状必须把**文档拼写**与**代码里的真实 `@Value` 键**钉在一起，而不是各写各的：
  1. 反射读出 `ActionOpsService` 构造器参数上的 `@Value` 注解，取其占位符里的键
     （形如 `opspilot.l1.…`）。**不得**在测试里硬抄该键字面值——硬抄就锁不住"有人改了 `@Value`"。
  2. 用一个只含 `OPSPILOT_L1_ALLOWEDTARGETS`（文档与红队的**无下划线**写法）的环境，
     断言步骤 1 读出的键能解析出该值。
  这样"改了 `@Value` 键"或"框架不再接受该拼写"都会让用例转红，才是 N-7 的真正回归锁。
- **用例用普通 JUnit（不起 Spring 上下文）**，构造真实解析路径，两条硬要求缺一不可：
  1. 属性源的名字须为 `systemEnvironment`（`StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME`），
     否则 Boot 不启用环境变量专用映射器；
  2. 须调用 `ConfigurationPropertySources.attach(environment)`——**漏了必假红**。
  实现须在用例注释里写明这两点，并附上面那张表的结论，防止后人"简化"掉。
  （刻意不用 `@SpringBootTest`：`ActionOpsWiringTest` 那种裸 `ApplicationContextRunner`
  不加载 `application.yml`，且本锁与上下文装配无关，起容器只是变慢并引入无关耦合。）
- **极性（必须为正）**：用例断言的是"设了 `OPSPILOT_L1_ALLOWEDTARGETS` → 解析得到该值"。
  禁止写成"设了反而解析不到"的反向断言——那会把缺陷钉成契约。
  - Verifier 的极性核验：把用例里的环境变量名改成 `OPSPILOT_L1_ALLOWED_TARGETS_BOGUS`（一个
    不可能绑定的名字）→ 该用例必须转红 → 改回后转绿。**不得**用改 `@Value`、改 yml 或删
    `attach` 的方式来验极性（那验的是别的东西，且会留下未清理的改动）。
  - Verifier 须在证据包记录转红输出、恢复输出与 `git status --short`（恢复后须与核验前一致）。
- **同时锁一条负向对照**：断言 `OPSPILOT_L1_ALLOWED_TARGETS_BOGUS` 不绑定
  `opspilot.l1.allowed-targets`，证明上面的正极性不是"任何键都返回真"的空断言。
- `docs/ops/l1-action-contract.md` §8.5 增一句：`OPSPILOT_L1_ALLOWEDTARGETS` 这类无下划线写法
  由 Spring Boot 的 relaxed binding 接受（点/连字符→下划线、连字符可选剥离），
  已验证，勿改成仅下划线形态；两种写法等价。

### AC-2 nonce 表有界（N-9）

- `ActionDispatchService` 新增 `@Scheduled` 清理方法，删除 `seen_at < now - 2*CALLBACK_WINDOW_S`
  的行；保留期常量必须由 `CALLBACK_WINDOW_S` 派生，不得另写字面量 600。
- 清理逻辑须可在测试中直接调用（包级可见），不得只靠等待调度。
- 清理方法**不得**依赖 `Clock` 注入之外的时间源；若用 `Instant.now()`，须与既有
  `clock` 字段一致（`ActionDispatchService.java:56` 已有测试时间机器），
  使测试无需睡眠即可构造"过期"行。
- **用例（双向）**：插入一条过期 nonce + 一条窗口内 nonce → 调用清理 → 断言过期行删除、
  窗口内行仍在；同一 nonce 仍返回 `nonce_reused`（清理不破坏防重放）。
- 清理不得删除未过期行：断言条件必须是"超出保留期"，而非"非本次插入"。

### AC-3 回调 status 白名单（N-10）

- 合法值集合恰为 `{"executed", "failed"}`。其它任何值（含 `success` / `OK` / `Executed` 大小写变体）
  一律 **400 `INVALID_STATUS`**，且：
  - 不修改 `action_ops.state`；
  - 不写 `action_result` 审计；
  - **不消耗 nonce**（校验位置须在 `callbackFreshnessReason` 之前）。
- 校验位置：**签名校验之后、`callbackFreshnessReason` 之前**。两条约束同时成立：
  - 不得早于签名校验——否则未认证调用方可拿 400/401 的差异当状态合法性探针，并破坏
    "未认证一律 401"的不变式；
  - 不得晚于 nonce 消耗——未知状态若先消耗 nonce，调用方改正后同 nonce 重试会被误判
    `nonce_reused`，把可纠正的输入错误变成不可恢复的终态风险。
  理由写入代码注释。
- **用例（双向）**：`status="success"` → 400 `INVALID_STATUS` 且 DB 仍为 `dispatched`、
  该 nonce 随后可被合法回调使用（证明未消耗）；`status="executed"` → 200 且终态 `executed`。
- 既有 `callbackFreshnessReasonIsLayered`、`callbackExemptFromL1Switch` 断言不得改动或弱化。

### AC-4 删除 SOP 任意兜底（N-11）

- 删除 `SopFallbackService.java:38-41` 的 `mapValues(...).limit(2)` 兜底块。`lookup` 在
  "无错误码命中且无服务名命中"时返回 `null`。
- 不得改动 `mapIsEmpty` 早退、错误码索引、服务名索引三段既有逻辑。
- `ChatOrchestrator` **零改动**（其 null 分支已存在）；不得为 N-11 新增降级文案。
- **用例（三向）**：①错误码命中 → 返回该 SOP；②无命中但 `steps` 非空 → 返回 `null`
  （旧行为会返回任意两篇）；③`steps` 为空 → 返回 `null`（既有语义不回归）。

### AC-5 文档矛盾消解

- `docs/adr/0028-l1-controlled-action-contract.md:73` 的「灰度 label 白名单」改为配置白名单表述，
  并附一行修订注记（日期 + 原措辞 + 与裁决 4·A 不一致）。**ADR 其余行不得改动。**
- `docs/ops/l1-action-contract.md:216` 与 `:219`（⛳ 灰度额外收敛整段）同口径修正为配置白名单，
  并说明为何否决 K8s label（有 RBAC 即可打 label，白名单形同虚设；裁决 4·A）。
- **`docs/ops/roadmap.md:99` 不改**：那是 append-only 的历史盘点台账，记录的是当时的评审结论；
  改写它会篡改历史。已知矛盾由 ADR-0028 修订注记承担说明责任。
- 修订后 `grep -rn "label 白名单" docs/adr docs/ops/l1-action-contract.md` 零命中；
  全仓 `docs/` 仅允许 `roadmap.md:99` 一处历史命中，Verifier 须逐条列出命中位置并确认
  该处未在本 PR diff 内。
- **措辞边界**：`docs/ops/l1-action-contract.md:185` 与 `.workbuddy/audit/backend.md` 不在
  Allowed paths，一律不动。前者已写"选 A：不用 K8s label"，与裁决 4·A 同口径，无需改；
  后者是审计底稿，属历史记录。

### AC-6 红队入 CI（堵住 AC-1 的回归缺口）

- `.github/workflows/ci.yml` 新增 `redteam` job：`ubuntu-latest`、JDK 21、`mvn -B -DskipTests package`、
  `bash scripts/redteam.sh`。`DEMO_PASSWORD` 由 job 内**运行时随机生成**（如
  `echo "DEMO_PASSWORD=$(openssl rand -hex 16)" >> "$GITHUB_ENV"`），不写死字面值、
  不进仓库（该口令只喂脚本自建的临时 memory/mock 栈）。
- job 失败即 CI 红；不得加 `continue-on-error`，不得用 `|| true` 吞掉退出码。
- 本判据的实证是**本 PR 自身的 CI 运行**（`redteam` check 为 success），非本机输出。
- **若该 job 在 CI 首次运行失败**：Implementer 须修复 `scripts/redteam.sh` 的平台假设
  （Linux 路径/`python3`/端口）使其在 ubuntu-latest 通过，**不得**通过放宽用例、加 SKIP
  豁免或从 CI 摘除 job 来变绿。`scripts/redteam.sh` 因此列入 Allowed paths（仅限平台兼容性修复，
  不得改动任何用例的判定逻辑）。
- **失败的处置边界（防止"红队变绿"式作弊）**：若失败原因是**真实缺陷**（用例正确指出代码问题），
  则本 PR 验收失败，不得在 AC-6 内消化；Implementer 须回退 `redteam` job 并报告。
  只有"脚本在 Linux 上跑不起来但用例语义不变"才允许在 Allowed paths 内修脚本。

### AC-7 回归不破 + 无弱化

- `mvn -o test` 全量通过，用例数 **≥ 435**（429 基线 + 本契约新增 ≥6：AC-1 正/负向 2、AC-2 ≥1、
  AC-3 2、AC-4 3），0 failed / 0 errors / 0 skipped。
- `offline` pytest 全量通过；`doc_numbers --check`、`provenance --check`、
  `check_public_copy --check`、`check_panel_contract.sh` 全绿。
  （`doc_numbers` 的 `java_test_declarations` 项须先把 `README.md:342` 与 `docs/repo-map.md:28`
  的 429 同步为 438——见修订注记 R1；**门闩本身不得改**。）
- `scripts/redteam.sh` 本机 32/32。
- **diff 核对**：`git diff origin/main...HEAD -- src/test/` 中既有断言只允许"新增用例"或
  "极性修正并逐一说明"，不得出现删除/放宽。Verifier 逐条核对并在证据包列出。

## Verification commands（Verifier 亲自重跑，工作目录 `D:/OpsPilot — AIOps`）

~~~bash
git rev-parse HEAD && git status --short          # SHA 与工作树须记入证据包
git diff origin/main...HEAD --stat                # 触碰面必须落在 Allowed paths 内
git diff origin/main...HEAD -- src/test/          # AC-7：既有断言无删除/放宽
source scripts/java_home.sh && mvn -o test        # AC-1/2/3/4/7：≥435，0 fail/0 skip
git diff origin/main...HEAD -- src/main/java/com/opspilot/action/ActionOpsService.java   # AC-1：须为空
git diff origin/main...HEAD -- src/main/resources/                                       # AC-1：须为空
cd offline && python -m pytest tests -q           # 回归
python doc_numbers.py --check && python provenance.py --check
cd .. && python scripts/check_public_copy.py --check
bash scripts/check_panel_contract.sh              # 须输出"十层一致"
# AC-1/6 本机：32/32、0 SKIP、0 泄漏。口令每次随机生成，本文件不留字面值：
DEMO_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9')" bash scripts/redteam.sh
grep -rn "label 白名单" docs/                     # 期望：仅 docs/ops/roadmap.md:99 一处历史命中
git diff origin/main...HEAD -- docs/ops/roadmap.md | head   # 须为空（历史台账不改写）
grep -n "redteam" .github/workflows/ci.yml        # AC-6：job 存在且无 continue-on-error
# R1：两处文档数字只许改数字，diff 必须恰为一行、且只含 429→438
git diff origin/main...HEAD -- README.md docs/repo-map.md
~~~

AC-6 的 CI 侧判据由 PR 的 `redteam` check 提供；Verifier 须记录 check 名与结论，不得以本机
`redteam.sh` 通过替代 CI 实证。

## Forbidden

1. 改/删/弱化任何既有断言或门闩以换通过（AC-7 的 diff 核对项）；
2. 跳过任一 Verification command，或以"应该没问题"替代实跑；
3. 触碰 Allowed paths 之外的文件（含新增文件）；
   （R1 后 `README.md`/`docs/repo-map.md` 已入清单，但**仅限**把 429 改成 438 这两个数字）
4. 为 N-11 新增降级文案或改动 `ChatOrchestrator`（其 null 分支已存在）；
5. 把 AC-3 的状态校验放在 nonce 消耗之后；
6. 在 CI 里用 `continue-on-error` / `|| true` / 删除 job 消红；
7. 改写 ADR-0028 第 73 行以外的内容，或改写 `docs/ops/roadmap.md` 任何一行；
8. 改动 `scripts/redteam.sh` 中任何用例的判定逻辑（仅允许平台兼容性修复）；
9. Implementer 自宣通过（裁决只能由独立 Verifier 出具）；
10. 提交任何 token / 凭据 / 派发密钥到仓库或证据文件。

## 人审四问（分级留痕，合并签收用）

1. Verification commands 是否全部亲跑且退出码 0？（Verifier 回答，附输出摘要）
2. AC-1…AC-7 逐条判在哪份证据？特别是 AC-1 的正极性回归锁与 AC-3 的"nonce 未被消耗"。
3. 有无 Forbidden 事件？（任一发生即验收失败）
4. 遗留边界（AC-6 只在 ubuntu-latest 成立、本机为 Git Bash；`scripts/redteam.sh` 的 Linux
   行为以 PR CI 为准）是否接受为已知项？

## 人审签收

（待 Verifier 证据包出具后由 Lead 填写；未签收不得合并。）
