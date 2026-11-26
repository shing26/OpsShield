package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.gateway.AdminController;
import com.opspilot.metrics.AuditService;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 外发投递管理端点（契约 AC9）：平台门禁、tenant 必填、跨租户 404、sent 409、
 * 响应不含 URL/secret/载荷、retry 写审计。
 */
class IncidentPushAdminTest {

    private static final UserContext PLATFORM =
            new UserContext("sre-full", "platform", 3, "tenant-internal");
    private static final UserContext CUSTOMER_L3 =
            new UserContext("sre-acme", "sre", 3, "tenant-acme");

    private JdbcTemplate jdbc;
    private IncidentPushService pushes;
    private AuditService audit;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:push-admin-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
        jdbc = new JdbcTemplate(ds);
        IncidentPushOutboxStore store = new IncidentPushOutboxStore(jdbc);
        IncidentNotificationProperties props = new IncidentNotificationProperties(
                "", "generic", 256, 3000, "22:00-08:00", "Asia/Shanghai", 90,
                "0 17 4 * * *", Map.of(), "P2", "", "", 8, 1000, 300000, 1000, 20, 1000);
        pushes = new IncidentPushService(store, props,
                new IncidentNotificationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        audit = mock(AuditService.class);
        AdminController controller = new AdminController(null, null, null, null, null, null, null,
                null, audit, null, null, null, null, null, pushes);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private long insert(String tenant, String eventKey, String channel, String status) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO incident_push_outbox (tenant, event_key, incident_id, kind, recipient,"
                        + " priority, title, channel_id, status, attempts, next_attempt_at, last_status,"
                        + " last_error, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenant, eventKey, "INC-1", "created", "t1-alice", "P1", "支付网关 502",
                channel, status, 3, java.sql.Timestamp.from(now), 500,
                "HTTP 500 https://user:fake@example.test/hook?token=fake", java.sql.Timestamp.from(now));
        return jdbc.queryForObject("SELECT MAX(outbox_id) FROM incident_push_outbox", Long.class);
    }

    @Test
    void platformAdminListsTenantScopedDeliveriesWithoutSecrets() throws Exception {
        insert("tenant-1", "c:INC-1", "alpha", "dead");
        insert("tenant-2", "c:INC-2", "alpha", "sent");

        mvc.perform(get("/api/v1/admin/notifications/deliveries")
                        .param("tenant", "tenant-1")
                        .requestAttr(UserContext.REQUEST_ATTR, PLATFORM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].tenant").value("tenant-1"))
                .andExpect(jsonPath("$.deliveries[0].id").exists())
                .andExpect(jsonPath("$.deliveries[0].channel").value("alpha"))
                .andExpect(jsonPath("$.deliveries[0].attempts").value(3))
                .andExpect(jsonPath("$.deliveries[0].url").doesNotExist())
                .andExpect(jsonPath("$.deliveries[0].secret").doesNotExist())
                .andExpect(jsonPath("$.deliveries[0].payload").doesNotExist());
    }

    @Test
    void tenantIsRequiredAndCustomerIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/admin/notifications/deliveries")
                        .requestAttr(UserContext.REQUEST_ATTR, CUSTOMER_L3))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/notifications/deliveries")
                        .requestAttr(UserContext.REQUEST_ATTR, PLATFORM))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retryResetsDeadToPendingAndWritesAudit() throws Exception {
        long id = insert("tenant-1", "c:INC-1", "alpha", "dead");

        mvc.perform(post("/api/v1/admin/notifications/deliveries/" + id + "/retry")
                        .param("tenant", "tenant-1")
                        .requestAttr(UserContext.REQUEST_ATTR, PLATFORM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.attempts").value(0));

        verify(audit).logAdmin(eq(PLATFORM), eq("notifications/deliveries/retry"), eq("ok"), anyString());
        assertEquals("pending", jdbc.queryForObject(
                "SELECT status FROM incident_push_outbox WHERE outbox_id = ?", String.class, id));
    }

    @Test
    void retrySentIsConflictAndCrossTenantOrMissingIs404() throws Exception {
        long sent = insert("tenant-1", "c:INC-1", "alpha", "sent");
        mvc.perform(post("/api/v1/admin/notifications/deliveries/" + sent + "/retry")
                        .param("tenant", "tenant-1")
                        .requestAttr(UserContext.REQUEST_ATTR, PLATFORM))
                .andExpect(status().isConflict());

        long dead = insert("tenant-1", "c:INC-2", "alpha", "dead");
        mvc.perform(post("/api/v1/admin/notifications/deliveries/" + dead + "/retry")
                        .param("tenant", "tenant-2")
                        .requestAttr(UserContext.REQUEST_ATTR, PLATFORM))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/notifications/deliveries/999999/retry")
                        .param("tenant", "tenant-1")
                        .requestAttr(UserContext.REQUEST_ATTR, PLATFORM))
                .andExpect(status().isNotFound());
    }
}
