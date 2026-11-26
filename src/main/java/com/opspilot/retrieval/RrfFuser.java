package com.opspilot.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准 RRF（Reciprocal Rank Fusion）：score(d) = Σ 1/(k + rank_m(d))，k=60。
 * 无量纲融合双路排名，纯函数可单测（A2-2 验收依据）。
 */
public final class RrfFuser {

    private RrfFuser() {}

    /**
     * @param rankedLists 每路检索的 chunk 列表（按相关性降序）
     * @param k           RRF 常数
     * @return chunkId -> 融合分，降序
     */
    public static Map<String, Double> fuse(List<List<ScoredChunk>> rankedLists, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<ScoredChunk> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                scores.merge(list.get(rank).chunkId(), 1.0 / (k + rank + 1), Double::sum);
            }
        }
        Map<String, Double> sorted = new LinkedHashMap<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** 融合双路结果，返回按 RRF 分降序、携带各路原始分的列表。 */
    public static List<ScoredChunk> apply(List<ScoredChunk> es, List<ScoredChunk> vector, int k) {
        Map<String, Double> fused = fuse(List.of(es, vector), k);
        Map<String, ScoredChunk> esById = new LinkedHashMap<>();
        for (ScoredChunk c : es) esById.put(c.chunkId(), c);
        Map<String, ScoredChunk> vecById = new LinkedHashMap<>();
        for (ScoredChunk c : vector) vecById.put(c.chunkId(), c);

        List<ScoredChunk> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : fused.entrySet()) {
            ScoredChunk base = esById.containsKey(e.getKey()) ? esById.get(e.getKey()) : vecById.get(e.getKey());
            ScoredChunk v = vecById.get(e.getKey());
            out.add(new ScoredChunk(base.chunkId(), base.docId(), base.type(), base.text(),
                    base.breadcrumb(), base.service(), base.errorCodes(), base.authLevel(),
                    base.docDigest(), base.reviewedAt(),
                    new ScoredChunk.Scores(base.esScore(), v == null ? 0 : v.vectorScore(),
                            e.getValue(), 0)));
        }
        return out;
    }
}
