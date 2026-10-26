# OpsShield 控制台缺陷审查与修复台账（2026-10-27）

> 审查人：以“值班 SRE 打开这个工作台会遇到什么”为视角做的一次功能/展示缺陷审查。
> 方法：只读仓库 + 本机活体取证（memory/mock 实例，127.0.0.1:18081，另起 H2 文件库，未触碰生产数据）+ headless Chromium（Playwright）真实点击与截图。
> 结论口径：**24 条缺陷已全部关闭**，其中 P0 1 条、P1 5 条、P2 12 条、P3 6 条；修复以 9 个 PR 合入 `main`，每个 PR 都通过 CI 的完整 10 job。
> 台账口径：本文件是这次审查与修复的**单一事实源**；issue/PR 是过程记录，这里保留“判据、证据、残余风险”。

---

## 0. 结尾快照（先看数字，再看过程）

| 项 | 审查起点 | 结束状态 |
|---|---|---|
| 基线 commit | `66f34f3` | `286d73d` |
| Java 测试 | 421 | **424 全绿** |
| Python 测试 | 419 | **433 全绿**（含 14 条展示层静态契约、17 条 doc_numbers 用例） |
| 文档数字门闩 | 30 条 / 44 处（README 写 23/37，已漂移） | **33 条 / 48 处**，且现在会核自己 |
| 面板契约门闩 | 6 层 | **9 层** |
| open issue | 24 | **0** |
| 合并 PR | — | **9 个**（#38 #40 #41 #42 #43 #44 #45 #46 #47） |

---

## 1. 判据：什么算“缺陷”

三条口径，缺一不记为缺陷：

1. **值班时会误导人**：页面显示的数字/身份/耗时与事实不符（例：抽屉增量恒为 0、对照表把 B 未查询写成 0 引用、clarify 卡显示“检索 0ms”）；
2. **行为与承诺不一致**：文案说“已停止轮询”、实际 interval 仍是 30s（#35）；说“点击重试”、实际 500（#32）；
3. **同一事实有两套口径**：三处 query 上限不一致（#18）、面板契约只查 admin 不查 copilot（#33）、文档数字无人核（#39）。

不记为缺陷的（如实登记为边界）：mock rerank 无法判“域外拒答”（`docs/benchmark.md:33` 已写明）、live ES/Qdrant 本轮未做压测。

---

## 2. 缺陷清单（24 条，全部已关闭）

### 2.1 启动与鉴权（P0/P1）

