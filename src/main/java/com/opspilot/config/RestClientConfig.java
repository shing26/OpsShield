package com.opspilot.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * DashScope 出站客户端（精排 + embedding 共用；LLM 生成走 {@code LlmClient} 自己的 JDK HttpClient）。
 *
 * <p>B3（2026-09-21 补）：此前这里没有 connect/read 超时，全靠底层客户端默认值。
 * {@code RerankClient} 与 {@code EmbeddingClient} 都从这个 bean 取连接，所以这一处
 * 覆盖了那两路的全部出站调用。
 *
 * <p><b>Boot 4 迁移（#8，2026-10-27）</b>：Boot 4 移除了 {@code org.springframework.boot.web.client}
 * 下的 {@code ClientHttpRequestFactories/ClientHttpRequestFactorySettings}。这里改用 Spring
 * Framework 7 自带的 {@link JdkClientHttpRequestFactory} + JDK {@link HttpClient} 承载同一对超时，
 * 语义不变：connect 超时在 client 上，read 超时在工厂上。
 *
 * <p>为什么超时设置单独抽成 {@link #settings} 而不是内联在 {@code builder()} 里：
 * 内联之后唯一能验证它的手段是 grep 源码里有没有 "timeout" 字样——那只能证明写了字，
 * 证明不了值真的流进了请求工厂。抽出来之后 {@code OutboundTimeoutTest} 可以直接断言
 * 配置值 → 工厂设置的传递，且能把"0 或负数被拒绝"也钉住。
 */
@Configuration
public class RestClientConfig {

    /** 出站超时的单一事实源（毫秒 → Duration）：请求工厂与测试都从这里取值。 */
    record Timeouts(Duration connect, Duration read) {
    }

    static Timeouts settings(OpsPilotProperties.DashScope dashscope) {
        return new Timeouts(Duration.ofMillis(dashscope.connectTimeoutMs()),
                            Duration.ofMillis(dashscope.readTimeoutMs()));
    }

    /** 按 Timeouts 建请求工厂：connect 走 HttpClient，read 走工厂（两条超时都落实，不是只写注释）。 */
    static JdkClientHttpRequestFactory requestFactory(Timeouts timeouts) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeouts.connect()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeouts.read());
        return factory;
    }

    @Bean
    public RestClient dashScopeRestClient(OpsPilotProperties props) {
        return RestClient.builder()
                .baseUrl(props.dashscope().baseUrl())
                .defaultHeader("Authorization", "Bearer " + props.dashscope().apiKey())
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(requestFactory(settings(props.dashscope())))
                .build();
    }
}