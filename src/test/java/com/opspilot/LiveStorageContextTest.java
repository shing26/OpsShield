package com.opspilot;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.opspilot.action.ActionOpsService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **live 存储形态的启动回归锁**（P0，2026-10-26）。
 *
 * <p>此前的缺陷：{@code ActionOpsService} 强依赖只在 memory 注册的 {@code InMemoryIndex}，
 * 于是 {@code opspilot.storage.mode=live}（同时也是 {@code application.yml} 的默认值、
 * 以及 {@code DeploymentGuard} strict 强制要求的形态）启动即失败——而仓里仅有的两个
 * {@code @SpringBootTest} 都钉着 memory，CI 全绿。本用例补上「应用在 live 形态能否起来」这一格。
 *
 * <p><b>只 mock 唯一会急连的客户端</b>：{@code StoreConfig.redissonClient()} 会
 * {@code Redisson.create(...)} 直接连 Redis（ADR-0022 决策 2 的刻意设计）；ES/Qdrant 的
 * RestClient/QdrantClient 都是懒连接，指向不可达地址也不会在启动期失败。所以这里用
 * {@link MockitoBean} 换掉 Redisson，其余装配全部走真实代码路径——包括 ActionOpsService、
 * IngestionRunner、SopWarmupRunner 等启动期参与者。
 *
 * <p>不启动 web 端口（默认 MOCK），不触网：ES 指向 127.0.0.1:1，首灌探测会失败并被
 * {@code IngestionRunner} 的 catch 吞掉（其设计就是「ES 不可达时不擅自重灌」）。
 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=live",
        "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "opspilot.es.uri=http://127.0.0.1:1",
        "opspilot.qdrant.host=127.0.0.1",
        "opspilot.qdrant.grpc-port=1",
        "opspilot.cache.l2-cleanup-initial-delay-ms=86400000",
        "spring.datasource.url=jdbc:h2:mem:livestorage;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.clean-disabled=true"})
class LiveStorageContextTest {

    /** 唯一被替换的 bean：它在 live 形态下会急连 Redis（不可达即 10s 超时后抛）。 */
    @MockitoBean
    RedissonClient redissonClient;

    @Autowired
    ApplicationContext ctx;

    @Autowired
    ActionOpsService actionOpsService;

    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "livestorage".repeat(4));   // 44 chars >= 256bit
    }

    @Test
    void applicationContextStartsWithLiveStorage() {
        assertThat(actionOpsService).isNotNull();
        // 这个 bean 只在 memory 注册；它能缺席，正是本用例要证明的事实
        assertThat(ctx.containsBean("inMemoryIndex")).isFalse();
        assertThat(ctx.containsBean("redisKvStore")).isTrue();
    }
}
