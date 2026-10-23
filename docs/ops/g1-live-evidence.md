# G1 live 栈实测记录（2026-10-22）

> 本页只记录**实际执行过**的 live 验证，不代替十条门禁的完整验收。
> 环境：本机 Windows + Docker Desktop；Redis/Qdrant/ES 容器；DashScope 真实 key 从 `.env` 注入（未打印）。
> 验证后已停止 gateway 与三个中间件容器；prometheus/alertmanager 保持原状态。

## 1. 启动

```bash
docker compose up -d redis qdrant elasticsearch
# 等 ES healthy / Redis healthy 后：
# 以 .env 为环境源启动 jar；另一次以 OPSPILOT_STRICT=true 启动
```

实测：
- 非 strict：`Started OpsPilotApplication in 16.564 seconds`；
- strict：`OPSPILOT_STRICT=true 部署校验通过：JWT 密钥强度 / H2 口令 / live 模型 / live 存储 / 监听地址均达标`，
  `Started ... in 11.346 seconds`。
- `GET /actuator/health/readiness`：**200**，`{"status":"UP"}`。

## 2. 一次真实 chat（DashScope live）

调用 `offline.localapi.login("sre-full")` + `stream_chat`，未打印 token/答案全文。结果：

```json
{"status": 200, "ttft_s": 7.695, "total_s": 19.631,
 "cache_hit": "none", "degrade": "L0", "refused": false,
 "refs": 3, "answer_chars": 969, "error": null}
```

含义：
- 真实模型调用成功（不是 mock）；
- 首个 delta 7.7s，端到端 19.6s（live 冷请求，含检索/精排/生成）；
- 返回 3 条引用，未拒答，L0 档。

## 2.1 notify 渠道自动识别

以 Slack 形状 URL 启动 notify（**未真正发 Slack**，只验证配置解析）：

```bash
OPSPILOT_HUMAN_CHANNEL=auto \
OPSPILOT_HUMAN_WEBHOOK="https://hooks.slack.com/services/T000/B000/XXXX" \
OPSPILOT_NOTIFY_PORT=18090 python scripts/notify.py
```

`GET /healthz` → `{"status":"UP","service":"notify","human_channel":"slack(host=hooks.slack.com)"}`。
说明：粘贴 Slack/飞书/企微机器人 URL 即可自动识别，不用写 relay；Telegram 走 token+chat_id。
真正的“人收到”仍需要真实 webhook 与一次投递回执。

## 2.2 飞书 webhook 实测（签名校验通过）

飞书自定义机器人开启了签名校验；仅 URL 时 notify 正确识别为失败：

```text
POST /api/v2/alerts -> HTTP 503
{"status":"upstream_failed","channel":"feishu","upstream":200,
 "error":"feishu code=19021 msg=sign match fail or timestamp is not within one hour from current time"}
```

补 `OPSPILOT_FEISHU_SECRET` 后重试：

```text
GET /healthz -> {"human_channel":"feishu(host=open.feishu.cn)"}
POST /api/v2/alerts -> HTTP 200
{"status":"forwarded","channel":"feishu","upstream":200}
```

说明：
- 飞书 API 已接受并返回业务成功（notify 现在会检查“HTTP 200 + 业务 code≠0”，不会再假绿）；
- 测试消息已发到机器人所在群，**用户已确认群内收到**（2026-10-22）；
- 该链路证明：URL + secret 两个值即可完成飞书自定义机器人接入，不需要自建 relay。

## 2.3 进程级 jar 切换/回滚（本机测试）

没有 systemd 测试机，但用本机进程做了真实的 **previous → current → rollback** 三跳：
旧 jar 取自 `HEAD` 的独立 worktree 构建，current 为当前工作区 jar；同一个临时 H2/端口 8082，
每跳都等 `/actuator/health` 200 后再停进程。

```text
previous health=True exited=False
current  health=True exited=False
rollback health=True exited=False
RESULT=True
```

边界：这验证的是“jar 可替换、进程可回滚、健康面能恢复”，**不是 systemd 停服/切换演练**，
也没有验证真实端口切换期间的连接中断时长；后者仍需要一台 systemd 测试机。

