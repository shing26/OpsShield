# OpsPilot 演示手册（DEMO）

> 六幕主线（服务、中间件、live 后端就绪后约 5 分钟）+ 幕⑦标准协议面（+40 秒）+ 幕⑧自举闭环（+3 分钟，独立一条链路）。也可作为面试现场的可复现操作单。
> 前置自检：`bash scripts/demo.sh`（见文末）。**管理员日常操作（开号/离职/配额/告警）看 [OPS.md](../OPS.md)，不是本文。**

## 0. 启动

**推荐（一行，自带端口占用预检 + 就绪判据 + 自动 export OPSPILOT_BASE）**：

```bash
bash scripts/start_gateway.sh          # 就绪判据 = 日志出现 Started OpsPilotApplication
```

**手工起（Git Bash）**：

```bash
cd "/d/OpsPilot — AIOps" && set -a && . ./.env && set +a && /e/java/jdk21/bin/java -jar target/opspilot-gateway-1.0.0.jar
```

**CMD**（无 `set -a`，手动注入 .env）：

```bat
cd /d "D:\OpsPilot — AIOps"
for /f "tokens=1,* delims==" %i in ('findstr /b "DASHSCOPE_API_KEY= JWT_SECRET=" .env') do set "%i=%j"
E:\java\jdk21\bin\java -jar target\opspilot-gateway-1.0.0.jar
```

看到 `Started OpsPilotApplication` 即就绪（`DASHSCOPE_API_KEY` 已配 → 自动 DashScope live；留空自动 mock 词法后端，机制不变）。

所有演示命令在**另一个终端**执行：

```bash
cd "D:/OpsPilot — AIOps/offline" && set -a && . ../.env && set +a
PY=.venv/Scripts/python.exe
# P2 账号体系：合法 token 实时 login（口令只来自 .env 的 DEMO_PASSWORD），红队畸形 token 读文件
L3=$($PY -c "import sys;sys.path.insert(0,'.');import localapi;print(localapi.login('sre-full'))")
L0=$(grep -m1 '^sre_l0=' ../scripts/redteam_tokens.txt | cut -d= -f2)
```

> 前置：新环境需先 `bash scripts/seed_demo_users.sh`（建 sre-limited/sre-full/sre-acme）
> 并 `python scripts/gen_tokens.py > scripts/redteam_tokens.txt`（红队畸形样本）。
> 两者都不入库、可再生；仓库内没有任何可用凭据。

**开演前复位**（保证幕①是冷启动、TTFT 呈现真实秒级链路；否则上次演示的缓存会让幕①直接毫秒级、讲不出冷路径）：

```bash
curl -s -X POST http://localhost:8081/api/v1/admin/cache/flush \
  -H "Authorization: Bearer $L3" -H "Content-Type: application/json" -d '{}'
```

**底幕：统一工作台**——浏览器开 `http://localhost:8081/`，右上角**账号胶囊**填 `sre-full` + 口令（`.env` 的 `DEMO_PASSWORD`）接入。默认落在**排障台视图**（ADR-0020/ADR-0021）；点顶栏「运行面板」切到只读可观测面（ADR-0009）。
顶栏 live/mock 徽标与三依赖灯常亮做背景；幕④⑥的关键数字（在途组计数/熔断倒计时/收敛墙）从这里指给观众看，比终端滚日志抓眼。
（`bash scripts/console_token.sh` 那条取 token 路径现在服务 curl / 探针 / 自动化，页面侧不再粘贴 token——见 OPS.md §9。）

> **零依赖底幕（可选，电脑上什么都没装时）**：`STORAGE_MODE=memory DASHSCOPE_MODE=live bash scripts/start_gateway.sh`——ES/Qdrant/Redis 三者全换内存实现，界面与协议一字未动，但**不承诺任何检索质量数字**（向量是词法哈希）。想完全离线把 `DASHSCOPE_MODE` 换成 `mock`，代价是域外提问不再被拒答。取舍与三个实测坑见 [OPS.md §14](../OPS.md)、[ADR-0022](adr/0022-standalone-storage-mode.md)。注意 `demo.sh` 第 1 检要求三个中间件容器在跑，是 **live 形态预检**——memory 模式跑它会红，属预期。

