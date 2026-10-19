package com.opspilot.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QdrantConfig {

    /**
     * memory 模式下不建（ADR-0022，与 {@code EsConfig} 同口径）：Qdrant 客户端本身是懒连接的
     * （不建也不会报错），但留一个永远连不上的客户端只会让"零依赖"这句话在代码里说不圆。
     */
    @Bean
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "live", matchIfMissing = true)
    public QdrantClient qdrantClient(OpsPilotProperties props) {
        QdrantGrpcClient.Builder b = QdrantGrpcClient
                .newBuilder(props.qdrant().host(), props.qdrant().grpcPort(), false)
                .withTimeout(java.time.Duration.ofSeconds(5));
        // P3：凭据存在才附加（空=CI/本地无认证形态）；api-key 同护 REST 与 gRPC
        String apiKey = props.qdrant().apiKey();
        if (apiKey != null && !apiKey.isBlank()) b.withApiKey(apiKey);
        return new QdrantClient(b.build());
    }
}
