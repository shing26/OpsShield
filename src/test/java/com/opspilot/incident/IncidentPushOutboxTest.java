package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import com.opspilot.metrics.AuditService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外发 outbox 的事务与路由（ADR-0045 / 契约 AC2/AC4/AC6/AC10）。
 *
 * <p>这里刻意不 mock 数据库：同事务回滚、唯一键去重与并发 clientId 只有真实事务能证明。
 * @Scheduled 轮询被调到 1 小时后，测试只观察 outbox 行，不触发真实 HTTP。
 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:push-outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.clean-disabled=true",
        "opspilot.incident.sla.scan-initial-delay-ms=3600000",
        "opspilot.incident.notify.poll-interval-ms=3600000",
        "opspilot.incident.notify.poll-initial-delay-ms=3600000",
        "opspilot.incident.notify.created-min-priority=P2",
        "opspilot.incident.notify.channels.alpha.url=http://127.0.0.1:9/hook",
        "opspilot.incident.notify.channels.alpha.min-priority=P2",
        "opspilot.incident.notify.channels.alpha.kinds=created,sla_due_soon,sla_breached",
        "opspilot.incident.notify.channels.beta.url=http://127.0.0.1:9/hook",
        "opspilot.incident.notify.channels.beta.min-priority=P3",
        "opspilot.incident.notify.channels.beta.kinds=assigned,reopened"})
