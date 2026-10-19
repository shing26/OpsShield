package com.opspilot.store;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import com.opspilot.config.OpsPilotProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 存储装配的回归锁（ADR-0022 决策 1/2）。
 *
 * <p>最要紧的一条是 **memory 模式下 {@code RedissonClient} bean 根本不存在**：这是
 * "零依赖"这句话唯一可执行的形式。若哪天有人把 starter 的自动装配放回来，它会照样建客户端并
 * 急连 Redis——于是零依赖模式反过来依赖 Redis，而这个回归在演示里完全看不出来
 * （它只在"电脑上什么都没装"时炸，而那恰恰是这个模式唯一的使用场景）。
 *
 * <p>live 侧不拉真连接：装配函数直接调，Redis 不可达时那条"可执行的下一步"单独断言——
 * 真连一次要等 10s socket 超时，而判据本身与超时无关。
 */
class StoreWiringTest {

    @Configuration
    @EnableConfigurationProperties(OpsPilotProperties.class)
    @Import(StoreConfig.class)
    static class Cfg {
    }

    @Test
    void memoryModeWiresMemoryStoreAndNoRedisClient() {
        new ApplicationContextRunner()
                .withUserConfiguration(Cfg.class)
                .withPropertyValues("opspilot.storage.mode=memory")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(KvStore.class)).isInstanceOf(MemoryKvStore.class);
                    assertThat(ctx).doesNotHaveBean(RedissonClient.class);
                });
    }

    @Test
    void liveStoreWrapsTheRedisClient() {
        assertThat(new StoreConfig().redisKvStore(mock(RedissonClient.class)))
                .isInstanceOf(RedisKvStore.class)
                .extracting(KvStore::backend).isEqualTo("redis");
    }

    @Test
    void memoryStoreSelfReportsMemory() {
        assertThat(new StoreConfig().memoryKvStore().backend()).isEqualTo("memory");
    }

    /**
     * 决策 2 的全部内容：探不到中间件时**不静默切换**，但必须把可执行的下一步说出来。
     * 只说"Redis 不可达"等于让人自己去翻文档找那个开关。
     */
    @Test
    void redisFailureCarriesAnActionableHint() {
        RuntimeException wrapped = StoreConfig.unreachable("localhost", 6379,
                new RuntimeException("connection refused"));
        assertThat(wrapped).isInstanceOf(IllegalStateException.class);
        assertThat(wrapped.getMessage())
                .contains("storage.mode=live")
                .contains("localhost:6379")
                .contains("connection refused")
                .contains("--opspilot.storage.mode=memory");
        assertThat(wrapped.getCause()).hasMessage("connection refused");
    }

    @Test
    void describeLabelsBothBackends() {
        assertThat(StoreConfig.describe(props("memory"))).contains("memory");
        assertThat(StoreConfig.describe(props("live"))).contains("live");
    }

    private static OpsPilotProperties props(String mode) {
        return new OpsPilotProperties(null, new OpsPilotProperties.Storage(mode), null, null, null,
                null, null, null, null);
    }
}
