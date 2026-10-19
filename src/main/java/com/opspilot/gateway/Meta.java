package com.opspilot.gateway;

import com.opspilot.gateway.dto.Gate;
import com.opspilot.resilience.DegradationState.Level;
import java.util.Objects;

/**
 * 一次回答交付的运行时元信息，不进入缓存或持久化载荷。
 */
public record Meta(
        String fingerprint,
        String cacheHit,
        Level level,
        boolean fastPath,
        boolean deduplicated,
        long retrievalMs,
        boolean refused,
        Gate gate
) {
    public Meta {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(gate, "gate");
    }
}
