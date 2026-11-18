# 验收契约：L0 只读命令策略内容外置（ADR-0029 第二个切面）

> **状态：冻结（2026-11-17 人审签收；未实现；是否派实现另行决定）。** 本契约只写"做什么、怎么验"，不包含任何实现。
> 判定：**必须走完整验收链路**——本改动触碰 L0 只读命令策略的允许集合（`TOOLS`/`VERBS`），
> 属"业务逻辑 / 认证授权"档。生成与裁决分离：契约由设计会话撰写；实现须由**另一会话在独立
> worktree** 完成；裁决由**全新会话**的 `verifier` subagent（Read/Grep/Glob/Bash）亲自复跑，
> 实现方贴的日志与自评不构成证据；合并前须人审四问签收。
>
> **本切片性质（先写在最前）：本次是内容外置，不是接口提取；不新增命令族；不改 L1；不引入
> 插件机制。** 只把 `ReadOnlyCommandPolicy` 中 `TOOLS`/`VERBS`/`SUBVERBS` 三张**成员关系表**
> 移到 classpath 数据文件；四层判定机制、注入/链式门、逐工具的旗标/尾部校验、SQL 判据、
> 命令替换/重定向扫描**全部留在 Java**。不消耗 rule of three 的接口额度，不引入 SPI /
> ServiceLoader / 运行时覆盖。

## Task ID

`readonly-policy-data-accept`

## 证据绑定（契约起草时）

- **基底**：main tip `04acce6`（2026-11-16，含 PR #86/#83 签收）；`4227324..04acce6`
  对 `src/main/java/com/opspilot/action` 与 `src/test/java/com/opspilot/action` **零改动**
  （`git diff --stat 4227324..04acce6 -- src/main/java/com/opspilot/action src/test/java/com/opspilot/action`
  为空），故 4.2 节行为基线仍以 `4227324` 的实现在语义上成立。
- **起草分支**：`docs/readonly-policy-contract`（独立 worktree
  `C:/Users/Shing/AppData/Local/Temp/opspilot-task2-readonly-policy`）。
- **起草时 `git status --short`**：干净（契约提交前为空）。
- **验证目标 SHA**：本契约冻结并合入 main 后，实现分支 head
  （`git log -1 --format=%H -- plans/readonly-policy-data-accept.md` 为契约提交；
  Verifier 须绑定的是**实现分支 head**，不是契约提交）。
- **验证环境**：独立干净 worktree；不得在共享工作区验证。

## 1. 方案选择：新增数据文件，不扩展现有 L1 文件

| 候选 | 形态 | 结论 |
| --- | --- | --- |
| A. 扩展现有 `policy/command-policy.json` | 把 `tools`/`verbs`/`subverbs` 加进同一文件；`CommandPolicyData` 升 `schema_version: 2`，同时解析 L1 `families` 与 L0 三张表；一个加载器、一个 classpath 路径 | **否决**（见下） |
| B. 新增 `policy/readonly-command-policy.json` | 新文件 + 新加载器 `ReadOnlyCommandPolicyData`；`command-policy.json`、`CommandPolicyData`、`CommandPolicyDataTest` 零改动 | **推荐** |

**推荐 B，理由按五个维度：**

1. **文件职责**：现有 `command-policy.json` 的职责是"**L1 写白名单内容**"（前缀/旗标/宾语闭集，
   `families` 结构），已由 `command-policy-data-accept` 冻结（AC-3 把文件内容逐字写死）。
   把 L0 读白名单塞进同一文件，会让一个文件同时承担"写通道放行"与"读通道放行"两套相反的
   安全语义，职责不再单一。B 让两个文件各自回答一个问题：
   `command-policy.json` = "哪些写动作可进入 L1 确认链"；
   `readonly-command-policy.json` = "哪些命令形态可被认作只读"。
2. **加载顺序**：B 的两个加载器互不依赖——`ControlledCommandPolicy` 静态持有 L1 数据，
   `ReadOnlyCommandPolicy` 静态持有 L0 数据；`classify` 先调 L0 再走 L1，但两个文件各自独立
   校验，**不存在"A 文件必须先于 B 文件加载"的顺序契约**。A 把两者绑进同一个解析器，反而
   引入"改 L0 schema 必须同时改 L1 解析路径"的耦合。
3. **失败域**：两者都在启动期 fail-closed（任一非法即应用起不来），因此"应用可用性"层面的
   失败域本来就相同；真正的差别是**错误定位与契约独立性**。A 的一个 L0 拼写错误会让
   L1 数据文件同时被判非法，并迫使 `CommandPolicyDataTest` 的 10 条既有断言随 schema 版本
   改动；B 的错误信息与测试边界各自独立，且不触碰已冻结的 L1 契约。
4. **可读性**：L0 内容约 56 个工具、10 个动词家族、87 个动词，以及 `redis-cli` 的 9 个
   复合动词 / 27 个子动词；L1 只有 2 个家族。B 的文件可保持"一张工具表 + 一张动词表 + 一张子动词表"的扁平
   结构；A 会让同一文件里出现两种完全不同的 schema 形状（`families` 的旗标型/单宾语型 vs
   `verbs` 的工具→动词映射），审阅者需要在两个心智模型间切换。
5. **迁移成本**：B 只需新增 1 个数据文件 + 1 个加载器 + 1 个测试类，并在
   `ReadOnlyCommandPolicy` 内把三张表的来源换成 `ReadOnlyCommandPolicyData.load()`；
   `command-policy.json`、`CommandPolicyData`、`CommandPolicyDataTest`、L1 判定器全部不动。
   A 需要升 schema 版本、改既有严格解析器与 10 条 schema 测试、重写已冻结的 AC-3 内容，
   迁移成本与契约冲突都更高。

**为什么现在可以做**：ADR-0029 R2 已明确"L0 `TOOLS`/`VERBS` 暂留，等第一份数据文件把加载器
形状证明后再另立契约"；首个切面（L1 内容外置）已通过独立验证并合入 `7760c40`，加载器形状
（classpath 固定路径、严格 schema、启动期 fail-closed、无运行时覆盖面）已被证明。本切片
沿用同一形状，属于**内容外置的第二个实例**，不是接口提取。

## 2. 边界：什么是 L0 内容，什么仍是 Java 机制

