package com.opspilot.store;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * live 后端的 TTL 接缝（G1-W1.3）：{@code expire} 必须落到 Redis 的过期命令上，
 * 且用**毫秒精度**——秒级精度会让短 TTL 用例与内存后端产生可观测差异。
 *
 * <p>同时锁一条安全语义：非正 TTL 不能变成"永不过期"（Redis EXPIRE 0 的实际语义是立即删除，
 * 而在这个接缝里"想删就删"必须显式），否则 SOP 键会在一次配置错误后永久驻留。
 */
class RedisKvStoreTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RKeys keys = mock(RKeys.class);
    private final RedisKvStore kv = new RedisKvStore(redisson);

    @Test
    void expireDelegatesWithMillisPrecision() {
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.expire("sop:t1:steps", Duration.ofDays(7).toMillis(), TimeUnit.MILLISECONDS))
                .thenReturn(true);

        assertTrue(kv.expire("sop:t1:steps", Duration.ofDays(7)));
        verify(keys).expire("sop:t1:steps", 604_800_000L, TimeUnit.MILLISECONDS);
    }

    @Test
    void missingKeyReportsFalseWithoutCreatingIt() {
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.expire("nope", 60_000L, TimeUnit.MILLISECONDS)).thenReturn(false);

        assertFalse(kv.expire("nope", Duration.ofMinutes(1)));
    }

    @Test
    void nonPositiveTtlMeansDeleteNotNeverExpire() {
        when(redisson.getKeys()).thenReturn(keys);
        when(keys.delete("k")).thenReturn(1L);

        assertTrue(kv.expire("k", Duration.ZERO), "0 TTL 等价于立即删除（返回是否删到了键）");
        verify(keys).delete("k");

        when(keys.delete("gone")).thenReturn(0L);
        assertFalse(kv.expire("gone", Duration.ofSeconds(-1)));
    }
}
