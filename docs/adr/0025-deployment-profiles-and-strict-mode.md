# 部署形态与 strict 模式：绑定地址、凭据校验、演示/生产分离

状态：已接受（G0）
决定日期：2026-10-22
落地范围：G0-W0.1（`opspilot.deploy.*` + `DeploymentGuard` + `server.address` 缺省值）

上下文：2026-10-22 运维审查把四条"单看都是小修"的部署默认值放在一起实测，合起来是
**一台同网段机器即可拿到平台管理员**：

1. 网关用 Boot 默认监听（实测 `::`，所有网卡），`/actuator/health`、`/actuator/prometheus`、
   登录端点、面板首页从 LAN IP 全部可达；而 compose 形态反而把端口绑在 `127.0.0.1`——
   **被 OPS 定为生产形态的宿主裸进程，是唯一边界缺失的那条路径**。
2. `JWT_SECRET` 只判非空；`.env.example` 的 `change-me-to-a-random-32-char-secret` 有 36 字符，
   jjwt 的 HS256 只要求 256-bit，**能通过启动校验**；文档公开的账号名 + `token_ver` 默认值，
   让拿到仓库的人可以离线伪造平台管理员 token。
3. `H2_DB_PASSWORD` 默认空，`AUTO_SERVER=TRUE` 在非环回网卡上开了无 TLS 的 TCP 服务。
4. 未认证登录失败的 username 原样进审计、面板未转义渲染（存储侧已实测复现）。

这些都不是"配置写错了"，而是**缺省的形态语义没有定义**：同一份 `application.yml` 同时被演示、
单测与生产形态使用，谁也说不清"这次启动的是哪一种"。

决策：

1. **部署形态是一条显式声明，不是一个隐含约定**：新增 `opspilot.deploy.strict`（环境变量
   `OPSPILOT_STRICT`），缺省 `false` = 演示/开发形态，`true` = 生产形态声明。不用 Spring profile
   做这件事：profile 是打包/装配轴，形态是"这套配置能不能对外服务"的判据；把安全判据挂在
   profile 名上，一旦激活不到就退回不安全，而缺省值是所有人都会走的那条路。
2. **strict=true 时启动期硬校验，一次列全违规**，任一项不达标拒绝启动并点名环境变量：
   - `JWT_SECRET`：≥43 字符（HS256 256-bit 密钥 base64 最短形态）且不含占位标记
     （`change-me` / `changeme` / `replace-me` / `your-`）；
   - `H2_DB_PASSWORD`（`spring.datasource.password`）：非空；
   - `DASHSCOPE_MODE` / `DASHSCOPE_API_KEY`：必须真实 live，不得回落 mock；
   - `STORAGE_MODE`：必须 `live`，不得 `memory`；
   - `SERVER_ADDRESS`：非 `0.0.0.0`/`::`，除非显式 `OPSPILOT_ALLOW_PUBLIC_BIND=true`。
   实现落点 `config/DeploymentGuard`（`InitializingBean`）与 `config/OpsDeployProperties`。
3. **绑定地址默认收窄**：`server.address` 缺省 `127.0.0.1`（此前是 Boot 默认的全网卡）。
   容器/反向代理形态显式 `SERVER_ADDRESS=0.0.0.0`；strict 下再要一把钥匙
   （`OPSPILOT_ALLOW_PUBLIC_BIND=true`）——**双钥匙**：只有"知道自己处在容器/反代后面"的人
   才能把监听放开，忘了的人得到的是更安全的失败（外部连不上），不是更危险的通过。
4. **演示形态的放宽项必须自报**：mock 模型、memory 存储、占位凭据、环境变量默认值这四类放宽，
   在 demo 形态下允许，但启动日志必须留下一条可搜索的痕迹（strict=false 时打印跳过校验的说明；
   通配绑定时启动脚本打印"仅容器/反代形态应如此"）。"我这次是哪一形态"要和 ADR-0022 的
   `storage=memory` 一样，是一条可 grep 的事实，不靠人回忆启动命令。

