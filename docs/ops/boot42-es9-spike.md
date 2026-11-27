# Boot 4.2 / Elasticsearch 9 兼容性 spike（2026-11-27）

> 契约：`plans/boot42-es9-spike-accept.md`（冻结于 `77c747c`，执行基线 `2220dfa`）。
> **结论：Boot 4.2.0 `ADOPT`；`elasticsearch-java` 9.5.6 `DEFER`（等待 ES 服务端 9.x）。**
> 实验分支 `codex/boot42-es9-lab` 只用于复现，**永不合并**；合并切片只含文档。

## 证据绑定

- 实验分支：`codex/boot42-es9-lab`
  - A：`2220dfa`（基线，无额外提交）
  - B：`47460a1`（Boot 4.2.0 only + README 版本门修正）
  - C：`1ed359b`（ES 9.5.6 only + 机械迁移）
  - D：`2fce507`（Boot 4.2.0 + ES 9.5.6）
- 文档分支：`codex/boot42-es9-spike-impl`（本报告与 ADR-0048；HEAD 见提交记录）。
- 工作树：实验分支与文档分支的 `git status --short` 均为空（生成物已还原/未入库）。
- 环境：Windows 11 / PowerShell，`JAVA_HOME=E:\java\jdk21`（Temurin 21.0.12），
  Maven 3.9.14，`MAVEN_OPTS=-Xmx1g`；Maven 联网（未用 `-o`）。
- 运行时：memory + mock，`SERVER_PORT=18081`，fat jar，H2 文件库；
  账号由 `UserAdminCli` 现场播种（`smoke` / `tenant-internal` / level 3 / platform）。
- 测试负载：`mvn test -DforkCount=1 -DreuseForks=true`。
- 说明：启动耗时是「进程启动 → `/actuator/health` 首次 200」；常驻内存是
  login + chat + admin 之后的 `WorkingSet64`。单次读数，仅作相对参考。

## 四格矩阵

| 格 | 组合 | 编译 | 全量测试 | 启动 | health | chat | admin 读 | 启动耗时 | 常驻内存 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| A | Boot 4.1.2 + ES 8.14.3 | OK | 642（基线既有，未重跑） | OK | UP / readiness UP | `event:done` | `/admin/state` 有 `build` | 13.55s（冷启 11.58s） | 318.8MB |
| B | Boot 4.2.0 + ES 8.14.3 | OK | **642 / 0 / 0 / 0** | OK | UP / readiness UP | `event:done` | `/admin/state` 有 `build` | 12.66s | 304.2MB |
| C | Boot 4.1.2 + ES 9.5.6 | 初始 22 main + 16 test 错误；机械修复后 OK | **642 / 0 / 0 / 0** | OK | UP / readiness UP | `event:done` | `/admin/state` 有 `build` | 12.66s | 318.6MB |
| D | Boot 4.2.0 + ES 9.5.6 | OK | **642 / 0 / 0 / 0** | OK | UP / readiness UP | `event:done` | `/admin/state` 有 `build` | 12.60s | 323.8MB |

补充（D 格，CI 同形脚本）：

- `bash scripts/smoke.sh target/opspilot-gateway-1.0.0.jar` → `PASS smoke`
  （health/readiness/登录/SSE/指标/state 全绿）。
- `bash scripts/redteam.sh` → **35/35 通过，SKIP 0，泄漏 0**。
- `bash scripts/benchmark.sh` → **10/10 判据通过**。

内存与启动时间相对 A 的增量均在单次运行噪声内（Boot 4.2：−0.9s / −14.6MB；
ES 9：−0.9s / −0.2MB；两者：−0.95s / +5.0MB）。**不据此下容量结论。**

## #108：Boot 4.2.0 的 static-gate

在当前基线只把 Boot 改成 4.2.0 后，复现到与旧 CI 一致的结果：

```text
FAIL  静态：受支持 Boot 版本（G1）（README.md 未命中 Spring_Boot-4.2.0）
PASS=68 WARN=3 FAIL=1
```

全量 `mvn test` 为 **642/642 全绿**，没有编译或测试失败。精确修复清单：

| 文件:行 | 现状 | 拟改 | 依据 |
| --- | --- | --- | --- |
| `pom.xml:10` | `<version>4.1.2</version>` | `<version>4.2.0</version>` | Dependabot #108 本体 |
| `README.md:13` | `Spring_Boot-4.1.2` / `alt="Spring Boot 4.1.2"` | `Spring_Boot-4.2.0` / `alt="Spring Boot 4.2.0"` | `production_gate.sh` 从 pom 取版本后 grep README |
| `README.md:170` | `Spring Boot 4.1.2、Flyway...` | `Spring Boot 4.2.0、Flyway...` | 同一事实的第二处 |

