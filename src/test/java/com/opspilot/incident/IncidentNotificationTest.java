package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 事故通知契约（ADR-0037）：同一事务、收件人判定、去重、JWT 隔离、已读幂等与留存。
 *
 * <p>这里刻意不 mock 数据库：原子性与唯一键冲突只有在真实事务/JDBC 上才有意义。
 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:incident-notifications;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.clean-disabled=true",
        "opspilot.incident.sla.scan-initial-delay-ms=3600000"})
class IncidentNotificationTest {

    @org.springframework.test.context.DynamicPropertySource
    static void jwtSecret(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "incidentnotifications".repeat(3));
    }

    @Autowired IncidentService incidents;
    @Autowired IncidentSlaScanner scanner;
    @Autowired IncidentNotificationService notifications;
    @Autowired IncidentNotificationStore notificationStore;
    @Autowired UserStore users;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired PlatformTransactionManager txManager;

    static final UserContext ALICE = new UserContext("t1-alice", "sre", 3, "tenant-1");
    static final UserContext BOB = new UserContext("t1-bob", "sre", 3, "tenant-1");
    static final UserContext ACME = new UserContext("t2-carol", "sre", 3, "tenant-2");

    private TransactionTemplate tx;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        mvc = MockMvcBuilders.standaloneSetup(new IncidentNotificationController(notifications)).build();
        jdbc.update("DELETE FROM incident_notifications");
        jdbc.update("DELETE FROM incident_comments");
        jdbc.update("DELETE FROM incident_assignments");
        jdbc.update("DELETE FROM incidents");
        seed("t1-alice", "tenant-1", false);
        seed("t1-bob", "tenant-1", false);
        seed("t1-off", "tenant-1", true);
        seed("t2-carol", "tenant-2", false);
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

    private static IncidentRequests.Transfer transfer(String toOwner) {
        return new IncidentRequests.Transfer(toOwner, "夜班交接");
    }

    private List<IncidentNotification> listFor(UserContext user) {
        return notifications.list(user, false, 100);
    }

    private int rows(String tenant, String recipient) {
        return notificationStore.countFor(tenant, recipient);
    }

    // ---- AC2：同一事务 / 回滚零通知 ----

    @Test
    void rollbackOfIncidentChangeLeavesZeroNotificationRows() {
        Incident inc = create("cid-rollback", "P2");

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));
            throw new IllegalStateException("force rollback");
        }));

        assertEquals("t1-alice", incidents.get(ALICE, inc.id()).owner(), "事故变更也必须回滚");
        assertEquals(0, rows("tenant-1", "t1-bob"), "事故回滚后该事故必须零通知行");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM incident_notifications WHERE incident_id = ?", Integer.class, inc.id()));
    }

    @Test
    void dedupeConflictIsSwallowedAndCountsOneRow() {
        Incident inc = create("cid-dedupe", "P2");

        tx.executeWithoutResult(s -> {
            notifications.onSla(inc, Incident.SLA_DUE_SOON);
            notifications.onSla(inc, Incident.SLA_DUE_SOON);
        });

        // 创建人=负责人=alice，同一事件同一人只一行。
        assertEquals(1, rows("tenant-1", "t1-alice"));
    }

    /** P1 回归：空标题建单时 title 由 query 派生，通知与 webhook 都不得把 query 带出去。 */
    @Test
    void notificationTitleNeverLeaksQueryWhenTitleWasDerived() {
        String marker = "SECRET-QUERY-FALLBACK-7788";
        String query = marker + " 支付网关 502 排查";
        Incident inc = incidents.create(ALICE, new IncidentRequests.Create(
                "cid-query-fallback", "", query, "payment-service",
                "50041_PAY_GATEWAY_502", "prod", "", "manual", "P1")).incident();
        assertEquals(IncidentService.titleFromQuery(query), inc.title(),
                "事故本体的空标题回退语义不得改变");

        incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));

        IncidentNotification row = listFor(BOB).get(0);
        assertEquals("事故 " + inc.id(), row.title(), "通知标题不得复用 query 派生标题");
        assertFalse(row.title().contains(marker), "通知行不得包含 query marker");
        assertFalse(IncidentPushPayload.text(row).contains(marker),
                "webhook 载荷不得包含 query marker");
    }

    /** P1 回归：显式标题照常透传；query 仍然不得进入通知或 webhook。 */
    @Test
    void explicitTitlePassesThroughWithoutLeakingQuery() {
        String marker = "SECRET-QUERY-PASSTHRU-9900";
        String title = "支付网关 502（人工标题）";
        Incident inc = incidents.create(ALICE, new IncidentRequests.Create(
                "cid-title-passthru", title, marker + " 支付网关 502 排查",
                "payment-service", "50041_PAY_GATEWAY_502", "prod", "", "manual", "P1")).incident();

        incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));

        IncidentNotification row = listFor(BOB).get(0);
        assertEquals(title, row.title(), "非空标题必须原样透传");
        assertFalse(IncidentPushPayload.text(row).contains(marker),
                "显式标题透传时 query 仍不得进入 webhook");
    }

    // ---- AC3：收件人 ----

    @Test
    void transferNotifiesNewOwnerOnlyAndNeverTheActor() {
        Incident inc = create("cid-owner", "P2");

        incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));
        assertEquals(1, rows("tenant-1", "t1-bob"), "转交只通知新负责人");
        assertEquals(0, rows("tenant-1", "t1-alice"), "操作人本人不收");

        // 当前负责人是 bob，alice（创建人）把单转回给自己：操作人==收件人，不产生通知。
        incidents.transfer(ALICE, inc.id(), transfer("t1-alice"));
        assertEquals(0, rows("tenant-1", "t1-alice"), "自己指派给自己不产生通知");
    }

    @Test
    void slaNotifiesOwnerAndCreatorOncePerState() {
        Incident inc = create("cid-sla", "P1");
        incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));

        Instant created = inc.createdAt();
        scanner.scan(created.plus(25, ChronoUnit.MINUTES)); // P1 30m 窗口，提前 6m 进入 due_soon
        // bob 已有 1 条 assigned（转交），due_soon 再各加 1；alice 只有 due_soon。
        assertEquals(2, rows("tenant-1", "t1-bob"));
        assertEquals(1, rows("tenant-1", "t1-alice"));

        scanner.scan(created.plus(25, ChronoUnit.MINUTES));
        assertEquals(2, rows("tenant-1", "t1-bob"), "同一状态重复扫描不得重复通知");
        assertEquals(1, rows("tenant-1", "t1-alice"));

        scanner.scan(created.plus(31, ChronoUnit.MINUTES));
        assertEquals(3, rows("tenant-1", "t1-bob"), "breached 是新事件，应各多一行");
        assertEquals(2, rows("tenant-1", "t1-alice"));

        // 空负责人只发创建人：另一张 P1 单取消分配后，SLA 通知只落到 alice。
        Incident unowned = create("cid-sla-unowned", "P1");
        incidents.patch(ALICE, unowned.id(), owner(""));
        scanner.scan(unowned.createdAt().plus(25, ChronoUnit.MINUTES));
        assertEquals(1, listFor(ALICE).stream()
                .filter(n -> unowned.id().equals(n.incidentId())).count(), "空负责人时创建人仍应收");
        assertEquals(0, listFor(BOB).stream()
                .filter(n -> unowned.id().equals(n.incidentId())).count(), "空负责人不应产生负责人通知");
    }

    @Test
    void foreignOrDisabledRecipientsNeverReceive() {
        Incident inc = create("cid-foreign", "P2");

        tx.executeWithoutResult(s -> {
            notifications.onAssigned(ALICE, inc, "t2-carol");
            notifications.onAssigned(ALICE, inc, "t1-off");
        });

        assertEquals(0, rows("tenant-1", "t2-carol"), "跨租户用户即使被猜到 sub 也收不到");
        assertEquals(0, rows("tenant-1", "t1-off"), "禁用用户不收");
    }

    /** 操作人是当前负责人：自己重开不通知自己，创建人（非操作人）必须收到。 */
    @Test
    void reopeningReachesCreatorWhenActorIsOwner() {
        Incident inc = create("cid-reopen2", "P2");
        incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));
        incidents.patch(BOB, inc.id(), patchStatus("closed"));

        incidents.patch(BOB, inc.id(), patchStatus("open"));

        assertEquals(1, listFor(ALICE).stream()
                .filter(n -> IncidentNotification.KIND_REOPENED.equals(n.kind())).count());
        assertEquals(0, listFor(BOB).stream()
                .filter(n -> IncidentNotification.KIND_REOPENED.equals(n.kind())).count());
    }

    /** 操作人是创建人：自己重开不通知自己，当前负责人（非操作人）必须收到。 */
    @Test
    void reopeningReachesOwnerWhenActorIsCreator() {
        Incident inc = create("cid-reopen3", "P2");
        incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));
        incidents.patch(BOB, inc.id(), patchStatus("closed"));

        incidents.patch(ALICE, inc.id(), patchStatus("open"));

        assertEquals(1, listFor(BOB).stream()
                .filter(n -> IncidentNotification.KIND_REOPENED.equals(n.kind())).count());
        assertEquals(0, listFor(ALICE).stream()
                .filter(n -> IncidentNotification.KIND_REOPENED.equals(n.kind())).count());
    }

    // ---- AC4/5：JWT 隔离与已读幂等 ----

    @Test
    void listAndReadAreScopedToJwtRecipient() throws Exception {
        Incident inc = create("cid-api", "P2");
        incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));
        long bobId = listFor(BOB).get(0).id();

        mvc.perform(get("/api/v1/copilot/notifications").requestAttr(UserContext.REQUEST_ATTR, BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].incidentId").value(inc.id()))
                .andExpect(jsonPath("$.notifications[0].read").value(false))
                .andExpect(jsonPath("$.unread").value(1));

        mvc.perform(get("/api/v1/copilot/notifications?unreadOnly=true&limit=100")
                        .requestAttr(UserContext.REQUEST_ATTR, BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(1));

        mvc.perform(get("/api/v1/copilot/notifications").requestAttr(UserContext.REQUEST_ATTR, ACME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(0))
                .andExpect(jsonPath("$.unread").value(0));

        // 非本人 → 404，不泄漏存在性。
        mvc.perform(post("/api/v1/copilot/notifications/" + bobId + "/read")
                        .requestAttr(UserContext.REQUEST_ATTR, ALICE))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/copilot/notifications/" + bobId + "/read")
                        .requestAttr(UserContext.REQUEST_ATTR, BOB))
                .andExpect(status().isNoContent());
        Instant firstRead = listFor(BOB).get(0).readAt();
        assertNotNull(firstRead);
        Thread.sleep(5);
        mvc.perform(post("/api/v1/copilot/notifications/" + bobId + "/read")
                        .requestAttr(UserContext.REQUEST_ATTR, BOB))
                .andExpect(status().isNoContent());
        assertEquals(firstRead, listFor(BOB).get(0).readAt(), "已读时间只写一次，不覆盖");

        mvc.perform(get("/api/v1/copilot/notifications?unreadOnly=true")
                        .requestAttr(UserContext.REQUEST_ATTR, BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(0));

        mvc.perform(post("/api/v1/copilot/notifications/read-all")
                        .requestAttr(UserContext.REQUEST_ATTR, BOB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(0));
    }

    @Test
    void readAllReturnsUpdatedCountAndIsIdempotent() {
        Incident inc = create("cid-readall", "P2");
        incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));
        incidents.patch(BOB, inc.id(), patchStatus("closed"));
        incidents.patch(ALICE, inc.id(), patchStatus("open")); // 操作人是创建人，bob 再收一条 reopened

        assertEquals(2, notifications.unread(BOB));
        assertEquals(2, notifications.readAll(BOB));
        assertEquals(0, notifications.readAll(BOB));
        assertEquals(0, notifications.unread(BOB));
    }

    // ---- AC9/10：留存与指标 ----

    @Test
    void cleanupDeletesOnlyRowsOlderThanRetention() {
        Incident inc = create("cid-retention", "P2");
        Instant old = Instant.now().minus(91, ChronoUnit.DAYS);
        assertTrue(notificationStore.insert(new IncidentNotification(0L, "tenant-1", "t1-bob",
                inc.id(), IncidentNotification.KIND_ASSIGNED, "旧通知", "P2", null, "old-key", old, null)));
        assertTrue(notificationStore.insert(new IncidentNotification(0L, "tenant-1", "t1-bob",
                inc.id(), IncidentNotification.KIND_ASSIGNED, "新通知", "P2", null, "new-key",
                Instant.now(), null)));

        assertEquals(1, notifications.cleanup());
        List<IncidentNotification> left = notificationStore.list("tenant-1", "t1-bob", false, 10);
        assertEquals(1, left.size());
        assertEquals("新通知", left.get(0).title());
    }

    @Test
    void notificationMetricIsTaggedByKind() {
        Incident inc = create("cid-metric", "P2");
        double before = meterRegistry.counter("aiops.incident.notifications", "kind", "assigned").count();

        incidents.transfer(ALICE, inc.id(), transfer("t1-bob"));

        assertEquals(before + 1,
                meterRegistry.counter("aiops.incident.notifications", "kind", "assigned").count());

        // 契约锁的是 Prometheus 名与标签，不只是 Micrometer 内部名。
        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        IncidentNotificationMetrics metrics = new IncidentNotificationMetrics(prom);
        metrics.notification(IncidentNotification.KIND_ASSIGNED);
        metrics.webhook("suppressed");
        metrics.webhook("dropped");
        String scrape = prom.scrape();
        assertTrue(scrape.contains("aiops_incident_notifications_total"));
        assertTrue(scrape.contains("kind=\"assigned\""));
        assertTrue(scrape.contains("aiops_incident_notify_webhook_total"));
        assertTrue(scrape.contains("result=\"suppressed\""));
        assertTrue(scrape.contains("result=\"dropped\""));
    }

    private static IncidentRequests.Patch patchStatus(String status) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, status, null,
                null, null, null, null);
    }

    private static IncidentRequests.Patch owner(String owner) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, null, owner,
                null, null, null, null);
    }
}
