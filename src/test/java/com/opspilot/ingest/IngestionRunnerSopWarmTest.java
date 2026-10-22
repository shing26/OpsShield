package com.opspilot.ingest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.retrieval.InMemoryIndex;
import com.opspilot.store.KvStore;
import io.qdrant.client.QdrantClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SOP 清单 TTL 与启动补灌（G1-W1.3，2026-10-22 运维审查）。
 *
 * <p>为什么必须钉住 TTL：SOP 键若永不过期，语料删掉的操作步骤会永久留下，L2 档位继续直出；
 * 而在 Redis {@code volatile-lru} 策略下，无 TTL 的键连被淘汰的资格都没有。为什么还要钉住补灌：
 * 有了 TTL 就会有"过期了但没人重灌"的状态，本测试锁住"只补缺失租户、且不因失败阻断启动"。
 *
 * <p>用 mock {@link KvStore} 而非真存储：断言的是**写入了哪些键、TTL 是不是 7 天**，
 * 而不是内存 Map 能不能用（那是 MemoryKvStoreTest 的事）。
 */
class IngestionRunnerSopWarmTest {

    @TempDir
    Path tmp;

    private static final String CHUNK_LINE = "{"
            + "\"chunk_id\":\"c1\",\"doc_id\":\"doc-1\",\"type\":\"sop\","
            + "\"text\":\"步骤一：重启连接池\",\"breadcrumb\":\"止损操作\","
            + "\"metadata\":{\"service\":\"orders\",\"endpoint\":\"/orders\",\"method\":\"GET\","
            + "\"error_codes\":[\"50012_DB_TIMEOUT\"],\"auth_level\":2,\"env\":\"prod\",\"tenant\":\"t1\"}}";

    /** live 形态的最小 props：ensureSopWarm 只看 storage 是否 memory。 */
    private static OpsPilotProperties liveProps() {
        return new OpsPilotProperties(null, null, null, null, null, null, null, null);
    }

    private String chunksFile() throws Exception {
        Path p = tmp.resolve("chunks.jsonl");
        Files.writeString(p, CHUNK_LINE + "\n");
        return p.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    private IngestionRunner runner(KvStore store, String chunksPath) {
        return new IngestionRunner(emptyProvider(), emptyProvider(), store, emptyProvider(),
                mock(EmbeddingClient.class), liveProps(), mock(L2SemanticCacheService.class), chunksPath);
    }

    @Test
    void ensureSopWarmWritesMissingTenantAndAppliesSevenDayTtl() throws Exception {
        KvStore store = mock(KvStore.class);
        when(store.mapIsEmpty("sop:t1:steps")).thenReturn(true);   // 该租户清单缺失

        runner(store, chunksFile()).ensureSopWarm();

        verify(store).deleteByPrefix("sop:t1:");   // 只重写缺失租户（不是全量清空）
        verify(store).mapPut("sop:t1:steps", "doc-1", "步骤一：重启连接池");
        verify(store).setAdd("sop:t1:code:50012_DB_TIMEOUT", "doc-1");
        verify(store).setAdd("sop:t1:service:orders", "doc-1");
        verify(store).expire("sop:t1:steps", IngestionRunner.SOP_TTL);
        verify(store).expire("sop:t1:code:50012_DB_TIMEOUT", IngestionRunner.SOP_TTL);
        verify(store).expire("sop:t1:service:orders", IngestionRunner.SOP_TTL);
        assertEquals(Duration.ofDays(7), IngestionRunner.SOP_TTL, "TTL 口径是 7 天");
    }

    @Test
    void ensureSopWarmLeavesHealthyTenantsUntouched() throws Exception {
        KvStore store = mock(KvStore.class);   // mapIsEmpty 默认 false = 清单在 TTL 内
        runner(store, chunksFile()).ensureSopWarm();

        verify(store, never()).deleteByPrefix(anyString());
        verify(store, never()).mapPut(anyString(), anyString(), anyString());
        verify(store, never()).expire(anyString(), any(Duration.class));
    }

    /**
     * 补灌失败（这里用 chunks 文件缺失模拟"补灌拿不到数据"）必须被吞成 warn——
     * 它由 ApplicationRunner 在启动尾段调用，抛出去就是整机起不来。
     */
    @Test
    void ensureSopWarmFailureNeverPropagates() {
        KvStore store = mock(KvStore.class);
        assertDoesNotThrow(() -> runner(store, tmp.resolve("missing.jsonl").toString()).ensureSopWarm());
        verify(store, never()).mapPut(anyString(), anyString(), anyString());
    }
}