| # | 级别 | 现象 | 证据 | 修法 | PR |
|---|---|---|---|---|---|
| [#15](https://github.com/shing26/OpsShield/issues/15) | **P0** | `storage.mode=live` / `strict` 起不来：`ActionOpsService` 硬依赖 `InMemoryIndex` | `ActionOpsService.java` 构造器字段 | 改 `ObjectProvider<InMemoryIndex>`；缺索引时 `propose` 明确拒绝 `l1_requires_memory_storage`，而不是启动失败 | [#38](https://github.com/shing26/OpsShield/pull/38) |
| [#17](https://github.com/shing26/OpsShield/issues/17) | P1 | L1 面板不带 `auth_level` 过滤：低密级用户可读到高密级 op 的命令原文 | `ActionOpsService.list()` / `status()` | `list` SQL 加 `auth_level <= ?`；`status`/`cancel` 同判据；`op_level_insufficient` → 403 | [#40](https://github.com/shing26/OpsShield/pull/40) |
| [#18](https://github.com/shing26/OpsShield/issues/18) | P1 | `/v1` 面绕过字段契约：超长 query 不被 400，反而进入准入/配额 | `OpenAiController` | 注入 `Validator`，准入之前先校验；`MAX_QUERY_CHARS=8192` 三面统一 | [#41](https://github.com/shing26/OpsShield/pull/41) |
| [#31](https://github.com/shing26/OpsShield/issues/31) | P1 | 429 仍扣当日配额（准入顺序颠倒） | `CopilotController` | 先 `tryAcquire` 再 `precheck`；precheck 异常释放 permit | [#41](https://github.com/shing26/OpsShield/pull/41) |

### 2.2 控制台状态机与证据面（P2）

| # | 现象 | 证据 | 修法 | PR |
|---|---|---|---|---|
| [#27](https://github.com/shing26/OpsShield/issues/27) | 抽屉数字慢一拍、增量恒 0 | `renderDrawer` 把上一轮当当前值相减 | 显式传（当前 metrics, 上一轮 poll metrics）；poll 并发合并 | [#42](https://github.com/shing26/OpsShield/pull/42) |
| [#16](https://github.com/shing26/OpsShield/issues/16) | 换身份后旧答案卡挂新用户名（归因反转） | `card()` 抬头用输入框 user | 引入 `tokenUser`（登录时记真实主体）；身份不符先 `dropToken` 并提示重接 | [#42](https://github.com/shing26/OpsShield/pull/42) |
| [#19](https://github.com/shing26/OpsShield/issues/19) | 连点发起 → 多路 SSE 同写一张卡 | `ask()` 无重入守卫 | `S.asking` 覆盖 login await 窗口 + 单次捕获 query | [#42](https://github.com/shing26/OpsShield/pull/42) |
| [#37](https://github.com/shing26/OpsShield/issues/37) | SSE 无超时/无取消/截断静默 | `stream()` 裸 `for(;;)` | AbortController + 15s 首字节 / 150s 总时长；未收 `done` 判“流被截断”；生成中可停止 | [#42](https://github.com/shing26/OpsShield/pull/42) |
| （内部 #3） | 换身份后旧答案卡不清理 | `login`/`dropToken` 不清 `s.r` | 两处都清 `s.r` | [#42](https://github.com/shing26/OpsShield/pull/42) |

### 2.3 页面展示（P2，三批）

| # | 现象 | 根因 | 修法 | PR |
|---|---|---|---|---|
| [#20](https://github.com/shing26/OpsShield/issues/20) | 请求轨迹的“分段耗时条”完全不可见 | `.bar i` 只有宽高、没有背景色 | 补 `background:var(--accent)`；实测 5 段均 `rgb(88,166,255)` | [#43](https://github.com/shing26/OpsShield/pull/43) |
| [#21](https://github.com/shing26/OpsShield/issues/21) | L1 待确认卡样式塌掉 + 白底输入框 | `--bd/--fg2/--bg2` 三个变量从未定义 | 改用既有变量；`.l1in` 暗色；补 `.msg`；`color-scheme:dark` | [#43](https://github.com/shing26/OpsShield/pull/43) |
| [#25](https://github.com/shing26/OpsShield/issues/25) | 零采样报“峰值 1”；对照表 B 未查询写 B=0 | 柱高兜底值当对外数字；缺行未判空 | 峰值按 `hasData` 分支；缺一侧只给说明不给表 | [#43](https://github.com/shing26/OpsShield/pull/43) |
| [#28](https://github.com/shing26/OpsShield/issues/28) | 滚动后弹层跑到视口外，点账号像“没反应” | `.pop` 是 `absolute`，顶栏是 sticky | 改 `position:fixed`；窄屏 max-height 内部滚动 | [#44](https://github.com/shing26/OpsShield/pull/44) |
| [#22](https://github.com/shing26/OpsShield/issues/22) | 401 遮罩两条路径不一致；`.sat` 把 fixed 变成相对整页 | body 上的 `filter` 为 fixed 后代创建包含块 | 统一 `showAuth()`；滤镜只压 `.topbar/.view/footer` | [#44](https://github.com/shing26/OpsShield/pull/44) |
| [#23](https://github.com/shing26/OpsShield/issues/23) | 滚动后左栏顶部被顶栏遮住 | sticky 左栏在等高等容器里**粘不住**；`top:53px` 与换行后 92px 不符 | 三段式 flex 壳（顶栏/视图/页脚），视图内部滚动；顶栏高度 JS 实测回写 `--topbar-h` | [#44](https://github.com/shing26/OpsShield/pull/44) |
| [#24](https://github.com/shing26/OpsShield/issues/24) | 移动端 KPI 一列五屏、顶栏三行 141px、时间带纳秒 | `minmax(190px)` 自动单列；指纹串太长；ISO 原串直出 | ≤600px KPI 两列；顶栏 141→81px；`fmtTs()` 截纳秒 | [#44](https://github.com/shing26/OpsShield/pull/44) |
| [#26](https://github.com/shing26/OpsShield/issues/26) | Markdown 退化：有序列表丢编号、表格出竖线、引用/链接原样 | 渲染器只认标题/粗体/无序列表 | 保留编号；补表格/引用块；链接只放行 http(s) | [#45](https://github.com/shing26/OpsShield/pull/45) |
| [#29](https://github.com/shing26/OpsShield/issues/29) | clarify 卡显示“精排/检索 0ms/TTFT 0ms” | 指标胶囊未按 gate 过滤；空命令文案套用引用集话术 | 按 `gate` 过滤；clarify 明说“未做检索” | [#45](https://github.com/shing26/OpsShield/pull/45) |
| [#30](https://github.com/shing26/OpsShield/issues/30) | 浮层无 Esc/焦点管理、答案无 aria-live、label 未关联 | 纯鼠标实现 | Esc 依次关+焦点归还；Tab 陷阱；`role=dialog`；`aria-live`；tabs 方向键；label `for/id` | [#45](https://github.com/shing26/OpsShield/pull/45) |

### 2.4 门闩、文档与卫生（P3）

| # | 现象 | 证据 | 修法 | PR |
|---|---|---|---|---|
| [#32](https://github.com/shing26/OpsShield/issues/32) | 未知 opId 返回 500；审计缺 `action_dispatched`/`action_expired`；命令被截 200 字 | `queryForMap` 空结果抛异常；审计只有 proposed/confirmed | 404 `stage=not_found`；按**真实状态**写 dispatched/not_dispatched；补 expired；`action_*` 的 `q` 放宽到 1024 | [#47](https://github.com/shing26/OpsShield/pull/47) |
| [#33](https://github.com/shing26/OpsShield/issues/33) | 面板契约只查 admin，copilot/auth 改名静默失明 | `check_panel_contract.sh` 六层 | 补三层：路径（含类级前缀/裸映射）、SSE 帧名与 done 键、请求体字段 ⊆ `ChatRequest` | [#46](https://github.com/shing26/OpsShield/pull/46) |
| [#34](https://github.com/shing26/OpsShield/issues/34) | MCP `source` 无枚举；`get_gateway_state` 与“最小密级账号”建议冲突 | `mcp_server.py` schema / 文档 | 补 enum `[manual,alert]`；403 明确“需 platform 角色”；文档同步 | [#46](https://github.com/shing26/OpsShield/pull/46) |
| [#35](https://github.com/shing26/OpsShield/issues/35) | 无 hash 路由、非 2xx 丢响应体、降频后回不到 2s、`build.commit` 不展示、事件流无空态、复制失败静默 | `setView`/`stream`/`poll`/`renderEvents` | 逐条修；见 PR 证据表 | [#47](https://github.com/shing26/OpsShield/pull/47) |
| [#36](https://github.com/shing26/OpsShield/issues/36) | 根目录 7 个 `hs_err`、3 个 `replay` 日志与 `data/*.trace/lock.db` 残留 | `ls` 实测 | 删除 + `production_gate.sh` 加卫生检查（再出现即 FAIL） | [#46](https://github.com/shing26/OpsShield/pull/46) |
| [#39](https://github.com/shing26/OpsShield/issues/39) | `doc_numbers` 自己的规模读数无人核（README 23/37 vs 实际 30/44） | `doc_numbers.json` | 新增 `registry_entries`/`registry_citations` 自指派生 + Python 用例数；README 改 33/48；变异 E | [#46](https://github.com/shing26/OpsShield/pull/46) |

---

## 3. 修复过程中暴露的“修复本身的缺陷”（值得单独记）

这五条都不是原始缺陷，而是**修复动作引入或差点引入**的问题；全部被门闩或自写探针在合入前抓住：

1. **public-copy 门闩抓住注释里的 canonical 词**（PR #42）：我在 `index.html` 注释里写“单飞”，对外面必须写“并发合并”——门闩红，改词后绿。说明这道门闩连代码注释都管。
2. **`esc()` 之后再匹配 `>` 必然失败**（PR #45）：Markdown 引用块正则写成 `^\s*>`，而 `md()` 先 `esc()` 把 `>` 转成 `&gt;`——真浏览器探针把它抓出来，改成匹配 `&gt;`。
3. **审计说谎**（PR #47）：confirm 后无条件写 `action_dispatched`，而派发可能因开关/key/webhook 缺失停在 confirmed。单测补断言：**审计声称已派发 ⇔ 真实状态是 dispatched**。
4. **`sed` 没吃掉尾部括号**（PR #46）：`s/.*"([^"]+)"/\1/` 把 `@RequestMapping("/api/v1/copilot")` 提取成 `/api/v1/copilot)`，map 全错、CI 连红两次，靠临时 debug echo 定位；改成 `s/.*"([^"]+)".*/\1/`。
5. **静态契约锁写得太“实现化”**（PR #44）：先断言“左栏 sticky 读变量”，改成 flex 壳后断言失效——改成断言“桌面壳定高两列”，避免把实现细节当契约。

---

## 4. 新增的回归锁（防同一类缺陷回来）

| 锁 | 位置 | 锁住什么 |
|---|---|---|
| 展示层静态契约 14 条 | `offline/tests/test_console_contract.py` | `.bar i` 必须有背景色；页面不得引用未定义 CSS 变量；峰值必须按 `hasData`；对照表必须双侧；弹层必须 fixed；body 不得挂 filter；顶栏高度必须变量化；窄屏 KPI 两列；时间必须格式化；Markdown 表格/有序列表/外链策略；clarify 指标过滤；Esc+焦点陷阱+dialog；答案 live region；label 关联 |
| 面板契约九层 | `scripts/check_panel_contract.sh` | admin/copilot/auth 路径映射、指标双向、state 键集、actuator 暴露面、`ev.sub` 转义、SSE 帧名/done 键、请求体字段 ⊆ DTO |
| 文档数字自指 + 变异 E | `offline/doc_numbers.py` / `test_doc_numbers.py` | 门闩自身规模由登记表现算；改 README 规模读数必红 |
| 仓库卫生检查 | `scripts/production_gate.sh` | 根目录 `hs_err/replay`、`data/*.trace.db` 再现即 FAIL |
| 端到端探针（不入库） | `.workbuddy/audit/probe-*.cjs` | 真进程 + headless Chromium 的点击/截图证据；每次修复前后对比 |

---

## 5. 验证口径（哪些是实测，哪些是静态推演）

**实测（有命令/截图）**：

- 每个 PR 的 Playwright 断言与截图：抽屉增量、身份归因 mismatch、重入计数、SSE 截断/停止、轨迹条 5 段样式、L1 卡计算样式、空采样文案、单侧对照、弹层滚动定位、桌面壳 92px、401 遮罩居中、390px 两列 KPI、Markdown 样张、clarify 卡片、Esc/焦点陷阱/tabs 方向键、hash 刷新、commit 指纹、400 文案、404 结构、事件空态；
- `mvn test` 424 绿、`pytest` 433 绿、`doc_numbers --check` 33/48 绿、`check_public_copy` 绿；
- CI 每轮 10 个 job 全绿（java / static-gate / panel-contract / provenance / python / shell / security / sbom / smoke / benchmark）。

**静态推演（无活体栈）**：

- live ES / Qdrant / Redis 的真实检索与降级未在本轮压测（memory/mock 覆盖协议面）；
- Spring Boot 4.1.1 升级（dependabot #8）未验证；
- 面板契约九层是静态 grep，不替代端到端；`#33` 的“反向漂移”只覆盖到请求体/帧键，`/state` 嵌套键的全量反向校验仍是缺口。

---

## 6. 残余风险与后续建议

1. **10 个 dependabot PR**（#4–#14）仍未合；其中 #8 是 Spring Boot **4.1.1 major**。建议单开一条“依赖升级评估”issue：分支跑 `mvn test` + `smoke.sh` + 一次 memory 实例启动，再决定；不要自动合。
2. **README 的 Python 用例数口径**：现登记的是 `^def test_` 声明数（389），pytest 参数化展开后执行 433 例——两者不是同一个数，已在正文写明，但后续改测试时仍需同步 README（门闩会红）。
3. **`.workbuddy/` 证据未入库**（gitignored）：截图与探针是本轮证据，若需长期可复现，应把探针脚本迁到 `offline/tests/`（需先评估它对无头浏览器的依赖）。
4. **本轮未覆盖**：ES/Qdrant live 检索质量、并发压测、审计 WORM 异地副本、MCP 端到端（仅静态 schema 校验）。

---

## 7. 复现路径（按需）

```powershell
# 1) 起 memory/mock 实例（H2 文件库另放临时目录）
$env:STORAGE_MODE='memory'; $env:DASHSCOPE_MODE='mock'; $env:SERVER_PORT='18081'
java -jar target/opspilot-gateway-1.0.0.jar

# 2) 静态门闩
cd offline; python doc_numbers.py --check; python -m pytest tests -q
cd ..; python scripts/check_public_copy.py --check

# 3) 端到端（需 playwright + 本机 chromium）
node .workbuddy/audit/probe-b4.cjs
```

---

来源：2026-10-26/27 缺陷审查（基线 `66f34f3`）；issues [#15](https://github.com/shing26/OpsShield/issues/15)–[#39](https://github.com/shing26/OpsShield/issues/39)，PRs [#38](https://github.com/shing26/OpsShield/pull/38)–[#47](https://github.com/shing26/OpsShield/pull/47)。