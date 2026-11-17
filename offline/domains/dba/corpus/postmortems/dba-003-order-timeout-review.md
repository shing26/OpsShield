---
doc_id: dba-003
source_incident: pm-001
service: order-service
env: prod
auth_level: 2
tenant: tenant-internal
error_codes: [50012_DB_TIMEOUT, 50141_CONNECTION_POOL_EXHAUSTED]
---

# 下单库超时复盘：数据库值班视角（源自 pm-001）

## 事故摘要

原始事故 `pm-001` 记录了订单创建超时与连接池耗尽。DBA 侧关注的是同一事件的连接与锁面证据，
本卡不重复应用侧的修复叙述，也不引入新错误码。

## 核查步骤

### 第一步：复核当时的连接峰值

```bash
grep -E 'HikariPool|timeout' /var/log/app/order.log | tail -50
```

从应用日志还原连接池告警的时间点，与数据库侧观测对齐。

### 第二步：复核活跃会话

```sql
SHOW FULL PROCESSLIST;
```

只看 `command != 'Sleep'` 的会话，记录 `time` 与 `state` 分布。

### 第三步：核对慢查询基线

```sql
SELECT * FROM information_schema.processlist ORDER BY time DESC LIMIT 20;
```

与 `dba-001` 的快照对照，确认是同一类慢 SQL 还是新出现的形态。

## 修复措施的 DBA 侧注释

原事故的单表 analyze 由应用侧执行；DBA 侧只核对执行前后的慢日志分布，不在值班窗口自行执行 DDL。

## 复盘教训

连接池耗尽的表象下可能是慢 SQL 堆积；先给出数据库侧证据，再讨论是否扩池。
