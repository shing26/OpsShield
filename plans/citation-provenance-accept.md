# 验收契约：引用溯源（文档版本 + 审查时效）

> 来源：2026-11-25 用户选定 S3 `citation-provenance`（引用溯源）。
> **状态：已冻结（2026-11-25）。** 基线 `main` @ `d8c47e8`；
> 本分支只承载契约；实现分支为 `codex/citation-provenance-impl`（契约合并后从 main 切出）。
> 用户已签收四项口径：内容摘要作版本、`reviewed_at` 作时效事实源、
> `Ref` 只加字段且回放不重算、现有 0/69 不做伪回填。
> 本切片只做「引用带得出版本身份与审查时效」，不做文档版本历史、不做过期降权、不做上游批量导入。

## Task ID

`citation-provenance`

## 现状与缺口（2026-11-25 实测，基线 `d8c47e8`）

已有能力：

- `AnswerPayload.Ref` 只有 `chunkId / breadcrumb / service`（`gateway/dto/AnswerPayload.java:41`），
  由 `ChatOrchestrator` 从本轮 `ScoredChunk` 直接构造（`ChatOrchestrator.java:426-428`）；
- SSE `done.refs` 原样下发该记录（`SseEvents.emitDone` → `done.put("refs", refs)`）；
- OpenAI 面因无结构化帧位，把 refs 降级渲染成正文参考列表（`OpenAiChatSink.java:108-135`）；
- 语料 69 篇 Markdown + 1 份 OpenAPI，产出 459 条 chunk；
- `offline/provenance.py` 已有「报告与语料同代」的内容摘要判据，并进 CI `provenance` job。

缺口：

1. **引用没有文档版本身份**：`chunkId` 是「doc_id::面包屑」，文档正文改了而标题没改时，
   新旧两版引用长得一模一样。回复无法回答「这句话依据的是哪一版 runbook」。
2. **语料没有审查时间**：全仓无 `reviewed_at` / `version` / `updated` 字段；
   现有 `date:`（42/69 篇）是文档或事故日期，**不是**审查日期，不能拿来冒充。
3. **引用身份只活在页面里**：复制为工单（`copyVerdict()`）与 OpenAI 面都不带版本，
   工单、聊天记录、外部客户端拿到的引用无法复核。
4. **没有回归锁**：chunker 改字段、ES/Qdrant 映射漏字段时，引用会静默退回「无版本」。

## 判定口径（四项，待签收）

1. **版本 = 内容摘要，不引入手填版本号**。
   `docDigest = sha256(规范化正文) 的十六进制前 12 位（小写）`。
   - Markdown：取 front matter **之后**的正文；行尾统一为 LF；去首尾换行，不动行内空白。
   - OpenAPI：`json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`
     的 UTF-8 字节（键序无关，语义相同 ⇒ 摘要相同）。
   - 同一 `doc_id` 的所有 chunk **共享**同一摘要，由构建期断言，不允许逐 chunk 各算一份。
   - **否决**「哈希整份文件」：改 `reviewed_at` 会改版本号而正文未变，引用面板会出现无意义的版本跳变。
2. **时效的事实源 = 可选 front matter `reviewed_at: YYYY-MM-DD`**。
   - 缺失 → `reviewedAt=null`、`staleness=unknown`，**绝不默认新鲜**。
   - 格式非法或日期不存在（如 `2026-02-30`）→ 构建期 `ValueError` 点名文件，不静默降级。
   - 未来日期 → 运行期判 `unknown`（构建期不读时钟，保证产物可复现）。
   - 阈值 `opspilot.citation.stale-after-days`，默认 **180**；`today - reviewed_at > 阈值` → `stale`，
     `0..阈值` → `fresh`。阈值不得硬编码。
   - `date:` 语义不动，也不作为 `reviewed_at` 的回退。
3. **`Ref` 只做加法**：新增 `docId / docDigest / reviewedAt / staleness`，旧三字段不变。
   - `staleness ∈ {fresh, stale, unknown}`；null / 空 / 非法值一律归一为 `unknown`。
   - 旧缓存回放（JSON 无新字段）→ 反序列化成功、`staleness=unknown`、`docDigest=null`。
   - **快照语义**：回放不重算时效；引用带的是「产生答案时看到的版本与状态」，不是当前状态。
