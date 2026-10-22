# 值班 5 分钟 Runbook（G1 首版，2026-10-22）

> 这是**按症状拆分**的入口，不是第二套运维手册。命令与判据的完整出处见 [OPS.md](../../OPS.md)；
> 十条门禁的验收记录见 [production-gate.md](production-gate.md)。本页只回答："值班人现在先看什么、先做什么"。
> 所有命令默认单机 systemd 形态；容器形态把 `systemctl` 换成 `docker compose` 对应动作。

## 0. 先做三件事（30 秒）

```bash
curl -fsS http://127.0.0.1:8081/actuator/health/readiness   # 依赖面
curl -fsS http://127.0.0.1:8081/actuator/health             # 聚合
tail -n 80 logs/app.log                                     # 最近错误
```

判定：
- `readiness` DOWN → 看 **A（账号库）** 或 **B（外部依赖）**；
- `readiness` UP 但用户说不可用 → 看 **C（降级/上游）** 或网关入口；
- 网关进程不在 → 直接走 **B**，不要先查业务。

## A. 登录 500 / 账号库异常

**症状**：登录接口 500；`/actuator/health` 的 `db` DOWN；日志出现 H2 `MVStore` / `JdbcSQL` 异常。

1. **先止损**：确认不是并发写坏库（G0 已去掉 AUTO_SERVER）。若同时有 CLI/脚本在写库，先停掉它们。
   ```bash
   systemctl status opspilot-gateway --no-pager
   grep -n "H2\|MVStore\|users" logs/app.log | tail -n 30
   ```
2. **看最近一次成功备份**（没有成功备份就不要动库）：
   ```bash
   cat backup/.last-success.json 2>/dev/null || echo "无备份状态文件"
   ls -lt backup/users-*.zip | head
   ```
3. **不要手工改 H2 文件**。按 [OPS.md](../../OPS.md) §8 用 `scripts/restore_drill.sh` 先恢复到**演练库**验证备份可用；
   需要真恢复时，停网关 → 用 `UserAdminCli restore` 恢复到 `./data/users` → 起网关。
4. **恢复后的必做**：全员 token_ver 会回退到备份时刻，必须让所有用户重新登录（见 OPS §8 的吊销说明）。
5. 若 Flyway 报 schema 校验失败：不要跳过校验强启；先看 `flyway_schema_history` 与代码版本是否匹配，
   把库/版本事实写进事件记录，再决定回滚发布还是前滚迁移。

**5 分钟边界**：5 分钟内无法确认备份可用 → 先保住现场（停写、留日志/库文件副本），升级给负责人；不要在故障中试恢复。

## B. 网关不可达 / 端口不监听

**症状**：`curl` 连接拒绝；`systemctl status` failed；容器不断重启。

1. **看进程与端口**：
   ```bash
   systemctl status opspilot-gateway --no-pager
   ss -ltnp | grep 8081 || true
   tail -n 100 logs/app.log
   ```
2. **区分启动失败与运行中崩溃**：
   - 启动日志有 `DeploymentGuard` / strict 违规 → 按点名的变量修配置（占位 JWT、空 H2 口令、mock、0.0.0.0 绑定）；
   - 启动日志有 Flyway 失败 → 走 A 的第 5 步；
   - 无日志但进程退出 → 看 `journalctl -u opspilot-gateway -n 100`，按 OOM/端口冲突处理。
3. **回滚**：确认上一版 release 产物存在且哈希通过后，按 `scripts/rollback.sh <version>` 的提示操作
   （该脚本只校验+提示；`--apply` 会执行 systemd 停/换/起）。
4. 起不来且 5 分钟内无结论 → 保留 `logs/` 与 `journalctl` 输出，升级；不要反复重启掩盖首个错误。

## C. 降级常亮（L1/L2 不恢复）

**症状**：面板降级档长期 L1/L2；`aiops_llm_network_errors_total` / `aiops_llm_rate_limited_total` 增长；
用户反馈"只会回 SOP/拒答"。

1. **确认是自动还是手动锁**：看 `/api/v1/admin/state` 的 `degrade.manual` 与 `degrade.level`。
   手动锁要由值班人显式解除，不是故障。
2. **看上游信号**（不要只看网关）：
   ```bash
   curl -fsS http://127.0.0.1:8081/actuator/prometheus | grep -E "aiops_llm_(network_errors|rate_limited|retries)|aiops_inflight|aiops_requests_failed"
   grep -n "stream error\|LLM 429\|timeout" logs/app.log | tail -n 30
   ```
3. **判断**：
   - 429/限流为主 → 查账号配额与 `ADMISSION_*` 是否把正常流量挡在门外；必要时临时调低准入上限而不是关掉它；
   - 网络错/超时为主 → 查 DashScope 连通性与 DNS，确认恢复后再等冷却窗口（`opspilot.degrade.llm-open-seconds`）；
   - 只有 `aiops_inflight` 高 → 看是否有单租户打满（准入应已 429；查 `aiops_admission_rejected_total`）。
4. **不要**为了"看起来恢复"把熔断阈值改成永不触发；那会把上游故障变成全站慢查询。
5. 恢复后看 `aiops_ttft_seconds` 与 `aiops_request_seconds` 是否回到基线，再关事件。

## 交接模板（复制到事件记录）

```text
时间/发现人：
症状（A/B/C）：
readiness / 关键指标：
已做动作与结果：
备份状态（.last-success / 最近 zip）：
当前版本（/state build.version+commit）：
下一步与负责人：
```
