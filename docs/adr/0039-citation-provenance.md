# 引用带有文档版本身份与审查时效

## 上下文

`AnswerPayload.Ref` 此前只有 `chunkId / breadcrumb / service`。`chunkId` 的形状是
`<doc_id>::<面包屑>`：文档正文改了而标题没改时，新旧两版引用**长得一模一样**。
回复因此无法回答一个最基本的排障问题——"这句话依据的是哪一版 runbook"。
语料也没有任何审查时间字段；现有 `date:`（42/69 篇）是文档或事故日期，
不是"谁在何时确认这份手册仍然正确"，不能拿来冒充。

同时，引用身份只活在页面里：复制为工单与 OpenAI 兼容面都不带版本，
工单和外部客户端拿到的依据无法复核。

## 决策

1. **版本 = 正文内容摘要**：`docDigest = sha256(规范化正文)` 的十六进制前 12 位小写。
   - Markdown 取 front matter 之后的正文；行尾统一为 LF；去首尾换行，不动行内空白。
   - OpenAPI 取 `json.dumps(spec, sort_keys=True, separators=(",", ":"), ensure_ascii=False)`
     的 UTF-8 字节——键序无关，语义相同则摘要相同。
   - 同一 `doc_id` 的所有 chunk 共享一个摘要；`build_chunks.py` 构建期显式 raise，
     不允许同一文档出现多个"版本"。
2. **审查时效的事实源 = 可选 front matter `reviewed_at: YYYY-MM-DD`**。
   - 缺失 → `reviewedAt=null`、`staleness=unknown`，绝不默认新鲜。
   - 格式非法或日期不存在 → 构建期 `ValueError` 点名文件；未来日期在运行期判 `unknown`。
   - 阈值 `opspilot.citation.stale-after-days`，默认 180 天；`today - reviewed_at > 阈值` 判 `stale`。
3. **`Ref` 只做加法**：新增 `docId / docDigest / reviewedAt / staleness`，旧三字段不变；
   非 `fresh|stale|unknown` 的值一律归一为 `unknown`。旧缓存缺字段时反序列化为 `null`。
4. **快照语义**：时效在生成答案时计算一次，随载荷进 L1/L2；回放不重算。
   引用回答的是"产生这条答案时看到的版本与状态"，不是"现在是什么状态"。
5. **三个消费面同源**：SSE `done/error` 的 refs、OpenAI 正文参考列表、
   面板引用卡与「复制为工单」都读同一组字段；缺失时明写"版本未知"，不留空占位。

## 否决项

- **手填 `version:`**：会漂移（改正文忘改号、两篇共用号），且无法从产物复算。
- **哈希整份文件**：改 `reviewed_at` 会制造"正文版本"跳变，面板出现无意义的版本噪声。
- **缺失/非法/未来日期当 `fresh`**：会让"没人签字"的文档看起来和刚审查过的一样，是静默的错误信号。
- **拿 `date:` 回退**：日期语义不同（文档/事故日期 ≠ 审查日期），回退等于伪回填。
- **回放时重算时效**：会破坏快照语义——同一条缓存的引用会在不同日期显示不同结论。
- **本切片做版本历史表 / 过期自动降权 / 上游批量导入**：都需要独立的存储或事实来源，
  不在"把版本身份带出来"这一步里提前施工。

## 后果

- 现有 **0/69** 篇有 `reviewed_at`，首版全部显示「未标注」；这是如实登记的数据缺口，
  回填是独立数据任务（需要人工确认"这份手册仍然正确"），不在本切片伪造。
- `offline/corpus/chunks.jsonl` 重建后仅新增 `metadata.doc_digest` 与 `metadata.reviewed_at`
  两个键；正文、文档身份、错误码逐字不变，`golden_dataset.jsonl` 逐字节不变；
  `PROVENANCE.json` 经 `--stamp` 重新登记。
- 旧客户端忽略新字段；旧 ES/Qdrant 索引与旧缓存缺字段时回落 `null` / `unknown`，
  不 NPE、不误判新鲜。
- 审查通过后，运维可以在引用卡上直接核对"这一版手册是哪一版、有没有过期"，
  复制到工单里的依据也带同一身份。
