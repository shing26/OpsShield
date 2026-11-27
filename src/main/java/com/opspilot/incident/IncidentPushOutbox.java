package com.opspilot.incident;

import java.time.Instant;

/**
 * 一条持久化外发任务（ADR-0045）。
 *
 * <p>行内只带定位信息；query、诊断正文、评论正文、引用片段与 secret 都不进这张表。
 * {@code eventKey} 是业务事件身份：created 为 {@code c:<incidentId>}，站内通知为
 * {@code n:<dedupeKey>}。唯一键 {@code (tenant, eventKey, channelId)} 保证一个事件对
 * 同一频道只入一行。
 */
public record IncidentPushOutbox(
        long id,
        String tenant,
        String eventKey,
        String incidentId,
        String kind,
        String recipient,
        String priority,
        String title,
        Instant dueAt,
        String channelId,
        String status,
        int attempts,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Integer lastStatus,
        String lastError,
        Instant sentAt,
        Instant createdAt) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_DEAD = "dead";

    public static final String KIND_CREATED = "created";

    public boolean terminal() {
        return STATUS_SENT.equals(status) || STATUS_DEAD.equals(status);
    }
}
