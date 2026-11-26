# 浏览器级 E2E 入仓

## 上下文

事故通知铃、历史分页、引用版本徽标这些交互此前只有静态字符串断言：页面里的
`data-*` 钩子、轮询、SSE 结束态与事件绑定是否真的工作，单测与 HTTP 集成都看不见。
多个切片用 Playwright 做过临时验收，但验完即删（`plans/incident-responsibility-accept.md`
等三处明写），换台机器就要重写 H2、用户、端口与语料拷贝。结果是：UI 运行时缺陷
只能靠人工发现，且失败没有截图、trace、服务端日志可复述。

## 决策

1. **技术栈 = Python 3.11 + Playwright for Python**：`playwright==1.65.2`、
   `pytest-playwright==0.10.0`，只跑 Chromium headless。仓库测试语言已是 Python，
   不引入 Node / `package.json` / 第二套 runner。
2. **真实性 = 真进程，不 mock 网络**：`mvn -DskipTests package` 的 fat jar +
   `STORAGE_MODE=memory` + `DASHSCOPE_MODE=mock` + 每次全新 H2 + 随机空闲端口；
   登录走真实 UI；用户只在启动前用 `UserAdminCli` seed；业务数据只走公开 API，
   不直插 H2、不 stub/abort 业务请求、不注入 token 跳过登录。
3. **覆盖 = 五条核心旅程**：登录与会话、排障 SSE + 引用 + 只读命令、事故协作闭环、
   历史分页、告警入口幂等。每条一个 spec，可单独执行。
4. **零 flake 是硬约束**：`retries=0`；UI 等待只用 Playwright 自动等待与显式条件，
   禁固定 `sleep` / `wait_for_timeout`（启动探活的有界轮询除外）；失败留
   screenshot + trace + `server.log`；套件（不含浏览器安装）< 5 分钟。
5. **稳定选择器优先**：优先既有 `id` / `data-act` / `data-inc` / `data-ref-item`；
   缺钩子只在 `index.html` 补 `data-testid`，不为测试改业务行为或文案。

## 否决项

- **引入 Node + `package.json`**：多一套运行时与锁文件，只为跑同一批浏览器动作；
  仓库的测试入口已经是 pytest。
- **mock 网络 / route abort 业务 API**：那样只能证明"页面会按假响应渲染"，
  证明不了真实 SSE、轮询、鉴权与租户过滤串起来仍然成立。
- **直插 H2 造数**：绕过公开 API 的校验与写入路径，测试会与产品行为分叉。
- **固定 `sleep` / 重试**：把 flake 藏起来，等于让门禁随机变绿。
- **视觉回归、跨浏览器（Firefox/WebKit）、移动端视口、性能、L1 执行**：
  都需要各自的基线或环境，不属于"核心旅程是否走得通"这一层。
- **把临时脚本继续留在本机**：不可重复、无证据、无 CI 门禁，等于没有回归。

## 后果

- 新增 `e2e/` 与 CI 第 12 个 job（`e2e`）。运行时零改动，回滚只需删 `e2e/` 与该 job。
- `index.html` 只新增测试钩子；E2E 同时发现并修正了左栏长列表压住「加载更多」的
  flex 收缩布局缺陷（`flex:0 0 auto`），属于可点击性修复，不改变业务语义。
- E2E 不替代单测/HTTP 集成，也不替代人审；它守的是"真实浏览器里页面还能用"。
- 首次运行需下载约 330MB 的 Chromium（CI 走缓存），这是本层唯一的新环境成本。
