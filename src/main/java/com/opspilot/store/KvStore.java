package com.opspilot.store;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 键值存储接缝（ADR-0022 决策 3/4）：Redis 与内存两种后端，**同一套调用面**。
 *
 * <p><b>为什么是窄接口而不是"给 RedissonClient 套一层"</b>：套一层会把 RBucket/RScoredSortedSet/
 * RMap 这些类型一路泄漏到 7 个消费方，内存实现就得假装自己是个 Redis。这里只保留**调用方真正
 * 需要的语义**，于是"内存模式与 live 模式行为一致"这件事变成可读的：两个实现各自把同一份语义
 * 落到不同介质上，而 key 形状、TTL 取值、fail-closed 校验这些**领域决定全部留在原服务里**
 * （决策 4：内存实现不得另写一份权限/策略判定）。
 *
 * <p><b>方法命名刻意用领域词而非 Redis 词</b>：{@code windowRegister} 而不是 {@code zadd}——
 * 上层要的是"登记一次窗口内计数并告诉我此前有几条"，不是"往有序集合里加一个成员"。
 *
 * <p>TTL 一律由调用方给：接缝不替上层猜"这个键该活多久"。
 */
public interface KvStore {

    /** 取字符串；不存在或已过期返回 null。 */
    String getString(String key);

    /** 写字符串并设 TTL（L1 缓存）。 */
    void setString(String key, String value, Duration ttl);

    /** 按前缀删，返回删除条数（L1 flush；Redis 侧是 deleteByPattern）。 */
    long deleteByPrefix(String prefix);

    /**
     * 自增计数并返回自增后的值；**首次创建**时设 TTL（配额水位、登录失败计数）。
     * 与 Redis 版同语义：已存在的键不因再次自增而续期。
     */
    long increment(String key, Duration ttlOnCreate);

    /** 只读计数；不存在返回 0（**读不得消耗/创建配额**）。 */
    long readLong(String key);

    /** 删单键（登录成功后清零失败计数）。 */
    void delete(String key);

    /**
     * 给已存在的键设置/覆盖 TTL（G1-W1.3：SOP 清单键需要过期，避免语料删除后旧清单永久驻留）。
     *
     * <p><b>为什么是"给已有键打 TTL"而不是在 mapPut/setAdd 上加 ttl 参数</b>：本接缝的既有纪律是
     * "TTL 一律由调用方显式给"，而 SOP 预热写的是复合键（steps 一张表 + 每错误码/服务名若干集合），
     * 只有调用方知道哪些键属于同一份清单、该用同一个过期时间。也不让 mapPut 多一个"可空 TTL"参数——
     * 那会让"这个键到底会不会过期"取决于调用点写没写参数，而 Redis 的 {@code volatile-lru} 策略下
     * 无 TTL 的键是**永不淘汰**的，这种分叉一旦出现就是 OOM。
     *
     * <p>语义对齐 Redis 的 {@code EXPIRE}：键不存在返回 false 且不创建；返回 true 表示 TTL 已生效。
     */
    boolean expire(String key, Duration ttl);

    /**
     * 窗口计数：先修剪窗口外成员，再返回**加入本条之前**的窗口内条数，最后把本条登记进去。
     *
     * <p>三步顺序与返回值都是语义的一部分，不是实现细节——{@code SlidingWindowService} 用
     * "此前条数为 0"判定本窗口首条（dedup 叙事），而 Redis 版正是
     * {@code removeRangeByScore → size → add} 这个顺序。同一成员重复登记不增加条数
     * （ZSET 成员唯一），两个实现都如此。
     */
    long windowRegister(String key, long nowMs, long windowMs, String member, Duration ttl);

    /** SOP 静态清单：写一个字段。 */
    void mapPut(String key, String field, String value);

    /** SOP 静态清单：读一个字段；无此键/字段返回 null。 */
    String mapGet(String key, String field);

    /** SOP 静态清单：整张表是否为空（决定 L2 兜底有没有东西可直出）。 */
    boolean mapIsEmpty(String key);

    /** SOP 静态清单：全部值（兜底路径取前两条）。顺序不承诺——Redis 侧同样不承诺。 */
    List<String> mapValues(String key);

    /** SOP 静态清单：往集合里加成员（按错误码/服务名索引 doc）。 */
    void setAdd(String key, String member);

    /** SOP 静态清单：读集合成员。 */
    Set<String> setMembers(String key);

    /** 键是否存在（健康探针的单键 GET，不 SCAN）。 */
    boolean exists(String key);

    /**
     * 后端自报（健康面 / 启动摘要 / 页面标注）：{@code redis} 或 {@code memory}。
     * 存在的理由与 {@code dashscope.mode} 的启动摘要同源——"我这次跑的是什么后端"必须是
     * 一条可 grep 的事实，不能靠人回忆启动命令。
     */
    String backend();
}
