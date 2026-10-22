package com.opspilot.cache;

import com.google.common.util.concurrent.Futures;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L2 语义缓存的正确性锁（G1-W1.3，2026-10-22 运维审查）。
 *
 * <p>三组断言各自对应一个"缓存会变旧/会撑爆"的真实风险：
 * <ul>
 *   <li><b>live TTL filter</b>：lookup 必须带 {@code created_at >= now-ttl} 条件，清理必须按
 *       {@code created_at < cutoff} 删——没有它，缓存就是永不过期的第二份事实源；</li>
 *   <li><b>memory 有界 LRU</b>：条目要带时间戳、命中要刷新最近使用、超上限淘汰最久未用
 *       （旧实现是无上限 CopyOnWriteArrayList 全表扫描）；</li>
 *   <li><b>写路径语义不变</b>（OP-R3）：投递即返回、失败被吞但任务真的执行。</li>
 * </ul>
 *
 * <p>memory 用例走包内构造注入 {@link Duration} 级 TTL：生产默认 72 小时，测试当然不能等 72 小时。
 */
class L2SemanticCacheServiceTest {

    private static final String CACHE_COLLECTION = "opspilot-l2-cache";

    private QdrantClient qdrant;
    private ExecutorService vt;
    private OpsPilotProperties props;

    @BeforeEach
    void setUp() {
        qdrant = mock(QdrantClient.class);
        vt = Executors.newVirtualThreadPerTaskExecutor();
        props = new OpsPilotProperties(
                null, null,
                new OpsPilotProperties.Qdrant("localhost", 6334, "opspilot-chunks-read", CACHE_COLLECTION, ""),
                null, new OpsPilotProperties.Cache(2, 0.95), null, null, null);
    }

    @AfterEach
    void tearDown() {
        vt.shutdownNow();
    }

    private L2SemanticCacheService service() {
        return new L2SemanticCacheService(provider(qdrant), mock(EmbeddingClient.class), props, vt);
    }

