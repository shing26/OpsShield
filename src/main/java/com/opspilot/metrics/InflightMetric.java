package com.opspilot.metrics;

import com.opspilot.resilience.DegradationState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * 在途请求 gauge（G0，2026-10-22 运维审查）。
 *
 * <p>为什么必须有它：L1 的触发线是 {@code inflight >= 40}，但该值此前只出现在
 * {@code /admin/state} 的 JSON 里——Prometheus 抓不到，于是"容量接近降级拐点"无法告警，
 * 只能等 L1 已经发生后再从 {@code degradation.state} 看见。运维需要的是**降级前**的窗口。
 *
 * <p>实现与 {@link DegradationStateMetric} 同构：值来自另一个 bean，注册权归指标侧；
 * gauge 无标签、无副作用（只读 {@code inflightValue()}，不触发状态机观察）。
 */
@Component
public class InflightMetric implements MeterBinder {

    private final DegradationState state;

    public InflightMetric(DegradationState state) {
        this.state = state;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("aiops.inflight", state, s -> (double) s.inflightValue())
                .description("当前在途编排请求数（L1 触发线见 opspilot.degrade.inflight-threshold）")
                .register(registry);
    }
}
