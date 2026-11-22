package com.opspilot.incident;

import java.time.Instant;

/**
 * 事故行（服务端唯一真相）。JSON 字段以字符串保存，读写在 {@link IncidentService} 里做解析/校验，
 * 这样存储层不依赖 Jackson，也不会把"坏 JSON"从数据库带到响应面。
 */
public record Incident(
        String id,
        String tenant,
        String clientId,
        String title,
        String query,
        String service,
        String errorCode,
        String env,
        String alertId,
        String source,
        String priority,
        String status,
        String owner,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        String closedBy,
        String closeNote,
        String diagnosisJson,
        String timelineJson,
        Instant dueAt,
        String slaState) {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_CLOSED = "closed";

    public static final String SLA_OK = "ok";
    public static final String SLA_DUE_SOON = "due_soon";
    public static final String SLA_BREACHED = "breached";

    public boolean closed() {
        return STATUS_CLOSED.equals(status);
    }
}
