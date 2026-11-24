package com.opspilot.auth;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1 存储迁移锁（2026-10-22）：Flyway 必须在**空库**建表，在**已有库**幂等执行 V1
 * （CREATE TABLE IF NOT EXISTS，不丢数据）并留下版本记录。
 *
 * <p>第二种形态是升级安全的关键：现网库已经由旧 schema.sql 建过 users；V1 重放不能破坏数据，
 * 且 flyway_schema_history 必须记录版本 1，后续 V2 加列/回滚才有可预期基线。
 */
class FlywayMigrationTest {

    @TempDir
    Path tmp;

    private DataSource ds(String name) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + tmp.resolve(name).toAbsolutePath().toString().replace('\\', '/'));
        ds.setUser("opspilot");
        ds.setPassword("test-pass");
        return ds;
    }

    private static Flyway flyway(DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    /** 读 incident_comments.body 的声明长度：不用 INFORMATION_SCHEMA，免踩 H2/PG 大小写与 schema 差异。 */
    private static int bodyColumnWidth(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT body FROM incident_comments WHERE 1 = 0")) {
            ResultSetMetaData md = rs.getMetaData();
            return md.getPrecision(1);
        }
    }

    @Test
    void v6UpgradesExistingV5DataAndAddsAssignmentTable() throws Exception {
        DataSource ds = ds("v5-to-v6");
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target("5")
                .load()
                .migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO incidents (incident_id, tenant, client_id, title, query, source, status,"
                    + " created_by) VALUES ('INC-V5-1', 'tenant-1', 'cid-v5', '旧单', '排查', 'manual',"
                    + " 'open', 't1-alice')");
        }

        flyway(ds).migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // V6 只新增表与可空/有默认值的列：旧行必须保留且自动获得 sla_state='ok'。
            try (ResultSet rs = st.executeQuery(
                    "SELECT due_at, sla_state FROM incidents WHERE incident_id = 'INC-V5-1'")) {
                assertTrue(rs.next(), "V6 升级不得丢已有事故行");
                assertNull(rs.getTimestamp("due_at"));
                assertEquals("ok", rs.getString("sla_state"));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM incident_assignments")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V6 必须建出 append-only 转交表");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '6' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "已有 V5 库升级后必须留下 V6 版本记录");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM incident_comments")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V7 必须建出 append-only 评论表");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '7' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "已有库升级后必须留下 V7 版本记录");
            }
        }
    }

    @Test
    void emptyDatabaseGetsUsersTableAndHistory() throws Exception {
        DataSource ds = ds("empty");
        flyway(ds).migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT \"version\", \"success\" FROM \"flyway_schema_history\" WHERE \"version\" = '1'")) {
                assertTrue(rs.next(), "空库必须由 V1 建表并留下版本记录");
                assertEquals(1, rs.getInt("success"));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM incident_comments")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "空库必须由 V7 建出评论表");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '7' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "空库必须留下 V7 版本记录");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '8' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "空库一次跑完必须留下 V8 版本记录");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM alert_intake")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "空库必须由 V9 建出告警台账表");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '9' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "空库一次跑完必须留下 V9 版本记录");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM incident_notifications")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "空库必须由 V10 建出通知表");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '10' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "空库一次跑完必须留下 V10 版本记录");
            }
        }
        assertEquals(4000, bodyColumnWidth(ds), "空库跑完 V1..V9 后 body 应为 VARCHAR(4000)");
    }

    /** V9 库升级到 V10：只新增通知表，既有事故/评论/台账数据必须原样保留。 */
    @Test
    void v10AddsNotificationsOnExistingV9Database() throws Exception {
        DataSource ds = ds("v9-to-v10");
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target("9")
                .load()
                .migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO incidents (incident_id, tenant, client_id, title, query, source, status,"
                    + " created_by) VALUES ('INC-V9-1', 'tenant-1', 'cid-v9', 'V9 旧单', '排查', 'manual',"
                    + " 'open', 't1-alice')");
        }

        flyway(ds).migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT title FROM incidents WHERE incident_id = 'INC-V9-1'")) {
                assertTrue(rs.next(), "V10 升级不得丢已有事故行");
                assertEquals("V9 旧单", rs.getString("title"));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM incident_notifications")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V10 必须建出空的通知表");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '10' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "已有 V9 库升级后必须留下 V10 版本记录");
            }
            // 唯一索引必须真的存在：同一 (tenant, recipient, dedupe_key) 第二次插入应被拒。
            st.execute("INSERT INTO incident_notifications (tenant, recipient, incident_id, kind, title,"
                    + " dedupe_key) VALUES ('tenant-1', 't1-alice', 'INC-V9-1', 'assigned', 'V9 旧单', 'k-1')");
            boolean rejected = false;
            try {
                st.execute("INSERT INTO incident_notifications (tenant, recipient, incident_id, kind, title,"
                        + " dedupe_key) VALUES ('tenant-1', 't1-alice', 'INC-V9-1', 'assigned', 'V9 旧单', 'k-1')");
            } catch (java.sql.SQLException e) {
                rejected = true;
            }
            assertTrue(rejected, "V10 必须有 (tenant, recipient, dedupe_key) 唯一索引");
        }
    }

    /** V8 库升级到 V9：只新增告警台账表，既有事故/评论/转交数据必须原样保留。 */
    @Test
    void v9AddsAlertIntakeOnExistingV8Database() throws Exception {
        DataSource ds = ds("v8-to-v9");
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target("8")
                .load()
                .migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO incidents (incident_id, tenant, client_id, title, query, source, status,"
                    + " created_by) VALUES ('INC-V8-1', 'tenant-1', 'cid-v8', 'V8 旧单', '排查', 'manual',"
                    + " 'open', 't1-alice')");
            st.execute("INSERT INTO incident_comments (tenant, incident_id, author, body)"
                    + " VALUES ('tenant-1', 'INC-V8-1', 't1-alice', 'V8 评论')");
        }

        flyway(ds).migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT title FROM incidents WHERE incident_id = 'INC-V8-1'")) {
                assertTrue(rs.next(), "V9 升级不得丢已有事故行");
                assertEquals("V8 旧单", rs.getString("title"));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM incident_comments")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "V9 升级不得丢已有评论");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM alert_intake")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V9 必须建出空的告警台账表");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '9' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "已有 V8 库升级后必须留下 V9 版本记录");
            }
        }
    }

    /**
     * 升级路径（Verifier 复现的那条）：库已由 0cc2ab6 跑到 V1..V7（body VARCHAR(2000)），
     * 新 HEAD 再启动时只能补 V8——不得因 V7 checksum 变化抛 mismatch，且列宽要从 2000 变 4000。
     */
    @Test
    void v8WidensBodyOnDatabaseAlreadyMigratedToOldV7() throws Exception {
        DataSource ds = ds("v7-old-width-to-v8");
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target("7")
                .load()
                .migrate();

        assertEquals(2000, bodyColumnWidth(ds), "旧版 V7 建出的 body 应为 VARCHAR(2000)");

        // V7 文件与 0cc2ab6 逐字一致，所以这里不会撞 checksum；只应多应用 V8。
        flyway(ds).migrate();

        assertEquals(4000, bodyColumnWidth(ds), "V8 必须把 body 加宽到 4000");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '7' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "升级后 V7 历史必须保留并通过校验");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '8' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "升级路径必须留下 V8 版本记录");
            }
            // 真正写一条最坏情况正文：2000 个表情 = 4000 个 UTF-16 码元。
            String body = "😀".repeat(2000);
            assertEquals(4000, body.length(), "构造的正文应为 4000 个 UTF-16 码元");
            st.execute("INSERT INTO incident_comments (tenant, incident_id, author, body)"
                    + " VALUES ('tenant-1', 'INC-V7-1', 't1-alice', '" + body + "')");
            try (ResultSet rs = st.executeQuery("SELECT body FROM incident_comments"
                    + " WHERE tenant = 'tenant-1' AND incident_id = 'INC-V7-1'")) {
                assertTrue(rs.next(), "加宽后必须能写入 2000 个表情的正文");
                assertEquals(body, rs.getString(1));
            }
        }
    }

    /** V6 库升级到 V7：只新增一张表，既有事故与转交数据必须原样保留。 */
    @Test
    void v7UpgradesExistingV6DatabaseWithoutDataLoss() throws Exception {
        DataSource ds = ds("v6-to-v7");
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target("6")
                .load()
                .migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO incidents (incident_id, tenant, client_id, title, query, source, status,"
                    + " created_by) VALUES ('INC-V6-1', 'tenant-1', 'cid-v6', 'V6 旧单', '排查', 'manual',"
                    + " 'open', 't1-alice')");
            st.execute("INSERT INTO incident_assignments (tenant, incident_id, from_owner, to_owner, reason,"
                    + " assigned_by) VALUES ('tenant-1', 'INC-V6-1', 't1-alice', 't1-bob', '交接', 't1-alice')");
        }

        flyway(ds).migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT title FROM incidents WHERE incident_id = 'INC-V6-1'")) {
                assertTrue(rs.next(), "V7 升级不得丢已有事故行");
                assertEquals("V6 旧单", rs.getString("title"));
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM incident_assignments")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "V7 升级不得丢已有转交事实");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM incident_comments")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "V7 必须建出空的评论表");
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '7' AND \"success\"")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "已有 V6 库升级后必须留下 V7 版本记录");
            }
        }
    }

    @Test
    void existingDatabaseKeepsDataAndRecordsVersion() throws Exception {
        DataSource ds = ds("legacy");
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE users ("
                    + "sub VARCHAR(64) PRIMARY KEY, pass_bcrypt VARCHAR(100) NOT NULL,"
                    + " tenant VARCHAR(64) NOT NULL, auth_level INT NOT NULL,"
                    + " role VARCHAR(32) NOT NULL, disabled BOOLEAN NOT NULL DEFAULT FALSE,"
                    + " token_ver INT NOT NULL DEFAULT 1,"
                    + " updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }

        flyway(ds).migrate();

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            // V1 是 CREATE TABLE IF NOT EXISTS：已有库上重放也必须是 no-op（不丢数据），
            // 且版本记录必须留下——这是后续 V2 加列/回滚可预期性的前提。
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\" = '1'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "已有库必须留下 V1 版本记录");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }
}
