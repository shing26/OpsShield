package com.opspilot.incident;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 通知访问层：每条语句都带 {@code tenant = ? AND recipient = ?}，两者只由调用方从 JWT 取。
 * 去重命中（唯一键冲突）返回 {@code false}，由 Service 当作"同一事件重放"吞掉。
 */
@Repository
public class IncidentNotificationStore {

    private static final Logger log = LoggerFactory.getLogger(IncidentNotificationStore.class);

    private static final RowMapper<IncidentNotification> MAPPER = (ResultSet rs, int i) ->
            new IncidentNotification(
                    rs.getLong("notification_id"),
                    rs.getString("tenant"),
                    rs.getString("recipient"),
                    rs.getString("incident_id"),
                    rs.getString("kind"),
                    rs.getString("title"),
                    rs.getString("priority"),
                    instant(rs, "due_at"),
                    rs.getString("dedupe_key"),
                    instant(rs, "created_at"),
                    instant(rs, "read_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbc;

    public IncidentNotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入一行；唯一键冲突返回 {@code false}（去重命中不是失败）。
     *
     * <p>刻意不先 SELECT 再 INSERT：先查后写会把唯一键变成"建议"，
     * 并发下仍要由数据库裁决；直接把冲突当正常分支更短也更准。
     */
    public boolean insert(IncidentNotification n) {
        try {
            return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) con -> {
                boolean autoCommit = con.getAutoCommit();
                Savepoint savepoint = null;
                try {
                    if (!autoCommit) savepoint = con.setSavepoint();
                    return jdbc.update("INSERT INTO incident_notifications "
                                    + "(tenant, recipient, incident_id, kind, title, priority, due_at, "
                                    + "dedupe_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            n.tenant(), n.recipient(), n.incidentId(), n.kind(), n.title(), n.priority(),
                            n.dueAt() == null ? null : Timestamp.from(n.dueAt()),
                            n.dedupeKey(), Timestamp.from(n.createdAt())) == 1;
                } catch (DuplicateKeyException e) {
                    // PostgreSQL 在唯一键冲突后会中止整个事务；回滚 savepoint 才能继续
                    // 写同一事务里的 outbox 行。H2 与 PostgreSQL 语义一致。
                    if (!autoCommit) rollbackQuietly(con, savepoint);
                    return false;
                } finally {
                    if (!autoCommit) releaseQuietly(con, savepoint);
                }
            }));
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private static void rollbackQuietly(Connection con, Savepoint savepoint) {
        if (savepoint == null) return;
        try {
            con.rollback(savepoint);
        } catch (SQLException e) {
            log.warn("notification savepoint rollback failed: {}", e.getSQLState());
        }
    }

    private static void releaseQuietly(Connection con, Savepoint savepoint) {
        if (savepoint == null) return;
        try {
            con.releaseSavepoint(savepoint);
        } catch (SQLException ignored) {
            // 某些驱动回滚后已释放 savepoint。
        }
    }

    /** 列表按 created_at DESC, notification_id DESC；recipient/tenant 永不来自请求。 */
    public List<IncidentNotification> list(String tenant, String recipient, boolean unreadOnly, int limit) {
        String sql = "SELECT * FROM incident_notifications WHERE tenant = ? AND recipient = ?"
                + (unreadOnly ? " AND read_at IS NULL" : "")
                + " ORDER BY created_at DESC, notification_id DESC LIMIT ?";
        return jdbc.query(sql, MAPPER, tenant, recipient, limit);
    }

    public int unreadCount(String tenant, String recipient) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications "
                + "WHERE tenant = ? AND recipient = ? AND read_at IS NULL", Integer.class, tenant, recipient);
        return n == null ? 0 : n;
    }

    /** 已读时间只写一次：{@code read_at IS NULL} 条件保证重复调用不覆盖首次时间。 */
    public int markRead(String tenant, String recipient, long id, Instant now) {
        return jdbc.update("UPDATE incident_notifications SET read_at = ? "
                        + "WHERE tenant = ? AND recipient = ? AND notification_id = ? AND read_at IS NULL",
                Timestamp.from(now), tenant, recipient, id);
    }

    /** 用于区分"已读（幂等 204）"与"不存在/非本人（404）"。 */
    public boolean exists(String tenant, String recipient, long id) {
        List<Long> ids = jdbc.queryForList("SELECT notification_id FROM incident_notifications "
                        + "WHERE tenant = ? AND recipient = ? AND notification_id = ?",
                Long.class, tenant, recipient, id);
        return !ids.isEmpty();
    }

    public int markAllRead(String tenant, String recipient, Instant now) {
        return jdbc.update("UPDATE incident_notifications SET read_at = ? "
                        + "WHERE tenant = ? AND recipient = ? AND read_at IS NULL",
                Timestamp.from(now), tenant, recipient);
    }

    /** 单次清理上限 5000 行：避免一次大删除长时间持锁。 */
    public int deleteOlderThan(Instant cutoff, int maxRows) {
        return jdbc.update("DELETE FROM incident_notifications WHERE notification_id IN ("
                        + "SELECT notification_id FROM incident_notifications "
                        + "WHERE created_at < ? ORDER BY created_at LIMIT ?)",
                Timestamp.from(cutoff), maxRows);
    }

    public int countFor(String tenant, String recipient) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications "
                + "WHERE tenant = ? AND recipient = ?", Integer.class, tenant, recipient);
        return n == null ? 0 : n;
    }
}
