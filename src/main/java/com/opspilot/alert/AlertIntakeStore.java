package com.opspilot.alert;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 告警台账访问层（ADR-0035）：所有语句都带 {@code tenant = ?}。
 *
 * <p>写入用「先条件 UPDATE、未命中再 INSERT」而不是 H2 的 {@code MERGE}：生产是 PostgreSQL，
 * {@code MERGE ... KEY} 不是可移植语法；并发首次插入撞主键时由调用方重试一次 UPDATE 即可幂等。
 */
@Repository
public class AlertIntakeStore {

    private static final RowMapper<AlertIntake> MAPPER = (ResultSet rs, int i) -> new AlertIntake(
            rs.getString("tenant"),
            rs.getString("alert_id"),
            rs.getString("trace_id"),
            rs.getString("service"),
            rs.getString("env"),
            rs.getString("error_code"),
            rs.getString("summary"),
            rs.getString("alert_status"),
            instant(rs, "first_seen"),
            instant(rs, "last_seen"),
            rs.getInt("seen_count"),
            rs.getString("incident_id"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private final JdbcTemplate jdbc;

    public AlertIntakeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AlertIntake> find(String tenant, String alertId) {
        List<AlertIntake> rows = jdbc.query(
                "SELECT * FROM alert_intake WHERE tenant = ? AND alert_id = ?", MAPPER, tenant, alertId);
        return rows.stream().findFirst();
    }

    /**
     * 已存在则计数 +1 并刷新可变量；返回受影响行数（0 表示尚未存在）。
     *
     * <p>所有可刷新列都用 {@code COALESCE(?, col)}：调用方把「缺席」传成 null 时必须保留旧值，
     * 否则后续不带某字段的告警请求会把台账逐字段刷成空串（P1-1；trace_id 尤其关键，
     * 它是告警与审计的关联键）。
     */
    public int touch(String tenant, String alertId, String traceId, String summary, String service,
                     String env, String errorCode, String alertStatus, Instant now) {
        return jdbc.update("UPDATE alert_intake SET seen_count = seen_count + 1, last_seen = ?, "
                        + "trace_id = COALESCE(?, trace_id), summary = COALESCE(?, summary), "
                        + "service = COALESCE(?, service), env = COALESCE(?, env), "
                        + "error_code = COALESCE(?, error_code), alert_status = COALESCE(?, alert_status) "
                        + "WHERE tenant = ? AND alert_id = ?",
                Timestamp.from(now), traceId, summary, service, env, errorCode, alertStatus,
                tenant, alertId);
    }

    public void insert(AlertIntake row) {
        jdbc.update("INSERT INTO alert_intake (tenant, alert_id, trace_id, service, env, error_code, "
                        + "summary, alert_status, first_seen, last_seen, seen_count, incident_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.tenant(), row.alertId(), row.traceId(), row.service(), row.env(), row.errorCode(),
                row.summary(), row.alertStatus(), Timestamp.from(row.firstSeen()),
                Timestamp.from(row.lastSeen()), row.seenCount(), row.incidentId());
    }

    /**
     * 面板增量读取：跨租户、按 {@code last_seen DESC}。{@code since} 是 last_seen 的毫秒水位；
     * 首轮传 0。排序尾键用 alert_id，保证同一毫秒内的行顺序稳定。
     */
    public List<AlertIntake> recent(long sinceMs, int limit) {
        return jdbc.query("SELECT * FROM alert_intake WHERE last_seen > ? "
                        + "ORDER BY last_seen DESC, alert_id DESC LIMIT ?",
                MAPPER, Timestamp.from(Instant.ofEpochMilli(Math.max(0L, sinceMs))), limit);
    }

    /**
     * 历史键集分页：排序键 {@code (last_seen DESC, tenant DESC, alert_id DESC)}。
     *
     * <p>不拼 OFFSET；游标比较展开成布尔表达式，H2 与 PostgreSQL 都不依赖 row-value 比较。
     * 时间参数取游标里的微秒值，边界与数据库回读的 {@code last_seen} 同精度。
     */
    public List<AlertIntake> history(AlertHistoryCursor after, int fetch) {
        StringBuilder sql = new StringBuilder("SELECT * FROM alert_intake");
        List<Object> args = new ArrayList<>();
        if (after != null) {
            sql.append(" WHERE last_seen < ? OR (last_seen = ? AND (tenant < ?"
                    + " OR (tenant = ? AND alert_id < ?)))");
            Timestamp at = Timestamp.from(after.lastSeen());
            args.add(at);
            args.add(at);
            args.add(after.tenant());
            args.add(after.tenant());
            args.add(after.alertId());
        }
        sql.append(" ORDER BY last_seen DESC, tenant DESC, alert_id DESC LIMIT ?");
        args.add(fetch);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public int markIncident(String tenant, String alertId, String incidentId) {
        return jdbc.update("UPDATE alert_intake SET incident_id = ? WHERE tenant = ? AND alert_id = ?",
                incidentId, tenant, alertId);
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM alert_intake WHERE last_seen < ?", Timestamp.from(cutoff));
    }

    public List<String> tenants() {
        return jdbc.queryForList("SELECT DISTINCT tenant FROM alert_intake ORDER BY tenant", String.class);
    }

    /**
     * 每租户只保留最近 {@code maxPerTenant} 条。用派生表包住 LIMIT：H2（PostgreSQL 兼容模式）
     * 与 PostgreSQL 都要求子查询在 IN 里有确定的列与别名，直接子查询写 LIMIT 在两者上都能跑，
     * 但多包一层可避免部分版本对「DELETE 同表 + LIMIT 子查询」的解析差异。
     */
    public int deleteOverflow(String tenant, int maxPerTenant) {
        return jdbc.update("DELETE FROM alert_intake WHERE tenant = ? AND alert_id NOT IN ("
                        + "SELECT alert_id FROM (SELECT alert_id FROM alert_intake WHERE tenant = ? "
                        + "ORDER BY last_seen DESC, alert_id DESC LIMIT ?) keep)",
                tenant, tenant, maxPerTenant);
    }
}
