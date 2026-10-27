package com.opspilot.llm;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.opspilot.config.OpsPilotProperties;

/** DashScope gte-rerank 精排：返回按相关度降序的 (原索引, 分数)。 */
@Component
public class RerankClient {

    public record Ranked(int index, double score) {}

    private final RestClient rest;
    private final OpsPilotProperties props;

    public RerankClient(RestClient dashScopeRestClient, OpsPilotProperties props) {
        this.rest = dashScopeRestClient;
        this.props = props;
    }

    public List<Ranked> rerank(String query, List<String> documents, int topN) {
        if (!props.dashscope().live()) {
            List<Ranked> mock = new ArrayList<>();
            for (int i = 0; i < documents.size(); i++) {
                mock.add(new Ranked(i, MockEngine.rerankScore(query, documents.get(i))));
            }
            mock.sort((a, b) -> Double.compare(b.score(), a.score()));
            return mock.subList(0, Math.min(topN, mock.size()));
        }
        Map<String, Object> body = Map.of(
                "model", props.dashscope().rerankModel(),
                "input", Map.of("query", query, "documents", documents),
                "parameters", Map.of("return_documents", false, "top_n", topN));
        JsonNode resp = rest.post()
                .uri("/api/v1/services/rerank/text-rerank/text-rerank")
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        List<Ranked> out = new ArrayList<>();
        if (resp == null) return out;
        for (JsonNode r : resp.path("output").path("results")) {
            out.add(new Ranked(r.path("index").asInt(), r.path("relevance_score").asDouble()));
        }
        return out;
    }
}
