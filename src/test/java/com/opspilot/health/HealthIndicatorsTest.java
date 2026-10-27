package com.opspilot.health;

import com.opspilot.config.OpsDeployProperties;
import com.opspilot.config.OpsPilotProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两个自定义健康指示器的回归锁（G1-W1.4）。
 *
 * <p>锁的是"健康面必须说实话"这条纪律：
 * ① H2 探针 SELECT 1 成功才 UP，抛异常必须 DOWN 且带上异常摘要（不能吞成 UP）；
 * ② 模型后端只读配置：live=UP / mock=UP(detail=mock) / **strict+mock=DOWN**——
 *    最后一格是"自称生产却跑假模型"的唯一可见面，丢了它，strict 声明就只是启动期的一句话。
 *
 * <p>不触网、不连库：JdbcTemplate 是 mock——健康指示器的判据是"SQL 结果/异常怎么映射"，
 * 真连一次库只会让用例依赖本机环境，测不到那层映射。
 */
class HealthIndicatorsTest {

    private static OpsPilotProperties props(String mode, String apiKey) {
        return new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://x", apiKey, "em", 1024, "rm", "lm",
                        60, 3000, 15000, mode),
                null, null, null, null, null, null, null);
    }

    @Test
    void dbIsUpWhenSelectOneReturnsOne() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        Health h = HealthIndicators.db(jdbc).health();
        assertEquals(Status.UP, h.getStatus());
        assertEquals("SELECT 1 ok", h.getDetails().get("detail"));
    }

    @Test
    void dbIsDownWithReasonWhenQueryThrows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        Health h = HealthIndicators.db(jdbc).health();
        assertEquals(Status.DOWN, h.getStatus(), "库连不上必须 DOWN——把异常吞成 UP 是健康面最坏的失效方式");
        assertTrue(String.valueOf(h.getDetails().get("detail")).contains("connection refused"),
                "DOWN 必须带可排障的摘要，不能只说 DOWN");
    }

    @Test
    void dbIsDownOnUnexpectedResult() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);
        Health h = HealthIndicators.db(jdbc).health();
        assertEquals(Status.DOWN, h.getStatus(), "SELECT 1 没回 1 就不能算连通");
    }

    @Test
    void backendLiveIsUp() {
        Health h = HealthIndicators.backend(props("live", "key"), new OpsDeployProperties(false, false)).health();
        assertEquals(Status.UP, h.getStatus());
        assertEquals("live", h.getDetails().get("detail"));
    }

    @Test
    void backendMockIsUpButSaysMock() {
        Health h = HealthIndicators.backend(props("mock", ""), new OpsDeployProperties(false, false)).health();
        assertEquals(Status.UP, h.getStatus(), "mock 是 ADR-0022 的合法零依赖形态，不是故障");
        assertEquals("mock", h.getDetails().get("detail"), "detail 必须点明跑的是 mock，否则 UP 会被读成『真实模型可用』");
    }

    @Test
    void backendMockUnderStrictIsDown() {
        Health h = HealthIndicators.backend(props("mock", ""), new OpsDeployProperties(true, false)).health();
        assertEquals(Status.DOWN, h.getStatus(), "strict 声明生产却跑 mock：运行期必须如实报 DOWN");
        assertTrue(String.valueOf(h.getDetails().get("detail")).contains("strict"));
    }

    /** 防御：deploy 组整体缺配时绑定成 null，指示器不得 NPE（宁可按非 strict 处理）。 */
    @Test
    void backendToleratesNullDeployProperties() {
        HealthIndicator ind = HealthIndicators.backend(props("mock", ""), null);
        assertEquals(Status.UP, ind.health().getStatus());
    }
}
