package com.opspilot.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 出站客户端。
 *
 * <p>B3（2026-09-21 补）：此前只设了认证，没有 connect/socket 超时——ES 挂住时
 * 检索线程会一直等。这里补上，取值来自 {@code opspilot.es.*}。
 *
 * <p>与 {@code RestClientConfig} 同样的理由：超时对象抽成 {@link #requestConfig} 以便单测，
 * 否则"写了超时"只能靠 grep 证明。
 */
@Configuration
public class EsConfig {

    /**
     * 把 {@code opspilot.es.*} 的超时写进给定的 builder。
     * <p>生产路径（下面的 {@code setRequestConfigCallback}）与 {@code OutboundTimeoutTest}
     * 走的是<b>同一个函数</b>——否则测试验的就不是生产那条路，等于没验。
     */
    static RequestConfig.Builder applyTimeouts(RequestConfig.Builder builder, OpsPilotProperties.Es es) {
        return builder.setConnectTimeout(Timeout.ofMilliseconds(es.connectTimeoutMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(es.readTimeoutMs()));
    }

    /** 供测试直接取一份成品配置；生产路径用 {@link #applyTimeouts}。 */
    static RequestConfig requestConfig(OpsPilotProperties.Es es) {
        return applyTimeouts(RequestConfig.custom(), es).build();
    }

    /** HttpClient 5 把 socket 超时放在连接级配置上，不是请求级。 */
    static ConnectionConfig.Builder applySocketTimeout(ConnectionConfig.Builder builder, OpsPilotProperties.Es es) {
        return builder.setSocketTimeout(Timeout.ofMilliseconds(es.readTimeoutMs()));
    }

    static ConnectionConfig connectionConfig(OpsPilotProperties.Es es) {
        return applySocketTimeout(ConnectionConfig.custom(), es).build();
    }

    /**
     * 出站连接池：上限来自 {@code opspilot.es.pool-*}，不再交给库默认。
     * <p>库默认 total=30 / per-route=10——单 ES 主机就是单路由，10 个连接把检索在途
     * 卡在 ~10 量级，高并发档延迟随队列深度爆炸（压测实证，报告见
     * {@code offline/load/reports/}），且 inflight 永远够不到降级阈值。
     * 单独抽成静态方法与 {@link #applyTimeouts} 同一理由：测试打的就是生产这条路径。
     */
    static PoolingAsyncClientConnectionManagerBuilder applyHttpConfig(
            PoolingAsyncClientConnectionManagerBuilder builder, OpsPilotProperties.Es es) {
        return builder.setMaxConnTotal(es.poolMaxTotal()).setMaxConnPerRoute(es.poolMaxPerRoute());
    }

    static PoolingAsyncClientConnectionManager connectionManager(OpsPilotProperties.Es es) {
        return applyHttpConfig(PoolingAsyncClientConnectionManagerBuilder.create(), es).build();
    }

    /**
     * memory 模式下**不建**（ADR-0022）。两个理由，第二个才是关键：
     * ① 零依赖模式不该存在一条连不上的客户端，那只会给人"它可能被用到"的错觉；
     * ② Boot 会按 {@code RestClient} bean 自动注册 {@code ElasticsearchRestClientHealthIndicator}，
     *    它**无条件 ping ES**——于是内存模式下 `/actuator/health` 恒为 DOWN。
     *    实测咬到：memory 模式启动成功、功能正常，健康面却报 DOWN，而 demo.sh / liveness.py /
     *    compose healthcheck 都以这个端点当存活判据 ⇒ 整条零依赖路径被判"没起来"。
     */
    @Bean
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "live", matchIfMissing = true)
    public Rest5Client esRestClient(OpsPilotProperties props) {
        java.net.URI uri = java.net.URI.create(props.es().uri());
        HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());
        Rest5ClientBuilder builder = Rest5Client.builder(host)
                .setRequestConfigCallback(rc -> applyTimeouts(rc, props.es()))
                .setConnectionConfigCallback(cc -> applySocketTimeout(cc, props.es()))
                .setConnectionManagerCallback(cm -> applyHttpConfig(cm, props.es()));
        // P3：xpack security 形态下 Basic 认证；口令空保持无认证（CI/本地）
        String pass = props.es().password();
        builder = builder.setHttpClientConfigCallback(c -> {
            if (pass != null && !pass.isBlank()) {
                BasicCredentialsProvider creds = new BasicCredentialsProvider();
                creds.setCredentials(
                        new AuthScope(host.getHostName(), host.getPort()),
                        new UsernamePasswordCredentials(props.es().username(), pass.toCharArray()));
                c.setDefaultCredentialsProvider(creds);
            }
        });
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "live", matchIfMissing = true)
    public ElasticsearchClient elasticsearchClient(Rest5Client restClient) {
        ElasticsearchTransport transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
