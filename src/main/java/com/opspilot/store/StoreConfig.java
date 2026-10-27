package com.opspilot.store;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.opspilot.config.OpsPilotProperties;

/**
 * 存储装配（ADR-0022 决策 1/2）：按 {@code opspilot.storage.mode} 决定 {@link KvStore} 与
 * {@link RedissonClient} 的存亡。
 *
 * <p><b>为什么 Redisson 的自动装配被恒排除</b>（见 {@code application.yml}）：它会在 memory 模式
 * 下照样建客户端并**急连** Redis，于是"零依赖模式"反过来依赖 Redis——这正是本 ADR 要消掉的门槛。
 * 恒排除 + 显式构造之后，两件事同时成立：memory 模式下根本不存在 Redis 连接路径；live 模式下
 * 连接参数的出处从"starter 的魔法约定"变成这份可读的代码。
 *
 * <p><b>失败信息必须可执行</b>（决策 2）：live 模式探不到 Redis 时**不静默切换**，而是把
 * "如需零依赖体验，加 --opspilot.storage.mode=memory" 一起抛出去。自动回退会让同一台机器
 * 两次启动跑在不同后端，而页面与审计里的数字长得一模一样——那正是假绿。
 */
@Configuration
public class StoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StoreConfig.class);

    static final String MEMORY_HINT =
            "；如需零依赖体验，加 --opspilot.storage.mode=memory（内存实现，不承诺检索质量）";

    @Bean
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "memory")
    public KvStore memoryKvStore() {
        return new MemoryKvStore();
    }

    /**
     * live 后端。{@code matchIfMissing=true} 是刻意的：缺配置项时的行为必须与历史一致
     * （默认 live，缺中间件即启动失败），不能因为引入了开关就默认放宽。
     */
    @Bean
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "live", matchIfMissing = true)
    public KvStore redisKvStore(RedissonClient redisson) {
        return new RedisKvStore(redisson);
    }

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(name = "opspilot.storage.mode", havingValue = "live", matchIfMissing = true)
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password) {
        Config cfg = new Config();
        // Redisson 4.8：per-connection 的 setPassword 已弃用（启动打印 "password setting is deprecated"），
        // 统一改到 Config 级设置；语义等价（单连接形态下两者都作用于唯一 server 配置）。
        cfg.setPassword(password == null || password.isBlank() ? null : password);
        cfg.useSingleServer().setAddress("redis://" + host + ":" + port);
        try {
            RedissonClient client = Redisson.create(cfg);
            log.info("storage=live：Redis 已连接 {}:{}", host, port);
            return client;
        } catch (RuntimeException e) {
            // 决策 2：不静默切换，只把可执行的下一步说清楚，然后照常失败
            throw unreachable(host, port, e);
        }
    }

    /**
     * 连接失败的包装。抽成静态方法是为了**可断言**：真实调用要等 Redisson 的 socket 超时
     * （默认 10s），把它做成用例等于给 CI 加 10 秒；而这条判据本身（失败信息里必须带
     * 那条可执行的下一步）恰恰是最该被钉住的东西——ADR-0022 决策 2 的全部内容就是这句话。
     */
    static RuntimeException unreachable(String host, int port, RuntimeException cause) {
        return new IllegalStateException(
                "storage.mode=live 但 Redis 不可达（" + host + ":" + port + "）："
                        + cause.getMessage() + MEMORY_HINT, cause);
    }

    /** 启动摘要与健康面共用的一行标注：本次跑的是哪条链路。 */
    public static String describe(OpsPilotProperties props) {
        return props.storage().inMemory() ? "memory(in-memory)" : "live(redis+es+qdrant)";
    }
}