## 1. 六幕脚本

### 幕① 主流式链路（讲点：SSE 三事件 + 混合检索 + 溯源）

```bash
$PY console_client.py
```

预期：打字机流式输出排障答案；`[meta]` 含 `fingerprint/cache_hit/degradation_level/fast_path`；`[TTFT]` 秒级（live 冷路径真实延迟：embedding+rerank+LLM）；`[refs]` 列出面包屑溯源。

### 幕② 缓存秒回（讲点：L1 掺 tenant+authLevel 的 Key 设计；热点 P99≈16ms 现场版）

同一条命令**立刻再跑一次**：

```bash
$PY console_client.py
```

预期：`cache_hit: "L1"`，TTFT 从秒级掉到 ~20ms。⚠️ 若被问"缓存是不是作弊"——口径：缓存 Key 含权限维度，回放内容与首次一致且永不跨密级（幕⑤反证）；延迟数字底稿见 `offline/load/reports/l1_hit_latency.md`（n=200 回放，服务端 TTFT p99=16ms）。

### 幕③ 语义改写命中 L2（讲点：神经向量余弦>0.95 语义缓存）

```bash
$PY console_client.py "下单接口报 50012_DB_TIMEOUT 怎么排查啊"
```

预期：`cache_hit: "L2"`——换了个说法，精确缓存 miss，但语义缓存命中（live 下这是 text-embedding-v3 的真余弦）。

### 幕④ 风暴收敛——核心幕（讲点：指纹归一化 + 进程内 Single-Flight，ADR-0003）

`llm_calls` 是累计计数，必须取前后差值（直接读绝对值会误判）：

```bash
BEFORE=$(curl -s http://localhost:8081/api/v1/admin/metrics -H "Authorization: Bearer $L3" \
  | grep -o '"llm_calls":[0-9]*' | cut -d: -f2)
.venv/Scripts/python.exe console_client.py --storm
AFTER=$(curl -s http://localhost:8081/api/v1/admin/metrics -H "Authorization: Bearer $L3" \
  | grep -o '"llm_calls":[0-9]*' | cut -d: -f2)
echo "500 并发 → LLM 增量 = $((AFTER-BEFORE))"        # 期望输出：增量 = 1
```

预期：500 并发同指纹打完，`llm_calls` 增量 =1（演示全程 5000+ 请求累计仅 ~17 次 LLM 调用）。可指 `dedup_aggregated` 同步 +500。
**面板动作**：风暴开打的瞬间指屏幕——"数据流·PIPELINE"区的 `Single-Flight 在途组` 从 0 跳到 1、计数墙 `收敛` 瓦片每秒 +n 地爬、`请求` 涨 500 而 `LLM` 只 +1；"指着跳动说收敛"比"回头读差值"强一档。
主动亮取舍：Single-Flight 是进程内 `ConcurrentHashMap`（ADR-0003 明写）——多实例部署需换 Redis 分布式实现，当前口径是单实例。

### 幕⑤ 权限硬隔离 + 提权拒止（讲点：引擎层过滤，Prompt 越狱不可跨越；P0 修复的边界矩阵）

```bash
$PY console_client.py "50042_PAY_SIGN_INVALID 密钥配置" --token sre_l1   # 结果全部 auth_level<=1
$PY console_client.py "50042_PAY_SIGN_INVALID 密钥配置" --token sre_l3   # L3 可见密级文档
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8081/api/v1/copilot/search \
  -H "Authorization: Bearer $L0" -H "Content-Type: application/json" \
  -d '{"query":"任意","mode":"hybrid"}'                                  # 红队 level-0 → 401
```

讲点：密级恒取 JWT claim，无参数可覆盖（历史上 `authLevelOverride` 提权面已被红队发现并移除）；`auth_level<=0` token 在 filter 层 401、即便绕过也在检索层失败关闭（双保险，`/search` 与 `/chat/stream` 共 4 路验收锁）。

### 幕⑥ 降级与拒答（讲点：三级自适应降级 + 置信度空态门控）

