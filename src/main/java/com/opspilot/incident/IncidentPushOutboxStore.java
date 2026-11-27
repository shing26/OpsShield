package com.opspilot.incident;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 外发 outbox 访问层（ADR-0045）。所有读写都带 {@code tenant} 的入口只接受调用方
 * 从 JWT 或事故行取得的 tenant；调度面按 outbox_id 与 status 操作，不跨租户拼接。
 */
@Repository
public class IncidentPushOutboxStore {

    private static final Logger log = LoggerFactory.getLogger(IncidentPushOutboxStore.class);

    private static final RowMapper<IncidentPushOutbox> MAPPER = (ResultSet rs, int i) ->
            new IncidentPushOutbox(
                    rs.getLong("outbox_id"),
                    rs.getString("tenant"),
                    rs.getString("event_key"),
                    rs.getString("incident_id"),
                    rs.getString("kind"),
                    rs.getString("recipient"),
                    rs.getString("priority"),
                    rs.getString("title"),
                    instant(rs, "due_at"),
                    rs.getString("channel_id"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    instant(rs, "next_attempt_at"),
                    instant(rs, "last_attempt_at"),
                    (Integer) rs.getObject("last_status", Integer.class),
                    rs.getString("last_error"),
                    instant(rs, "sent_at"),
                    instant(rs, "created_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbc;

    public IncidentPushOutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 入队；唯一键冲突返回 {@code false}（同一事件对同一频道的重放是 no-op）。
     *
     * <p>用 JDBC savepoint 包住 INSERT：PostgreSQL 在唯一键冲突后会中止整个事务，
     * 单纯 catch {@code DuplicateKeyException} 会让"重复入队"变成"后续语句全部失败"。
     * savepoint 回滚后事务仍可继续，H2 与 PostgreSQL 语义一致。
     */
    public boolean insertIfAbsent(IncidentPushOutbox row) {
        try {
            return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) con -> {
                Savepoint savepoint = null;
                boolean autoCommit = con.getAutoCommit();
                try {
                    // 自动提交模式下语句失败不会污染后续语句，也不需要 savepoint；
                    // 生产路径在业务事务内（autoCommit=false），才用 savepoint 隔离唯一键冲突。
                    if (!autoCommit) savepoint = con.setSavepoint();
                    try (PreparedStatement ps = con.prepareStatement(
                            "INSERT INTO incident_push_outbox "
                                    + "(tenant, event_key, incident_id, kind, recipient, priority, title, "
                                    + "due_at, channel_id, status, attempts, next_attempt_at, created_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        ps.setString(1, row.tenant());
                        ps.setString(2, row.eventKey());
                        ps.setString(3, row.incidentId());
                        ps.setString(4, row.kind());
                        ps.setString(5, nz(row.recipient()));
                        ps.setString(6, nz(row.priority()));
                        ps.setString(7, row.title());
                        ps.setTimestamp(8, row.dueAt() == null ? null : Timestamp.from(row.dueAt()));
                        ps.setString(9, row.channelId());
                        ps.setString(10, IncidentPushOutbox.STATUS_PENDING);
                        ps.setInt(11, row.attempts());
                        ps.setTimestamp(12, Timestamp.from(row.nextAttemptAt()));
                        ps.setTimestamp(13, Timestamp.from(row.createdAt()));
                        return ps.executeUpdate() == 1;
                    }
                } catch (SQLException e) {
                    if (isUniqueViolation(e)) {
                        if (!autoCommit) rollbackQuietly(con, savepoint);
                        return false;
                    }
                    throw e;
                } finally {
                    if (!autoCommit) releaseQuietly(con, savepoint);
                }
            }));
        } catch (DataAccessException e) {
            throw e;
        }
    }

    /** 到期 pending：按 outbox_id 升序，保证同一事件的入队顺序不被随机化。 */
    public List<IncidentPushOutbox> findDue(Instant now, int limit) {
        return jdbc.query("SELECT * FROM incident_push_outbox "
                        + "WHERE status = ? AND next_attempt_at <= ? ORDER BY outbox_id LIMIT ?",
                MAPPER, IncidentPushOutbox.STATUS_PENDING, Timestamp.from(now), limit);
    }

    /** 单行认领：只有仍为 pending 的行能被本次调度拿走。 */
    public int claim(long id, Instant now, Instant nextAttemptAt) {
        return jdbc.update("UPDATE incident_push_outbox "
                        + "SET attempts = attempts + 1, last_attempt_at = ?, next_attempt_at = ? "
                        + "WHERE outbox_id = ? AND status = ?",
                Timestamp.from(now), Timestamp.from(nextAttemptAt), id,
                IncidentPushOutbox.STATUS_PENDING);
    }

    public int markSent(long id, int statusCode, Instant now) {
        return jdbc.update("UPDATE incident_push_outbox SET status = ?, last_status = ?, "
                        + "last_error = NULL, sent_at = ?, next_attempt_at = ? "
                        + "WHERE outbox_id = ? AND status = ?",
                IncidentPushOutbox.STATUS_SENT, statusCode, Timestamp.from(now), Timestamp.from(now),
                id, IncidentPushOutbox.STATUS_PENDING);
    }

    public int markRetry(long id, Integer statusCode, String error, Instant nextAttemptAt) {
        return jdbc.update("UPDATE incident_push_outbox SET status = ?, last_status = ?, "
                        + "last_error = ?, next_attempt_at = ? WHERE outbox_id = ? AND status = ?",
                IncidentPushOutbox.STATUS_PENDING, statusCode, error, Timestamp.from(nextAttemptAt),
                id, IncidentPushOutbox.STATUS_PENDING);
    }

    public int markDead(long id, Integer statusCode, String error, Instant now) {
        return jdbc.update("UPDATE incident_push_outbox SET status = ?, last_status = ?, "
                        + "last_error = ?, next_attempt_at = ? WHERE outbox_id = ? AND status = ?",
                IncidentPushOutbox.STATUS_DEAD, statusCode, error, Timestamp.from(now),
                id, IncidentPushOutbox.STATUS_PENDING);
    }

    public Optional<IncidentPushOutbox> find(String tenant, long id) {
        List<IncidentPushOutbox> rows = jdbc.query(
                "SELECT * FROM incident_push_outbox WHERE tenant = ? AND outbox_id = ?",
                MAPPER, tenant, id);
        return rows.stream().findFirst();
    }

    public List<IncidentPushOutbox> list(String tenant, String status, String channel, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM incident_push_outbox WHERE tenant = ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenant);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (channel != null && !channel.isBlank()) {
            sql.append(" AND channel_id = ?");
            args.add(channel);
        }
        sql.append(" ORDER BY outbox_id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 手动重试：只重置 dead/pending；sent 不会被改回 pending。 */
    public int resetForRetry(String tenant, long id, Instant now) {
        return jdbc.update("UPDATE incident_push_outbox SET status = ?, attempts = 0, "
                        + "next_attempt_at = ? WHERE tenant = ? AND outbox_id = ? "
                        + "AND status IN (?, ?)",
                IncidentPushOutbox.STATUS_PENDING, Timestamp.from(now), tenant, id,
                IncidentPushOutbox.STATUS_DEAD, IncidentPushOutbox.STATUS_PENDING);
    }

    public int countPending(String channel) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM incident_push_outbox "
                        + "WHERE channel_id = ? AND status = ?",
                Integer.class, channel, IncidentPushOutbox.STATUS_PENDING);
        return n == null ? 0 : n;
    }

