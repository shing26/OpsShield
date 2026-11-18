---
doc_id: rb-116
service: opspilot-gateway
env: local
auth_level: 1
error_codes: []
date: 2026-11-19
---

# JVM 运行时诊断：jcmd 子命令到既有工具的等价映射

## 适用症状

- 需要看线程栈、类直方图、堆占用、GC 统计、进程命令行，但排障入口只能给只读命令清单
- 手里只有历史 jcmd 指引（`jcmd <pid> Thread.print`、`jcmd <pid> VM.system_properties` 等），
  而这些形态在 L0 白名单里已全拒（2026-11-18 删除死面，见 ADR-0017 附记）
- 目标 JVM 未预埋 NMT，拿不到本地内存明细；或有人要求触发 GC、改管理代理配置、挂 JVMTI 代理

## 排查步骤

历史背景只作说明：jcmd 的动词在第三个 token（PID 之后），第二 token 是数字 PID，与只读策略
"第二 token 收敛"的形态不兼容；恢复它不是加数据键，而是机制级扩权。Lead 评估后选 A——
维持 L0 不恢复 jcmd，以下用白名单内既有工具给出等价命令。本节围栏块里**只有**既有工具，
jcmd 形态一律行内引用；映射与口径的单一事实源是 ADR-0017 的 2026-11-19 附记。

### 第一步：线程与堆栈

`jcmd <pid> Thread.print` 的等价命令是 `jstack <pid>`，抓当前线程栈看阻塞与死锁：

```bash
jstack <pid>
```

### 第二步：系统属性与 JVM 旗标

`jcmd <pid> VM.system_properties` 与 `jcmd <pid> VM.flags` 分别看系统属性和启动旗标。
用下列显式旗标形态；`jinfo <pid>` 不带参数会一次打印 sysprops + flags，JDK 17+ 有弃用告警，
仅作历史指引的行内说明，不作为推荐写法：

```bash
jinfo -flags <pid>
```

### 第三步：类直方图与堆占用

`jcmd <pid> GC.class_histogram` 对应类直方图，`jcmd <pid> GC.heap_info` 对应堆配置与 GC 统计：

```bash
jmap -histo <pid>
jmap -heap <pid>
jstat -gc <pid>
```

`jmap -dump:*` 会把堆写成文件，属写语义，不在只读面内。

### 第四步：命令行与版本口径

`jcmd <pid> VM.command_line` 看进程命令行，用 `ps -fp <pid>` 即可；
`jcmd <pid> VM.uptime` 与 `jcmd <pid> VM.version` 的等价口径见 ADR-0017 附记的
`jinfo <pid>` 行（该无参形态同样受 JDK 17+ 弃用告警约束，按附记口径处理）：

```bash
ps -fp <pid>
```

## 无等价物与永不放行

- `jcmd <pid> VM.native_memory` **无等价物**：它要求目标 JVM 启动时预埋 NMT
  （`-XX:NativeMemoryTracking=summary|detail`），没预埋就是取不到数据，属于可观测性前置条件，
  不是把 jcmd 放回白名单能解决的。
- **永不放行**：`GC.run`（触发 GC，写语义）、`ManagementAgent.*`（改管理代理配置）、
  `JVMTI.*`（native 代理/插桩面）。这三类即使将来另立扩权评审也不进只读面。

## 升级路径

- 拿不到本地内存明细 → 先确认目标 JVM 是否已预埋 NMT；没有就走可观测性评审申请预埋，
  不要用触发式命令替代。
- 现有工具的等价命令也取不到数据（进程不存在、权限不足、容器命名空间隔离）→ 按普通
  取证问题升级，先确认 PID 与命名空间，不要因此改 L0 白名单。
- 若出现"等价映射确实无法满足"的新场景 → 另立扩权评审，本手册与 ADR-0017 附记为基线。