| 类别 | 本切片处置 | 说明 |
| --- | --- | --- |
| `TOOLS`（56 个纯读工具名） | **外置到数据文件** | 第一层工具白名单的成员关系 |
| `VERBS`（10 个多态工具 → 动词闭集，87 个动词 token） | **外置到数据文件** | 第二层动词白名单；大小写**原样保留** |
| `SUBVERBS`（`redis-cli` 的 9 个复合动词 → 子动词闭集，27 个 token） | **外置到数据文件** | 第二层的子动词约束 |
| `SHELLS`（`sh`/`bash`/`ssh` 等） | **留在 Java** | 递归校验的结构性入口，不是可配置内容；数据列出即启动失败 |
| `MAX_DEPTH` | 留在 Java | 递归深度上限 |
| `REDIS_VALUE_FLAGS` / `REDIS_READONLY_FLAGS` | 留在 Java | 与 `verbIndex`/旗标模式扫描耦合 |
| `SSH_VALUE_FLAGS` / `SSH_REJECT_FLAGS` | 留在 Java | 与 `shellPayload` 的取值跳转耦合 |
| `DMESG_FLAGS` / `KEYTOOL_FLAGS` / `JMAP_FLAGS` / `FIND_WRITE_PREDICATES` | 留在 Java | 逐工具旗标/谓词级写面，绑定具体解析分支 |
| `SQL_READ_VERBS` / `SQL_WRITE_VERBS` | 留在 Java | SQL 裸语句判据，不是命令工具表 |
| `SUBSTITUTION_MARKERS` / `CURL_MUTATION_FLAGS` | 留在 Java | 注入/写面标记，安全机制 |
| `EXEC_VALUE_FLAGS` / `EXEC_FLAGS` / `EXEC_SHORT_FLAGS` | 留在 Java | `kubectl/docker exec` 负载定位语法 |
| `find`/`sed` 的旗标分支 | **留在 Java，且仍不可达** | 两者不在 `TOOLS`/`VERBS` 中；本切片不得借数据把它们"救活" |
| `openssl x509 -out` 检查 | 留在 Java | `toolReadOnly` 的逐工具尾部校验 |
| `curl` 的 `-o/-O/--output` 检查 | 留在 Java | `curlReadOnly` 的逐工具尾部校验 |
| `sort -o` / `date -s` / `hostname` 带参 / `dmesg` 旗标 / `journalctl --vacuum*` | 留在 Java | 纯读工具的旗标级写面 |

**判定顺序不变**：`segments` → `redirectsReadOnly` → `noCommandSubstitution` → `argv` →
SQL 首关键字 → 工具/外壳/动词门 → `toolReadOnly`（外壳递归 / exec 负载 / 逐工具旗标 / 动词表）。
数据只替换"工具门"与"动词表"的成员关系，不改变任何分支的先后顺序。

## 3. 数据 schema

### 3.1 文件

固定路径：`src/main/resources/policy/readonly-command-policy.json`（classpath 根相对路径）。
加载器：`com.opspilot.action.ReadOnlyCommandPolicyData`，唯一读取入口，固定常量
`RESOURCE_PATH = "policy/readonly-command-policy.json"`。

### 3.2 严格 schema（v1）

根节点**恰好**为：

```json
{
  "schema_version": 1,
  "tools": ["…"],
  "verbs": {"…": ["…"]},
  "subverbs": {"…": {"…": ["…"]}}
}
```

拒绝规则（任一命中即启动期 `IllegalStateException`，不静默忽略、不回落默认值）：

1. **未知 `schema_version`**：不是整数 `1`（含 `1.0`、字符串 `"1"`、缺失）→ 失败。
2. **未知键**：根节点多/少键；`verbs` 里出现 `VERB_TOOLS` 以外的工具键；
   `subverbs` 里出现 `verbs` 以外的工具键；子动词对象里出现该工具动词集以外的键；任何层级
   出现 schema 未定义的键 → 失败。
3. **未知工具**：`tools` 的每一项、`verbs` 的每个键、`subverbs` 的每个键，必须在
   `KNOWN_TOOLS` 闭集（63 个：56 个 `TOOLS` ∪ 7 个 `VERBS` 独有工具）内；否则失败。
   `sh`/`bash`/`zsh`/`dash`/`ash`/`powershell`/`pwsh`/`ssh`/`find`/`sed` **不在**
   `KNOWN_TOOLS` 内——数据列出即失败（它们是机制入口，不能借内容扩权）。
   `KNOWN_TOOLS` 与 `VERB_TOOLS` 必须是 `ReadOnlyCommandPolicyData` 内的
   `private static final Set<String>` 字面量（63 / 10 项），**不得从 `tools()` / `verbs()`
   派生**。`VERB_TOOLS` 恰为 10 个多态工具；`KNOWN_TOOLS` = 56 个纯读工具 ∪ 7 个
   `VERBS` 独有工具（`kubectl`、`docker`、`git`、`systemctl`、`service`、`jcmd`、`redis-cli`）。
   若把它们写成数据派生，"未知工具"判据会退化成同义反复，AC-2 直接 FAIL。
4. **动词家族闭集**：`verbs` 的键必须**恰为** 10 个多态工具
   `kubectl/docker/git/systemctl/service/timedatectl/chronyc/openssl/jcmd/redis-cli`；
   多一个或少一个 → 失败。这保证数据不能在"纯读工具"与"多态工具"之间搬动工具，
   从而避免把 `openssl` 之类降级成无动词约束的纯读工具。
5. **重复**：数组内重复项、`tools` 内重复、`verbs` 内同一工具的重复动词、`subverbs` 内
   重复子动词、**重复 JSON 对象键** → 失败。重复 JSON 键必须用严格重复检测（Jackson 3 的
   strict duplicate detection 或等价的手写扫描），不能依赖"后值覆盖前值"的默认行为。
6. **通配/正则/元字符/空白**：
   - `tools`：`^[a-z0-9][a-z0-9._-]*$`；
   - `verbs`：`^[A-Za-z0-9][A-Za-z0-9._-]*$`（允许大写是因为现役 `jcmd` 动词
     `VM.version`/`Thread.print`/`GC.class_histogram` 等必须**逐字保留**；加载器不得
     大小写归一）；
   - `subverbs`：`^[a-z0-9][a-z0-9._-]*$`；
   - 不允许 `* ? [ ] { } ( ) | ; & $ ` < > " ' \`、空格、制表符、换行、`--flag=value`
     形态；不允许空字符串。
7. **空集**：`tools` 为空、`verbs` 为空、任一工具的动词集为空、`subverbs` 为空、任一子动词
   集为空 → 失败。