**面板动作**：degrade 打到 L2 时，屏幕"降级状态机"区的 L2 灯当场亮起 + `MANUAL LOCK` 徽标（手动锁 vs 自动熔断的可辨证据）；恢复 auto 后灯回 L0。拒答发生时事件流区滚动红色 `refused` 行——三块同屏互相印证。

**必须先 flush**：链路是 L1→L2→降级判定，若目标 query 已被前面幕次缓存，会走回放而非 SOP 直出，演示失真。另注意现状行为：**SOP 直出的答案会写回 L1**，恢复 auto 后同 query 仍返回缓存 SOP——所以幕⑥结尾必须再 flush 复位：

```bash
curl -s -X POST http://localhost:8081/api/v1/admin/cache/flush -H "Authorization: Bearer $L3" \
  -H "Content-Type: application/json" -d '{}'
curl -s -X POST http://localhost:8081/api/v1/admin/degrade -H "Authorization: Bearer $L3" \
  -H "Content-Type: application/json" -d '{"level":"L2"}'
$PY console_client.py "支付回调积压怎么止损"     # meta: degradation_level=L2，静态 SOP 直出，零 LLM
curl -s -X POST http://localhost:8081/api/v1/admin/degrade -H "Authorization: Bearer $L3" \
  -H "Content-Type: application/json" -d '{"level":"auto"}'   # 恢复 auto
$PY console_client.py "asdfgh junk 乱码"          # 置信度门控：显式拒答，跳过 LLM
# 收场复位（清掉 SOP/拒答缓存残留，下次开演从干净状态开始）
curl -s -X POST http://localhost:8081/api/v1/admin/cache/flush -H "Authorization: Bearer $L3" \
  -H "Content-Type: application/json" -d '{}'
```

## 1.5 怎么问才对（讲解或自用都先读这段）

- **贴错误码，别描述感觉**：`下单接口报 50012_DB_TIMEOUT 怎么排查` 远好于"订单一直转圈"——口语极值可能被置信度门控误拒（阈值标定在案，ADR-0012）。
- **每条提问自包含**：本系统单轮，不记得上一句。追问"刚才那个怎么办"会得到澄清话术（请把关键信息并入本条），不会被硬检索。
- **一次排查用同一个 `X-Trace-Id` 头**：多次调用事后可整段取出（审计按 trace 串行）。
- **答案里出现的指标名/配置键先对 [参考N] 原文再执行**：错误码引用有集合判据背书；具体配置键/指标名若语料中不存在会被探针点名（warn），人工复核后再动手。

## 2. 故障排查

| 症状 | 处置 |
| --- | --- |
| 端口 8081 被占 / 启动即退 | `powershell "Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force"` 重试；`8080` 被本机 nexus-web 占用是既定事实 |
| `JWT_SECRET 环境变量未设置` | .env 未 source，或行内有前导空格/值带引号 |
| 检索结果异常少 / mode 一直 es_only | 中间件没起来：`docker compose up -d`；Qdrant 集合空（曾 `down -v`）→ 启动参数加 `--opspilot.ingest=true` 重灌（448 chunks 实测 24.4s，live） |
| rerank 403 | 旧 `gte-rerank` 已停授权；确认 `${RERANK_MODEL:gte-rerank-v2}`（ADR-0002 有修订注记） |
| `curl -d` 发中文报 400 请求体解析失败 | Git Bash/CMD 的 curl 按本地代码页发中文（GBK 出局）→ 带中文的请求一律走 python（`offline/localapi.py` 的 `post_json`/`stream_chat`） |

## 3. 成本口径

演示全程（含六幕）约 **15-20 次 LLM 调用 + 40 次 embedding**，live 下人民币几分到几角。风暴幕 500 并发只打 1 次 LLM——这本身就是产品卖点。幕⑧追加 **1-2 次**（同故障重复触发走 L1 缓存，不再调用 LLM，这一点当场可见）。

## 4. 完整回归（面试前 3 分钟自测）

```bash
.venv/Scripts/python.exe acceptance_a2.py    # 期望 A2: 10/10 PASS（~2min，含 500 并发风暴）
.venv/Scripts/python.exe acceptance_a3.py    # 期望 A3: 8/8 PASS（~5min，含评测集检索）
```

