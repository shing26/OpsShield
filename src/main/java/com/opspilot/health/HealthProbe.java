package com.opspilot.health;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.qdrant.client.QdrantClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.retrieval.InMemoryIndex;
import com.opspilot.store.KvStore;

/**
 * 依赖健康探针（部署级健康，与 /actuator/health 的聚合互补）：一次探测回答
 * "Redis 连得上吗 / Qdrant 集合在吗 / ES 活着吗 / 现在是 live 还是 mock / 索引里有多少块"。
 * 每个探测 3s 超时自吞异常返回 DOWN——健康检查本身绝不能把服务拖死。
 * summary() 有 5s TTL 缓存（ReentrantLock 双检，防 Ops Console 2s 轮询把探测放大；
 * 外审核账：面板量级并发用双检锁即够，不引异步协调）。detail 是人读字符串，
 * value 给面板结构化数字（ES docs / Qdrant pts；DOWN 或无计数语义为 null——前端不再解析字符串）。
 */
@Component
public class HealthProbe {

    public record Comp(String status, String detail, Long value) {
        static Comp up(String detail) { return new Comp("UP", detail, null); }
        static Comp up(String detail, long value) { return new Comp("UP", detail, value); }
        static Comp down(Exception e) { return new Comp("DOWN", brief(e), null); }
        static Comp down(String detail) { return new Comp("DOWN", detail, null); }
    }

    private final ObjectProvider<ElasticsearchClient> es;
    private final ObjectProvider<QdrantClient> qdrant;
    private final KvStore store;
    private final ObjectProvider<InMemoryIndex> memoryIndex;
    private final OpsPilotProperties props;

    public HealthProbe(ObjectProvider<ElasticsearchClient> es, ObjectProvider<QdrantClient> qdrant,
                       KvStore store, ObjectProvider<InMemoryIndex> memoryIndex,
                       OpsPilotProperties props) {
        this.es = es;
        this.qdrant = qdrant;
        this.store = store;
        this.memoryIndex = memoryIndex;
        this.props = props;
    }

    public Comp redis() {
        // memory 模式：后端是进程内 KV，**没有可探的外部依赖**——报 UP 是实话（它确实可用），
        // 但 detail 必须写明是内存后端，否则"redis UP"会被读成"Redis 连上了"（ADR-0022 决策 5）。
        if (inMemory()) return Comp.up("in-memory kv（" + store.backend() + "，重启即清空）");
        try {
            store.exists("health:ping"); // 单键 GET，不 SCAN
            return Comp.up("reachable");
        } catch (Exception e) {
            return Comp.down(e);
        }
    }

    public Comp qdrant() {
        if (inMemory()) return memoryComp("pts");
        QdrantClient client = qdrant.getIfAvailable();
        // live 模式下客户端一定在（EsConfig/QdrantConfig 的 matchIfMissing=true）；缺席只可能是
        // 装配被改坏，此时如实报 DOWN 并点名原因，不要静默报 UP
        if (client == null) return Comp.down("qdrant 客户端未装配（storage.mode=live 却无 bean）");
        try {
            var info = client.getCollectionInfoAsync(props.qdrant().collection())
                    .get(3, TimeUnit.SECONDS);   // 查询侧传的是别名，这里一并验证别名解析
            return Comp.up(info.getPointsCount() + " pts", info.getPointsCount());
        } catch (Exception e) {
            return Comp.down(e);
        }
    }

    public Comp es() {
        if (inMemory()) return memoryComp("docs");
        ElasticsearchClient client = es.getIfAvailable();
        if (client == null) return Comp.down("ES 客户端未装配（storage.mode=live 却无 bean）");
        try {
            if (!client.ping().value()) return new Comp("DOWN", "no response", null);
            // 显式 request 重载（Consumer 版是 final 方法，不利于 mock 拦截）
            var req = co.elastic.clients.elasticsearch.core.CountRequest.of(
                    b -> b.index(props.es().index())); // 别名=读路径同款
            long n = client.count(req).count();
            return Comp.up(n + " docs", n);
        } catch (Exception e) {
            return Comp.down(e);
        }
    }

    private final java.util.concurrent.locks.ReentrantLock probeLock =
            new java.util.concurrent.locks.ReentrantLock();
    private volatile Map<String, Object> cachedSummary;
    private volatile long cachedUntilMs;

    /** 细节汇总（供 /admin/health、/admin/state 与预检脚本消费）；5s TTL 双检缓存。 */
    public Map<String, Object> summary() {
        Map<String, Object> hit = cachedSummary;
        if (hit != null && System.currentTimeMillis() < cachedUntilMs) return hit;
        probeLock.lock();
        try {
            hit = cachedSummary; // 双重检查：等锁期间可能已被刷新
            if (hit != null && System.currentTimeMillis() < cachedUntilMs) return hit;
            Map<String, Object> fresh = buildSummary();
            cachedSummary = fresh;
            cachedUntilMs = System.currentTimeMillis() + 5_000L;
            return fresh;
        } finally {
            probeLock.unlock();
        }
    }

    private Map<String, Object> buildSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("redis", redis());
        out.put("qdrant", qdrant());
        out.put("es", es());
        // 存储后端自报（ADR-0022 决策 5）：面板/审计/启动摘要都读这一处，页面据此标注
        out.put("storage", props.storage().inMemory() ? "memory" : "live");
        var ds = props.dashscope();
        out.put("live_mode", ds.live());
        out.put("backend", Map.of(
                "embedding", ds.live() ? "dashscope:" + ds.embeddingModel() : "mock-lexical-hash",
                "rerank", ds.live() ? "dashscope:" + ds.rerankModel() : "mock-idf-coverage",
                "llm", ds.live() ? "dashscope:" + ds.llmModel() : "mock-template"));
        boolean allUp = ((Comp) out.get("redis")).status().equals("UP")
                && ((Comp) out.get("qdrant")).status().equals("UP")
                && ((Comp) out.get("es")).status().equals("UP");
        out.put("status", allUp ? "UP" : "DEGRADED");
        return out;
    }

    private boolean inMemory() {
        return props.storage().inMemory();
    }

    private Long memoryChunks() {
        InMemoryIndex idx = memoryIndex.getIfAvailable();
        return idx == null ? null : (long) idx.size();
    }

    /** 内存后端的读数：索引未装载时如实说"未装载"，不拿 0 冒充"有 0 篇"。 */
    private Comp memoryComp(String unit) {
        Long n = memoryChunks();
        return n == null ? Comp.up("in-memory（索引未装载）") : Comp.up(n + " " + unit + " (in-memory)", n);
    }

    private static String brief(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }
}
