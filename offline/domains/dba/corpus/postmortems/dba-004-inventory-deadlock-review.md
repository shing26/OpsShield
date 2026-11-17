---
doc_id: dba-004
source_incident: pm-002
service: inventory-service
env: prod
auth_level: 2
tenant: tenant-internal
error_codes: [50013_DB_DEADLOCK]
---

# 库存死锁复盘：数据库值班视角（源自 pm-002）

## 事故摘要

原始事故 `pm-002` 的根因是批量任务与在线扣减的加锁顺序不一致。DBA 侧复盘聚焦锁证据与顺序约定。

## 核查步骤

### 第一步：重放死锁的锁顺序证据

```sql
SHOW ENGINE INNODB STATUS\G
```

从 `LATEST DETECTED DEADLOCK` 段读出两个事务各自持有的锁与等待的锁。

### 第二步：核对事务起始时间

```sql
SELECT trx_id, trx_started, trx_state FROM information_schema.innodb_trx;
```

确认长事务是否是死锁链的一环，记录其起始时间。

### 第三步：核对热点索引

```sql
EXPLAIN SELECT sku_id FROM t_inventory WHERE sku_id = 1001;
```

确认查询走唯一索引，避免把索引问题误读成加锁顺序问题。

## 修复措施的 DBA 侧注释

按原复盘的加锁顺序约定调整批量任务；DBA 侧复核后再放行。禁止用 `KILL` 代替顺序修复。

## 复盘教训

死锁不是"偶发抖动"：同一加锁顺序的业务只要并发就会重现。先改顺序，再谈限流。
