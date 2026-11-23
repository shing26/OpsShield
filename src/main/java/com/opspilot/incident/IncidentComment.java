package com.opspilot.incident;

import java.time.Instant;

/**
 * 事故评论行（ADR-0034）。{@code mentionsJson} 是服务端从正文解析出的用户数组，
 * 以原始 JSON 保存；解析/渲染在 {@link IncidentService} 做，存储层不依赖 Jackson。
 *
 * <p>评论是 append-only 的对话事实：没有 update/delete 方法，也没有「软删」列——
 * 撤回若要支持，必须另立一轮设计（ADR-0034 的否决项）。
 */
public record IncidentComment(
        long commentId,
        String tenant,
        String incidentId,
        String author,
        String body,
        String mentionsJson,
        Instant createdAt) {}