## 2.4 Redis/磁盘依赖指标实测

新增 `DependencyMetrics` 后，live 网关的 Prometheus 面出现：

```text
aiops_dependency_up{dependency="es"} 1.0
aiops_dependency_up{dependency="qdrant"} 1.0
aiops_dependency_up{dependency="redis"} 1.0
aiops_disk_free_bytes{path="data"} 2.6486673408E10
```

停掉 Redis 容器后 20s：

```text
aiops_dependency_up{dependency="redis"} 0.0
```

这补上了 `OpsPilotRedisDown` 的指标生产者；规则还需 Prometheus 重新加载后等 2m 才会
进入 Alertmanager。磁盘规则使用同一 gauge（5GiB 阈值），本次未真的填满磁盘，故只验证了
指标与规则文件，不宣称“磁盘满场景已演练”。

## 2.5 三场景告警演练（网关 down / Redis down）

### 场景 1：网关 down

- 02:37:39 停 gateway（8081）；
- 02:40:11 Alertmanager 出现 `OpsPilotDown`，`receivers=[human-notify]`，
  `status=active`，`startsAt=18:39:51Z`；
- Alertmanager webhook 通知成功（见下方计数）；
- 02:43 重启 gateway，`up{job="opspilot"}=1`，告警恢复。

### 场景 2：Redis down

- 02:46:38 停 `opspilot-redis`；
- 20s 后 `aiops_dependency_up{dependency="redis"}=0`；
- 02:49:21 Alertmanager 出现 `OpsPilotRedisDown`，`receivers=[human-notify]`，
  `status=active`，`startsAt=18:49:06Z`；
- 恢复 Redis 后 35s，`/api/v2/alerts` 为空，告警恢复。

### 投递证据

演练前/后 Alertmanager 指标：

```text
alertmanager_notifications_total{integration="webhook"} 752 -> 756
alertmanager_notifications_failed_total{integration="webhook",reason="other"} 748 -> 748
```

即新增 4 次成功通知（两条 firing + 两条 resolved），无新增失败；两条告警的
`receivers` 都是 `human-notify`。飞书应收到 2 条 firing + 2 条 resolved（请人工确认）。

### 磁盘满场景

只验证了 `aiops_disk_free_bytes` 指标与 `OpsPilotDiskLow` 规则已加载，**没有真的填满磁盘**；
不宣称该场景已演练。

## 2.6 失败证据 full（门禁 4）

用临时网关（memory + live 真实 embedding/rerank、故意把 LLM model 配成不存在）制造一次真实失败：

```text
SSE status=200 event=error code=PIPELINE_ERROR
refs=3（检索已成功，失败发生在 LLM）
meta.degradation_level=L0 gate=none
```

三条证据能指到同一 request_id `a8cf4948`：

| 面 | 证据 |
|---|---|
| 审计 | `ev=chat_failed, err_class=LlmHttpException, err_msg="LLM HTTP 404", delivered=false, request_id=a8cf4948` |
| 应用日志 | `WARN [a8cf4948] ChatOrchestrator - stream error: LlmHttpException: LLM HTTP 404` |
| 指标 | `aiops_requests_failed_total{reason="LlmHttpException"} 1.0` |

说明：失败请求仍返回 HTTP 200 + SSE error 帧（协议口径），不是 5xx；审计与日志的
`request_id` 是关联键。

## 2.7 备份每日调度（门禁 1）

Windows 本机非管理员，`install_windows_tasks.ps1` 的 `Register-ScheduledTask` 被拒绝；
改用 `schtasks.exe /Create` 注册同名任务，任务动作指向稳定的
`C:\Users\Shing\opspilot-backup.ps1`（调用 `scripts/backup.sh`）。

实测：

```text
TaskName: OpsPilot-Backup
Status: Ready
Next Run Time: 2026/10/24 2:00:00
Last Run Time: 2026/10/23 3:31:39
Last Result: 0
```

由任务触发的产物（不是手工跑）：

```json
{"epoch":1792697508,"ts":"2026-10-22T19:31:48Z","sha256":"81f048f0...","size":11800,
 "verified":true,"file":"backup/users-2026-10-23.zip","encrypted":false}
```

