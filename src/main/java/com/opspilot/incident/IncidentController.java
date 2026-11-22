package com.opspilot.incident;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 事故端点（JWT 守卫内，路径前缀已在 {@link JwtAuthFilter} 的 guarded 清单里）。
 * 响应字段刻意使用前端既有的 camelCase 契约，避免前端做一层无意义的字段映射。
 */
@RestController
@RequestMapping("/api/v1/copilot/incidents")
public class IncidentController {

    private final IncidentService service;
    private final ObjectMapper json;

    public IncidentController(IncidentService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest http,
                                    @RequestParam(name = "mine", required = false) String mine,
                                    @RequestParam(name = "status", required = false) String status) {
        List<Map<String, Object>> rows = service.list(user(http), mine, status).stream()
                .map(this::view).toList();
        return Map.of("incidents", rows);
    }

    @GetMapping("/assignees")
    public Map<String, Object> assignees(HttpServletRequest http) {
        List<Map<String, Object>> rows = service.assignees(user(http)).stream()
                .map(u -> Map.<String, Object>of("sub", u.sub(), "role", u.role(), "authLevel", u.authLevel()))
                .toList();
        return Map.of("users", rows);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody IncidentRequests.Create req,
                                                      HttpServletRequest http) {
        var result = service.create(user(http), req);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(view(result.incident()));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id, HttpServletRequest http) {
        return view(service.get(user(http), id));
    }

    @GetMapping("/{id}/assignments")
    public Map<String, Object> assignments(@PathVariable String id, HttpServletRequest http) {
        return Map.of("assignments", service.assignments(user(http), id));
    }

    @PostMapping("/{id}/transfer")
    public Map<String, Object> transfer(@PathVariable String id,
                                        @Valid @RequestBody IncidentRequests.Transfer req,
                                        HttpServletRequest http) {
        return view(service.transfer(user(http), id, req));
    }

    @PatchMapping("/{id}")
    public Map<String, Object> patch(@PathVariable String id,
                                     @Valid @RequestBody IncidentRequests.Patch req,
                                     HttpServletRequest http) {
        return view(service.patch(user(http), id, req));
    }

    private UserContext user(HttpServletRequest http) {
        UserContext u = JwtAuthFilter.from(http);
        if (u == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "缺少身份上下文");
        return u;
    }

    private Map<String, Object> view(Incident i) {
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
        m.put("diagnosis", parse(i.diagnosisJson()));
        m.put("timeline", parse(i.timelineJson()));
        return m;
    }

    private JsonNode parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return json.readTree(raw);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "事故单 JSON 字段损坏");
        }
    }
}
