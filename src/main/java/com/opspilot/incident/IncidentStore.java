package com.opspilot.incident;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 事故访问层：所有语句都带 {@code tenant = ?}，tenant 由调用方从 JWT 取，永不来自请求体。
 * 更新走"整行写入"：Service 先读出当前行、合并补丁、再写回——字段少，动态 SQL 的收益不抵它的风险。
 *
 * <p>责任转交的整行更新 + 追加记录由调用方（{@link IncidentService}）的事务包住；
 * 这里对 owner / sla_state 做条件更新（带旧值），并发下宁可 409 也不静默覆盖。
 */
@Repository
public class IncidentStore {

    private static final DateTimeFormatter ID_DAY = DateTimeFormatter.ofPattern("MMdd")
            .withZone(ZoneId.systemDefault());

    private static final RowMapper<Incident> MAPPER = (ResultSet rs, int i) -> new Incident(
            rs.getString("incident_id"),
            rs.getString("tenant"),
            rs.getString("client_id"),
            rs.getString("title"),
            rs.getString("query"),
            rs.getString("service"),
            rs.getString("error_code"),
            rs.getString("env"),
            rs.getString("alert_id"),
            rs.getString("source"),
            rs.getString("priority"),
            rs.getString("status"),
            rs.getString("owner"),
            rs.getString("created_by"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            instant(rs, "closed_at"),
            rs.getString("closed_by"),
            rs.getString("close_note"),
            rs.getString("diagnosis_json"),
            rs.getString("timeline_json"),
            instant(rs, "due_at"),
            rs.getString("sla_state"));

    private static final RowMapper<IncidentAssignment> ASSIGNMENT_MAPPER = (ResultSet rs, int i) ->
            new IncidentAssignment(
                    rs.getString("from_owner"),
                    rs.getString("to_owner"),
                    rs.getString("reason"),
                    rs.getString("assigned_by"),
                    instant(rs, "assigned_at"));

    private static final RowMapper<IncidentComment> COMMENT_MAPPER = (ResultSet rs, int i) ->
            new IncidentComment(
                    rs.getLong("comment_id"),
                    rs.getString("tenant"),
                    rs.getString("incident_id"),
                    rs.getString("author"),
                    rs.getString("body"),
                    rs.getString("mentions_json"),
                    instant(rs, "created_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbc;

    public IncidentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 无参时行为与历史版本完全一致：本租户最近 limit 条，按 updated_at 倒序。 */
    public List<Incident> list(String tenant, int limit) {
        return list(tenant, limit, null, null);
    }

    /**
     * 服务端筛选：owner / status 都是静态 SQL 片段 + 绑定参数，tenant 永远来自 JWT。
     * mine=true 由调用方把 owner 设为调用者 sub；前端不得用本地列表冒充筛选结果。
     */
    public List<Incident> list(String tenant, int limit, String owner, String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM incidents WHERE tenant = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenant);
        if (owner != null) {
            sql.append(" AND owner = ?");
            args.add(owner);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY updated_at DESC, incident_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<Incident> find(String tenant, String id) {
        List<Incident> rows = jdbc.query(
                "SELECT * FROM incidents WHERE tenant = ? AND incident_id = ?", MAPPER, tenant, id);
        return rows.stream().findFirst();
    }

    public Optional<Incident> findByClientId(String tenant, String clientId) {
        List<Incident> rows = jdbc.query(
                "SELECT * FROM incidents WHERE tenant = ? AND client_id = ?", MAPPER, tenant, clientId);
        return rows.stream().findFirst();
    }

    /**
     * SLA 扫描的租户枚举：系统任务没有调用方 JWT，只能先把"有开口到期单据"的租户键取出来，
     * 再逐租户带 {@code tenant = ?} 读取。这是本切片唯一的跨租户读，且只读 tenant 列。
     */
    public List<String> tenantsWithOpenDue() {
        return jdbc.queryForList("SELECT DISTINCT tenant FROM incidents "
                        + "WHERE status = ? AND due_at IS NOT NULL ORDER BY tenant",
                String.class, Incident.STATUS_OPEN);
    }

    /** 扫描专用：只取未结单且有 due_at 的行；tenant 必须由 {@link #tenantsWithOpenDue()} 给出。 */
    public List<Incident> listOpenDue(String tenant) {
        return jdbc.query("SELECT * FROM incidents WHERE tenant = ? AND status = ? AND due_at IS NOT NULL",
                MAPPER, tenant, Incident.STATUS_OPEN);
    }

    /** 转交历史只从 append-only 表读，绝不从客户端时间线拼。 */
    public List<IncidentAssignment> assignments(String tenant, String incidentId, int limit) {
        return jdbc.query("SELECT from_owner, to_owner, reason, assigned_by, assigned_at "
                        + "FROM incident_assignments WHERE tenant = ? AND incident_id = ? "
                        + "ORDER BY assigned_at DESC, id DESC LIMIT ?",
                ASSIGNMENT_MAPPER, tenant, incidentId, limit);
    }

    /**
     * 评论只做 INSERT：不读改写既有行，因此并发追加不会互相覆盖（ADR-0034）。
     * 返回数据库生成的自增 id，作为前端未读水位的单调键。
     */
    public long insertComment(String tenant, String incidentId, String author, String body,
                              String mentionsJson, Instant createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update((java.sql.Connection con) -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO incident_comments (tenant, incident_id, author, body, mentions_json, "
                            + "created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, tenant);
            ps.setString(2, incidentId);
            ps.setString(3, author);
            ps.setString(4, body);
            ps.setString(5, mentionsJson);
            ps.setTimestamp(6, Timestamp.from(createdAt));
            return ps;
        }, keys);
        // H2 会把带 DEFAULT 的列（created_at）也放进 generated keys，getKey() 因「多键」直接抛错；
        // 这里按列名取主键，PostgreSQL 驱动返回单键时同样适用。
        Map<String, Object> all = keys.getKeys();
        Object generated = all.get("COMMENT_ID");
        if (generated == null) generated = all.get("comment_id");
        if (generated == null && all.size() == 1) generated = all.values().iterator().next();
        if (!(generated instanceof Number n)) throw new IllegalStateException("评论插入未返回自增 id");
        return n.longValue();
    }

    /** 单事故评论数：供 200 条上限判定；用聚合查询，不把正文读回内存。 */
    public int countComments(String tenant, String incidentId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM incident_comments "
                + "WHERE tenant = ? AND incident_id = ?", Integer.class, tenant, incidentId);
        return n == null ? 0 : n;
    }

    /** 增量读取：comment_id 是严格单调水位，按 (created_at, comment_id) 升序给出。 */
    public List<IncidentComment> listComments(String tenant, String incidentId, long since, int limit) {
        return jdbc.query("SELECT comment_id, tenant, incident_id, author, body, mentions_json, created_at "
                        + "FROM incident_comments WHERE tenant = ? AND incident_id = ? AND comment_id > ? "
                        + "ORDER BY created_at ASC, comment_id ASC LIMIT ?",
                COMMENT_MAPPER, tenant, incidentId, since, limit);
    }

    /** 左栏未读角标用：每张单当前最大 commentId；只返回 id，不读正文。 */
    public Map<String, Long> lastCommentIds(String tenant) {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT incident_id, MAX(comment_id) AS max_id FROM incident_comments "
                        + "WHERE tenant = ? GROUP BY incident_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        out.put(rs.getString("incident_id"), rs.getLong("max_id")), tenant);
        return out;
    }

    /**
     * 插入。ID 由服务端生成（客户端只提供 clientId 做幂等键），冲突时重试新 ID；
     * 若是 (tenant, client_id) 唯一键冲突则交由 Service 回查既有记录。
     */
    public Incident insert(Incident draft) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String id = newId();
            try {
                jdbc.update("INSERT INTO incidents (incident_id, tenant, client_id, title, query, service, "
                                + "error_code, env, alert_id, source, priority, status, owner, created_by, "
                                + "created_at, updated_at, due_at, sla_state, diagnosis_json, timeline_json) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        id, draft.tenant(), draft.clientId(), draft.title(), draft.query(), draft.service(),
                        draft.errorCode(), draft.env(), draft.alertId(), draft.source(), draft.priority(),
                        draft.status(), draft.owner(), draft.createdBy(),
                        Timestamp.from(draft.createdAt()), Timestamp.from(draft.updatedAt()),
                        draft.dueAt() == null ? null : Timestamp.from(draft.dueAt()), draft.slaState(),
                        draft.diagnosisJson(), draft.timelineJson());
                return find(draft.tenant(), id).orElseThrow();
            } catch (DuplicateKeyException e) {
                // 可能是主键撞车，也可能是同 clientId 的并发重放：调用方回查后决定
                if (findByClientId(draft.tenant(), draft.clientId()).isPresent()) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("事故 ID 连续冲突，拒绝静默覆盖");
    }

    public boolean update(Incident row) {
        return jdbc.update("UPDATE incidents SET title = ?, query = ?, service = ?, error_code = ?, env = ?, "
                        + "alert_id = ?, priority = ?, status = ?, owner = ?, due_at = ?, sla_state = ?, "
                        + "closed_at = ?, closed_by = ?, close_note = ?, diagnosis_json = ?, timeline_json = ?, "
                        + "updated_at = ? WHERE tenant = ? AND incident_id = ?",
                row.title(), row.query(), row.service(), row.errorCode(), row.env(), row.alertId(),
                row.priority(), row.status(), row.owner(),
                row.dueAt() == null ? null : Timestamp.from(row.dueAt()), row.slaState(),
                row.closedAt() == null ? null : Timestamp.from(row.closedAt()),
                row.closedBy(), row.closeNote(),
                row.diagnosisJson(), row.timelineJson(), Timestamp.from(row.updatedAt()),
                row.tenant(), row.id()) == 1;
    }

    /**
     * 转交的唯一写入口：条件更新 owner（带旧 owner 防并发覆盖）+ 追加一行转交事实。
     * 两条语句必须由调用方事务包住；任一失败返回 false，Service 抛 409 且不回写前端状态。
     */
    public boolean transfer(Incident row, String expectedOwner, String reason, String assignedBy,
                            Instant assignedAt) {
        // SQL 三值逻辑：owner = NULL 恒为 UNKNOWN，旧 owner 为空时必须用 IS NULL，
        // 否则未指派单（含刚被「取消分配」的单）会永久 409。条件本身不能去掉——那是并发保护。
        String ownerPredicate = expectedOwner == null ? " AND owner IS NULL" : " AND owner = ?";
        List<Object> args = new ArrayList<>(Arrays.asList(
                row.title(), row.query(), row.service(), row.errorCode(), row.env(), row.alertId(),
                row.priority(), row.status(), row.owner(),
                row.dueAt() == null ? null : Timestamp.from(row.dueAt()), row.slaState(),
                row.closedAt() == null ? null : Timestamp.from(row.closedAt()),
                row.closedBy(), row.closeNote(),
                row.diagnosisJson(), row.timelineJson(), Timestamp.from(row.updatedAt()),
                row.tenant(), row.id()));
        if (expectedOwner != null) args.add(expectedOwner);
        int updated = jdbc.update("UPDATE incidents SET title = ?, query = ?, service = ?, error_code = ?, "
                        + "env = ?, alert_id = ?, priority = ?, status = ?, owner = ?, due_at = ?, sla_state = ?, "
                        + "closed_at = ?, closed_by = ?, close_note = ?, diagnosis_json = ?, timeline_json = ?, "
                        + "updated_at = ? WHERE tenant = ? AND incident_id = ?" + ownerPredicate,
                args.toArray());
        if (updated != 1) return false;
        jdbc.update("INSERT INTO incident_assignments (tenant, incident_id, from_owner, to_owner, reason, "
                        + "assigned_by, assigned_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                row.tenant(), row.id(), expectedOwner, row.owner(), reason, assignedBy,
                Timestamp.from(assignedAt));
        return true;
    }

    /** SLA 状态跃迁也走条件更新（带旧状态），保证同一次跃迁只落一条审计。 */
    public boolean transitionSla(String tenant, String incidentId, String fromState, String toState,
                                 Instant now) {
        return jdbc.update("UPDATE incidents SET sla_state = ?, updated_at = ? "
                        + "WHERE tenant = ? AND incident_id = ? AND sla_state = ? AND status = ?",
                toState, Timestamp.from(now), tenant, incidentId, fromState, Incident.STATUS_OPEN) == 1;
    }

    private String newId() {
        return "INC-" + ID_DAY.format(Instant.now()) + "-"
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
