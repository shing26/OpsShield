package com.opspilot.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.IndexState;
import co.elastic.clients.json.JsonpDeserializer;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.util.ObjectBuilder;
import com.opspilot.config.OpsPilotProperties;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 覆盖范围声明的两条不变式（ADR-0023 决策 5）：
 *
 * ① **过滤与检索同源、两个维度都在**——清单本身是越权面。给外部租户列出内部租户的服务名
 *    等于把语料边界泄出去，所以"tenant 等值 term + auth_level range lte 都进了 filter"
 *    是这条端点的安全性质，不是实现细节。
 * ② **摘要与列表同源、截断显式**——"摘要说 22 个服务、列表只有 21 条"这种自相矛盾，
 *    和"分面被截断了却不告诉你"是同一类谎。计数从列表长度派生，截断用 ES 自己给的
 *    `sum_other_doc_count`，不靠"桶数 == size"猜。
 *
 * 变异验证（改坏必红）：把 `identityFilters(...)` 换成 `List.of()` → 用例 1/2 必红；
 * 把 `services()` 改成返回常量 → 用例 3 必红；把 `otherDocs(...) > 0` 改成 `false` → 用例 4 必红。
 */
class CoverageServiceTest {

    private static final JsonpMapper MAPPER = new JacksonJsonpMapper();
    private static final long BUILT_AT = 1761000000000L;

