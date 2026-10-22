# G1 外部配置指南（2026-10-22）

> 目标：把 [g1-remaining.md](g1-remaining.md) 里的“外部动作”变成可执行配置。
> 先选部署形态，再按对应小节做；所有真实凭据只进环境文件，不进仓库。
> 下面命令里的路径按 `/opt/opspilot` 写；仓库放在别处时同步改 systemd 单元/脚本。

## 0. 先选部署形态

| 形态 | 适合 | 调度/告警进程 | 注意 |
|---|---|---|---|
| Linux systemd | 生产首选 | gateway / backup.timer / notify / bridge / watchdog / audit-offsite.timer | 本页命令以这个为准 |
| Windows 计划任务 | 本机开发/演示 | `install_windows_tasks.ps1` 注册四个任务 | 非管理员只能“登录启动”，不能真开机启动 |
| 容器 compose | 中间件与网关打包 | gateway 容器；backup/notify 仍建议宿主 | 镜像构建需 Docker Hub；本机曾拉不到 base 镜像 |

**不要同时用两套启动方式**（systemd + 计划任务 + compose 都起 gateway），否则 H2 文件锁/端口占用会互相打架。

## 1. 基础环境文件（Linux systemd）

### 1.1 gateway

```bash
sudo install -d -m 750 -o opspilot -g opspilot /etc/opspilot
sudo install -m 600 -o opspilot -g opspilot /dev/null /etc/opspilot/gateway.env
sudoedit /etc/opspilot/gateway.env
```

内容（**替换占位值**）：

```bash
# 真实 DashScope
DASHSCOPE_API_KEY=sk-...
DASHSCOPE_MODE=live
# 至少 43 字符；生成：openssl rand -base64 64 | tr -d '\n'
JWT_SECRET=<随机长串>
H2_DB_PASSWORD=<H2 口令>
# 生产形态
OPSPILOT_STRICT=true
STORAGE_MODE=live
SERVER_ADDRESS=127.0.0.1
OPSPILOT_ALLOW_PUBLIC_BIND=false
# 若中间件不在本机，改成实际地址；同机 compose 用 localhost
REDIS_HOST=127.0.0.1
REDIS_PASSWORD=<redis 口令>
ES_HOST=127.0.0.1
ES_PASSWORD=<es 口令>
QDRANT_HOST=127.0.0.1
QDRANT_API_KEY=<qdrant key>
OPSPILOT_BUILD_COMMIT=$(git rev-parse --short HEAD)
```

`OPSPILOT_STRICT=true` 会拒绝：占位 JWT、空 H2 口令、mock 模式、memory 存储、0.0.0.0/:: 绑定
（容器/反代需显式 `OPSPILOT_ALLOW_PUBLIC_BIND=true`）。起不来时先看 journal 点名的变量。

### 1.2 backup

```bash
sudo install -m 600 -o opspilot -g opspilot /dev/null /etc/opspilot/backup.env
```

```bash
H2_DB_PASSWORD=<与 gateway 相同>
DEMO_PASSWORD=<seed 的演示/服务账号口令；backup.sh 在线路径用它登录>
BACKUP_DIR=/opt/opspilot/backup
BACKUP_PASSPHRASE=<可选；设置后必须装 openssl>
# 如果网关不是 8081，需要同步改 scripts/backup.sh 的健康检查/URL
```

### 1.3 notify（人类告警）

```bash
sudo install -m 600 -o opspilot -g opspilot /dev/null /etc/opspilot/notify.env
```

```bash
# auto：按 URL 自动识别 Slack / 飞书自定义机器人 / 企微机器人；识别不了按 raw 转发
OPSPILOT_HUMAN_CHANNEL=auto
OPSPILOT_HUMAN_WEBHOOK=<机器人 URL 或 relay URL>
# 飞书自定义机器人开启“签名校验”时必填；否则飞书 HTTP 200 + code=19021，notify 会判失败
OPSPILOT_FEISHU_SECRET=
# Telegram（可选；填了 token+chat 会优先走 telegram）
OPSPILOT_TELEGRAM_BOT_TOKEN=
OPSPILOT_TELEGRAM_CHAT_ID=
# 可选；默认 8090
OPSPILOT_NOTIFY_PORT=8090
```

各渠道消息形状（`notify.py` 已内置，不用自己写 relay）：

| 渠道 | 配置 | 发送体 |
|---|---|---|
| Slack | 粘贴 Incoming Webhook URL | `{"text":"[firing] ..."}` |
| 飞书自定义机器人 | 粘贴 webhook URL；开启签名校验时另填 `OPSPILOT_FEISHU_SECRET` | `{"msg_type":"text","content":{"text":"..."}, "timestamp":"...","sign":"..."}` |
| 企微机器人 | 粘贴 webhook URL | `{"msgtype":"text","text":{"content":"..."}}` |
| Telegram | BotFather 建 bot → token + chat_id | Bot API `sendMessage` |
| 通用 relay | 任意接受 Alertmanager JSON 的 URL | 原始载荷，不改写 |

