# Bridge 与 Producer 共用行为保持的告警投递 seam

状态：accepted（2026-10-20）

上下文：2026-10-20 architecture review 的 C2 指出，`scripts/alert_bridge.py` 与
`offline/alert_producer.py` 各自实现 token 刷新、quota/hour 闸门、401 处理、响应/引用投影与
JSONL 台账追加。两部分目前"看起来相似"，实际在闸门顺序、配额来源、冷却与登录失败控制流上不同；
继续并排维护会让同一投递事实在两端逐渐漂移。Alertmanager 翻译与 Producer 异常判定属于各自输入面，
不应被吸入投递层。C1 的 ChatSink 已完成，C3 仍受 OPS §5 / §5.1 触发线约束，均不在本决策范围内。

决策：Bridge 与 Producer 共用一个行为保持的告警投递深模块。一次投递尝试只调用
`deliver(attempt, policy) -> DeliveryOutcome`；登录与 token 刷新、发送前闸门、网关请求、401 后的
token 失效处理、响应/引用投影以及台账追加都收在该入口内部。实现落点为 `offline/alert_delivery.py`：
Bridge 已把 `offline/` 加入导入路径，Producer 本身也在该目录。调用方只准备已翻译的尝试和来源策略，
并解释结果。Producer 为异常检测所需的认证状态读取复用同一 session，但该读取不是投递生命周期的一部分。

`attempt` 携带发送所需的请求体、可选的 `trace_id` 与来源特有台账上下文；`policy` 携带来源闸门顺序、
quota 的观察方式（实时读取或本轮快照）、hour budget、quota reserve、可选 cooldown 键与窗口、dry-run，
以及登录失败/传输失败时是继续还是中止来源循环。登录、quota 读取、引用投影与台账追加不单独暴露为公共
组合 API；共享 `session()` 只额外提供 Producer 检测所需的只读状态获取。

投递结果封闭为四态：`Skipped`（请求未发出）、`Delivered`（已收到 2xx）、`Rejected`（已收到非 2xx，
包括 401/429）与 `TransportFailed`（未拿到响应）。现有 JSONL 字段 `emit`、`sent_ok`、`skip`、`http`、
`fp`、`cache_hit`、`deduplicated`、`degradation_level`、`refs`、`ref_docs` 及耗时字段仍按原义写出；
领域结果只负责消除布尔字段组合歧义，不改台账消费契约。来源特有的台账上下文由调用方提供，但公共投递
字段以 seam 的结果为准，避免来源覆盖事实。

来源策略不归一化。Bridge 保持"空 query → dry-run → hour budget → 实时 quota"的顺序；Producer 保持
"hour budget → 本轮 quota 快照 → cooldown → dry-run"的顺序。Cooldown 只作为可选闸门：Bridge 不启用，
Producer 保持 `(rule, service)` 键和原有计时语义。登录失败也不强行统一：Bridge 记 `login_failed` 后继续
同批其他告警，Producer 继续累计失败、退避并按既有上限退出；401 不新增自动重试，只沿用"清 token，
下一次尝试或下一轮重登"的自愈路径。登录失败分类按来源显式选择：Bridge 只把 401/403（或带同值 `code`
的异常）视为凭据失败，其他登录异常仍是传输失败；Producer 将登录端点的任何 `HTTPError` 视为需退避的
登录失败。传输异常以 `TransportFailureHandling.CONTINUE / RAISE` 进入来源策略：Bridge 记录单条失败后
继续批次，Producer 不写候选行并由适配器按 `RAISE` 将本轮转为 `unreachable`，后续轮次仍继续。

理由：① 这些重复不是四个独立的工具函数，而是一条完整的投递生命周期；若只抽 login/quota/ledger 小工具，
调用方仍需自行排列顺序、理解 401 与结果字段，重复和漏用不会消失。② 四态结果把"未发、已发且成功、
已发但被拒、未拿到响应"分开，消除 `emit=true` 同时可能表示 2xx、429 或异常返回的组合歧义。
③ 来源策略显式保留当前差异，满足本轮行为保持约束，也避免借架构整理偷偷改变告警速率、配额保护或
退出语义。④ 台账 writer 共用可以让 group-cap 丢弃、计数器复位和不可达等非投递记录也走同一追加纪律，
但领域上仍与投递结果分开。

备选与否决：**只抽 `localapi` 之上的认证、配额、投影、台账四个 helper**——调用方仍要重写闸门顺序和
异常控制流，不解决 C2；**强行统一闸门顺序和失败控制流**——会改变 Bridge/Producer 的发送时机与退出语义，
本轮明确要求行为保持；**让 Alertmanager 分组或 Producer 检测进入 seam**——会把外部协议翻译与异常判定
耦合进投递层，超出 seam 职责；**自动重试 401/429/5xx**——现有实现刻意不重试，告警风暴下重试会放大压力；
**借 C2 同时推进 ChatSink 或网关原生告警端点**——分别属于已完成 C1 与受触发线约束的 C3。

后果：Bridge 与 Producer 的重复投递实现将收为一个深模块，新增来源只需提供 attempt、policy 与台账上下文，
不能再自行解释 token、401、引用帧或台账格式。现有台账 schema、闸门顺序、单实例约束、登录失败行为和
无自动重试语义保持不变。测试需同时覆盖共享 seam 的四态与来源策略矩阵，并用变异验证锁住闸门顺序、
401 清 token、引用投影和每行台账唯一追加；Alertmanager 映射测试与 Producer 检测测试继续各自独立。

相关：ADR-0011（自举告警源）、ADR-0018（告警身份）、ADR-0019（事故身份与上游边界）、
ADR-0023（canonical 术语与对外面双轨）。
