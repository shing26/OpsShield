package com.opspilot.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.opspilot.llm.EmbeddingClient;

/**
 * memory 模式的两条腿（ADR-0022）：同一份 {@link InMemoryIndex}，两种打分。
 *
 * <p><b>不承诺检索质量</b>（决策 5）：词法腿是"错误码精确命中 + 词面重合"的确定性打分，
 * 不是 BM25；向量腿吃的是 {@link EmbeddingClient} 在 mock 模式下的**词法哈希向量**，不是神经语义。
 * 两者都**足以让 RRF 融合真事、让排障台点一下就出结果**，但任何"命中率/相关性"的说法都不适用——
 * README 与启动摘要都按这个口径写。
 *
 * <p>打分**形状**刻意照抄 ES 的 should 子句权重（错误码 20 / FQCN 8 / 短语 5 / 词面 1），
 * 这样两边的排序倾向不会南辕北辙；数值本身不同（ES 是 BM25 归一后的分），不可跨后端比较。
 */
final class InMemoryLexicalLeg implements RetrievalLeg {

    private static final double W_ERROR_CODE = 20.0;
    private static final double W_FQCN = 8.0;
    private static final double W_PHRASE = 5.0;
    private static final double W_TERM = 1.0;

    private final InMemoryIndex index;

    InMemoryLexicalLeg(InMemoryIndex index) {
        this.index = index;
    }

    @Override
    public List<ScoredChunk> search(String query, String tenant, int authLevel, int topK) {
        List<String> codes = EsSearchService.extractErrorCodes(query);
        Set<String> qTokens = InMemoryIndex.tokens(query);
        boolean hasFqcn = EsSearchService.FQCN.matcher(query).find();
        String phrase = query.toLowerCase(Locale.ROOT).trim();

        List<ScoredChunk> out = new ArrayList<>();
        for (InMemoryIndex.Doc d : index.visible(tenant, authLevel)) {
            ScoredChunk c = d.chunk();
            String hay = c.text() == null ? "" : c.text().toLowerCase(Locale.ROOT);
            double score = 0;
            for (String code : codes) {
                if (c.errorCodes().contains(code)) { score += W_ERROR_CODE; break; }
            }
            if (hasFqcn) {
                var m = EsSearchService.FQCN.matcher(c.text() == null ? "" : c.text());
                while (m.find()) {
                    if (query.contains(m.group())) { score += W_FQCN; break; }
                }
            }
            if (!phrase.isEmpty() && hay.contains(phrase)) score += W_PHRASE;
            int hit = 0;
            for (String t : qTokens) if (hay.contains(t)) hit++;
            if (!qTokens.isEmpty()) score += W_TERM * hit / qTokens.size();

            if (score > 0) {
                out.add(new ScoredChunk(c.chunkId(), c.docId(), c.type(), c.text(), c.breadcrumb(),
                        c.service(), c.errorCodes(), c.authLevel(),
                        new ScoredChunk.Scores(score, 0, 0, 0)));
            }
        }
        out.sort(Comparator.comparingDouble(ScoredChunk::esScore).reversed());
        return out.subList(0, Math.min(topK, out.size()));
    }
}

/** 向量腿：查询向量 vs 索引里预存的切片向量，余弦相似度降序。 */
final class InMemoryVectorLeg implements RetrievalLeg {

    private final InMemoryIndex index;
    private final EmbeddingClient embedding;

    InMemoryVectorLeg(InMemoryIndex index, EmbeddingClient embedding) {
        this.index = index;
        this.embedding = embedding;
    }

    @Override
    public List<ScoredChunk> search(String query, String tenant, int authLevel, int topK)
            throws Exception {
        float[] qv = embedding.embedOne(query);
        List<ScoredChunk> out = new ArrayList<>();
        for (InMemoryIndex.Doc d : index.visible(tenant, authLevel)) {
            if (d.vector() == null) continue;
            double cos = Vectors.cosine(qv, d.vector());
            if (cos <= 0) continue;
            ScoredChunk c = d.chunk();
            out.add(new ScoredChunk(c.chunkId(), c.docId(), c.type(), c.text(), c.breadcrumb(),
                    c.service(), c.errorCodes(), c.authLevel(),
                    new ScoredChunk.Scores(0, cos, 0, 0)));
        }
        out.sort(Comparator.comparingDouble(ScoredChunk::vectorScore).reversed());
        return out.subList(0, Math.min(topK, out.size()));
    }
}
