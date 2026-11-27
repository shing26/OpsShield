# 验收契约：Boot 4.2 / Elasticsearch 9 兼容性 Spike

> 来源：2026-11-27 用户确认路线「先 `alert-history-cursor`，再单独起 Boot 4.2 / ES 9 兼容性 spike」。
> **状态：草案（待签收）。** 基线 `main` @ `77c747c`；
> 本分支只承载契约；实验在一次性分支 `codex/boot42-es9-lab`（不合并）。

## Task ID

`boot42-es9-spike`

## 现状（2026-11-27 实测）

- `pom.xml`：Spring Boot **4.1.2**；`elasticsearch-java` 显式钉 **8.14.3**；
  Jackson 3.2.3 / Tomcat 11.0.25 为 SCA 补丁；Flyway 走 Boot 4 的独立模块。
- Dependabot PR **#108**（Boot 4.1.2 → 4.2.0）：12 个 check 中 11 个通过，
  唯一失败是 `static-gate`：
  `FAIL 静态：受支持 Boot 版本（G1）（README.md 未命中 Spring_Boot-4.2.0）`。
  **没有观察到编译或测试失败**——失败点是版本门读取 README，而不是代码不兼容。
- Dependabot PR **#81**（`elasticsearch-java` 8.14.3 → 9.5.6）：`java` / `benchmark` /
  `redteam` / `smoke` 四个 job 失败。首批编译错误：
  - `config/EsConfig.java:72-75`：`org.apache.http.impl.client` / `org.apache.http.auth` 不存在；
  - `ingest/IngestionRunner.java:390`：`GetAliasResponse.result()` 不存在；
  - `retrieval/CoverageService.java:181`：`GetIndexResponse.result()` 不存在；
  - `retrieval/EsSearchService.java:67`：`RangeQuery.Builder.field(String)` 不存在。
- 上述两次 dependabot 运行分别发生在 2026-11-26 / 2026-11-12，**main 已前进**；
  结论必须在当前 `77c747c` 上重跑，不能直接引用旧日志作为最终证据。

## 判定口径

- **Spike 不是升级**：合并进 `main` 的只有契约、事实报告与 ADR-0048；
  `pom.xml` 与 `src/` 在合并切片中**零 diff**，生产依赖保持 4.1.2 / 8.14.3。
- 实验在一次性分支 `codex/boot42-es9-lab` 上进行（可改 pom/src），
  分支推送到 origin 供复现，**不合并、不开 PR**；实验 HEAD 必须记录 SHA。
- **四格矩阵**（每格独立可复现）：
  - A：基线 4.1.2 + 8.14.3；
  - B：Boot 4.2.0 + ES 8.14.3；
  - C：Boot 4.1.2 + ES 9.5.6；
  - D：Boot 4.2.0 + ES 9.5.6。
- **采纳判据（任一依赖）**：该格必须同时满足
  ① `mvn test` 全绿；② fat jar 在 memory 模式启动；③ `/actuator/health` 为 UP；
  ④ 一次 chat 请求与一次 admin 只读成功；⑤ 静态门闩无新增 FAIL。
  但 spike 只产出结论，不代替后续实现契约。
- **时间盒 1 个工作日**：到点未收敛就产出 `DEFER`，写明卡点、下一步与触发线；
  不接受「再研究一下」式结论。
- Maven 必须联网（新依赖不在本地仓库），**禁止用 `-o` 得出「依赖不可用」的假阴性**；
  Maven Central 429 只记为环境重试，不计为不兼容。
- 兼容性事实必须引用官方一手来源（Boot 4.2 release notes / migration guide、
  Elasticsearch Java Client 9 兼容矩阵与迁移说明），并附 URL 与访问日期。

## Allowed paths（合并切片）

```
plans/boot42-es9-spike-accept.md
docs/adr/0048-boot42-es9-upgrade-decision.md
docs/ops/boot42-es9-spike.md
docs/repo-map.md
README.md
offline/doc_numbers.json
```

实验分支 `codex/boot42-es9-lab` 不受此列表约束，但**不得合并**。

## Acceptance criteria

1. **复现 #108**：在当前 main 上只把 Boot 改到 4.2.0，跑全量 `mvn test`；
   记录 tests/failures/errors/skipped、失败类与方法。若全绿，给出 `static-gate`
   所需的精确改动清单（README 受支持版本行、`pom.xml`、`docs` 计数、SBOM/白名单等），
   逐项给出文件:行与拟改文本。
