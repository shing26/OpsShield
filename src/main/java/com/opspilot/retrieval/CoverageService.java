package com.opspilot.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.IndexState;
import com.opspilot.config.OpsPilotProperties;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 覆盖范围声明（ADR-0023 决策 5）：**提问前可见的知识库边界**。
 *
 * 为什么是服务端现算而不是页面写死一句"支持 51 个错误码"：
 * ① 写死的数字必然漂移，而这个仓库已经为这类漂移建了"每次现算"的门闩；
 * ② **清单本身就是越权面**——给 `sre-acme` 列出 tenant-internal 的服务名，等于把语料边界泄给外部租户。
 * 所以过滤走 {@link EsSearchService#identityFilters}，与检索**同一份**过滤条件，不另立一套。
 *
 * 数据源是 ES 别名（blue/green 切换后查询侧只认别名），所以这里的读数恒等于**当前在服务的那一份语料**，
 * 不是"某次启动时的快照"。`built_at` 取别名所指物理索引的 `settings.index.creation_date`
 * （结构化字段，不是从索引名里抠时间戳），它随重启存活，取不到就**如实给 null**，不编一个时间。
 *
 * 计数**从分面列表长度派生**（单一来源）：`services`/`error_codes` 就是两个列表的长度，
 * 不存在"摘要说 22、列表有 21 条"这种自相矛盾。截断信号用 ES 自己给的 `sum_other_doc_count`
 * ——它表示"有多少文档落在返回的分面之外"，是权威的，不靠"桶数 == size"猜。
 */
@Component
public class CoverageService {

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);

    /**
     * 分面返回上限。取 1000 而不是 ES 默认的 10：本机语料 22 服务 / 51 错误码，
     * 真实部署也可能上百；真超过就靠 `facets_truncated` 显式告知，绝不静默截断。
     */
    static final int FACET_SIZE = 1000;

    private final ObjectProvider<ElasticsearchClient> es;
    private final OpsPilotProperties props;
    private final ObjectProvider<InMemoryIndex> memoryIndex;

    public CoverageService(ObjectProvider<ElasticsearchClient> es, OpsPilotProperties props,
                           ObjectProvider<InMemoryIndex> memoryIndex) {
        this.es = es;
        this.props = props;
        this.memoryIndex = memoryIndex;
    }

    /** 一个分面项：服务名或错误码，以及它下面的语义块数。 */
    public record Facet(String name, long chunks) {}

    /**
     * 覆盖快照。`services` / `errorCodes` 由列表长度派生（见类注释）；
     * `facetsTruncated` 为真时说明还有分面没被列出来——页面必须照实显示，不能假装完整。
     */
    public record Coverage(
            String tenant,
            int authLevel,
            long chunks,
            Long builtAt,
            List<Facet> serviceList,
            List<Facet> errorCodeList,
            boolean facetsTruncated) {

        public long services() { return serviceList.size(); }

        public long errorCodes() { return errorCodeList.size(); }
    }

    public Coverage coverage(String tenant, int authLevel) throws Exception {
        // memory 模式（ADR-0022）：同一份身份过滤、同一套分面派生，数据源换成内存索引。
        // 过滤仍然只走 IdentityFilter（InMemoryIndex.visible 内部调它），不另写一份。
        if (props.storage().inMemory()) return coverageFromMemory(tenant, authLevel);

        ElasticsearchClient client = es.getIfAvailable();
        if (client == null) throw new IllegalStateException("storage.mode=live 但 ES 客户端未装配");
        SearchResponse<Void> resp = client.search(s -> s
                .index(props.es().index())
                .size(0)   // 只要计数与分面，不要命中文档
                .query(q -> q.bool(b -> b.filter(EsSearchService.identityFilters(tenant, authLevel))))
                .aggregations("services", a -> a.terms(t -> t.field("metadata.service").size(FACET_SIZE)))
                .aggregations("error_codes", a -> a.terms(t -> t.field("metadata.error_codes").size(FACET_SIZE))),
                Void.class);

        long chunks = resp.hits().total() == null ? 0L : resp.hits().total().value();
        List<Facet> services = facets(resp, "services");
        List<Facet> codes = facets(resp, "error_codes");
        boolean truncated = otherDocs(resp, "services") > 0 || otherDocs(resp, "error_codes") > 0;
        return new Coverage(tenant, authLevel, chunks, builtAt(client), services, codes, truncated);
    }

    /**
     * memory 模式的分面现算。**与 ES 侧逐条对齐**：每篇文档对每个**不同**服务/错误码各计 1
     * （ES 的 terms 聚合在数组字段上按文档计数，同一文档里的重复值只算一次），
     * 排序与截断规则（块数降序、同数按名升序、超出 FACET_SIZE 置 {@code facetsTruncated}）也一致——
     * 否则"同一份语料换个后端，覆盖范围数字就变了"，而用户无从判断哪个是真的。
     */
    private Coverage coverageFromMemory(String tenant, int authLevel) {
        InMemoryIndex idx = memoryIndex.getIfAvailable();
        if (idx == null) {
            // 装配异常（memory 模式却没有索引 bean）：如实给空集 + 未知时刻，不编数字
            log.warn("覆盖范围：memory 模式但内存索引缺席，返回空覆盖");
            return new Coverage(tenant, authLevel, 0, null, List.of(), List.of(), false);
        }
        Map<String, Long> services = new LinkedHashMap<>();
        Map<String, Long> codes = new LinkedHashMap<>();
        long chunks = 0;
        for (InMemoryIndex.Doc d : idx.visible(tenant, authLevel)) {
            chunks++;
            ScoredChunk c = d.chunk();
            services.merge(c.service(), 1L, Long::sum);
            for (String code : new LinkedHashSet<>(c.errorCodes())) codes.merge(code, 1L, Long::sum);
        }
        List<Facet> svcFacets = toFacets(services);
        List<Facet> codeFacets = toFacets(codes);
        boolean truncated = svcFacets.size() > FACET_SIZE || codeFacets.size() > FACET_SIZE;
        return new Coverage(tenant, authLevel, chunks, idx.builtAtMs() == 0 ? null : idx.builtAtMs(),
                cap(svcFacets), cap(codeFacets), truncated);
    }

    private static List<Facet> toFacets(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .map(e -> new Facet(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingLong(Facet::chunks).reversed().thenComparing(Facet::name))
                .toList();
    }

    private static List<Facet> cap(List<Facet> facets) {
        return facets.size() <= FACET_SIZE ? facets : facets.subList(0, FACET_SIZE);
    }

    /** 分面 → 列表。按块数降序、同数按名字升序：读数稳定，页面不会每次刷新换顺序。 */
    private static List<Facet> facets(SearchResponse<Void> resp, String name) {
        Aggregate agg = aggregate(resp, name);
        if (agg == null || !agg.isSterms()) return List.of();
        return agg.sterms().buckets().array().stream()
                .map(b -> new Facet(b.key().isString() ? b.key().stringValue() : String.valueOf(b.key()._get()),
                        b.docCount()))
                .sorted(Comparator.comparingLong(Facet::chunks).reversed().thenComparing(Facet::name))
                .toList();
    }

    /** ES 自己给的"未进分面的文档数"——截断判据的权威来源。取不到按 0（不误报截断）。 */
    private static long otherDocs(SearchResponse<Void> resp, String name) {
        Aggregate agg = aggregate(resp, name);
        if (agg == null || !agg.isSterms() || agg.sterms().sumOtherDocCount() == null) return 0L;
        return agg.sterms().sumOtherDocCount();
    }

    /**
     * 取分面结果。**必须按后缀匹配**：ES Java 客户端对 search 请求**恒发 `typed_keys=true`**
     * （见生成客户端 `SearchRequest.java` 里的 `params.put("typed_keys","true")`），于是响应里的
     * 聚合键是 `<type>#<name>`（本处即 `sterms#services`）而不是请求时写的 `services`。
     * 只认裸名会让两个分面**恒为空**——而"空清单"看起来和"这个身份确实没有覆盖"一模一样，
     * 是最难发现的那类错。按后缀取同时兼容两种形态，也不写死 `sterms` 前缀
     * （字段映射若从 keyword 变成数值，前缀会变成 `lterms`）。
     */
    private static Aggregate aggregate(SearchResponse<Void> resp, String name) {
        Aggregate direct = resp.aggregations().get(name);
        if (direct != null) return direct;
        return resp.aggregations().entrySet().stream()
                .filter(e -> e.getKey().endsWith("#" + name))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    /** 别名所指物理索引的创建时刻（epoch ms）。任何一步取不到就返回 null——不编造"最近重建时间"。 */
    private Long builtAt(ElasticsearchClient es) {
        try {
            var got = es.indices().get(g -> g.index(props.es().index()));
            return got.result().values().stream()
                    .map(IndexState::settings)
                    .filter(Objects::nonNull)
                    .map(CoverageService::creationDate)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            // 不静默：取不到就如实给 null，但要把原因留在日志里，否则"为什么 built_at 总是空"无从查起
            log.warn("覆盖范围：读取索引创建时刻失败，built_at 置空: {}", e.toString());
            return null;
        }
    }

    /**
     * 创建时刻在**内层**。ES 的 `GET /&lt;index&gt;` 形状是 `settings.index.creation_date`，而生成客户端里的
     * `IndexSettings` 是自引用的——`IndexSettings.setupIndexSettingsDeserializer` 里
     * `op.add(Builder::index, IndexSettings._DESERIALIZER, "index")`，也就是 `settings.index` 又被解成一个
     * `IndexSettings`。所以 `IndexState.settings().creationDate()` 恒为 null，真值在 `.settings().index()` 上。
     * （2026-10-19 实测咬到：只读外层时 `built_at` 恒空，而"空"看起来与"ES 没给这个字段"一样。）
     * 两层都探一次，因为 `include_defaults` 等请求参数会改变客户端把值放在哪一层。
     */
    private static Long creationDate(IndexSettings settings) {
        if (settings.creationDate() != null) return settings.creationDate();
        IndexSettings inner = settings.index();
        return inner == null ? null : inner.creationDate();
    }
}
