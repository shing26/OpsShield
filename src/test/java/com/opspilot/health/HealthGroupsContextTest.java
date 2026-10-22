package com.opspilot.health;

import com.opspilot.config.OpsDeployProperties;
import com.opspilot.config.OpsPilotProperties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.availability.AvailabilityProbesAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.availability.ApplicationAvailabilityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 健康分组的**装配级**回归锁（G1-W1.4）：把"application.yml 片段里写的名字"与
 * "运行时真实存在的组件键"钉在一起。
 *
 * <p>为什么必须用 Spring 上下文测而不是只测指示器逻辑：Boot 的组件键 = bean 名去掉
 * {@code HealthIndicator}/{@code HealthContributor} 后缀（{@code HealthContributorNameFactory}
 * 只认这两个后缀）。历史写法 {@code redisHealth} 实际产生的键是 {@code redisHealth}（驼峰），
 * 而分组 include 里写 {@code redis} 在**启动期**会被
 * {@code HealthEndpointGroupMembershipValidator} 拒绝（{@code NoSuchHealthContributorException}）
 * ——那是一个只有真起上下文才会暴露的失败，单测指示器逻辑永远看不到。
 *
 * <p>同一把锁也覆盖"liveness 不得含外部依赖"：若有人把 redis/db 加进 liveness，
 * 单个中间件抖动就会让编排器判定进程该被重启（liveness 的语义是"进程自己卡死了吗"）。
 */
class HealthGroupsContextTest {

    /** 与交给 Lead 的 application.yml 片段逐字一致；改这里必须先改片段，反之亦然。 */
    private static final String[] GROUP_PROPS = {
            // probes.enabled 才会注册 livenessState/readinessState 两个状态指示器（Boot 的
            // ProbesCondition：显式 true 或跑在 K8s 上），并给两组挂上 additional-path。
            // 没有它，include 里的状态键会在启动期被判为"不存在的组件"——这正是本测试要拦的错。
            "management.endpoint.health.probes.enabled=true",
            // 必须与下面这行一起合入：Boot 的 DataSourceHealthContributorAutoConfiguration 也会
            // 注册一个映射到键 db 的指示器，两个 bean 同名会被注册表**静默覆盖**（Map.put）。
            // 关掉它，键 db 才确定是本项目的 SELECT 1 探针。
            "management.health.db.enabled=false",
            "management.endpoint.health.group.liveness.include=livenessState,ping",
            "management.endpoint.health.group.readiness.include=readinessState,db,redis,es,qdrant,backend",
    };

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ApplicationAvailabilityAutoConfiguration.class,
                        AvailabilityProbesAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class))
                .withUserConfiguration(HealthCheckConfig.class)
                .withBean(HealthProbe.class, () -> mock(HealthProbe.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                // 真实形态里 DataSource 一定存在（spring-boot-starter-jdbc + spring.datasource.url），
                // 这里显式给一个 mock，才能验证 db.enabled=false 之后键 db 归本项目所有。
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(OpsPilotProperties.class, HealthGroupsContextTest::props)
                .withBean(OpsDeployProperties.class, () -> new OpsDeployProperties(false, false))
                .withPropertyValues(GROUP_PROPS);
    }

    private static OpsPilotProperties props() {
        return new OpsPilotProperties(
                new OpsPilotProperties.DashScope("http://x", "", "em", 1024, "rm", "lm",
                        60, 3000, 15000, "mock"),
                null, null, null, null, null, null, null);
    }

    @Test
    void componentKeysExistForEveryNameTheGroupsReference() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            HealthContributorRegistry registry = ctx.getBean(HealthContributorRegistry.class);
            for (String key : Set.of("db", "redis", "es", "qdrant", "backend")) {
                assertThat(registry.getContributor(key))
                        .as("组件键 " + key + " 必须存在：bean 名要写成 <key>HealthIndicator")
                        .isNotNull();
            }
        });
    }

    /**
     * 键 {@code db} 必须是本项目那条 SELECT 1 探针，而不是 Boot 的通用 DataSource 指示器。
     * 判据用 detail（"SELECT 1 ok"）而不是只看状态：两者在"库通"时都会报 UP，
     * 只有 detail 能区分"到底探的是哪条路径"——而 application.yml 的 db.enabled=false
     * 正是让这件事确定下来的那一行。
     */
    @Test
    void dbComponentIsTheProjectProbeNotBootsGenericIndicator() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ApplicationAvailabilityAutoConfiguration.class,
                        AvailabilityProbesAutoConfiguration.class,
                        HealthContributorAutoConfiguration.class,
                        HealthEndpointAutoConfiguration.class))
                .withUserConfiguration(HealthCheckConfig.class)
                .withBean(HealthProbe.class, () -> mock(HealthProbe.class))
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(OpsPilotProperties.class, HealthGroupsContextTest::props)
                .withBean(OpsDeployProperties.class, () -> new OpsDeployProperties(false, false))
                .withPropertyValues(GROUP_PROPS)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var db = ctx.getBean(HealthContributorRegistry.class).getContributor("db");
                    assertThat(db).isInstanceOf(org.springframework.boot.actuate.health.HealthIndicator.class);
                    var health = ((org.springframework.boot.actuate.health.HealthIndicator) db).health();
                    assertThat(health.getDetails().get("detail")).isEqualTo("SELECT 1 ok");
                });
    }

    @Test
    void readinessCoversExternalDependencies() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            HealthEndpointGroup readiness = ctx.getBean(HealthEndpointGroups.class).get("readiness");
            assertThat(readiness).as("readiness 分组必须存在").isNotNull();
            for (String key : Set.of("readinessState", "db", "redis", "es", "qdrant", "backend")) {
                assertThat(readiness.isMember(key)).as("readiness 应包含 " + key).isTrue();
            }
        });
    }

    @Test
    void livenessCarriesNoExternalDependency() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            HealthEndpointGroup liveness = ctx.getBean(HealthEndpointGroups.class).get("liveness");
            assertThat(liveness).as("liveness 分组必须存在").isNotNull();
            assertThat(liveness.isMember("livenessState")).isTrue();
            assertThat(liveness.isMember("ping")).isTrue();
            for (String key : Set.of("db", "redis", "es", "qdrant", "backend")) {
                assertThat(liveness.isMember(key))
                        .as("liveness 不得包含外部依赖 " + key + "：中间件抖动不该触发进程重启")
                        .isFalse();
            }
        });
    }
}
