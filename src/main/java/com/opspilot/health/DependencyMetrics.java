package com.opspilot.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 依赖健康指标（G1，2026-10-22）：把 /actuator/health 的 redis/es/qdrant 与磁盘余量
 * 变成 Prometheus 可告警的 gauge。
 *
 * <p>为什么不能只靠 /actuator/health：Prometheus 抓的是 /actuator/prometheus，健康端点的
 * DOWN 不会自动变成时间序列；没有 gauge 就没有 OpsPilotRedisDown / 磁盘低余量规则，
 * "Redis 挂了"只能等业务报错或人看面板。G0 审查要求三个告警场景（网关 down / Redis down /
 * 磁盘满），前两个里 Redis 一直没有生产者，本条就是补这个静默缺口。
 *
 * <p>探测复用 {@link HealthProbe} 的 3s 超时与内存后端语义；15s 一次，避免把健康探测
 * 变成对 Redis/ES/Qdrant 的持续压测。磁盘取 data 所在文件系统；取不到时保持上次值，
 * 不拿 0 冒充"磁盘已满"制造假告警。
 */
@Component
public class DependencyMetrics {

    private static final Logger log = LoggerFactory.getLogger(DependencyMetrics.class);
    private static final Path DATA = Path.of("data");

    private final HealthProbe probe;
    private final MeterRegistry registry;
    private final AtomicInteger redisUp = new AtomicInteger(1);
    private final AtomicInteger esUp = new AtomicInteger(1);
    private final AtomicInteger qdrantUp = new AtomicInteger(1);
    private final AtomicLong diskFree = new AtomicLong(Long.MAX_VALUE);

    public DependencyMetrics(HealthProbe probe, MeterRegistry registry) {
        this.probe = probe;
        this.registry = registry;
    }

    @PostConstruct
    void register() {
        Gauge.builder("aiops.dependency.up", redisUp, AtomicInteger::get)
                .description("依赖健康（1=UP，0=DOWN；来自 HealthProbe）")
                .tag("dependency", "redis").register(registry);
        Gauge.builder("aiops.dependency.up", esUp, AtomicInteger::get)
                .description("依赖健康（1=UP，0=DOWN；来自 HealthProbe）")
                .tag("dependency", "es").register(registry);
        Gauge.builder("aiops.dependency.up", qdrantUp, AtomicInteger::get)
                .description("依赖健康（1=UP，0=DOWN；来自 HealthProbe）")
                .tag("dependency", "qdrant").register(registry);
        Gauge.builder("aiops.disk.free.bytes", diskFree, AtomicLong::get)
                .description("data 所在文件系统可用字节（取不到时保持上次值）")
                .tag("path", "data").register(registry);
        refresh();
    }

    /** 15s 一次；@EnableScheduling 已在 OpsPilotApplication 上开启。 */
    @Scheduled(fixedDelay = 15_000, initialDelay = 5_000)
    public void refresh() {
        redisUp.set(up(probe.redis()));
        esUp.set(up(probe.es()));
        qdrantUp.set(up(probe.qdrant()));
        Long free = freeBytes();
        if (free != null) diskFree.set(free);
    }

    private static int up(HealthProbe.Comp c) {
        return c != null && "UP".equals(c.status()) ? 1 : 0;
    }

    private Long freeBytes() {
        try {
            Path path = Files.exists(DATA) ? DATA : Path.of(".");
            FileStore fs = Files.getFileStore(path);
            return fs.getUsableSpace();
        } catch (Exception e) {
            log.debug("disk free probe failed: {}", e.toString());
            return null;
        }
    }
}