4. **数据缺口如实登记**：现有 **0/69** 篇有 `reviewed_at`，首版全部显示「未标注」。
   本切片**不做伪回填**（不拿 `date:` 冒充）；回填是需要事实来源的独立数据任务。

## Allowed paths

```
plans/citation-provenance-accept.md
docs/adr/0039-citation-provenance.md
docs/CONTEXT.md
docs/repo-map.md
README.md
offline/doc_numbers.json
offline/chunkers/doc_digest.py
offline/chunkers/markdown_tree_chunker.py
offline/chunkers/openapi_ast_chunker.py
offline/chunkers/build_chunks.py
offline/corpus/chunks.jsonl
offline/eval/reports/PROVENANCE.json
offline/tests/test_doc_digest.py
offline/tests/test_chunk_provenance_fields.py
offline/tests/test_console_contract.py
src/main/java/com/opspilot/chunk/Chunk.java
src/main/java/com/opspilot/retrieval/ScoredChunk.java
src/main/java/com/opspilot/retrieval/CitationFreshness.java
src/main/java/com/opspilot/retrieval/InMemoryIndex.java
src/main/java/com/opspilot/retrieval/InMemoryLegs.java
src/main/java/com/opspilot/retrieval/EsSearchService.java
src/main/java/com/opspilot/retrieval/QdrantSearchService.java
src/main/java/com/opspilot/retrieval/RrfFuser.java
src/main/java/com/opspilot/ingest/IngestionRunner.java
src/main/java/com/opspilot/gateway/dto/AnswerPayload.java
src/main/java/com/opspilot/gateway/ChatOrchestrator.java
src/main/java/com/opspilot/gateway/OpenAiChatSink.java
src/main/java/com/opspilot/config/OpsPilotProperties.java
src/main/resources/application.yml
src/main/resources/static/index.html
src/test/java/com/opspilot/retrieval/CitationFreshnessTest.java
src/test/java/com/opspilot/retrieval/InMemoryIndexTest.java
src/test/java/com/opspilot/retrieval/RrfFuserTest.java
src/test/java/com/opspilot/retrieval/HybridSearchServiceTest.java
src/test/java/com/opspilot/gateway/dto/AnswerPayloadRefTest.java
src/test/java/com/opspilot/gateway/ChatOrchestratorTest.java
src/test/java/com/opspilot/gateway/OpenAiChatSinkTest.java
src/test/java/com/opspilot/gateway/SseChatSinkTest.java
src/test/java/com/opspilot/config/OpsPilotPropertiesValidationTest.java
```

## Acceptance criteria

1. **Markdown 摘要**：`build_chunks.py` 产出的每行 `metadata.doc_digest` 为 12 位小写 hex；
   同一 `doc_id` 的所有 chunk 摘要相同；正文改一个字符摘要必变；只改 front matter
   （如 `reviewed_at`）摘要不变；同一正文 CRLF 与 LF 摘要相同。
2. **OpenAPI 摘要**：同一 spec 仅键序不同 ⇒ 摘要相同；改任一 operation/description ⇒ 摘要变；
   同一 `doc_id` 所有 chunk 共享摘要。
3. **`reviewed_at` 解析**：缺失 → `null`；合法 `YYYY-MM-DD` 原样入 chunk；
   非法格式或不存在日期 → 构建期 `ValueError`，消息含文件名与字段名。
4. **语料重建只加字段，不改语义**：重建后仍为 **459** 行；对每个 `chunk_id`，
   `text / doc_id / type / breadcrumb / metadata 的既有键` 与 `main` 版逐字相同，
   `metadata` 只新增 `doc_digest` 与 `reviewed_at` 两个键；`golden_dataset.jsonl` 逐字节不变。
   `PROVENANCE.json` 只允许由 `python provenance.py --stamp` 重新生成，且登记前必须通过上述比对；
   提交信息写明「仅新增元数据字段，正文/错误码/文档身份逐字未变」。不得手改摘要。
