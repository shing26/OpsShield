# L1 受控行动契约——设计规格 v1.1（ADR-0028 配套，已含评审裁决）

> 决策与备选取舍见 [ADR-0028](../adr/0028-l1-controlled-action-contract.md)；
> **2026-10-25 设计评审定稿**：三个开放问题已裁决（§0），六条评审增补已并入（标 ⛳）。
> **当前状态：设计定稿；实现顺序见 §9。**

## 0. 三个开放问题的裁决（2026-10-25）

| # | 问题 | 裁决 | 关键约束 |
|---|---|---|---|
| 1 | 确认入口 | **面板优先**，飞书 v1.1 | 面板在已认证会话内点击：确认事件与身份强绑定、审计链短、零外部回调面。飞书卡片会引入回调伪造/重放/跨租户新攻击面——L1 必须先证明干净 |
| 2 | 自动化对接 | **通用 webhook + reference verifier**，Rundeck v1.1 | 验收目标是"对方验签通过、重放/篡改/过期被拒"，与 echo/Rundeck 无关；reference verifier（约 50 行）本身是对外资产 + 红队靶子，证明契约可移植 |
| 3 | 确认 URL | **一次性 token + 短 TTL（5–10min）+ 已认证会话绑定；不绑 IP** | capability URL 只定位不承载权限：泄露后攻击者没有已认证会话点不下确认。绑 IP 在运维场景误伤（4G/WiFi 切换）大于收益。确认页必须展示 op_id + payload 哈希 + 命令原文 + 影响范围 + 倒计时，防手滑 |

Q3 隐含要求：URL token 与 op_id 一一对应、**消费即焚**；确认会话用户须与 propose 时身份匹配——
跨租户代确认需额外授权并单独审计事件（`action_confirmed_proxy`）。

## 1. 契约 schema（草案）

~~~json
{
  "op_id": "act_01HZX...",            // 幂等键（ULID），防重放；存储后端见 §4 ⛳
  "action_class": "L1-write-low",      // L0-read | L1-write-low（L2 预留）
  "n": 3, "step": "恢复动作",          // 与 L0 Action 同源
  "command": "kubectl rollout restart deployment/order-api -n prod",
  "lang": "bash",
  "ref": "rb-030::恢复动作",           // 逐字来源（不变式：command ⊆ ref chunk 原文）
  "breadcrumb": "...", "service": "order-service",
  "auth_level": 3,                      // 文档块密级
  "proposed_by": "sre-full",           // 提案人
  "proposed_at": "...",
  "state": "proposed",
  "confirm": {
    "required": true,
    "window_s": 900,                    // 确认窗口 15 分钟
    "confirm_token": "一次性，消费即焚，TTL=窗口",
    "confirmed_by": null, "confirmed_at": null   // ⛳ 必须 ≠ proposed_by principal
  },
  "dispatch": {                          // confirmed 后才生成
    "target": "webhook",
    "payload_sha256": "...",
    "hmac": "...",                     // constant-time 验签（§6）
    "expires_at": "...",
    "idempotency_key": "op_id",        // ⛳ 共享存储，重复 dispatch 返回 409
    "result": null
  }
}
~~~

## 2. 状态机（含 ⛳ confirmer 守卫与补偿语义）

~~~
proposed ──confirm(人)──> confirmed ──dispatch(签名签发)──> dispatched ──callback──> executed|failed
    │  ↑窗口过期/撤销          │人工撤销                    │窗口过期
    └──> expired/revoked <─────┘<────────────────────────── ┘(dispatched 前可 revoke)
~~~

推进规则：

- `proposed → confirmed`：**仅**人经面板确认入口（已认证会话 + 一次性 token）；
  Agent/MCP/告警桥没有任何端点能推进此状态——构造保证；
- ⛳ **confirmer ≠ proposer 硬守卫**：`confirmed` 事件的 actor 必须与 `proposed` 事件的
  actor 是**不同 principal**，且 confirmer 必须持有对应租户的 `action:confirm` 权限。
  被注入的 Agent 自己 propose 自己确认（哪怕有独立凭据）在此被状态机直接拒绝；
- ⛳ 跨租户代确认（值班 manager）：单独审计事件 `action_confirmed_proxy`，不复用普通确认事件；
- `confirmed → dispatched`：系统自动（签名 + webhook），无需人再操作；
- ⛳ **dispatch 失败的补偿语义**：`dispatched → failed` 后**不可自动重试**（自动重试 =
  绕过人确认的后门）；重新执行必须走**新 op_id 重新提案 + 重新人确认**；
  旧 op_id 连同失败原因进 WORM 永久留痕；
- 同 op_id 重复 confirm/dispatch → **409**（幂等语义，存储后端见 §4）。

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

## 3.5 三条契约裁决（2026-10-25 签名派发评审）

| # | 契约 | 裁决 |
|---|---|---|
| 1 | 回调终态语义 | **选 A**：执行系统只发一次终态回调（执行侧保证）；OpsShield 首次定终态，后续回调（含 failed→executed 修正）一律 409 **并落审计 `action_callback_conflict`**（带现有终态，不静默） |
| 2 | 回调防重放 | HMAC 消息 = `callback:op_id:status:ts:nonce`；ts 窗 ±300s、nonce 一生一次（dispatch_nonces 表）；**domain separation**：派发签名加 `dispatch:` 前缀——两方向签名不可互换 |
| 3 | 派发期限 | **confirm 时即写 dispatch_expires_at**（confirmed_at + 15min）；过期不可派发（惰性转 expired，须新 op_id + 重新人确认）——不许"诚实停在 confirmed"变成"静默堆积" |

