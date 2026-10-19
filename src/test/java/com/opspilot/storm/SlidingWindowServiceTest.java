package com.opspilot.storm;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.KvStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成质量包 Q4 锁：滑动窗口 = `来源×指纹` 的聚合计数窗口（非穿透闸门）。
 * key 必须掺 source——否则 manual(30s) 的修剪会删掉 alert(60s) 窗口内成员，两边计数都打偏。
 * 纯 mock（零 Redis 依赖，CI 可跑）；混源真实行为在 V6 live 验收用 Redis 直读佐证。
 *
 * <p>2026-10-19（ADR-0022）：断言面从"ZSET 的三步操作"上移到 {@link KvStore#windowRegister}
 * 的**入参**。这不是把断言放松了——key 掺 source、alert 窗口更宽、TTL=窗口+10s 三条判据一条没少，
 * 只是它们现在钉在接缝上，于是对两个后端同时有效。原先钉在 Redisson 调用序列上的写法，
 * 在内存后端下根本无法表达。
 */
class SlidingWindowServiceTest {

    private final KvStore kv = mock(KvStore.class);
    private final SlidingWindowService svc = new SlidingWindowService(kv,
            new OpsPilotProperties(null, null, null, null, null,
                    new OpsPilotProperties.Storm(30, 60), null, null));

    @Test
    void windowKeyIsScopedBySource() {
        when(kv.windowRegister(anyString(), anyLong(), anyLong(), anyString(), any(Duration.class)))
                .thenReturn(0L);

        svc.tryAcquire("fp1", "alert");
        svc.tryAcquire("fp1", "manual");

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(kv, times(2)).windowRegister(keys.capture(), anyLong(), anyLong(), anyString(),
                any(Duration.class));
        assertEquals("storm:win:alert:fp1", keys.getAllValues().get(0));
        assertEquals("storm:win:manual:fp1", keys.getAllValues().get(1));
        assertNotEquals(keys.getAllValues().get(0), keys.getAllValues().get(1),
                "两源共用一个 ZSET 则短窗口修剪污染长窗口计数（Q4 根因）");
    }

    @Test
    void alertPrunesLessAndTtlLongerThanManual() {
        when(kv.windowRegister(anyString(), anyLong(), anyLong(), anyString(), any(Duration.class)))
                .thenReturn(0L);

        svc.tryAcquire("fp1", "alert");
        svc.tryAcquire("fp1", "manual");

        ArgumentCaptor<Long> window = ArgumentCaptor.forClass(Long.class);
        verify(kv, times(2)).windowRegister(anyString(), anyLong(), window.capture(), anyString(),
                any(Duration.class));
        assertEquals(60_000L, window.getAllValues().get(0), "alert 窗口 60s");
        assertEquals(30_000L, window.getAllValues().get(1), "manual 窗口 30s");
        assertTrue(window.getAllValues().get(0) > window.getAllValues().get(1),
                "alert 窗口更宽 ⇒ 修剪区间终点更早 ⇒ 删得更少（Q4 根因）");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(kv, times(2)).windowRegister(anyString(), anyLong(), anyLong(), anyString(),
                ttl.capture());
        assertEquals(70, ttl.getAllValues().get(0).toSeconds(), "alert TTL=窗口+10s");
        assertEquals(40, ttl.getAllValues().get(1).toSeconds(), "manual TTL=窗口+10s");
    }

    /** first 仅是计数起点归属（喂 dedup_aggregated 指标），不承担"穿透/等待"语义。 */
    @Test
    void firstReflectsCountStartOnly() {
        when(kv.windowRegister(anyString(), anyLong(), anyLong(), anyString(), any(Duration.class)))
                .thenReturn(0L, 5L);

        assertTrue(svc.tryAcquire("fp1", "alert").first());
        SlidingWindow.WindowResult again = svc.tryAcquire("fp1", "alert");
        assertFalse(again.first());
        assertEquals(5, again.aggregated());
        // 两次请求都照常登记（登记与"是不是首条"是两件事）
        verify(kv, times(2)).windowRegister(anyString(), anyLong(), anyLong(), anyString(),
                any(Duration.class));
    }
}