8. **类型错误**：根不是对象、`tools` 不是字符串数组、`verbs` 不是对象、动词集不是非空字符串
   数组、`subverbs` 不是对象、子动词集不是非空字符串数组、数组项不是字符串 → 失败。
9. **缺失/不可读/非法 JSON**：classpath 缺文件、读取抛 `IOException`、JSON 解析失败 →
   失败。不得内置一份默认表回落。
10. **读取面封闭**：只从 classpath 固定路径读取；**不得**新增环境变量、HTTP 上传、按租户
    覆盖、热加载、系统属性覆盖面。`ReadOnlyCommandPolicyData` 不读任何外部配置。
11. **启动期自检**：加载器标注 `@Component`，静态 `INSTANCE = load(...)`；Spring 启动即
    实例化，非法数据在启动期失败，而不是等到第一次提案/第一次抽取。判定方走静态
    `load()`，不依赖注入。构造函数与 `CommandPolicyData` 同形：public 无参构造，类初始化
    时完成 `INSTANCE = load(...)`，非法数据先抛出。
12. **schema 版本**：本切片固定 `schema_version: 1`，与 L1 的 `command-policy.json` 版本号
    各自独立。未来若要把尾部/宾语约束搬进数据，必须升到 `2` 并另立契约；**不得在 v1 上
    追加可选键**——未知键即启动失败，v1 的形状是冻结的。

### 3.3 数据读取后的访问器

`ReadOnlyCommandPolicyData` 至少暴露（包可见即可）：

- `Set<String> tools()`
- `Map<String, Set<String>> verbs()`
- `Map<String, Map<String, Set<String>>> subverbs()`

`ReadOnlyCommandPolicy` 的四处成员关系判断改为：

- `TOOLS.contains(tool)` → `DATA.tools().contains(tool)`；
- `VERBS.containsKey(tool)` / `VERBS.get(tool)` → `DATA.verbs()`；
- `SUBVERBS.get(tool)` → `DATA.subverbs()`。

其余分支、常量、判定顺序不动。原 `TOOLS` 常量上方"有意不收的解释器/变更器/流量捕获/
落盘下载/执行器/网络配置四件套"这段安全理由**不得丢失**：应移到
`ReadOnlyCommandPolicyData` 的类级 Javadoc 或数据文件旁的 reader 文档中（JSON 不能写注释）；
它是审阅者理解"为什么白名单是这些而不是更多"的依据。

## 4. 行为等价

### 4.1 既有 18 条回归锁

`src/test/java/com/opspilot/action/ReadOnlyCommandPolicyTest.java` 现有 **18 条 `@Test`**
（`grep -c '@Test'` 得 18）及其**全部断言**不得删除、修改或弱化。它们是主行为锁：

- 语料真实只读命令全部判过；
- 写工具/写动词/curl 写形态/SQL 写语句/落盘旗标/重定向/命令替换/外壳递归/未知形态/
  顺序执行逐段判；
- 2026-10-11 评审批的 6 组绕过修复回归锁（env、旗标级写面、进程替换、ssh 取值旗标、
  占位符掩码、git 元数据写）。

本契约只允许**追加**新测试方法，不允许改动既有方法体。机械判据见 AC-5。

### 4.2 追加的等价性输入（至少 20 个）

新增测试方法 `dataMechanismEquivalenceTable`，逐项断言如下（基线=main
`4227324` 的现行实现）：

| # | 输入 | 期望 | 覆盖层 |
| --- | --- | --- | --- |
| 1 | `ps aux` | `true` | 第 1 层：纯读工具 |
| 2 | `journalctl -u opspilot-gateway --since "1 hour ago"` | `true` | 第 1 层 + 逐工具旗标 |
| 3 | `timedatectl status` | `true` | 第 1/2 层：工具 + 动词 |
| 4 | `kubectl get pods -n prod` | `true` | 第 2 层：动词 |
| 5 | `redis-cli -h redis-session-0.prod.internal INFO clients \| grep connected` | `true` | 取值旗标 + 多段 |
| 6 | `jstack <pid> \| grep -A 20 "ConsumeMessageThread"` | `true` | 占位符 + 多段 |
| 7 | `sh -c 'cat /etc/os-release'` | `true` | 第 3 层：外壳负载递归 |
| 8 | `ssh -i ~/.ssh/id_rsa gateway-1 'cat /var/log/app/error.log'` | `true` | 第 3 层：ssh 取值旗标 |
| 9 | `curl -s -o /dev/null -w "%{http_code}" https://gateway.example/health` | `true` | curl 落盘豁免 |
| 10 | `sort /etc/passwd` | `true` | 纯读工具 + 无写旗标 |
| 11 | `rm -rf /tmp/cache` | `false` | 未知工具 |
| 12 | `kubectl delete pod payment-7d9f4` | `false` | 动词不在闭集 |
| 13 | `redis-cli FLUSHALL` | `false` | 动词不在闭集 |
| 14 | `git push origin main` | `false` | 动词不在闭集 |
| 15 | `jcmd VM.version` | `false` | **大小写折叠的既有行为**（见开放问题 3） |
| 16 | `find /data -name '*.tmp' -delete` | `false` | `find` 不在允许集合（分支不可达，行为保持） |
| 17 | `sed -i 's/a/b/' f` | `false` | `sed` 不在允许集合（分支不可达，行为保持） |
| 18 | `ls $(rm -rf /data)` | `false` | 第 4 层：命令替换 |
| 19 | `cat <(rm -rf /data)` | `false` | 第 4 层：进程替换 |
| 20 | `kubectl get pods; rm -rf /data` | `false` | 链式：逐段判 |
| 21 | `sh -c 'sh -c "bash -c 'sh -c ls'"'` | `false` | 第 3 层：递归深度上限 |
| 22 | `openssl x509 -in a.pem -out forged.pem` | `false` | 逐工具尾部校验（留在 Java） |
| 23 | `dmesg -C` | `false` | 旗标级写面（留在 Java） |
| 24 | `curl -X POST localhost:8080/api/v1/search/reindex` | `false` | curl 方法改写（留在 Java） |

> 表中 `false` 的 15/16/17 尤其重要：它们证明"数据外置没有顺手修 bug，也没有把不可达分支
> 变成可达"。`jcmd` 的 `VM.version` 在现行实现中因输入被 `toLowerCase` 而永远匹配不上
> 大写动词——本切片**逐字保留**这个保守偏差，修复它属于独立的行为变更（开放问题 3）。

### 4.3 既有测试已覆盖、本表不必重复的等价项

