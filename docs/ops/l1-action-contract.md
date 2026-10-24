# L1 受控行动契约——设计规格（ADR-0028 配套）

> 决策与备选取舍见 [ADR-0028](../adr/0028-l1-controlled-action-contract.md)；
> 本页是实现规格：schema、状态机、时序、审计事件、红队扩写清单与开放问题。
> **当前状态：设计定稿待评审；实现未开始。**

## 1. 契约 schema（草案）

~~~json
{
  "op_id": "act_01HZX...",            // 幂等键（ULID），防重放
  "action_class": "L1-write-low",      // L0-read | L1-write-low（L2 预留）
  "n": 3, "step": "恢复动作",          // 与 L0 Action 同源
  "command": "kubectl rollout restart deployment/order-api -n prod",
  "lang": "bash",
  "ref": "rb-030::恢复动作",           // 逐字来源（不变式：command ⊆ ref chunk 原文）
  "breadcrumb": "...", "service": "order-service",
  "auth_level": 3,                      // 文档块密级
  "proposed_by": "sre-full",           // 提案人（Agent 以该用户身份检索命中）
  "proposed_at": "...",
  "state": "proposed",
  "confirm": {
    "required": true,
    "window_s": 900,                    // 确认窗口：15 分钟过期
    "confirmed_by": null, "confirmed_at": null
  },
  "dispatch": {                          // confirmed 后才生成
    "target": "webhook",               // v1 通用 webhook；后续可枚举 rundeck/argo
    "payload_sha256": "...",           // 内容绑定：改一个字符验签即失败
    "hmac": "...",                     // HMAC-SHA256(OP_ID+SHA+EXP, DISPATCH_KEY)
    "expires_at": "...",
    "result": null                      // 自动化系统回调回填
  }
}
~~~

## 2. 状态机

~~~
proposed ──confirm(人)──> confirmed ──dispatch(签名签发)──> dispatched ──callback──> executed|failed
    │  ↑窗口过期/撤销          │人工撤销                    │窗口过期
    └──> expired/revoked <─────┘<────────────────────────── ┘(dispatched 前可 revoke)
~~~

推进规则：

- `proposed → confirmed`：**仅**人经确认入口（面板按钮/IM 卡片按钮）；
  Agent/MCP/告警桥没有任何端点能推进此状态——构造保证，不是过滤；
- `confirmed → dispatched`：系统自动（签名 + webhook），无需人再操作；
- 窗口（900s）内未确认 → expired；confirmed 后未签发 → revocable；
- 终态不可逆；同 op_id 一生只允许一次成功签发（幂等）。

## 3. 判定（ControlledCommandPolicy，伪码）

~~~
def classify(command_text, chunk_meta):
    # 第一关：ReadOnly 四层注入判定（L1 不豁免——写动词不豁免注入通道）
    if not readonly_four_layer_passes(command_text):
        return "REJECT"                 # 与 L0 同口径拒
    # 第二关：L0 读白名单命中 → L0-read（现状不变）
    if readonly_whitelist_match(command_text):
        return "L0-read"
    # 第三关：L1 双确认——文档声明 + 窄闭集前缀
    if chunk_meta.action_class == "L1-write-low" \
       and l1_prefix_match(command_text):          # kubectl rollout restart|undo / systemctl restart <白名单>
        return "L1-write-low"
    return "REJECT"
~~~

要点：**L1 不是 L0 的放宽，是另一条更窄的通道**——注入判定两层全过是共同前提；
文档声明解决"这条写命令是有意的恢复动作"的语义确认；前缀闭集解决"写到什么程度"的边界。

## 4. 端点与时序

~~~
Agent(MCP propose_action / 告警桥)
  │ POST /api/v1/copilot/actions/propose   （L≥2 用户；body 含 op 上下文+ref）
  ▼
OpsShield：判定 classify → proposed 落库 → 审计 ev=action_proposed
  │ 返回 {op_id, confirm_url, expires_at}
  ▼
值班人（面板/IM 卡片）──POST /api/v1/copilot/actions/confirm──> confirmed（ev=action_confirmed）
  ▼
OpsShield：生成 dispatch payload + HMAC → POST 自动化系统 webhook（ev=action_dispatched）
  ▼
自动化系统：验签 → 执行 → POST /api/v1/integrations/executor/callback（ev=action_result）
~~~

安全细节：

- **验签**：自动化侧用共享 DISPATCH_KEY 验 HMAC（op_id+payload_sha256+expires_at）；
  payload 任一字符被改、过期、op_id 重放，验签皆失败——OpsShield 不需要信任网络；
- **确认凭据**：confirm 端点要求确认人自己的 JWT（非提案 token）；
  提案人与确认人可以是同一人（小团队现实），但审计分字段记录，事后可查"自提自确"率；
- **频控**：confirm 端点挂独立限流（确认疲劳是真实风险，泄漏的确认比泄漏的检索危险得多）。

## 5. 审计事件（ev=）

| 事件 | 关键字段 |
|---|---|
| `action_proposed` | op_id/ref/command(逐字)/action_class/proposed_by |
| `action_confirmed` | op_id/confirmed_by/latency_ms（提案→确认耗时，看确认疲劳） |
| `action_expired` | op_id |
| `action_dispatched` | op_id/target/payload_sha256 |
| `action_result` | op_id/exit_status/duration_ms（自动化回调） |
| `action_rejected` | op_id/reject_stage(classify|verify)/reason |

全部走既有 AuditService（SHA-256 链 + WORM 副本）——L1 的每一步都在不可变审计里。

## 6. 红队扩写清单（实现前必须先行合入并全过）

| 类别 | 用例 | 判据 |
|---|---|---|
| 重放 | 同 op_id 二次 confirm/dispatch | 拒（幂等） |
| 过期 | 窗口外 confirm / expires_at 外验签 | 拒 |
| 篡改 | 确认后 payload 改一字符 | 验签失败 |
| 越权确认 | L1 用户 confirm L3 文档的提案 | 拒 |
| 注入不自动确认 | 注入 prompt 直接打 propose/confirm 链路 | 永远停在 proposed，无人在环推不动 |
| 契约注入 | 语料 L1 小节里藏第二命令/管道 | 四层判定拒；只放白名单前缀本体 |

## 7. 开放问题（实现前须裁决）

1. **确认入口**：v1 先面板按钮还是先 IM（飞书卡片）？IM 更贴值班动线，但要多一跳凭据；
2. **自动化系统对接顺序**：先通用 webhook（沙箱用 echo server）还是直接绑定 Rundeck？
3. **状态机存储**：单机 H2 够（与 users 同库）；多副本时迁 Postgres——现在建表就按可迁移设计；
4. **L1 白名单治理**：白名单本身入库版本化，变更走 ADR（哪怕一行）；
5. **MCP propose_action 的返回形态**：确认 URL 放 tool 结果文本里给 Agent 转述给人——
   Agent 拿不到确认凭据，但 URL 本身是"能力"——是否绑 IP/一次性？