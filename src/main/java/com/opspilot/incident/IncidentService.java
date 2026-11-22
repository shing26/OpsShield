package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import com.opspilot.metrics.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
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

    /** 评论约束（ADR-0034）：正文 1..2000 字、单事故 200 条、单条 @ 最多 10 人。 */
    private static final int MAX_COMMENT_BODY = 2000;
    /** 正文的 UTF-16 码元兜底：2000 个 code point 最坏是 4000 个码元（全是代理对）。 */
    private static final int MAX_COMMENT_UTF16 = 4000;
    private static final int MAX_COMMENTS = 200;
    private static final int MAX_MENTIONS = 10;
    private static final int COMMENT_PAGE = 100;

    /**
     * @ 用户名：以正文为准服务端解析。前置字符不是「词字符 / 点」才认作 @——
     * 否则 email（{@code a@b.com}）会被误认成点名。用户名允许 {@code -}（如 {@code sre-limited}）。
     */
    private static final Pattern MENTION = Pattern.compile("(?<![A-Za-z0-9_.])@([A-Za-z0-9][A-Za-z0-9_-]*)");

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

    // ---- 评论（ADR-0034）：append-only 的对话事实，独立于时间线快照 ----

    /**
     * 追加一条评论。任何同租户、authLevel>=1 的用户都可写（与「协作者可写诊断」同口径）；
     * 不需要 created_by / owner / platform，也不改变事故状态、负责人、优先级或时间线。
     *
     * <p>@ 只从正文解析：请求体里的 {@code mentions} 数组是客户端提示，不参与授权，
     * 也不落库——否则前端一改数组就能伪造点名。
     *
     * <p>「计数 + 插入」在事故行锁内完成（{@link IncidentStore#lockIncident}）：
     * 事务把同一事故的追加串行化，200 条上限在并发下也成立。
     */
    @Transactional
    public Map<String, Object> addComment(UserContext user, String id, IncidentRequests.Comment req) {
        requireUser(user);
        get(user, id); // 跨租户在这里就是 404，不泄漏事故是否存在

        String body = req == null || req.body() == null ? "" : req.body().trim();
        if (body.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "评论正文不能为空");
        }
        if (body.codePointCount(0, body.length()) > MAX_COMMENT_BODY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "评论正文超长（上限 " + MAX_COMMENT_BODY + " 字）");
        }
        if (body.length() > MAX_COMMENT_UTF16) {
            // 兜底：code point 口径合法但 UTF-16 码元超出列宽时直接 400，
            // 绝不让它走到 JDBC 层变成 500（H2 按码元计量，PostgreSQL 按字符计量）。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "评论正文超长（上限 " + MAX_COMMENT_BODY + " 字，最多 " + MAX_COMMENT_UTF16 + " 码元）");
        }

        // 锁在计数之前：否则两个请求会同时读到 199 再各自插入，突破 200 上限。
        if (!store.lockIncident(user.tenantId(), id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "事故单不存在");
        }
        if (store.countComments(user.tenantId(), id) >= MAX_COMMENTS) {
            // 上限是「单事故」的：继续堆会让讨论区失去可读性，也会把真正的结单信息淹没。
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "单条事故评论已达上限（" + MAX_COMMENTS + " 条），请在结单备注里汇总");
        }

        List<String> mentions = resolveMentions(user, body, id);
        String mentionsJson = writeMentions(mentions);
        Instant now = Instant.now();
        long commentId = store.insertComment(user.tenantId(), id, user.sub(), body, mentionsJson, now);
        audit.logIncident(user, id, "comment", "ok", "commentId=" + commentId);
        return commentView(new IncidentComment(commentId, user.tenantId(), id, user.sub(), body,
                mentionsJson, now));
    }

    /** 增量读取：{@code since} 是 commentId 水位；按 (created_at, comment_id) 升序，单次上限 100。 */
    public Map<String, Object> listComments(UserContext user, String id, Long since) {
        requireUser(user);
        get(user, id);
        long from = since == null ? 0L : Math.max(0L, since);
        List<IncidentComment> rows = store.listComments(user.tenantId(), id, from, COMMENT_PAGE);
        long nextSince = rows.isEmpty() ? from : rows.get(rows.size() - 1).commentId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("comments", rows.stream().map(this::commentView).toList());
        out.put("nextSince", nextSince);
        return out;
    }

    /**
     * 左栏未读角标所需的每单最大 commentId。只读聚合列，不读正文；
     * 它不进检索/LLM/指纹/缓存键（AC9），只是会话内的计数水位。
     */
    public Map<String, Long> lastCommentIds(UserContext user) {
        requireUser(user);
        return store.lastCommentIds(user.tenantId());
    }

    private List<String> resolveMentions(UserContext user, String body, String incidentId) {
        Matcher m = MENTION.matcher(body);
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        while (m.find()) unique.add(m.group(1));
        if (unique.size() > MAX_MENTIONS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "单条评论最多 @ " + MAX_MENTIONS + " 人（当前 " + unique.size() + " 人）");
        }
        List<String> out = new ArrayList<>(unique.size());
        for (String sub : unique) {
            UserStore.User target = users.find(sub);
            if (target == null || target.disabled() || !user.tenantId().equals(target.tenant())) {
                audit.logIncident(user, incidentId, "comment", "invalid", "@" + sub + " 不在本租户或已禁用");
                // 不静默丢弃：被 @ 的人永远不知道，发评论的人也以为叫到了人。
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "被 @ 的用户不是本租户的启用用户：@" + sub);
            }
            out.add(sub);
        }
        return out;
    }

    private String writeMentions(List<String> mentions) {
        try {
            return json.writeValueAsString(mentions);
        } catch (RuntimeException e) {
            throw new IllegalStateException("评论 mentions 序列化失败", e);
        }
    }

    private Map<String, Object> commentView(IncidentComment c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commentId", c.commentId());
        m.put("incidentId", c.incidentId());
        m.put("author", c.author());
        m.put("body", c.body());
        m.put("mentions", readMentions(c.mentionsJson()));
        m.put("createdAt", c.createdAt());
        return m;
    }

    private List<String> readMentions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode node = json.readTree(raw);
            if (node == null || !node.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && item.isString()) out.add(item.asString());
            }
            return out;
        } catch (RuntimeException e) {
            // 存储损坏不该把整页讨论打挂；点名信息缺失仍可读正文。
            return List.of();
        }
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
        if (reopening) {
            // 重开视为新的响应周期（ADR-0033 决策 6）：按当前优先级从重开时刻重算 due_at，
            // 并把 sla_state 重置为 ok。否则已 breached 的单重开后仍是终态，扫描器不再理会。
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
