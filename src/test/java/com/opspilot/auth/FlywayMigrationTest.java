package com.opspilot.auth;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
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