`python scripts/check_backup_age.py` → `BACKUP AGE: 0.0h / 26h, verified=True`，exit 0。
边界：这是 Windows 计划任务形态；Linux 生产仍需 systemd timer。

## 2.8 Runbook 演练（门禁 10）

| 症状 | 本次动作 | 结果 |
|---|---|---|
| A 登录 500 / 账号库异常 | 临时网关用空库启动（Flyway off）→ 登录返回 500；按 runbook 换回有效库/备份后重启 | 登录 500（`Table "USERS" not found`），恢复后 `LOGIN_OK`；另跑 `restore_drill.sh` 验证任务备份可用（4 账号） |
| B 网关不可达 | 02:37:39 停 gateway，按 runbook 看端口/日志，02:43 重启 | readiness 恢复 UP；`OpsPilotDown` 告警已投递 |
| C 降级常亮 | 临时网关把 LLM model 配成不存在，连续 3 次真实失败；查 `/admin/state` | 档位 L0→L0→L2，`failure=3`；换回有效模型重启后 L0、failure=0 |

结论：A/B/C 三症状均已实际操作；A 是在临时库上注入登录 500 并恢复，B/C 是真实停进程/模型错误。
门禁 10 可判绿（本机形态；生产 systemd 形态仍需在目标机复跑）。

## 2.9 live 单租户并发采样（门禁 6 / 7）

脚本：`scripts/live_concurrency.py`；真实 DashScope，20 并发，10s（实际 23.8s 排空），
租户 `tenant-internal` 加载，`tenant-acme` 对照。原始数据：[`live-concurrency-2026-10-23.json`](live-concurrency-2026-10-23.json)。

| 指标 | tenant-internal（加载） | tenant-acme（对照） |
|---|---:|---:|
| 请求数 | 84 | 1 |
| 200 | 4 | 1 |
| 429 | 80（95.2%） | 0 |
| 5xx/连接错误 | 0 | 0 |
| TTFT P50 | 18.126s | 8.944s |
| TTFT P95 | 18.133s | 8.944s |

网关侧：`aiops_requests_total=85`、`aiops_admission_rejected_total=80`、
`aiops_requests_failed_total=0`、`aiops_llm_calls_total=2`；
`/admin/state`：`degradation_level=L0`、`failures=0`。

结论与边界：
- 20 并发下租户级许可（4）触发 429 背压，0 个 5xx；另一租户未出现 429；
- 成功请求 TTFT P95 约 18s，对照租户约 8.9s；这是 10s 窗口样本，不是容量结论；
- 对照样本只有 1 条，不能宣称“其他租户成功率统计不下降”，只能说不受同租户 429 牵连；
- L2 未被单租户流量点亮（L0），但仍需更长窗口复测。

## 2.10 Ubuntu VM 测试机（2026-10-23）

VM：Ubuntu 24.04，systemd 255，OpenJDK 21，IP 192.168.59.129；网关以 systemd 运行在
/opt/opspilot，jar 为 /opt/opspilot/app.jar（OPSPILOT_JAR 与 rollback 落点一致）。

### T1：systemd 起服务

~~~text
systemctl is-active opspilot-gateway.service -> active
GET /actuator/health/readiness -> {"status":"UP"}
GET /actuator/health -> {"status":"UP","groups":["liveness","readiness"]}
~~~

### T2：停服/换 jar/回滚

当前版本 v-current（commit 969e137）→ 回滚到上一版 v-prev（commit 65cc9b3）→ 再回 v-current：

~~~text
current  active, readiness UP
v-prev   active, readiness UP
v-current active, readiness UP
rollback.sh v-prev --apply 校验 sha256 OK
~~~

注入 OPSPILOT_BUILD_COMMIT 后：

~~~text
v-current: /state.build.commit = 969e137，manifest v-current = 969e137
v-prev:    /state.build.commit = 65cc9b3，manifest v-prev = 65cc9b3
v-current: /state.build.commit = 969e137，readiness UP
~~~

manifest commit 与 /state commit 已对齐；门禁 5 的停服/换 jar/回滚/状态对齐全部完成。

