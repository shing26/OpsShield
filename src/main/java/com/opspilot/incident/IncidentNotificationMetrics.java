package com.opspilot.incident;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 通知与外发指标（ADR-0037 / ADR-0045）：<b>Prometheus-only</b>，不进 OpsMetrics.snapshot。
 *
 * <p>新增外发四项：{@code aiops_incident_push_total{channel,result}}、
 * {@code aiops_incident_push_pending{channel}}、{@code aiops_incident_push_latency_seconds{channel}}、
 * {@code aiops_incident_push_last_success_timestamp{channel}}。
 *
 * <p>旧 {@code aiops.incident.notify.webhook{result}} 保留：只对 {@code default} 通道映射
 * {@code ok|fail|suppressed}，不再产生 {@code dropped}（队列丢弃路径已由持久化 outbox 取代）。
 * {@code dropped} 仍以 0 基线注册，便于旧面板/抓取查询不因序列消失而报错。
 */
@Component
public class IncidentNotificationMetrics {

    private final MeterRegistry registry;
    private final Map<String, AtomicLong> lastSuccess = new ConcurrentHashMap<>();

    public IncidentNotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String kind : new String[]{
                IncidentNotification.KIND_ASSIGNED,
                IncidentNotification.KIND_SLA_DUE_SOON,
                IncidentNotification.KIND_SLA_BREACHED,
                IncidentNotification.KIND_REOPENED}) {
            registry.counter("aiops.incident.notifications", "kind", kind);
        }
        for (String result : new String[]{"ok", "fail", "suppressed", "dropped"}) {
            registry.counter("aiops.incident.notify.webhook", "result", result);
        }
        for (String result : new String[]{"ok", "retry", "dead", "suppressed"}) {
            registry.counter("aiops.incident.push", "channel", "unconfigured", "result", result);
        }
    }

    public void notification(String kind) {
        registry.counter("aiops.incident.notifications", "kind", kind).increment();
    }

    /** 旧指标：仅 default 通道调用。 */
    public void webhook(String result) {
        registry.counter("aiops.incident.notify.webhook", "result", result).increment();
    }

    public void push(String channel, String result) {
        registry.counter("aiops.incident.push", "channel", channel, "result", result).increment();
    }

    public void pushLatency(String channel, long nanos) {
        registry.timer("aiops.incident.push.latency", "channel", channel)
                .record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    public void pushSuccess(String channel, Instant at) {
        lastSuccess.computeIfAbsent(channel, this::registerLastSuccess)
                .set(at.getEpochSecond());
    }

    /** 启动期为已知通道建好 pending / last_success 的 0 基线。 */
    public void registerChannel(String channel, Supplier<Number> pending) {
        registerPendingGauge(channel, pending);
        lastSuccess.computeIfAbsent(channel, this::registerLastSuccess);
    }

    /**
     * pending gauge 由 store 现算（单实例查询 1s 一次，成本可接受）；查询失败回 0，
     * 避免数据库短暂不可用时整个 /actuator/prometheus 被拖垮。
     */
    public void registerPendingGauge(String channel, Supplier<Number> supplier) {
        Gauge.builder("aiops.incident.push.pending", supplier)
                .tag("channel", channel)
                .register(registry);
    }

    private AtomicLong registerLastSuccess(String channel) {
        AtomicLong value = new AtomicLong(0);
        Gauge.builder("aiops.incident.push.last.success.timestamp", value, AtomicLong::get)
                .tag("channel", channel)
                .register(registry);
        return value;
    }
}
