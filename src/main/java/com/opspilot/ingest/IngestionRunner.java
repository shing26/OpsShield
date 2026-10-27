package com.opspilot.ingest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import tools.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.chunk.Chunk;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.retrieval.InMemoryIndex;
import com.opspilot.store.KvStore;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * 离线 JSONL → 在线双写（ADR-0001 接缝），P4 起为 **blue/green 原子切流**（ADR-0006）：
 * 写 staging 物理库（&lt;base&gt;-&lt;ts&gt;）→ 计数验收 → 单请求原子换 ES alias / Qdrant alias → 删旧物理库。
 * 查询侧只认别名（es.index / qdrant.collection 即别名），重建全程零空窗——
 * 旧「先删后建」在 40s 窗口内 ES+Qdrant 双双为空，降级路也会空态拒答，已被评审否决。
 * 触发：启动 --opspilot.ingest=true 或 POST /api/v1/admin/reingest（level>=3，单飞互斥）。
 * 任何验收不通过都保留旧别名指向，失败只影响 staging。
 */
@Component
// 排序（G1-W1.3）：比默认的 LOWEST_PRECEDENCE 略早，保证 SopWarmupRunner 的"缺失才补灌"检查
// 看到的是本次启动装载/首灌完成后的 SOP 状态，而不是上一轮遗留状态。
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class IngestionRunner implements ApplicationRunner, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(IngestionRunner.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /*
     * live 路径的两个客户端在 run() 里一次性解析并存入字段。**非 final 是刻意的**：
     * memory 模式下这两个 bean 根本不存在（EsConfig/QdrantConfig 按 storage.mode 装配），
     * 而 live 路径的十几个调用点散在 8 个方法里——把它们全改成 `es().xxx()` 只会制造噪音，
     * 换不来任何安全性：字段的生命周期与 bean 一致，且只在 live 分支被触碰。
     * 解析失败（live 却没有 bean = 装配被改坏）在 run() 里当场抛，不留到第一次重灌才炸。
     */
    private ElasticsearchClient es;
    private QdrantClient qdrant;
    private final ObjectProvider<ElasticsearchClient> esProvider;
    private final ObjectProvider<QdrantClient> qdrantProvider;
    private final KvStore store;
    private final ObjectProvider<InMemoryIndex> memoryIndex;
    private final EmbeddingClient embedding;
    private final OpsPilotProperties props;
    private final L2SemanticCacheService l2;
    // Jackson 3（Boot 4，#8）：ObjectMapper.setPropertyNamingStrategy 已移除，改名策略走 builder。
    private final ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .build();
    private final String chunksPath;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final AtomicReference<String> lastResult = new AtomicReference<>("never");

    public IngestionRunner(ObjectProvider<ElasticsearchClient> esProvider,
                           ObjectProvider<QdrantClient> qdrantProvider, KvStore store,
                           ObjectProvider<InMemoryIndex> memoryIndex,
                           EmbeddingClient embedding, OpsPilotProperties props,
                           L2SemanticCacheService l2,
                           @Value("${opspilot.chunks-path:offline/corpus/chunks.jsonl}") String chunksPath) {
        this.esProvider = esProvider;
        this.qdrantProvider = qdrantProvider;
        this.store = store;
        this.memoryIndex = memoryIndex;
        this.embedding = embedding;
        this.props = props;
        this.l2 = l2;
        this.chunksPath = chunksPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // memory 模式的装载在 afterPropertiesSet 里就做完了（见那里的理由），这里不再重复
        if (props.storage().inMemory()) return;
        this.es = esProvider.getIfAvailable();
        this.qdrant = qdrantProvider.getIfAvailable();
        if (es == null || qdrant == null) {
            throw new IllegalStateException(
                    "storage.mode=live 但 ES/Qdrant 客户端未装配（装配被改坏？）");
        }
        // flag 显式触发；或首启自愈（ES 读别名不存在=从未灌过）——容器化场景重启不再
        // 需要人工带 flag，也避免每次重启白烧一遍 embedding（live 下是真金白银）。
        if (args.containsOption("opspilot.ingest") || firstBootMissingAlias()) ingest("startup");
    }

    /**
     * memory 模式的装载时机（ADR-0022 落地实测）：必须在**端口对外之前**完成。
     *
     * <p>原先放在 {@code ApplicationRunner}（与 live 同一条路）时实测咬到：Spring 的启动顺序是
     * "先起 Tomcat、再跑 ApplicationRunner"，于是内存索引装载期间（本机实测 20s+，因为
     * {@code dashscope.mode=live} 时 embedding 要走网络）网关已经对外服务——那段时间里
     * 检索恒空、排障台对任何问题都答"知识库无足够参考"。**它看起来像拒答，实际是没装完**，
     * 而拒答恰恰是最贵的诚实性资产（ADR-0023 理由④），不能被这个竞态污染。
     *
     * <p>live 模式没有这个问题：语料在 ES/Qdrant 里，重启期间旧别名照常服务，装载发生在
     * 首次灌库时才需要，且那时确实还没有可服务的语料（首启语义）。
     *
     * <p>代价如实登记：memory 模式启动耗时 = 装载耗时（零依赖演示约 1–3s；带真 key 走网络时
     * 实测 20s+）。这是"启动慢"换"起来就能用"，比"起来了但答不了"正确。
     */
    @Override
    public void afterPropertiesSet() throws Exception {
        if (props.storage().inMemory()) ingest("startup-memory");
    }

    private boolean firstBootMissingAlias() {
        // memory 模式（ADR-0022）：内存索引每次启动都是空的，**必然要装**——否则
        // "敲一行命令就能用"会退化成"敲一行命令 + 记得带 --opspilot.ingest=true"。
        if (props.storage().inMemory()) return true;
        try {
            return !Boolean.TRUE.equals(es.indices().exists(e -> e.index(props.es().index())).value());
        } catch (Exception e) {
            return false; // ES 不可达时不擅自重灌，交给健康检查/人工处置
        }
    }

    /** 异步重灌（admin 端点）：单飞互斥，busy 时拒绝。返回 false = 已有任务在跑。 */
    public boolean reingestAsync() {
        if (!busy.compareAndSet(false, true)) return false;
        Thread.ofVirtual().name("reingest").start(() -> {
            try {
                ingest("admin-reingest");
            } catch (Exception e) {
                lastResult.set("failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                log.error("reingest 失败（旧别名保持服务，不影响线上检索）", e);
            } finally {
                busy.set(false);
            }
        });
        return true;
    }

    public boolean busy() { return busy.get(); }

    public String lastResult() { return lastResult.get(); }

    synchronized void ingest(String trigger) throws Exception {
        long t0 = System.currentTimeMillis();
        String ts = LocalDateTime.now().format(TS);
        String esAlias = props.es().index();                 // 查询侧：别名（如 opspilot-chunks-read）
        String qdrAlias = props.qdrant().collection();       // 如 opspilot-vectors-live
        String esPhys = stripSuffix(esAlias) + "-" + ts;
        String qdrPhys = stripSuffix(qdrAlias) + "-" + ts;

        List<Chunk> chunks = readChunks();
        // memory 模式（ADR-0022）走另一条装载路径，直接返回——staging/别名/计数验收那一套
        // 是为"换语料时不许有空窗"建的，内存索引换的是引用，本来就没有空窗（见 ingestMemory）。
        if (props.storage().inMemory()) {
            ingestMemory(chunks, trigger, t0);
            return;
        }
        log.info("[{}] 读取 chunks.jsonl: {} 条, staging es={} qdrant={}", trigger, chunks.size(), esPhys, qdrPhys);

        createEsIndex(esPhys);
        createQdrantCollection(qdrPhys);
        ensureCacheCollection();
        dualWrite(chunks, esPhys, qdrPhys);
        verifyCounts(chunks.size(), esPhys, qdrPhys);

        swapEsAlias(esAlias, esPhys);
        swapQdrantAlias(qdrAlias, qdrPhys);

        // G1-W1.3：语料蓝绿切换成功 ⇒ 旧 L2 条目里的 refs 指向已删除的 chunk，必须清空。
        // 位置刻意在 warmSop 之前：别名已换、语料已是新的，即使后面 SOP 预热失败，也不该让
        // 指向旧 chunk 的缓存答案继续被回放（"新语料 + 旧答案"比缓存未命中危险得多）。
        // 失败路径（前面任一验收/切换抛异常）走不到这里，旧语料与旧缓存保持配套。
        l2.flush();

        warmSop(chunks);
        lastResult.set("ok @ " + LocalDateTime.now() + " (trigger=" + trigger + ")");
        log.info("[{}] 入库完成: {} chunks, 别名已原子切换, 耗时 {}ms", trigger, chunks.size(),
                System.currentTimeMillis() - t0);
    }

    /**
     * memory 模式的装载（ADR-0022）：语料读进内存索引 + 预热 SOP，**没有** staging / 别名 /
     * 计数验收。理由：那一整套解决的是"换一份语料时检索侧不许有空窗"，而内存索引的替换
     * 是一次 volatile 引用写（{@code InMemoryIndex.load} 先建好整份新列表再一次性换上），
     * 旧索引在新索引就绪前一直服务——原子性是这个数据结构自带的，照搬 staging 只会造出
     * 一堆没有对应风险的机制。embedding 仍走同一个 {@link EmbeddingClient}（mock 模式下是
     * 本地词法哈希，零出站），并保留 {@link EmbeddingSanity} 校验：维度错了必须在装载期响，
     * 不能等到检索时给出"看起来正常但毫无意义"的排序。
     */
    private void ingestMemory(List<Chunk> chunks, String trigger, long t0) throws Exception {
        InMemoryIndex idx = memoryIndex.getIfAvailable();
        if (idx == null) {
            throw new IllegalStateException("storage.mode=memory 但内存索引 bean 缺席（装配异常）");
        }
        List<float[]> vectors = embedding.embed(chunks.stream().map(Chunk::text).toList());
        EmbeddingSanity.check(vectors, chunks.size(), props.dashscope().embeddingDim());
        idx.load(chunks, vectors);
        // memory 模式同样换了一整份语料：旧 L2 条目的 refs 同样失效。放在 load 之后，
        // 保证装载失败时缓存不动（失败路径不清）。
        l2.flush();
        warmSop(chunks);
        lastResult.set("ok @ " + LocalDateTime.now() + " (trigger=" + trigger + ", backend=memory)");
        log.info("[{}] 内存索引装载完成: {} chunks, 耗时 {}ms", trigger, chunks.size(),
                System.currentTimeMillis() - t0);
    }

    /** 别名 = <base>-read / <base>-live；物理库 = <base>-<ts>。 */
    private static String stripSuffix(String alias) {
        return alias.replaceFirst("-(read|live)$", "");
    }

    private List<Chunk> readChunks() throws Exception {
        List<Chunk> out = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(Path.of(chunksPath))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                Chunk c = mapper.readValue(line, Chunk.class);
                // P1 租户显式化 fail-closed：缺 tenant 的 chunk 拒绝入库——一旦入库就是
                // "过滤 term 永不命中该文档"的静默丢失，比启动期报错难发现得多
                if (c.metadata() == null || c.metadata().tenant() == null
                        || c.metadata().tenant().isBlank()) {
                    throw new IllegalStateException(
                            "chunk 缺少 metadata.tenant，拒绝入库: " + c.chunkId());
                }
                out.add(c);
            }
        }
        return out;
    }

    private void createEsIndex(String index) throws Exception {
        es.indices().create(c -> c
                .index(index)
                .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                .mappings(mp -> mp
                        .properties("chunk_id", p -> p.keyword(k -> k))
                        .properties("doc_id", p -> p.keyword(k -> k))
                        .properties("type", p -> p.keyword(k -> k))
                        .properties("text", p -> p.text(t -> t))
                        .properties("breadcrumb", p -> p.keyword(k -> k))
                        .properties("metadata", p -> p.object(o -> o
                                .properties("service", pp -> pp.keyword(k -> k))
                                .properties("endpoint", pp -> pp.keyword(k -> k))
                                .properties("method", pp -> pp.keyword(k -> k))
                                .properties("error_codes", pp -> pp.keyword(k -> k))
                                .properties("auth_level", pp -> pp.integer(i -> i))
                                .properties("env", pp -> pp.keyword(k -> k))
                                .properties("tenant", pp -> pp.keyword(k -> k))))));
        log.info("ES staging 索引已建: {}", index);
    }

    private void createQdrantCollection(String name) throws Exception {
        qdrant.createCollectionAsync(name, Collections.VectorParams.newBuilder()
                .setSize(props.dashscope().embeddingDim())
                .setDistance(Collections.Distance.Cosine)
                .build(), Duration.ofSeconds(20)).get(20, TimeUnit.SECONDS);
        createIndex(name, "doc_id", Collections.PayloadSchemaType.Keyword);
        createIndex(name, "metadata.service", Collections.PayloadSchemaType.Keyword);
        createIndex(name, "metadata.auth_level", Collections.PayloadSchemaType.Integer);
        createIndex(name, "metadata.tenant", Collections.PayloadSchemaType.Keyword);
        log.info("Qdrant staging 集合已建: {}", name);
    }

    /**
     * L2 缓存集合是运行时数据（非语料派生）：只 ensure、不清建——是否清空由调用方决定
     * （reingest 成功切流后由 {@code ingest()} 显式 {@code l2.flush()}；失败路径不清）。
     */
    private void ensureCacheCollection() throws Exception {
        String cache = props.qdrant().cacheCollection();
        boolean reusable = false;
        if (Boolean.TRUE.equals(qdrant.collectionExistsAsync(cache).get(10, TimeUnit.SECONDS))) {
            try {
                qdrant.getCollectionInfoAsync(cache).get(10, TimeUnit.SECONDS);
                reusable = true;
            } catch (Exception ignore) { /* 落入重建分支 */ }
        }
        if (!reusable) {
            qdrant.createCollectionAsync(cache, Collections.VectorParams.newBuilder()
                    .setSize(props.dashscope().embeddingDim())
                    .setDistance(Collections.Distance.Cosine)
                    .build(), Duration.ofSeconds(20)).get(20, TimeUnit.SECONDS);
            createIndex(cache, "tenant", Collections.PayloadSchemaType.Keyword);
            createIndex(cache, "max_auth_level", Collections.PayloadSchemaType.Integer);
            log.info("Qdrant 缓存集合已建: {}", cache);
        }
        // G1-W1.3：TTL 清理按 created_at 范围删除，需要 payload index。已有集合也补一次：
        // createIndex 内部把"索引已存在"吞成一条 info，重复调用无副作用（否则老部署永远没索引）。
        createIndex(cache, "created_at", Collections.PayloadSchemaType.Integer);
    }

    private void createIndex(String collection, String field, Collections.PayloadSchemaType type)
            throws Exception {
        try {
            qdrant.createPayloadIndexAsync(collection, field, type, null, null, null,
                    Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.info("payload 索引已存在（忽略）: {}.{}", collection, field);
        }
    }

    private void dualWrite(List<Chunk> chunks, String esIndex, String qdrCollection) throws Exception {
        List<String> texts = chunks.stream().map(Chunk::text).toList();
        List<float[]> vectors = embedding.embed(texts);
        EmbeddingSanity.check(vectors, chunks.size(), props.dashscope().embeddingDim());

        es.bulk(b -> {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                Map<String, Object> src = toEsSource(c);
                b.operations(op -> op.index(idx -> idx.index(esIndex).id(c.chunkId()).document(src)));
            }
            return b;
        });
        es.indices().refresh(r -> r.index(esIndex));

        List<Points.PointStruct> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            io.qdrant.client.grpc.JsonWithInt.Value metadataValue =
                    io.qdrant.client.grpc.JsonWithInt.Value.newBuilder()
                            .setStructValue(io.qdrant.client.grpc.JsonWithInt.Struct.newBuilder()
                                    .putFields("service", value(str(c.metadata().service())))
                                    .putFields("endpoint", value(str(c.metadata().endpoint())))
                                    .putFields("method", value(str(c.metadata().method())))
                                    .putFields("error_codes", value(c.metadata().errorCodes().stream()
                                            .map(io.qdrant.client.ValueFactory::value).toList()))
                                    .putFields("auth_level", value(c.metadata().authLevel()))
                                    .putFields("env", value(str(c.metadata().env())))
                                    .putFields("tenant", value(str(c.metadata().tenant())))
                                    .build())
                            .build();
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = new LinkedHashMap<>();
            payload.put("chunk_id", value(c.chunkId()));
            payload.put("doc_id", value(c.docId()));
            payload.put("type", value(c.type()));
            payload.put("text", value(c.text()));
            payload.put("breadcrumb", value(c.breadcrumb()));
            payload.put("metadata", metadataValue);
            points.add(Points.PointStruct.newBuilder()
                    .setId(id(UUID.nameUUIDFromBytes(c.chunkId().getBytes())))
                    .setVectors(vectors(vectors.get(i)))
                    .putAllPayload(payload)
                    .build());
        }
        qdrant.upsertAsync(qdrCollection, points).get(60, TimeUnit.SECONDS);
        log.info("双写完成: ES {} + Qdrant {}", chunks.size(), chunks.size());
    }

    /** 切别名前的硬验收：staging 计数必须与语料一致，任何偏差都保留旧别名不动。 */
    private void verifyCounts(int expected, String esIndex, String qdrCollection) throws Exception {
        long esCount = es.count(c -> c.index(esIndex)).count();
        long qdrCount = qdrant.getCollectionInfoAsync(qdrCollection).get(10, TimeUnit.SECONDS).getPointsCount();
        if (esCount != expected || qdrCount < expected) {
            throw new IllegalStateException("staging 验收失败（别名不切换）: es=" + esCount
                    + " qdrant=" + qdrCount + " expected=" + expected);
        }
        log.info("staging 验收通过: es={} qdrant={}", esCount, qdrCount);
    }

    private void swapEsAlias(String alias, String newPhys) throws Exception {
        String oldPhys = null;
        try {
            // GetAliasResponse 是 index→aliases 的字典：按别名反查后取首个 index（本服务别名恒指单索引）
            var got = es.indices().getAlias(g -> g.name(alias));
            oldPhys = got.result().keySet().stream().findFirst().orElse(null);
        } catch (Exception notFound) {
            // 首次：别名不存在
        }
        final String from = oldPhys;
        es.indices().updateAliases(u -> u
                .actions(a -> {
                    if (from != null) a.remove(r -> r.index(from).alias(alias));
                    a.add(ad -> ad.index(newPhys).alias(alias));
                    return a;
                }));
        if (from != null) {
            try {
                es.indices().delete(d -> d.index(from));
            } catch (Exception e) {
                log.warn("旧 ES 索引删除失败（不影响服务）: {}", from);
            }
        }
        log.info("ES 别名已切换: {} -> {}（旧 {}）", alias, newPhys, from == null ? "无" : from);
    }

    private void swapQdrantAlias(String alias, String newPhys) throws Exception {
        String oldPhys = null;
        for (Collections.AliasDescription d : qdrant.listAliasesAsync().get(10, TimeUnit.SECONDS)) {
            if (alias.equals(d.getAliasName())) oldPhys = d.getCollectionName();
        }
        List<Collections.AliasOperations> ops = new ArrayList<>();
        if (oldPhys != null) {
            ops.add(Collections.AliasOperations.newBuilder()
                    .setDeleteAlias(Collections.DeleteAlias.newBuilder()
                            .setAliasName(alias).build())
                    .build());
        }
        ops.add(Collections.AliasOperations.newBuilder()
                .setCreateAlias(Collections.CreateAlias.newBuilder()
                        .setCollectionName(newPhys).setAliasName(alias).build())
                .build());
        qdrant.updateAliasesAsync(ops, Duration.ofSeconds(10)).get(10, TimeUnit.SECONDS);
        if (oldPhys != null) {
            try {
                qdrant.deleteCollectionAsync(oldPhys).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("旧 Qdrant 集合删除失败（不影响服务）: {}", oldPhys);
            }
        }
        log.info("Qdrant 别名已原子切换: {} -> {}（旧 {}）", alias, newPhys, oldPhys == null ? "无" : oldPhys);
    }

    private Map<String, Object> toEsSource(Chunk c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunk_id", c.chunkId());
        m.put("doc_id", c.docId());
        m.put("type", c.type());
        m.put("text", c.text());
        m.put("breadcrumb", c.breadcrumb());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("service", c.metadata().service());
        meta.put("endpoint", c.metadata().endpoint());
        meta.put("method", c.metadata().method());
        meta.put("error_codes", c.metadata().errorCodes());
        meta.put("auth_level", c.metadata().authLevel());
        meta.put("env", c.metadata().env());
        meta.put("tenant", c.metadata().tenant());
        m.put("metadata", meta);
        return m;
    }

    private static String str(String s) { return s == null ? "" : s; }

    /**
     * SOP 清单键 TTL（G1-W1.3）：7 天。
     *
     * <p>为什么必须带 TTL：① 语料删除/改名后，旧清单键会永久驻留，L2 档位继续直出已下线的操作步骤；
     * ② Redis 改成 {@code volatile-lru} 后，**没有 TTL 的键连被淘汰的资格都没有**——缓存压力下它们
     * 只会把 Redis 撑到 OOM。7 天覆盖"一周至少一次 reingest/重启"的部署节奏，过期后由
     * {@link #ensureSopWarm()} 在下次启动补灌。
     */
    static final Duration SOP_TTL = Duration.ofDays(7);

    /** Level 2 兜底：止损步骤按 doc 与 error_code 双维度预热进 Redis（P1：键按租户分片）。 */
    private void warmSop(List<Chunk> chunks) {
        warmSop(chunks, null);
    }

    /**
     * @param onlyTenants 非 null 时只重灌这些租户（启动补灌只补缺失的，不动仍在 TTL 内的租户）；
     *                    null = 全量重灌（reingest 路径）。
     */
    private void warmSop(List<Chunk> chunks, Set<String> onlyTenants) {
        Set<String> tenants = onlyTenants != null ? onlyTenants : chunks.stream()
                .map(c -> c.metadata().tenant())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String t : tenants) {
            store.deleteByPrefix("sop:" + t + ":");
        }
        // 先收集所有被写到的键，最后统一打 TTL：写入过程中反复 expire 没意义，
        // 而"先 expire 再写"在内存实现里会被下一次写入覆盖（TTL 属于 key，不是 value）。
        Set<String> touched = new LinkedHashSet<>();
        for (Chunk c : chunks) {
            String tenant = c.metadata().tenant();
            if (!tenants.contains(tenant)) continue;
            if (c.breadcrumb() != null && c.breadcrumb().contains("止损操作")) {
                String stepsKey = "sop:" + tenant + ":steps";
                store.mapPut(stepsKey, c.docId(), c.text());
                touched.add(stepsKey);
                for (String code : c.metadata().errorCodes()) {
                    String codeKey = "sop:" + tenant + ":code:" + code;
                    store.setAdd(codeKey, c.docId());
                    touched.add(codeKey);
                }
                String serviceKey = "sop:" + tenant + ":service:" + c.metadata().service();
                store.setAdd(serviceKey, c.docId());
                touched.add(serviceKey);
            }
        }
        for (String key : touched) {
            store.expire(key, SOP_TTL);
        }
        log.info("SOP 预热完成（按租户分片）: 租户 {} 个, 键 {} 个, ttl={}d",
                tenants.size(), touched.size(), SOP_TTL.toDays());
    }

    /**
     * 启动期补灌（G1-W1.3，供 {@code SopWarmupRunner} 调用）：SOP 键有 7 天 TTL，长跑或停机超过
     * TTL 后清单会自然过期，而 L2 档位直出没有第二份数据来源。这里只补"steps 缺失"的租户，
     * 未过期的租户一个键都不动。
     *
     * <p><b>所有异常都吞成 warn</b>：补灌不是主链路，chunks 文件/存储不可达不能把启动拖死。
     * "SOP 缺失"最坏是 L2 档位回退到通用话术；"起不来"是整机不可用，两者代价不对等。
     * 本方法不依赖 ES（chunks 来自本地离线文件），ES 不可达时补灌照样能成。
     */
    public void ensureSopWarm() {
        if (props.storage().inMemory()) return;   // memory 启动装载在 afterPropertiesSet 里必 warm
        try {
            List<Chunk> chunks = readChunks();
            Set<String> missing = chunks.stream()
                    .map(c -> c.metadata().tenant())
                    .distinct()
                    .filter(t -> store.mapIsEmpty("sop:" + t + ":steps"))
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (missing.isEmpty()) {
                log.info("SOP 启动校验：全部租户的止损清单均在 TTL 内，无需补灌");
                return;
            }
            warmSop(chunks, missing);
            log.info("SOP 启动补灌完成: 缺失租户 {}", missing);
        } catch (Exception e) {
            log.warn("SOP 启动补灌失败（不影响启动；下次重灌/重启会重试）: {}", e.toString());
        }
    }
}
