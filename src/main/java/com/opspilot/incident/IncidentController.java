package com.opspilot.incident;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
        UserContext user = user(http);
        Map<String, Long> lastComments = service.lastCommentIds(user);
        List<Map<String, Object>> rows = service.list(user, mine, status).stream()
                .map(i -> {
                    Map<String, Object> m = view(i);
                    // 左栏未读角标只消费这个单调水位；评论正文不进入列表响应。
                    m.put("lastCommentId", lastComments.getOrDefault(i.id(), 0L));
                    return m;
                }).toList();
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

    /**
     * 追加评论。刻意只有 GET / POST 两个映射：没有编辑/删除端点（ADR-0034）。
     * 对同一路径发 DELETE 会由 Spring 报 405（GlobalExceptionHandler 保留 405 语义），
     * 不会被任何兜底映射变成静默成功。
     */
    @PostMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> addComment(@PathVariable String id,
                                                          @RequestBody(required = false) IncidentRequests.Comment req,
                                                          HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addComment(user(http), id, req));
    }

    @GetMapping("/{id}/comments")
    public Map<String, Object> comments(@PathVariable String id,
                                        @RequestParam(name = "since", required = false) Long since,
                                        HttpServletRequest http) {
        return service.listComments(user(http), id, since);
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
        return IncidentView.of(i, json);
    }
}