5. **元数据贯通两条链路**：`Chunk.Metadata` 新增 `docDigest / reviewedAt`（旧 chunk 缺字段为 `null`）；
   memory 模式（`InMemoryIndex`）与 live 模式（ES `_source`、Qdrant payload、ES mapping、
   `EsSearchService` / `QdrantSearchService` 回读）都把它们带到 `ScoredChunk`；
   缺失时不得 NPE。`RrfFuser` / `withScores` 原样保留这两个字段。
6. **Ref 契约**：SSE `done.refs[i]` 与 `error.refs[i]` 新增
   `docId / docDigest / reviewedAt / staleness`；`chunkId / breadcrumb / service` 不变；
   `staleness` 非 `fresh|stale|unknown` 时归一为 `unknown`。
7. **时效判定**：纯函数 `CitationFreshness.classify(reviewedAt, today, staleAfterDays)`：
   null / 空 / 非法 / 未来 → `unknown`；差 180 天 → `fresh`，181 天 → `stale`；
   阈值只从 `opspilot.citation.stale-after-days` 读取（默认 180），单测覆盖边界。
8. **缓存回放**：旧载荷（refs 无新字段）反序列化成功且 `staleness=unknown`；
   L1 命中的 refs 与首次回答的 refs **逐字段相同**（回放不重算、不改写）。
9. **OpenAI 面**：正常与失败两条路径的参考列表都带版本身份，格式固定为
   `[n] <breadcrumb> · <service> · 文档 <docId>@<docDigest>`，
   后接 `（未过期：<reviewedAt>）` / `（已过期：<reviewedAt>）` / `（审查日期未标注）`；
   `docDigest` 缺失时写 `文档 <docId>（版本未知）`，不得留空占位。
10. **前端**：引用卡每条显示序号、`chunkId`、service、`docId@docDigest`
    （等宽、`title` 给全摘要与审查日期）、时效徽标「未过期 / 已过期 / 未标注」；
    新字段一律走 `esc()`；`copyVerdict()` 的「参考来源」同格式；
    `check_panel_contract.sh` 十层保持绿（**不得改该脚本或 `demo.sh` 放行**）。
11. **不回归**：检索排序、RRF、权限过滤、拒答、只读命令清单、审计字段、
    事故创建/PATCH/转交/评论/通知/历史分页语义零变化；
    `check_public_copy.py --check`、`doc_numbers.py --check`、`pytest offline/tests` 全绿。
12. **文档**：ADR-0039 记录「内容摘要作版本 / `reviewed_at` 作时效事实源 / 只加字段」
    三项决策与否决项；`docs/CONTEXT.md` 增「文档版本（Doc Digest）」「引用时效（Citation Staleness）」
    两条词条；`docs/repo-map.md` 与 README 的 ADR/用例计数同批更新（`doc_numbers.py --check` 会校验）。

## Verification commands（Verifier 亲自重跑）

```bash
cd "D:/OpsPilot — AIOps"
git rev-parse HEAD && git status --short
E:/java/apache-maven-3.9.14/bin/mvn.cmd -o test
E:/Git/bin/bash.exe scripts/check_panel_contract.sh
offline/.venv/Scripts/python.exe -m pytest offline/tests -q
offline/.venv/Scripts/python.exe offline/provenance.py --check
offline/.venv/Scripts/python.exe offline/doc_numbers.py --check
offline/.venv/Scripts/python.exe scripts/check_public_copy.py --check
```

重建确定性（产物必须能由当前语料逐字节复现）：

```bash
cd "D:/OpsPilot — AIOps/offline"
.venv/Scripts/python.exe chunkers/build_chunks.py --out rebuild-check.jsonl
cmp <(git show main:offline/corpus/chunks.jsonl) corpus/chunks.jsonl && echo "UNEXPECTED: 未变化"
cmp rebuild-check.jsonl corpus/chunks.jsonl && echo "OK: 重建逐字节一致"
rm -f rebuild-check.jsonl
```

「只加字段」比对（在仓库根执行）：