    private ElasticsearchClient es;
    private ElasticsearchIndicesClient indices;
    private final AtomicReference<SearchRequest> captured = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        es = mock(ElasticsearchClient.class);
        indices = mock(ElasticsearchIndicesClient.class);
        when(es.indices()).thenReturn(indices);
        captured.set(null);
    }

    private CoverageService service() {
        return new CoverageService(provider(es), props(), absentMemoryIndex());
    }

    /** live 模式用例：内存索引 bean 缺席（{@code getIfAvailable()} 给 null），走 ES 那条路。 */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<InMemoryIndex> absentMemoryIndex() {
        return mock(ObjectProvider.class);
    }

    /** live 模式用例：ES 客户端 bean 在场。 */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private static OpsPilotProperties props() {
        return new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://mock", "", "m", 1024, "m", "m", 30, 3000, 15000, "mock"),
                new OpsPilotProperties.Es("http://localhost:9200", "opspilot-chunks-read", "elastic", "", 3000, 10000),
                new OpsPilotProperties.Qdrant("localhost", 6334, "c", "cache", ""),
                new OpsPilotProperties.Jwt("secret", 3600),
                new OpsPilotProperties.Cache(2, 0.95),
                new OpsPilotProperties.Storm(30, 60),
                new OpsPilotProperties.Retrieval(10, 10, 60, 20, 3, 800, 0.05),
                new OpsPilotProperties.Degrade(5, 3, 30));
    }

    /** 让 `search(...)` 的 lambda 真的跑一遍，好把构造出来的 SearchRequest 留下来。 */
    @SuppressWarnings("unchecked")
    private void stubSearch(String responseJson) throws Exception {
        SearchResponse<Void> resp = parse(responseJson);
        when(es.search(any(Function.class), eq(Void.class))).thenAnswer(inv -> {
            Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> fn = inv.getArgument(0);
            captured.set(fn.apply(new SearchRequest.Builder()).build());
            return resp;
        });
    }

    /** 用真实反序列化器吃一段 ES 响应 JSON——比手搭 SearchResponse 树更能证明"读得懂线上形状"。 */
    private static SearchResponse<Void> parse(String json) {
        return SearchResponse.createSearchResponseDeserializer(JsonpDeserializer.voidDeserializer())
                .deserialize(MAPPER.jsonProvider().createParser(new StringReader(json)), MAPPER);
    }

    /**
     * 响应 JSON 里的聚合键**必须带类型前缀**：ES Java 客户端对 search 恒发 `typed_keys=true`，
     * 真实响应的键就是 `sterms#services` 这种形状。这条不是"测试写起来方便"，
     * 而是被实测咬过一次——只认裸名时两个分面恒为空，而"空清单"与"这个身份确实没有覆盖"
     * 长得一模一样（2026-10-19，本测试首跑即红）。
     */
    private static String response(String hits, String services, String codes) {
        return """
                {"took":1,"timed_out":false,
                 "_shards":{"total":1,"successful":1,"skipped":0,"failed":0},
                 "hits":{"total":{"value":%s,"relation":"eq"},"max_score":null,"hits":[]},
                 "aggregations":{"sterms#services":%s,"sterms#error_codes":%s}}
                """.formatted(hits, services, codes);
    }

    private static String terms(String buckets, long other) {
        return """
                {"doc_count_error_upper_bound":0,"sum_other_doc_count":%d,"buckets":[%s]}
                """.formatted(other, buckets);
    }

    private static final String OK_BODY = response("12",
            terms("""
                    {"key":"pay-service","doc_count":5},{"key":"order-service","doc_count":7}
                    """, 0),
            terms("""
                    {"key":"50012_DB_TIMEOUT","doc_count":4}
                    """, 0));

    @Test
    void bothIdentityDimensionsAreInjectedIntoTheFilter() throws Exception {
        stubSearch(OK_BODY);
        service().coverage("tenant-acme", 3);

        List<Query> filters = captured.get().query().bool().filter();
        assertEquals(2, filters.size(), "tenant 与 auth_level 必须各是一条 filter");

        assertEquals("metadata.tenant", filters.get(0).term().field());
        assertEquals("tenant-acme", filters.get(0).term().value().stringValue());

        assertEquals("metadata.auth_level", filters.get(1).range().field());
        assertEquals(3, filters.get(1).range().lte().to(Integer.class));
    }

    /**
     * fail-closed：tenant 为 null/空时 term 打到空串——ES 里没有任何文档的 `metadata.tenant` 是空串，
     * 于是**空集**而不是"全部文档"。这是"缺 claim 就当没权限"的落地形态（与 EsSearchService 同源）。
     */
    @Test
    void blankTenantFailsClosedToEmptyString() throws Exception {
        stubSearch(OK_BODY);
        service().coverage(null, 1);

        List<Query> filters = captured.get().query().bool().filter();
        assertEquals("", filters.get(0).term().value().stringValue(),
                "tenant 缺失必须落到空串（匹配不到任何文档），不得退化成不过滤");
    }

    /** 计数与列表同源；列表按块数降序（读数稳定，刷新不换序）。 */
    @Test
    void countsComeFromFacetListsAndAreSortedByChunks() throws Exception {
        stubSearch(OK_BODY);
        CoverageService.Coverage c = service().coverage("tenant-internal", 2);

        assertEquals(12, c.chunks());
        assertEquals(2, c.services());
        assertEquals(1, c.errorCodes());
        assertEquals(List.of("order-service", "pay-service"),
                c.serviceList().stream().map(CoverageService.Facet::name).toList());
        assertEquals(7, c.serviceList().get(0).chunks());
        assertEquals("50012_DB_TIMEOUT", c.errorCodeList().get(0).name());
        assertFalse(c.facetsTruncated());
    }

    /** 截断判据来自 ES 的 sum_other_doc_count（权威），不是"桶数 == size"（猜测）。 */
    @Test
    void truncationFollowsSumOtherDocCount() throws Exception {
        stubSearch(response("12",
                terms("""
                        {"key":"order-service","doc_count":7}
                        """, 31),
                terms("""
                        {"key":"50012_DB_TIMEOUT","doc_count":4}
                        """, 0)));
        assertTrue(service().coverage("tenant-internal", 2).facetsTruncated(),
                "有 31 个文档落在分面之外时必须显式标截断");
    }

    /** 只要计数与分面：size=0（不拉文档），两个分面字段名固定。 */
    @Test
    void requestAsksForCountsAndFacetsOnly() throws Exception {
        stubSearch(OK_BODY);
        service().coverage("tenant-internal", 2);

        SearchRequest req = captured.get();
        assertEquals(0, req.size(), "覆盖快照不该拉回文档正文");
        assertEquals("opspilot-chunks-read", req.index().get(0), "查询侧只认别名（blue/green）");
        assertEquals("metadata.service", req.aggregations().get("services").terms().field());
        assertEquals("metadata.error_codes", req.aggregations().get("error_codes").terms().field());
    }

    /**
     * built_at 取别名所指物理索引的 creation_date。
     *
     * **形状必须按线上真实的样子钉**：`GET /&lt;index&gt;` 返回的是 `settings.index.creation_date`，
     * 而客户端里的 `IndexSettings` 自引用，真值在**内层** `settings().index()` 上。
     * 只读外层时 `built_at` 恒为 null——而"恒空"与"ES 没给这个字段"长得一样，是最难发现的那类错
     * （2026-10-19 实测咬到）。外层形态也一并锁住，因为 `include_defaults` 等参数会改变落在哪一层。
     */
    @Test
    void builtAtReadsNestedCreationDateAndFallsBackToNull() throws Exception {
        stubSearch(OK_BODY);

        // 线上真实形状：settings.index.creation_date
        when(indices.get(any(Function.class))).thenReturn(GetIndexResponse.of(b -> b
                .result("opspilot-chunks-20261019052630",
                        IndexState.of(s -> s.settings(IndexSettings.of(
                                st -> st.index(i -> i.creationDate(BUILT_AT))))))));
        assertEquals(BUILT_AT, service().coverage("tenant-internal", 2).builtAt());

        // 外层形态（客户端把值摊平到 settings 上时）
        when(indices.get(any(Function.class))).thenReturn(GetIndexResponse.of(b -> b
                .result("opspilot-chunks-20261019052630",
                        IndexState.of(s -> s.settings(IndexSettings.of(st -> st.creationDate(BUILT_AT)))))));
        assertEquals(BUILT_AT, service().coverage("tenant-internal", 2).builtAt());

        when(indices.get(any(Function.class))).thenThrow(new RuntimeException("es down"));
        assertNull(service().coverage("tenant-internal", 2).builtAt(),
                "取不到创建时刻必须给 null，不能拿 System.currentTimeMillis() 冒充");
    }
}
