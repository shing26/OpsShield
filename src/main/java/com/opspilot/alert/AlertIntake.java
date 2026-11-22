package com.opspilot.alert;

import java.time.Instant;

/**
 * 一条告警台账行（ADR-0035）。
 *
 * <p>身份是 {@code (tenant, alertId)}：{@code alertId} 由桥按
 * {@code alertname|job|error_code|status} 派生、**不含租户**，故同一 alertId 在两个租户
 * 是两行，不是同一行（契约 R1）。
 *
 * @param incidentId 已建立的事故单 ID；null 表示尚未建单
 */
public record AlertIntake(
        String tenant,
        String alertId,
        String traceId,
        String service,
        String env,
        String errorCode,
        String summary,
        String alertStatus,
        Instant firstSeen,
        Instant lastSeen,
        int seenCount,
        String incidentId) {
}
