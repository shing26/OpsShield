package com.opspilot.metrics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单请求分段耗时（G2）：检索分腿 + LLM + L2 写入，随 chat 审计行落 `stage_ms` 嵌套对象。
 *
 * 为什么挂审计行而不是 SSE 帧：SSE 是对外协议面（ADR-0007 下 OpenAI 面本就不发 meta），内部
 * 耗时不该泄到那里；而审计行本就是"一条请求一条记录"且已带 `took_ms`，分段耗时与它同属一条
 * 记录——落同一行既无需跨事件关联，也天然进 ring / 磁盘 / 面板三面。
 *
 * 口径（勿误读）：
 * - `retrievalMs` 来自 `SearchOutcome.tookMs`；`esMs`/`vectorMs`/`rrfMs`/`rerankMs` 来自
 *   `LegTimings`。两腿**并行**发起，故 `retrievalMs ≈ max(esMs, vectorMs) + rrfMs + rerankMs`，
 *   **不是四者之和**。
 * - `vectorMs` **包含该腿内部的 embedding 调用**（Qdrant 腿先 embed 再检索）。
 * - `rerankMs == 0` 表示**未调用**（快路径 / es_only / 降级），不是"很快"。
 * - `l2StoreMs` 在 G1（2026-10-22）后表示 **L2 写入的入队开销**，不是真实写入耗时：
 *   答案下发后异步写（embedding+Qdrant upsert 在虚拟线程），本字段只测"把任务交给执行器"的
 *   那一段，仍可发现执行器饱和/拒绝；真实写入耗时看 `aiops.cache.write.fail` 与 Qdrant 侧指标。
 *   历史含义（内含第二次 embedding 调用）已随本次异步化失效，引用旧数据时必须带时间口径。
 *
 * 非 chat 路径（/search、鉴权、管理面）传 `null`，审计行不落 `stage_ms` 字段——与既有
 * `src_tenant` / `verbatim_masked` 的"null 即不落"约定一致，避免全量噪音。
 */
public record StageTimings(
        int retrievalMs,
        int esMs,
        int vectorMs,
        int rrfMs,
        int rerankMs,
        int llmTtftMs,
        int llmMs,
        int l2StoreMs
) {
    /** 落审计行的嵌套对象。全 0 的段仍落字段——"测了且为 0"与"没测"是两回事。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("retrieval", retrievalMs);
        m.put("es", esMs);
        m.put("vector", vectorMs);
        m.put("rrf", rrfMs);
        m.put("rerank", rerankMs);
        m.put("llm_ttft", llmTtftMs);
        m.put("llm", llmMs);
        m.put("l2_store", l2StoreMs);
        return m;
    }
}
