package com.opspilot.cache;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.retrieval.QdrantSearchService;
import com.opspilot.retrieval.Vectors;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * L2 语义缓存：复用检索时已算好的 Query 向量查专属 Collection，
 * 余弦 > 0.95 判定命中直接回放答案。payload 携带 tenant + max_auth_level，
 * 检索时双硬过滤——语义缓存本身不能成为权限或租户的泄漏通道（P1：缓存串租户
 * 与权限泄漏同级，回放前必须等值命中请求方 tenant）。
 *
 * <p><b>G1-W1.3（2026-10-22 运维审查）补"缓存自身也会变旧"的三件事</b>：
 * <ol>
 *   <li><b>TTL</b>：lookup 只认 {@code created_at >= now - l2-ttl-hours} 的点，并每小时
 *       {@link #evictExpired()} 删一次过期点。此前缓存**永不过期**：答案里的 refs 指向的 chunk
 *       在 reingest 后已不存在，回放出来的溯源是死链；语料变更后旧答案还会以"高相似命中"继续
 *       服务，而它可能已经是错的。TTL 让"缓存"重新变回"短时加速"而不是第二份事实源。</li>
 *   <li><b>memory 后端有界 LRU</b>：此前是 {@code CopyOnWriteArrayList} + 全表线性扫描，
 *       无上限、每次写入复制整表；演示跑久了内存只涨不降，且 O(n) 扫描随条目线性变慢。
 *       现在默认 5000 条、access-order {@link LinkedHashMap}，超限淘汰最久未用。</li>
 *   <li><b>reingest 后清空</b>（{@code IngestionRunner} 调用 {@link #flush()}）：语料蓝绿切换成功后
 *       旧缓存里的 refs 一定失效，不清空就是"新语料 + 旧答案"的混合态——比纯缓存未命中更危险，
 *       因为它看起来是命中。</li>
 * </ol>
 *
 * <p><b>配置</b>（不写进 application.yml，走 @Value 默认值；改配置无需动代码）：
 * {@code opspilot.cache.l2-ttl-hours}（默认 72）、{@code opspilot.cache.l2-max-entries}（默认 5000）、
 * {@code opspilot.cache.l2-cleanup-interval-ms}（默认 3600000 = 每小时）。
 */
@Service
public class L2SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(L2SemanticCacheService.class);

    /** 默认 TTL（小时）= 72：覆盖一个周末的演示空窗，又不至于让一个月前的答案继续回放。 */
    public static final long DEFAULT_TTL_HOURS = 72;

    /** 默认内存条目上限：5000 × (1024 维向量 + payload) ≈ 约 30MB，演示机器可接受。 */
    public static final int DEFAULT_MAX_ENTRIES = 5000;

    public record CacheHit(String payloadJson, double score) {}

    private final ObjectProvider<QdrantClient> qdrant;
    private final EmbeddingClient embedding;
    private final OpsPilotProperties props;
    private final java.util.concurrent.ExecutorService vt;
    private final Duration ttl;
    private final int maxEntries;

    /**
     * memory 后端（ADR-0022 决策 3）：进程内条目表，替代 Qdrant 的 cache collection。
     * 过滤条件与 live 侧**逐条对应**（tenant 等值 + max_auth_level lte + created_at 未过期），
     * 只是判据写在 Java 里——语义缓存串租户/串权限与回放未命中同级严重（P1），
     * 两个后端不许有第二套口径。
     *
     * <p><b>access-order 的 LinkedHashMap + 全表加锁</b>：条目上限只有几千，扫描一次是毫秒级；
     * 换成"分片/无锁"只会让淘汰顺序与一致性更难解释。所有读写都在同一把锁内完成，
     * 保证"淘汰最久未用"这件事在并发下仍然成立（写入线程与查询线程不会各删一半）。
     */
    private final LinkedHashMap<String, MemEntry> memCache = new LinkedHashMap<>(16, 0.75f, true);

    private final AtomicLong memSeq = new AtomicLong();

    private record MemEntry(float[] vector, String payloadJson, int maxAuthLevel, String tenant,
                            long createdAtMs) {}

    /**
     * Spring 注入用构造：TTL/上限走 {@code @Value} 默认值。
     *
     * <p>显式 {@code @Autowired}：本类另有给单测的便捷构造，多构造器时 Spring 无法自行判定。
     */
    @Autowired
    public L2SemanticCacheService(ObjectProvider<QdrantClient> qdrant, EmbeddingClient embedding,
                                  OpsPilotProperties props,
                                  java.util.concurrent.ExecutorService virtualThreadExecutor,
                                  @Value("${opspilot.cache.l2-ttl-hours:72}") long ttlHours,
                                  @Value("${opspilot.cache.l2-max-entries:5000}") int maxEntries) {
        this(qdrant, embedding, props, virtualThreadExecutor, Duration.ofHours(ttlHours), maxEntries);
    }

    /** 单测/旧调用便捷构造：使用与 @Value 默认值一致的 TTL 与上限。 */
    public L2SemanticCacheService(ObjectProvider<QdrantClient> qdrant, EmbeddingClient embedding,
                                  OpsPilotProperties props,
                                  java.util.concurrent.ExecutorService virtualThreadExecutor) {
        this(qdrant, embedding, props, virtualThreadExecutor,
                Duration.ofHours(DEFAULT_TTL_HOURS), DEFAULT_MAX_ENTRIES);
    }

    /** 测试可注入的精确 TTL/上限（包内可见：故意的，不为测试改生产默认值）。 */
    L2SemanticCacheService(ObjectProvider<QdrantClient> qdrant, EmbeddingClient embedding,
                           OpsPilotProperties props,
                           java.util.concurrent.ExecutorService virtualThreadExecutor,
                           Duration ttl, int maxEntries) {
        this.qdrant = qdrant;
        this.embedding = embedding;
        this.props = props;
        this.vt = virtualThreadExecutor;
        this.ttl = ttl;
        this.maxEntries = Math.max(1, maxEntries);
    }

    public CacheHit lookup(String query, String tenant, int authLevel) {
        if (props.storage().inMemory()) return lookupMemory(query, tenant, authLevel);
        try {
            QdrantClient qdrant = this.qdrant.getIfAvailable();
            if (qdrant == null) return null;   // live 却无客户端=装配被改坏；缓存故障本就不致命
            float[] vector = embedding.embedOne(query);
            Points.SearchPoints req = Points.SearchPoints.newBuilder()
                    .setCollectionName(props.qdrant().cacheCollection())
                    .addAllVector(QdrantSearchService.toFloatList(vector))
                    .setLimit(1)
                    .setScoreThreshold((float) props.cache().l2Threshold())
                    .setFilter(Points.Filter.newBuilder()
                            .addMust(Points.Condition.newBuilder()
                                    .setField(Points.FieldCondition.newBuilder()
                                            .setKey("tenant")
                                            .setMatch(Points.Match.newBuilder()
                                                    .setKeyword(tenant == null ? "" : tenant).build())
                                            .build())
                                    .build())
                            .addMust(Points.Condition.newBuilder()
                                    .setField(Points.FieldCondition.newBuilder()
                                            .setKey("max_auth_level")
                                            .setRange(Points.Range.newBuilder().setLte(authLevel).build())
                                            .build())
                                    .build())
                            // G1：只认未过期的点。没有这一条，缓存就是"永不过期的第二份事实源"——
                            // reingest 后 refs 已死，旧答案仍会以高相似命中回放。
                            .addMust(rangeGte("created_at", System.currentTimeMillis() - ttl.toMillis()))
                            .build())
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                    .build();
            List<Points.ScoredPoint> hits = qdrant.searchAsync(req).get(3, TimeUnit.SECONDS);
            if (hits.isEmpty()) return null;
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> pl = hits.get(0).getPayloadMap();
            return new CacheHit(pl.get("payload_json").getStringValue(), hits.get(0).getScore());
        } catch (Exception e) {
            return null; // 缓存故障不致命
        }
    }

    /** 内存版 lookup：LRU 扫描 + 同一套阈值、身份与 TTL 过滤；取分最高的一条并刷新其 LRU 位置。 */
    private CacheHit lookupMemory(String query, String tenant, int authLevel) {
        try {
            // embedding 在锁外做：它可能走网络（memory 版也可能配了真 key），不能占着缓存锁等网络
            float[] qv = embedding.embedOne(query);
            String want = tenant == null ? "" : tenant;
            long cutoff = System.currentTimeMillis() - ttl.toMillis();
            synchronized (memCache) {
                String bestKey = null;
                MemEntry best = null;
                double bestScore = props.cache().l2Threshold();
                Iterator<Map.Entry<String, MemEntry>> it = memCache.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, MemEntry> e = it.next();
                    MemEntry m = e.getValue();
                    if (m.createdAtMs() < cutoff) { it.remove(); continue; }   // 惰性淘汰过期条目
                    if (!m.tenant().equals(want)) continue;         // 租户等值（跨租户零命中）
                    if (m.maxAuthLevel() > authLevel) continue;     // 缓存条目的密级上限不得高于请求方
                    double s = Vectors.cosine(qv, m.vector());
                    if (s >= bestScore) { bestScore = s; best = m; bestKey = e.getKey(); }
                }
                if (best == null) return null;
                memCache.get(bestKey);   // access-order：命中一次即刷新为最近使用
                return new CacheHit(best.payloadJson(), bestScore);
            }
        } catch (Exception e) {
            return null; // 缓存故障不致命（与 live 侧同语义）
        }
    }

    /**
     * 清空语义缓存（reingest 成功后由 {@code IngestionRunner} 调用；验收隔离/演示重置也用它）。
     */
    public void flush() {
        if (props.storage().inMemory()) {
            synchronized (memCache) {
                memCache.clear();
            }
            return;
        }
        try {
            QdrantClient qdrant = this.qdrant.getIfAvailable();
            if (qdrant == null) return;
            qdrant.deleteAsync(props.qdrant().cacheCollection(),
                    Points.Filter.getDefaultInstance()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 清理失败不致命
        }
    }

    /** 当前条目数（memory：进程内条目；live：缓存集合点数；失败返回 -1）。供健康面/测试使用。 */
    public int size() {
        if (props.storage().inMemory()) {
            synchronized (memCache) {
                return memCache.size();
            }
        }
        try {
            QdrantClient qdrant = this.qdrant.getIfAvailable();
            if (qdrant == null) return -1;
            Long n = qdrant.countAsync(props.qdrant().cacheCollection()).get(3, TimeUnit.SECONDS);
            return n == null ? -1 : n.intValue();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 每小时清一次过期点（Lead 已在 OpsPilotApplication 打开 {@code @EnableScheduling}）。
     * 只按 created_at 删本集合内的点，不碰语料集合；失败只记一条 warn，不升级为故障
     * （缓存清理失败最坏是过期点多留一会儿，读侧 filter 仍会挡住它们）。
     */
    @Scheduled(fixedDelayString = "${opspilot.cache.l2-cleanup-interval-ms:3600000}",
            initialDelayString = "${opspilot.cache.l2-cleanup-initial-delay-ms:60000}")
    public void evictExpired() {
        long cutoff = System.currentTimeMillis() - ttl.toMillis();
        if (props.storage().inMemory()) {
            int before;
            int after;
            synchronized (memCache) {
                before = memCache.size();
                memCache.values().removeIf(e -> e.createdAtMs() < cutoff);
                after = memCache.size();
            }
            if (before != after) log.info("L2 memory 缓存清理过期条目: {} -> {}", before, after);
            return;
        }
        try {
            QdrantClient qdrant = this.qdrant.getIfAvailable();
            if (qdrant == null) return;
            qdrant.deleteAsync(props.qdrant().cacheCollection(), Points.Filter.newBuilder()
                    .addMust(rangeLt("created_at", cutoff))
                    .build()).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("L2 缓存过期清理失败（读侧 filter 仍会挡住过期点，不影响正确性）: {}", e.toString());
        }
    }

    /**
     * LLM 完整回答结束后才写入（避免半成品入缓存）。payloadJson 含 refs，回放时溯源完整。
     *
     * <p><b>2026-09-28（OP-R3）：upsert 改为投递即返回</b>，不再同步等 {@code .get(5, SECONDS)}。
     * 那个等待发生在**请求尾段**（LLM 已答完、答案已下发），纯属缓存写入的延迟，却直接计入用户可见的
     * 端到端时延；而写缓存失败/慢本就不该进主链路 SLO（异常原本就被吞）。语义变化只有一条：
     * 写入变**最终一致**——同一 query 紧接着重放时可能仍未命中 L2（L1 已在编排里同步写好，不受影响）。
     * 失败语义不变：任务内吞异常，不阻断也不重试。
     *
     * <p>注意本方法**不负责**去掉"同一请求 embed 两次"的成本：向量仍由调用方传入，检索腿算出的那次
     * 没有被回传复用——那是 {@code OPS.md} §5 单列的另立触发线（{@code stage_ms.l2_store} 占比 >10%
     * 或上游调用量成为瓶颈才立项），本项只去掉等待，不碰调用次数。
     */
    public void store(String query, float[] queryVector, String payloadJson, int maxAuthLevel, String tenant) {
        long createdAt = System.currentTimeMillis();
        if (props.storage().inMemory()) {
            // 与 live 侧同语义：投递即返回、失败吞掉。内存版没有可失败的写入路径，
            // 但仍走 vt 执行，保证"写缓存在请求尾段不占主链路"这一条两边一致。
            MemEntry entry = new MemEntry(queryVector, payloadJson, maxAuthLevel,
                    tenant == null ? "" : tenant, createdAt);
            String key = "l2-" + memSeq.incrementAndGet();
            vt.execute(() -> putMemory(key, entry));
            return;
        }
        try {
            QdrantClient qdrant = this.qdrant.getIfAvailable();
            if (qdrant == null) return;
            Points.PointStruct point = Points.PointStruct.newBuilder()
                    .setId(id(UUID.randomUUID()))
                    .setVectors(vectors(queryVector))
                    .putPayload("query_text", value(query))
                    .putPayload("payload_json", value(payloadJson))
                    .putPayload("max_auth_level", value(maxAuthLevel))
                    .putPayload("tenant", value(tenant == null ? "" : tenant))
                    .putPayload("created_at", value(createdAt))
                    .build();
            vt.execute(() -> {
                try {
                    qdrant.upsertAsync(props.qdrant().cacheCollection(), List.of(point)).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // 写缓存失败不影响主链路（与同步版同语义）
                }
            });
        } catch (Exception e) {
            // 构造 point 失败（入参异常）同样不致命
        }
    }

    /** 写入内存缓存：先清过期，再按 LRU 淘汰到上限以内。 */
    private void putMemory(String key, MemEntry entry) {
        synchronized (memCache) {
            long cutoff = System.currentTimeMillis() - ttl.toMillis();
            memCache.values().removeIf(e -> e.createdAtMs() < cutoff);
            memCache.put(key, entry);
            Iterator<String> eldest = memCache.keySet().iterator();
            while (memCache.size() > maxEntries && eldest.hasNext()) {
                eldest.next();
                eldest.remove();
            }
        }
    }

    /** Qdrant range 条件：created_at >= value（毫秒时间戳在 double 里可精确表示）。 */
    private static Points.Condition rangeGte(String field, double value) {
        return Points.Condition.newBuilder()
                .setField(Points.FieldCondition.newBuilder()
                        .setKey(field)
                        .setRange(Points.Range.newBuilder().setGte(value).build())
                        .build())
                .build();
    }

    /** Qdrant range 条件：created_at < value（清理用，左开避免边界点被新写入后仍被误删）。 */
    private static Points.Condition rangeLt(String field, double value) {
        return Points.Condition.newBuilder()
                .setField(Points.FieldCondition.newBuilder()
                        .setKey(field)
                        .setRange(Points.Range.newBuilder().setLt(value).build())
                        .build())
                .build();
    }
}
