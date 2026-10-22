# OpsShield 运维视角审查（2026-10-22）

> 审查人：以 SRE / 运维工程师视角做的一次“收尾前体检”。
> 方法：只读仓库 + 本机活体取证（临时目录另起 memory/mock 实例，端口 18081，未触碰现有栈与 .env 之外的数据）；四条线并行深审（可靠性、安全、交付、代码与测试），本文对结论做了交叉核对与重新定级。
> 结论口径：**机制层达到“可演示、可论证”的水准；运维交付层尚未达到“可托付”。** README / 就绪度台账里的“生产级”目前是机制描述，不是运行事实。

---

## 0. 本次实测到的关键事实（先看证据，再看判断）

| 取证 | 结果 |
|---|---|
| `mvn -B test` | **301 个测试全绿**（与 README 一致）；本机 JaCoCo 因目录名含非 ASCII 字符不落盘（pom 注释已说明），覆盖率门槛只在 CI 生效 |
| `pytest --collect-only offline/tests` | **338 个用例**（README 写 319，已漂移） |
| memory/mock 实例启动 | 13.8s 起来；`/actuator/health` 匿名 200；`/actuator/prometheus` 匿名 200；监听 `::`（**所有网卡**），从本机 LAN IP 可访问 |
| 登录防护 | 连续 5 次错口令 → 第 5 次 429；**随后用正确口令仍 429**（同账号被锁 15 分钟） |
| 审计注入面 | 未认证登录请求的 username 被原样写进审计行：`"sub":"<img src=x onerror=alert(1)>"`（后续面板渲染点未转义，见 P0-3） |
| 监控栈现状 | Prometheus/Alertmanager 容器健康运行 2 小时；`up{job="opspilot"}=0`（target down），**firing alerts 为空**；Alertmanager 无告警，无人类通知出口 |
| 备份现状 | `backup/` 最新 `users-2026-09-17.zip`，今天 **2026-10-22，停摆 35 天**；本机无相关计划任务；`data/users.mv.db` 已更新到 10-20 |
| 数据目录 | `logs/` 98 个文件 11.8MB（61 个 `run-*.log` 从未清理）；`logs/watchdog_task.log` 5.5MB 里 9 段 traceback；`logs/liveness.jsonl` 不存在 |
| Git/资产 | `.env` 从未入库（`git log --all -- .env` 为空）；README 首屏 GIF 的 `docs/assets/readme-workbench.gif` **未跟踪**（GitHub 上会裂图）；版本号 4 处硬编码 `1.0.0`，而 tag 已有 v1.1.0 且其后 135 个提交 |
| 依赖面 | Spring Boot 3.3.5，3.3.x 的 OSS 支持已于 **2025-06-30** 结束（末版 3.3.13）；protobuf-java 3.25.1 落在 CVE-2024-7254 受影响区间；无 SBOM/SCA/依赖机器人 |

---

## 1. 先承认：这个项目真正做对的地方

以下不是客套，它们决定了这个仓库的上限：

- **权限是引擎层数据过滤，不是提示词约定**：ES `filter` 与 Qdrant `Filter` 双引擎注入 tenant+auth_level，缓存键与 Single-Flight 组键同样带权限维度，cross-tenant replay 有 fail-closed 绊线；
- **“零幻觉”是构造保证**：只读命令清单从本次引用原文逐字抽取，四层策略判拒，答案接地有离线探针；
- **证据文化**：ADR、`PROVENANCE`、`doc_numbers`、面板契约、门闩脚本、变异验证——这套自我登记密度在个人项目里非常少见；
- **降级是一等公民**：L0/L1/L2 状态机、审计转移事件、半开自愈，不是 catch 块；
- **零依赖 memory 模式**：把“评审者能在 5 分钟内跑起来”做成了产品能力（ADR-0022）。

**问题不在机制设计，而在“机制跑起来之后谁来看、谁来修、坏了怎么恢复”这一层。** 下面按“离生产还有多远”排序。

---

## 2. P0：在声称“生产级”之前必须解决的 5 件事

### P0-1 备份与恢复：唯一不可再生数据在裸奔，且“恢复”会倒退权限

**证据**
- OPS.md:298 写“每日备份”，OPS.md:305-308 给的是 Linux cron 与“Windows 想起来手敲一行”；实测最新备份 2026-09-17，停摆 35 天，本机 223 条计划任务无一相关。
- scripts/backup.sh:31-32 明文 tar，:36-37 本地保留 7/14 天，无加密、无异地、无 sha256、无 unzip -t 校验；data/、logs/、backup/ 三者同盘。
- data/users.mv.db.corrupt-20260916.bak 与 data/users.trace.db 里 18 次 “The file is locked” 证明这个库**已经真坏过一次**（事故 51103，见 scripts/user_admin.sh:14-28 的守卫注释）。
- 恢复链：UserStore.java:68-70 每次 disable/rotate 都 token_ver+1，而 UserAdminCli.java:74-87 的 restore 只校验“目标库不存在”。恢复到 9/17 的库 = **token_ver 回退 + 被禁用账号复活 + 口令回退**，已吊销但未过期的旧 JWT 重新有效。
- docs 与 OPS 里没有 RPO/RTO 定义，也没有“最近成功备份时间”的指标或告警。

