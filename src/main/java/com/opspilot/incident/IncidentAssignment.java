package com.opspilot.incident;

import java.time.Instant;

/**
 * 一次责任转交的不可变事实（V6 的 incident_assignments 行）。
 *
 * <p>与 {@code incidents.owner} 的就地覆盖不同，这里只追加：谁在何时把单子从谁交给谁、
 * 为什么。前端时间线是整段覆盖写的快照，不能当责任凭证（ADR-0032 已记该边界）。
 */
public record IncidentAssignment(
        String fromOwner,
        String toOwner,
        String reason,
        String assignedBy,
        Instant assignedAt) {
}
