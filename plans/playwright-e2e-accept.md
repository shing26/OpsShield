# 验收契约：浏览器级 E2E 入库（Playwright）

> 来源：2026-11-26 用户选定 S4 `playwright-e2e`。
> **状态：已冻结（2026-11-26）。** 基线 `main` @ `b172c94`；
> 本分支只承载契约；实现分支为 `codex/playwright-e2e-impl`（契约合并后从 main 切出）。
> 用户已签收五项口径：Python Playwright + Chromium only、真进程不 mock 网络、
> 五条核心旅程、零 flake 纪律、稳定选择器优先。
> 本切片只做「把五条核心用户旅程变成每次 CI 都跑的浏览器级用例」；
> 不做视觉回归、跨浏览器矩阵、移动端 E2E、性能、L1 执行。

## Task ID

`playwright-e2e`

## 现状与缺口（2026-11-26 实测，基线 `b172c94`）

已有能力：

- 前端是一份 3936 行的单文件 `src/main/resources/static/index.html`（内联 CSS/JS），
  由 Spring Boot 直接托管；
- `offline/tests/test_console_contract.py` 有 **39 条静态断言**（CSS 变量、选择器、文案、
  请求体字段），能拦「改了字符串但没改页面」；
- HTTP 层有事故/通知/分页/告警的集成测试与 API 矩阵；
- 之前多个切片用 Playwright（Edge/Chromium）做过临时验收，但**验完即删**：
  `plans/incident-responsibility-accept.md:116`、`plans/incident-collaboration-accept.md:103`、
  `plans/alert-intake-panel-accept.md:152` 明写「临时脚本，验完即删」；
  `docs/qa/2026-10-27-console-defect-review.md:129` 已建议迁入仓库但未执行。

缺口：

1. **没有可重复的浏览器级回归**：S1 通知铃、S2「加载更多」、S3 引用版本徽标都只有静态字符串
   断言，没有真实点击、SSE 渲染、轮询与焦点行为。
2. **没有可复用的启动/seed 夹具**：每次临时脚本都要手搓 H2、用户、端口与语料拷贝，
   换台机器就重写一遍。
3. **没有失败证据**：临时脚本不留截图、trace、服务端日志，失败只能靠复述。
4. **没有 flake 纪律**：固定 `sleep`、重试掩盖、顺序依赖都没有被禁止。
5. **CI 没有浏览器 job**：11 个 job 覆盖单测/契约/证据同代/smoke，但 UI 运行时缺陷
   （未捕获 JS 异常、事件绑定断、SSE 结束态渲染错）只能靠人工发现。

## 判定口径（五项，待签收）

1. **技术栈 = Python 3.11 + Playwright for Python**：
   `playwright==1.65.2`、`pytest-playwright==0.10.0`（其 `Requires-Dist` 为
   `pytest>=7,<10`，与仓库既有 `pytest==9.1.1` 兼容）；只跑 **Chromium headless**。
   **不引入 Node / package.json**——仓库的测试语言已经是 Python，E2E 只加一个已锁版本的依赖。
2. **真实性 = 真进程，不 mock 网络**：
   - 跑 `mvn -DskipTests package` 产出的 fat jar（`target/opspilot-gateway-1.0.0.jar`）；
   - `STORAGE_MODE=memory` + `DASHSCOPE_MODE=mock` + 每次全新 H2 文件库 + 随机空闲端口；
   - 登录**走真实 UI**（身份胶囊 → 用户名/口令 → 接入），不注入 token 跳过登录；
   - 用户 seed 只在**启动前**用 `UserAdminCli`；业务数据 seed 只走**公开 API**；
     不直插 H2、不 stub `/api/*`、不 route abort 业务请求。
3. **覆盖 = 五条核心旅程**（每条一个 spec 文件）：
   1) 登录与会话（错口令/正口令/刷新保持）；
   2) 排障主链路（SSE 完成 → 结论 + 引用 + 只读命令；引用带 S3 的 `docId@摘要` 与时效徽标）；
   3) 事故协作闭环（建单 → 分配 → 通知铃 → 打开 → 评论 @ → 未读 → 结单）；
   4) 历史分页（≥65 条 seed → 加载更多不重复 → 切筛选重置）；
   5) 告警入口（`source=alert` + `alertId` → 面板行 → 建单 → 再次请求幂等）。
   不做视觉回归、跨浏览器（Firefox/WebKit）、移动端视口、性能、L1 执行。
4. **稳定性 = 零容忍 flake**：`retries=0`；UI 断言禁用固定 `sleep` / `wait_for_timeout`，
   一律用 Playwright 的自动等待与显式条件；失败必须留 screenshot + trace + `server.log`；
   套件（不含浏览器安装）**< 5 分钟**；Verifier 连跑两遍全绿，且每个 spec 单独跑也绿。
