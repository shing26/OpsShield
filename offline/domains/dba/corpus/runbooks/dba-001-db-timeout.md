---
doc_id: dba-001
source_incident: rb-001
service: order-service
env: prod
auth_level: 2
tenant: tenant-internal
error_codes: [50012_DB_TIMEOUT, 50141_CONNECTION_POOL_EXHAUSTED, 50101_SLOW_SQL_DETECTED]
---

# 下单库超时：数据库值班处置卡（50012_DB_TIMEOUT）

## 适用症状

- 下单接口返回 `50012_DB_TIMEOUT`，应用日志出现 `HikariPool-1 - Connection is not available`。
- 同时可见 `50141_CONNECTION_POOL_EXHAUSTED` 或 `50101_SLOW_SQL_DETECTED`。
- 原始事件见 `rb-001`（订单创建超时排查手册）；本卡只重写 DBA 值班视角，不新增错误码。

## 核查步骤

### 第一步：确认是慢 SQL 还是连接池满

```sql
SELECT * FROM information_schema.processlist
WHERE command != 'Sleep' ORDER BY time DESC LIMIT 20;
```

活跃连接若长期处于 `Sending data`，按慢 SQL 走；若 processlist 平静而应用侧报池耗尽，按连接池走。

### 第二步：定位慢 SQL

```sql
SHOW FULL PROCESSLIST;
```

```sql
EXPLAIN SELECT * FROM t_order WHERE order_id = 1;
```

关注 `type=ALL` 与 `rows` 异常大的执行计划。

### 第三步：核对连接池与应用指标

```bash
curl -s localhost:8080/actuator/metrics/hikaricp.connections.active | jq
```

```bash
curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending | jq
```

`pending` 持续增长提示池上限不足或连接泄漏，交给应用负责人确认。

### 第四步：收集慢查询样本

```bash
tail -100 /var/log/mysql/slow.log | grep -E 'Query_time|Rows_examined'
```

样本按 SQL 指纹归类，交给值班 DBA 判读；不要在值班窗口改动表名或索引。

## 止血与恢复

1. 对慢 SQL 来源接口临时限流；长查询由值班 DBA 人工确认后 `KILL <processlist_id>`。
2. 连接池耗尽时先摘流量，再重启实例释放泄漏连接；禁止直接对生产库执行 DDL。
3. 恢复后保留 processlist 快照与慢日志片段，供复盘对齐。

## 升级路径

10 分钟内无法定位升级 DBA 负责人；涉及主库走 P1 流程，不在本卡授权写操作。