class IncidentPushOutboxTest {

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "incidentpushoutbox".repeat(3));
        // 静默期窗口放到 12 小时后，保证本类测试不因本机当前时刻而随机抑制。
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).plusHours(12);
        String start = now.toLocalTime().withSecond(0).withNano(0).toString();
        String end = now.plusMinutes(1).toLocalTime().withSecond(0).withNano(0).toString();
        r.add("opspilot.incident.notify.quiet-hours", () -> start + "-" + end);
    }

    @Autowired IncidentService incidents;
    @Autowired IncidentNotificationService notifications;
    @Autowired IncidentPushService push;
    @Autowired IncidentPushOutboxStore outbox;
    @Autowired UserStore users;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired io.micrometer.core.instrument.MeterRegistry meterRegistry;

    static final UserContext ALICE = new UserContext("t1-alice", "sre", 3, "tenant-1");
    static final UserContext BOB = new UserContext("t1-bob", "sre", 3, "tenant-1");

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        jdbc.update("DELETE FROM incident_push_outbox");
        jdbc.update("DELETE FROM incident_notifications");
        jdbc.update("DELETE FROM incident_comments");
        jdbc.update("DELETE FROM incident_assignments");
        jdbc.update("DELETE FROM incidents");
        seed("t1-alice", "tenant-1", false);
        seed("t1-bob", "tenant-1", false);
    }

    private void seed(String sub, String tenant, boolean disabled) {
        if (users.find(sub) != null) return;
        users.insert(sub, "x", tenant, 3, "sre");
        if (disabled) users.setDisabled(sub, true);
    }

    private Incident create(String clientId, String priority) {
        var req = new IncidentRequests.Create(clientId, "支付网关 502", "支付网关 502 排查",
                "payment-service", "50041_PAY_GATEWAY_502", "prod", "", "manual", priority);
        return incidents.create(ALICE, req).incident();
    }

    private int outboxCount(String incidentId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM incident_push_outbox WHERE incident_id = ?",
                Integer.class, incidentId);
        return n == null ? 0 : n;
    }

    private List<IncidentPushOutbox> rows(String incidentId) {
        return jdbc.query("SELECT * FROM incident_push_outbox WHERE incident_id = ? ORDER BY outbox_id",
                (rs, i) -> new IncidentPushOutbox(rs.getLong("outbox_id"), rs.getString("tenant"),
                        rs.getString("event_key"), rs.getString("incident_id"), rs.getString("kind"),
                        rs.getString("recipient"), rs.getString("priority"), rs.getString("title"),
                        null, rs.getString("channel_id"), rs.getString("status"), rs.getInt("attempts"),
                        null, null, null, rs.getString("last_error"), null, null),
                incidentId);
    }

    @Test
    void createdEventIsRoutedInTheIncidentTransaction() {
        Incident p2 = create("cid-created-p2", "P2");
        List<IncidentPushOutbox> rows = rows(p2.id());
        assertEquals(1, rows.size(), "P2 建单应入 alpha（kinds=created, min-priority=P2）");
        assertEquals("c:" + p2.id(), rows.get(0).eventKey());
        assertEquals("alpha", rows.get(0).channelId());
        assertEquals(IncidentPushOutbox.KIND_CREATED, rows.get(0).kind());
        assertEquals("t1-alice", rows.get(0).recipient());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications "
                + "WHERE incident_id = ?", Integer.class, p2.id()),
                "created 事件不得新增站内通知行");

        Incident p3 = create("cid-created-p3", "P3");
        assertEquals(0, outboxCount(p3.id()), "P3 低于 created-min-priority=P2");
    }

    @Test
    void duplicateClientIdReplayDoesNotEnqueueAgain() {
        Incident first = create("cid-replay", "P1");
        Incident second = create("cid-replay", "P1");
        assertEquals(first.id(), second.id());
        assertEquals(1, outboxCount(first.id()), "幂等重放不得重复入 outbox");
    }

    @Test
    void concurrentDuplicateClientIdLeavesOneIncidentAndOneOutboxRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<IncidentService.Created> a = pool.submit(() -> {
                start.await();
                return incidents.create(ALICE, req("cid-concurrent", "P1"));
            });
            Future<IncidentService.Created> b = pool.submit(() -> {
                start.await();
                return incidents.create(ALICE, req("cid-concurrent", "P1"));
            });
            start.countDown();
            Incident one = a.get(5, TimeUnit.SECONDS).incident();
            Incident two = b.get(5, TimeUnit.SECONDS).incident();
            assertEquals(one.id(), two.id(), "并发重放必须返回同一条事故");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM incidents WHERE client_id = ?", Integer.class, "cid-concurrent"));
            assertEquals(1, outboxCount(one.id()), "并发重放不得重复入 outbox");
        } finally {
            pool.shutdownNow();
        }
    }

    private static IncidentRequests.Create req(String clientId, String priority) {
        return new IncidentRequests.Create(clientId, "并发标题", "并发 query", "payment-service",
                "50041_PAY_GATEWAY_502", "prod", "", "manual", priority);
    }

    @Test
    void rollbackOfIncidentChangeLeavesZeroOutboxRows() {
        Incident inc = create("cid-rollback-push", "P1");
        assertEquals(1, outboxCount(inc.id()), "建单本身的 c: 行存在");

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            incidents.transfer(ALICE, inc.id(), new IncidentRequests.Transfer("t1-bob", "夜班交接"));
            throw new IllegalStateException("force rollback");
        }));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incident_push_outbox "
                + "WHERE incident_id = ? AND event_key LIKE 'n:%'", Integer.class, inc.id()),
                "通知 outbox 必须与事故变更同事务回滚");
        assertEquals(1, outboxCount(inc.id()), "建单已提交的 c: 行不受后续转交回滚影响");
    }

    @Test
    void notificationDedupeProducesOneOutboxRowPerChannel() {
        Incident inc = create("cid-dedupe-push", "P1");
        tx.executeWithoutResult(s -> {
            notifications.onSla(inc, Incident.SLA_DUE_SOON);
            notifications.onSla(inc, Incident.SLA_DUE_SOON);
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM incident_push_outbox "
                + "WHERE incident_id = ? AND event_key LIKE 'n:%'", Integer.class, inc.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incident_push_outbox "
                + "WHERE incident_id = ? AND channel_id = 'beta'", Integer.class, inc.id()),
                "beta 的 kinds=assigned,reopened 不含 sla_due_soon");
    }

    @Test
    void assignedRoutesOnlyToChannelThatAcceptsIt() {
        Incident inc = create("cid-assigned-route", "P2");
        // alpha 只要 created，beta 接受 assigned；P2 达到 beta 的 min-priority=P3。
        incidents.transfer(ALICE, inc.id(), new IncidentRequests.Transfer("t1-bob", "交接"));
        List<IncidentPushOutbox> rows = rows(inc.id());
        assertEquals(2, rows.size(), "created(alpha) + assigned(beta)");
        assertTrue(rows.stream().anyMatch(r -> "alpha".equals(r.channelId())
                && IncidentPushOutbox.KIND_CREATED.equals(r.kind())));
        assertTrue(rows.stream().anyMatch(r -> "beta".equals(r.channelId())
                && IncidentNotification.KIND_ASSIGNED.equals(r.kind())));
    }

    @Test
    void outboxIsWrittenInTheSameTransactionAsNotifications() {
        Incident inc = create("cid-same-tx", "P1");
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            notifications.onSla(inc, Incident.SLA_BREACHED);
            throw new IllegalStateException("force rollback");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incident_notifications "
                + "WHERE incident_id = ? AND kind = 'sla_breached'", Integer.class, inc.id()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incident_push_outbox "
                + "WHERE incident_id = ? AND kind = 'sla_breached'", Integer.class, inc.id()));
    }

    @Test
    void retentionDeletesOnlySentAndDeadAndNeverPending() {
        Instant old = Instant.now().minus(91, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO incident_push_outbox (tenant, event_key, incident_id, kind, title,"
                        + " channel_id, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "tenant-1", "c:OLD-SENT", "OLD-SENT", "created", "旧", "alpha", "sent",
                java.sql.Timestamp.from(old));
        jdbc.update("INSERT INTO incident_push_outbox (tenant, event_key, incident_id, kind, title,"
                        + " channel_id, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "tenant-1", "c:OLD-DEAD", "OLD-DEAD", "created", "旧", "alpha", "dead",
                java.sql.Timestamp.from(old));
        jdbc.update("INSERT INTO incident_push_outbox (tenant, event_key, incident_id, kind, title,"
                        + " channel_id, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "tenant-1", "c:OLD-PENDING", "OLD-PENDING", "created", "旧", "alpha", "pending",
                java.sql.Timestamp.from(old));

        assertEquals(2, push.cleanup());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM incident_push_outbox "
                + "WHERE incident_id = 'OLD-PENDING'", Integer.class), "pending 永不清理");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM incident_push_outbox "
                + "WHERE incident_id IN ('OLD-SENT','OLD-DEAD')", Integer.class));
    }

    @Test
    void newMetricsAreExposedWithContractNames() {
        io.micrometer.prometheusmetrics.PrometheusMeterRegistry prom =
                new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                        io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        IncidentNotificationMetrics metrics = new IncidentNotificationMetrics(prom);
        metrics.push("alpha", "ok");
        metrics.push("alpha", "retry");
        metrics.push("alpha", "dead");
        metrics.push("alpha", "suppressed");
        metrics.pushLatency("alpha", 1_000_000);
        metrics.pushSuccess("alpha", Instant.now());
        String scrape = prom.scrape();
        assertTrue(scrape.contains("aiops_incident_push_total"));
        assertTrue(scrape.contains("channel=\"alpha\""));
        assertTrue(scrape.contains("result=\"retry\""));
        assertTrue(scrape.contains("aiops_incident_push_latency_seconds"));
        assertTrue(scrape.contains("aiops_incident_push_last_success_timestamp"));
    }
}
