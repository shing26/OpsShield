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
 * <p>过期是**惰性**的：读/写该键时顺带清理。没有后台清扫线程——演示形态下多一个线程就多一个
 * 需要解释的东西，而键的基数（每租户一份 SOP、每用户一条配额）不足以让它成为问题。
 */
public class MemoryKvStore implements KvStore {

    private record Entry(String value, long expiresAtMs) {}

    private final ConcurrentHashMap<String, Entry> strings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> counters = new ConcurrentHashMap<>(); // [value, expiresAtMs]
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> maps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> sets = new ConcurrentHashMap<>();

    @Override
    public String getString(String key) {
        Entry e = strings.get(key);
        if (e == null) return null;
        if (expired(e.expiresAtMs())) { strings.remove(key, e); return null; }
        return e.value();
    }

    @Override
    public void setString(String key, String value, Duration ttl) {
        strings.put(key, new Entry(value, expiry(ttl)));
    }

    @Override
    public long deleteByPrefix(String prefix) {
        long n = 0;
        n += countAndRemove(strings, prefix);
        n += countAndRemove(counters, prefix);
        n += countAndRemove(windows, prefix);
        n += countAndRemove(maps, prefix);
        n += countAndRemove(sets, prefix);
        return n;
    }

    @Override
    public long increment(String key, Duration ttlOnCreate) {
        // 用 synchronized 块而不是 compute：要在"创建"与"自增"两个分支里都返回最终值，
        // 而 ConcurrentHashMap 的 compute 函数不允许可靠地向外写局部变量。
        synchronized (counters) {
            long[] cur = counters.get(key);
            if (cur == null || expired(cur[1])) {
                long[] fresh = {1L, expiry(ttlOnCreate)};
                counters.put(key, fresh);
                return 1L;
            }
            cur[0] = cur[0] + 1;
            return cur[0];
        }
    }

    @Override
    public long readLong(String key) {
        long[] cur = counters.get(key);
        if (cur == null) return 0L;
        if (expired(cur[1])) { counters.remove(key, cur); return 0L; }
        return cur[0];
    }

    @Override
    public void delete(String key) {
        strings.remove(key);
        counters.remove(key);
        windows.remove(key);
        maps.remove(key);
        sets.remove(key);
    }

    @Override
    public long windowRegister(String key, long nowMs, long windowMs, String member, Duration ttl) {
        ConcurrentHashMap<String, Long> win = windows.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        synchronized (win) {
            long cutoff = nowMs - windowMs;
            win.values().removeIf(score -> score <= cutoff);   // 闭区间，与 removeRangeByScore 对齐
            long size = win.size();
            win.put(member, nowMs);                            // 成员唯一：重复登记不增条数
            return size;
        }
    }

    @Override
    public void mapPut(String key, String field, String value) {
        maps.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(field, value);
    }

    @Override
    public String mapGet(String key, String field) {
        Map<String, String> m = maps.get(key);
        return m == null ? null : m.get(field);
    }

    @Override
    public boolean mapIsEmpty(String key) {
        Map<String, String> m = maps.get(key);
        return m == null || m.isEmpty();
    }

    @Override
    public List<String> mapValues(String key) {
        Map<String, String> m = maps.get(key);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    @Override
    public void setAdd(String key, String member) {
        sets.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(member);
    }

    @Override
    public Set<String> setMembers(String key) {
        Set<String> s = sets.get(key);
        return s == null ? Set.of() : new LinkedHashSet<>(s);
    }

    @Override
    public boolean exists(String key) {
        Entry e = strings.get(key);
        if (e != null && !expired(e.expiresAtMs())) return true;
        if (e != null) strings.remove(key, e);
        long[] c = counters.get(key);
        if (c != null && !expired(c[1])) return true;
        return windows.containsKey(key) || maps.containsKey(key) || sets.containsKey(key);
    }

    @Override
    public String backend() {
        return "memory";
    }

    private static boolean expired(long expiresAtMs) {
        return expiresAtMs != Long.MAX_VALUE && System.currentTimeMillis() >= expiresAtMs;
    }

    private static long expiry(Duration ttl) {
        return System.currentTimeMillis() + ttl.toMillis();
    }

    private static <T> long countAndRemove(ConcurrentHashMap<String, T> m, String prefix) {
        long n = 0;
        for (String k : m.keySet()) {
            if (k.startsWith(prefix) && m.remove(k) != null) n++;
        }
        return n;
    }
}