`ReadOnlyCommandPolicyTest` 的 18 条已覆盖 `sed -i`/`find -delete`/`jmap -dump`/
`keytool -genkey`/`sort -o`/`date -s`/`journalctl --vacuum`/`hostname` 带参/
`ssh -o`/`ssh -L`/`curl -o file`/`curl -F`/SQL 写关键字/`env`/重定向/占位符等；
本契约不要求重复，只要求它们原样通过。

## 5. 回归锁与证据

| 锁 | 要求 |
| --- | --- |
| L0 只读契约 | `ReadOnlyCommandPolicyTest` **18 条既有**全过，且断言零删改；新增至少 2 条 |
| L0 数据加载器 | `ReadOnlyCommandPolicyDataTest` 新增，至少 8 条；覆盖未知 schema/键/工具、重复、通配/正则、空集、类型错误、缺失/不可读 |
| 灰度 fail-closed | `ActionOpsServiceTest` **13 条**全过；`opspilot.l1.allowed-targets` 空值仍全拒 |
| L1 三通道 | `ControlledCommandPolicyTest` **8 条**全过；L1 数据文件与判定器零改动 |
| L1 数据 schema | `CommandPolicyDataTest` **10 条**全过；`command-policy.json` 零改动 |
| 语料逐字性/抽取 | `ReadOnlyActionExtractorTest` **10 条**全过；语料扫描 `acts >= 38`、`rejected >= 5`；`offline/corpus/**` 零改动 |
| 活体探针 | `offline/action_contract_probe.py` 与 `offline/tests/test_action_contract_probe.py` 零改动；Python 单测全过；有活体栈时探针 PASS（推荐证据，不作硬 AC） |
| 红队 | `bash scripts/redteam.sh` **32/32**、0 泄漏、0 SKIP |
| 审计链 | `bash scripts/smoke.sh` 的 `verify_audit_chain.py` 通过；`bash scripts/fault_drill.sh` 的 strict 启动拒绝与审计篡改检测通过（无带 hash 审计行时 B 段会显式 WARN 跳过，SKIP 须登记、不得当作已行使）；`scripts/verify_audit_chain.py`、`scripts/l1_worm_gate.py` 零改动 |
| 告警链 | `ops/alerting/**` 零改动；L0 判拒计数 `aiops.guard.action_commands_rejected` 语义不变（仍只进 Prometheus，不进 `/state` 面板契约） |
| 证据绑定 | 证据包写明实现 HEAD SHA、`git status --short`（须为空）、独立 worktree 路径；Verifier 亲自重跑全部命令 |

## 6. Allowed paths

实现 diff **恰为**以下 8 个文件（契约自身不计入，见 AC-1）：

- `src/main/java/com/opspilot/action/ReadOnlyCommandPolicyData.java`（新增，加载器；类名/路径固定）
- `src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java`（只改三张表的来源与接线；四层机制不动）
- `src/main/resources/policy/readonly-command-policy.json`（新增，唯一 L0 数据文件）
- `src/test/java/com/opspilot/action/ReadOnlyCommandPolicyDataTest.java`（新增）
- `src/test/java/com/opspilot/action/ReadOnlyCommandPolicyTest.java`（**只追加**等价性/schema 测试；18 条既有方法零改动）
- `README.md`（`java_test_declarations` 数字与 badge 同步）
- `docs/repo-map.md`（新增数据文件/加载器条目；单测数字同步）
- `docs/CONTEXT.md`（ADR-0029 第二个切面的词条补记；保留 `_对外_`/`_Avoid_` 行）

**不改**：`src/main/resources/policy/command-policy.json`、`CommandPolicyData.java`、
`ControlledCommandPolicy.java`、`ActionOpsService.java`、`ActionOpsServiceTest.java`、
`ControlledCommandPolicyTest.java`、`CommandPolicyDataTest.java`、`application.yml`、
`offline/corpus/**`、`offline/redteam/**`、`offline/action_contract_probe.py`、
`src/main/java/com/opspilot/llm/**`、`src/main/java/com/opspilot/resilience/**`、
`ops/alerting/**`、`docs/adr/0017-…`、`docs/adr/0029-…`。

验证临时产物**只许**落在 `offline/staging/`（`.gitignore` 覆盖）或系统临时目录，**不入库**。

## 7. Acceptance criteria

**AC-1 变更面封闭。** `git diff --name-only origin/main...HEAD` 恰为第 6 节的 8 个文件
（排序后 `wc -l` = 8），多一个或少一个都 FAIL。**契约自身口径**：本契约冻结后先合入 main；
实现分支从 main 切出，故 `plans/readonly-policy-data-accept.md` **不应**出现在实现 diff。
若因分支切点把契约带进 diff，Verifier 只按 8 个实现文件判定，并在证据包登记该偏差，
不改判据。

**AC-2 数据是 L0 白名单成员关系的唯一事实源。**
- `grep -nE 'private static final (Set<String> TOOLS|Map<String, Set<String>> VERBS|Map<String, Map<String, Set<String>>> SUBVERBS)' src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java`
  **零命中**；
- `grep -n 'ReadOnlyCommandPolicyData.load()' src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java`
  **≥1 命中**；
- `grep -rn 'policy/readonly-command-policy.json' src/main/java` **只命中**
  `ReadOnlyCommandPolicyData.java`；
- 机制分支可以保留工具名字面量（`SHELLS`、`find`/`sed`/`curl`/`sort`/`hostname`/`dmesg`/
  `journalctl`/`date`/`keytool`/`jmap`/`openssl x509`/`kubectl|docker exec` 的逐工具校验），
  但**不得再出现任何允许集合（工具/动词/子动词）的 Java 字面量**；允许集合只来自数据；
- 新增测试 `loadedContentEqualsFrozenSets` 证明加载后的三张表与 AC-4 冻结集合逐项相等。
  这条测试是"数据单一事实源"的最终判据——即使有人把字面量偷偷加回机制，集合相等测试
  仍会暴露内容漂移。
- **已知宇宙不是数据自证**：`KNOWN_TOOLS` / `VERB_TOOLS` 必须是
  `ReadOnlyCommandPolicyData` 的 `private static final Set<String>` 字面量（63 / 10 项），
  **不得**由 `tools()` / `verbs()` 派生；新增测试 `knownUniverseEqualsFrozenSets` 逐项断言
  这两个集合与本节冻结内容相等。机械判据：
  `grep -nE 'private static final Set<String> (KNOWN_TOOLS|VERB_TOOLS)' src/main/java/com/opspilot/action/ReadOnlyCommandPolicyData.java`
  必须 **≥2 命中**，且源码中不得出现 `Set.copyOf(tools())` / `Set.copyOf(verbs().keySet())`
  这类派生写法。

