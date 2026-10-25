# OpsShield 后续演进路线图（2026-10-24 定稿）

> 来源：2026-10-24 运维负责人给出的方向建议，落库作为后续迭代的顶层依据。
> 核心判断：OpsShield 的差异化在**「Agent 的只读可信底座」**——不要变成全功能 AIOps 平台，
> 而是把「可信运维上下文提供者」这个生态位做深、做成标准。

## 近期（3–6 个月）：把「参考实现」变成「可托付的生产组件」

### 1. MCP Server 接口（最高优先级）

把 OpsShield 能力暴露为 Model Context Protocol (MCP) Server：

- Claude Code / Cursor / Windsurf / Cline 等主流工具**零改造接入**，直接消费「可信运维上下文」；
- 工具集如 `query_runbook(error_code)`、`search_ops_docs(query)`、`get_readonly_commands(doc_id)`，
  每个工具输出天然带「逐字抽取 + 只读契约」安全保证；
- MCP Server 是**前置适配层**：内部复用既有检索、削峰、降级、权限隔离，不重复造轮子。

### 2. 补齐生产门禁运维交付项

- 部署形态：Helm Chart / K8s Operator，多副本无状态 + 共享状态存储；
- 可观测性：自身 Prometheus 指标、健康端点、结构化审计；
- 告警接线：Alertmanager → OpsShield webhook 入口 + 自身告警；
- 备份与恢复：知识库索引、去重键状态、审计日志；
- 配置管理：版本化配置 + 启动校验 + 变更审计。

### 3. 可复现评测基准（护城河）

- 公开脱敏测试集（运维文档、告警样本、错误码）；
- 一键评测脚本（安全性、削峰、检索命中率、降级行为）；
- CI 回归门禁：PR 引入安全回归直接拦截。

### 4. 轻量管理界面 / CLI

- CLI：知识库导入、索引重建、租户/密级配置、健康检查；
- 极简面板：检索调试、命令契约查看、降级状态、审计查询。

## 中期（6–12 个月）：只读契约 → 受控行动契约

### 5. 分级行动契约（带审批）

| 级别 | 示例 | 机制 |
|---|---|---|
| L0 只读 | `kubectl get/describe/logs` | 现有机制：逐字抽取 + 白名单 |
| L1 低危写 | `kubectl rollout restart` | 逐字抽取 + 二次确认 + 审计 |
| L2 高危写 | 数据库变更、配置修改 | 人工审批工作流 + 执行窗口 + 自动回滚钩子 |

原则不变：**命令仍从文档逐字抽取，LLM 只做匹配与建议，不做生成与执行**；
执行经 webhook 对接 Ansible/Rundeck/Argo，OpsShield 不持有执行权限。

### 6. 反馈闭环与知识库治理

- 检索/建议命令的「有用/无用/错误」反馈回流排序与质量看板；
- 文档版本化、引用标注版本、过期文档降权；
- 摄取流水线：Confluence / Git / 工单系统批量导入（清洗、分块、元数据）。

### 7. 多模态运维上下文

- 日志片段：结构化字段检索 + 语义检索混合；
- Trace/指标：trace_id / 指标异常 → 关联 Runbook；
- 配置片段：K8s YAML、Nginx conf 结构化精确匹配。

### 8. 对抗性测试套件（红队化）

Prompt 注入 / 越狱 / 跨租户探测 / 密级绕过攻击用例库；每次发布自动跑，结果公开徽章化。

## 长期（12 个月+）：可信运维上下文的事实标准

### 9. 可信运维上下文协议

- 定义输出**证据链格式**（doc id、版本、抽取位置、置信度、安全级别）供上游 Agent 审计；
- 与 OpenTelemetry / Alertmanager / Grafana 标准化集成；
- 目标：任何 Agent 做运维决策时，都通过 OpsShield 获取可信上下文。

### 10. Agentic Ops 生态中的底座位置

~~~
监控/告警 → [OpsShield: 可信上下文 + 削峰 + 降级 + 权限] → Agent 决策循环 → 自动化执行
~~~

不抢 Agent 的决策角色，做「事实来源 + 安全护栏」。

## 明确不做

1. 不做异常检测/根因分析算法（消费 Metis/KeenTune/商业可观测的告警）；
2. 不做自动化执行引擎（对接现有自动化系统，不持有执行权限）；
3. 不做自主决策 Agent（不引入 LLM 决策循环与 tool-calling）；
4. 不做通用 LLM 网关（多模型路由/成本追踪是 LiteLLM/Portkey 的领域）；
5. 不做大而全管理 UI（轻量 CLI + 极简面板即可）。

## 落地状态对照（2026-10-24 盘点）

| 路线图条目 | 仓库现状 |
|---|---|
| MCP Server | **本轮实现**：`scripts/mcp_server.py`（stdio，stdlib，3 工具），见 ADR-0027 |
| 生产门禁交付项 | 10 条门禁 8 绿（备份调度/告警/边界/失败证据/回滚/SCA/WORM/Runbook），6/7 缺长窗口与 30 天数据 |
| 可观测/告警接线 | `aiops_*` 指标 + Alertmanager 双出口 + DependencyMetrics 已落地 |
| 备份恢复 | 计划任务 + `restore_drill.sh` + MinIO Object Lock 副本 |
| 反馈闭环 | `/api/v1/copilot/feedback` 端点已存在（排序回流待接） |
| 受控行动契约（中期 #5） | **L1 全链已入红队门禁（2026-10-25）**：7 条端到端全过（32/32 零泄漏零跳过），派发验签跨语言 e2e；ADR-0028 验收路径前 4 步完成；ADR-0028 设计评审定稿（2026-10-25）：三裁决（面板/通用 webhook+reference verifier/一次性 token+会话绑定）+ 六增补（confirmer≠proposer 守卫、幂等共享存储、失败补偿语义、verifier constant-time+分状态码、WORM 门禁、灰度 label 白名单）；实现顺序见 [l1-action-contract.md](l1-action-contract.md) §9 |
| 评测基准 | **本轮实现**：一键基准 + CI 回归门禁 + **公开脱敏数据集**（43 文件审计 0 命中、sha256 指纹清单、CC BY 4.0）+ 对外基准页 [docs/benchmark.md](benchmark.md)（数字进 doc_numbers 门禁） |
| 轻量 CLI | `scripts/user_admin.sh` 等已有；面板已有 Ops Console |
| 红队化 | 越权/注入用例在测试中，未产品化为独立套件 |