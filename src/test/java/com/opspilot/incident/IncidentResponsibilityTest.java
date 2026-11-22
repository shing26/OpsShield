package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import com.opspilot.metrics.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事故责任流转（ADR-0033）的硬约束：转交是唯一写入口、append-only 历史、
 * 「我的」服务端筛选、SLA 派生与扫描幂等。
 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:incident-responsibility;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.clean-disabled=true"})
class IncidentResponsibilityTest {

    @org.springframework.test.context.DynamicPropertySource
    static void jwtSecret(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "incidentresponsibility".repeat(3));
    }

    @Autowired IncidentService service;
    @Autowired IncidentStore store;
    @Autowired IncidentSlaScanner scanner;
    @Autowired AuditService audit;
    @Autowired UserStore users;
    @Autowired JdbcTemplate jdbc;

    static final UserContext ALICE = new UserContext("t1-alice", "sre", 3, "tenant-1");
    static final UserContext BOB = new UserContext("t1-bob", "sre", 3, "tenant-1");
    static final UserContext ACME = new UserContext("t2-carol", "sre", 3, "tenant-2");

    @BeforeEach
    void seedUsers() {
        seed("t1-alice", "tenant-1", 3, "sre", false);
        seed("t1-bob", "tenant-1", 3, "sre", false);
        seed("t1-off", "tenant-1", 3, "sre", true);
        seed("t2-carol", "tenant-2", 3, "sre", false);
    }

    private void seed(String sub, String tenant, int level, String role, boolean disabled) {
        if (users.find(sub) != null) return;
        users.insert(sub, "x", tenant, level, role);
        if (disabled) users.setDisabled(sub, true);
    }

    private Incident create(String clientId, String priority) {
        var req = new IncidentRequests.Create(clientId, "标题", "支付网关 502 排查",
                "payment-service", "50041_PAY_GATEWAY_502", "prod", "", "manual", priority);
        return service.create(ALICE, req).incident();
    }

    private static IncidentRequests.Transfer transfer(String toOwner, String reason) {
        return new IncidentRequests.Transfer(toOwner, reason);
    }

    private static IncidentRequests.Patch owner(String sub) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, null, sub,
                null, null, null, null);
    }

    private static IncidentRequests.Patch priority(String priority) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, null, null,
                priority, null, null, null);
    }

    private static IncidentRequests.Patch status(String status) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, status, null,
                null, null, null, null);
    }

    // ---- 转交 ----

    @Test
    void transferMovesOwnerAndAppendsAssignmentFact() {
        Incident inc = create("cid-transfer", "P2");

        Incident moved = service.transfer(ALICE, inc.id(), transfer("t1-bob", "夜班交接"));

        assertEquals("t1-bob", moved.owner());
        List<IncidentAssignment> rows = service.assignments(ALICE, inc.id());
        assertEquals(1, rows.size(), "一次转交必须且只能追加一行事实");
        IncidentAssignment row = rows.get(0);
        assertEquals("t1-alice", row.fromOwner());
        assertEquals("t1-bob", row.toOwner());
        assertEquals("夜班交接", row.reason());
        assertEquals("t1-alice", row.assignedBy());

        var auditRows = audit.recentSince(0, 200).events().stream()
                .filter(e -> "incident".equals(e.get("ev")) && inc.id().equals(e.get("id")))
                .filter(e -> "transfer".equals(e.get("action")) && "ok".equals(e.get("outcome")))
                .toList();
        assertEquals(1, auditRows.size(), "转交成功必须落 ev=incident action=transfer 审计");
    }

    @Test
    void patchOwnerUsesTheSameTransferPathAndWritesDashReason() {
        Incident inc = create("cid-patch-owner", "P2");

        Incident patched = service.patch(ALICE, inc.id(), owner("t1-bob"));

        assertEquals("t1-bob", patched.owner());
        List<IncidentAssignment> rows = service.assignments(ALICE, inc.id());
        assertEquals(1, rows.size(), "PATCH owner 也要写 append-only 转交行");
        assertEquals("—", rows.get(0).reason(), "PATCH owner 不带理由，归一为 —");
        assertEquals(inc.owner(), rows.get(0).fromOwner());
        assertEquals("t1-bob", rows.get(0).toOwner());
    }

    @Test
    void transferRejectsForeignDisabledAndSameOwnerWithoutWritingHistory() {
        Incident inc = create("cid-transfer-valid", "P2");

        var foreign = assertThrows(ResponseStatusException.class,
                () -> service.transfer(ALICE, inc.id(), transfer("t2-carol", null)));
        assertEquals(HttpStatus.BAD_REQUEST, foreign.getStatusCode());

        var disabled = assertThrows(ResponseStatusException.class,
                () -> service.transfer(ALICE, inc.id(), transfer("t1-off", null)));
        assertEquals(HttpStatus.BAD_REQUEST, disabled.getStatusCode());

        var same = assertThrows(ResponseStatusException.class,
                () -> service.transfer(ALICE, inc.id(), transfer("t1-alice", null)));
        assertEquals(HttpStatus.CONFLICT, same.getStatusCode());

        var tooLong = assertThrows(ResponseStatusException.class,
                () -> service.transfer(ALICE, inc.id(), transfer("t1-bob", "x".repeat(201))));
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.getStatusCode());

        assertEquals(0, service.assignments(ALICE, inc.id()).size(),
                "失败的转交不得留下任何责任事实");
        assertEquals("t1-alice", service.get(ALICE, inc.id()).owner());
    }

    @Test
    void transferRequiresCreatorOwnerOrPlatform() {
        Incident inc = create("cid-transfer-perm", "P2");

        var denied = assertThrows(ResponseStatusException.class,
                () -> service.transfer(BOB, inc.id(), transfer("t1-bob", "越权")));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals(0, service.assignments(ALICE, inc.id()).size());

        var auditRows = audit.recentSince(0, 200).events().stream()
                .filter(e -> "incident".equals(e.get("ev")) && inc.id().equals(e.get("id")))
                .filter(e -> "transfer".equals(e.get("action")) && "forbidden".equals(e.get("outcome")))
                .toList();
        assertEquals(1, auditRows.size(), "越权转交必须落 outcome=forbidden");
    }

    @Test
    void assignmentsAreTenantScopedAndMissingIncidentIs404() {
        Incident inc = create("cid-tenant", "P2");
        service.transfer(ALICE, inc.id(), transfer("t1-bob", "交接"));

        var crossTenant = assertThrows(ResponseStatusException.class,
                () -> service.assignments(ACME, inc.id()));
        assertEquals(HttpStatus.NOT_FOUND, crossTenant.getStatusCode());

        var missing = assertThrows(ResponseStatusException.class,
                () -> service.assignments(ALICE, "INC-NOPE"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    @Test
    void staleOwnerUpdateDoesNotInsertAssignmentRow() {
        Incident cur = create("cid-atomic", "P2");
        Incident next = new Incident(cur.id(), cur.tenant(), cur.clientId(),
                cur.title(), cur.query(), cur.service(), cur.errorCode(), cur.env(), cur.alertId(),
                cur.source(), cur.priority(), cur.status(), "t1-bob", cur.createdBy(),
                cur.createdAt(), Instant.now(), cur.closedAt(), cur.closedBy(), cur.closeNote(),
                cur.diagnosisJson(), cur.timelineJson(), cur.dueAt(), cur.slaState());

        boolean moved = store.transfer(next, "t1-someone-else", "并发", "t1-alice", Instant.now());

        assertFalse(moved, "旧 owner 与库中不符时必须拒绝整次写入");
        assertEquals(0, service.assignments(ALICE, cur.id()).size(),
                "条件更新失败后不得追加转交行（同一事务的原子性）");
    }

    // ---- 「我的」筛选 ----

    @Test
    void mineFilterIsServerSideAndStacksWithStatus() {
        Incident kept = create("cid-mine-1", "P2");
        Incident moved = create("cid-mine-2", "P2");
        service.transfer(ALICE, moved.id(), transfer("t1-bob", "交给 bob"));

        List<Incident> aliceMine = service.list(ALICE, "true", null);
        assertTrue(aliceMine.stream().anyMatch(i -> i.id().equals(kept.id())));
        assertTrue(aliceMine.stream().noneMatch(i -> i.id().equals(moved.id())),
                "已转交出去的单不得留在「我的」里");

        List<Incident> bobMine = service.list(BOB, "true", null);
        assertTrue(bobMine.stream().anyMatch(i -> i.id().equals(moved.id())));
        assertTrue(bobMine.stream().noneMatch(i -> i.id().equals(kept.id())));

        service.patch(ALICE, kept.id(), status("closed"));
        assertTrue(service.list(ALICE, "true", "open").stream().noneMatch(i -> i.id().equals(kept.id())),
                "status 必须能与 mine 叠加");
        assertTrue(service.list(ALICE, "true", "closed").stream().anyMatch(i -> i.id().equals(kept.id())));

        var badStatus = assertThrows(ResponseStatusException.class,
                () -> service.list(ALICE, "true", "pending"));
        assertEquals(HttpStatus.BAD_REQUEST, badStatus.getStatusCode());
        var badMine = assertThrows(ResponseStatusException.class,
                () -> service.list(ALICE, "yes", null));
        assertEquals(HttpStatus.BAD_REQUEST, badMine.getStatusCode());
    }

    // ---- SLA ----

    @Test
    void dueAtIsDerivedFromPriorityAndRecomputedOnChange() {
        Incident p1 = create("cid-sla-p1", "P1");
        assertEquals(30, Duration.between(p1.createdAt(), p1.dueAt()).toMinutes());
        assertEquals(Incident.SLA_OK, p1.slaState());

        Incident p3 = service.patch(ALICE, p1.id(), priority("P3"));
        assertEquals(480, Duration.between(p3.updatedAt(), p3.dueAt()).toMinutes(),
                "改优先级必须按新窗口重算 due_at");
        assertEquals(Incident.SLA_OK, p3.slaState());

        Incident cleared = service.patch(ALICE, p1.id(), priority(""));
        assertNull(cleared.dueAt(), "空优先级不设时限");
    }

    @Test
    void slaScannerMovesForwardOnceAndAuditsEachTransition() {
        Incident inc = create("cid-sla-scan", "P1");
        Instant now = Instant.now();

        // 距到期不足 20% 窗口 → due_soon
        jdbc.update("UPDATE incidents SET due_at = ? WHERE tenant = ? AND incident_id = ?",
                java.sql.Timestamp.from(now.plus(Duration.ofMinutes(3))), inc.tenant(), inc.id());
        assertEquals(1, scanner.scan(now));
        assertEquals(Incident.SLA_DUE_SOON, service.get(ALICE, inc.id()).slaState());
        assertEquals(1, slaAudit(inc.id(), "due_soon"));

        assertEquals(0, scanner.scan(now), "同一状态不得重复跃迁");
        assertEquals(1, slaAudit(inc.id(), "due_soon"), "同一状态不得重复落审计");

        // 越过到期时间 → breached
        Instant later = now.plus(Duration.ofMinutes(5));
        assertEquals(1, scanner.scan(later));
        assertEquals(Incident.SLA_BREACHED, service.get(ALICE, inc.id()).slaState());
        assertEquals(1, slaAudit(inc.id(), "breached"));

        assertEquals(0, scanner.scan(later.plus(Duration.ofHours(1))), "breached 是终态，不重复跃迁");
        assertEquals(1, slaAudit(inc.id(), "breached"));
    }

    @Test
    void closedIncidentsDoNotParticipateInSlaScan() {
        Incident inc = create("cid-sla-closed", "P1");
        service.patch(ALICE, inc.id(), status("closed"));
        Instant now = Instant.now();
        jdbc.update("UPDATE incidents SET due_at = ? WHERE tenant = ? AND incident_id = ?",
                java.sql.Timestamp.from(now.minus(Duration.ofHours(1))), inc.tenant(), inc.id());

        assertEquals(0, scanner.scan(now));
        assertEquals(Incident.SLA_OK, service.get(ALICE, inc.id()).slaState());
    }

    private long slaAudit(String id, String outcome) {
        return audit.recentSince(0, 200).events().stream()
                .filter(e -> "incident".equals(e.get("ev")) && id.equals(e.get("id")))
                .filter(e -> "sla".equals(e.get("action")) && outcome.equals(e.get("outcome")))
                .count();
    }
}
