# 前端重设计交接说明（供 codex / Claude Code 接手）

> 状态：**A 版已落地生产页面**（`src/main/resources/static/index.html`，2928 行），
> B/C 仍为独立可运行的高保真草图，未接入后端。
> 本目录保留三版设计资产与预览图；生产页面的实现差异见第 8 节。

## 1. 交付物清单

```
docs/design-mockups/
├── README.md                          ← 本文件（交接说明）
├── variant-A-incident-console.html     ← A 事件响应工作台（492 行）
├── variant-B-knowledge-desk.html       ← B 企业知识助手（360 行）
├── variant-C-platform-console.html     ← C 平台能力中心（462 行）
└── previews/
    ├── A-overview.png / A-refuse.png   ← A 主视图 / 拒答态
    ├── B-home.png     / B-refuse.png   ← B 首页 / 拒答态
    └── C-overview.png / C-lock.png     ← C 概览 / 权限锁
```

草图特性：
- 单文件、内联 CSS/JS、**零外部依赖**，双击即可在浏览器打开。
- 每页底部有 `.demo-bar` 状态演示条，点按钮切换空/加载/拒答/错误/权限等真实业务状态。
- 已校验：HTML 标签配平、JS 语法 `node --check` 通过、Playwright+Edge 1440×900 无报错无横向溢出。

## 2. 三版定位与取舍

| 版本 | 定位 | 主用户 | 主对象 | 后端改动 | 取舍 |
|---|---|---|---|---|---|
| A | 事件响应工作台 | 值班 SRE | 事故单 | 几乎为零 | 闭环最强、面窄 |
| B | 企业知识助手 | 一线同事 | 一个答案 | 几乎为零 | 产品感强、技术深度露出少 |
| C | 平台能力中心 | 管理员/集成方 | 平台能力 | 需 2 个新前端页，零后端改动 | 放大工程量、离一线远 |

**落地结论：A**（告警→诊断→处置闭环最贴合现有后端），已合入生产页面。
B/C 未实现，设计语言与组件映射仍保留在第 5 节，供后续切片参考。

## 3. 事实依据（数据均取自仓库，非编造）

- 语料统计（`offline/corpus/chunks.jsonl`，459 条）：服务 `opspilot-gateway` 119 / `order-service` 65 / `payment-service` 43；错误码 `50041_PAY_GATEWAY_502`、`50012_DB_TIMEOUT`、`50021_REDIS_TIMEOUT` 等均真实存在。
- 只读命令白名单：`src/main/resources/policy/readonly-command-policy.json`（kubectl get/logs、grep 等只读动词）。
- API 端点/字段来自后端源码：
  - `POST /api/v1/copilot/chat/stream`（SSE：`meta` fingerprint/cache_hit/degradation_level/gate → `delta` token → `done` refs/actions/near_miss/ttft_ms）
  - `GET /api/v1/copilot/coverage`、`POST /api/v1/copilot/feedback`、`POST /api/v1/copilot/search`
  - `/api/v1/copilot/actions/*`（L1 受控动作，默认关闭）、`/api/v1/admin/*`（platform-only）
  - OpenAI 兼容面 `/v1/chat/completions`（需 stream=true）、`/v1/models`
- 权限模型：tenant 硬隔离 + auth_level 1-9 密级 + role（仅 platform 且 level≥3 可进管理面；**密级高≠可信**）。
- 真实性红线（草图已如实体现，落地时不得删改）：LLM 后端默认 `MOCK`（无 DashScope Key）；L1 受控动作默认 `OPSPILOT_L1_ENABLED=false`。

## 4. 生产页面改造锚点（index.html）

草图组件 → 现有函数位置（按函数名定位，行号仅供快速参考）：

| 草图元素 | 现有实现 | 大致行号 |
|---|---|---|
| 顶栏（身份/健康/视图切换） | `renderHeader()` / topbar HTML | 463-478 / 1325 |
| 事故列表（A 新增概念） | `renderRail()`（现为排障输入栏） | 1260 |
| 结论卡 | `verdict()` / `firstLine()` | 970 / 953 |
| 流式答案 | `answer()` / `paintAnswer()` / `stream()` | 933 / 842 / 745 |
| 只读命令 | `contract()` | 897 |
| 参考来源 | `refs()` | 923 |
| 拒答导航 | `nearMissBlock()` | 1057 |
| 覆盖边界 | `async loadCoverage()` / `coverageBlock()` | 1204 / 1220 |
| 反馈上报 | `reportGap()` / feedback 调用 | 1080 / 1086 |
| 运行态抽屉 | drawer HTML + drawer 渲染 | 483-487 / 1477 |
| 运行面板（C 的基础） | `#v-panel` 视图 + `poll()` / `/admin/state` | 499 / 1588 |
| 审计事件流 | `/admin/audit/recent` 渲染 | 1610 |
| 状态徽标 | `badge()` | 861 |
| 权限锁 | `#panel-lock` / `showAuth()` | 500 / 2115 |

