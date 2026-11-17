---
doc_id: dba-005
source_incident: ac-508
service: order-service
env: prod
auth_level: 2
tenant: tenant-internal
error_codes: [52008_REPORT_SQL_SLOW, 50101_SLOW_SQL_DETECTED]
---

# 报表慢 SQL：数据库值班处置卡（源自 ac-508）

## 适用症状

- 报表接口慢，伴随 `52008_REPORT_SQL_SLOW` 与 `50101_SLOW_SQL_DETECTED`。
- 原始事件见 `ac-508`；本卡只补 DBA 值班的核查面，不新增错误码。

## 核查步骤

### 第一步：看报表 SQL 的执行计划

```sql
EXPLAIN SELECT * FROM t_order WHERE created_at BETWEEN '2026-11-01' AND '2026-11-17';
```

关注是否缺少合适的时间索引、是否出现全表扫描。

### 第二步：确认当前没有长事务堆积

```sql
SELECT trx_id, trx_started FROM information_schema.innodb_trx;
```

若存在超长事务，先记录其起始时间，不要在核查阶段改动它。

### 第三步：检查慢日志形态

```bash
grep 'Rows_examined' /var/log/mysql/slow.log | tail -30
```

按扫描行数排序，定位最重的查询模板，交给应用侧评估索引方案。

### 第四步：核对实例负载

```bash
top -b -n 1 | head -20
```

只做只读观测，确认 CPU/IO 是否已成为瓶颈。

## 止血与恢复

1. 报表限流由应用侧执行；DBA 只提供执行计划与慢日志证据。
2. 单表 analyze 由原事故中的应用流程负责，DBA 值班窗口不自行执行写操作。
3. 恢复后在复盘卡中登记扫描行数变化，作为索引评估依据。

## 常见误判

扫描行数大不一定等于索引缺失：先排除长事务与锁等待，再看执行计划。