```bash
git show main:offline/corpus/chunks.jsonl > /tmp/old-chunks.jsonl
offline/.venv/Scripts/python.exe - <<'PY'
import json
old = [json.loads(l) for l in open('/tmp/old-chunks.jsonl', encoding='utf-8')]
new = [json.loads(l) for l in open('offline/corpus/chunks.jsonl', encoding='utf-8')]
assert len(old) == len(new) == 459, (len(old), len(new))
by_id = {c['chunk_id']: c for c in old}
assert len(by_id) == 459
for c in new:
    o = by_id[c['chunk_id']]
    for k in ('text', 'doc_id', 'type', 'breadcrumb'):
        assert c[k] == o[k], (c['chunk_id'], k)
    om, nm = o['metadata'], c['metadata']
    assert set(nm) - set(om) == {'doc_digest', 'reviewed_at'}, (c['chunk_id'], set(nm) - set(om))
    for k in om:
        assert nm[k] == om[k], (c['chunk_id'], k)
print('OK 459 chunks：仅新增 doc_digest/reviewed_at，语义字段逐字不变')
PY
```

## 活体验收（Verifier 亲自操作）

用 memory + mock 模型起服务（`STORAGE_MODE=memory DASHSCOPE_MODE=mock`），
登录后请求 `50012_DB_TIMEOUT 怎么排查`：

1. `done` 帧的每个 ref 都含 `docId`、`docDigest`（12 位）、`reviewedAt`、`staleness`；
   `docDigest` 与 `chunks.jsonl` 中该 `chunk_id` 的 `doc_digest` 一致；
2. 现有语料无 `reviewed_at`，故 `reviewedAt=null`、`staleness=unknown`；
3. 页面参考来源逐条显示 `docId@摘要` 与「未标注」徽标，无 `undefined` / `null` 字样；
4. 复制为工单的「参考来源」段带同样的版本身份；
5. 同一 query 立即重发命中 L1：refs 与首次回答逐字段相同（含 `staleness`），
   证明回放未重算；
6. 未改动任何事故/通知/分页行为。

时效 `fresh` / `stale` 由固定日期的单测证明（活体语料刻意没有 `reviewed_at`，
不伪造审查事实）。

## Forbidden

- 禁止引入手填 `version:` 或任何人工版本号；版本只能是内容摘要。
- 禁止把缺失 / 非法 / 未来的 `reviewed_at` 当作 `fresh`；禁止用 `date:` 冒充审查日期。
- 禁止在缓存回放路径重新计算 `staleness`（会破坏快照语义）。
- 禁止改 `scripts/check_panel_contract.sh` / `scripts/demo.sh` 来放行，或用字符串拼接绕过面板门禁
  （S1 已因同类形态判 P1）。
- 禁止批量给现有 69 篇补 `reviewed_at`（回填需事实来源，另立数据任务）。
- 禁止改 V1–V11 迁移；禁止改变事故、通知、评论、历史分页、告警台账的既有语义与字段。
- 禁止把 tenant 写进 refs 或摘要；禁止让 `docDigest` 参与授权判断。
- 禁止手改 `PROVENANCE.json` 摘要；禁止跳过重建比对直接 `--stamp`。
- 禁止改/删/弱化断言换通过；禁止跳测试不记录；禁止 `git add -A`。

## 回滚

`doc_digest` / `reviewed_at` 都是可选新增字段：旧客户端忽略，旧 chunk 反序列化为 `null`。
回滚只需 revert 本切片（含 `chunks.jsonl` 与 `PROVENANCE.json` 同批回滚），
无需数据迁移，也不影响既有检索与事故链路。

## 人审签收

**状态：已签收（2026-11-25）。** 四项口径按原样确认：

1. 版本 = 正文内容摘要（12 位 sha256），不引入手填版本号；
2. 时效事实源 = 可选 `reviewed_at`，缺失/非法/未来一律 `unknown`，阈值默认 180 天可配；
3. `Ref` 只加字段，旧缓存缺字段回落 `unknown`，回放不重算（快照语义）；
4. 现有 0/69 篇有 `reviewed_at`，首版全部「未标注」，本切片不做伪回填。

实现者与 Verifier 不得为同一会话。本次签收只授权实现，不含合并授权；
合并须另行书面签收。