    /** live 用例：Qdrant 客户端 bean 在场（memory 模式下它根本不存在，见 ADR-0022）。 */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    /** 变异验证（改坏必红）：把 store 改回同步 `.get(...)` → 首条断言必红（耗时 > 200ms）。 */
    @Test
    void storeReturnsImmediatelyWithoutWaitingForUpsert() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(qdrant.upsertAsync(anyString(), anyList())).thenAnswer(inv -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);   // 模拟慢 upsert：同步实现会在此卡住
            return Futures.immediateFuture(null);
        });

        long t0 = System.nanoTime();
        service().store("q", new float[]{0.1f}, "{\"answer\":\"x\"}", 1, "tenant-demo");
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(ms < 200, "store 必须投递即返回（原实现同步等 .get(5s)），实际 " + ms + "ms");
        assertTrue(entered.await(2, TimeUnit.SECONDS), "写入任务必须真的被投递——不是静默丢弃");
        release.countDown();

        // 写的是**专属缓存集合**，不是检索主集合（写错集合会把缓存载荷混进语料面）
        verify(qdrant).upsertAsync(eq(CACHE_COLLECTION), anyList());
    }

    /** 写失败仍然被吞（与同步版同语义），但任务本身必须跑到——否则"没报错"只是因为压根没执行。 */
    @Test
    void upsertFailureIsSwallowedButTaskStillRuns() {
        when(qdrant.upsertAsync(anyString(), anyList())).thenThrow(new RuntimeException("qdrant down"));
        assertDoesNotThrow(() -> service().store("q", new float[]{0.1f}, "{}", 1, "tenant-demo"),
                "写缓存失败不得上抛（缓存不是主链路）");
        verify(qdrant, timeout(2_000)).upsertAsync(anyString(), anyList());
    }

    /**
     * live lookup 必须带 TTL 过滤。断言"条件里出现 created_at 且是 gte 且起点 = now - ttl"，
     * 而不是只断言"发了 search"——只看调用次数无法区分"带 TTL"和"没带 TTL"。
     */
    @Test
    void liveLookupFiltersOnCreatedAtWithinTtl() throws Exception {
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.embedOne(anyString())).thenReturn(new float[]{1f, 0f});
        when(qdrant.searchAsync(any(Points.SearchPoints.class))).thenReturn(Futures.immediateFuture(List.of()));

        long before = System.currentTimeMillis() - Duration.ofHours(L2SemanticCacheService.DEFAULT_TTL_HOURS).toMillis();
        new L2SemanticCacheService(provider(qdrant), embedding, props, vt).lookup("q", "tenant-demo", 1);
        long after = System.currentTimeMillis() - Duration.ofHours(L2SemanticCacheService.DEFAULT_TTL_HOURS).toMillis();

        ArgumentCaptor<Points.SearchPoints> captor = ArgumentCaptor.forClass(Points.SearchPoints.class);
        verify(qdrant).searchAsync(captor.capture());
        Points.Filter filter = captor.getValue().getFilter();
        assertEquals(3, filter.getMustCount(), "tenant + max_auth_level + created_at 三个硬条件");
        Points.Condition created = filter.getMustList().stream()
                .filter(c -> "created_at".equals(c.getField().getKey()))
                .findFirst().orElseThrow(() -> new AssertionError("lookup 缺 created_at TTL 过滤: " + filter));
        double gte = created.getField().getRange().getGte();
        assertTrue(gte >= before && gte <= after,
                "created_at 下限应为 now-ttl（" + before + ".." + after + "），实际 " + gte);
    }

    /** 每小时清理：按 created_at < cutoff 删缓存集合里的点（只删自己的集合）。 */
    @Test
    void evictExpiredDeletesOldPointsByCreatedAtFilter() throws Exception {
        when(qdrant.deleteAsync(eq(CACHE_COLLECTION), any(Points.Filter.class)))
                .thenReturn(Futures.immediateFuture(null));

        long before = System.currentTimeMillis() - Duration.ofHours(L2SemanticCacheService.DEFAULT_TTL_HOURS).toMillis();
        service().evictExpired();
        long after = System.currentTimeMillis() - Duration.ofHours(L2SemanticCacheService.DEFAULT_TTL_HOURS).toMillis();

        ArgumentCaptor<Points.Filter> captor = ArgumentCaptor.forClass(Points.Filter.class);
        verify(qdrant).deleteAsync(eq(CACHE_COLLECTION), captor.capture());
        Points.Condition created = captor.getValue().getMustList().stream()
                .filter(c -> "created_at".equals(c.getField().getKey()))
                .findFirst().orElseThrow(() -> new AssertionError("清理缺 created_at 条件"));
        double lt = created.getField().getRange().getLt();
        assertTrue(lt >= before && lt <= after, "清理 cutoff 应为 now-ttl，实际 " + lt);
    }

    // ---------- memory 后端：TTL / 有界 LRU / flush / size ----------

    private OpsPilotProperties memoryProps() {
        return new OpsPilotProperties(null, new OpsPilotProperties.Storage("memory"), null, null, null,
                new OpsPilotProperties.Cache(2, 0.95), null, null, null);
    }

    /** 每个 query 一个固定方向向量：让"命中/未命中"由余弦判定，而不是由条目存在性碰运气。 */
    private static EmbeddingClient directionalEmbedding() {
        EmbeddingClient e = mock(EmbeddingClient.class);
        when(e.embedOne(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0, String.class);
            return switch (q) {
                case "q1" -> new float[]{1f, 0f};
                case "q2" -> new float[]{0f, 1f};
                default -> new float[]{-1f, 0f};
            };
        });
        return e;
    }

    private L2SemanticCacheService memoryService(Duration ttl, int maxEntries) {
        return new L2SemanticCacheService(provider(null), directionalEmbedding(), memoryProps(), vt, ttl, maxEntries);
    }

    private static void await(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("等待被中断: " + what);
            }
        }
        fail("等待超时: " + what);
    }

    @Test
    void memoryEntriesCarryTimestampAndExpireInLookup() throws Exception {
        L2SemanticCacheService svc = memoryService(Duration.ofMillis(300), 100);
        svc.store("q1", new float[]{1f, 0f}, "{\"a\":1}", 1, "t1");
        await(() -> svc.size() == 1, "写入完成");

        assertNotNull(svc.lookup("q1", "t1", 1), "TTL 内必须命中");
        Thread.sleep(400);   // 超过 300ms TTL
        assertNull(svc.lookup("q1", "t1", 1), "TTL 外必须未命中（惰性淘汰）");
        assertEquals(0, svc.size(), "lookup 应顺带清掉过期条目");
    }

    @Test
    void memoryCacheIsBoundedAndEvictsLeastRecentlyUsed() {
        L2SemanticCacheService svc = memoryService(Duration.ofHours(1), 2);
        svc.store("q1", new float[]{1f, 0f}, "{\"a\":1}", 1, "t1");
        svc.store("q2", new float[]{0f, 1f}, "{\"a\":2}", 1, "t1");
        await(() -> svc.size() == 2, "两条写入完成");

        assertNotNull(svc.lookup("q1", "t1", 1), "先确认 q1 在（这次查询把它刷成最近使用）");

        svc.store("q3", new float[]{-1f, 0f}, "{\"a\":3}", 1, "t1");
        await(() -> svc.size() == 2, "上限仍为 2（淘汰最久未用，而不是无界增长）");

        assertNull(svc.lookup("q2", "t1", 1), "q2 是最久未用的一条，应被淘汰");
        assertNotNull(svc.lookup("q1", "t1", 1), "q1 刚被查询过，必须还在（access-order LRU）");
    }

    @Test
    void flushClearsMemoryAndSizeReportsIt() {
        L2SemanticCacheService svc = memoryService(Duration.ofHours(1), 100);
        svc.store("q1", new float[]{1f, 0f}, "{}", 1, "t1");
        svc.store("q2", new float[]{0f, 1f}, "{}", 1, "t1");
        await(() -> svc.size() == 2, "两条写入完成");

        svc.flush();
        assertEquals(0, svc.size(), "flush 后条目必须清零");
        assertNull(svc.lookup("q1", "t1", 1), "flush 后不得再命中（reingest 后旧 refs 已失效）");
    }

    /** 身份过滤与 live 逐条对应：租户不等/密级不够都不得命中（P1，缓存不是越权旁路）。 */
    @Test
    void memoryLookupKeepsTenantAndAuthLevelGuards() {
        L2SemanticCacheService svc = memoryService(Duration.ofHours(1), 100);
        svc.store("q1", new float[]{1f, 0f}, "{}", 3, "t1");
        await(() -> svc.size() == 1, "写入完成");

        assertNull(svc.lookup("q1", "t2", 3), "跨租户零命中");
        assertNull(svc.lookup("q1", "t1", 2), "请求方密级低于条目上限时不得回放");
        assertNotNull(svc.lookup("q1", "t1", 3));
    }
}
