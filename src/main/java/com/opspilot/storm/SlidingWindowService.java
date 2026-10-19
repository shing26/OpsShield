package com.opspilot.storm;

import java.time.Duration;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.KvStore;

/**
 * Redisson 聚合计数窗口（{@link SlidingWindow} 默认实现，生成质量包 Q4 语义矫正）：
 * ZSET score=时间戳，维护 `来源×指纹` 的窗口内请求计数，供 dedup 计数与"30s 聚合"
 * 叙事。alert 来源用更长窗口（默认 60s），manual 用 30s；key 掺 source，两源修剪
 * 互不污染计数。
 *
 * **窗口不是穿透排除闸门**：穿透的唯一闸门归属 Single-Flight（ADR-0003/0008）。
 * 旧口径"窗口内同指纹仅首条穿透"与实现不符，2026-09-13 grill 裁定废止
 * （CONTEXT.md「滑动窗口」词条为当前真相）。
 */
@Service
public class SlidingWindowService implements SlidingWindow {

    private final KvStore store;
    private final OpsPilotProperties props;

    public SlidingWindowService(KvStore store, OpsPilotProperties props) {
        this.store = store;
        this.props = props;
    }

    /**
     * 登记一次窗口内计数。first=true 表示该来源窗口内首条（计数从此起算）；
     * 调用方（ChatOrchestrator leader 路径）仅据此记 dedup_aggregated 指标——
     * 检索照常执行，等待/复用由 Single-Flight 负责。
     */
    @Override
    public WindowResult tryAcquire(String fingerprint, String source) {
        int windowSec = "alert".equals(source)
                ? props.storm().alertWindowSeconds() : props.storm().windowSeconds();
        long now = System.currentTimeMillis();
        // 修剪/计数/登记三步收在 KvStore.windowRegister 里：两个后端各自实现同一顺序，
        // 上层拿到的仍是"此前几条"这一个语义（ADR-0022 决策 4）。
        long size = store.windowRegister("storm:win:" + source + ":" + fingerprint, now,
                windowSec * 1000L, now + "-" + Thread.currentThread().threadId(),
                Duration.ofSeconds(windowSec + 10L));
        return new WindowResult(size == 0, size);
    }
}
