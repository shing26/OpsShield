package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import com.opspilot.metrics.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * 事故领域服务：租户隔离、幂等创建、分配校验与状态机都在这里，Controller 只做协议转换。
 *
 * <p><b>权限口径</b>：同租户成员都可读、都可写诊断/时间线（协作）；但
 * 「结单/重开/改负责人/改优先级」只允许 created_by、当前 owner 或 platform——
 * 这三类动作改变的是责任归属，不是内容。越权统一 403 并留 {@code ev=incident} 审计。
 *
 * <p><b>责任流转</b>（ADR-0033）：{@code transfer} 与 PATCH owner 共用同一个
 * {@link IncidentStore#transfer} 事务入口——owner 的条件更新与 append-only 转交记录
 * 要么都成功、要么都不写。前端时间线不参与责任判定。
 */
@Service
public class IncidentService {

    private static final int MAX_LIST = 60;
    private static final int MAX_ASSIGNMENTS = 50;
    private static final int MAX_REASON = 200;
    private static final String NO_REASON = "—";

    private final IncidentStore store;
    private final UserStore users;
    private final AuditService audit;
    private final ObjectMapper json;
    private final IncidentSlaProperties slaProperties;

    public IncidentService(IncidentStore store, UserStore users, AuditService audit, ObjectMapper json,
                           IncidentSlaProperties slaProperties) {
        this.store = store;
        this.users = users;
        this.audit = audit;
        this.json = json;
        this.slaProperties = slaProperties;
    }

    public List<Incident> list(UserContext user) {
        return list(user, null, null);
    }

    /**
     * 服务端筛选：mine=true 时只取 owner=调用者 sub；status 可叠加。
     * 两者都只拼绑定参数，不拼 SQL 字面量；无参数时与历史行为一致。
     */
    public List<Incident> list(UserContext user, String mine, String status) {
        requireUser(user);
        String owner = null;
        if (mine != null && !mine.isBlank()) {
            if ("true".equalsIgnoreCase(mine)) owner = user.sub();
            else if (!"false".equalsIgnoreCase(mine)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mine 只能是 true 或 false");
            }
        }
        String statusFilter = null;
        if (status != null && !status.isBlank()) {
            if (!Incident.STATUS_OPEN.equals(status) && !Incident.STATUS_CLOSED.equals(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status 只能是 open 或 closed");
            }
            statusFilter = status;
        }
        return store.list(user.tenantId(), MAX_LIST, owner, statusFilter);
    }

    public Incident get(UserContext user, String id) {
        requireUser(user);
        return store.find(user.tenantId(), id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "事故单不存在"));
    }

    /** 转交历史只读 append-only 表；跨租户 get() 已经按 404 拒绝了。 */
    public List<IncidentAssignment> assignments(UserContext user, String id) {
        requireUser(user);
        get(user, id);
        return store.assignments(user.tenantId(), id, MAX_ASSIGNMENTS);
    }

    public List<UserStore.User> assignees(UserContext user) {
        requireUser(user);
        return users.listActiveByTenant(user.tenantId());
    }

    /** 创建结果带 {@code created} 标记：幂等重放也要能如实回 200 而不是假装新建。 */
    public record Created(Incident incident, boolean created) {}

    public Created create(UserContext user, IncidentRequests.Create req) {
        requireUser(user);
        String clientId = blankToNull(req.clientId());
        if (clientId == null) clientId = UUID.randomUUID().toString();
        var replay = store.findByClientId(user.tenantId(), clientId);
        if (replay.isPresent()) return new Created(replay.get(), false);

        Instant now = Instant.now();
        String query = req.query().trim();
        String title = blankToNull(req.title());
        if (title == null) title = titleFromQuery(query);
        String source = "alert".equals(req.source()) ? "alert" : "manual";
        String priority = req.priority() == null ? "" : req.priority();
        Instant dueAt = deriveDueAt(priority, now);

        Incident draft = new Incident(
                null, user.tenantId(), clientId, title, query,
                nz(req.service()), nz(req.errorCode()), nz(req.env()), nz(req.alertId()),
                source, priority, Incident.STATUS_OPEN, user.sub(), user.sub(),
                now, now, null, null, null,
                null, initialTimeline(source, req.alertId(), now),
                dueAt, Incident.SLA_OK);
        try {
            Incident saved = store.insert(draft);
            audit.logIncident(user, saved.id(), "create", "ok", source);
            return new Created(saved, true);
        } catch (DuplicateKeyException e) {
            // 并发重放：另一条请求已经用同一 clientId 建好，回查即幂等
            return new Created(store.findByClientId(user.tenantId(), clientId).orElseThrow(() -> e), false);
        }
    }

    /** 显式转交：owner 改写与转交事实在同一事务内完成。 */
    @Transactional
    public Incident transfer(UserContext user, String id, IncidentRequests.Transfer req) {
        requireUser(user);
        Incident cur = get(user, id);
        // 先判权限再判结单：越权者不应通过 409 反推出"这单已结单"。
        requireResponsibility(user, cur, "transfer");
        if (cur.closed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "事故单已结单；重新打开后再转交");
        }
        String toOwner = blankToNull(req.toOwner());
        String reason = normalizeReason(req.reason());
        if (toOwner == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "toOwner 不能为空");
        }
        requireAssignableTarget(user, toOwner, id, "transfer");
        if (toOwner.equals(cur.owner())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "目标与当前负责人相同，不做无意义转交");
        }

        Instant now = Instant.now();
        Incident next = new Incident(cur.id(), cur.tenant(), cur.clientId(),
                cur.title(), cur.query(), cur.service(), cur.errorCode(), cur.env(), cur.alertId(),
                cur.source(), cur.priority(), cur.status(), toOwner, cur.createdBy(),
                cur.createdAt(), now, cur.closedAt(), cur.closedBy(), cur.closeNote(),
                cur.diagnosisJson(), cur.timelineJson(), cur.dueAt(),
                cur.slaState() == null ? Incident.SLA_OK : cur.slaState());
        if (!store.transfer(next, cur.owner(), reason, user.sub(), now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "事故单已被其他会话转交，请刷新后重试");
        }
        audit.logIncident(user, id, "transfer", "ok", cur.owner() + " -> " + toOwner);
        return next;
    }

    @Transactional
    public Incident patch(UserContext user, String id, IncidentRequests.Patch p) {
        requireUser(user);
        Incident cur = get(user, id);
        boolean reopening = cur.closed() && "open".equals(p.status());
        if (cur.closed() && !reopening && touchesAnyField(p)) {
            // 结单即冻结：除单独提交 {"status":"open"} 外，任何字段都不再接受改写。
            // 否则 UI 虽隐藏入口，直接调 API 仍能在结单后改标题/负责人/处置结论。
            throw new ResponseStatusException(HttpStatus.CONFLICT, "事故单已结单；仅可重新打开后再修改");
        }
        if (reopening && touchesAnyFieldExceptStatus(p)) {
            // 重开是状态迁移，不与内容修改混在一次请求里，避免"以重开为名顺手改责任字段"。
            throw new ResponseStatusException(HttpStatus.CONFLICT, "重新打开请单独提交 status，勿混带其他字段");
        }

        boolean manages = manages(user, cur);
        boolean touchesResponsibility = p.status() != null || p.owner() != null
                || p.priority() != null || p.closeNote() != null;
        if (touchesResponsibility && !manages) {
            auditDeniedResponsibility(user, id, p);
        }
        boolean touchesMetadata = p.title() != null || p.query() != null || p.service() != null
                || p.errorCode() != null || p.env() != null || p.alertId() != null;
        if (touchesMetadata && !manages) {
            audit.logIncident(user, id, "denied", "forbidden", "仅可协作写诊断/时间线");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有创建人、负责人或平台管理员可以改事故单内容");
        }

        String owner = cur.owner();
        boolean ownerChanged = false;
        String action = "update";
        if (p.owner() != null) {
            owner = blankToNull(p.owner());
            if (owner != null) {
                requireAssignableTarget(user, owner, id, "assign");
                if (owner.equals(cur.owner())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "目标与当前负责人相同，不做无意义写入");
                }
            }
            ownerChanged = !Objects.equals(owner, cur.owner());
            action = "assign";
        }

        String status = cur.status();
        Instant closedAt = cur.closedAt();
        String closedBy = cur.closedBy();
        String closeNote = cur.closeNote();
        if (p.status() != null && !p.status().equals(status)) {
            if (Incident.STATUS_CLOSED.equals(p.status())) {
                status = Incident.STATUS_CLOSED;
                closedAt = Instant.now();
                closedBy = user.sub();
                action = "close";
            } else {
                status = Incident.STATUS_OPEN;
                closedAt = null;
                closedBy = null;
                action = "reopen";
            }
        }
        if (p.closeNote() != null) closeNote = blankToNull(p.closeNote());

        String diagnosis = p.diagnosisJson() == null ? cur.diagnosisJson() : requireJson(p.diagnosisJson(), "diagnosisJson");
        String timeline = p.timelineJson() == null ? cur.timelineJson() : requireJson(p.timelineJson(), "timelineJson");

        String title = p.title() == null ? cur.title() : p.title().trim();
        String query = p.query() == null ? cur.query() : p.query().trim();
        if (query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }
        String priority = p.priority() == null ? cur.priority() : p.priority();
        Instant now = Instant.now();
        Instant dueAt = cur.dueAt();
        String slaState = cur.slaState() == null ? Incident.SLA_OK : cur.slaState();
        if (p.priority() != null && !Objects.equals(priority, cur.priority())) {
            // 优先级变化即重新起算响应时限；清空则 no due_at（空优先级不设时限）。
            dueAt = deriveDueAt(priority, now);
            slaState = Incident.SLA_OK;
        }
        Incident next = new Incident(cur.id(), cur.tenant(), cur.clientId(),
                title.isBlank() ? cur.title() : title, query,
                p.service() == null ? cur.service() : p.service().trim(),
                p.errorCode() == null ? cur.errorCode() : p.errorCode().trim(),
                p.env() == null ? cur.env() : p.env().trim(),
                p.alertId() == null ? cur.alertId() : p.alertId().trim(), cur.source(),
                priority, status, owner, cur.createdBy(), cur.createdAt(), now, closedAt, closedBy, closeNote,
                diagnosis, timeline, dueAt, slaState);
        boolean saved = ownerChanged
                ? store.transfer(next, cur.owner(), NO_REASON, user.sub(), now)
                : store.update(next);
        if (!saved) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "事故单已被其他会话修改，请刷新后重试");
        }
        audit.logIncident(user, id, action, "ok", p.status() == null ? null : status);
        return next;
    }

    private void auditDeniedResponsibility(UserContext user, String id, IncidentRequests.Patch p) {
        boolean statusOwnership = p.status() != null || p.owner() != null || p.priority() != null;
        audit.logIncident(user, id, "denied", "forbidden", statusOwnership
                ? "需 created_by / owner / platform"
                : "需 created_by / owner / platform（closeNote）");
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "只有创建人、负责人或平台管理员可以改状态/负责人/优先级");
    }

    private boolean manages(UserContext user, Incident incident) {
        return user.sub().equals(incident.createdBy())
                || user.sub().equals(incident.owner())
                || "platform".equals(user.role());
    }

    /** 授权口径与既有责任动作完全相同（ADR-0032）；不新增角色。 */
    private void requireResponsibility(UserContext user, Incident incident, String action) {
        if (!manages(user, incident)) {
            audit.logIncident(user, incident.id(), action, "forbidden", "需 created_by / owner / platform");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "只有创建人、负责人或平台管理员可以转交事故单");
        }
    }

    private void requireAssignableTarget(UserContext user, String sub, String incidentId, String action) {
        UserStore.User target = users.find(sub);
        if (target == null || target.disabled() || !user.tenantId().equals(target.tenant())) {
            audit.logIncident(user, incidentId, action, "invalid", "目标不在本租户或已禁用");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是本租户的启用用户");
        }
    }

    private String normalizeReason(String reason) {
        if (reason != null && reason.length() > MAX_REASON) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason 超长（上限 200）");
        }
        return reason == null || reason.isBlank() ? NO_REASON : reason.trim();
    }

    private Instant deriveDueAt(String priority, Instant from) {
        Duration window = slaProperties.window(priority);
        return window == null ? null : from.plus(window);
    }

    private void requireUser(UserContext user) {
        if (user == null || user.authLevel() < 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "auth_level 非法（须 >=1）");
        }
    }

    private static boolean touchesAnyField(IncidentRequests.Patch p) {
        return p.title() != null || p.query() != null || p.service() != null
                || p.errorCode() != null || p.env() != null || p.alertId() != null
                || p.status() != null || p.owner() != null || p.priority() != null
                || p.diagnosisJson() != null || p.timelineJson() != null || p.closeNote() != null;
    }

    private static boolean touchesAnyFieldExceptStatus(IncidentRequests.Patch p) {
        return p.title() != null || p.query() != null || p.service() != null
                || p.errorCode() != null || p.env() != null || p.alertId() != null
                || p.owner() != null || p.priority() != null
                || p.diagnosisJson() != null || p.timelineJson() != null || p.closeNote() != null;
    }

    private String requireJson(String raw, String field) {
        if (raw.isEmpty()) return null;
        try {
            json.readTree(raw);
            return raw;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " 不是合法 JSON");
        }
    }

    private String initialTimeline(String source, String alertId, Instant now) {
        List<Map<String, Object>> events = new ArrayList<>();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("at", now.toString());
        ev.put("kind", "created");
        ev.put("text", "事故单创建 · 来源 " + source);
        ev.put("meta", alertId == null || alertId.isBlank() ? "" : "alertId " + alertId);
        events.add(ev);
        try {
            return json.writeValueAsString(events);
        } catch (RuntimeException e) {
            return "[]";
        }
    }

    private static String titleFromQuery(String query) {
        String one = query.replaceAll("\\s+", " ").trim();
        if (one.isEmpty()) return "未命名事故";
        return one.length() > 46 ? one.substring(0, 46) + "…" : one;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