**运维后果**：今天磁盘故障、误删、勒索或 H2 再次损坏，最近可用恢复点是 5 周前；恢复动作本身会把权限状态一起倒退，而且没有任何审计行记录“有人恢复过旧库”（UserAdminCli 全文 grep audit 为 0）。这是典型的“有备份脚本、没有备份系统”。

**建议（1 天内可做）**
1. 计划任务/系统服务每日跑 backup.sh，成功后写 backup/.last-success（时间 + sha256 + 文件大小），失败非零退出；
2. 备份后 unzip -t + sha256sum，失败即告警；备份龄 > 26h 告警；
3. 至少一份异盘/异地副本；备份加密（bcrypt 散列 + tenant/auth_level 离开机器就是可离线爆破的口令库）；
4. 写死 RPO=24h、RTO=1h，并注明“恢复会回退吊销状态，恢复后必须强制全员重新登录”；
5. restore_drill.sh 把 OPS §8 的手工演练做成一条命令，CI 里跑一次 --to-db 演练库。

### P0-2 可观测性与告警闭环没有“人类出口”，网关自己死了没人知道

**证据**
- 三条规则全是 sum(rate(aiops_*_total[5m])) > 0 + for: 0m（ops/alerting/rules/opspilot.yml:17-47），**没有 up{job="opspilot"} == 0**，也没有 SLO burn-rate、备份龄、磁盘、在途、配额、审计中断；
- 唯一 receiver 是宿主 bridge（ops/alerting/alertmanager.yml:37-41），没有 SMTP/第二通道；bridge 的职责是把告警**翻译成对网关的提问**，答案只落 logs/alert-bridge.jsonl——**告警从来不送达人**；
- 更糟的是投递失败语义：bridge 对正常载荷一律回 200，即使内部转发失败（scripts/alert_bridge.py:455 + offline/alert_delivery.py:271 的 CONTINUE），只在台账记 sent_ok=false → Alertmanager 不会重试，**网关短暂不可用期间的告警被静默丢弃**；
- scripts/liveness.py:20-22 明说“只探测+记录+退出码，不重启、不投递”，而 logs/liveness.jsonl 根本不存在；网关/bridge/watchdog 都没有系统服务或计划任务（中间件有 restart: unless-stopped，宿主进程没有）；
- 实测：Prometheus target 已 down 2 小时，规则未 firing、Alertmanager 无告警——**监控面板全绿，产品已下线**；
- scripts/watchdog_alerts.py 的 login 在 try 之外，网关不可达时会抛 URLError 退出（logs/watchdog_task.log 9 段 traceback 即证据）——它恰恰是“网关挂了”时唯一的产品内信号源。

**运维后果**：机器重启（Windows 更新 / Docker Desktop 修复，OPS §10 实录）后中间件自起、网关与 bridge 不起；没有第二通道叫醒人。告警链路与被监控对象同生共死，这是“自监控”最忌讳的拓扑。

**建议（1-2 天）**
1. 加 up{job="opspilot"} == 0 for 2m；加恒定 firing 的 Watchdog + 外部 dead-man（第三方心跳）；
2. Alertmanager 加第二 receiver（SMTP/IM/企业微信至少一个），bridge 失败返回 5xx 或进本地重试队列；
3. 网关、bridge、watchdog、liveness 注册为系统服务（Windows 计划任务/NSSM；Linux systemd），开机自启；
4. 修 watchdog_alerts.py 的 login try/catch 与主循环兜底；
5. Prometheus/Alertmanager 加命名卷（现在只挂配置，重启即丢历史与静默）。

### P0-3 部署默认不安全：网络边界、凭据默认值、请求体上限、面板存储型 XSS

这四条单看都是“小修”，合起来是“一台同网段机器即可拿到平台管理员”。

**3a 监听 0.0.0.0，与“端口仅绑 127.0.0.1”的自述冲突**
- src/main/resources/application.yml:1-2 只设 port，无 server.address；scripts/start_gateway.sh:85,89 只传 -Dserver.port；实测监听 ::，LAN IP 可访问 /actuator/health、/actuator/prometheus、登录端点、面板首页。
- compose 把容器端口绑到 127.0.0.1（docker-compose.yml:58），但 OPS 把“宿主裸进程”定为主形态（OPS.md:273-275）——**被定为生产形态的那条路径反而没有边界**。
- 建议：server.address 走环境变量 BIND_ADDR（默认 127.0.0.1），容器内显式 0.0.0.0；启动摘要打印实际 bind。

**3b 凭据默认值可用**
- src/main/java/com/opspilot/auth/JwtService.java:24-28 只判非空；.env.example:4 的 change-me-to-a-random-32-char-secret 有 36 字符，jjwt 的 HS256 只要求 256 bit，**能通过启动校验**；sub=sre-full 是文档公开账号名，schema.sql:10 的 token_ver 默认 1 → 拿到仓库的人可离线伪造平台管理员 token。
- application.yml:23 H2 口令默认空；application.yml:21 AUTO_SERVER=TRUE；实测 data/users.lock.db 里 server=100.99.204.104:62402（Tailscale 地址）——H2 的 TCP 服务开在非环回网卡上，且 H2 TCP 无 TLS。
- 建议：启动期拒绝出厂占位/低熵 JWT_SECRET（要求 ≥43 字符 base64 或 64 hex）；H2_DB_PASSWORD 非空校验；运行时去掉 AUTO_SERVER（维护窗口停网关 + CLI），必须用时加 -Dh2.bindAddress=127.0.0.1 + 固定端口。