修复后 `SKIP_TESTS=1 bash scripts/production_gate.sh --fast`：
`PASS=69 WARN=3 FAIL=0`。`doc_numbers.py --check` 与 `check_public_copy.py --check`
在 B/C/D 均通过；**无需改 `offline/doc_numbers.json`**（642 与 ADR 计数均未因依赖升级变化）。
SBOM 由 `scripts/sbom.sh` 现读 pom 生成，无手工版本白名单；合并前仍需重跑 SCA/Trivy。

## #81：Elasticsearch 9.5.6 的全部编译错误

### main（22 个，全部集中在 4 个类）

| 文件:行 | 失败符号 | 原 API（8.x） | ES 9 替代 |
| --- | --- | --- | --- |
| `config/EsConfig.java:7,63` | `HttpHost` | `org.apache.http.HttpHost` | `org.apache.hc.core5.http.HttpHost` |
| `config/EsConfig.java:8,31,36,37` | `RequestConfig` | `org.apache.http.client.config.RequestConfig` | `org.apache.hc.client5.http.config.RequestConfig` |
| `config/EsConfig.java:9,62,64` | `RestClient` / `RestClientBuilder` | `org.elasticsearch.client.RestClient` | `co.elastic.clients.transport.rest5_client.low_level.Rest5Client` / `Rest5ClientBuilder` |
| `config/EsConfig.java:47,48` | `HttpAsyncClientBuilder` | `org.apache.http.impl.nio.client.HttpAsyncClientBuilder` | `org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder`（池上限走 `setConnectionManagerCallback`） |
| `config/EsConfig.java:71,72` | `BasicCredentialsProvider` | `org.apache.http.impl.client.BasicCredentialsProvider` | `org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider` |
| `config/EsConfig.java:74,75` | `AuthScope` / `UsernamePasswordCredentials` | `org.apache.http.auth.*` | `org.apache.hc.client5.http.auth.*`（口令改 `char[]`） |
| `config/EsConfig.java:85` | 传输实现 | `RestClientTransport` | `Rest5ClientTransport`（构造参数 `Rest5Client`） |
| `ingest/IngestionRunner.java:416` | `GetAliasResponse.result()` | 返回 index→aliases 的 `Map` | `aliases()` |
| `retrieval/CoverageService.java:181` | `GetIndexResponse.result()` | 返回 index→state 的 `Map` | `indices()` |
| `retrieval/EsSearchService.java:67` | `RangeQuery.Builder.field(String)` | 旧的扁平 builder | `RangeQuery.Builder.number(n -> n.field(...).lte(...))`（9.x 为 tagged union） |

注：`EsConfig` 的错误按 javac 行号逐条计为 19 个（同一 import/类型重复命中），
加上 3 个调用点共 22 个。HTTP 4→5 还带来两处机械修正：
`HttpHost.create(String)` 改为 `URI` + 构造器（避免检查异常），
`setHttpClientConfigCallback` 的参数由 `Function` 变为 `Consumer`（去掉 `return c`）。

### test（main 修复后暴露 16 个）

| 文件:行 | 失败符号 | 原 API | 替代 |
| --- | --- | --- | --- |
| `config/OutboundTimeoutTest.java:5,66` | `RequestConfig` | HC4 `org.apache.http.client.config` | HC5 `org.apache.hc.client5.http.config` |
| `config/OutboundTimeoutTest.java:6,87,101,118,126` | `PoolingNHttpClientConnectionManager` | HC4 NIO 池 | `org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager` |
| `config/OutboundTimeoutTest.java:76` | `getSocketTimeout()` | HC4 `RequestConfig.getSocketTimeout()` | HC5 socket 超时在 `ConnectionConfig`，响应超时在 `RequestConfig` |
| `config/OutboundTimeoutTest.java:121` | `HttpAsyncClients.custom()` | HC4 builder | `EsConfig.connectionManager(...)`（生产同一路径） |
| `retrieval/CoverageServiceTest.java:145,146` | `range().field()` / `range().lte()` | `RangeQuery` 扁平访问器 | `range().number().field()` / `range().number().lte()` |
| `retrieval/CoverageServiceTest.java:220,227` | `GetIndexResponse.Builder.result(...)` | 8.x builder | `indices(...)` |
| `retrieval/IdentityFilterContrastTest.java:54,55,99` | `range().field()` / `range().lte()` | 8.x 扁平访问器 | `range().number().field()` / `range().number().lte()` |

### 首个失败点与修复结果

四个 CI job 的第一步都是 Maven 构建，因此 ES 9 升级后的**首个失败点相同**：
`EsConfig.java:7` 的 `org.apache.http` import 解析失败（`mvn compile` 阶段）。

