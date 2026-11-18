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

    // ───────────────── command-yield-accept AC-2/3：选槽保位（只追加，不改上面任何断言）───────────────

    /** 构造一个可被 L0 抽取的诊断叶：面包屑第二段含「排查」，围栏块内一条只读命令。 */
    private static ScoredChunk leaf(String id, String doc, double rerank, double rrf) {
        return new ScoredChunk(id, doc, "runbook",
                "[" + doc + " > 排查步骤 > 预检]\n\n```bash\nnetstat -ano | grep :8091\n```\n",
                doc + " > 排查步骤 > 预检", "opspilot", List.of(), 1,
                new ScoredChunk.Scores(1, 0, rrf, rerank));
    }

    /** 构造低信息槽（标题/段头）：面包屑 < 3 段、不在诊断段。 */
    private static ScoredChunk header(String id, String doc, String breadcrumb, double rerank, double rrf) {
        return new ScoredChunk(id, doc, "runbook", "[" + breadcrumb + "]\n# " + breadcrumb,
                breadcrumb, "opspilot", List.of(), 1,
                new ScoredChunk.Scores(1, 0, rrf, rerank));
    }

    @Test
    void diagnosticLeafReplacesLowestRankedHeaderSlotInTop1Doc() throws Exception {
        ScoredChunk t1 = header("T1", "rb-103", "rb-103 > 根因", 0.9, 5);
        ScoredChunk t2 = header("T2", "rb-103", "rb-103 > 验证身份而非端口", 0.8, 4);
        ScoredChunk other = header("O1", "rb-101", "rb-101 > 处置", 0.7, 3);
        ScoredChunk candidateLeaf = leaf("LEAF", "rb-103", 0.6, 2);
        when(es.search(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(t1, t2, other, candidateLeaf));
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankClient.Ranked(0, 0.9), new RerankClient.Ranked(1, 0.8),
                new RerankClient.Ranked(2, 0.7)));

        SearchOutcome out = service().search("端口被占的排查步骤", "tenant-demo", 1, "hybrid");

        assertEquals(3, out.chunks().size(), "引用数恒为 3");
        assertEquals(List.of("T1", "LEAF", "O1"), ids(out),
                "应替换 Top-1 文档中排名最低的标题槽（下标 1），其余槽位不变");
        verify(metrics, times(1)).diagnosticLeafSwap();
    }

    @Test
    void noSwapWhenTopAlreadyHasDiagnosticLeaf() throws Exception {
        ScoredChunk present = leaf("PRESENT", "rb-103", 0.9, 5);
        ScoredChunk other = header("O1", "rb-101", "rb-101 > 处置", 0.8, 4);
        ScoredChunk candidateLeaf = leaf("LEAF2", "rb-103", 0.6, 2);
        when(es.search(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(present, other, candidateLeaf));
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankClient.Ranked(0, 0.9), new RerankClient.Ranked(1, 0.8),
                new RerankClient.Ranked(2, 0.6)));

        SearchOutcome out = service().search("端口被占的排查步骤", "tenant-demo", 1, "hybrid");

        assertEquals(List.of("PRESENT", "O1", "LEAF2"), ids(out), "Top 已含诊断叶时不得再换");
        verify(metrics, never()).diagnosticLeafSwap();
    }

    @Test
    void diagnosticSectionHeaderWithoutCommandsDoesNotBlockSwap() throws Exception {
        ScoredChunk sectionHead = header("HEAD", "rb-115", "rb-115 > 排查步骤", 0.9, 5);
        ScoredChunk other = header("O1", "rb-101", "rb-101 > 处置", 0.8, 4);
        ScoredChunk title = header("TITLE", "rb-115", "rb-115", 0.7, 3);
        ScoredChunk candidateLeaf = leaf("LEAF", "rb-115", 0.6, 2);
        when(es.search(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(sectionHead, other, title, candidateLeaf));
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankClient.Ranked(0, 0.9), new RerankClient.Ranked(1, 0.8),
                new RerankClient.Ranked(2, 0.7)));

        SearchOutcome out = service().search("H2 库损坏的排查步骤", "tenant-demo", 1, "hybrid");

        assertEquals(List.of("HEAD", "O1", "LEAF"), ids(out),
                "只有诊断段头（无围栏命令）不算诊断叶，应继续保位；替换同文档排名最低的标题槽（下标 2）");
        verify(metrics, times(1)).diagnosticLeafSwap();
    }

    @Test
    void noSwapWhenCandidatePoolHasNoReadableDiagnosticLeaf() throws Exception {
        ScoredChunk t1 = header("T1", "rb-103", "rb-103 > 根因", 0.9, 5);
        ScoredChunk t2 = header("T2", "rb-103", "rb-103 > 处置", 0.8, 4);
        ScoredChunk other = header("O1", "rb-101", "rb-101 > 处置", 0.7, 3);
        ScoredChunk unreadable = new ScoredChunk("BAD", "rb-103", "runbook",
                "[rb-103 > 排查步骤]\n\n```bash\nrm -rf data\n```\n",
                "rb-103 > 排查步骤", "opspilot", List.of(), 1,
                new ScoredChunk.Scores(1, 0, 2, 0.6));
        when(es.search(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(t1, t2, other, unreadable));
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankClient.Ranked(0, 0.9), new RerankClient.Ranked(1, 0.8),
                new RerankClient.Ranked(2, 0.7)));

        SearchOutcome out = service().search("端口被占的排查步骤", "tenant-demo", 1, "hybrid");

        assertEquals(List.of("T1", "T2", "O1"), ids(out), "候选池无可抽取诊断叶时原样返回");
        verify(metrics, never()).diagnosticLeafSwap();
    }

    @Test
    void crossDocumentCandidateLeafIsRejected() throws Exception {
        ScoredChunk t1 = header("T1", "rb-103", "rb-103 > 根因", 0.9, 5);
        ScoredChunk t2 = header("T2", "rb-103", "rb-103 > 处置", 0.8, 4);
        ScoredChunk other = header("O1", "rb-101", "rb-101 > 处置", 0.7, 3);
        ScoredChunk foreign = leaf("FOREIGN", "rb-115", 0.6, 2);  // 别的文档的诊断叶
        when(es.search(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(t1, t2, other, foreign));
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankClient.Ranked(0, 0.9), new RerankClient.Ranked(1, 0.8),
                new RerankClient.Ranked(2, 0.7)));

        SearchOutcome out = service().search("端口被占的排查步骤", "tenant-demo", 1, "hybrid");

        assertEquals(List.of("T1", "T2", "O1"), ids(out), "不得跨文档选叶");
        verify(metrics, never()).diagnosticLeafSwap();
    }

    @Test
    void leafSelectionIsDeterministicByRerankThenRrfThenId() throws Exception {
        ScoredChunk t1 = header("T1", "rb-103", "rb-103 > 根因", 0.9, 5);
        ScoredChunk t2 = header("T2", "rb-103", "rb-103 > 处置", 0.8, 4);
        ScoredChunk other = header("O1", "rb-101", "rb-101 > 处置", 0.7, 3);
        // 注意：RrfFuser 构造候选时 rerank 分恒 0（只有进入 Top 的 chunk 才有 rerank 分），
        // 因此候选叶的实际排序键是 rrf 分；这里用不同 rrf 验证确定性排序的第二级。
        ScoredChunk leafLowRerank = leaf("A-LOW", "rb-103", 0.5, 9);
        ScoredChunk leafHighRerank = leaf("B-HIGH", "rb-103", 0.65, 1);
        when(es.search(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(t1, t2, other, leafLowRerank, leafHighRerank));
        when(rerank.rerank(anyString(), anyList(), anyInt())).thenReturn(List.of(
                new RerankClient.Ranked(0, 0.9), new RerankClient.Ranked(1, 0.8),
                new RerankClient.Ranked(2, 0.7)));

        SearchOutcome first = service().search("端口被占的排查步骤", "tenant-demo", 1, "hybrid");

        assertEquals(List.of("T1", "A-LOW", "O1"), ids(first), "候选叶按 rrf 分确定性排序");
    }

    @Test
    void fastPathSwapUsesFusedPoolAndKeepsRefsCount() throws Exception {
        String code = "51103_H2_CONCURRENT_WRITE_CORRUPTION";
        ScoredChunk t1 = new ScoredChunk("T1", "rb-115", "runbook", "[rb-115 > 适用症状]\n# 适用症状",
                "rb-115 > 适用症状", "opspilot", List.of(code), 1, new ScoredChunk.Scores(1, 0, 0, 0));
        ScoredChunk t2 = header("T2", "rb-115", "rb-115 > 处置", 0, 4);
        ScoredChunk other = header("O1", "rb-101", "rb-101 > 处置", 0, 3);
        ScoredChunk candidateLeaf = leaf("LEAF-FAST", "rb-115", 0, 2);
        when(es.search(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(t1, t2, other, candidateLeaf));
        // 查询含错误码符号且 ES Top-1 命中该码 → 走快路径，不调 rerank

        SearchOutcome out = service().search(code + " MVStoreException: File is corrupted",
                "tenant-demo", 1, "hybrid");

        assertTrue(out.fastPath(), "应走快路径");
        verifyNoInteractions(rerank);
        assertEquals(3, out.chunks().size());
        assertEquals(List.of("T1", "LEAF-FAST", "O1"), ids(out), "快路径从 fused 池选叶");
        verify(metrics, times(1)).diagnosticLeafSwap();
    }
}
