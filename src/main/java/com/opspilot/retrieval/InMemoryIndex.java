package com.opspilot.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import com.opspilot.chunk.Chunk;

/**
 * memory 模式的语料索引（ADR-0022 决策 3）：把 {@code chunks.jsonl} 整份读进进程，
 * 两条腿（词法 / 向量）都从这一份数据出发。
 *
 * <p><b>为什么是"一份索引两条腿"而不是"两个假后端"</b>：RRF 融合要求两路召回**真的不同**，
 * 否则融合出来的顺序是装饰。这里词法腿按查询词/错误码打分、向量腿按 embedding 余弦打分，
 * 两个排序确实不同，RRF 做的是真事。反过来，若给内存模式造两个一模一样的假索引，
 * 那就是"为了像 live 而像 live"——本仓库的诚实性红线不容这一条。
 *
 * <p><b>权限过滤是构造保证</b>（ADR-0008/0022 决策 4）：{@link #visible} 是唯一的取数入口，
 * 两条腿都只能经它拿候选，且它直接调 {@link IdentityFilter#matches}——与 ES/Qdrant 侧同一份判据。
 * 不提供"不过滤取全部"的方法，是为了让"忘了过滤"这件事在类型层面就做不到。
 */
@Component
@ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "memory")
public class InMemoryIndex {

    /** 一条入库切片：原文档 + 归属身份 + 向量（向量缺席为 null，向量腿会跳过它）。 */
    public record Doc(ScoredChunk chunk, String tenant, String actionClass, float[] vector) {}

    private volatile List<Doc> docs = List.of();
    private volatile long builtAtMs;

    /** 整份替换（reingest 语义：新索引整体上线，不原地改）。 */
    public void load(List<Chunk> chunks, List<float[]> vectors) {
        List<Doc> fresh = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            Chunk.Metadata m = c.metadata();
            ScoredChunk sc = new ScoredChunk(
                    c.chunkId(), c.docId(), c.type(), c.text(), c.breadcrumb(),
                    m.service() == null ? "" : m.service(),
                    m.errorCodes() == null ? List.of() : m.errorCodes(),
                    m.authLevel(), new ScoredChunk.Scores(0, 0, 0, 0));
            fresh.add(new Doc(sc, m.tenant(), m.actionClass(),
                    vectors != null && i < vectors.size() ? vectors.get(i) : null));
        }
        this.docs = List.copyOf(fresh);
        this.builtAtMs = System.currentTimeMillis();
    }

    /** 按 id 取一条**该身份可见**的切片——L1 propose 的服务端复核入口（ADR-0028）：
     *  与 {@link #visible} 同一身份判据，不存在"不过滤的 byId"。 */
    public Doc byId(String chunkId, String tenant, int authLevel) {
        for (Doc d : docs) {
            if (d.chunk().chunkId().equals(chunkId)
                    && IdentityFilter.matches(d.tenant(), d.chunk().authLevel(), tenant, authLevel)) {
                return d;
            }
        }
        return null;
    }

    /** 该身份可见的切片——**唯一的取数入口**（过滤是构造保证，不是调用方的纪律）。 */
    public List<Doc> visible(String tenant, int authLevel) {
        List<Doc> out = new ArrayList<>();
        for (Doc d : docs) {
            if (IdentityFilter.matches(tenant, authLevel, d.tenant(), d.chunk().authLevel())) {
                out.add(d);
            }
        }
        return out;
    }

    /** 语料规模（健康面/覆盖范围用；不是"可见规模"）。 */
    public int size() {
        return docs.size();
    }

    /** 本份索引的装载时刻；未装载返回 0（调用方须如实渲染成"未知"，不得替换成当前时间）。 */
    public long builtAtMs() {
        return builtAtMs;
    }

    /**
     * 查询词切分：小写化后按非字母数字切，去重。
     * <p>与 ES 的 {@code match} 分析器**不是同一套**（没有 stemming/同义词）——这正是
     * "内存模式不承诺检索质量"（ADR-0022 决策 5）的具体含义，写在这里免得有人以为能对齐。
     */
    static Set<String> tokens(String query) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String t : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_]+")) {
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }
}
