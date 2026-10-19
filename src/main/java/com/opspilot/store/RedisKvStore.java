package com.opspilot.store;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

/**
 * live 后端：把 {@link KvStore} 的语义落到 Redisson。**只有存储，没有领域判断**——
 * key 由调用方拼好、TTL 由调用方给、fail-closed 校验留在原服务里（ADR-0022 决策 4）。
 *
 * <p>唯一带语义的一处是 {@link #windowRegister}：它把"修剪 → 计数 → 登记"钉在一个方法里，
 * 因为那是**调用方要的语义**而不是三步 Redis 操作；拆开暴露出去，内存实现就得重新拼一遍，
 * 两边的顺序迟早会漂。行为与重构前的 {@code SlidingWindowService} 逐行等价。
 */
public class RedisKvStore implements KvStore {

    private final RedissonClient redisson;

    public RedisKvStore(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public String getString(String key) {
        RBucket<String> bucket = redisson.getBucket(key);
        return bucket.get();
    }

    @Override
    public void setString(String key, String value, Duration ttl) {
        redisson.<String>getBucket(key).set(value, ttl);
    }

    @Override
    public long deleteByPrefix(String prefix) {
        return redisson.getKeys().deleteByPattern(prefix);
    }

    @Override
    public long increment(String key, Duration ttlOnCreate) {
        RAtomicLong counter = redisson.getAtomicLong(key);
        long n = counter.incrementAndGet();
        if (n == 1) counter.expire(ttlOnCreate);
        return n;
    }

    @Override
    public long readLong(String key) {
        return redisson.getAtomicLong(key).get();
    }

    @Override
    public void delete(String key) {
        redisson.getKeys().delete(key);
    }

    @Override
    public long windowRegister(String key, long nowMs, long windowMs, String member, Duration ttl) {
        RScoredSortedSet<String> zset = redisson.getScoredSortedSet(key);
        // 清理窗口外成员（本源窗口；不再误删别源成员——key 已掺 source）
        zset.removeRangeByScore(0, true, nowMs - windowMs, true);
        long size = zset.size();
        zset.add(nowMs, member);
        zset.expire(ttl);
        return size;
    }

    @Override
    public void mapPut(String key, String field, String value) {
        redisson.<String, String>getMap(key).put(field, value);
    }

    @Override
    public String mapGet(String key, String field) {
        RMap<String, String> map = redisson.getMap(key);
        return map.get(field);
    }

    @Override
    public boolean mapIsEmpty(String key) {
        return redisson.<String, String>getMap(key).isEmpty();
    }

    @Override
    public List<String> mapValues(String key) {
        RMap<String, String> map = redisson.getMap(key);
        List<String> out = new ArrayList<>();
        for (String v : map.readAllValues()) {
            if (v != null) out.add(String.valueOf(v));
        }
        return out;
    }

    @Override
    public void setAdd(String key, String member) {
        redisson.<String>getSet(key).add(member);
    }

    @Override
    public Set<String> setMembers(String key) {
        RSet<String> set = redisson.getSet(key);
        return new LinkedHashSet<>(set.readAll());
    }

    @Override
    public boolean exists(String key) {
        return redisson.<String>getBucket(key).isExists();
    }

    @Override
    public String backend() {
        return "redis";
    }
}
