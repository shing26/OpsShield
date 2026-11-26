# 浏览器级 E2E（Playwright）

把五条核心用户旅程放进每次 CI：真实 fat jar、真实浏览器、真实 SSE 与轮询。
不是视觉回归，也不是跨浏览器矩阵——它只回答一个问题：**页面在真实浏览器里还走得通吗。**

## 前置

- JDK 21+（`JAVA_HOME` 或 `JAVA_BIN`，Windows 本机也可用 `E:\java\jdk21`）
- `offline/.venv` 已按 `offline/requirements-dev.txt` 装好 `playwright==1.65.2` /
  `pytest-playwright==0.10.0`
- Chromium：`python -m playwright install chromium`（Windows 不加 `--with-deps`；CI 加）

## 跑

```bash
# 先产出夹具要用的 fat jar
mvn -o -DskipTests package

python -m pytest e2e -q --browser=chromium \
  --tracing=retain-on-failure --screenshot=only-on-failure --output=e2e/artifacts
```

单条旅程：

```bash
python -m pytest e2e/test_troubleshoot.py -q --browser=chromium \
  --tracing=retain-on-failure --screenshot=only-on-failure --output=e2e/artifacts
```

## 夹具口径

- session 级临时目录：拷贝 fat jar 与 `offline/corpus/chunks.jsonl`，起一个随机端口的真进程；
- `STORAGE_MODE=memory` + `DASHSCOPE_MODE=mock` + 每次全新 H2 文件库；
- 用户只在启动前用 `UserAdminCli` seed（`e2e-owner` = platform / `e2e-peer` = sre，
  同属 `tenant-internal`——语料只对这个租户可见）；
- 业务数据（事故单、告警、评论）只走公开 HTTP API，不直插 H2、不 mock 业务请求；
- 登录必须走页面身份胶囊，不注入 token；
- 密码与 `JWT_SECRET` 每轮随机生成，只走环境变量。

## 失败证据

CLI 的 `--output=e2e/artifacts` 会留下失败截图与 trace；session teardown 在**有失败**时
额外把服务端 `app.log` 拷成 `e2e/artifacts/server.log`。整个目录已进 `.gitignore`，
CI 失败时作为 artifact 保留 7 天。

## 纪律

- `retries=0`；禁止 `time.sleep` / `page.wait_for_timeout` 当 UI 等待；
- 失败不跳过、不 `xfail`、不靠重试掩盖；
- 每个 spec 可单独跑，顺序无关；业务数据用唯一后缀避免跨用例串扰；
- 选择器优先既有 `id` / `data-*`，缺的只在 `index.html` 补 `data-testid`。
