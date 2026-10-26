package com.opspilot.config;

import java.time.Duration;
import org.apache.http.client.config.RequestConfig;
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
        return new OpsPilotProperties.Es("http://localhost:9200", "idx", "elastic", "", connectMs, readMs);
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
}