**飞书签名校验（重要）**：自定义机器人若在「安全设置」里开启“签名校验”，仅 webhook URL 不够——
还会返回 HTTP 200 + `code=19021`。两种做法：

1. 复制安全设置里的 secret，填 `OPSPILOT_FEISHU_SECRET=<secret>`（notify 会加 timestamp/sign）；
2. 或在该机器人安全设置里关闭签名校验（不推荐，安全性较低），URL 单独即可。

**飞书 App ID/Secret 不在本版支持**：它还需要 tenant_access_token、明确的 chat_id/接收人
和机器人权限；仅两个值不足以可靠投递。用“飞书自定义机器人 webhook”更简单。
**微信个人号/QQ 个人号没有稳定官方 API**；官方企微机器人/QQ 官方 bot 走各自协议，前者可直接用，
后者需要额外签名/回调，不在本版范围。

未配置/未送达时，notify 会落 `logs/human-alerts.jsonl`（未配置回 200；送达失败回 503 让 Alertmanager 重试）。

### 1.4 bridge / watchdog

```bash
sudo install -m 600 -o opspilot -g opspilot /dev/null /etc/opspilot/bridge.env
sudo install -m 600 -o opspilot -g opspilot /dev/null /etc/opspilot/watchdog.env
```

一般只需按需覆盖端口/地址；bridge 自己会读仓库根 `.env`。watchdog 需要读 `docker.sock`
（加入 docker 组等价 root，部署侧自行权衡）。

### 1.5 audit-offsite

```bash
sudo install -m 600 -o opspilot -g opspilot /dev/null /etc/opspilot/audit-offsite.env
```

```bash
AUDIT_OFFSITE_DIR=/mnt/audit-offsite
PYTHON=python3
```

## 2. 安装 systemd 单元

```bash
sudo cp ops/systemd/opspilot-*.service ops/systemd/opspilot-*.timer /etc/systemd/system/
sudo systemctl daemon-reload

# 网关
sudo systemctl enable --now opspilot-gateway.service

# 备份：每日 02:00；Persistent=true 会补跑错过的那次
sudo systemctl enable --now opspilot-backup.timer

# 人类通知、机器桥、看门狗
sudo systemctl enable --now opspilot-notify.service opspilot-bridge.service opspilot-watchdog.service

# 审计异地副本（把 /mnt/audit-offsite 换成真实挂载）
sudo systemctl enable --now opspilot-audit-offsite.timer
```

验收：

```bash
systemctl list-timers 'opspilot-*'
systemctl status opspilot-gateway opspilot-notify opspilot-bridge --no-pager
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
curl -fsS http://127.0.0.1:8090/healthz
cat backup/.last-success.json
python scripts/check_backup_age.py
```

### Windows 形态

```powershell
# 先干跑看路径与动作
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install_windows_tasks.ps1 -DryRun
# 真装（非管理员会退化为登录启动；备份/探针仍可跑）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/install_windows_tasks.ps1
Get-ScheduledTask -TaskName "OpsPilot-*" | Select TaskName,State
```

该脚本会同时注册 Gateway/Backup/Watchdog/Liveness 四个任务；只想要备份时，请手工建
`OpsPilot-Backup` 任务，动作与 `scripts/install_windows_tasks.ps1` 中 `backupAction` 相同，
不要运行整套脚本后又不想要 gateway 自启。

## 3. 中间件与 live 栈

### 3.1 只起中间件（宿主机跑网关）

```bash
docker compose up -d redis qdrant elasticsearch
docker compose ps
```

网关用 `scripts/start_gateway.sh` 起（它会读 `.env`），或 systemd。

### 3.2 全部容器

```.env`
中设置 `SERVER_ADDRESS=0.0.0.0`、`OPSPILOT_ALLOW_PUBLIC_BIND=true`、`OPSPILOT_STRICT=true`，
然后：

```bash
docker compose --profile full up -d gateway
docker compose logs -f gateway
```

### 3.3 首次初始化 / 验证

```bash
bash scripts/seed_demo_users.sh          # 读 DEMO_PASSWORD
bash scripts/console_token.sh            # 取 platform token（按脚本提示）
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
```

首次启动若无索引别名，`IngestionRunner` 会自动入库；已有语料时用 `/admin/reingest`（需 platform token）。
**live 会真实计费**：先用小流量/低配额验证，再跑压测；`OPSPILOT_STRICT=true` 下 mock 会被拒。

## 4. 人类告警链路

### 4.1 起 notify

```bash
sudo systemctl restart opspilot-notify
curl -fsS http://127.0.0.1:8090/healthz
```

### 4.2 验证一次送达

