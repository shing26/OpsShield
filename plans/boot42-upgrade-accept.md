# 验收契约：Spring Boot 4.1.2 → 4.2.0

> 来源：ADR-0048（`docs/adr/0048-boot42-es9-upgrade-decision.md`）判定 Boot 4.2.0 **ADOPT**。
> **状态：草案（待签收）。** 基线 `main` @ `e7e600c`；
> 本分支只承载契约；实现分支为 `codex/boot42-upgrade-impl`（契约合并后从 main 切出）。

## Task ID

`boot42-upgrade`

## 现状与证据（2026-11-27）

- `pom.xml:10` = Boot **4.1.2**；`README.md:13` badge 与 `README.md:170` 依赖支持窗口
  仍写 4.1.2。
- `scripts/production_gate.sh:85-87` 的 G1 判据：从 pom 现取版本，再要求 README 命中
  `Spring_Boot-${version}`；版本不同步即
  `FAIL 静态：受支持 Boot 版本（G1）（README.md 未命中 Spring_Boot-4.2.0）`。
- Spike（ADR-0048 / lab `47460a1`）已在 Boot 4.2.0 + ES 8.14.3 下实测：
  642/642 全绿、fat jar 启动、health/chat/admin 全通；`PASS=69 FAIL=0` 只需补 README 两处。
- 4.2.0 的依赖变化：`spring-core 7.0.10→7.1.0`、`flyway 12.4.0→12.11.0`；
  Jackson 2/3、Tomcat、gRPC 解析版本不变。

## 判定口径

- **只升 Boot**：`elasticsearch-java` 保持 8.14.3；不顺手升 Guava / jjwt / grpc / pytest。
- Jackson 2/3、Tomcat 的显式补丁属性（`jackson-2-bom.version`、`jackson-bom.version`、
  `tomcat.version`）必须保留并确认真实生效；Boot 4.2 BOM 若给出更高版本，
  以「不降低现有修复版本」为准，不盲目删除属性。
- 历史文档不改写：`docs/qa/2026-10-27-boot4-live-verification.md` 等写的是当时的 4.1.1，
  属于历史证据；只改 README 的当前声明与必要的 SCA 矩阵。
- Flyway 12.4 → 12.11 必须验证**已有库升级**，不能只测空库：
  以 V1–V13 已迁移的 H2 文件为基线，启动后不得报 checksum/版本错误，不得产生 V14。
- SCA 只接受「已登记且有触发线」的豁免；新增 HIGH/CRITICAL 必须停下来报告，
  不得用 `.trivyignore` 静默吞掉。
- 依赖升级必须走实现者 ≠ Verifier；合并须另行书面签收。

## Allowed paths

```
plans/boot42-upgrade-accept.md
pom.xml
README.md
docs/ops/sca-findings.md
.trivyignore
offline/doc_numbers.json
```

## Acceptance criteria

1. **版本**：`pom.xml` parent = `4.2.0`；`mvn dependency:tree` 显示
   `spring-boot 4.2.0`、`spring-core 7.1.x`、`flyway-core 12.11.x`；
   Jackson 2/3 与 Tomcat 的解析版本**不低于**升级前（`jackson 2.22.3 / 3.2.3`、
   `tomcat-embed-core 11.0.25` 或更高）。
2. **门闩**：`scripts/production_gate.sh --fast` → `FAIL=0`；
   README badge/alt 与支持窗口行均为 4.2.0。
3. **回归**：`mvn -o test`（或联网）全量全绿，数字与基线 642 一致或只增不减；
   0 failures / 0 errors / 0 skipped。
4. **CI 同形**：`smoke` / `redteam` / `benchmark` / `e2e` 全绿；
   `pytest offline/tests` 703 passed；面板契约十层、public-copy、doc_numbers 通过。
5. **Flyway 升级路径**：在已含 V1–V13 的 H2 文件上启动成功；
   `flyway_schema_history` 无新增版本、`alert_intake`/`incidents`/`incident_push_outbox` 可用。
6. **活体**：fat jar memory + mock 启动，`/actuator/health` UP；
   一次 chat 返回 `event:done`；一次 admin 只读 200。
7. **SCA/SBOM**：security job 绿；Trivy Java vulns 为 0 或仅剩既有已登记豁免；
   若版本/矩阵变化，更新 `docs/ops/sca-findings.md` 的数据源 run 与结论。
8. **无业务变更**：`src/` 零 diff；无 V14；API/配置键/指标名不变。
9. **文档**：README 两处版本 + SCA 矩阵（如需）；不新增 ADR（ADR-0048 已是决策记录）。

## Verification commands（Verifier 亲自重跑）

```bash
cd "D:/OpsPilot — AIOps"
git rev-parse HEAD && git status --short
E:/java/apache-maven-3.9.14/bin/mvn.cmd -o test
E:/java/apache-maven-3.9.14/bin/mvn.cmd -o dependency:tree -Dincludes=org.springframework.boot:*,org.springframework:spring-core,org.flywaydb:*,org.apache.tomcat.embed:*,com.fasterxml.jackson.*
E:/Git/bin/bash.exe scripts/production_gate.sh --fast
E:/Git/bin/bash.exe scripts/check_panel_contract.sh
offline/.venv/Scripts/python.exe -m pytest offline/tests -q
offline/.venv/Scripts/python.exe scripts/check_public_copy.py --check
offline/.venv/Scripts/python.exe offline/doc_numbers.py --check
```

活体验收（Verifier 亲自操作）：

1. 空库启动：health UP、chat `event:done`、admin 只读 200；
2. 复用 V1–V13 已迁移的 H2 文件再启动：无 Flyway 错误、无 V14；
3. 若 security job 产物可得，核对 Trivy Java vulns 计数与 `.trivyignore`。

## Forbidden

- 禁止顺手升级 ES 客户端、Guava、jjwt、grpc 等无关依赖。
- 禁止删除或弱化 Jackson/Tomcat 补丁属性来「让版本看起来统一」。
- 禁止改历史 QA/SCA 记录里的 4.1.1 事实。
- 禁止用 `.trivyignore` 静默吞新增 HIGH/CRITICAL；必须停下来报告。
- 禁止改 `src/`、数据库 schema 或测试断言换通过。
- 禁止 `git add -A`。

## 回滚

单提交回滚 `pom.xml` + README 即回到 4.1.2；无 schema 变更，无需数据回滚。

## 人审签收

**状态：待签收。** 需确认三项口径：

1. 只升 Boot 4.2.0，不顺手升 ES 9 或其它依赖。
2. Jackson/Tomcat 补丁版本只升不降；SCA 新增高危必须停。
3. Flyway 必须验证「已有库升级」，不能只测空库。

实现者与 Verifier 不得为同一会话。本次签收只授权实现，不含合并授权。
