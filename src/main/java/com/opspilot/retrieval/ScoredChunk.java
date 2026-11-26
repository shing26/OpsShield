package com.opspilot.retrieval;

import java.util.List;

/** 检索结果：chunk 全文 + 元数据 + 四路分数（Scores 值对象，Data Clumps 收敛）。 */
public record ScoredChunk(
        String chunkId,
        String docId,
        String type,
        String text,
        String breadcrumb,
        String service,
        List<String> errorCodes,
        int authLevel,
        String docDigest,
        String reviewedAt,
        Scores scores
) {
    /** 旧 9 参兼容构造：S3 之前的位置参数调用点（测试夹具）无需逐个补 null。 */
    public ScoredChunk(String chunkId, String docId, String type, String text, String breadcrumb,
                       String service, List<String> errorCodes, int authLevel, Scores scores) {
        this(chunkId, docId, type, text, breadcrumb, service, errorCodes, authLevel, null, null, scores);
    }

    /** ES BM25 / 向量余弦 / RRF 融合 / Rerank 精排；0 表示该路未打分（如 es_only 无 rerank）。 */
    public record Scores(double es, double vector, double rrf, double rerank) {}

    public ScoredChunk withRrf(double s) {
        return new ScoredChunk(chunkId, docId, type, text, breadcrumb, service, errorCodes,
                authLevel, docDigest, reviewedAt,
                new Scores(scores.es(), scores.vector(), s, scores.rerank()));
    }

    public ScoredChunk withRerank(double s) {
        return new ScoredChunk(chunkId, docId, type, text, breadcrumb, service, errorCodes,
                authLevel, docDigest, reviewedAt,
                new Scores(scores.es(), scores.vector(), scores.rrf(), s));
    }

    // 便捷读取（消费侧口径稳定：调用方无需关心 Scores 的包装结构）
    public double esScore() { return scores.es(); }
    public double vectorScore() { return scores.vector(); }
    public double rrfScore() { return scores.rrf(); }
    public double rerankScore() { return scores.rerank(); }
}
