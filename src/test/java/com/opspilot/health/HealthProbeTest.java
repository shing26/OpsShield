package com.opspilot.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.google.common.util.concurrent.Futures;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.KvStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** P4 健康面：三依赖全 UP 聚合 UP；单依赖故障必须变 DEGRADED 且异常被吞（健康检查不拖死服务）。 */
class HealthProbeTest {

    private ElasticsearchClient es;
    private QdrantClient qdrant;
    private KvStore kv;
    private HealthProbe probe;

    @BeforeEach
    void setUp() {
        es = mock(ElasticsearchClient.class);
        qdrant = mock(QdrantClient.class);
        kv = mock(KvStore.class);
        // 键不存在没关系——没抛异常=连通；这里 stub 的正是"单键 GET 不 SCAN"那条路径
        when(kv.exists(anyString())).thenReturn(false);
        OpsPilotProperties props = new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://mock", "", "m", 1024, "m", "m", 30, 3000, 15000, "mock"),
                new OpsPilotProperties.Es("http://localhost:9200", "opspilot-chunks-read", "elastic", "", 3000, 10000),
                new OpsPilotProperties.Qdrant("localhost", 6334, "opspilot-vectors-live", "semantic-cache", ""),
                new OpsPilotProperties.Jwt("secret", 3600), null, null, null, null);
        // live 用例：两个客户端 bean 在场（provider 给真 mock），内存索引缺席
        probe = new HealthProbe(provider(es), provider(qdrant), kv, provider(null), props);
    }

    /** 造一个"按需给出该 bean"的 provider；给 null 就是"这个 bean 不存在"。 */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private void stubHealthy() throws Exception {
        when(qdrant.getCollectionInfoAsync(anyString()))
                .thenReturn(Futures.immediateFuture(
                        Collections.CollectionInfo.newBuilder().setPointsCount(251).build()));
        when(es.ping()).thenReturn(new BooleanResponse(true));
        CountResponse cnt = mock(CountResponse.class);
        when(cnt.count()).thenReturn(251L);
        when(es.count(any(co.elastic.clients.elasticsearch.core.CountRequest.class))).thenReturn(cnt);
    }

    @Test
    void allDependenciesUpAggregatesUp() throws Exception {
        stubHealthy();
        Map<String, Object> s = probe.summary();
        assertEquals("UP", s.get("status"));
        assertEquals("251 docs", ((HealthProbe.Comp) s.get("es")).detail());
        assertEquals(Boolean.FALSE, s.get("live_mode")); // mock 形态（apiKey 空）
    }

    @Test
    void singleDependencyFailureDegradesWithoutThrowing() throws Exception {
        stubHealthy();
        when(qdrant.getCollectionInfoAsync(anyString()))
                .thenThrow(new RuntimeException("collection not found (alias missing)"));
        Map<String, Object> s = probe.summary();
        assertEquals("DEGRADED", s.get("status"));
        assertEquals("DOWN", ((HealthProbe.Comp) s.get("qdrant")).status());
        assertEquals("UP", ((HealthProbe.Comp) s.get("es")).status(), "其余组件不受牵连");
    }

    /** 结构化数值（Ops Console 不再解析 "251 docs" 字符串）；DOWN 时 value=null。 */
    @Test
    void compCarriesStructuredCount() throws Exception {
        stubHealthy();
        Map<String, Object> s = probe.summary();
        assertEquals(251L, ((HealthProbe.Comp) s.get("es")).value());
        assertEquals(251L, ((HealthProbe.Comp) s.get("qdrant")).value());
        assertNull(((HealthProbe.Comp) s.get("redis")).value(), "redis 无计数语义=null");
    }

    /**
     * W9（外审②惊群锁）：TTL 窗内并发 5 次 summary → 真实探测只发生 1 轮。
     * 无锁实现下 5 个线程会各自穿透缓存发起探测（ES/Qdrant 请求被面板轮询放大）。
     */
    @Test
    void ttlCacheCollapsesConcurrentProbesToSingleFlight() throws Exception {
        stubHealthy();
        var threads = new java.util.ArrayList<Thread>();
        var start = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(5);
        for (int i = 0; i < 5; i++) {
            var t = new Thread(() -> {
                try {
                    start.await();
                    probe.summary();
                    probe.summary();          // 同线程第二击必须命中缓存
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
            threads.add(t);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS));
        verify(es, times(1)).ping();
        verify(qdrant, times(1)).getCollectionInfoAsync(anyString());
    }
}