**3c 请求体与审计字段无上限**
- AuthController.java:30 的 LoginReq 只有 @NotBlank；ChatRequest.java:28-35 的 query 无 @Size；application.yml 无 Tomcat body 上限；AuditService.java:112-121 的 sub 不截断（对比 q 截 200）。
- 未认证的 /api/v1/auth/login 可接收 10MB username → Jackson 全量缓冲 → H2 查询 → Redis 10MB key → 审计同步落 10MB 单行。磁盘满后 logback 只进 StatusManager，审计静默丢失，而 H2 与日志同盘。
- 建议：server.tomcat.max-swallow-size=1MB + max-http-form-post-size=1MB；登录/DTO 加 @Size；AuditService 所有字符串统一 trunc()；登录按 IP 限流。

**3d 面板存储型 XSS（本次实测复现了存储侧）**
- 未认证登录失败会把 username 原样写审计：实测 sub 字段含 <img src=x onerror=alert(1)>（AuditService.java:112-121）；
- 面板事件流渲染 ev.sub 时**未转义**：src/main/resources/static/index.html:1346-1349 的 who 直接拼进 insertAdjacentHTML（同文件其他字段都走了 esc()，这里是漏点）；
- 平台管理员打开“运行面板”后每 2s 轮询 /admin/audit/recent，事件流命中即执行；token 存在 sessionStorage，脚本可直接读走 → **未认证注入、认证后执行、可升级为平台管理员会话劫持**。
- 建议：who 包 esc()；补 CSP / X-Content-Type-Options / X-Frame-Options 响应头；登录失败审计对 sub 做白名单/转义（纵深防御）。

### P0-4 静默 mock：一个“全绿”的假能力

**证据**：OpsPilotProperties.java:121-125（auto 无 key → live=false）、LlmClient.java:73-76（非 live 直接 mockStream）、MockEngine 明确“不承诺检索质量”；但 HealthProbe.java:113-131 的 status 只看 redis/qdrant/es，mock 不影响 UP；审计行与 Prometheus 计数器**都没有 backend 字段**（mode 是检索模式，不是后端模式），aiops_llm_calls 在 mock 下照加。

**运维后果**：漏配一个 env（systemd EnvironmentFile 少一行、灰度机复制了模板、key 过期）就得到“健康、200、流式、审计全有”的实例，但答案是哈希召回 + 模板抽取。值班按它给的止损步骤操作，比直接 5xx 更坏；事后审计/指标**分不出**哪些答案来自 mock。

**建议（S）**：生产 profile 下 auto + 空 key 直接启动失败；审计行永久带 backend: mock|live；加 aiops_backend_mock gauge + readiness 组（mock 时 readiness DOWN）；启动摘要已有 mode=mock，但必须让健康面也表达。

### P0-5 单用户可打爆全站：无准入控制，降级状态全局共享

**证据**
- 入口无任何并发准入：ChatOrchestrator.java:87-90 的 precheck 只做每用户日配额与计数；:95-120 直接 vt.execute；全仓无 Semaphore/RateLimiter；Tomcat 默认 maxConnections 是唯一事实上限。
- 降级状态机是全局单例：DegradationStateMachine.java:87-94,161-171 按“连续 3 次 LLM 失败”开 60s L2，**没有 tenant/sub 维度**；L1 只是把检索切成 es_only，LLM 调用完全不变（ChatOrchestrator.java:240,297-311）。
- 项目自己的压测已复现：48 并发唯一指纹 → 在途峰值 137 → L0→L1；0.4s 内 41 次 too many concurrent streams + 76 次 429 → 3 秒内 L1→L2（docs/ops/production-readiness-2026-09-12.md:491-492）。
- 配额在 SingleFlight 折叠之前计数（ChatOrchestrator.java:87-90 早于 :141），每个 follower 也消耗配额；5000/天足够一个用户反复打 45 并发。

**运维后果**：某个团队写错循环、或一次真风暴里有 45 条不同指纹 → 3 秒内全实例 L2，此后 60 秒**所有租户、所有人**（包括管理员自己排障）只能拿静态 SOP；持续限流下“冷却 60s → 再失败 → 再 60s”可无限循环。值班看到的指标是“降级常亮”，真相是“某个用户并发太高”。

**建议（1-2 人日）**：LLM 调用外包全局 Semaphore（按上游并发上限的 60–70%）+ 每租户 Semaphore；拿不到许可排队 2–3s 后回 429+Retry-After；熔断按错误类型（429 退避、不参与开闸）与滑动窗口失败率；降级状态按租户维度复制（至少让单人打爆不影响他人）；给 inflight 加 gauge 与告警。

---

## 3. P1：生产不可接受，但可排期（按主题合并）

### 3.1 单机形态与发布：H2 是硬单点，schema 无迁移，制品不可追溯