### T3：1G loop 盘磁盘满

~~~text
before fill: aiops_disk_free_bytes{path="data"} 9.5019008E8  (~906MB)
after fill:  aiops_disk_free_bytes{path="data"} 4.12504064E8 (~393MB)
df: /dev/loop0 974M 851M 57M 94% /mnt/opspilot-test
~~~

VM 网关绑定 0.0.0.0，Windows Prometheus 新增 target 192.168.59.129:8081
（service=opspilot-vm）；notify 以飞书通道运行。

~~~text
Alertmanager: OpsPilotDiskLow
status=active, startsAt=2026-10-23T19:07:36Z
receivers=[human-notify], instance=192.168.59.129:8081, service=opspilot-vm
~~~

恢复磁盘后 alert resolved；Alertmanager webhook 通知计数新增成功。
边界：飞书群内磁盘告警已由用户确认收到；OpsPilotDown 仍因本机宿主网关停止而 active，与磁盘场景无关。

## 2.11 MinIO Object Lock 实测（门禁 9 对象锁部分）

VM 上另起独立 MinIO 实例（不覆盖原有 9000）：

~~~text
endpoint: http://192.168.59.129:9002
bucket:   opspilot-audit
image:    registry.cn-hangzhou.aliyuncs.com/mdpublic/mingdaoyun-minio:RELEASE.2025-04-22T22-12-26Z
object lock: COMPLIANCE 180 days
~~~

上传两次带时间戳的 audit 副本：

~~~text
20261023T233000Z/audit.tar.gz
20261023T233100Z/audit.tar.gz
~~~

对象元数据：

~~~text
X-Amz-Object-Lock-Mode: COMPLIANCE
X-Amz-Object-Lock-Retain-Until-Date: 2027-04-21T23:32:36Z
~~~

不可变验证：

~~~text
mc rm --version-id e20891ba-... -> "is WORM protected and cannot be overwritten" (rc=1)
~~~

下载副本后按时间序验链：

~~~text
PASS 审计链：2 条带 hash，2881 条旧格式；末 hash=b5359a1c...
~~~

边界：MinIO 与网关在同一台 VM，对象锁/版本保护/副本验链已证，但**不是异地副本**；
门禁 9 的“异地”仍缺。原有 9000 实例未动；新实例凭据在 /etc/opspilot/minio-audit.env（600）。

## 3. 未完成/被环境阻塞

| 项 | 结果 | 原因 |
|---|---|---|
| 飞书人类回执 | 已确认 | 飞书自定义机器人（URL + 签名 secret）已配置，notify 返回 `200 forwarded`，用户确认群内收到 |
| Trivy SCA | 已完成 | CI run 39873876035 全绿；artifact Java vulns=0；4 条跨大版本豁免见 `.trivyignore` |
| WORM 异地副本 | 部分完成 | 本地 MinIO Object Lock 已验（COMPLIANCE 180d、版本删除被拒、副本验链 PASS）；同一台 VM，**不是异地** |
| 测试机停服/切换/回滚 | 已完成 | Ubuntu 24.04 systemd：current→v-prev→current，readiness UP，`/state.build.commit` 与 manifest 对齐 |

## 4. 复现命令（安全版）

```bash
# 1) 起中间件
docker compose up -d redis qdrant elasticsearch

# 2) 起网关（strict；密钥从 .env 读，不打印）
set -a; . ./.env; set +a
OPSPILOT_STRICT=true java -jar target/opspilot-gateway-1.0.0.jar

# 3) 另一终端验证
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
# 4) 真实 chat 用 offline/localapi.py（不要打印 token）
python - <<'PY'
import sys; sys.path.insert(0, "offline"); import localapi
t = localapi.login("sre-full")
r = localapi.stream_chat({"query": "50012_DB_TIMEOUT 怎么排查", "source": "manual"}, t)
print(r["status"], r["ttft_s"], len((r.get("done") or {}).get("refs") or []))
PY
```

> 注意：`java -jar` 前台会占用终端；生产用 systemd/计划任务，不要用这个命令常驻。
> `.env` 含真实凭据，不要 cat、不要提交。
