# 事故复盘草稿生成（人机协同入口）

> 补的是 [`OPS.md` §5.1](../../OPS.md)「复盘 → 知识回灌」的**运行期入口**，不是写路径本身：
> 生成器只写 `offline/staging/postmortems/`，语料仍由人审后手工移入。
>
> 为什么这样切：语料是全系统"可信"的源头。自动回灌等于把模型输出当成事实再喂给检索——
> 那正是本仓库从第一天就在防的环路。机器负责**把证据摆齐**，人负责**下判断**。

## 它做什么 / 不做什么

| 做 | 不做 |
|---|---|
| 按 `trace_id` 取出一次 incident 的全部 `ev=chat` 审计行 | 不调用网关、不重新生成答案 |
| 关联告警台账的 `alert_id` / `status` / `refs` / `ref_docs` | 不写 `offline/corpus/`（路径守卫直接拒绝，退出码 3） |
| 关联人工反馈（按 `fp`）与窗口内的降级切换 | 不填根因 / 处置 / 验证 / 教训（四段只有 `TODO(人)`） |
| 渲染成一份可直接编辑的复盘草稿 | 不自动入库、不自动 reingest |

## 证据从哪来

审计行只落**统计量**（`AuditService.log` 不写答案正文与引用清单），所以证据分三处取：

| 来源 | 提供什么 | 缺失时 |
|---|---|---|
| `logs/audit.jsonl` 的 `ev=chat` 行 | `request_id`、`via`、`source`、`refused`、`mode`、`refs`/`actions`（计数）、`took_ms`、`degrade_level`、`q`（截 200 字符） | 直接退出 2（找不到该 trace） |
| `logs/alert-bridge.jsonl` | `alert_id`、`alertname`、`status`、`error_code`、`refs`（chunk id）、`ref_docs` | 草稿显式写"未找到台账行，不推断引用清单" |
| 同一份审计的 `ev=feedback` / `ev=degrade_transition` | 人给过的 verdict/note；窗口内的档位切换 | 写"无" |

`trace_id` 的分组口径与 ADR-0007 修订注一致：告警组用 `am-<32hex>`，多步 agent 用自己生成的
关联键；`request_id` 始终是服务端权威，两者不混用。

## 用法

```bash
# 告警 incident（trace_id 来自 alert_bridge 的 incident_id）
python offline/postmortem_draft.py --trace-id am-<32hex>

# 多步 agent incident
python offline/postmortem_draft.py --trace-id agent-<id>

# 审计有轮转片：--audit 可传目录，取全部 audit*.jsonl
python offline/postmortem_draft.py --trace-id ... --audit logs

# 只看不落盘
python offline/postmortem_draft.py --trace-id ... --stdout
```

默认输出 `offline/staging/postmortems/draft_<date>_<trace>.md`；同名存在时需 `--force`。

退出码：`0` 成功；`2` 找不到该 trace 或草稿已存在；`3` 拒绝写出（语料目录 / 敏感信息）。

## 入库前检查清单

草稿文末已内置同一份清单，摘录如下：

1. 六个 `TODO(人)` 写完，且没有把模型猜测写成事实；
2. 去敏：邮箱 / 公网 IP / 凭据 / 长 hex / 手机号；
3. `service` / `error_codes` / `severity` 填好，`doc_id` 改成唯一的 `rb-NNN` 或 `pm-NNN`；
4. 文件名改成 `rb-NNN-<slug>.md`，移入 `offline/corpus/runbooks/`（或 `postmortems/`）；
5. `python chunkers/build_chunks.py`；
6. `python eval/build_golden.py && python eval/evaluate.py`（会重打 provenance）；
7. 更新 `doc_numbers` 登记的数字（复盘/Runbook 篇数、chunks 行数）；
8. `POST /api/v1/admin/reingest`。

## 两个刻意的设计

**默认文件名 `draft_` 前缀。** chunker 从文件名前缀 `[a-z]+-\d+` 取 `doc_id`
（`markdown_tree_chunker.py`），而 frontmatter 里的 `doc_id` 被忽略。如果草稿叫
`rb-2026-…`，两份草稿会共享 `doc_id` 并在 `build_chunks` 里互相覆盖；`draft_` 前缀
匹配不上该正则，"忘了改名就入库"会在第一步就失败。

**渲染后过一遍敏感扫描。** 复用 `offline/eval/dataset_audit.audit_text`，命中邮箱 / 公网 IP /
凭据 / 长 hex / 手机号即拒绝写出（fail-closed），由人先脱敏或确认输入。`trace_id` 本身
（允许 8–64 位 hex）从命中里剔除——它是关联键，不是凭据。

## 测试

`offline/tests/test_postmortem_draft.py` 16 条：

- 过滤与排序、坏行容忍、重复行去重；
- 台账富化、台账缺失时**不推断**；
- 反馈按 `fp` 关联、降级切换只在 incident 窗口内；
- 六个判断段只有 `TODO(人)`（反造假红线）；
- 草稿自身过敏感扫描；
- 拒绝写语料目录、拒绝敏感 query；
- 无行退出 2、同名需 `--force`、输出确定性、UTF-8/LF；
- 默认文件名不会被 chunker 误当 `doc_id`。