- 每请求鉴权查单文件 H2（JwtAuthFilter.java:81）；H2 故障被 catch 后统一 401 invalid_api_key（:91-93），值班会误判为 token/攻击；何况 /actuator/health **不包含 H2**（HealthCheckConfig.java:17-31 只有 redis/qdrant/es）。
- 多实例不会拒绝启动，会静默分裂：各自一份 H2（吊销状态不同）、各自 SingleFlight/降级/缓存（一台 L2 一台 L0）、各自 reingest。OPS.md:104 的“多实例”触发线只写“团队>200 或 QPS>50”，没写“H2 是它的硬前置”。
- schema.sql:3 只有 CREATE TABLE IF NOT EXISTS，application.yml:24-26 每次启动执行；加列/改约束对已有库**静默无效**，新代码读到旧表即登录 SQLException；UserAdminCli.java:94-102 还有第二份内联 DDL。无 Flyway/Liquibase。
- 版本 4 处硬编码 1.0.0（pom.xml:16、Dockerfile:16、start_gateway.sh:30、user_admin.sh:11），build-info 无 commit；git 已有 v1.1.0 且其后 135 个提交未打标签；target/ 被 gitignore，旧制品不留存 → 回滚 = checkout + 重打包 5-10 分钟（Windows 上还要先停 JVM，否则截断 jar）。
- 无优雅停机：application.yml 无 server.shutdown=graceful，compose 无 stop_grace_period，AsyncConfig.java:12-15 只 shutdown 不等待 → 发布/重启截断在途 SSE，而审计行在 sink.deliver 之后才写（ChatOrchestrator.java:330-335），**丢失的正是复盘需要的证据**。
- 建议：H2 迁 PostgreSQL 或至少单写者 + 启动文件锁；Flyway；镜像/仓库/commit/tag 对齐 + release.sh；server.shutdown=graceful + spring.lifecycle.timeout-per-shutdown-phase=30s + compose stop_grace_period: 90s；H2 健康探针与 503 错误码。

### 3.2 Redis 是热路径硬依赖，而淘汰策略会清掉安全与兜底状态

- 每请求 quota.checkAndConsume 走 Redis INCR（QuotaService.java:30-36）；Redis 一慢/挂，chat 全 500（ChatOrchestrator.precheck 抛异常 → GlobalExceptionHandler 500）。登录失败计数、L1、滑动窗口、SOP 全在同库。
- compose：--maxmemory 256mb --maxmemory-policy allkeys-lru --save ""（docker-compose.yml:12）；而 SOP 键 sop:<tenant>:steps 通过 mapPut 写入、**没有 TTL**（RedisKvStore.java:76-79、IngestionRunner.java:429-446）。allkeys-lru 下“SOP 在 L0 期零访问”正是最先被淘汰的一批；配额与登录锁定键也可能被淘汰（=成本护栏归零、暴力破解窗口重置）。
- Redis 无持久化（--save ""），重启即清空当日配额与锁定计数（OPS §10 把“重启 Redis”列为常态恢复动作）。
- 建议：volatile-lru 或把 SOP/配额/锁定拆独立实例；SOP 加长 TTL + 启动时检测为空则重新 warm；配额/锁定持久化或明确“重启重置”并纳入审计；Redis 故障时给 chat 一条明确的降级/503 路径，而不是裸 500。

### 3.3 缓存正确性与生命周期：“成功被写成失败”、L2 无 TTL、语料更新后仍回放旧答案

- **成功变失败**：ChatOrchestrator.java:319 先写 L1，:324 再做 L2 写入前的 embedding.embedOne(query)（同步，read timeout 15s，application.yml:68），:325 l2.store，:330 才 sink.deliver(done)；而 :309-344 的 catch 把任何异常都 degrade.llmFailure() 并抛 FailedWithContract。embedding 抖动 → 用户看到“半截答案 + error”、**无审计行**（审计在 deliver 之后），3 次即误开全局 LLM 熔断（LLM 本身健康）。
- **L2 直出前仍调 embedding**：ChatOrchestrator.java:193 无条件 l2.lookup，而 lookup 第一步就是 embedding（L2SemanticCacheService.java:58）。上游整体不可用时，“熔断直出 SOP”每个请求仍要等 3s connect / 15s read 才落到 SOP——零 LLM 但首字节 15s。
- **L2 无 TTL/无淘汰**：live 每次成功生成写一个 Qdrant 点（payload 含 query_text 明文），created_at 只写不读；语义缓存集合永不清理，reingest 只 ensure 不清空（IngestionRunner.java:267-283）→ 语料更新后旧答案/旧 refs 仍可按 0.95 相似度回放，且磁盘只增。memory 版是 CopyOnWriteArrayList 全表线性余弦（L2SemanticCacheService.java:40,91-107），唯一 query 永久增长。
- **memory 惰性过期**：MemoryKvStore.java:157-163 只在访问同键时删除；L1 按指纹分键，绝大多数键此后再不读 → memory 模式跑得越久越慢，最终 OOM。
- 建议：sink.deliver 提前到 L2 之前，L2 写入整体异步且失败只计 cache_write_fail；L2 档位直接跳过 lookup/store；L2 加 TTL + 容量上限 + 条目数 gauge，reingest 后按代际失效；memory 版换有界 LRU + 定期清扫。

