package com.opspilot.incident;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 事故单的对外视图（字段名保持前端既有的 camelCase 契约）。
 *
 * <p>抽成独立工具而不是留在 Controller：告警入口的「一键建单」在平台面返回**同一种**
 * 事故单形状，两处各拼一份迟早漂移（ADR-0035）。
 */
public final class IncidentView {

    private IncidentView() {
    }

    public static Map<String, Object> of(Incident i, ObjectMapper json) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id());
        m.put("title", i.title());
        m.put("query", i.query());
        m.put("service", i.service());
        m.put("errorCode", i.errorCode());
        m.put("env", i.env());
        m.put("alertId", i.alertId());
        m.put("source", i.source());
        m.put("priority", i.priority());
        m.put("status", i.status());
        m.put("owner", i.owner());
        m.put("createdBy", i.createdBy());
        m.put("createdAt", i.createdAt());
        m.put("updatedAt", i.updatedAt());
        m.put("dueAt", i.dueAt());
        m.put("slaState", i.slaState());
        m.put("closedAt", i.closedAt());
        m.put("closedBy", i.closedBy());
        m.put("closeNote", i.closeNote());
        m.put("diagnosis", parse(i.diagnosisJson(), json));
        m.put("timeline", parse(i.timelineJson(), json));
        return m;
    }

    private static JsonNode parse(String raw, ObjectMapper json) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return json.readTree(raw);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "事故单 JSON 字段损坏");
        }
    }
}
