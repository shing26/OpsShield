package com.opspilot.retrieval;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.RerankClient;
import com.opspilot.metrics.OpsMetrics;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Spec 锁（P1-1）：L1 降级 = es_only 必须同时摘除向量路与 Rerank，
 * 结果保持 RRF（此处即 ES）顺序且 rerank_score 恒 0；hybrid 路则必须精排。
 */
class HybridSearchServiceTest {

    private EsSearchService es;
    private QdrantSearchService qdrant;
    private RerankClient rerank;
    private OpsMetrics metrics;
    private ExecutorService vt;
    private OpsPilotProperties props;

    @BeforeEach
    void setUp() {
        es = mock(EsSearchService.class);
        qdrant = mock(QdrantSearchService.class);
        rerank = mock(RerankClient.class);
        metrics = mock(OpsMetrics.class);
        vt = Executors.newVirtualThreadPerTaskExecutor();
        props = new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://mock", "", "m", 1024, "m", "m", 30, 3000, 15000, "mock"),
                new OpsPilotProperties.Es("http://localhost:9200", "idx", "elastic", "", 3000, 10000, 100, 100),
                new OpsPilotProperties.Qdrant("localhost", 6334, "c", "cache", ""),
                new OpsPilotProperties.Jwt("secret", 3600),
                new OpsPilotProperties.Cache(2, 0.95),
                new OpsPilotProperties.Storm(30, 60),
                new OpsPilotProperties.Retrieval(10, 10, 60, 20, 3, 800, 0.05),
                new OpsPilotProperties.Degrade(5, 3, 30));
    }

    @AfterEach
    void tearDown() {
        vt.shutdownNow();
    }

    private static ScoredChunk c(String id) {
        return new ScoredChunk(id, id, "runbook", "text-" + id, "bc", "svc", List.of(), 1,
                new ScoredChunk.Scores(1.0, 0, 0, 0));
    }

    private static List<String> ids(SearchOutcome out) {
        return out.chunks().stream().map(ScoredChunk::chunkId).toList();
    }

    private HybridSearchService service() {
        return new HybridSearchService(es, qdrant, rerank, metrics, vt, props);
    }

    @Test
    void esOnlyModeSkipsRerankAndKeepsEsOrder() throws Exception {
        when(es.search(anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(c("A"), c("B"), c("C")));
        // 无错误码符号的查询：不触发快路径，rerank 与否完全由降级分支决定
        SearchOutcome out = service().search("数据库连接池耗尽的排查步骤", "tenant-demo", 1, "es_only");

        verifyNoInteractions(rerank);
        assertEquals(List.of("A", "B", "C"), ids(out));
        assertTrue(out.chunks().stream().allMatch(x -> x.rerankScore() == 0.0),
                "es_only 不得携带任何 rerank 分数");
        assertEquals("es_only", out.mode());
        assertEquals(1.0, out.topRelevance(), 1e-9); // 降级纯 ES：best-effort，不做门控
    }

    @Test
    void hybridModeAppliesRerankOrderAndRelevance() throws Exception {
        when(es.search(anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(c("A"), c("B"), c("C")));
        when(qdrant.search(anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankClient.Ranked(2, 0.9), new RerankClient.Ranked(0, 0.4),
                new RerankClient.Ranked(1, 0.1)));

        SearchOutcome out = service().search("数据库连接池耗尽的排查步骤", "tenant-demo", 1, "hybrid");

        verify(rerank, times(1)).rerank(anyString(), anyList(), eq(3)); // finalTopK
        assertEquals(List.of("C", "A", "B"), ids(out));                 // 精排序覆盖 RRF 序
        assertEquals(0.9, out.chunks().get(0).rerankScore(), 1e-9);
        assertEquals(0.9, out.topRelevance(), 1e-9);                    // 门控取 Top-1 相关度
    }

    /**
     * G2 分段耗时的回归锁：分腿耗时必须在**腿内**测。两腿并行发起，若把计时围在 joinSafe 外面，
     * 测到的是"等待时间"，会把慢腿的耗时重复计入两条腿——那正是本项要修的可观测缺陷。
     *
     * 判据用"桩里 sleep 的下限"而非精确值：耗时天然抖动，断言精确值必然 flaky。
     * 变异验证（改坏必红）：摘掉 supply(...) 的 finally 计时段（elapsedMs 恒 0）→ 前两条断言必红；
     * 把 rerankMs 恒置 0 → 第三条断言必红。
     */
    @Test
    void legTimingsAreMeasuredInsideEachLeg() throws Exception {
        when(es.search(anyString(), anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(30);
            return List.of(c("A"), c("B"), c("C"));
        });
        when(qdrant.search(anyString(), anyString(), anyInt(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(60);
            return List.of();
        });
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenAnswer(inv -> {
            Thread.sleep(40);
            return List.of(new RerankClient.Ranked(0, 0.9));
        });

        SearchOutcome out = service().search("数据库连接池耗尽的排查步骤", "tenant-demo", 1, "hybrid");
        LegTimings legs = out.legs();

        assertTrue(legs.esMs() >= 25, "ES 腿耗时未在腿内采集: " + legs);
        assertTrue(legs.vectorMs() >= 55, "向量腿耗时未在腿内采集: " + legs);
        assertTrue(legs.rerankMs() >= 35, "Rerank 耗时未采集: " + legs);
        // 两腿并行、顺序 join：检索总时长 ≥ 关键路径（慢腿 + RRF + Rerank），**不是四者之和**
        assertTrue(out.tookMs() >= Math.max(legs.esMs(), legs.vectorMs()) + legs.rerankMs() - 5,
                "检索总时长应 ≥ 关键路径: took=" + out.tookMs() + " " + legs);
    }

    /** 快路径 / 降级不调 Rerank：rerankMs 必须是 0——它表示**未调用**，不是"很快"。 */
    @Test
    void rerankTimingIsZeroWhenRerankIsSkipped() throws Exception {
        when(es.search(anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(c("A")));
        SearchOutcome out = service().search("数据库连接池耗尽的排查步骤", "tenant-demo", 1, "es_only");
        assertEquals(0, out.legs().rerankMs(), "es_only 未调 Rerank，rerankMs 必须为 0（未调用≠很快）");
    }

    /**
     * OP-R5 判别锁（2026-09-28）：**两腿都非空且 chunk 不相交**时，向量腿独有的 chunk 必须真的
     * 进入 `fused → Rerank` 候选池。
     *
     * 为什么必须补这一条：既有两条 hybrid 用例的 qdrant 桩都返回 `List.of()`（空），于是"服务层"
     * 从未证明过并集进了重排池。而评测报告里 `hybrid` 与 `vector_only` 读数**逐位相同**
     * （exact 1.0/1.0/1.0；semantic hit@1 0.88 / hit@3 1.0 / MRR 0.940），指标层无法分辨
     * "融合生效但没改变 top-3"与"ES 腿结果根本没进融合"——本条把后一种解释**当场排除**，
     * 让"现夹具下测不出融合增益"成为可信结论而不是可疑结论。
     *
     * 变异验证（改坏必红）：把 `RrfFuser.apply(esRes, vecRes, rrfK)` 的 `vecRes` 换成 `List.of()`
     * → "向量腿独有 chunk 未进池" 必红。
     */
    @Test
    void bothLegsNonOverlappingChunksReachRerankPool() throws Exception {
        when(es.search(anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(c("A"), c("B"), c("C")));
        when(qdrant.search(anyString(), anyString(), anyInt(), anyInt())).thenReturn(List.of(c("D"), c("E")));
        when(rerank.rerank(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new RerankClient.Ranked(0, 0.9)));

        service().search("数据库连接池耗尽的排查步骤", "tenant-demo", 1, "hybrid");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> pool = ArgumentCaptor.forClass(List.class);
        verify(rerank, times(1)).rerank(anyString(), pool.capture(), eq(3));   // finalTopK
        List<String> poolTexts = pool.getValue();
        assertTrue(poolTexts.contains("text-D") && poolTexts.contains("text-E"),
                "向量腿独有 chunk 未进入重排池——融合接线断了: " + poolTexts);
        assertTrue(poolTexts.containsAll(List.of("text-A", "text-B", "text-C")),
                "ES 腿独有 chunk 也必须进池: " + poolTexts);
    }
}