### 3.4 失败路径是证据黑洞：没有审计、没有堆栈、没有指标

- ChatOrchestrator.java:106-118 两个 catch 只 logWarn + sink.deliver(Failed)，**没有 audit.log**；logWarn 只打类名+message，不打堆栈（:350-354）；虚拟线程异常到不了 GlobalExceptionHandler。
- 因此欠费、4xx、60s 超时、后段异常这些最需要证据的时刻：审计里没有“谁在 10:03 问了什么、为什么没拿到答案”，app.log 只有一行无堆栈的 message，指标 aiops_requests_total 照加。SSE 面恒 200，错误率只能靠 5xx 算——**算不出来**。
- 客户端断开也不可见：SSE 写失败只 log.debug（SseEvents.java:119-125，而包级日志是 INFO，debug 永不可见），服务端继续跑完 60s、写 L1/L2、记一条“成功”审计；风暴期这些僵尸请求把 inflight 顶进 L1（**由已无人接收的请求触发降级**）。
- 建议：catch 落 ev=chat_failed（err_class/err_msg 截断、degrade_level、refused），logWarn 传异常对象；加 aiops_requests_failed{class}；SSE onCompletion/onError 置 aborted 标志，token 回调与 deliver 前短路，aborted 跳过缓存写并审计 delivered=false。

### 3.5 观测与 SLO：只有计数器，没有分位；“承诺线”没有测量装置

- OpsMetrics.java:45-73 只有 15 个无标签 Counter + 4 个累计比率 gauge；全仓无 Timer/DistributionSummary → **Prometheus 侧没有 TTFT/请求延迟分位**，而 TTFT P95 < 3s 是 OPS §11 的 SLO 之一。
- 4 个比率 gauge 是“自启动以来累计比值”（OpsMetrics.java:64-72），不是实时健康度；重启归零、风暴后不回落，拿它做告警每次发布必误报。
- 告警规则 for: 0m + rate(...)>0：单次瞬时事件就叫；网关重启后计数器复位，rate() 会把复位前峰值补回，**“上游 429”假阳约 10 分钟**（docs/qa/2026-10-17-alertmanager-intake.md:93-105 已实测，结论是“不修”）；每次发布烧掉 bridge 1/20 小时预算。
- SLO 七条里 4 条“待 live 补”（OPS.md:411-419）；可用性 99.5% 的核色方式是压测的 Failure Count，不是请求成功率；无 error budget/burn-rate/月度复盘/on-call/状态页。
- 无 Grafana dashboard；Ops Console 的时序是浏览器 localStorage 采样，只在页面打开期间存在（文档已承认）。
- 建议：加 http.server.requests 分位（management.metrics.distribution.percentiles）与 aiops_ttft_seconds histogram；失败/成功计数分离；up/SLO burn-rate/备份龄/磁盘/在途 告警；OPS §11 改成带日期与产物的月报；先有“人类出口”再谈 error budget。

### 3.6 审计合规：14 天、可改写、无异地、无容量上限

- logback-spring.xml:23-32 审计只有 maxHistory=14、**无 totalSizeCap**（app.log 反而有 500MB cap）；按 10-17 实测 449B/行、88 req/s 满负荷约 3.4GB/日，先写满盘，再伤及同盘 H2。
- 审计写入同步且 immediateFlush=true，每条事件进 synchronized ring + 同步文件写，在请求尾段；磁盘慢时直接拖延迟。
- ring 在落盘**之前**入队（AuditService.java:217-221），logback 写失败不抛异常（catch 基本死）→ /admin/audit/recent 会展示从未落盘的“幽灵证据”，且没有写失败计数。
- 内容：q 存 query 前 200 字符原文，而用户被明确引导“贴日志”（连接串/内网 IP/token 片段可能进审计）；平台管理员可跨租户读全量审计且读审计本身不留痕；logs/ ACL 实测 Authenticated Users: Modify（任何本地账户可改删）；14 天不满足等保/网安法常见的 6 个月留存。
- 建议：审计单独卷 + totalSizeCap + 写失败指标；异步 appender（discardingThreshold=0）；追加 hash 链或每日 sha256sum 到只追加存储；q 出口脱敏或只存指纹；平台读审计留痕；留存 ≥180 天；icacls 收权。

### 3.7 CI / 供应链 / 升级：验证不到“能部署”，依赖停在 2025 年

- ci.yml 五个 job 无 docker build、无起栈、无启动 smoke、无备份/恢复 smoke、无依赖扫描/镜像扫描/SBOM；ci.yml:6 的理由“live 验收需 compose 栈”在 ADR-0022 memory 模式后已不成立——memory+mock 约 14s 就能起一次服务。
- 全仓无 @SpringBootTest（只有 2 个 ApplicationContextRunner + 1 个 standalone MockMvc），全上下文装配/yml 绑定/logback 路径/条件装配从未在 CI 启动过；覆盖率 LINE 门槛 46%。
- Spring Boot 3.3.5：3.3.x 的 OSS 支持 **2025-06-30 结束**（末版 3.3.13），其管理的 Tomcat 10.1.31、protobuf-java 3.25.1（CVE-2024-7254）落在已知 CVE 区间；无 Dependabot/Renovate、无 SCA、基础镜像 tag 未 pin digest、容器 root、无 read_only/no-new-privileges；构建依赖走第三方 aliyun 镜像。
- 建议：升到受支持 Boot 3.5.x 或 4.x；CI 加 memory 启动 smoke + docker build + backup/restore 演练 + dependabot/trivy/SBOM；action 与 base image 钉 SHA/digest；容器非 root、只读根。