**AC-3 数据 schema 严格且 fail-closed。** 第 3.2 节 12 条规则逐条有测试或机械判据，且：
- 未知 `schema_version` / 未知顶层键 / 未知工具 / 未知动词家族 / 未知子动词键 →
  **启动期**抛 `IllegalStateException`；
- 重复数组项与**重复 JSON 对象键** → 启动失败；
- 空 `tools` / 空 `verbs` / 任一空动词集 / 空 `subverbs` / 任一空子动词集 → 启动失败；
- 通配/正则/大写工具/空白/元字符 → 启动失败；`verbs` 中的大写 token 原样保留，不做
  大小写归一；
- 文件缺失/不可读/非法 JSON/类型错误 → 启动失败；
- 只从 classpath 固定路径读取，无环境变量/HTTP/租户覆盖/热加载面，不回落默认值。
- `ReadOnlyCommandPolicyDataTest` 必须包含以下**具名**负向测试（缺少任一即 FAIL）：
  `unknownToolFails`、`unknownVerbFamilyFails`、`duplicateJsonKeyFails`、`emptySetFails`、
  `missingResourceFails`、`unreadableResourceFails`、`invalidSchemaVersionFails`；
  重复 JSON 键不得用 `python json.load` 证明（它静默取后值），必须由加载器自身的严格解析
  测试证明。

**AC-4 冻结内容逐项相等。** `src/main/resources/policy/readonly-command-policy.json`
必须逐项等于下方冻结内容（数组顺序不作判据，集合相等即可）：

- `tools`：**56** 项；
- `verbs`：**10** 个工具家族、**87** 个动词 token；
- `subverbs`：**1** 个工具家族（`redis-cli`）、**9** 个复合动词、**27** 个子动词 token。

```json
{
  "schema_version": 1,
  "tools": [
    "ps", "top", "uptime", "free", "vmstat", "iostat", "mpstat", "pidstat", "sar",
    "ls", "cat", "file", "stat", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "jq",
    "grep", "egrep", "fgrep", "rg",
    "echo", "printf", "date", "printenv", "id", "whoami", "hostname", "uname",
    "dmesg", "lsb_release",
    "ping", "traceroute", "tracepath", "ss", "netstat", "dig", "nslookup", "host", "curl",
    "timedatectl", "chronyc", "ntpq", "ntpstat",
    "jps", "jstack", "jstat", "jinfo", "jmap",
    "keytool", "openssl",
    "journalctl"
  ],
  "verbs": {
    "kubectl": ["get", "logs", "describe", "top", "explain", "api-resources",
                "version", "cluster-info", "events", "exec"],
    "docker": ["ps", "logs", "inspect", "stats", "top", "images", "version",
               "info", "port", "exec"],
    "git": ["status", "log", "ls-files", "show", "diff", "blame", "rev-parse",
            "check-ignore", "describe", "shortlog", "grep"],
    "systemctl": ["status", "list-units", "list-unit-files", "is-active",
                  "is-enabled", "show", "cat"],
    "service": ["status"],
    "timedatectl": ["status", "show", "list-timezones"],
    "chronyc": ["tracking", "sources", "sourcestats", "clients", "serverstats"],
    "openssl": ["x509", "s_client", "version", "ciphers"],
    "jcmd": ["VM.version", "VM.uptime", "VM.info", "VM.flags", "VM.command_line",
             "VM.system_properties", "Thread.print", "GC.class_histogram", "GC.heap_info"],
    "redis-cli": ["info", "slowlog", "cluster", "get", "mget", "exists", "ttl",
                  "pttl", "type", "scan", "dbsize", "keys", "hget", "hgetall",
                  "lrange", "smembers", "zrange", "zscore", "config", "memory",
                  "client", "monitor", "object", "command", "latency", "xinfo",
                  "randomkey"]
  },
  "subverbs": {
    "redis-cli": {
      "slowlog": ["get", "len"],
      "cluster": ["info", "nodes", "slots", "shards"],
      "config": ["get"],
      "memory": ["usage", "doctor", "stats"],
      "client": ["list", "info"],
      "object": ["encoding", "refcount", "idletime", "freq"],
      "command": ["count", "docs", "info", "getkeys"],
      "latency": ["history", "latest", "doctor", "graph"],
      "xinfo": ["stream", "groups", "consumers"]
    }
  }
}
```

**AC-5 行为逐项等价（不能靠"测试变绿"证明）。**
- `ReadOnlyCommandPolicyTest` 既有 **18 条** `@Test` 与全部断言**不得删除、修改或弱化**；
  `git diff -U0 -- src/test/java/com/opspilot/action/ReadOnlyCommandPolicyTest.java | grep -E '^-[^-]'`
  **零命中**（纯追加 diff）；
- 新增至少 **2 条** `@Test`：
  (a) `loadedContentEqualsFrozenSets`：加载后的 `tools`/`verbs`/`subverbs` 与 AC-4 冻结集合
      逐项相等（工具 56、动词家族 10、动词 token 87、子动词家族 1、子动词 27）；
  (b) `dataMechanismEquivalenceTable`：第 4.2 节 **24 个输入**的 verdict 与 main `4227324`
      逐项相等；
- 18 条既有测试全过；4.2 节 24 个输入全过。

**AC-6 回归锁原样保持。** 第 5 节表格中的锁全部满足：
- `ReadOnlyCommandPolicyTest` 18 条全过；
- `ActionOpsServiceTest` 13 条全过；`containsVerbatim` 的 `not_verbatim` 语义不变；
  `action_proposed` 审计行仍含 op_id、逐字命令、ref、proposer；
- `ControlledCommandPolicyTest` 8 条全过；`opspilot.l1.allowed-targets` 空值仍全拒；
- `CommandPolicyDataTest` 10 条全过；
- `ReadOnlyActionExtractorTest` 10 条全过，语料扫描 `acts >= 38`、`rejected >= 5`；
- `offline/action_contract_probe.py` 与 `offline/tests/test_action_contract_probe.py` 零改动，
  Python 单测全过；
