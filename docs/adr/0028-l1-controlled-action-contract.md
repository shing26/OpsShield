# ADR-0028：L1 受控行动契约——只读底座向"经人确认的低危恢复动作"延伸

日期：2026-10-25 ｜ 状态：已接受（实现待评审）｜
关联：[ADR-0013](0013-trust-base-for-agents-not-an-agent.md)（不第 N 个 agent）、
[ADR-0017](0017-read-only-action-contract.md)（只读契约）、[roadmap](../ops/roadmap.md) 中期 #5、
[红队套件](../ops/redteam.md)（前置门禁，2026-10-24 已闭环）

## 背景

路线图中期的核心跃迁：把能力边界从"只读建议"推进到"受控执行"。前置条件已满足：
红队套件 6 类 21 用例闭环（含 MCP 端到端），泄漏硬 0 门禁进 CI。生产排障的真实诉求是：
Agent 说"怎么办"之后，值班人还要手工敲一遍 kubectl rollout restart——价值链在
"建议"与"执行"之间断开，而断开的原因是安全叙事，不是能力。

## 决策

1. **分级进契约，不进新面**：Action 增加 action_class 字段
   （L0-read｜L1-write-low），同一条抽取流水线、同一套引用与审计，不另起服务。
2. **L1 的判定 = 文档声明 + 引擎白名单，双确认**：
   - 命令仍逐字来自 chunk 原文（ADR-0017 不变式不变）；
   - chunk 的文档**显式标注**该小节为受控恢复动作（action_class: L1-write-low），
     未标注的写命令一律维持 L0 拒绝——不是语料里的写命令都自动成为契约；
   - ControlledCommandPolicy：先跑 ReadOnly 四层注入判定（**L1 不豁免**——
     rollout restart 后面不能藏 ; rm，替换/递归/嵌套判定照旧），
     再按 L1 窄闭集前缀匹配（kubectl rollout restart|undo、systemctl restart <白名单>），
     两关全过才标 L1；任何一关不过维持拒。
3. **OpsShield 仍不执行**：确认后签发 **HMAC 签名的 dispatch payload**
   （内容哈希绑定 + op_id 幂等 + 过期时间），经 webhook 推给既有自动化系统
   （Rundeck/Argo/Ansible）。验签与执行在对方，OpsShield 零执行权限。
4. **人始终在环**：L1 状态机 proposed → confirmed → dispatched → executed|failed|expired|revoked；
   只有人的确认能把 proposed 推进到 confirmed（面板/IM 点确认，记录 confirmed_by）；
   **prompt 注入无论怎么写都产生不了 confirmed 状态**——这是设计的构造保证，不是防御承诺。
5. **MCP 面只加 propose，不加 execute**：propose_action 工具返回确认链接给人；
   Agent 全程拿不到可执行凭据，也推进不了状态机。
6. **红队前置（已裁决的顺序，不可反）**：实现合入前，红队套件先扩操作面用例并全过：
   重放签发、过期确认、内容篡改验签、越权确认、注入诱导自动确认。0 容忍照旧。

## 备选与否决

| 备选 | 否决原因 |
|---|---|
| OpsShield 内嵌执行引擎（直接跑 kubectl） | 持有执行权限 = 只读可信底座定位自毁；凭据面/攻击面爆炸 |
| LLM 判定 L1/L2 或判定确认合法性 | judge 即攻击面——与红队纪律同一裁决 |
| 独立 L1 微服务 | 认证/审计/配额/限流全部重造；碎片化拖累可信叙事 |
| 所有写命令自动升级 L1 | 语料里未声明的写命令可能是笔误或示例——文档作者显式声明 + 评审是语义确认 |

## 后果

- 正面：价值链从"知道怎么办"延伸到"经人确认后可执行"；Agent 决策循环第一次能闭环
  恢复动作，而安全叙事完整（人确认 + 零执行权限 + 全链审计 + 红队门禁）；
- 代价：新增状态机存储（H2 表 + Redis 确认窗口 TTL）、2 个端点 + 1 个签名回调、
  HMAC 密钥管理；确认疲劳风险（人无脑点确认）→ 靠确认窗口、频控与审计分析缓解；
- 已知保守偏差：四层判定的词法保守性对 L1 同样生效（合法写用法会被拒），
  与 ADR-0017 的取舍同源——"少给一条"好过"给错一条"。

## 验收路径（顺序固定）

1. 红队扩写操作面用例（重放/过期/篡改/越权确认/注入不自动确认）→ 全过入 CI；
2. schema + 状态机 + ControlledCommandPolicy 实现（单测钉判定）；
3. propose/confirm 端点 + 面板/IM 确认入口；
4. dispatch 签名 + 自动化回调验签（对接沙箱自动化）；
5. 灰度：先只放 kubectl rollout restart 一族。