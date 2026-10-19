package com.opspilot.store;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * memory 后端的语义回归锁（ADR-0022）。这些用例守的不是"Map 能用"，而是
 * **内存实现刻意保留的几处 Redis 语义**——它们看着像实现细节，实际都已被上层依赖：
 *
 * <ul>
 *   <li>{@code increment} 的 TTL 只在首次创建时设（配额日切、登录锁窗口靠它）；</li>
 *   <li>{@code windowRegister} 修剪用闭区间、同成员重复登记不增条数（dedup 叙事靠它）；</li>
 *   <li>{@code mapValues} 不承诺顺序（Redis 也不承诺）——用例只断言集合相等。</li>
 * </ul>
 *
 * 任何一条被"顺手写得更合理"，两个后端就会在边界上分叉，而分叉在演示里完全看不出来。
 *
 * <p>TTL 用例用 50–80ms 的真实等待：时钟是这里唯一无法 mock 的输入，而把 TTL 抽成
 * 可注入时钟会为三条用例给生产代码加一个接口——不值得。等待上限 120ms，不影响 CI 时长。
 */
class MemoryKvStoreTest {

    private final MemoryKvStore kv = new MemoryKvStore();

    @Test
    void stringsHonourTtl() throws Exception {
        kv.setString("k", "v", Duration.ofMillis(50));
        assertEquals("v", kv.getString("k"));
        Thread.sleep(80);
        assertNull(kv.getString("k"), "过期后必须读不到（惰性清理也算清理）");
    }

    @Test
    void incrementCountsAndReadDoesNotCreate() {
        assertEquals(0L, kv.readLong("n"), "只读不得凭空创建键");
        assertEquals(1L, kv.increment("n", Duration.ofMinutes(1)));
        assertEquals(2L, kv.increment("n", Duration.ofMinutes(1)));
        assertEquals(2L, kv.readLong("n"));
    }

    /** 关键语义：**已存在的键不因再次自增而续期**——否则失败计数会被持续尝试"喂活"。 */
    @Test
    void incrementTtlIsSetOnCreateOnly() throws Exception {
        kv.increment("n", Duration.ofMillis(60));
        Thread.sleep(30);
        kv.increment("n", Duration.ofMillis(60));   // 若这里续期，下面的断言会读到 2
        Thread.sleep(40);                            // 距首次创建 70ms > 60ms
        assertEquals(0L, kv.readLong("n"), "TTL 被续期了：登录锁窗口会永远不过期");
    }

    @Test
    void windowPrunesClosedIntervalAndKeepsMembersUnique() {
        // 窗口 1000ms。t=0 登记 "a"；t=1500 时 cutoff=500，"a"(score 0) 落在闭区间内被删
        assertEquals(0L, kv.windowRegister("w", 0, 1000, "a", Duration.ofSeconds(10)));
        assertEquals(0L, kv.windowRegister("w", 1500, 1000, "b", Duration.ofSeconds(10)));
        // 同一成员重复登记：条数不增（ZSET 成员唯一），返回值仍是"此前有几条"
        assertEquals(1L, kv.windowRegister("w", 1600, 1000, "b", Duration.ofSeconds(10)));
        assertEquals(1L, kv.windowRegister("w", 1700, 1000, "c", Duration.ofSeconds(10)));
    }

    @Test
    void windowCutoffIsInclusive() {
        // t=1000 登记；窗口 500 ⇒ 在 t=1500 时 cutoff=1000，闭区间把它一起删掉（与 Redisson 一致）
        kv.windowRegister("w", 1000, 500, "a", Duration.ofSeconds(10));
        assertEquals(0L, kv.windowRegister("w", 1500, 500, "b", Duration.ofSeconds(10)),
                "闭区间边界上的成员应被修剪（Redisson removeRangeByScore 的 to 是含的）");
    }

    @Test
    void deleteByPrefixCountsAndRemovesAcrossTypes() {
        kv.setString("cache:l1:t:3:fp", "{}", Duration.ofMinutes(1));
        kv.increment("cache:l1:other", Duration.ofMinutes(1));
        kv.mapPut("cache:l1:m", "f", "v");
        kv.setString("keep:me", "x", Duration.ofMinutes(1));

        assertEquals(3L, kv.deleteByPrefix("cache:l1:"));
        assertNull(kv.getString("cache:l1:t:3:fp"));
        assertEquals(0L, kv.readLong("cache:l1:other"));
        assertTrue(kv.mapIsEmpty("cache:l1:m"));
        assertEquals("x", kv.getString("keep:me"), "前缀之外的一个都不许动");
    }

    @Test
    void sopMapAndSetOps() {
        assertTrue(kv.mapIsEmpty("sop:t1:steps"), "空表 ⇒ L2 兜底直出 null");
        kv.mapPut("sop:t1:steps", "rb-1", "步骤一");
        kv.mapPut("sop:t1:steps", "rb-2", "步骤二");
        assertFalse(kv.mapIsEmpty("sop:t1:steps"));
        assertEquals("步骤一", kv.mapGet("sop:t1:steps", "rb-1"));
        assertNull(kv.mapGet("sop:t1:steps", "不存在"));
        assertEquals(Set.of("步骤一", "步骤二"), Set.copyOf(kv.mapValues("sop:t1:steps")));

        kv.setAdd("sop:t1:code:50012_DB_TIMEOUT", "rb-1");
        kv.setAdd("sop:t1:code:50012_DB_TIMEOUT", "rb-1");   // 集合成员唯一
        kv.setAdd("sop:t1:code:50012_DB_TIMEOUT", "rb-2");
        assertEquals(Set.of("rb-1", "rb-2"), kv.setMembers("sop:t1:code:50012_DB_TIMEOUT"));
        assertEquals(Set.of(), kv.setMembers("sop:t1:code:没这个码"));
    }

    @Test
    void deleteRemovesEveryTypeUnderTheKey() {
        kv.setString("k", "v", Duration.ofMinutes(1));
        kv.increment("k", Duration.ofMinutes(1));
        kv.delete("k");
        assertNull(kv.getString("k"));
        assertEquals(0L, kv.readLong("k"));
    }

    @Test
    void backendSelfReports() {
        assertEquals("memory", kv.backend(),
                "后端自报是启动摘要/健康面/页面标注的唯一来源，不许含糊");
    }
}
