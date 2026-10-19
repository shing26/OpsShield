package com.opspilot.retrieval;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;

/**
 * Qdrant 向量检索：Query 实时向量化 + auth_level Payload 硬过滤（权限隔离第二道闸）。
 * 装配方式见 {@link EsSearchService} 的类注释（ADR-0022：由 {@code RetrievalWiring} 显式装配）。
 */
public class QdrantSearchService implements RetrievalLeg {

    private final QdrantClient qdrant;
    private final EmbeddingClient embedding;
    private final OpsPilotProperties props;

    public QdrantSearchService(QdrantClient qdrant, EmbeddingClient embedding, OpsPilotProperties props) {
        this.qdrant = qdrant;
        this.embedding = embedding;
        this.props = props;
    }

    @Override
    public List<ScoredChunk> search(String query, String tenant, int authLevel, int topK) throws Exception {
        float[] vector = embedding.embedOne(query);
        Points.SearchPoints.Builder req = Points.SearchPoints.newBuilder()
                .setCollectionName(props.qdrant().collection())
                .addAllVector(toFloatList(vector))
                .setLimit(topK)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build())
                .setFilter(authFilter(tenant, authLevel));

        List<Points.ScoredPoint> hits = qdrant.searchAsync(req.build()).get(5, TimeUnit.SECONDS);
        List<ScoredChunk> out = new ArrayList<>();
        for (Points.ScoredPoint p : hits) {
            out.add(fromPoint(p));
        }
        return out;
    }

    /**
     * 双条件权限过滤（恒构建、失败关闭）：
     * ① term(metadata.tenant) 等值 —— 跨租户零命中（P1 租户显式化，tenant 缺失时钳到 ""，
     *    与任何真实文档值都不相等 → 空集）；
     * ② range(metadata.auth_level) lte(user 级) —— doc≤user；authLevel<=0 钳到 lte(0)
     *    （库内文档 auth_level>=1，命中空集），与 ES 侧一致，杜绝 level-0 token 绕过提权（P0）。
     */
    static Points.Filter authFilter(String tenant, int authLevel) {
        return Points.Filter.newBuilder()
                .addMust(Points.Condition.newBuilder()
                        .setField(Points.FieldCondition.newBuilder()
                                .setKey(IdentityFilter.TENANT_FIELD)
                                .setMatch(Points.Match.newBuilder()
                                        .setKeyword(tenant == null ? "" : tenant).build())
                                .build())
                        .build())
                .addMust(Points.Condition.newBuilder()
                        .setField(Points.FieldCondition.newBuilder()
                                .setKey(IdentityFilter.AUTH_LEVEL_FIELD)
                                .setRange(Points.Range.newBuilder().setLte(Math.max(authLevel, 0)).build())
                                .build())
                        .build())
                .build();
    }

    public static List<Float> toFloatList(float[] v) {
        List<Float> l = new ArrayList<>(v.length);
        for (float f : v) l.add(f);
        return l;
    }

    @SuppressWarnings("unchecked")
    static ScoredChunk fromPoint(Points.ScoredPoint p) {
        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> pl = p.getPayloadMap();
        io.qdrant.client.grpc.JsonWithInt.Value meta = pl.get("metadata");
        Map<String, io.qdrant.client.grpc.JsonWithInt.Value> mf =
                meta.hasStructValue() ? meta.getStructValue().getFieldsMap() : Map.of();
        List<String> codes = new ArrayList<>();
        io.qdrant.client.grpc.JsonWithInt.Value ec = mf.get("error_codes");
        if (ec != null && ec.hasListValue()) {
            for (io.qdrant.client.grpc.JsonWithInt.Value v : ec.getListValue().getValuesList()) {
                codes.add(v.getStringValue());
            }
        }
        return new ScoredChunk(
                str(pl.get("chunk_id")), str(pl.get("doc_id")), str(pl.get("type")),
                str(pl.get("text")), str(pl.get("breadcrumb")),
                str(mf.get("service")), codes,
                mf.get("auth_level") == null ? 1 : (int) mf.get("auth_level").getIntegerValue(),
                new ScoredChunk.Scores(0, p.getScore(), 0, 0));
    }

    private static String str(io.qdrant.client.grpc.JsonWithInt.Value v) {
        return v == null ? "" : v.getStringValue();
    }
}