| job | 首个失败点 | 修复后实测 |
| --- | --- | --- |
| `java` | `mvn -B test` → main 编译，`EsConfig.java:7` | 642/642 绿 |
| `benchmark` | `mvn -B -DskipTests package` → 同一编译错误 | `benchmark.sh` 10/10 通过（D 格） |
| `redteam` | 同上 | `redteam.sh` 35/35 通过（D 格） |
| `smoke` | 同上 | `smoke.sh` PASS（D 格） |

机械修复规模：**7 个文件**（1 pom + 4 main + 3 test），断言语义未改、未跳测试。
结论仍是 `DEFER`，原因不在“修不动”，而在服务端兼容性与真栈证据。

## 依赖树差异（`mvn dependency:tree`）

| 组件 | A（4.1.2 / 8.14.3） | B（4.2.0 / 8.14.3） | C（4.1.2 / 9.5.6） | D（4.2.0 / 9.5.6） |
| --- | --- | --- | --- | --- |
| `spring-core` | 7.0.10 | **7.1.0** | 7.0.10 | **7.1.0** |
| `spring-boot` | 4.1.2 | **4.2.0** | 4.1.2 | **4.2.0** |
| Jackson 2 `jackson-databind` | 2.22.3 | 2.22.3 | 2.22.3 | 2.22.3 |
| Jackson 3 `tools.jackson.core:jackson-databind` | 3.2.3 | 3.2.3 | 3.2.3 | 3.2.3 |
| `tomcat-embed-core` | 11.0.25 | 11.0.25 | 11.0.25 | 11.0.25 |
| `flyway-core` | 12.4.0 | **12.11.0** | 12.4.0 | **12.11.0** |
| `elasticsearch-java` | 8.14.3 | 8.14.3 | **9.5.6** | **9.5.6** |
| `elasticsearch-rest5-client` | — | — | **9.4.8（BOM 偏斜）** | **9.5.6** |
| Apache HttpClient | 4.5.14（HC4） | 4.5.14（HC4） | **httpclient5 5.6.5 / httpcore5 5.4.4** | **httpclient5 5.6.5 / httpcore5 5.4.4** |
| `grpc-api` / `grpc-core` | 1.85.0 / 1.85.1 | 1.85.0 / 1.85.1 | 1.85.0 / 1.85.1 | 1.85.0 / 1.85.1 |

Jackson 2/3 未变，是因为 `pom.xml` 已显式钉住 2.22.3 / 3.2.3（SCA 补丁）。
C 格的 transport 9.4.8 是 Boot 4.1.2 BOM 的默认管理版本；实现切片必须同时钉住
`elasticsearch-java` 与 `elasticsearch-rest5-client`，或直接采用 Boot 4.2 的 BOM。

## 官方兼容性（访问日期 2026-11-27）

- **Spring Boot 4.2 系统要求**：至少 Java 17，兼容至 Java 27；要求 Spring Framework 7.1.0+。
  <https://docs.spring.io/spring-boot/system-requirements.html>
- **Spring Boot 4.2 Release Notes**：Spring Boot 4.0 中弃用的类/方法/属性在本版移除；
  Spring Framework 7.1 弃用 `RestTemplate`（转向 `RestClient`）。
  <https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes>
- **Spring Boot 4.2 属性表**：`management.health.elasticsearch.enabled` 仍存在。
  <https://docs.spring.io/spring-boot/appendix/application-properties/index.html>
- **Elasticsearch Java Client 兼容矩阵**：9.x 客户端对应 9.x 服务端；8.x 分支支持 8.17。
  <https://github.com/elastic/elasticsearch-java#compatibility>
- **传输层**：`rest5-client` 是默认 HTTP 实现，基于 Apache HttpClient 5。
  <https://github.com/elastic/elasticsearch-java/blob/main/docs/reference/transport/index.md>
- **超时语义**：connect/socket 在 `ConnectionConfig`，response/connection-request 在
  `RequestConfig`；通过 `setConnectionConfigCallback` / `setRequestConfigCallback` 配置。
  <https://github.com/elastic/elasticsearch-java/blob/main/docs/reference/transport/rest5-client/config/timeouts.md>
- **9.5.6 依赖事实**：POM 依赖 `elasticsearch-rest5-client:9.5.6`，Jackson 2.22.2（runtime）
  与 Jackson 3.1.0（runtime）。
  <https://repo1.maven.org/maven2/co/elastic/clients/elasticsearch-java/9.5.6/elasticsearch-java-9.5.6.pom>

**「8.14 服务端 + 9.x 客户端」是否官方支持：否。** 官方矩阵只列 9.x 客户端 ↔ 9.x 服务端；
文档只承诺“客户端向前兼容（旧客户端连更新的服务端）”，反向（新客户端连旧服务端）属于
backwards compatible、**without guarantees**。因此不能把本 spike 的编译/测试全绿当作生产兼容证明。

