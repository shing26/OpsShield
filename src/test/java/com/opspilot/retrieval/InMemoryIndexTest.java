package com.opspilot.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import com.opspilot.chunk.Chunk;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * memory 索引的两条不变式（ADR-0022 决策 4 + 后果清单点名要求的对照测试）。
 *
 * <p>① **权限过滤是构造保证**：{@link InMemoryIndex#visible} 是唯一取数入口，两条腿都只能经它
 * 拿候选。这里用合成语料逐条钉住 fail-closed 边界（缺 tenant、跨租户、越密级）。
 *
 * <p>② **低密级侧的引用集不得出现高密级 chunk**（ADR 原文）：同一条 query、同一个租户、
 * 两个密级各跑一次两条腿，低密级的结果集必须是高密级的子集，且一条越界 chunk 都不许出现。
 * 这条判据在 live 侧由 ES/Qdrant 的引擎过滤保证，在 memory 侧由谓词保证——
 * 两边都必须成立，否则"演示出来的权限隔离与生产不是一回事"。
 *
 * <p>变异验证（改坏必红）：把 {@code visible} 里的 tenant 比较删掉 → 用例 2/3 红；
 * 把谓词交给 {@code InMemoryLexicalLeg} 之外的路径（例如直接遍历 {@code docs}）→
 * 用例 5 的"低密级 ⊆ 高密级"立刻被跨租户条目打破。
 */
class InMemoryIndexTest {

    private static final int DIM = 64;

    private InMemoryIndex index;
    private EmbeddingClient embedding;
    private InMemoryLexicalLeg lexical;
    private InMemoryVectorLeg vector;

    @BeforeEach
    void setUp() {
        index = new InMemoryIndex();
        embedding = new EmbeddingClient(mock(RestClient.class), props());
        lexical = new InMemoryLexicalLeg(index);
        vector = new InMemoryVectorLeg(index, embedding);

        List<Chunk> chunks = List.of(
                chunk("c-l1", "svc-a", "tenant-internal", 1, "50012_DB_TIMEOUT 连接池耗尽排查步骤"),
                chunk("c-l3", "svc-a", "tenant-internal", 3, "50012_DB_TIMEOUT 止损操作 提权步骤"),
                chunk("c-acme", "svc-b", "tenant-acme", 1, "50012_DB_TIMEOUT 外部租户手册"));
        // 走生产那条路取向量（mock 模式下 EmbeddingClient 内部就是本地词法哈希，零出站）
        index.load(chunks, embedding.embed(chunks.stream().map(Chunk::text).toList()));
    }

    @Test
    void visibleFiltersByTenantAndLevel() {
        assertEquals(List.of("c-l1"), docIds(index.visible("tenant-internal", 1)));
        assertEquals(List.of("c-l1", "c-l3"), docIds(index.visible("tenant-internal", 3)));
        assertEquals(List.of("c-acme"), docIds(index.visible("tenant-acme", 3)));
    }

    @Test
    void visibleIsFailClosedOnMissingTenant() {
        assertTrue(index.visible(null, 3).isEmpty(), "缺 tenant 必须给空集，不是全部文档");
        assertTrue(index.visible("  ", 3).isEmpty(), "空 tenant 同理");
        assertTrue(index.visible("tenant-internal", 0).isEmpty(), "level=0 命中空集（库内文档 >=1）");
    }

    @Test
    void sizeReportsTheWholeCorpusNotTheVisibleSlice() {
        assertEquals(3, index.size());
        assertTrue(index.builtAtMs() > 0, "装载后必须留下真实的装载时刻");
    }

    /** 未装载时 {@code built_at} 是 0（调用方须渲染成"未知"），不是当前时间。 */
    @Test
    void builtAtIsZeroBeforeAnyLoad() {
        assertEquals(0L, new InMemoryIndex().builtAtMs(),
                "不许拿 System.currentTimeMillis() 冒充'刚刚重建'");
    }

    @Test
    void lexicalLegNeverReturnsChunksAboveTheCallersLevel() {
        Set<String> low = chunkIds(lexical.search("50012_DB_TIMEOUT 排查", "tenant-internal", 1, 10));
        Set<String> high = chunkIds(lexical.search("50012_DB_TIMEOUT 排查", "tenant-internal", 3, 10));
        assertTrue(low.contains("c-l1"));
        assertFalse(low.contains("c-l3"), "低密级侧出现了高密级 chunk");
        assertTrue(high.containsAll(low), "低密级结果必须是高密级的子集");
    }

    @Test
    void vectorLegNeverReturnsChunksAboveTheCallersLevel() throws Exception {
        Set<String> low = chunkIds(vector.search("50012_DB_TIMEOUT 排查", "tenant-internal", 1, 10));
        Set<String> high = chunkIds(vector.search("50012_DB_TIMEOUT 排查", "tenant-internal", 3, 10));
        assertFalse(low.contains("c-l3"), "低密级侧出现了高密级 chunk");
        assertTrue(high.containsAll(low), "低密级结果必须是高密级的子集");
        assertFalse(low.contains("c-acme"), "跨租户泄漏");
    }

    /** 两条腿的排序必须真的不同，否则 RRF 融合出来的是装饰（ADR-0022 决策 3 的前提）。 */
    @Test
    void twoLegsScoreDifferently() throws Exception {
        var lex = lexical.search("50012_DB_TIMEOUT", "tenant-internal", 3, 10);
        var vec = vector.search("50012_DB_TIMEOUT", "tenant-internal", 3, 10);
        assertFalse(lex.isEmpty());
        assertFalse(vec.isEmpty());
        assertTrue(lex.stream().allMatch(c -> c.esScore() > 0 && c.vectorScore() == 0),
                "词法腿只填 es 分");
        assertTrue(vec.stream().allMatch(c -> c.vectorScore() > 0 && c.esScore() == 0),
                "向量腿只填 vector 分");
    }

    /** 词法腿的精确命中权重（错误码 20）必须压过纯词面重合，否则快路径形同虚设。 */
    @Test
    void errorCodeHitOutranksBareTokenOverlap() {
        var hits = lexical.search("50012_DB_TIMEOUT", "tenant-internal", 3, 10);
        assertTrue(hits.get(0).esScore() >= 20.0, "错误码精确命中应拿满 20 分权重");
    }

    private static List<String> docIds(List<InMemoryIndex.Doc> docs) {
        return docs.stream().map(d -> d.chunk().chunkId()).collect(Collectors.toList());
    }

    private static Set<String> chunkIds(List<ScoredChunk> docs) {
        return docs.stream().map(ScoredChunk::chunkId).collect(Collectors.toSet());
    }

    private static Chunk chunk(String id, String service, String tenant, int level, String text) {
        return new Chunk(id, "doc-" + id, "runbook", text, "Ops > " + service + " > 排查",
                new Chunk.Metadata(service, null, null, List.of("50012_DB_TIMEOUT"), level, "prod", tenant));
    }

    private static OpsPilotProperties props() {
        return new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://mock", "", "m", DIM, "m", "m", 30, 3000, 15000, "mock"),
                new OpsPilotProperties.Storage("memory"),
                null, null, null, null, null, null, null);
    }
}
