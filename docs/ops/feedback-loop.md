# 反馈 → 质量闭环（指标 + 复核队列）

> 补的是 `docs/ops/roadmap.md` 里「反馈闭环：`/api/v1/copilot/feedback` 端点已存在
> （排序回流待接）」的后半句。但"接"的方式是**给人一份可复核的队列**，不是自动改权重。
>
> 为什么：反馈是"人 → 系统"的唯一人工信号，而它的价值在**沉淀成知识**（补文档 / 写复盘），
> 不在自动调参。自动回流会让评测自我循环——系统按自己收到的票数改排序，再用同一批数据证明
> 自己变好了。人机协同在这里不是折中，是唯一站得住的闭环。

## 闭环长什么样

| 环节 | 落在哪 | 谁做 |
|---|---|---|
| 收 | `POST /api/v1/copilot/feedback` → 审计 `ev=feedback` + Prometheus `aiops.feedback{verdict}` | 使用者 |
| 看 | `/actuator/prometheus` 的 `aiops_feedback_total`，按 `verdict` 分序列 | 抓取侧 / 告警 |
| 排 | `offline/feedback_review.py` → 指纹级复核队列（优先级 + 证据） | 值班 / SRE |
| 改 | 补 `offline/corpus/` 文档、起草复盘、`POST /api/v1/admin/reingest` | 人 |

## 指标

`aiops.feedback` 是一个带单标签 `verdict` 的 Counter，导出为 `aiops_feedback_total{verdict="up"|"down"}`。

三个刻意的决定（见 `OpsMetrics#feedback` 与 `OpsMetricsTest`）：

1. **两个 tag 值都预注册为 0**。与 `aiops.requests.failed{reason="none"}` 同一判断：空向量和 0
   在 PromQL 里是两种事实，抓取侧会把"还没人反馈"误读成"指标没接上"。
2. **未知 verdict 静默忽略，绝不新建 tag**。tag 基数由外部输入决定等于把指标面交出去，
   而反馈正是最容易被刷的一类输入；`up|down` 已在 DTO 侧用 `@Pattern` 收口。
3. **只进 Prometheus，不进 `/state` 的 snapshot**。面板契约的键集被 `check_panel_contract.sh`
   锁死，而反馈量的消费者是抓取侧规则与复核队列，不是磁贴。

常用查询：

```promql
# 判负率（窗口内 down / 全部反馈）；分母兜底避免 0/0
sum(rate(aiops_feedback_total{verdict="down"}[30m]))
  / clamp_min(sum(rate(aiops_feedback_total[30m])), 1e-9)

# 有没有人在反馈（两个 0 基线）
sum by (verdict) (aiops_feedback_total)
```

**尚未接告警规则**：阈值要有实测基线才有意义，现在只有 0 基线可读。这不是遗漏，
在 `ops/alerting/rules/` 里加 `FeedbackDownRateHigh` 之前，先让数据跑一段。

## 复核队列

`offline/feedback_review.py` 读审计（默认 `logs/audit.jsonl`，可传目录取轮转片），
把散落的 `ev=feedback` 行归并成**指纹级**条目。

证据从三处取，缺哪处就显式说哪处缺：

| 来源 | 提供什么 | 缺失时 |
|---|---|---|
| `ev=feedback` | `fp`、`verdict`、`note`、时间 | 无反馈即退出 2（"空"不是"错"） |
| `ev=chat`（同 `fp`） | 历史提问、作答/拒答次数、来源、`trace_id`、降级档位 | 写"审计里没有该指纹的请求行"，只剩票数 |
| `logs/alert-bridge.jsonl`（同 `fp`） | `alert_id`、`refs` / `ref_docs`（被引用的 chunk 与文档） | 写"引用清单：无"（手工提问没有台账行，是预期） |

### 优先级是复核顺序，不是准确率结论

| 优先级 | 条件 | 含义 |
|---|---|---|
| P1 重复判负 | 同一指纹 `down ≥ 2` | `OPS.md` §5.1「复盘 → 知识回灌」触发线的直接候选 |
| P2 作答被否 | 只否了一次，且该指纹下系统给过答案 | 错答比拒答更贵：用户拿到的是"看起来对"的东西 |
| P3 仅拒答被否 | 只否了拒答 | 可能是过度拒答（该答没答），也可能是问题超出语料——后续动作完全不同 |

同一指纹同时有 up 票时标「判断有分歧」，提醒复核者先看两边的具体理由，别只看票数。

### 可疑文档：相关性排序，不是根因判定

队列把**被判负答案引用过的文档**按判负票数聚合（引用来自告警台账）。一篇文档出现在这里，
可能是它写错了，也可能是该问题根本没有对应文档、检索只能拿相邻的凑。两种可能都值得复核，
结论由人给。

### 一条必须说清的边界

`ev=feedback` 只有 `fp`，与 `ev=chat` 的 `request_id` **没有关联**——反馈是另一次 HTTP 请求，
自带 request_id。所以数据上不存在"哪一次生成被否"这回事。队列答的是「这个问题被否了几次」，
不假装知道更多。`fp` 本身是归一化指纹（时间戳 / UUID / 数字被掩码），同根因的不同问法会落到
同一指纹——这正是"≥2 次"数得出来的原因，也意味着票数是**问题级**的。

## 用法

```bash
# 默认：写 offline/staging/feedback/queue_<窗口日期>.md
python offline/feedback_review.py

# 只看不落盘
python offline/feedback_review.py --stdout

# 机器可读（看板 / 脚本），JSON 字段见 render_json
python offline/feedback_review.py --json --stdout

# 指定审计目录（取全部 audit*.jsonl 轮转片）与输出目录
python offline/feedback_review.py --audit logs --out /tmp
```

输出文件名由**数据窗口**的日期（UTC）决定，不是今天：同一份审计重跑得到同名文件，换台机器
（或 CI 的 UTC runner）也是同名——报表里的时间同样是 UTC，不带机房时区。
报表是派生件，落在 `offline/staging/`（`.gitignore` 覆盖），随时重生成，不要在上面做人工批注。

退出码：`0` 成功（队列可以为空）；`2` 审计里没有任何反馈行。

## 不做的事

- **不自动改排序 / 权重**：见开头，那会把闭环变成自证。
- **不写语料**：队列只读审计；改文档由人做，走 `postmortem_draft.py` 的检查清单或直接补语料。
- **不按 request_id 归因**：数据里没有这个关联，造一个出来就是编造。
- **不把 up 票当"正确答案"**：up 只说明这次没被否，不作为评测金标。

## 测试

- Java：`FeedbackControllerTest` 5 条（拒绝路径两个出口都不得落痕）、
  `OpsMetricsTest` 11 条（预注册 0 基线、tag 基数收口、仍在面板契约之外）。
- Python：`offline/tests/test_feedback_review.py` 23 条——归并口径（up-only 不进队列、
  未知 verdict 只计数、无 fp 的行不被塞进 unknown 桶）、优先级分档、上下文缺失显式化、
  台账富化、可疑文档排序、坏行容忍、轮转片拼合、空队列/无反馈的区分、JSON 形态、
  `--stdout` 不落盘、表格转义、确定性。