理由：

① **启动失败是最便宜的失败**。本仓反复吃过同一类亏：配置写错不报错，只"看起来启动了、实际走错"
（占位密钥照样能签发、空口令照样建库、mock 照样出答案）。等上线后才发现，代价是一条已经对外
服务的路径要回滚重来。把判据放在启动期，是一次性付清。
② **网络边界与鉴权是两件事**。鉴权是 JWT，它挡不住"同网段任何人都能试口令"；`maxConnections`
与限流也不构成边界。环回默认 + 显式双钥匙，比"文档里写一句请绑 127.0.0.1"可靠。
③ **一次列全**：生产上按"改一个变量 → 重启 → 撞下一个"的节奏修配置是最慢的路径。
④ **演示这条腿不能断**：零依赖试跑（ADR-0022）是本项目的产品能力，单测 fixture 也长期依赖
占位值与 mock。把 strict 设成缺省会让演示与 CI 全红，于是必然出现"用 `OPSPILOT_STRICT=false`
一把关掉"的日常操作——那比没有开关更糟。故拆成三件套：**能安全默认的那一项（绑定地址）
默认安全**，其余用显式声明 + 门禁检查兜住。

备选与否决：

| 被否项 | 理由 |
| --- | --- |
| 缺省即 strict（不配就拒绝启动） | 见理由 ④：会切断零依赖演示与 CI 路径，最终被"顺手关掉"，等于没有闸。 |
| 用 Spring profile（`prod` / `demo`）表达形态 | profile 与打包装配耦合，激活不到就退回不安全；且"两个 profile 各一份 yml"会让判据分叉（同一份安全清单维护两遍必漂移）。 |
| 只打印警告不拒绝启动 | "配了 strict 却只警告"是半吊子；本项目已有实证——"每日备份"在文档里存在而实际停摆 35 天，告警形态的纪律失效起来无声无息。 |
| 只在校验脚本里做（`scripts/*.sh`） | 脚本可被绕过（手工 `java -jar` 启动不经过它）；判据必须在进程内、在启动期。 |
| 不分形态，一律拒绝占位凭据 | 单测、CI、演示全部起不来；把"演示"逼成"改代码才能跑"。 |
| 收紧全部默认值（连 H2 口令也强制非空） | H2 空口令在演示/单测形态是刻意允许的；强制会让"什么都不装也能跑"这条腿断掉。strict 下才硬要求。 |

后果：

- 生产部署清单多两项：`OPSPILOT_STRICT=true`、容器/反代形态再加 `OPSPILOT_ALLOW_PUBLIC_BIND=true`
  与 `SERVER_ADDRESS=0.0.0.0`。**忘配的后果是"得到一处演示形态"**——这正是门禁 3 要检查的：
  复核时看部署配置里该项是否为 `true`，以及启动日志有没有打印跳过校验的说明
  （判据见 [production-gate.md](../ops/production-gate.md) §2）。
- 校验清单是门禁 3 的判据组成部分：新增或放宽任一校验项，须同批更新门禁表并留复核记录。
- 已知边界（如实登记）：校验只看进程能读到的环境变量/属性，无法识别"值合规但来自公开仓库示例"
  这类来源问题；也无法覆盖部署机本身的网络策略（安全组/防火墙）——后者归运维配置，不在进程判据内。
- 与本决策同批落地但**不属于部署形态**的修复：请求体上限（`RequestSizeLimitFilter`）、
  安全响应头与面板输出转义（`SecurityHeadersFilter` + 页面 `esc()`）、去掉 H2 `AUTO_SERVER`、
  优雅停机。它们由各自代码/配置注释记录理由，本 ADR 只声明"形态"这一层。

相关：ADR-0005（账号体系与凭据边界）、ADR-0022（零依赖试跑：memory 存储不承诺检索质量，
故 strict 下禁用）、[production-gate.md](../ops/production-gate.md)（门禁 3）、
[2026-10-22 运维审查](../qa/2026-10-22-ops-readiness-review.md) §2 P0-3/P0-4。
