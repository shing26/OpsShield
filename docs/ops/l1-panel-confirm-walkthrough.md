# L1 面板确认动线——活体验收记录（2026-11-05/06）

> ADR-0028 验收路径第 3 步（propose/confirm 端点 + **面板**确认入口）的最后一块：
> 服务端守卫与全链路已被单测 + 红队 32 用例覆盖（production-gate §4 路线图行 2026-10-25 绿），
> 但审计里所有 `action_confirmed` 的 `via` 都是 `action-api`——**面板这条 UI 动线此前从未被活体走过**。
> 本页把这次走链的证据固化。配套设计：[l1-action-contract.md](l1-action-contract.md) §0 裁决 1/3。

## 0. 结论

- **链路验证通过**：提案（sre-full）→ 面板待确认列表（sre-manager 视角）→ 同形状确认请求 →
  终态返回 → 审计三连（proposed / confirmed / not_dispatched），全程零人工守卫绕过。
- **发现两个真缺口**（见 §3）：审计 `via` 不区分面板与 API；派发失败返回态的 UI 呈现未经验证。

## 1. 环境（可复现）

memory 形态隔离工作区（与 redteam.sh L1 栈同构，不动账号库与仓库语料）：

~~~bash
# 独立目录：jar 副本 + 语料副本 + 追加 L1 声明 chunk（rb-l1rt::恢复动作，同红队脚本内容）
STORAGE_MODE=memory DASHSCOPE_MODE=mock SERVER_PORT=8081 SERVER_ADDRESS=127.0.0.1 \
OPSPILOT_L1_ENABLED=true \
OPSPILOT_DISPATCH_KEY=<64hex> \
OPSPILOT_L1_DISPATCHWEBHOOK=http://127.0.0.1:18104/exec \
OPSPILOT_L1_ALLOWEDTARGETS=deployment/inventory-api \
OPSPILOT_L1_WEBHOOK_ALLOW_PRIVATE=true \
java -jar app.jar
~~~

## 2. 动线与证据

| 步 | 动作 | 主体 | 证据 |
|---|---|---|---|
| 1 | propose（HTTP，红队同路径） | sre-full | `op_id=act_7bb6d4cad03864b4c8af`，`expires_at=2026-11-05T16:11:03Z`（窗口 15min） |
| 2 | 面板刷新列表：`GET /api/v1/copilot/actions?state=proposed` | sre-manager | 返回 1 条，含 `opId/command/proposedBy/state`——与 index.html `renderActions()` 渲染字段逐字对应 |
| 3 | 确认：`POST /confirm`，body `{opId, token}` | sre-manager（≠提案人） | 返回终态 **`failed`**（见 §2.1） |
| 4 | 负向对照：已消费 token 再确认（sre-acme，跨租户 + 重放双重违规） | sre-acme | `400 state_failed`——状态机先拒（op 已终态 failed），token 原子消费使得重放连门都进不去 |
| 5 | 审计链 | — | [evidence/l1-panel-confirm-audit.jsonl](evidence/l1-panel-confirm-audit.jsonl)：`action_proposed`(sre-full) → `action_confirmed`(sre-manager) → `action_not_dispatched (state=failed)`，哈希链完整（seq 1→5，prev_hash 逐位衔接；含负向对照的 action_rejected） |

确认守卫全部在网关侧生效（面板无第二套规则）：confirmer≠proposer、租户匹配、token 原子消费。

### 2.1 意外收获：终态三选一的活体证明

ADR-0028 评审增补 3「confirm 同步写死，返回态即终态三选一：dispatched / failed / confirmed」——
这次 webhook（18104）没有执行器在听，confirm 返回 `failed`，app.log 记 `L1 派发失败（不自动重试）`，
审计落 `action_not_dispatched`。**"确认了但不知派没派"的中间态不存在**，第一次在活体链路上得到证明。

## 3. 发现的缺口（记录，不粉饰）

1. **审计 `via` 不区分面板与 API**：`index.html` 的确认 fetch 与 curl 走同一端点，审计 `via` 恒为
   `action-api`。"这条确认来自面板 UI"在审计链里**证明不了**。ADR-0028 §0 裁决 1 选面板优先的
   理由是"确认事件与身份强绑定、审计链短"——身份绑定已证，但入口来源不可辨是缺口。
   修复方向：前端带来源标识（如 `X-Opshilot-Surface: panel` 或 body 字段），审计透传；
   或接受现状并把"面板动线"的验收口径改为"UI 渲染字段与端点契约对齐"（即 §2 步 2 的核对）。
2. **`failed` 终态的 UI 呈现未验证**：面板确认后的提示是「已确认（failed）——派发由自动化系统执行」，
   对 `failed` 态这句文案有误导（没派出去）。浏览器预览在本安装不可用（in-app Browser 未启用），
   UI 点击层以"页面代码提取请求形状 + 同形状 HTTP 走链"降级完成，渲染层真实验证留待浏览器可用时补。

## 4. 验收口径建议

将 ADR-0028 验收路径第 3 步拆成两半记账：

- **服务端 + 审计链**（本次已证）：确认守卫、一次性 token、终态语义、审计三连；
- **UI 渲染层**（待补）：pending 列表字段契约已核对（§2 步 2），点击层与 `failed` 文案待真实浏览器走查。