5. **选择器 = 稳定钩子优先**：优先既有 `id` / `data-act` / `data-inc` / `data-ref-item` 等；
   缺钩子的地方只在 `index.html` 补 `data-testid`；**不得用可见文案做断言选择器**，
   也不得为了测试改业务行为或改文案。

## Allowed paths

```
plans/playwright-e2e-accept.md
docs/adr/0040-browser-e2e.md
docs/CONTEXT.md
docs/repo-map.md
README.md
offline/doc_numbers.json
offline/requirements-dev.txt
.github/workflows/ci.yml
.gitignore
e2e/README.md
e2e/conftest.py
e2e/helpers.py
e2e/test_login.py
e2e/test_troubleshoot.py
e2e/test_incident_collaboration.py
e2e/test_history_pagination.py
e2e/test_alert_intake.py
src/main/resources/static/index.html
```

## Acceptance criteria

1. **启动夹具**：session 级 fixture 建临时目录，拷贝 fat jar 与 `offline/corpus/chunks.jsonl`，
   用 `UserAdminCli` seed 用户（`e2e-owner` = platform、`e2e-peer` = sre，同租户），
   随机空闲端口起进程，`/actuator/health` 60s 内 UP；teardown 必杀进程并清理临时目录；
   测试结束不得残留 java 进程或端口占用。密码/密钥每轮随机生成，只走环境变量。
2. **登录**：错口令 → `#acctpop` 内错误提示，胶囊仍「未接入」；
   正口令 → 胶囊显示用户名、事故列表加载；刷新页面仍在（sessionStorage）；
   新 context 打开 `/` → 未接入。
3. **排障 + 引用 + 命令**：输入 `50012_DB_TIMEOUT 怎么排查` → `done` 后结论卡可见；
   `[data-ref-item]` ≥1，每条含 `data-testid="ref-version"`（`docId@12位摘要`）与
   `data-testid="ref-staleness"`（未过期/已过期/未标注）；摘要与 `chunks.jsonl` 中该
   `chunk_id` 的 `doc_digest` 逐条一致；只读命令清单 ≥1；引用区无 `undefined` / `null`；
   `pageerror == 0`。
4. **事故协作闭环**：
   - owner 通过 `#incDialog` 建单（唯一标题）→ 左栏出现并选中；
   - 点 `[data-act="assign"]` → `#assignpop [data-assign="e2e-peer"]` → 负责人显示为 peer；
   - peer 新 context 登录 → 铃铛角标 ≥1 → 打开通知 → 跳到该事故单；
   - owner 发评论 `@e2e-peer ...` → 评论出现；peer 刷新列表 → 该单出现「新」未读角标，
     显式打开后角标消失；
   - 结单必填处置结论 → 状态变「已结单」，`已结单` 筛选可见。
5. **历史分页**：用公开 API seed ≥65 条 open 事故；UI 首屏条数 =
   `min(60, openCount)`；点 `#incMore` 后 = `min(120, openCount)` 且 `data-inc` 无重复；
   切 `已结单` → 空态/首屏重置；切回 `全部` → 回到第一页且按钮状态正确。
6. **告警入口**：用公开 API 发一条 `source=alert` + 唯一 `alertId` 的 chat；切运行面板 →
   `[data-alert="<id>"]` 行出现；点「建事故单」→ 生成一条带该 `alertId` 的事故；
   再调一次 `POST /alerts/{alertId}/incident?tenant=...` → 返回既有事故（幂等），
   API 里该 `alertId` 仍只有一条。
7. **稳定性证据**：`pytest e2e` 连跑两遍全绿；五个 spec 各自单独跑也全绿；
   代码中无 `time.sleep` / `page.wait_for_timeout` / `retry`；无 `skip` / `xfail`。
8. **失败证据**：失败时 `e2e/artifacts/` 下有 screenshot、trace、`server.log`；
   `e2e/artifacts/` 进 `.gitignore`；CI 失败时上传该目录（保留 7 天）。
9. **CI job**：新增 `e2e` job（Java 21 + Python 3.11 + pinned deps +
   `python -m playwright install --with-deps chromium` + `mvn -B -DskipTests package` +
   带 `--tracing=retain-on-failure --screenshot=only-on-failure --output=e2e/artifacts` 的 pytest）；
   浏览器目录走缓存、key 绑定 Playwright 版本；`timeout-minutes: 12`；
   PR 与 main push 都触发；失败上传 artifacts。
10. **选择器与静态契约**：新增的 `data-testid` 只出现在 `index.html`；
    既有 `test_console_contract.py` 39 条与 `check_panel_contract.sh` 十层保持全绿；
    不删改任何既有断言、不改业务行为/文案。