### 3.8 入口安全运营：登录锁定可被用来打断告警链，/search 不设配额

- AuthService.java:24-25,45-49：5 次/15min，按 auth:fail:<sub> 计数，**没有 IP 维度**；锁定判定在凭证校验之前，且锁定可被反复续期。实测 5 次错登后正确口令同样 429。
- 攻击者知道或猜到公开账号名 sre-full（文档公开）即可把管理员锁在门外；锁定 sre-watcher 则让 alert_bridge 连续 3 次登录失败后退出（scripts/alert_bridge.py:83-84）→ **告警链静默断**，且可被用来掩盖真实故障。
- 不存在用户短路跳过 bcrypt（AuthService.java:52-54）→ 存在/不存在用户有可测时延差（计时枚举）；/search 不占配额（SearchController.java:36-39），任一持证用户可不限量烧 embedding/rerank。
- 建议：auth:fail:<sub> + auth:fail:ip:<remote> 双计数、指数退避；不存在用户也跑一次 dummy bcrypt；平台账号锁定走人工解锁；给 /search 加速度限制/配额；登录/锁定失败进指标与告警。

### 3.9 可运维性与文档：97KB 单文件、绑定 Windows、日志卫生

- OPS.md 97KB/704 行，最长行 1,489 字符，恢复动作散在 §7/§8/§10/§13，且绑死 Windows（taskkill、wsl -t docker-desktop、D:\Docker\...）；标题写“五条纪律”正文编号到 6。新值班人遇“登录 500”要在四节里拼顺序，5 分钟找到正确动作的概率很低。
- logs/ 98 个文件 11.8MB，61 个 run-*.log 无轮转；watchdog_task.log 5.5MB；logs/usage.log 最后一条 9-27（daily_usage 未调度）。
- README 首屏 docs/assets/readme-workbench.gif 未跟踪（GitHub 裂图）；.env 有 LOBE_ACCESS_CODE 僵尸键（全仓 0 引用），SERVER_PORT/GATEWAY_HEAP 又不在 .env.example——配置三处真相且已漂移。
- 数字漂移：README “319 probes” 实际 338；版本号/标签不一致；doc_numbers 不覆盖 pytest 收集数与 surefire 执行数。
- 建议：拆 docs/runbook/（每症状一页：判据/动作/验证/回滚），OPS 只留索引；日志文件按天轮转；README 补资产/改数字/删僵尸配置；把 pytest 收集数纳入 doc_numbers。

### 3.10 容量结论与故障注入：压测只读到首帧，只有一种故障注入

- offline/load/locustfile.py:95-102 在 200 后立刻 success 并关闭流，**没读完 done 帧**；所有“RPS 峰值/拐点”是本机回环 + 首字节延迟，不是应用容量；u≥200 的失败发生在连接层（/state 采样也为 0），报告把这列成“在途”。
- 只注入过 LLM 404（LLM_MODEL=__opspilot_probe_invalid__），ES/Qdrant/Redis/embedding 从未注入；无长稳压测（>30min）、无泄漏测试、无重启/升级在途测试、无 SIGTERM 演练。
- 建议：locust 读完 done 再判成功；加 30min soak（RSS/堆/线程/连接曲线）；把“停 ES / 停 Qdrant / 慢 embedding / 压测中 SIGTERM”固化成脚本，纳入发布前检查。

---

## 4. P2 快速清单（可在收尾批次顺手做）

