package com.opspilot.llm;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.opspilot.config.OpsPilotProperties;

/** DashScope text-embedding-v3（OpenAI 兼容模式），batch ≤10。 */
@Component
public class EmbeddingClient {

    private final RestClient rest;
    private final OpsPilotProperties props;

    public EmbeddingClient(RestClient dashScopeRestClient, OpsPilotProperties props) {
        this.rest = dashScopeRestClient;
        this.props = props;
    }

    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        int batch = 10;
        for (int i = 0; i < texts.size(); i += batch) {
            List<String> slice = texts.subList(i, Math.min(i + batch, texts.size()));
            out.addAll(embedBatch(slice));
        }
        return out;
    }

    public float[] embedOne(String text) {
        return embed(List.of(text)).get(0);
    }

    private List<float[]> embedBatch(List<String> texts) {
        if (!props.dashscope().live()) {
            List<float[]> mock = new ArrayList<>();
            for (String t : texts) mock.add(MockEngine.embed(t, props.dashscope().embeddingDim()));
            return mock;
        }
        Map<String, Object> body = Map.of(
                "model", props.dashscope().embeddingModel(),
                "input", texts,
                "dimensions", props.dashscope().embeddingDim(),
                "encoding_format", "float");
        JsonNode resp = rest.post()
                .uri("/compatible-mode/v1/embeddings")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        List<float[]> vectors = new ArrayList<>();
        if (resp == null) throw new IllegalStateException("embedding 响应为空");
        for (JsonNode d : resp.path("data")) {
            JsonNode emb = d.path("embedding");
            float[] v = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) v[i] = (float) emb.get(i).asDouble();
            vectors.add(v);
        }
        return vectors;
    }
}
