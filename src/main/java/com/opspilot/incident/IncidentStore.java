package com.opspilot.incident;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 事故访问层：所有语句都带 {@code tenant = ?}，tenant 由调用方从 JWT 取，永不来自请求体。
 * 更新走"整行写入"：Service 先读出当前行、合并补丁、再写回——字段少，动态 SQL 的收益不抵它的风险。
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
            rs.getString("timeline_json"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbc;

    public IncidentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Incident> list(String tenant, int limit) {
        return jdbc.query("SELECT * FROM incidents WHERE tenant = ? "
                + "ORDER BY updated_at DESC, incident_id DESC LIMIT ?", MAPPER, tenant, limit);
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
     * 插入。ID 由服务端生成（客户端只提供 clientId 做幂等键），冲突时重试新 ID；
     * 若是 (tenant, client_id) 唯一键冲突则交由 Service 回查既有记录。
     */
    public Incident insert(Incident draft) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String id = newId();
            try {
                jdbc.update("INSERT INTO incidents (incident_id, tenant, client_id, title, query, service, "
                                + "error_code, env, alert_id, source, priority, status, owner, created_by, "
                                + "created_at, updated_at, diagnosis_json, timeline_json) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        id, draft.tenant(), draft.clientId(), draft.title(), draft.query(), draft.service(),
                        draft.errorCode(), draft.env(), draft.alertId(), draft.source(), draft.priority(),
                        draft.status(), draft.owner(), draft.createdBy(),
                        Timestamp.from(draft.createdAt()), Timestamp.from(draft.updatedAt()),
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
                        + "alert_id = ?, priority = ?, status = ?, owner = ?, closed_at = ?, closed_by = ?, "
                        + "close_note = ?, diagnosis_json = ?, timeline_json = ?, updated_at = ? "
                        + "WHERE tenant = ? AND incident_id = ?",
                row.title(), row.query(), row.service(), row.errorCode(), row.env(), row.alertId(),
                row.priority(), row.status(), row.owner(),
                row.closedAt() == null ? null : Timestamp.from(row.closedAt()),
                row.closedBy(), row.closeNote(),
                row.diagnosisJson(), row.timelineJson(), Timestamp.from(row.updatedAt()),
                row.tenant(), row.id()) == 1;
    }

    private String newId() {
        return "INC-" + ID_DAY.format(Instant.now()) + "-"
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
    }
}