另：灰度总开关 `opspilot.l1.enabled` **默认 false**（红队全绿前任何环境不默认开）；webhook connect 3s / read 5s、不跟随重定向（3xx 即 failed）。

## 4. 幂等与确认存储（⛳ 评审增补）

- op_id 幂等键需要**持久化 + 共享后端**：单机 H2 起步，多副本部署迁 Redis/Postgres
  （表结构按可迁移设计）；分布式下没有共享幂等存储，重放防护就是纸糊的；
- TTL：**≥ dispatch 过期时间 × 2**（过期窗口之后仍能识别重放）；
- 语义：同一 op_id 重复 confirm / 重复 dispatch → **409 CONFLICT**（区别于其他失败码）；
- 确认一次性 token：与 op_id 一一对应、消费即焚——同样进该存储（原子消费，防并发双击）。

## 5. 端点与时序

~~~
Agent(MCP propose_action / 告警桥)
  │ POST /api/v1/copilot/actions/propose   （L>=2 用户；body 含 op 上下文+ref）
  ▼
OpsShield：classify → proposed 落库 → 审计 ev=action_proposed（进 WORM ⛳）
  │ 返回 {op_id, confirm_url(一次性 token), expires_at}
  ▼
值班人（面板，已认证会话 + 一次性 token）──confirm──> confirmed（ev=action_confirmed，进 WORM ⛳）
  ▼
OpsShield：payload + HMAC → POST 自动化系统 webhook（ev=action_dispatched，进 WORM ⛳）
  ▼
自动化系统：reference verifier 验签（§6）→ 执行 → callback（ev=action_result，进 WORM ⛳）
~~~

确认页必须展示：op_id + payload 哈希 + 命令原文 + 影响范围 + 倒计时（防手滑）。

## 6. reference verifier（⛳ 红队靶子 + 对外资产，与派发同步交付）

约 50 行的独立验签器（任何自动化系统侧可嵌入的参考实现）：

- **constant-time 比较**：HMAC 用 `hmac.compare_digest`，绝不用 `==`；
- **校验顺序固定**，任一失败返回**可区分状态码**（红队据此分辨打穿到哪一层）：
  1. 签名不符 → **401**；
  2. payload 哈希 ≠ 内容 → **422**；
  3. 过期 → **410**；
  4. op_id 已签发（幂等） → **409**；
- **verifier 本身入库**（offline/ 下与 dataset_manifest 同构的 sha256 清单）：
  防"改 verifier 让红队变绿"——verifier 是靶子，靶子自己必须可验篡改。

## 7. 审计与 WORM（⛳ 门禁增补）

- L1 全部事件（proposed/confirmed/dispatched/failed/expired）+ verifier 验签结果
  都进 AuditService（SHA-256 链）→ WORM 副本；
- **新增门禁：任一 L1 事件缺 WORM 记录即 FAIL**（出事后能从 WORM 重放整条链取证）；
- 跨租户代确认单独事件类型 `action_confirmed_proxy`。

## 8. 审计事件（ev=）

| 事件 | 关键字段 |
|---|---|
| `action_proposed` | op_id/ref/command(逐字)/action_class/proposed_by |
| `action_confirmed` | op_id/confirmed_by(≠proposed_by)/latency_ms |
| `action_confirmed_proxy` | op_id/确认人/被代确认人（跨租户代确认专用） |
| `action_expired` | op_id |
| `action_dispatched` | op_id/target/payload_sha256 |
| `action_result` | op_id/exit_status/duration_ms |
| `action_rejected` | op_id/reject_stage(classify|verify)/reason |
| `action_verify_rejected` | op_id/阶段(401|422|410|409)（verifier 侧） |

全部走既有 AuditService（SHA-256 链 + WORM 副本）——L1 的每一步都在不可变审计里。

## 9. 实现顺序（⛔ 评审修正：verifier 与派发**同步交付**）

~~~
红队操作面用例先行写好（含 verifier 靶子用例：重放/篡改/过期——verifier 可后到）
  → schema/状态机/双白名单（含 confirmer≠proposer 守卫、幂等共享存储）
  → 确认入口（面板；一次性 token + 会话绑定）
  → 签名派发 + reference verifier（同步——verifier 既是派发的验收目标也是红队靶子）
  → 灰度：rollout restart 一族 + ⛳ 目标资源 label 白名单
~~~

⛳ **灰度额外收敛**：目标资源必须带指定 label（如 `opshield-l1=true`）才允许 L1 契约——
避免一个手滑重启整个 namespace。成本极低、收益极高。

## 10. 红队扩写清单（实现前必须先行合入并全过）

| 类别 | 用例 | 判据 |
|---|---|---|
| 重放 | 同 op_id 二次 confirm/dispatch | 409 |
| 过期 | 窗口外 confirm / expires_at 外验签 | 拒（confirm=4xx / verify=410） |
| 篡改 | 确认后 payload 改一字符 | verify 422 |
| 签名 | 错 key 签名 | verify 401 |
| 越权确认 | 同 principal 自确认 / L1 用户 confirm L3 提案 | 状态机拒 |
| 注入不自动确认 | 注入 prompt 直接打 propose/confirm 链路 | 永远停在 proposed（无人在环推不动） |
| 契约注入 | 语料 L1 小节里藏第二命令/管道 | 四层判定拒；只放白名单前缀本体 |

## 11. 开放问题 → 已全部裁决（2026-10-25）

~~确认入口 / 对接顺序 / 确认 URL~~ → 见 §0 裁决表。新增开放问题（若有）在此追加。