    /** 只删 sent/dead；pending 永不进入清理范围。单次上限避免长时间持锁。 */
    public int deleteTerminalOlderThan(Instant cutoff, int maxRows) {
        return jdbc.update("DELETE FROM incident_push_outbox WHERE outbox_id IN ("
                        + "SELECT outbox_id FROM incident_push_outbox "
                        + "WHERE status IN (?, ?) AND created_at < ? "
                        + "ORDER BY created_at LIMIT ?)",
                IncidentPushOutbox.STATUS_SENT, IncidentPushOutbox.STATUS_DEAD,
                Timestamp.from(cutoff), maxRows);
    }

    public int countForTenant(String tenant) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM incident_push_outbox WHERE tenant = ?",
                Integer.class, tenant);
        return n == null ? 0 : n;
    }

    private static boolean isUniqueViolation(SQLException e) {
        String state = e.getSQLState();
        return "23505".equals(state) || "23001".equals(state);
    }

    private static void rollbackQuietly(Connection con, Savepoint savepoint) {
        if (savepoint == null) return;
        try {
            con.rollback(savepoint);
        } catch (SQLException e) {
            log.warn("outbox savepoint rollback failed: {}", e.getSQLState());
        }
    }

    private static void releaseQuietly(Connection con, Savepoint savepoint) {
        if (savepoint == null) return;
        try {
            con.releaseSavepoint(savepoint);
        } catch (SQLException ignored) {
            // 某些驱动在回滚后已释放 savepoint；释放失败不影响事务继续。
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
