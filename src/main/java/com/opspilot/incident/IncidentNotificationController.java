package com.opspilot.incident;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 通知端点（JWT 守卫内）。
 *
 * <p>契约：{@code tenant} 与 {@code recipient} 只来自 JWT，查询参数再大也改不了
 * "看谁的通知"；非本人 / 跨租户一律 404（不泄漏存在性）。
 */
@RestController
@RequestMapping("/api/v1/copilot/notifications")
public class IncidentNotificationController {

    private final IncidentNotificationService service;

    public IncidentNotificationController(IncidentNotificationService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest http,
                                    @RequestParam(name = "unreadOnly", required = false) Boolean unreadOnly,
                                    @RequestParam(name = "limit", required = false) Integer limit) {
        UserContext user = user(http);
        boolean unread = Boolean.TRUE.equals(unreadOnly);
        List<Map<String, Object>> rows = service.list(user, unread, limit).stream()
                .map(this::view).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("notifications", rows);
        out.put("unread", service.unread(user));
        return out;
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@PathVariable long id, HttpServletRequest http) {
        if (!service.markRead(user(http), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll(HttpServletRequest http) {
        return Map.of("updated", service.readAll(user(http)));
    }

    private Map<String, Object> view(IncidentNotification n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id());
        m.put("incidentId", n.incidentId());
        m.put("kind", n.kind());
        m.put("title", n.title());
        m.put("priority", n.priority());
        m.put("dueAt", n.dueAt());
        m.put("createdAt", n.createdAt());
        m.put("read", n.read());
        return m;
    }

    private UserContext user(HttpServletRequest http) {
        UserContext u = JwtAuthFilter.from(http);
        if (u == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少身份上下文");
        return u;
    }
}
