---
doc_id: rb-103
service: opspilot-gateway
env: local
auth_level: 1
error_codes: []
---

# 起不来 / 连不上：端口被别的项目占用（本机 2026-10-04 真实事故）

## 真实签名

```
# 起网关时
APPLICATION FAILED TO START
Web server failed to start. Port 8091 was already in use.

# 或以为连上了自己，实际打的是别人
curl http://localhost:8091/actuator/health   → 503   ← 这是 ShopPilot biz-mock 的 503
```

当晚 8091 被 ShopPilot 的 `shoppilot-biz-mock`（宿主 JVM）占用，且它的 health 返回 503，
**极易被误读成"自己的网关半死不活"**。对 8091 探活拿到 503 时，OpsPilot 网关根本不在那个端口上。

## 根因

本机是多项目共存开发机：8080=nexus-web、8091=shoppilot-biz-mock、8092 被占。
完整占用表见 rb-101（本机辖区清单）。

## 排查步骤

先只读确认端口占用与健康响应，再决定换端口还是停旧实例（不要抢端口）：

```bash
netstat -ano | grep :8091
curl -s -o /dev/null -w "%{http_code}" http://localhost:8091/actuator/health
```

`netstat` 给出 LISTENING 的 pid；`curl` 的 200/503 只用于辨认是谁在服务，
不改动任何进程。下面的处置按身份判断后再执行。

## 处置

1. `bash scripts/start_gateway.sh` —— 它会**预检端口占用并点名占用者**（含完整命令行），
   一眼认出是自己的旧实例还是别的项目：
   ```
   端口 8081 已被占用：
     pid 28332 → E:\java\jdk21\bin\java.exe -Xmx768m -Dserver.port=8081 -jar target/opspilot-gateway-1.0.0.jar
   ```
2. 是自己的旧实例 → 按端口杀（`netstat -ano | grep :<port>` 取 pid → `taskkill //F //PID <pid>`）再起。
3. 是别人的 → **换端口**（`.env` 里 `SERVER_PORT=8099`），不要抢——同时必须配对
   `export OPSPILOT_BASE=http://localhost:<新端口>`，否则 python 工具链仍打默认 8081。
4. 规范端口是 **8081**，不为绕邻居而改——改端口会让文档与 compose 分叉，代价大于收益。

## 验证身份而非端口

起了之后别只看 health：任何 Spring Boot 应用的 health 都长一样。
`GET /` 的 HTML 含 `OpsPilot` 锚点才算本系统（`scripts/liveness.py` 的 `wrong_service`
状态就是干这个的——端口通但不是 OpsPilot 时，它报 `wrong_service` 而不是 `healthy`）。
