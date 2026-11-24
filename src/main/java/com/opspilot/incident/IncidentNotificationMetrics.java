package com.opspilot.incident;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 通知指标（ADR-0037）：<b>Prometheus-only</b>，不进 OpsMetrics.snapshot。
 *
 * <p>面板契约把 snapshot 键集锁死了，而通知量的消费者是抓取侧 PromQL；
 * 指标名与契约一致：{@code aiops_incident_notifications_total{kind}}、
 * {@code aiops_incident_notify_webhook_total{result}}。
 * 所有标签值在构造期预注册 0 基线——"还没发生过"与"指标没接上"必须能区分。
 */
@Component
public class IncidentNotificationMetrics {

    private final MeterRegistry registry;

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
    }

    public void notification(String kind) {
        registry.counter("aiops.incident.notifications", "kind", kind).increment();
    }

    public void webhook(String result) {
        registry.counter("aiops.incident.notify.webhook", "result", result).increment();
    }
}
