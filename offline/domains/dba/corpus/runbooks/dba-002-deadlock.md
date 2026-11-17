---
doc_id: dba-002
source_incident: rb-002
service: inventory-service
env: prod
auth_level: 2
tenant: tenant-internal
error_codes: [50013_DB_DEADLOCK, 50101_SLOW_SQL_DETECTED]
---

# 库存死锁：数据库值班处置卡（50013_DB_DEADLOCK）

## 适用症状

- 库存扣减接口间歇返回 `50013_DB_DEADLOCK`，重试后通常成功。
- 慢查询告警伴随 `50101_SLOW_SQL_DETECTED`；原始事件见 `rb-002`。

## 核查步骤

### 第一步：取 InnoDB 状态中的最近死锁

```sql
SHOW ENGINE INNODB STATUS\G
```

只取死锁段落，记录两个事务的加锁顺序与等待关系。

### 第二步：确认当前锁等待

```sql
SELECT * FROM information_schema.innodb_lock_waits;
```

```sql
SELECT * FROM information_schema.innodb_trx ORDER BY trx_started;
```

这两条只读语句帮助确认是否仍在等待；不要在此阶段执行 `FOR UPDATE` 类探测。

### 第三步：核对热点行的执行计划

```sql
EXPLAIN SELECT sku_id, version FROM t_inventory WHERE sku_id = 1001;
```

关注是否走了唯一索引、是否出现全表扫描。

### 第四步：核对应用侧重试日志

```bash
grep -c 'Deadlock found when trying to get lock' /var/log/app/inventory.log
```

统计单位时间内的死锁次数，判断是偶发还是持续。

## 止血与恢复

1. 持续死锁时由应用侧暂停批量扣减入口，不要直接在数据库里 `KILL` 事务。
2. 按 `pm-002` 的加锁顺序约定还原批量任务的更新顺序，再逐步放开流量。
3. 保留 `SHOW ENGINE INNODB STATUS\G` 输出作为复盘证据。

## 常见误判

慢查询告警不等于死锁：先 `SHOW ENGINE INNODB STATUS\G` 再判断，不要凭延迟数字直接下结论。