11. **不回归**：`mvn -o test` 601 / 0 / 0 / 0；`pytest offline/tests` 703 passed；
    `provenance.py --check`、`answer_eval.py --check`、`doc_numbers.py --check`、
    `check_public_copy.py --check` 全绿；事故/通知/评论/分页/告警语义零变化。
12. **文档**：ADR-0040 记录五项决策与否决项；`docs/CONTEXT.md` 增「浏览器级 E2E」词条；
    `docs/repo-map.md` 更新 ADR 计数（39→40）与测试层；README 增加 E2E 说明；
    `offline/doc_numbers.json` 新增 `e2e_test_declarations`（`derive: grep_count`
    扫 `e2e/**/*.py` 的 `^def test_`），README 用
    `Playwright E2E (\\d+) 条` 引用，数字由门闩现算。

## Verification commands（Verifier 亲自重跑）

```bash
cd "D:/OpsPilot — AIOps"
git rev-parse HEAD && git status --short
E:/java/apache-maven-3.9.14/bin/mvn.cmd -o test
E:/java/apache-maven-3.9.14/bin/mvn.cmd -o -DskipTests package
offline/.venv/Scripts/python.exe -m pip install -r offline/requirements-dev.txt
offline/.venv/Scripts/python.exe -m playwright install chromium   # Windows；CI 用 --with-deps
offline/.venv/Scripts/python.exe -m pytest e2e -q --browser=chromium \
  --tracing=retain-on-failure --screenshot=only-on-failure --output=e2e/artifacts
# 第二遍（证明无顺序依赖/无残留）
offline/.venv/Scripts/python.exe -m pytest e2e -q --browser=chromium \
  --tracing=retain-on-failure --screenshot=only-on-failure --output=e2e/artifacts
offline/.venv/Scripts/python.exe -m pytest offline/tests -q
offline/.venv/Scripts/python.exe offline/provenance.py --check
cd offline && "D:/OpsPilot — AIOps/offline/.venv/Scripts/python.exe" eval/answer_eval.py --check
offline/.venv/Scripts/python.exe offline/doc_numbers.py --check
offline/.venv/Scripts/python.exe scripts/check_public_copy.py --check
E:/Git/bin/bash.exe scripts/check_panel_contract.sh
```

## 活体验收（Verifier 亲自操作）

1. 先 headless 连跑两遍（命令如上），两遍都全绿；再 `--headed` 跑一遍，
   亲眼确认五条旅程是真实点击、真实 SSE、真实轮询，不是只读脚本输出。
2. 制造一次可控失败（例如临时改错一个 `data-testid`），确认失败目录里确实有
   screenshot / trace / `server.log`，随后恢复。
3. 跑完 `git status --short` 必须干净（`e2e/artifacts/` 已忽略）；`Get-Process java`
   不得残留测试进程。
4. 记录整套 E2E 的墙钟时间（不含浏览器安装），必须 < 5 分钟。

## Forbidden

- 禁止 `time.sleep` / `page.wait_for_timeout` 作为 UI 等待；启动探活的有界轮询除外。
- 禁止重试/跳过/`xfail` 掩盖 flake；禁止顺序依赖（每个 spec 单独跑必须绿）。
- 禁止直插 H2、禁止 stub/abort 业务 API、禁止注入 token 跳过登录（登录 spec 必须走 UI）。
- 禁止引入 Node / package.json / 第二套 Playwright runner。
- 禁止为通过测试改业务行为、改文案或删改既有断言；只允许补 `data-testid`。
- 禁止提交 `e2e/artifacts/`、截图、trace、日志或任何凭据；密码/密钥只走环境变量。
- 禁止把视觉回归、跨浏览器矩阵、移动端 E2E、性能测试塞进本切片。
- 禁止改 V1–V11 迁移、事故/通知/评论/分页/告警语义。

## 回滚

本切片只新增 `e2e/` 与 CI job，运行时零改动（`index.html` 仅加测试钩子）。
删除 `e2e` job 与 `e2e/` 即可回滚；既有功能不受影响。

## 人审签收

**状态：已签收（2026-11-26）。** 五项口径按原样确认：

1. Python + `playwright==1.65.2` / `pytest-playwright==0.10.0`，Chromium only，不引入 Node；
2. 真 fat jar + memory+mock + 全新 H2；登录走 UI，数据 seed 只走公开 API，不 mock 网络；
3. 只覆盖五条核心旅程；视觉回归/跨浏览器/移动端/性能/L1 执行不在本切片；
4. `retries=0`、禁固定 sleep、失败留 screenshot+trace+server.log、Verifier 连跑两遍；
5. 选择器优先既有钩子，缺的只补 `data-testid`，不改业务行为与文案。

实现者与 Verifier 不得为同一会话。本次签收只授权实现，不含合并授权；
合并须另行书面签收。
