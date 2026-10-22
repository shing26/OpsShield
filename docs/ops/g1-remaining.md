# G1 剩余外部动作清单（2026-10-22）

> 代码/脚本/门禁能做的部分已落在仓库里；本页只列**必须在真实环境或真实外部系统上完成**的项。
> 判据与证据要求以 [production-gate.md](production-gate.md) 为准；逐项配置步骤见
> [g1-external-setup.md](g1-external-setup.md)（systemd/env/webhook/中间件/Trivy/WORM/回滚）。
> 本轮 live 实测（readiness + 真实 DashScope chat）见 [g1-live-evidence.md](g1-live-evidence.md)。

## 0. 当前已验证到哪

- `production_gate --fast`：PASS=68 / WARN=0 / FAIL=0（含一次真实手动备份）。
- `production_gate --full`：PASS=68 / WARN=0 / FAIL=0（memory/mock 真进程 + 真实手动备份；**非 live 中间件**）。
- `mvn -B test`：385；`pytest offline/tests`：374。
- `smoke.sh`、`soak.sh 5 8`、`fault_drill.sh`、`drill_release_rollback.sh`、`restore_drill.sh` 均已实跑通过。
- **这不等于十条门禁全绿**；下面每一项仍缺“真实环境的一次运行事实”。

## 1. 备份每日调度（门禁 1）

已完成（Windows 计划任务形态）：`OpsPilot-Backup` 已注册，Next Run 2026/10/24 02:00，
Last Result=0；由任务触发备份 `backup/users-2026-10-23.zip`，`verified=true`，
`check_backup_age.py` age=0.0h。证据见 [g1-live-evidence.md](g1-live-evidence.md) §2.7。

边界：Linux 生产仍需把 `ops/systemd/opspilot-backup.timer` 注册到 systemd 并等一次真实触发。
验收：`check_backup_age.py` 退出 0 且 `verified=true`，时间戳来自任务运行（`logs/backup.task.log` / journal）。

## 2. 告警到人（门禁 2）

已完成：飞书自定义机器人（webhook + 签名 secret）已配置，notify 返回 `200 forwarded`；
用户已确认群内收到；新增 Redis/磁盘依赖指标与告警规则（停 Redis 20s 后 `aiops_dependency_up{redis}=0`）。

已完成：
- 网关 down：02:37:39 停 gateway，02:40:11 `OpsPilotDown` active，receiver=human-notify；
- Redis down：02:46:38 停 Redis，20s 后 gauge=0，02:49:21 `OpsPilotRedisDown` active，receiver=human-notify；
- Alertmanager webhook 成功通知 +4（两条 firing + 两条 resolved），无新增失败。

还缺：
- 磁盘满场景：只验证 `aiops_disk_free_bytes` 指标与 `OpsPilotDiskLow` 规则已加载，**未真的填满磁盘**。

## 3. 失败证据 full 演练（门禁 4）

已完成：临时网关把 LLM model 配成不存在，制造一次真实失败（request_id `a8cf4948`）。

- 审计：`ev=chat_failed, err_class=LlmHttpException, err_msg="LLM HTTP 404", delivered=false`；
- 日志：`WARN [a8cf4948] ChatOrchestrator - stream error: LlmHttpException: LLM HTTP 404`；
- 指标：`aiops_requests_failed_total{reason="LlmHttpException"} 1.0`。

证据见 [g1-live-evidence.md](g1-live-evidence.md) §2.6。

## 4. 发布/回滚真实演练（门禁 5）

缺：真实 systemd/容器服务上的停服、换 jar、起服、回滚。

已有 `release.sh` / `rollback.sh` / `drill_release_rollback.sh`（产物哈希与入口已验证），
并已做本机进程级 previous→current→rollback（三次 health 均 True，见 live evidence §2.3）。
还缺 **systemd 测试机上的真实停服/切换**：停服 → 换 jar → 起服 → readiness →
`rollback.sh <prev> --apply` → `/state` 的 `build.version/commit` 与 manifest 对齐。

## 5. 单人并发压测（门禁 6）

缺：**live 上游**下的单租户并发测试（mock/内存 soak 只证明背压不崩）。

- 用 locust 或 `soak.sh` 改 live 配置，单租户 20–50 并发。
- 记录请求数/成功率/429 比例、`aiops_admission_rejected_total`、L0→L1→L2、P95/P99 TTFT。
- 验收：其他租户成功率不因单租户压测显著下降；L2 不因单人流量点亮。

## 6. SCA 真实结果（门禁 8）

缺：CI `security` job 的 Trivy 结果（本地 Docker Hub 拉取受限，未跑）。

- 首次 push/PR 后保存 `security` job 日志与 findings；命中 HIGH/CRITICAL 按修复/豁免记录处置。
- Dependabot 的 PR 至少有一次实际合并/关闭记录。

## 7. 审计异地 WORM（门禁 9）

缺：真实远端/不可变存储（本地脚本与 systemd 模板已就位）。

- 挂载只追加卷到 `/mnt/audit-offsite`，配 `AUDIT_OFFSITE_DIR`，启用 `opspilot-audit-offsite.timer`。
- 验收：连续两次运行产生两个时间戳目录；副本验链通过；挂载侧确认对象锁/不可变。
- 哈希链不能证明尾部未删——尾部保护由 WORM/远端只追加保证。

## 8. Runbook 演练（门禁 10）

已做本地演练（证据见 [g1-live-evidence.md](g1-live-evidence.md) §2.8）：

- “网关不可达”：02:37:39 停 gateway → 按 runbook 看端口/日志 → 02:43 重启，readiness UP；
- “降级常亮”：LLM model 配错，连续 3 次失败 → L2、failures=3；换回有效模型重启 → L0、failures=0；
- “登录 500”：只做到备份可用性 + `restore_drill.sh` 恢复 tabletop（4 账号、token_ver 可读），**未做在线登录 500 故障注入**。

还缺：在线制造一次登录 500（停库/坏库）并按 runbook 恢复，记录耗时。

## 9. 语料漂移（已知债务）

`offline/corpus/runbooks/rb-115-h2-corruption-recovery.md` 仍写“schema.sql 启动自动建表”，
而 schema 已由 Flyway V1 管理。改语料会改变 chunks 与评测报告同代摘要，需在 live 栈上
重跑 reingest/评测/报告，不能只手改 `chunks.jsonl`。