## 运维影响

- **健康检查**：`management.health.elasticsearch.enabled` 在 Boot 4.2 属性表中仍是合法键；
  本仓库早已置 `false`，有效探针是 `HealthCheckConfig.esHealthIndicator`（组件键 `es`，
  走 `ElasticsearchClient`，不依赖低层 `RestClient`）。ES 9 下 Boot 的
  `ElasticsearchRestClientHealthIndicator` 预期不再装配，该键成为空操作；可保留到后续清理切片。
- **配置键**：`opspilot.es.*` 无 rename；超时/池上限仍由 `EsConfig` 映射，但 HC5 的语义是
  socket 超时在 `ConnectionConfig`、响应超时在 `RequestConfig`。
- **日志/指标**：应用自有指标与日志键无 rename；底层 HTTP 日志包名从
  `org.apache.http` 切到 `org.apache.hc.client5`，抓包/排障规则需同步。
- **资源**：memory/mock 下 B/C/D 的启动时间 12.60–12.66s、RSS 304–324MB，
  相对 A（13.55s / 319MB）无显著增量；真 live（ES/Qdrant/Redis）未测。

## 结论、成本与触发线

| 依赖 | 结论 | 触发线 | 工作量 |
| --- | --- | --- | --- |
| Spring Boot 4.2.0 | **ADOPT** | 立即可做；合并前重跑 SCA/SBOM + static-gate | 2 文件 / 0 类 / 0.5 人日 |
| `elasticsearch-java` 9.5.6 | **DEFER** | 生产或 staging ES 服务端升到 9.x（目标 9.5.x）；或 8.14 失去安全补丁；或 Boot 4.3 移除 8.x 客户端 | 7 文件 / 4 main 类 + 3 test 类 / 3–5 人日（含 live 联调） |

## 后续切片

1. `boot42-upgrade`（ADOPT）：`pom.xml` 4.2.0 + README 两处 + 重跑 SCA/SBOM、
   `production_gate.sh --fast`、全量测试；无类改动。
2. `es9-client-migration`（DEFER 解锁后）：同时钉 `elasticsearch-java` 与
   `elasticsearch-rest5-client`（或用 Boot 4.2 BOM），迁移 `EsConfig` / `IngestionRunner` /
   `CoverageService` / `EsSearchService` 与 3 个测试类；保持断言极性不变。
3. `es9-live-validation`：在 ES 9.x staging 上验证索引/别名切换、range 过滤、
   检索与覆盖范围、健康探针、blue/green 重建；这是 DEFER 转 ADOPT 的硬证据。
4. `es-health-key-cleanup`（可选）：确认 Boot 4.2 下
   `management.health.elasticsearch.enabled` 为空操作后，清理注释与配置。
5. `dependency-bom-alignment`：把 ES 客户端/transport 的版本对齐纳入 SCA/SBOM 检查，
   防止 C 格 9.5.6/9.4.8 偏斜重现。

## Dependabot 处置建议（只建议，未操作）

- **#108（Boot 4.2.0）**：建议保留并改基到当前 `main`，随 `boot42-upgrade` 一起合并；
  合并前补 README 两处并重跑 SCA/SBOM。**不要直接关闭。**
- **#81（ES 9.5.6）**：建议保留 Open，标题/标签注明「blocked on ES server 9.x」；
  待 `es9-live-validation` 有真栈证据后再改基或重开。**不要直接关闭。**

## 复现命令

```powershell
cd C:\Users\Shing\AppData\Local\Temp\opspilot-lab-boot42-es9
git rev-parse HEAD && git status --short
$env:MAVEN_OPTS='-Xmx1g'
E:/java/apache-maven-3.9.14/bin/mvn.cmd test -DforkCount=1 -DreuseForks=true
E:/java/apache-maven-3.9.14/bin/mvn.cmd -DskipTests package
```

```bash
JAVA_BIN=E:/java/jdk21/bin/java.exe PYTHON=python bash scripts/smoke.sh
DEMO_PASSWORD=<throwaway> JAVA_BIN=E:/java/jdk21/bin/java.exe PYTHON=python bash scripts/redteam.sh
DEMO_PASSWORD=<throwaway> JAVA_BIN=E:/java/jdk21/bin/java.exe PYTHON=python bash scripts/benchmark.sh
```

## 残余风险

- 所有活体证据是 memory + mock，不经过 ES/Qdrant/Redis；不能替代 live 兼容性验证。
- 启动/内存是单次读数，存在 JIT、页缓存与 GC 噪声；只用于判断“没有数量级回退”。
- 未跑 Trivy/SCA 与 SBOM 差异；Boot 4.2 合并前必须补。
- C 格的 transport 版本偏斜（9.4.8）已被识别，但未在 C 格内修正；D 格不存在该偏斜。
