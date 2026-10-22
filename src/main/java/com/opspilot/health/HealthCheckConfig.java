package com.opspilot.health;

import com.opspilot.config.OpsDeployProperties;
import com.opspilot.config.OpsPilotProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 把依赖探测注册为 actuator 组件（bean 名决定组件键，见下），供 compose healthcheck、
 * demo.sh 预检与 liveness.py 直接消费聚合状态，无需解析自定义 JSON。
 * 明细（含异常摘要与 chunks 计数）只经鉴权端点 /api/v1/admin/health 暴露。
 *
 * <p><b>命名约定（2026-10-22 G1 修正）</b>：组件键 = bean 名去掉 {@code HealthIndicator}
 * 后缀（Boot 的 {@code HealthContributorNameFactory} 只认 healthindicator / healthcontributor
 * 两个后缀，<b>不会</b>把 {@code redisHealth} 变成 {@code redis}）。此前三个 bean 写成
 * {@code redisHealth}/{@code qdrantHealth}/{@code elasticsearchClusterHealth}，实际组件键是
 * 这三个驼峰名——与文档/健康分组的口径对不上，而分组 include 写错会在启动期被
 * {@code HealthEndpointGroupMembershipValidator} 拒掉。故统一改成 {@code *HealthIndicator}，
 * 键即 {@code redis}/{@code es}/{@code qdrant}/{@code db}/{@code backend}。
 *
 * <p>分组（liveness 不含外部依赖 / readiness 含 db+redis+es+qdrant+backend）由
 * application.yml 的 {@code management.endpoint.health.group.*} 声明——放配置而不是代码：
 * "哪些依赖算就绪"是部署形态问题（本机 memory 模式与生产 live 模式的答案不同）。
 */
@Configuration
public class HealthCheckConfig {

    @Bean
    HealthIndicator redisHealthIndicator(HealthProbe probe) {
        return () -> toHealth(probe.redis());
    }

    @Bean
    HealthIndicator qdrantHealthIndicator(HealthProbe probe) {
        return () -> toHealth(probe.qdrant());
    }

    /** 键 {@code es}：Boot 的 ElasticsearchRestClientHealthIndicator 已在 application.yml 关掉。 */
    @Bean
    HealthIndicator esHealthIndicator(HealthProbe probe) {
        return () -> toHealth(probe.es());
    }

    /**
     * 键 {@code db}（H2 账号库，SELECT 1）。
     *
     * <p>前提：application.yml 必须同时置 {@code management.health.db.enabled: false}——
     * 否则 Boot 的 {@code dbHealthContributor} 与本 bean 映射到同一个组件键 {@code db}，
     * 注册表按 Map.put 覆盖（谁赢看注册顺序），"到底探的是哪个"就变成了不可见的事实。
     */
    @Bean
    HealthIndicator dbHealthIndicator(JdbcTemplate jdbc) {
        return HealthIndicators.db(jdbc);
    }

    /**
     * 键 {@code backend}：模型后端形态（live/mock），只读配置、不发请求。
     * 为什么把部署开关一起传进来：strict 下 mock 是"自称生产却跑假模型"，必须报 DOWN（见 HealthIndicators）。
     */
    @Bean
    HealthIndicator backendHealthIndicator(OpsPilotProperties props, OpsDeployProperties deploy) {
        return HealthIndicators.backend(props, deploy);
    }

    private static Health toHealth(HealthProbe.Comp c) {
        var b = Health.status(c.status().equals("UP") ? "UP" : "DOWN");
        if (!c.detail().isBlank()) b.withDetail("detail", c.detail());
        return b.build();
    }
}