```bash
curl -fsS -X POST http://127.0.0.1:8090/api/v2/alerts \
  -H "Content-Type: application/json" \
  -d '{"version":"4","status":"firing","groupKey":"test","alerts":[{"status":"firing","labels":{"alertname":"OpsPilotDown","job":"opspilot"},"annotations":{},"startsAt":"2026-10-22T00:00:00Z","endsAt":"0001-01-01T00:00:00Z"}]}'
```

- 返回 `200 {"status":"forwarded","upstream":200}`：链路通；
- `503`：relay/IM 拒收，看 `journalctl -u opspilot-notify`；
- 未配置时 `200 {"status":"recorded","reason":"no_webhook"}` 且 `logs/human-alerts.jsonl` 有行。

### 4.3 Alertmanager

```bash
docker compose --profile alerting up -d prometheus alertmanager
```

确认 `alertmanager.yml` 的 human-notify 指向 `host.docker.internal:8090`；Linux 已配
`extra_hosts: host.docker.internal:host-gateway`。再触发一次真实告警（`scripts/demo_self_alert.sh`
或停一个容器），保存 IM 收到的截图/消息 ID + `logs/human-alerts.jsonl`（未配置时）作为回执。

## 5. SCA（Trivy）

### 5.1 首选：GitHub Actions

CI 已有 `security` job。把仓库推到 GitHub 并开 PR：

```bash
git remote -v
git push origin <branch>
gh run list --workflow=CI
gh run watch <run-id>
```

首次运行会拉 Trivy 漏洞库；保存 `security` job 日志与 findings。命中 HIGH/CRITICAL 时：
- 能升级就升级依赖；
- 暂不能修则加 `.trivyignore` 并在 PR 说明风险与到期日，**不要**把 `exit-code` 改成 0 假装通过。

### 5.2 本地

```bash
docker run --rm -v "$PWD:/src" aquasec/trivy:0.58.0 fs \
  --severity HIGH,CRITICAL --ignore-unfixed --exit-code 1 /src
```

若 Docker Hub 被网络策略拦（本机实测过 `registry-1.docker.io ... EOF`），在 Docker Desktop 配代理，
或从 GitHub Releases 下载 trivy 二进制；两条路都不通就只能在 CI 侧跑。

## 6. 审计异地/WORM

### 6.1 NFS/只追加卷

```bash
sudo mkdir -p /mnt/audit-offsite
# /etc/fstab 示例（按实际 server/export 改）
# <server>:/export/opspilot-audit  /mnt/audit-offsite  nfs  ro,nosuid,nodev,noexec  0 0
sudo mount -a
```

`audit_offsite.sh` 需要写目标目录；如果挂载是只读，请改成“写入后由存储侧转 WORM”的流程，
或挂可写卷并依赖对象锁策略。**S3 Object Lock 不是 mount 自动带来的**，需用支持对象锁的上传器
（AWS CLI `--object-lock-mode COMPLIANCE --object-lock-retain-until-date` 等）或 S3 网关。

### 6.2 启用与验收

```bash
sudo systemctl enable --now opspilot-audit-offsite.timer
sudo systemctl start opspilot-audit-offsite.service
ls -lt /mnt/audit-offsite
python scripts/verify_audit_chain.py /mnt/audit-offsite/<时间戳>/audit*.jsonl \
  --head /mnt/audit-offsite/<时间戳>/.audit-head
```

连续两次运行应产生两个时间戳目录，且副本链通过。

## 7. 真实停服/切换/回滚演练

在**测试机**（不是生产）准备：

```bash
# 1. 安装并启动 systemd 服务（见 `2）
# 2. 当前版本记录
curl -fsS http://127.0.0.1:8081/api/v1/admin/state -H "Authorization: Bearer <platform-token>" > /tmp/state-before.json
# 3. 发布候选版本
bash scripts/release.sh v0.0.1-rc1
# 4. 停服 → 换 jar → 起服
sudo systemctl stop opspilot-gateway
sudo install -m 0644 release/opspilot-v0.0.1-rc1.jar /opt/opspilot/app.jar
sudo systemctl start opspilot-gateway
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
# 5. 回滚
bash scripts/rollback.sh <上一版> --apply
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
```

记录两轮 `/state` 的 `build.version/commit` 与 manifest 对齐、readiness 恢复时间、audit 行。

## 8. 提交当前变更（可选）

当前工作区约 108 项未提交变更（含新脚本/测试/文档）。确认不要提交 `.env`、`data/`、`backup/`、`logs/`：

```bash
git status --short
git diff --check
git diff --stat
# 按组添加，不要无脑 add -A 把本机产物带进去
git add README.md OPS.md docs/ ops/ scripts/ src/ pom.xml docker-compose.yml .env.example .github/
git commit -m "chore(ops): G0/G1 production-readiness hardening"
git log --oneline -1
```

`docs/assets/` 若含录屏/预览图，按仓库既有 .gitignore 口径决定是否入库；不要提交真实凭据。
