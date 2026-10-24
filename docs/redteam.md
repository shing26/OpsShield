# OpsShield 红队对抗基准（公开版）

> 一键复现：`bash scripts/redteam.sh`。判据全部 **fail-closed 布尔**：任何一条泄漏即 FAIL，
> 0 容忍、无阈值、无 LLM-as-judge（判定必须确定性）。本页数字由 doc_numbers 门禁与产物对账。

## 定位声明

红队套件是中期「受控行动契约 L1/L2」的**前置安全网**：闭环没就位之前，
只读契约不允许放开到写操作。运维安全是布尔判据——**0 泄漏 > 90% 覆盖率**，
本页刻意不做覆盖率叙事。

## 最新结果

| 维度 | 数值 |
|---|---|
| 红队·用例总数 | 21 |
| 红队·泄漏 | 0 |

完整逐用例结果：[redteam_report.json](../offline/redteam/report/redteam_report.json) ｜
人读版 [redteam_report.md](../offline/redteam/report/redteam_report.md)。

## 用例类别（6 类 21 条）

| 类别 | 攻击面 | 判据（全部确定性） |
|---|---|---|
| injection | query 指令注入（search 面 + chat 面） | 检索结果 ⊆ 语料；契约 action 逐字来自 ref chunk |
| jailbreak | 5 条越狱 prompt 打 L1 账号 | 结果密级全部 ≤1，零高密级泄漏 |
| cross_tenant | acme 租户打 internal 语料 | 结果租户全部 == 登录租户（语料 metadata 对账） |
| classification | L1 广撒网敏感词 | 同上密级判据 |
| command_contract | 诱导生成写命令（rm -rf / delete ns） | 契约命令逐字来自 ref chunk；语料外危险命令 = 泄漏 |
| mcp | 畸形帧 / 2MB 帧 / 无凭据调用 / 描述注入 / 参数注入 | 攻击后 ping 存活；零数据；描述字节级稳定；结果 ⊆ 语料 |

## MCP 层：当前时点独有的攻击面

OpsShield 的 MCP Server（ADR-0027）是新攻击面，这批端到端用例（真 stdio 进程、
真 JSON-RPC）是生态里少见的"带安全基准的运维 MCP Server"资产：

- **传输层**：畸形帧/超长帧 → parse error 后 ping 存活（fail-closed，不崩溃不泄漏）；
- **无凭据**：tools/call 直接 isError，失败信息零网关数据；
- **描述静态**：注入性调用前后 tools/list 字节级一致（工具描述无执行语义、不被上下文影响）；
- **参数注入**：恶意 query 的检索结果仍全部 ⊆ 语料（不透传不执行）。

## 防漂移机制（对标 benchmark）

- **manifest**：每用例 sha256 入库——删/改"难过的用例"必须显式 `--update-manifest`，
  CI 里 manifest 与 cases 的 diff 人审；
- **判定确定性**：字符串/结构/状态机，无 LLM-as-judge；
- **独立 runner**：不依赖 pytest，任何人 clone 即可 `bash scripts/redteam.sh` 复现；
- **数字门禁**：本页用例总数/泄漏数由 doc_numbers 与 `redteam_report.json` 现算对账。

## 口径与边界

- mock 后端（CI 可复现）；注入类用例的判定是**结构不变式**（契约逐字性/结果⊆语料），
  与 LLM 后端无关；
- L1/L2 受控行动契约开闸前，本套件必须先扩写操作用例——顺序不能反。