- `bash scripts/redteam.sh` 32/32、0 泄漏、0 SKIP；
- `bash scripts/smoke.sh` 审计哈希链校验通过；`bash scripts/fault_drill.sh` 的 strict 启动
  拒绝与审计篡改检测通过（B 段无审计行时 SKIP 须登记，不得当作已行使）；
- `ops/alerting/**`、`scripts/verify_audit_chain.py`、`scripts/l1_worm_gate.py` 零改动；
- `aiops.guard.action_commands_rejected` 计数器语义不变（判拒即递增，不静默；仅 Prometheus）。

**AC-7 全量测试与文档门闩。**
- `mvn -o test` 全绿；
- `python -m pytest offline/tests -q` 全绿；
- `python offline/doc_numbers.py --check` 退 0；
- `python scripts/check_public_copy.py` 退 0；
- `bash scripts/check_panel_contract.sh` 退 0；
- README/repo-map 的 Java 单测数等于 `grep -rE '^\s*@Test\b' src/test --include=*.java | wc -l`
  的实际值；README 的 `Java_Tests-<n>_Passed` badge 同步为同一数字（badge 不在
  `doc_numbers` 登记表内，但本契约要求手工同步）。

**AC-8 不新增接口 / 不扩权 / 不改 L1。**
- `grep -rn "ServiceLoader\|plugin" src/main/java` **零命中**；无 `src/main/java/**/plugin/**`
  新包；无 SPI/策略接口/运行时覆盖面；
- 无新命令家族/新工具/新动词：加载结果与 AC-4 冻结集合相等即证；
- 以下文件**不在 diff**：`src/main/resources/policy/command-policy.json`、
  `ControlledCommandPolicy.java`、`ActionOpsService.java`、`ActionOpsServiceTest.java`、
  `ControlledCommandPolicyTest.java`、`CommandPolicyDataTest.java`、`application.yml`、
  `offline/corpus/**`、`offline/redteam/**`、`offline/action_contract_probe.py`、
  `src/main/java/com/opspilot/llm/**`、`src/main/java/com/opspilot/resilience/**`、
  `ops/alerting/**`、`docs/adr/0017-…`、`docs/adr/0029-…`。

**AC-9 证据绑定。** 证据包写明实现 HEAD SHA（= Verifier 绑定的分支 head）、验证时
`git status --short`（须为空）与独立 worktree 路径；Verifier 亲自重跑全部 Verification
commands，禁止引用实现方日志或自评。

**AC-10 启动期 fail-closed。**
- `ReadOnlyCommandPolicyData` 标注 `@Component`，静态 `INSTANCE = load(...)`；应用启动即加载；
- 文件缺失/不可读/非法 JSON/未知 schema/未知键/未知工具/重复/空集/类型错误 → 启动期
  `IllegalStateException`，不静默忽略、不回落默认值；
- `ReadOnlyCommandPolicyDataTest` 至少包含 missing/unreadable 两条断言；
- 既有 `@SpringBootTest` 上下文（`ActionOpsServiceTest`、`ActionDispatchServiceTest`）在数据
  非法时启动失败，作为结构证据（不新增上下文测试）。

## 8. Verification commands

> Verifier 必须用**独立干净 worktree**；以下命令按顺序执行，任一非 0 即 FAIL。
> 路径按环境替换；Maven 可用 `source scripts/java_home.sh` 后直接 `mvn`；若 PATH 无 `mvn`，
> 用环境中的绝对路径（如 `/e/java/apache-maven-3.9.14/bin/mvn`）。

```bash
set -euo pipefail
git fetch origin
git worktree add /tmp/opspilot-verify-readonly-policy origin/<实现分支>
cd /tmp/opspilot-verify-readonly-policy

# AC-9 证据绑定
git rev-parse HEAD
git status --short                                   # 须为空

# AC-1 变更面
git diff --name-only origin/main...HEAD | sort
git diff --name-only origin/main...HEAD | wc -l      # 须为 8

# AC-2 数据单一事实源（成员关系表已移到数据）
grep -nE 'private static final (Set<String> TOOLS|Map<String, Set<String>> VERBS|Map<String, Map<String, Set<String>>> SUBVERBS)' \
  src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java || echo "零命中（预期）"
grep -n 'ReadOnlyCommandPolicyData.load()' src/main/java/com/opspilot/action/ReadOnlyCommandPolicy.java
grep -rn 'policy/readonly-command-policy.json' src/main/java

# AC-3 / AC-4 数据文件 schema 与冻结内容（严格重复键检测）
python - <<'PY'
import json

def no_dupes(pairs):
    keys = [k for k, _ in pairs]
    assert len(keys) == len(set(keys)), f'duplicate JSON key: {keys}'
    return dict(pairs)

d = json.load(open('src/main/resources/policy/readonly-command-policy.json', encoding='utf-8'),
              object_pairs_hook=no_dupes)
assert d['schema_version'] == 1
assert len(d['tools']) == 56
assert set(d['verbs']) == {'kubectl','docker','git','systemctl','service',
                           'timedatectl','chronyc','openssl','jcmd','redis-cli'}
assert sum(len(v) for v in d['verbs'].values()) == 87
assert set(d['subverbs']) == {'redis-cli'}
assert sum(len(v) for v in d['subverbs']['redis-cli'].values()) == 27
print('L0 schema/content ok')
PY

# AC-2 / AC-3 已知宇宙与负向测试必须存在
grep -nE 'private static final Set<String> (KNOWN_TOOLS|VERB_TOOLS)' \
  src/main/java/com/opspilot/action/ReadOnlyCommandPolicyData.java
for t in unknownToolFails unknownVerbFamilyFails duplicateJsonKeyFails emptySetFails \
         missingResourceFails unreadableResourceFails invalidSchemaVersionFails; do
  grep -q "void $t" src/test/java/com/opspilot/action/ReadOnlyCommandPolicyDataTest.java
done

# AC-5 既有 18 条零删改
git diff -U0 -- src/test/java/com/opspilot/action/ReadOnlyCommandPolicyTest.java \
  | grep -E '^-[^-]' && echo "FAIL：既有断言被删改" || echo "零删改（预期）"

# AC-5 / AC-6 / AC-7 Java 全量
source scripts/java_home.sh
mvn -o test 2>&1 | tail -40                          # 全绿；核对 Tests run
mvn -o -DskipTests package                           # 供 redteam/smoke/fault_drill 使用

# AC-6 语料逐字性 / Python 单测 / 探针单测
python -m pytest offline/tests -q

# AC-6 红队端到端（32 条，0 泄漏 / 0 SKIP）
bash scripts/redteam.sh

# AC-6 审计链与 strict 启动
bash scripts/smoke.sh
bash scripts/fault_drill.sh

# AC-7 文档门闩
python offline/doc_numbers.py --check
python scripts/check_public_copy.py
bash scripts/check_panel_contract.sh
ACTUAL=$(grep -rE '^\s*@Test\b' src/test --include=*.java | wc -l | tr -d ' ')
grep -q "Java ${ACTUAL} 单测" README.md
grep -q "单测与集成（${ACTUAL} 用例" docs/repo-map.md
grep -q "Java_Tests-${ACTUAL}_Passed" README.md

# AC-8 无新接口 / 不改 L1 / 不改禁用路径
grep -rn "ServiceLoader\|plugin" src/main/java || echo "零命中（预期）"
git diff --name-only origin/main...HEAD \
  | grep -E '(^|/)command-policy\.json|(^|/)ControlledCommandPolicy\.java|(^|/)ActionOpsService\.java|(^|/)ActionOpsServiceTest\.java|(^|/)ControlledCommandPolicyTest\.java|(^|/)CommandPolicyDataTest\.java|application\.yml|offline/corpus|offline/redteam|offline/action_contract_probe|src/main/java/com/opspilot/(llm|resilience)/|ops/alerting|docs/adr/00(17|29)' \
  && echo "FAIL：禁用路径被改" || echo "禁用路径零改动（预期）"
```

