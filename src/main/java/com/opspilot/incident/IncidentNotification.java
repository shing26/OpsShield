package com.opspilot.incident;

import java.time.Instant;

/**
 * 一条站内通知（派生数据，ADR-0037）。
 *
 * <p>刻意只携带定位信息：事故 ID、标题、优先级、时限与事件类型。query、诊断正文、
 * 评论正文、引用片段都不进通知，也不进 webhook 载荷。
 */
public record IncidentNotification(
        long id,
        String tenant,
        String recipient,
        String incidentId,
        String kind,
        String title,
        String priority,
        Instant dueAt,
        String dedupeKey,
        Instant createdAt,
        Instant readAt) {

    public static final String KIND_ASSIGNED = "assigned";
    public static final String KIND_SLA_DUE_SOON = "sla_due_soon";
    public static final String KIND_SLA_BREACHED = "sla_breached";
    public static final String KIND_REOPENED = "reopened";

    public boolean read() {
        return readAt != null;
    }
}
