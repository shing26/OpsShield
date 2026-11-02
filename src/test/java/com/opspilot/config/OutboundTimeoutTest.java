package com.opspilot.config;

import java.lang.reflect.Field;
import java.time.Duration;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.nio.conn.PoolingNHttpClientConnectionManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3（2026-09-21 补）：出站 socket 超时<b>真的流进了请求工厂</b>，不只是源码里有这几个字。
 *
 * <p>Boot 4 迁移（#8）：Boot 4 移除了 {@code ClientHttpRequestFactorySettings}，
 * 本测试同步改为断言 {@link RestClientConfig.Timeouts} → {@code JdkClientHttpRequestFactory} 的传递链；
 * 双向锁不变：合法值原样到达、0/负值在启动期被拒（后者在 {@code OpsPilotPropertiesValidationTest}）。
 *
 * <p>不触网、零凭据：只构造设置/工厂对象，不建连接。
 */
class OutboundTimeoutTest {

    private static OpsPilotProperties.DashScope dashScope(int connectMs, int readMs) {
        return new OpsPilotProperties.DashScope(
                "https://example.invalid", "k", "text-embedding-v3", 1024,
                "gte-rerank-v2", "qwen-plus", 60, connectMs, readMs, "mock");
    }

    private static OpsPilotProperties.Es es(int connectMs, int readMs) {
        return new OpsPilotProperties.Es("http://localhost:9200", "idx", "elastic", "", connectMs, readMs, 100, 100);
    }

    @Test
    void dashScopeSettingsCarryConfiguredTimeouts() {
        RestClientConfig.Timeouts t = RestClientConfig.settings(dashScope(3000, 15000));
        assertThat(t.connect()).isEqualTo(Duration.ofMillis(3000));
        assertThat(t.read()).isEqualTo(Duration.ofMillis(15000));
    }

    @Test
    void dashScopeTimeoutsAreExplicitNotNull() {
        // 与 Boot 3 的 DEFAULTS（两个 null = 交给底层库默认，常见是无限等）对齐：
        // 这里钉住"我们确实给了具体值"，而不是把超时留空。
        RestClientConfig.Timeouts t = RestClientConfig.settings(dashScope(3000, 15000));
        assertThat(t.connect()).isNotNull();
        assertThat(t.read()).isNotNull();
    }

    @Test
    void changingTheConfiguredValueChangesTheSettings() {
        // 反证：值不是硬编码的。若把配置读成常量，这条会红。
        assertThat(RestClientConfig.settings(dashScope(1, 2)).read())
                .isEqualTo(Duration.ofMillis(2));
        assertThat(RestClientConfig.settings(dashScope(3, 4)).read())
                .isEqualTo(Duration.ofMillis(4));
    }

    @Test
    void dashScopeSettingsCanActuallyBuildAFactory() {
        // 这条挡的是"设置对象构造出来了、但参数形态让工厂建不出来"这类只在起栈时才炸的错。
        assertThat(RestClientConfig.requestFactory(RestClientConfig.settings(dashScope(3000, 15000))))
                .isNotNull();
    }

    @Test
    void esRequestConfigCarriesConfiguredTimeouts() {
        RequestConfig c = EsConfig.requestConfig(es(3000, 10000));
        assertThat(c.getConnectTimeout()).isEqualTo(3000);
        assertThat(c.getSocketTimeout()).isEqualTo(10000);
    }

    @Test
    void esSocketTimeoutIsLooserThanTheRetrievalLegBudget() {
        // 口径钉住：socket 兜底必须比业务级预算（retrieval.leg-timeout-ms）松，
        // 否则两者赛跑，抛出的是原始 socket 异常而不是业务降级信号。
        int legBudgetMs = 4500;   // = application.yml 的 retrieval.leg-timeout-ms 默认值
        assertThat(EsConfig.requestConfig(es(3000, 10000)).getSocketTimeout()).isGreaterThan(legBudgetMs);
        assertThat(RestClientConfig.settings(dashScope(3000, 15000)).read())
                .isGreaterThan(Duration.ofMillis(legBudgetMs));
    }

    @Test
    void esPoolLimitsFlowIntoTheProductionConnectionManager() throws Exception {
        // 压测收口（2026-11-02）：池上限必须真的流进生产客户端的连接池，
        // 不能只存在于 yml 的几个字里——与 applyTimeouts 同一条"测试即生产路径"纪律。
        // 断言对象是 applyHttpConfig 真正建出的 HttpAsyncClient 内部连接池
        // （InternalHttpAsyncClient 为库内部类，字段名随 4.1.5 钉死的 BOM 走；换版本时这里会红）。
        PoolingNHttpClientConnectionManager cm = poolOfProductionClient(es(3000, 10000));
        try {
            assertThat(cm.getMaxTotal()).isEqualTo(100);
            assertThat(cm.getDefaultMaxPerRoute()).isEqualTo(100);
        } finally {
            cm.shutdown();
        }
    }

    @Test
    void esPoolLimitIsConfigDrivenNotHardcoded() throws Exception {
        // 反证：改配置必须改行为。若池上限被写死，这条会红。
        OpsPilotProperties.Es tuned = new OpsPilotProperties.Es(
                "http://localhost:9200", "idx", "elastic", "", 3000, 10000, 55, 44);
        PoolingNHttpClientConnectionManager cm = poolOfProductionClient(tuned);
        try {
            assertThat(cm.getMaxTotal()).isEqualTo(55);
            assertThat(cm.getDefaultMaxPerRoute()).isEqualTo(44);
        } finally {
            cm.shutdown();
        }
    }

    @Test
    void esPoolDefaultExceedsTheLibraryDefaultOfTenPerRoute() {
        // 口径钉住：默认值必须显著大于库默认 per-route=10——单 ES 主机即单路由，
        // 若哪天默认值退回 10，等于把压测修掉的瓶颈又请回来，这条先红。
        assertThat(es(3000, 10000).poolMaxPerRoute()).isGreaterThan(10);
    }

    /** 走生产函数 {@link EsConfig#applyHttpConfig} 建客户端，取出其内部连接池。 */
    private static PoolingNHttpClientConnectionManager poolOfProductionClient(OpsPilotProperties.Es es)
            throws Exception {
        var client = EsConfig.applyHttpConfig(
                org.apache.http.impl.nio.client.HttpAsyncClients.custom(), es).build();
        for (Class<?> c = client.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("connmgr");
                f.setAccessible(true);
                return (PoolingNHttpClientConnectionManager) f.get(client);
            } catch (NoSuchFieldException e) {
                // 继续向父类找
            }
        }
        throw new IllegalStateException("InternalHttpAsyncClient.connmgr 不存在——httpasyncclient 版本变了");
    }
}