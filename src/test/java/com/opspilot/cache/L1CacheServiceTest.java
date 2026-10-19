package com.opspilot.cache;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.KvStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * L1 键口径回归锁（2026-10-17 拆身份）：key 必须由调用方给出的**指纹**原样构成，
 * 不再自己哈希 query 文本。
 *
 * <p>2026-10-19（ADR-0022）：底层从 {@code RedissonClient} 换成 {@link KvStore} 接缝——
 * 键口径的断言一字未改，这正是接缝该有的效果（换存储介质不动领域决定）。
 *
 * <p>变异验证：把 {@link L1CacheService#key} 改回对入参再哈希一次 → 本类第一条必红；
 * 删掉 tenant/authLevel 组分 → 第四条必红。跨租户/跨密级隔离的真正执行点在这里，
 * 上游 SingleFlight 组键与本键同构（P0-1）。
 */
class L1CacheServiceTest {

    private static final String FP = "c817f438192dbb63f17859b36e149f81";

    private static OpsPilotProperties props(int l1TtlHours) {
        return new OpsPilotProperties(null, null, null, null,
                new OpsPilotProperties.Cache(l1TtlHours, 0.95), null, null, null);
    }

    private static L1CacheService svc(KvStore store, int ttlHours) {
        return new L1CacheService(store, props(ttlHours));
    }

    @Test
    void keyEmbedsFingerprintVerbatimWithoutASecondHash() {
        assertEquals("cache:l1:tenant-a:3:" + FP, svc(mock(KvStore.class), 2).key("tenant-a", 3, FP),
                "指纹必须原样进键：再哈希一次会让编排层算好的身份对不上缓存");
    }

    @Test
    void differentFingerprintsYieldDifferentKeys() {
        L1CacheService l1 = svc(mock(KvStore.class), 2);
        assertNotEquals(l1.key("tenant-a", 3, FP),
                l1.key("tenant-a", 3, "1f28342938dc58f66b9fa8743482b126"));
    }

    @Test
    void tenantAndAuthLevelStillSeparateTheKeySpace() {
        L1CacheService l1 = svc(mock(KvStore.class), 2);
        assertNotEquals(l1.key("tenant-a", 3, FP), l1.key("tenant-b", 3, FP), "跨租户不得共用条目");
        assertNotEquals(l1.key("tenant-a", 3, FP), l1.key("tenant-a", 1, FP), "跨密级不得共用条目");
    }

    @Test
    void blankTenantFailsFast() {
        L1CacheService l1 = svc(mock(KvStore.class), 2);
        assertThrows(IllegalStateException.class, () -> l1.key(null, 3, FP));
        assertThrows(IllegalStateException.class, () -> l1.key("  ", 3, FP));
    }

    @Test
    void putUsesFingerprintKeyAndConfiguredTtl() {
        KvStore store = mock(KvStore.class);
        svc(store, 2).put("tenant-a", 3, FP, "{}");
        verify(store).setString(eq("cache:l1:tenant-a:3:" + FP), eq("{}"), eq(Duration.ofHours(2)));
    }

    /** flush 走**前缀**删：Redis 侧原先写的是 glob（{@code cache:l1:*}），接缝统一成前缀语义。 */
    @Test
    void flushDeletesByPrefix() {
        KvStore store = mock(KvStore.class);
        svc(store, 2).flush();
        verify(store).deleteByPrefix("cache:l1:");
    }
}
