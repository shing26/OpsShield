package com.opspilot.store;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * memory 后端：把 {@link KvStore} 的语义落到进程内 Map（ADR-0022 决策 3），零外部依赖。
 *
 * <p><b>它是"另一条链路"而不是降级档</b>（ADR-0022 备选表）：进程重启即清空、无跨实例共享、
 * 不承诺任何持久性。这些**不是缺陷，是定义**——所以它由显式开关进入，不参与可用性叙事。
 *
 * <p><b>刻意保留 Redis 的几处"不优雅"语义</b>，因为它们已经被上层依赖：
 * <ul>
 *   <li>{@code increment} 的 TTL 只在**首次创建**时设，已存在的键不续期（配额日切、登录锁窗口）；</li>
 *   <li>{@code windowRegister} 修剪用**闭区间**（{@code score <= cutoff} 被移除），
 *       且同一成员重复登记不增加条数——两者都与 Redisson 的 {@code removeRangeByScore}/{@code add} 对齐；</li>
 *   <li>{@code mapValues} 不承诺顺序（Redis 也不承诺）。</li>
 * </ul>
 * 任何一处"顺手写得更合理"都会让两个后端在边界上分叉，而分叉在演示里完全看不出来。
 *
 * <p><b>G1-W1.3：TTL 从"每类结构各带一个过期字段"收成一张统一的 {@code expiries}</b>。
 * 起因是 {@link #expire} 要能给**任意类型**的键打 TTL（SOP 的 map/set 键、计数键都要），
 * 而旧实现里 strings/counters 各自带 expiresAt、maps/sets 根本没有过期概念——继续按类型扩展
 * 会让"expire 对哪类键有效"变成一张要背的表。统一成 "key → deadline" 后，所有读写入口先做一次
 * 惰性清理，语义与 Redis（TTL 属于 key，与 value 类型无关）一致。
 *
 * <p>过期是**惰性**的：读/写该键时顺带清理。没有后台清扫线程——演示形态下多一个线程就多一个
 * 需要解释的东西，而键的基数（每租户一份 SOP、每用户一条配额）不足以让它成为问题。
 */
public class MemoryKvStore implements KvStore {

    private final ConcurrentHashMap<String, String> strings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> maps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> sets = new ConcurrentHashMap<>();

    /** 统一过期表：key → 绝对过期时刻（毫秒）。没有条目 = 永不过期。 */
    private final ConcurrentHashMap<String, Long> expiries = new ConcurrentHashMap<>();

    @Override
    public String getString(String key) {
        purgeIfExpired(key);
        return strings.get(key);
    }

    @Override
    public void setString(String key, String value, Duration ttl) {
        strings.put(key, value);
        expiries.put(key, expiry(ttl));
    }

    @Override
    public long deleteByPrefix(String prefix) {
        long n = 0;
        n += countAndRemove(strings, prefix);
        n += countAndRemove(counters, prefix);
        n += countAndRemove(windows, prefix);
        n += countAndRemove(maps, prefix);
        n += countAndRemove(sets, prefix);
        // 过期表本身不是键空间的一部分，只清残留、不计入删除条数（否则同一 key 会被计两次）
        for (String k : expiries.keySet()) {
            if (k.startsWith(prefix)) expiries.remove(k);
        }
        return n;
    }

    @Override
    public long increment(String key, Duration ttlOnCreate) {
        // 用 synchronized 块而不是 compute：要在"创建"与"自增"两个分支里都返回最终值，
        // 而 ConcurrentHashMap 的 compute 函数不允许可靠地向外写局部变量。
        synchronized (counters) {
            purgeIfExpired(key);
            Long cur = counters.get(key);
            if (cur == null) {
                counters.put(key, 1L);
                expiries.put(key, expiry(ttlOnCreate));   // 只在首次创建时设 TTL（与 Redis 对齐）
                return 1L;
            }
            long next = cur + 1;
            counters.put(key, next);
            return next;
        }
    }

    @Override
    public long readLong(String key) {
        purgeIfExpired(key);
        Long v = counters.get(key);
        return v == null ? 0L : v;
    }

    @Override
    public void delete(String key) {
        removeKey(key);
        expiries.remove(key);
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        purgeIfExpired(key);
        if (!containsKey(key)) return false;   // 与 Redis EXPIRE 一致：不凭空创建键
        expiries.put(key, expiry(ttl));
        return true;
    }

    @Override
    public long windowRegister(String key, long nowMs, long windowMs, String member, Duration ttl) {
        purgeIfExpired(key);
        ConcurrentHashMap<String, Long> win = windows.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        long size;
        synchronized (win) {
            long cutoff = nowMs - windowMs;
            win.values().removeIf(score -> score <= cutoff);   // 闭区间，与 removeRangeByScore 对齐
            size = win.size();
            win.put(member, nowMs);                            // 成员唯一：重复登记不增条数
        }
        expiries.put(key, expiry(ttl));   // Redisson 每次登记都 expire，内存侧同口径续期
        return size;
    }

    @Override
    public void mapPut(String key, String field, String value) {
        purgeIfExpired(key);
        maps.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(field, value);
    }

    @Override
    public String mapGet(String key, String field) {
        purgeIfExpired(key);
        Map<String, String> m = maps.get(key);
        return m == null ? null : m.get(field);
    }

    @Override
    public boolean mapIsEmpty(String key) {
        purgeIfExpired(key);
        Map<String, String> m = maps.get(key);
        return m == null || m.isEmpty();
    }

    @Override
    public List<String> mapValues(String key) {
        purgeIfExpired(key);
        Map<String, String> m = maps.get(key);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    @Override
    public void setAdd(String key, String member) {
        purgeIfExpired(key);
        sets.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(member);
    }

    @Override
    public Set<String> setMembers(String key) {
        purgeIfExpired(key);
        Set<String> s = sets.get(key);
        return s == null ? Set.of() : new LinkedHashSet<>(s);
    }

    @Override
    public boolean exists(String key) {
        purgeIfExpired(key);
        return containsKey(key);
    }

    @Override
    public String backend() {
        return "memory";
    }

    private boolean containsKey(String key) {
        return strings.containsKey(key) || counters.containsKey(key) || windows.containsKey(key)
                || maps.containsKey(key) || sets.containsKey(key);
    }

    /** 惰性清理：只在被访问的那个 key 上做，且过期表与各类结构一起清，避免"值没了、TTL 还在"。 */
    private void purgeIfExpired(String key) {
        Long deadline = expiries.get(key);
        if (deadline == null || deadline == Long.MAX_VALUE
                || System.currentTimeMillis() < deadline) {
            return;
        }
        removeKey(key);
        expiries.remove(key, deadline);
    }

    private void removeKey(String key) {
        strings.remove(key);
        counters.remove(key);
        windows.remove(key);
        maps.remove(key);
        sets.remove(key);
    }

    /** 统一换算：负 TTL 视为"立即过期"（宁可不命中，也不留一个永远不过期的键）。 */
    private static long expiry(Duration ttl) {
        long ms = ttl == null ? 0L : ttl.toMillis();
        return ms <= 0 ? System.currentTimeMillis() : System.currentTimeMillis() + ms;
    }

    private static <T> long countAndRemove(ConcurrentHashMap<String, T> m, String prefix) {
        long n = 0;
        for (String k : m.keySet()) {
            if (k.startsWith(prefix) && m.remove(k) != null) n++;
        }
        return n;
    }
}