补充口径：

- `scripts/redteam.sh` 与 `scripts/smoke.sh` 需要 `DEMO_PASSWORD`（按脚本要求临时设置，
  不回显、不入库）；`redteam.sh` 还需要先 `mvn -o -DskipTests package` 产出 jar。
- `scripts/fault_drill.sh` 的 B 段（审计篡改检测）在仓库 `logs/` 没有带 hash 审计行时会
  显式 WARN 跳过。**跳过不得当作已行使**：Verifier 须在证据包登记该 SKIP；审计哈希链的
  主判据以 `scripts/smoke.sh` 的 `verify_audit_chain.py` 为准。
- 有活体栈时，额外跑 `python offline/action_contract_probe.py` 并把 PASS 行放进证据包；
  它是 ADR-0017 的活体复核，不作硬 AC（避免环境依赖造成假红）。

## 9. Forbidden

- 禁止新增/删除/重命名命令家族、工具、动词或子动词，禁止放宽 AC-4 冻结集合；
- 禁止把数据文件做成可被环境变量 / HTTP / 租户 / 系统属性 / 热加载覆盖的运行时面；
- 禁止修改、删除或弱化既有断言（含 18 条 L0 测试的任何一行）；禁止跳测试不记录；
  禁止关 lint / typecheck / 文档门闩消警；
- 禁止触碰 `command-policy.json`、`ControlledCommandPolicy.java`、`ActionOpsService.java`、
  `ActionOpsServiceTest.java`、`ControlledCommandPolicyTest.java`、`CommandPolicyDataTest.java`、
  `application.yml`、`offline/corpus/**`、`offline/redteam/**`、
  `offline/action_contract_probe.py`、`src/main/java/com/opspilot/llm/**`、
  `src/main/java/com/opspilot/resilience/**`、`ops/alerting/**`、ADR-0017/0029；
- 禁止在本次改动里提取插件 SPI / `ServiceLoader` / 按工具的运行时策略接口——那是 rule of
  three 的下一步；
- 禁止把 `find`/`sed` 加进 `KNOWN_TOOLS` 或数据文件（它们当前不可达，本切片不得"救活"）；
- 禁止把 `jcmd` 的大写动词改成小写来"修 bug"（行为变更，须另立契约）；
- 禁止实现方自宣通过；Verifier 结论必须来自亲自重跑的命令输出；
- 禁止在共享工作区验证；禁止提交凭据、审计原文或 `offline/staging/` 产物。

## 10. 风险与开放问题

### 10.1 已识别风险与缓解

| 风险 | 缓解 |
| --- | --- |
| `curl`/`openssl`/`journalctl`/`jmap`/`keytool`/`dmesg`/`sort`/`date`/`hostname` 等工具的敏感性：读是常态语义，但个别旗标落盘/改状态 | 逐工具旗标/尾部校验**留在 Java**；18 条既有测试 + 4.2 节等价表；任何删除/放宽都要另立契约 |
| 动词表大（10 家族 / 87 token）且与工具耦合，手改易错 | 严格 schema + 启动期 fail-closed + 冻结内容测试 + 红队；文件按工具分组，保持可读 |
| `jcmd` 的 `VM.version` 等大写动词在现行实现中因输入小写化而不可达 | 本切片**逐字保留**；数据不得归一化；4.2 节 #15 锁死该行为；修复另立契约 |
| 数据文件可读性与误改风险 | 未知工具启动失败、重复/空集/通配失败、集合相等测试；任何内容变更都必须同步测试与人审 |
| 两个 classpath 数据文件的加载顺序 | 两个加载器互不依赖，各自静态初始化并 fail-closed；不建立跨文件顺序契约 |
| 启动期自检是否足够 | `@Component` + 静态单例 + 缺失/不可读测试 + 既有 `@SpringBootTest` 上下文失败即证；不新增上下文测试 |

### 10.2 需要 Lead 拍板的开放问题（最多 5 条）

1. **方案 B（新增 `readonly-command-policy.json`）是否批准？** 推荐 B：保持已冻结的 L1
   数据契约零改动、schema 与失败域独立；代价是多一个 classpath 文件与加载器。
2. **是否把范围严格限定为 `TOOLS`/`VERBS`/`SUBVERBS`，旗标级闭集继续留在 Java？** 推荐
   是：`DMESG_FLAGS`/`KEYTOOL_FLAGS`/`JMAP_FLAGS`/`REDIS_READONLY_FLAGS`/SSH 旗标/curl
   写面与逐工具解析分支耦合，搬进数据会变成接口设计，超出"内容外置"。
3. **`jcmd` 的大小写折叠既有行为是保留还是在本切片顺手修复？** 推荐**保留**（行为等价是
   本契约的硬约束）；修复会让 `jcmd VM.version` 从拒变过，属于独立的行为变更。
4. **56 个工具是否原样保留（含 `curl`/`openssl`/`journalctl`）？** 推荐原样保留：这是
   现行 L0 白名单的内容，删任何一个都会改变线上契约；如要收紧，另立安全契约并重跑全部
   回归锁。
