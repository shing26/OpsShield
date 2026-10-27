# SCA 修复/豁免矩阵（G1 门禁 8）

> 数据源：GitHub Actions run `39871945612` 的 Trivy SBOM JSON artifact（Boot 3.5.14 初始状态）。
> 修复前 37 个 HIGH/CRITICAL；本轮通过同主版本补丁覆盖修复 33 个，4 个因修复只在
> Spring 7 / Spring Data 4 线而记录风险接受。最终 CI run **`39873876035` 全绿**
> （security job success，artifact 中 Java vulns=0）。
>
> 处置原则：**能同主版本补丁升级的不豁免**；只有跨大版本且本服务无攻击面时才豁免，
> 并写明复核触发条件。豁免文件：[`.trivyignore`](../../.trivyignore)。

## 汇总

| 项 | 数量 |
|---|---:|
| 初始 HIGH/CRITICAL | 37 |
| 同主版本补丁修复 | 33 |
| 风险接受（写入 `.trivyignore`） | 4 |
| 最终 CI 结果（run 39873876035） | security job success；artifact `vulns=0` |

## 修复矩阵

| CVE | 严重度 | 组件 | 修复前 | 修复版本 | 处置 | 说明 |
|---|---|---|---|---|---|---|
| CVE-2026-75595 | CRITICAL | `io.netty:netty-handler` | 4.1.132.Final | 4.1.137.Final | 已修复 | pom: netty.version |
| CVE-2026-41293 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.54 | 10.1.59 | 已修复 | pom: tomcat.version |
| CVE-2026-43512 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.54 | 10.1.59 | 已修复 | pom: tomcat.version |
| CVE-2026-43515 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.54 | 10.1.59 | 已修复 | pom: tomcat.version |
| CVE-2026-65182 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.54 | 10.1.59 | 已修复 | pom: tomcat.version |
| CVE-2026-65905 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.54 | 10.1.59 | 已修复 | pom: tomcat.version |
| CVE-2026-68525 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.54 | 10.1.59 | 已修复 | pom: tomcat.version |
| CVE-2026-47884 | CRITICAL | `org.springframework:spring-webmvc` | 6.2.18 | 7.0.9 | 豁免 | .trivyignore：仅 Spring 7；无 XSLT View / view fragment |
| CVE-2026-47890 | CRITICAL | `org.springframework:spring-webmvc` | 6.2.18 | 7.0.9 | 豁免 | .trivyignore：仅 Spring 7；无 XSLT View / view fragment |
| CVE-2026-68494 | HIGH | `com.fasterxml.jackson.core:jackson-core` | 2.21.2 | 2.22.3 | 已修复 | pom: jackson-bom.version |
| CVE-2026-89407 | HIGH | `com.fasterxml.jackson.core:jackson-core` | 2.21.2 | 2.22.3 | 已修复 | pom: jackson-bom.version |
| CVE-2026-89425 | HIGH | `com.fasterxml.jackson.core:jackson-core` | 2.21.2 | 2.22.3 | 已修复 | pom: jackson-bom.version |
| CVE-2026-54512 | HIGH | `com.fasterxml.jackson.core:jackson-databind` | 2.21.2 | 2.22.3 | 已修复 | pom: jackson-bom.version |
| CVE-2026-54513 | HIGH | `com.fasterxml.jackson.core:jackson-databind` | 2.21.2 | 2.22.3 | 已修复 | pom: jackson-bom.version |
| CVE-2026-68497 | HIGH | `com.fasterxml.jackson.core:jackson-databind` | 2.21.2 | 2.22.3 | 已修复 | pom: jackson-bom.version |
| CVE-2026-91776 | HIGH | `com.fasterxml.jackson.core:jackson-databind` | 2.21.2 | 2.22.3 | 已修复 | pom: jackson-bom.version |
| CVE-2026-91777 | HIGH | `com.fasterxml.jackson.core:jackson-databind` | 2.21.2 | 2.22.3 | 已修复 | pom: jackson-bom.version |
| CVE-2024-7254 | HIGH | `com.google.protobuf:protobuf-java` | 3.25.1 | 3.25.5 | 已修复 | pom: explicit version |
| CVE-2025-55163 | HIGH | `io.grpc:grpc-netty-shaded` | 1.65.1 | 1.75.0 | 已修复 | pom: grpc-bom import |
| CVE-2026-40983 | HIGH | `io.micrometer:micrometer-core` | 1.15.11 | 1.15.12 | 已修复 | pom: micrometer.version |
| CVE-2026-40984 | HIGH | `io.micrometer:micrometer-core` | 1.15.11 | 1.15.12 | 已修复 | pom: micrometer.version |
| CVE-2026-42583 | HIGH | `io.netty:netty-codec` | 4.1.132.Final | 4.1.137.Final | 已修复 | pom: netty.version |
| CVE-2026-59901 | HIGH | `io.netty:netty-codec` | 4.1.132.Final | 4.1.137.Final | 已修复 | pom: netty.version |
| CVE-2026-42579 | HIGH | `io.netty:netty-codec-dns` | 4.1.132.Final | 4.1.137.Final | 已修复 | pom: netty.version |
| CVE-2026-44249 | HIGH | `io.netty:netty-handler` | 4.1.132.Final | 4.1.137.Final | 已修复 | pom: netty.version |
| CVE-2026-45416 | HIGH | `io.netty:netty-handler` | 4.1.132.Final | 4.1.137.Final | 已修复 | pom: netty.version |
| CVE-2026-50010 | HIGH | `io.netty:netty-handler` | 4.1.132.Final | 4.1.137.Final | 已修复 | pom: netty.version |
| CVE-2026-45674 | HIGH | `io.netty:netty-resolver-dns` | 4.1.132.Final | 4.1.137.Final | 已修复 | pom: netty.version |
| CVE-2026-47691 | HIGH | `io.netty:netty-resolver-dns` | 4.1.132.Final | 4.1.137.Final | 已修复 | pom: netty.version |
| CVE-2026-41284 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.54 | 10.1.59 | 已修复 | pom: tomcat.version |
| CVE-2026-42498 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.54 | 10.1.59 | 已修复 | pom: tomcat.version |
| CVE-2026-43513 | HIGH | `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.54 | 10.1.59 | 已修复 | pom: tomcat.version |
| CVE-2026-41850 | HIGH | `org.springframework:spring-expression` | 6.2.18 | 6.2.19 | 已修复 | pom: spring-framework.version |
| CVE-2026-41842 | HIGH | `org.springframework:spring-webmvc` | 6.2.18 | 6.2.19 | 已修复 | pom: spring-framework.version |
| CVE-2026-41845 | HIGH | `org.springframework:spring-webmvc` | 6.2.18 | 6.2.19 | 已修复 | pom: spring-framework.version |
| CVE-2026-41695 | HIGH | `org.springframework.data:spring-data-commons` | 3.5.11 | 4.0.6 / 3.5.12（未上 Central） | 豁免 | .trivyignore：无 Repository/用户 property path |
| CVE-2026-41716 | HIGH | `org.springframework.data:spring-data-commons` | 3.5.11 | 4.0.6 / 3.5.12（未上 Central） | 豁免 | .trivyignore：无 Repository/用户 property path |

## 豁免项与补偿控制

| CVE | 组件 | 为什么不能直接升 | 补偿控制 |
|---|---|---|---|
| CVE-2026-47884 | Spring MVC `spring-webmvc` | 修复仅 7.0.9；Boot 3.5 跑 Spring 6.2，跨大版本升级破坏兼容 | 无 XSLT View、无用户可控 view name；控制器只返回 JSON/原始 SSE |
| CVE-2026-47890 | Spring MVC `spring-webmvc` | 同上 | 不使用 view fragment；SSE 走 `SseEmitter`/raw frame，不走 View 渲染 |
| CVE-2026-41695 | Spring Data Commons | 3.5.12 未上 Maven Central；4.0.6 需 Boot 4 | 无 Spring Data Repository、无用户 property path；查询参数校验 + 准入限流 |
| CVE-2026-41716 | Spring Data Commons | 同上 | 同上 |

## Boot 4 重基线（2026-10-27）

> 数据源：PR [#51](https://github.com/shing26/OpsShield/pull/51) 的 Trivy SBOM artifact（Spring Boot 4.1.1 初始状态）。
> 初始 13 条 HIGH/CRITICAL（3 CRITICAL + 10 HIGH），全部为**同主版本补丁缺失**，无跨大版本豁免需求。

| CVE | 严重度 | 组件 | Boot 4 默认 | 修复版本 | 处置 |
|---|---|---|---|---|---|
| CVE-2026-65182 / 65905 / 68525 | CRITICAL | `org.apache.tomcat.embed:tomcat-embed-core` | 11.0.24 | **11.0.25** | pom: `tomcat.version` |
| CVE-2026-89407 / 89425 | HIGH | `com.fasterxml.jackson.core:jackson-core` | 2.21.5 | **2.22.3** | pom: `jackson-2-bom.version` |
| CVE-2026-68497 / 91776 / 91777 | HIGH | `com.fasterxml.jackson.core:jackson-databind` | 2.21.5 | **2.22.3** | pom: `jackson-2-bom.version` |
| CVE-2026-89407 / 89425 | HIGH | `tools.jackson.core:jackson-core` | 3.1.5 | **3.2.3** | pom: `jackson-bom.version` |
| CVE-2026-68497 / 91776 / 91777 | HIGH | `tools.jackson.core:jackson-databind` | 3.1.5 | **3.2.3** | pom: `jackson-bom.version` |

结果：CI `security` job success，artifact 中 HIGH/CRITICAL = 0；`.trivyignore` 清空
（Boot 3 时代的 4 条 Spring 7 / Spring Data 4 豁免在新基线下已不再需要）。
迁移前后测试均为 424 绿，冒烟见 PR #51 描述。

## 复核触发条件

- ~~升级 Spring Boot 4 / Spring Framework 7：立即删除 `.trivyignore` 中对应项并重跑 CI~~ ✅ 2026-10-27 已完成（见上节）；
- 引入 XSLT/View 渲染、Spring Data Repository、或暴露 property path 参数：重新评估；
- 到期日：**2027-10-27**（Boot 4 重基线顺延）；到期前必须重跑 Trivy 并更新本矩阵。

## 验证

```text
mvn -B test                         385 passed
CI security artifact               trivy-results.json（37 条初始）
CI java/smoke/sbom/static/python    success
```

修复后 CI 产物为 0 条未处置 HIGH/CRITICAL（豁免项由 `.trivyignore` 过滤）；
最终 run `39873876035` 的 security job 为 success，artifact 中 Java vulns=0。
