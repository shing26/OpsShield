# G1 剩余外部动作清单（2026-10-22）

> 代码/脚本/门禁能做的部分已落在仓库里；本页只列**必须在真实环境或真实外部系统上完成**的项。
> 判据与证据要求以 [production-gate.md](production-gate.md) 为准；逐项配置步骤见
> [g1-external-setup.md](g1-external-setup.md)（systemd/env/webhook/中间件/Trivy/WORM/回滚）。
> 本轮 live 实测（readiness + 真实 DashScope chat）见 [g1-live-evidence.md](g1-live-evidence.md)。
> 按资源依赖拆的任务清单与 VM/WSL 操作步骤见 [blocker-tasks.md](blocker-tasks.md)。

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

已完成磁盘满：Ubuntu VM 用 1G loop 盘填到 94%（`aiops_disk_free_bytes` 906MB→393MB），
Windows Prometheus 新增 VM target；`OpsPilotDiskLow` 于 19:07:36Z active、receiver=human-notify、
service=opspilot-vm，恢复后 resolved；Alertmanager webhook 成功通知新增。

还缺：飞书群内磁盘告警消息的最终人工确认。

## 3. 失败证据 full 演练（门禁 4）

已完成：临时网关把 LLM model 配成不存在，制造一次真实失败（request_id `a8cf4948`）。

- 审计：`ev=chat_failed, err_class=LlmHttpException, err_msg="LLM HTTP 404", delivered=false`；
- 日志：`WARN [a8cf4948] ChatOrchestrator - stream error: LlmHttpException: LLM HTTP 404`；
- 指标：`aiops_requests_failed_total{reason="LlmHttpException"} 1.0`。

证据见 [g1-live-evidence.md](g1-live-evidence.md) §2.6。

## 4. 发布/回滚真实演练（门禁 5）

已完成 Ubuntu 24.04 VM 上的 systemd 演练（证据见 [g1-live-evidence.md](g1-live-evidence.md) §2.10）：

- current → v-prev（commit 65cc9b3）→ current 三次切换；
- 每次 `systemctl is-active` active、readiness UP；
- `rollback.sh v-prev --apply` 校验 sha256 OK；
- 注入 `OPSPILOT_BUILD_COMMIT` 后 `/state.build.commit` 分别 969e137 / 65cc9b3 / 969e137，
  与 manifest 对齐。

门禁 5 已闭环。

## 5. 单人并发压测（门禁 6）

已完成一轮 live 采样（20 并发 / 10s；`scripts/live_concurrency.py`）：
84 次加载请求、4×200、80×429、0×5xx；对照租户 1×200、0×429；
`aiops_admission_rejected_total=80`、`requests_failed=0`、`degradation_level=L0`；
成功 TTFT P95 18.1s，对照 8.9s。证据见 [g1-live-evidence.md](g1-live-evidence.md) §2.9。

还缺：
- 对照租户至少 20–30 次**同窗口**成功请求，才能比较“成功率不显著下降”；
- 更长窗口（5–30 分钟）与 P99/容量拐点；
- 不要用 10s 样本宣称容量通过。

## 6. SCA 真实结果（门禁 8）

已完成：CI run `39873876035` 全绿，`security` job success；Trivy artifact 中 Java vulns=0。

- 初始 37 个 HIGH/CRITICAL；同主版本补丁覆盖修复 33 个（Tomcat 10.1.59、Jackson 2.22.3、
  Netty 4.1.137.Final、Spring 6.2.19、Micrometer 1.15.12、protobuf 3.25.5、gRPC 1.75.0）；
- 4 个跨大版本修复的 CVE 登记在 [`.trivyignore`](../../.trivyignore) + [sca-findings.md](sca-findings.md)，
  附补偿控制与 2027-10-23 复核到期；
- 还缺：Dependabot 的 PR 至少一次实际合并/关闭记录。

## 7. 审计异地 WORM（门禁 9）

缺：真实远端/不可变存储（本地脚本与 systemd 模板已就位）。

- 挂载只追加卷到 `/mnt/audit-offsite`，配 `AUDIT_OFFSITE_DIR`，启用 `opspilot-audit-offsite.timer`。
- 验收：连续两次运行产生两个时间戳目录；副本验链通过；挂载侧确认对象锁/不可变。
- 哈希链不能证明尾部未删——尾部保护由 WORM/远端只追加保证。

## 8. Runbook 演练（门禁 10）

已做本地演练（证据见 [g1-live-evidence.md](g1-live-evidence.md) §2.8）：

- “登录 500”：空库启动 → 登录 500（`Table "USERS" not found`）→ 换回有效库重启 → `LOGIN_OK`；
- “网关不可达”：02:37:39 停 gateway → 按 runbook 看端口/日志 → 02:43 重启，readiness UP；
- “降级常亮”：LLM model 配错，连续 3 次失败 → L2、failures=3；换回有效模型重启 → L0、failures=0。

门禁 10 本机形态已满足；生产 systemd 形态仍需在目标机复跑一次。

## 9. 语料漂移（已知债务）

`offline/corpus/runbooks/rb-115-h2-corruption-recovery.md` 仍写“schema.sql 启动自动建表”，
而 schema 已由 Flyway V1 管理。改语料会改变 chunks 与评测报告同代摘要，需在 live 栈上
重跑 reingest/评测/报告，不能只手改 `chunks.jsonl`。
