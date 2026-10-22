package com.opspilot.ingest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetAliasRequest;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.util.ObjectBuilder;
import com.google.common.util.concurrent.Futures;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.store.KvStore;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * reingest 与 L2 缓存的配套关系（G1-W1.3）：**蓝绿切流成功后必须清空 L2，失败路径不得清**。
 *
 * <p>为什么这条不能只靠 code review：L2 条目里存的是含 refs 的完整答案，refs 指向 chunk_id。
 * reingest 换掉整个语料后旧 chunk_id 已不存在，回放出来的溯源是死链；更糟的是它看起来是"命中"，
 * 而"命中的是旧语料"比"未命中"更难被发现。所以清空点必须钉在"别名已原子切换"之后，
 * 且前面的计数验收失败时**不能**清（否则旧语料 + 空缓存，白丢一次预热）。
 *
 * <p>本测试用 mock 的 ES/Qdrant 走完整 {@code ingest()}：验收条数、别名切换、SOP 预热都在真实路径上，
 * 只把外部 I/O 换成可控桩。变异验证：把 {@code l2.flush()} 移到 {@code verifyCounts} 之前 →
 * 失败用例的 {@code never()} 断言必红；删掉 flush → 成功用例必红。
 */
class IngestionRunnerCacheFlushTest {

    private static final String CACHE = "opspilot-l2-cache";
    private static final String CHUNK_LINE = "{"
            + "\"chunk_id\":\"c1\",\"doc_id\":\"doc-1\",\"type\":\"sop\","
            + "\"text\":\"步骤一：重启连接池\",\"breadcrumb\":\"止损操作\","
            + "\"metadata\":{\"service\":\"orders\",\"endpoint\":\"/orders\",\"method\":\"GET\","
            + "\"error_codes\":[\"50012_DB_TIMEOUT\"],\"auth_level\":2,\"env\":\"prod\",\"tenant\":\"t1\"}}";

    @TempDir
    Path tmp;

    private ElasticsearchClient es;
    private ElasticsearchIndicesClient indices;
    private QdrantClient qdrant;
    private Collections.CollectionInfo collectionInfo;
    private KvStore store;
    private L2SemanticCacheService l2;
    private IngestionRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        es = mock(ElasticsearchClient.class);
        indices = mock(ElasticsearchIndicesClient.class);
        when(es.indices()).thenReturn(indices);
        // 首次灌库没有旧别名：getAlias 抛异常被 swapEsAlias 的 try/catch 吞掉，走"无旧索引"分支
        when(indices.getAlias(anyGetAliasFn())).thenThrow(new RuntimeException("alias not found"));

        CountResponse count = mock(CountResponse.class);
        when(count.count()).thenReturn(1L);
        when(es.count(anyCountFn())).thenReturn(count);

        qdrant = mock(QdrantClient.class);
        collectionInfo = mock(Collections.CollectionInfo.class);
        when(collectionInfo.getPointsCount()).thenReturn(1L);

        when(qdrant.collectionExistsAsync(CACHE)).thenReturn(Futures.immediateFuture(true));
        when(qdrant.createCollectionAsync(anyString(), any(Collections.VectorParams.class), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(null));
        // 新集合会建 payload 索引；已有集合也会补 created_at 索引（G1 TTL 清理需要）
        when(qdrant.createPayloadIndexAsync(anyString(), anyString(), any(Collections.PayloadSchemaType.class),
                any(), any(), any(), any(Duration.class)))
                .thenReturn(Futures.immediateFuture(null));
        when(qdrant.getCollectionInfoAsync(anyString())).thenAnswer(inv ->
                CACHE.equals(inv.getArgument(0))
                        ? Futures.immediateFuture(null)            // ensureCacheCollection 只看"能取到"
                        : Futures.immediateFuture(collectionInfo)); // verifyCounts 读点数
        when(qdrant.upsertAsync(anyString(), anyList())).thenReturn(Futures.immediateFuture(null));
        when(qdrant.listAliasesAsync()).thenReturn(Futures.immediateFuture(List.of()));
        when(qdrant.updateAliasesAsync(anyList(), any(Duration.class))).thenReturn(Futures.immediateFuture(null));

        EmbeddingClient embedding = mock(EmbeddingClient.class);
        when(embedding.embed(anyList())).thenReturn(List.of(new float[]{1f, 0f}));

        store = mock(KvStore.class);
        l2 = mock(L2SemanticCacheService.class);

        Path chunks = tmp.resolve("chunks.jsonl");
        Files.writeString(chunks, CHUNK_LINE + "\n");
        runner = new IngestionRunner(provider(es), provider(qdrant), store, emptyProvider(),
                embedding, props(), l2, chunks.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    /** live 形态 + 2 维向量：ingest 的全部必需字段都在这里，且不触网。 */
    private static OpsPilotProperties props() {
        return new OpsPilotProperties(
                new OpsPilotProperties.DashScope("https://dashscope.aliyuncs.com", "k", "text-embedding-v3",
                        2, "gte-rerank-v2", "qwen-plus", 60, 3000, 15000, "mock"),
                null,
                new OpsPilotProperties.Es("http://localhost:9200", "opspilot-chunks-read", null, null, 3000, 10000),
                new OpsPilotProperties.Qdrant("localhost", 6334, "opspilot-vectors-live", CACHE, ""),
                null, new OpsPilotProperties.Cache(2, 0.95), null, null, null);
    }

    // ES java client 的方法都有"请求对象"与"builder 函数"两个重载，裸 any() 会歧义；
    // 用具名 matcher 把类型钉死，读起来也比一长串 type witness 清楚。
    private static Function<GetAliasRequest.Builder, ObjectBuilder<GetAliasRequest>> anyGetAliasFn() {
        return any();
    }

    private static Function<CountRequest.Builder, ObjectBuilder<CountRequest>> anyCountFn() {
        return any();
    }

    private static Function<UpdateAliasesRequest.Builder, ObjectBuilder<UpdateAliasesRequest>> anyUpdateAliasesFn() {
        return any();
    }

    @Test
    void successfulReingestFlushesL2AfterAliasSwap() throws Exception {
        // 走 run() 而不是直接 ingest()：es/qdrant 字段由 run() 从 ObjectProvider 解析（live 装配契约），
        // 直接调 ingest 会拿不到客户端——那测的是"另一个入口"，不是真实重灌路径。
        runner.run(new DefaultApplicationArguments("--opspilot.ingest=true"));

        verify(l2).flush();
        // 顺序：新索引生效（两个别名都换完）之后才清缓存——否则清了缓存却还指着旧语料
        InOrder order = inOrder(indices, qdrant, l2);
        order.verify(indices).updateAliases(anyUpdateAliasesFn());
        order.verify(qdrant).updateAliasesAsync(anyList(), any(Duration.class));
        order.verify(l2).flush();
    }

    @Test
    void failedVerificationKeepsOldCacheAndDoesNotSwapAliases() throws Exception {
        // 计数验收失败（staging 点数少于语料条数）：旧别名保持服务，旧缓存也必须原样保留
        when(collectionInfo.getPointsCount()).thenReturn(0L);

        assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments("--opspilot.ingest=true")));

        verify(l2, never()).flush();
        verify(qdrant, never()).updateAliasesAsync(anyList(), any(Duration.class));
        verify(indices, never()).updateAliases(anyUpdateAliasesFn());
    }
}
