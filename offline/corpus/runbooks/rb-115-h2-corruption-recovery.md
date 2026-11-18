---
doc_id: rb-115
service: opspilot-gateway
env: local
auth_level: 1
error_codes: [51103_H2_CONCURRENT_WRITE_CORRUPTION]
date: 2026-09-16
---

# H2 库文件损坏（无法启动 / chunk 不可恢复）排查与处置手册（51103_H2_CONCURRENT_WRITE_CORRUPTION）

## 适用症状

- 网关启动即失败：`JdbcSQLNonTransientConnectionException: File corrupted while reading record`
- 底层报 `MVStoreException: File is corrupted - unable to recover a valid set of chunks`
- `Failed to obtain JDBC Connection` / Hikari `checkFailFast` 在启动早期就抛错
- 常见前情：刚刚**在另一个进程里**写过同一个库文件（CLI 直连、另一个实例、备份/拷贝动作），
  或刚刚非正常终止过持有该库的进程

## 排查步骤

### 第一步：确认是文件损坏，不是锁定或权限

1. 读异常链：`File corrupted` + `MVStoreException` 才是文件级损坏；只有 `Locked` / `Access denied`
   属锁或权限问题（把持有者停掉即可，**不要**删文件）
2. 看是否有 `.lock.db` 残留与同目录 `.trace.db`：后者含最近一次失败的完整栈，是取证入口
3. 检查是否存在第二个写者：另一个网关实例、宿主 CLI、容器与宿主同时持有同一挂载目录

### 第二步：先保全证据再动手

4. **复制**损坏文件留档：`cp data/users.mv.db data/users.mv.db.corrupt-<日期>.bak`
   （不要原地改名了事，也不要直接删——"删掉就当没事"会让复发时无迹可查）
5. 记录时间线：谁在什么时候写过它、什么时候停的、什么时候开始报错

### 第三步：评估可恢复性（别急着宣布丢失）

6. 试官方恢复工具：
   `java -cp h2-<版本>.jar org.h2.tools.Recover -dir data -db users`
7. 看产出：正常会生成 `users.h2.sql`（含 CREATE/INSERT，可直接回灌）；
   若只写出错误注释、或 `.mv.txt` 里出现明显荒谬的 chunk 长度（如单页 2GB），即**不可恢复**
8. 评估数据可重建性：本系统 H2 只存 `users` 表（账号 + token 版本号），
   演示账号全部可由 `scripts/seed_demo_users.sh` 重建 → 重建优于抢救

### 第四步：只读预检（先取证再评估）

在做任何恢复/重建动作前，先用只读命令确认现场状态（不写库、不删文件）：

```bash
ls -la data
stat data/users.mv.db
jps -l
grep -c "MVStoreException" data/*.trace.db
```

以上命令只读取证，不改变库文件状态；据此判断写者是否仍在、库文件是否存在与大小异常。
后续恢复动作仍按第三步与止损操作执行。

## 止损操作

1. 停止一切对该库的写入（停掉所有持有者：容器网关与宿主进程都要停）
2. 留档损坏文件（见上），再删除损坏的 `users.mv.db` 与伴随的 `.trace.db`
3. 重建：直接启动服务（`schema.sql` 在启动时 `CREATE TABLE IF NOT EXISTS` 自动建表），
   随后 `bash scripts/seed_demo_users.sh` 重播种账号
4. **先备份、后动手**：重建后立刻做一次 `backup/users-<日期>.zip`；
   若库里存在不可重建的数据，则必须先尝试第 3 步的恢复工具，禁止未备份即重建
5. 复原后核对：账号列表齐全、能登录换 token、`/admin/state` 正常
6. 恢复期影响：全部存量 token 失效（token_ver 重置），需要通知使用者重新登录

## 升级路径

- 反复损坏 → 按"存在并发写者"处理：排查是否有第二个实例/CLI 在写，并把写路径收敛到单点
  （容器形态下用 HTTP 管理面，宿主形态下先停服务再用 CLI）
- 涉及不可重建数据 → 立即按数据事故升级，恢复工具与备份双线并行，不要先删文件
- 需要"多进程安全共写"→ 属存储选型问题（H2 文件库非为跨挂载点并发设计），评估换用真正的
  客户端-服务端数据库，按 ADR 流程评审
