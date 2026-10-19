package com.opspilot.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.qdrant.client.QdrantClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.llm.EmbeddingClient;

/**
 * 检索腿装配（ADR-0022 的检索接缝）：按 {@code opspilot.storage.mode} 给出
 * **同名同型**的两条腿——{@code lexicalLeg} 与 {@code vectorLeg}。
 *
 * <p>为什么要显式装配而不是两个 {@code @Component}：{@link HybridSearchService} 要的是
 * "两条腿"这个形状，而不是"ES 与 Qdrant 这两个具体类"。显式装配之后，memory 模式给出的是
 * 同一份内存索引上的两种打分，编排层一行都不用改——RRF、超时隔离、降级判定全部原样复用。
 *
 * <p>顺带消掉一个隐患：原先两个服务都是 {@code @Component}，按类型注入时**谁是谁**只靠参数名
 * 与声明顺序暗示。命名 bean 之后，"词法腿"与"向量腿"在装配面上是显式的。
 */
@Configuration
public class RetrievalWiring {

    @Bean("lexicalLeg")
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "live", matchIfMissing = true)
    public RetrievalLeg esLexicalLeg(ElasticsearchClient es, OpsPilotProperties props) {
        return new EsSearchService(es, props);
    }

    @Bean("vectorLeg")
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "live", matchIfMissing = true)
    public RetrievalLeg qdrantVectorLeg(QdrantClient qdrant, EmbeddingClient embedding,
                                        OpsPilotProperties props) {
        return new QdrantSearchService(qdrant, embedding, props);
    }

    @Bean("lexicalLeg")
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "memory")
    public RetrievalLeg inMemoryLexicalLeg(InMemoryIndex index) {
        return new InMemoryLexicalLeg(index);
    }

    @Bean("vectorLeg")
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "memory")
    public RetrievalLeg inMemoryVectorLeg(InMemoryIndex index, EmbeddingClient embedding) {
        return new InMemoryVectorLeg(index, embedding);
    }
}