| # | 项 | 证据/动作 |
|---|---|---|
| 1 | 容器 JVM 堆与宿主差 5 倍 | Dockerfile:24 无 -Xmx，compose mem_limit: 768m → Java 21 默认 25% ≈192MB；宿主 -Xmx1024m。加 -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError，统一 JAVA_TOOL_OPTIONS |
| 2 | Docker HEALTHCHECK 用 wget | eclipse-temurin:21-jre 不保证带 wget（推断，需 docker run --rm ... command -v wget 验证）；容器可能恒 unhealthy |
| 3 | 重灌失败泄漏 staging | IngestionRunner.java:171-184 失败路径不删 staging 索引/集合；加 try/finally 清理 |
| 4 | reingest 部分成功无回滚 | ES 别名切换并删旧库（:370-376）后 Qdrant 切换失败 → ES 新/Qdrant 旧且旧 ES 已删；需要补偿或“先双切后删旧” |
| 5 | 检索腿串行等待 | HybridSearchService.java:67-72 先 join ES 再 join vector，最坏 9s；用统一 deadline + orTimeout/cancel |
| 6 | rerank 失败静默关门控 | :122-125 回退且 topRelevance=1.0，域外问题可能拿到自信空答案；加 rerank_failures + 区分“未调用/失败” |
| 7 | 比率 gauge 语义 | OpsMetrics.java:64-72 累计比值当实时指标；改 recording rule 或只留 counter |
| 8 | 配置校验有洞 | quota.daily-limit/chunks-path 用 @Value，填 0/-1 无启动拒绝；并入 @Validated 属性 |
| 9 | 相对路径依赖 cwd | H2/logs/backup/chunks 4 处 ./；systemd 未设 WorkingDirectory 时数据落错地方；收口到 OPSPILOT_HOME |
| 10 | 时区未显式化 | 配额日切/索引名用 LocalDate.now()/LocalDateTime.now()；注入 Clock/ZoneId |
| 11 | 审计 q 敏感串 | 用户被引导贴日志，q 前 200 字可能含连接串；出口脱敏或只存指纹 |
| 12 | 只读命令策略绕过 | jinfo -flag、git diff --output、curl --trace-ascii/-K、openssl -keylogfile 等旗标级写面；补黑名单或反转成“只放行已知只读旗标” |
| 13 | 备份明文 | users zip 含 bcrypt 散列、audit tar 含 query 原文；加密 + 校验清单 |
| 14 | 无 token/成本计量 | daily_usage 只聚合请求数/拒答率；无 token、预算、租户分摊；接 usage → Prometheus |
| 15 | 无日志聚合 | app.log 纯文本无 trace_id/线程名；Loki/ES 无法结构化；加 trace_id/线程名或 JSON encoder |
| 16 | 容器 root/构建不可复现 | Dockerfile 无 USER；action tag 浮动；.dockerignore 未排除 _archive/ 等私有目录 |
| 17 | README 资产/数字 | GIF 未跟踪、319 vs 338、版本 1.0.0 vs tag；收尾前一次性对齐 |

---

## 5. AIOps 领域特有缺口（不是代码 bug，是运营能力）

1. **知识库运营**：语料在 git、人工 build_chunks → reingest，没有“语料陈旧度/覆盖率变化/拒答率上升”的自动信号；/coverage 能看边界，但没有“缺口清单 → 排期”的闭环；reingest 后 L2 语义缓存不失效，旧答案会继续回放（见 3.3）。
2. **反馈没有消费方**：/feedback 落审计后没有聚合、分诊、周报或工单；“复盘→回灌”仍是人工。建议加 feedback_triage.py（按 fp 聚合 down 票、关联语料 doc、输出 top N 缺口）。
3. **模型/Prompt 变更无门禁**：改 LLM_MODEL 或 prompt 只靠 live 探针人工跑；没有“新模型 vs 旧模型”离线评测门禁/canary。这是 AIOps 产品最容易静默退化的一环。
4. **成本运营**：只有每用户 5000/天配额与 daily_usage 请求数；无 token 计量、无按租户成本、无预算告警、无 429 趋势；Redis 重启配额归零。
5. **值班与事故管理**：告警不送达人、无 on-call/分级/状态页/复盘模板；告警规则把“人类排障语句”当作 LLM 的输入，却没有“人收到什么”的设计。
6. **多租户运营**：tenant 是语料/DB 里的字符串，没有租户开通、配额、审计访问、数据删除（DSAR）流程；平台管理员可跨租户读全部查询原文。
7. **依赖治理**：LLM 供应商抽象、中间件 TLS 都按触发线挂起（可接受），但**缺一个“供应商/版本变更 → 重跑评测 → 更新基线”的流程**；否则挂起项到期时会一次性引爆。

---

## 6. 优化路线图（按投入产出排序）

### Phase 0：1–3 天，先让系统“可被托付地坏”
1. server.address 默认 127.0.0.1（容器覆盖 0.0.0.0）+ 拒绝占位 JWT_SECRET + H2_DB_PASSWORD 非空 + 请求体/@Size 上限。
2. 面板 who 转义 + 安全响应头；登录失败审计 sub 截断/转义。
3. 每日备份计划任务 + unzip -t/sha256 + 备份龄告警 + 异盘副本；RPO/RTO 写进 OPS；恢复演练脚本。
4. up{job="opspilot"}==0、dead-man、第二 receiver；网关/bridge/watchdog/liveness 注册为服务；修 watchdog login try/catch。
5. 失败请求审计 + 堆栈 + aiops_requests_failed；SSE 断开取消 + 计数。
6. L2 写入移到交付之后、失败不计 LLM 熔断；L2 档位跳过 lookup；L2 加 TTL/上限。
7. 生产禁用静默 mock（启动失败 + backend 标记 + readiness）。

**验收**：制造一次“停网关/停 Redis/磁盘满/断客户端/恢复旧库”演练，每个场景都能在 5 分钟内从告警/指标/审计定位到根因。