5. **是否接受"动词家族键必须恰为 10 个多态工具"这一启动期形状约束？** 推荐接受：它阻止
   数据把多态工具降级为纯读工具，也阻止新增家族；代价是数据不能单独"停用"某个多态工具
   （停用需改代码/契约）。

## 11. 处置路径

1. 人审本契约 → 书面冻结（判决：派实现 / 退回修订 / 不派）；
2. 派实现：**另一会话、独立 worktree**，只改 Allowed paths；
3. 全部判据由**全新会话** `verifier` 在干净 worktree 亲跑；
4. 人审四问（证据充分性 / 判据纪律 / 变更面封闭 / 遗留边界）→ 书面签收 → squash 合并；
5. 另起 `chore(accept)` 提交追加签收记录，不改判据。

## 12. 修订记录

### R2（2026-11-17，冻结）

- **决定**：Lead（shing26）书面签收，契约由草案转为冻结；判据未改。
- **五项签收**：
  1. 批准方案 B（新增 `readonly-command-policy.json` + 独立加载器）；
  2. 范围限定 `TOOLS`/`VERBS`/`SUBVERBS`，旗标闭集留在 Java；
  3. `jcmd` 大写动词折叠的既有行为保留；修复另立契约（扩权需单独安全评审）；
  4. 56 个工具原样保留；
  5. 接受「动词家族键恰为 10 个多态工具」的启动期形状约束。
- **实现授权**：**未授予**；派实现须另起会话与独立 worktree，或由 Lead 在后续指示中明确授权。
- **实现前基线**：`origin/main` 须含本契约提交；实现分支从该 main 切出。

### R1（2026-11-17，草案修订）

- **基底更新**：`origin/main` 到 `04acce6`；确认 `4227324..04acce6` 对 `src/main/java/com/opspilot/action`
  与 `src/test/java/com/opspilot/action` 零改动，4.2 节行为基线不变。
- **补 P1 判据**：`KNOWN_TOOLS` / `VERB_TOOLS` 必须是 `ReadOnlyCommandPolicyData` 的字面量，
  不得由数据派生；新增 `knownUniverseEqualsFrozenSets` 与 7 条具名负向测试，防止"未知工具"
  判据退化为同义反复。
- **验证脚本**：重复 JSON 键改用 `object_pairs_hook` 严格检测（`json.load` 会静默取后值）。
- **仍未冻结、未实现**；等待 Lead 人审。

### R0（2026-11-16，草案）

- 初始草案：ADR-0029 第二个切面，L0 `ReadOnlyCommandPolicy` 的 `TOOLS`/`VERBS`/`SUBVERBS`
  **内容**外置为 `policy/readonly-command-policy.json`；机制、旗标/尾部校验、SQL 判据、
  注入/重定向扫描全部留在 Java。
- **本次是内容外置，不是接口提取；不新增命令族；不改 L1；不引入插件机制。**
- 起草时事实：`ReadOnlyCommandPolicy` 仍为 Java 字面量；`ReadOnlyCommandPolicyTest` 18 条；
  `CommandPolicyData` 已证明加载器形状；`command-policy.json` 与 L1 契约已冻结。
- **未实现，未冻结**；等待人审决定是否派实现。

## 人审签收（2026-11-18）

- **签收人**：Lead（shing26）；**载体**：PR #93；**合并**：squash → `bc98bba`
  （分支 `feat/readonly-policy-impl` 保留，head `87395fe`）。
- **验证历程**：单轮独立 verifier。实现由另一会话在独立 worktree 完成
  （自报 477 单测 / 656 pytest，不构成证据）；verifier 在干净 worktree
  `opspilot-verify-readonly-policy` 亲跑契约 §8，**AC-1…AC-10 全部 PASS**，
  验证首尾 `git status --short` 为空。
- **关键证据**（verifier 亲跑）：`mvn -o test` 477/0/0/0；`pytest offline/tests -q`
  656 passed；`redteam` 32/32、0 泄漏、0 SKIP；`smoke.sh` 全绿；
  `fault_drill.sh` A 段 strict 拒绝 PASS、B 段篡改检测真实行使（改一行 → exit=3）；
  `doc_numbers` / `check_public_copy` / `check_panel_contract` 全退 0；
  数据文件 56 工具 / 10 家族 87 动词 / redis-cli 9 复合动词 27 子动词，与冻结集合逐项
  相等；`git diff --name-only` 恰 8 个 Allowed paths；CI 11/11 全绿。
- **人审四问**（签收即表示无异议）：
  1. **证据充分性**：无异议——verifier 亲跑、证据绑定实现 HEAD `87395fe`、可重跑命令
     齐备。
  2. **判据纪律**：无异议——既有 18 条测试纯追加零删改；`KNOWN_TOOLS`/`VERB_TOOLS`
     为字面量闭集；`jcmd` 大写折叠保留、`find`/`sed` 不可达保持；无 ServiceLoader/SPI；
     实现方自评未被采信。
  3. **变更面封闭**：无异议——恰 8 个 Allowed paths；`command-policy.json`、L1 判定器、
     `application.yml`、语料、探针、ADR、CI 零 diff。
  4. **遗留边界**：**接受为已知项**——pytest 首跑 1 例环回 socket 抖动（复跑全绿，非
     回归）；`smoke.sh` 首跑环境口径（需按脚本注释设 `JAVA_BIN`/`PYTHON`）；`jcmd` 大写
     动词不可达行为按契约保留（Issue #91 独立评审）；契约验证程序缺口见下。
- **禁令复核**：未以改/删/弱化断言换通过、未跳测试不记录、未关检查消警、
  实现方未自宣通过、未绕过人审、本次合并有书面签收。

### 程序缺口回灌（R3，不改判据）

- **事实**：契约 §8 的命令顺序先 `mvn -o test` 后 `fault_drill.sh`；mvn 测试的多个
  Spring 上下文会向 worktree 的 git-ignored `logs/audit.jsonl` 写入多段链（后启动上下文
  在文件为空时以 GENESIS 初始化），导致 `fault_drill.sh` B 段不按 WARN 跳过而直接 FAIL。
  任何按命令顺序执行的 Verifier 都会踩中。
- **回灌**：后续验证 `fault_drill.sh` 前须清理测试运行产生的 `logs/` 产物（或在干净
  worktree 中先生成单链审计文件）。本次 Verifier 按此口径复跑，B 段篡改检测已真实行使。
- **边界**：本条只修验证程序，AC-1…AC-10 的判据、阈值、期望集合均未改动。
