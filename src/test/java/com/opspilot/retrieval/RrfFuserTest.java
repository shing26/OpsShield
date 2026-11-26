package com.opspilot.retrieval;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A2-2：RRF 融合结果与手算 k=60 公式逐位一致。 */
class RrfFuserTest {

    private static ScoredChunk c(String id) {
        return new ScoredChunk(id, id, "test", "t", "b", "s", List.of(), 1,
                new ScoredChunk.Scores(0, 0, 0, 0));
    }

    @Test
    void fuseMatchesHandComputedFormula() {
        // ES: A B C ; Qdrant: B A C ; k=60
        List<ScoredChunk> es = List.of(c("A"), c("B"), c("C"));
        List<ScoredChunk> vec = List.of(c("B"), c("A"), c("C"));
        Map<String, Double> fused = RrfFuser.fuse(List.of(es, vec), 60);

        double a = 1.0 / 61 + 1.0 / 62;   // rank1 ES + rank2 vec
        double b = 1.0 / 62 + 1.0 / 61;   // rank2 ES + rank1 vec
        double cc = 1.0 / 63 + 1.0 / 63;  // rank3 both
        assertEquals(a, fused.get("A"), 1e-12);
        assertEquals(b, fused.get("B"), 1e-12);
        assertEquals(cc, fused.get("C"), 1e-12);
        // A 与 B 同分（对称），C 最低
        assertEquals(List.of("A", "B", "C"), List.copyOf(fused.keySet()));
    }

    @Test
    void singleListOnlyAppearsOnce() {
        List<ScoredChunk> es = List.of(c("X"), c("Y"));
        Map<String, Double> fused = RrfFuser.fuse(List.of(es, List.of()), 60);
        assertEquals(1.0 / 61, fused.get("X"), 1e-12);
        assertEquals(1.0 / 62, fused.get("Y"), 1e-12);
    }

    @Test
    void applyPreservesDescendingOrder() {
        List<ScoredChunk> es = List.of(c("A"), c("B"));
        List<ScoredChunk> vec = List.of(c("A"), c("C"));
        List<ScoredChunk> out = RrfFuser.apply(es, vec, 60);
        assertEquals("A", out.get(0).chunkId()); // 双路第一，融合分最高
        assertTrue(out.get(0).rrfScore() >= out.get(1).rrfScore());
    }

    /** S3 AC5：融合不得丢掉版本身份（否则引用在融合后静默变成"版本未知"）。 */
    @Test
    void applyPreservesDocProvenance() {
        ScoredChunk a = new ScoredChunk("A", "doc-A", "test", "t", "b", "s", List.of(), 1,
                "a1b2c3d4e5f6", "2026-09-16", new ScoredChunk.Scores(1, 0, 0, 0));
        List<ScoredChunk> out = RrfFuser.apply(List.of(a), List.of(), 60);
        assertEquals("a1b2c3d4e5f6", out.get(0).docDigest());
        assertEquals("2026-09-16", out.get(0).reviewedAt());
    }
}