### Phase 1：1–2 周，把“单机”做成“可发布”
1. 全局 + 每租户 LLM 信号量、排队与 429+Retry-After；熔断按错误类型 + 租户维度。
2. 优雅停机 + 在途请求集合 + stop_grace_period；统一 LLM/SSE/follower 预算（当前 60+1+60 vs 90 vs 120/300 不一致）。
3. Redis：volatile-lru 或拆分；SOP TTL + 重新 warm；配额/锁定持久化或明确降级；Redis 故障走 503 而非 500。
4. H2 去 AUTO_SERVER + Flyway + 健康探针；启动期 ES/Qdrant 短超时探活（或显式 --allow-degraded-start）。
5. 版本/制品/回滚：commit 进 build-info、tag、镜像 digest、release.sh；回滚演练。
6. CI：memory 启动 smoke + docker build + backup/restore smoke + dependabot/trivy/SBOM；Boot 升到受支持版本。
7. 指标补延迟分位、TTFT、backend、inflight、失败分类；告警去 for:0m 假阳、加 SLO burn-rate 的前置（先有人类出口）。
8. 审计 cap/异步/写失败指标/保留 180 天/权限收紧；日志轮转与 trace_id。
9. 登录 IP 维度限流 + dummy bcrypt + /search 配额；平台读写分权（sre-watcher 只读）。
10. 压测读完 done；加 30min soak 与 ES/Qdrant/Redis/embedding 故障注入。

### Phase 2：1–2 个月，才谈“生产级”
- 多实例：外部 DB、Redis 分布式 SingleFlight/降级、无状态应用、reingest 选主；应用层蓝绿/滚动发布；
- SLO 测量体系：请求成功率/TTFT 分位/拒答率进 Prometheus，30 天保留，error budget + burn-rate 告警 + 月度复盘；
- 审计 WORM/SIEM 投递、hash 链、跨租户访问审批；日志集中化；
- 成本：token 计量 → 按租户预算 → 预算/异常告警；知识库陈旧度与反馈分诊自动化；
- 模型/Prompt/语料变更门禁：离线评测 + canary + 回滚。

---

## 7. 对“收尾”的建议：把两个出口分开

当前项目同时承载两个目标：**作品集/技术评审** 与 **生产试点**。它们对“收尾”的定义不同：

**出口 A：作为作品/参考实现交付（1–2 天）**
- README 与就绪度台账把“生产级”改成准确口径：**“单机可复现的生产级机制参考实现”**，并显式列出“未完成生产就绪的运维面”（备份调度、告警到人、进程守护、多实例、TLS）；
- 修 GIF 资产、319→338、版本/标签对齐、删 LOBE_ACCESS_CODE；
- 补一页“运维边界与已知风险”（可直接引用本文 P0/P1 摘要）；
- 保留全部 ADR/证据/门闩——这些是真正的差异化资产。

**出口 B：作为生产试点上线（2–4 周）**
- 完成 Phase 0 + Phase 1；以“10 条生产门禁”验收：
  1. 备份每日成功且有龄期告警，恢复演练通过（含吊销状态回退说明）；
  2. 网关 down/Redis down/磁盘满 三个场景都能在 5 分钟内告警到人；
  3. 监听地址与网络策略明确，占位凭据启动即失败；
  4. 失败请求有审计/堆栈/指标；
  5. 有优雅停机与发布/回滚脚本；
  6. 全局+租户准入控制生效，单人压不垮全站；
  7. 延迟分位与 SLO 可测量；
  8. 依赖在受支持版本，有 SCA/SBOM；
  9. 审计可证明未被篡改且满足留存要求；
  10. 值班人能在 5 分钟内按 runbook 处理“登录 500 / 网关不可达 / 降级常亮”。

**不建议做的**：为了“生产级”三个字去补遥测采集/异常检测（永久非目标）、上多区域/多活、引入 LLM 决策循环——这些会稀释项目真正的卖点：**只读、可判定、可溯源、有确定性兜底**。

---

## 8. 证据索引（便于逐条复核）

- 运行/测试：mvn -B test（301 全绿）；pytest --collect-only offline/tests（338）；memory/mock 实例启动日志（13.8s、监听 ::、health/prometheus 匿名 200）；登录锁定与审计注入实测（临时实例，端口 18081）。
- 配置：src/main/resources/application.yml、logback-spring.xml、docker-compose.yml、Dockerfile、.env.example。
- 代码：ChatOrchestrator.java、DegradationStateMachine.java、JwtAuthFilter.java、AuthService.java、AuditService.java、L2SemanticCacheService.java、MemoryKvStore.java、IngestionRunner.java、HealthProbe.java、OpsMetrics.java、index.html:1346-1349。
- 运维资产：scripts/backup.sh、scripts/liveness.py、scripts/watchdog_alerts.py、scripts/alert_bridge.py、offline/alert_delivery.py、ops/alerting/*、OPS.md §5/§8/§10/§11/§13。
- 现场：logs/（98 文件、watchdog 9 段 traceback、liveness.jsonl 缺失）、backup/（最新 9-17）、data/（corrupt-20260916.bak、users.trace.db 18 次 file locked、users.lock.db 的 Tailscale 地址）。
- 依赖生命周期：endoflife.date Spring Boot 3.3（EOL 2025-06-30）；CVE-2024-7254（protobuf-java 3.25.1）。

> 说明：本文不含任何真实凭据值；.env 只统计键与长度、不打印内容；临时实例使用独立目录与测试口令，已停止。标注“推断”的项（如容器堆 192MB、JDK HttpClient 流式 body 无读超时、temurin 镜像无 wget）建议在合并修复前用一条命令实测确认。
