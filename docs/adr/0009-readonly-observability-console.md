# 只读可观测面：同源静态渲染 + 轮询游标 + 进程内有界事件环

状态：accepted（2026-09-12，产品重定位"个人 AIOps 排障基础设施"的可视化落点）

上下文：2026-09-11 撤除 LobeChat 聊天壳后，"运维系统可视化"的诉求落空——系统真相（metrics 计数、
降级状态、审计事件、能力边界）全在 JSON 里。选项盘点：Grafana/Prometheus 栈（重：新中间件+新端口+
违反单机 compose 预算）、自建 SSE 长连接推送（EventSource 不能带 Authorization 头，且现有 SseEmitter
基建无 emitter registry，需自建心跳/泄漏清理）、后端二次缓存轮询放大面。个人工具的真实规模是
1-3 个本地浏览器标签页。

决策：
1. **同源静态单页**：HTML/JS 放 `src/main/resources/static/`，Boot 默认资源处理器 serve（零配置零构建零
   CDN），访问 `/` 即得。HTML 壳匿名可开且零信息；数据端点全部继承 `/api/v1/admin` 的 JWT 守卫 +
   `requirePlatformAdmin` 门禁——与 metrics/health 同一信任域，不新增授权面。
2. **纯轮询 + 全局 seq 游标**：`/admin/state`（聚合快照）2s 轮询 + `/admin/audit/recent?since=<seq>`
   增量拉取。否决 SSE 推送：面板量级下推送的复杂度（registry/心跳/断连清理）不换任何真实收益；
   否决后端二次缓存：`/state` 除 health 外全是 atomic getter（已由 HealthProbe 5s TTL 覆盖探测放大）。
   前端 `visibilityState` 隐藏标签页自动降频 30s。
3. **进程内有界事件环**：`AuditService.writeEvent`（全部审计事件唯一必经出口）单点挂 200 条 ring buffer，
   内存 ~40KB 有界。**有界即有轮转，轮转必有丢失**——`recentSince` 返回 `truncated` 显式告知
   （游标早于最老驻留=轮转丢失；游标越过 maxSeq=服务重启），前端插 ⚠️ 行并跳 maxSeq，禁静默空洞。

安全与纪律（继承既有红线）：
- 面板纯读侧：零新写路径、零模拟动画（前端速率=相邻两次真值求差）；成功读路径不落审计
  （实测 60s 轮询 audit.jsonl 增量 0），面板不污染它所展示的证据流。
- `src_tenant ≠ tenant` 的事件在面板亮红——P0-1 绊线的可视化哨兵，理论上恒零、非零即回归失败。
- 键名契约双向钉死：`scripts/check_panel_contract.sh`（CI 独立 job，零服务）比对 HTML fetch 路径↔
  Controller 映射、OpsMetrics 键↔面板 tiles、`/state` 键集；W11 改名演练实测点名致红。
- token 只存浏览器 localStorage（不入库不入仓），OPS §9 记载取法与清理。

后果：可观测面与演示链同体——幕④"指着收敛墙讲 500→1"、幕⑥降级灯、收尾能力边界块；但它是
**日常工具兼演示证据**（禁假数据），多实例/多人使用时升级线：metrics 换 Micrometer+Prometheus 导出、
事件环换消息总线（触发线并入 OPS 债务闹钟表口径）。

相关：ADR-0003（单实例与 SF 视图）、ADR-0007（/v1 协议面与 LobeChat 撤壳）、ADR-0008（权限三元组——
面板数据端点全平台门禁即其应用）。

## 状态注（2026-10-27 产品化二期：成果视图）

面板的**数据契约不变**（仍只读 `/admin/state` 与 `/admin/audit/recent`，零新端点），但信息结构从
「计数器墙」改为「成果视图」：KPI 改为完成排障数 / 命中可核对依据 / 可执行只读动作 / 结论耗时 P50，
并新增逐条「排障成果流」（时间、提问、状态、依据条数、动作条数、耗时）。

为此审计行新增两个**只存条数**的字段 `refs` / `actions`（chat 成功与回放路径、以及 /search 的引用数；
不含正文/命令），旧审计行缺这两个键时面板按 0 处理——「没测」与「测得 0」的区分沿用既有口径。
仍然禁止「节省时间」这类推断型收益数字：面板只展示能从事实派生的量。
