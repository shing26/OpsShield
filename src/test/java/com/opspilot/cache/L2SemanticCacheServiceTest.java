package com.opspilot.cache;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;
import io.qdrant.client.QdrantClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OP-R3（2026-09-28）：L2 语义缓存**写入不得阻塞请求尾段**。
 *
 * 原实现的 `.get(5, SECONDS)` 发生在 LLM 已答完、答案已下发之后——纯属缓存写入的等待，
 * 却直接计入用户可见的端到端时延。本测试锁两件事：① 投递即返回（不等 upsert）；
 * ② 写入确实发生且失败被吞（**不是**把写入悄悄丢了——那是另一种假绿）。
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
            return null;
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
}