## 5. 3 分钟一镜到底录屏讲解稿

> 原则：不剪辑、不回头；每幕敲命令前先说这句口播。开演前完成 §0 复位。
>
> **只录面板那一路时**：录屏产物落 `docs/demo/*.webm`，**不入库**（可再生——它录的是本机活体栈，
> 而二进制进公开仓只增体积不增证据力；证据在 `offline/load/reports/` 与 `logs/audit.jsonl` 里）。
> 面板侧的三个数最值得指：顶栏 build 指纹、「数据流」区的**累计计数墙**（风暴后
> `total_requests=301 / llm_calls=2 / dedup_aggregated=299` —— 300 个并发只换 1 次 LLM 就是幕④的落点）、
> 以及「降级状态机」的 L0/L1/L2 灯（幕⑥现场把灯打到 L2 看 MANUAL LOCK）。

| 时点 | 口播（照读即可） |
|---|---|
| 0:00 开场 | "这是 OpsPilot——一个把运维告警接进 LLM 的排障网关。它要解决两件事：告警风暴把 LLM 打爆，以及向量检索把 5 位错误码当噪音。全部指标 DashScope live 实测。" |
| 幕① | "一条口语化的 DB 超时问题。SSE 流式，meta 携带指纹和降级态；注意 TTFT 是秒级——embedding、rerank、LLM 三段真实外网调用，口径是双源校验过的。" |
| 幕② | "同样的问题再来一次——TTFT 从 6 秒掉到 20 毫秒，L1 精确缓存。缓存 key 掺了租户和密级，回放内容不可能跨权限。" |
| 幕③ | "多加了一个'啊'字，精确缓存 miss，但 1024 维神经向量的余弦过 0.95——L2 语义缓存命中，答案直接回放，零检索零 LLM。" |
| 幕④ | "核心幕。500 个并发的同指纹告警打进去——指屏幕：Single-Flight 在途组跳到 1、这面墙叫'收敛'在往上爬，500 个请求只换 1 次 LLM。滑动窗口在 Redis 计数，Single-Flight 在进程内收敛，指纹归一化把唯一 ID、时间戳全部掩掉。" |
| 幕⑤ | "同一个敏感问题，L1 工号查不到支付密钥配置，L3 查得到——这不是 Prompt 约束，是 ES 和 Qdrant 查询里注入的硬过滤。再用畸形租户的签名 token 打接口：401。密级越权和租户越权在入口与引擎层各封死一次。" |
| 幕⑥ | "熔断态手动打到 L2——屏幕降级区 L2 灯亮、MANUAL LOCK 挂上：LLM 挂了也能直出静态止损清单，零外网调用；恢复后问一个乱码——置信度门控显式拒答，事件流滚红，宁可说不知道，不烧 token 编答案。" |
| 收尾 | "最后给这块面板 15 秒：顶栏是 build 指纹和三个依赖灯，中间是真计数器不是演示动画——它只读，面板轮询连审计日志都不污染（W6 实测）。右下角能力边界列了我不做什么、每条带最后验证日期。仓库 README 有完整证据链：A2 十项全绿、蓝绿在线切流、债务带触发线。谢谢。" |

### 幕⑦（+40 秒，标准协议面，主线仍是上面六幕）

终端直播 curl（原"UI 降级预案"转正为主形态）：

```bash
set -a && . ./.env && set +a
TOKEN=$(offline/.venv/Scripts/python.exe -c "import sys;sys.path.insert(0,'offline');import localapi;print(localapi.login('sre-full'))")
curl -N http://localhost:8081/v1/chat/completions -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"model":"opspilot","stream":true,"messages":[{"role":"user","content":"how to fix 50012_DB_TIMEOUT"}]}'
```

口播："后端暴露标准 OpenAI 兼容面——任何标准客户端（LobeChat/Dify/SDK）可直连，这里用 curl 直播逐帧。这个'API Key'其实是账号体系签的 JWT：刚才幕⑤那个 disable，在这层同样秒生效，因为两个协议面共享同一条编排链路（缓存/降级/审计/租户过滤一个不少）。"