2. **复现 #81**：在当前 main 上只把 `elasticsearch-java` 改到 9.5.6，
   枚举**全部**编译错误（文件:行、符号、原 API → ES 9 替代 API），
   覆盖 `java` / `benchmark` / `redteam` / `smoke` 四个 job 的首个失败点；
   不得只写「有很多错误」。
3. **四格矩阵**：A/B/C/D 每格输出
   `编译 / 全量测试 / 启动 / health / chat / admin 读 / 启动耗时 / 常驻内存`；
   未做到的格子写「未执行 + 原因」，不得留空。
4. **依赖树差异**：B/C/D 相对 A 的解析版本变化，至少覆盖
   `spring-core`、`spring-boot`、`jackson2`、`jackson3`、`tomcat`、`flyway`、
   `elasticsearch-java`、`httpclient`、`grpc`。
5. **官方兼容性**：Boot 4.2 的最低 JDK / Framework 版本 / 已知破坏性变更；
   ES Java Client 9 支持的**服务端版本区间**、传输层（HttpClient 4→5）变化、
   与 Jackson 2 的版本要求；明确「8.14 服务端 + 9.x 客户端」是否官方支持。
6. **运维影响**：`management.health.elasticsearch.enabled` 是否仍需要；
   ES 客户端指标/日志/配置键是否有 rename；内存与启动时间相对 A 的增量。
7. **结论 ADR-0048**：对 Boot 4.2 与 ES 9 分别给出
   `ADOPT / DEFER / BLOCKED`，附触发线（例如「ES 服务端升级到 9.x 时再迁客户端」
   或「Boot 4.2 可直接采纳，先修版本门」）与工作量估算（文件数/类数/人日）。
8. **后续切片清单**：若 ADOPT，列出可独立执行的实现契约
   （如 `boot42-upgrade`：pom + README + gate；`es9-client-migration`：4 个类 + 测试）；
   若 DEFER，列出重新评估的触发条件。
9. **Dependabot 处置建议**：#108 / #81 是保留、关闭还是改基；
   只给建议并注明「需用户授权后才可关闭」，不得自行关闭。
10. **合并切片自证**：`git diff 77c747c..HEAD -- pom.xml src/` 为空；
    `doc_numbers.py --check`、`check_public_copy.py --check` 通过；
    实验分支未合并、未开 PR。
11. **证据绑定**：报告开头写实验 HEAD SHA、四格各自 SHA、`git status --short`；
    日志摘录只保留关键 20–50 行，不提交整份 CI 日志。

## Verification commands（Verifier 亲自重跑）

合并切片（文档）：

```bash
cd "D:/OpsPilot — AIOps"
git rev-parse HEAD && git status --short
git diff 77c747c..HEAD -- pom.xml src/     # 必须为空
offline/.venv/Scripts/python.exe offline/doc_numbers.py --check
offline/.venv/Scripts/python.exe scripts/check_public_copy.py --check
```

实验复现（Verifier 在 lab worktree 亲自重跑裁决格）：

```bash
cd <lab-worktree>
git rev-parse HEAD && git status --short
$env:MAVEN_OPTS='-Xmx1g'
E:/java/apache-maven-3.9.14/bin/mvn.cmd test -DforkCount=1 -DreuseForks=true
E:/java/apache-maven-3.9.14/bin/mvn.cmd -DskipTests package
# 启动 fat jar（memory + mock），curl /actuator/health，跑一次 chat 与一次 admin 读
```

Verifier 至少要重跑**建议采纳**的那一格；其余格子可核对实验日志与 diff，
但需在报告中说明未重跑项。

## Forbidden

- 禁止把任何 `pom.xml` / `src/` 改动合入 `main`；spike 合并切片只允许文档。
- 禁止把 #108 / #81 直接合并或关闭；处置只出建议。
- 禁止用 `mvn -o` 判定新依赖不可用。
- 禁止改/删/弱化断言、跳测试、关静态门闩来「让矩阵变绿」。
- 禁止在未启动进程、未发真实请求的情况下声称「兼容」。
- 禁止只引用旧 dependabot 日志而不在当前 main 复现。
- 禁止改 V1–V13 或数据库 schema。
- 禁止 `git add -A`；实验分支禁止合并。

## 回滚

合并切片仅新增文档，回滚不影响运行时；实验分支可直接删除。

## 人审签收

**状态：待签收。** 需要确认的三项口径：

1. Spike 只产出 ADR 与事实报告，生产依赖本切片不动。
2. 时间盒 1 个工作日；不收敛即 `DEFER` 并写触发线，不无限扩。
3. 判据按四格矩阵 + 启动/请求活体，禁止只凭旧 CI 日志下结论。

实现者与 Verifier 不得为同一会话。本次签收只授权实验与文档，不含依赖升级合并。