**A 的落地关键是引入"事故"这一持久业务对象**：历史查询升级为服务端 `incidents` 表里的事故单，
同租户多端可见、可分配；告警深链带 `alertId/source=alert` 预填，处置结论写回并触发 `feedback(verdict)`。

## 5. 各版落地要点（若改选 B/C）

- **B**：单列搜索式界面；证据链收进 `<details>` 折叠区，默认不露自证指标；历史/收藏走 localStorage；答案底部反馈条对接 `feedback`；页脚须标注 mock 后端。
- **C**：把 `#v-panel` 从附属 tab 提升为主产品；新增"接入管理"（JWT 作为 API Key + curl 示例 + SSE 帧契约）与"治理与策略"（L1 开关/白名单、权限模型说明、审计哈希链校验）两个页面；权限锁沿用 `#panel-lock` 并写明缺失条件。

## 6. 关键约束（沿袭项目 ADR，勿违反）

1. 诚实性红线（ADR-0009）：不补间、不平滑、无插值、**无假数据**；所有指标来自真实字段，口径写清楚。
2. 状态判断用 `meta.gate` / `meta.refused` 等**状态值**，不要从 `retrieval_ms==0` 反推（clarify 是零检索零 LLM 分支）。
3. 权限门禁一字不动、不为页面新开非 admin 端点：运行面板/健康抽屉数据源仍要求 role=platform ∧ auth_level≥3。
4. 单轮无状态设计：不做多轮会话，追问由澄清门拦截（gate=clarify）。
5. 网关只读：只展示从 chunk 逐字抽取的只读命令，绝不代执行；L1 默认关闭时前端显示"未启用"而非可点假按钮。
6. 数字须实测：任何体量/指标引用前先跑仓库核验，不沿用历史台账值。

## 7. 验收清单（开发完成后逐条实测）

- [x] A 的核心闭环可走通：告警深链→建单→诊断→只读命令→结单→`feedback=up`。
- [x] 空/加载/错误/权限四态均有真实文案与界面，不是空白或裸错误码。
- [x] 401/403/429 差异化处理：401 失效引导重登、403 明确缺什么权限、429 显示锁定语义。
- [x] SSE 截断、无 done 帧、15s/150s 看门狗有明确状态，不标"完成"。
- [x] mock 后端与 L1 默认关闭在 UI 上如实标注。
- [x] 响应式：1440/1024/768/375 四档无溢出、无重叠。
- [x] 键盘可达：浮层可 Tab 进入、Esc 退出、焦点归位。

## 8. A 版落地记录

### 保留的真实能力

登录/JWT、SSE `meta/delta/done/error`、15s/150s 看门狗、停止、结论卡、只读命令逐字展示、
诊断 Markdown、参考来源、拒答导航、覆盖范围、反馈上报、双身份对照、运行态抽屉、
运行面板（platform ∧ auth_level≥3）、L1 确认（默认关闭）。

### 新增的业务对象：事故

- 仅在用户新建或发起诊断时创建，服务端 `incidents` 表持久化；**无预置事故、无假告警等级**。
- 字段：`id/title/query/service/errorCode/env/alertId/source/priority/status/owner/createdBy/createdAt/updatedAt/closedAt/closedBy/closeNote/diagnosis/timeline`。
- 告警深链 `?alert=&source=alert&title=&q=&service=&errorCode=&env=` 只预填新建表单，不落库。
- 结单写入处置结论、时间线与 `feedback=up`；拒答/异常/未诊断默认不允许上报 up。
- 负责人只能从同租户启用用户中选；同租户成员可协作写诊断/时间线，结单/重开/分配/改优先级仅创建人、
  当前负责人或平台管理员可做。

### 刻意未搬入生产页

草图底部的 `.demo-bar`、静态事故列表与示例 P1/P2 数据。生产页的空态就是空态。

### 落地时修掉的两个缺陷

1. 运行态抽屉打开后整页塌成单列：`#v-trouble` 同时带 `shell` 与 `drawer` 两个 class，
   `.drawer{display:flex}` 覆盖了 `.shell{display:grid}`。状态类改名为 `with-drawer`。
2. 宽屏打开抽屉后缩到窄屏，遮罩不出现：resize 监听只处理了"变宽隐藏"，补上"变窄按抽屉开合显示"。

### 验证证据（2026-11-20）

- Playwright + Edge：29/29 检查通过，覆盖四档响应式、桌面/窄屏抽屉、真实诊断、
  `feedback=up` 200、结单持久化、告警深链、拒答、SSE 截断、401 遮罩、双身份对照、低权限锁定、无 console/page error。
- `scripts/check_panel_contract.sh`：十层一致。
- `python scripts/check_public_copy.py --check`：通过。