（历史：曾接入 LobeChat 壳验证兼容性并实测全链路穿透，2026-09-11 撤除——聊天壳只呈现"会答对的对话框"，
本协议面的价值是兼容性证明本身，UI 不承载系统实质；见 ADR-0007 状态注与 OPS §7。）

### 幕⑧（+3 分钟，独立链路）自举闭环——系统自己发现故障

> **这一幕和前七幕的区别**：前面所有幕的 query 都是人敲的、故障都是参数模拟的。这一幕里
> **故障是真的（真停一个依赖）、query 是系统自己写的、知识来自它自己的事故复盘**——
> 语料/评测集/压测流量全由本项目自产，"被真实输入检验过"这件事只有在这一幕成立（ADR-0011）。

一条命令跑完全程（脚本会真的停掉再起回 qdrant，请确认在本机开发环境执行）：

```bash
bash scripts/demo_self_alert.sh          # 完整链路，约 3 分钟
bash scripts/demo_self_alert.sh --quick  # 只演到"告警 + 收敛"，跳过恢复等待
```

八个镜头与预期（脚本按序打印，照此口播）：

| 镜头 | 动作 | 预期 |
| --- | --- | --- |
| ① | 健康态探测 | `candidates=0`——没故障就不发告警（不造假告警） |
| ② | `docker compose stop qdrant` | 真实依赖不可用 |
| ③ | 等健康面反映（15s） | `health=DEGRADED`（探活 5s TTL + 3s 探测超时） |
| ④ | 单轮探测 | 告警发出：`rule=dep_down service=qdrant http=200 sent_ok=True`；台账带 `fp` 与命中文档；审计出现 `source=alert` + `sub=sre-watcher` |
| ⑤ | 连发同故障告警 | `llm_calls` 增量 **0**（同指纹走 L1），台账 `cache_hit=L1`、指纹逐位一致 |
| ⑥ | 直查告警里的错误码 | `51208_DEPENDENCY_DOWN` → Top-1 = **rb-208**（系统自己写的那篇处置单，含「止损操作」） |
| ⑦ | 起回 qdrant + 等 80s | 网关侧健康面自愈（gRPC 通道重建约 1 分钟；外部 REST 早已健康，**这条观测滞后已写进 rb-208**） |
| ⑧ | 再探测 | `candidates=0`——**恢复即停报** |

口播（约 3 分钟）：

> "前面几幕的故障是我按参数模拟的、query 是我敲的。这一幕不一样：我现在**真的把向量库停掉**——
> 注意没有任何人告诉系统'出故障了'，是它自己轮询运行态发现的：依赖 DOWN、健康面转 DEGRADED。
> 它给自己发了一条告警，`source=alert`，主体是独立的告警账号——这条审计行就是证据：来源可辨、主体可辨。
> 再看收敛：同样的告警再发两次，指纹逐位相同、缓存直接命中，**LLM 调用增量是 0**——告警风暴打不爆它。
> 最有意思的是它检索到了什么：告警里带的错误码，查出来的是**它自己那篇处置单**——这套系统的知识库里
> 躺着它自己踩过的坑，包括'依赖停了之后网关侧健康面要一分钟才反应过来'，那也是实测写进去的。
> 最后我把向量库起回来，它自己停了告警。整条链路：自检 → 自报 → 收敛 → 命中自己的复盘 → 恢复停报，
> 零人工构造 query。"

**被问到就答**：生产者 `offline/alert_producer.py` 只读既有观测面（`/admin/state` 的 health/degradation/metrics/quota）、
只调既有入口，不新增后端接口；五条规则（依赖 DOWN / 熔断降级 / 上游限流 / 检索路劣化 / 拒答激增）；
护栏含单实例锁、每规则冷却 300s、每小时上限 20 条、本主体配额保留线、登录失败退避、出口环回白名单。
**已知盲区**：网关自身挂掉时生产者无法自证，只能记 `unreachable`——单进程自举的固有边界，不假装覆盖。
实测台账：[docs/qa/2026-09-16-self-alert-loop.md](qa/2026-09-16-self-alert-loop.md)。
