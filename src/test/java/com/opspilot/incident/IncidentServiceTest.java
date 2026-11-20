package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事故持久化的四条硬约束：租户隔离、创建幂等、分配校验、责任动作权限。
 * 这四条一旦放松，"多端可见"就会变成"多租户可见"或"谁都能结单"。
 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:incidents;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.clean-disabled=true"})
class IncidentServiceTest {

    @org.springframework.test.context.DynamicPropertySource
    static void jwtSecret(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "incidentpersistence".repeat(3));
    }

    @Autowired IncidentService service;
    @Autowired UserStore users;

    static final UserContext ALICE = new UserContext("t1-alice", "sre", 3, "tenant-1");
    static final UserContext BOB = new UserContext("t1-bob", "sre", 3, "tenant-1");
    static final UserContext ACME = new UserContext("t2-carol", "sre", 3, "tenant-2");
    static final UserContext ADMIN = new UserContext("t1-root", "platform", 3, "tenant-1");

    @BeforeEach
    void seedUsers() {
        seed("t1-alice", "tenant-1", 3, "sre", false);
        seed("t1-bob", "tenant-1", 3, "sre", false);
        seed("t1-root", "tenant-1", 3, "platform", false);
        seed("t1-off", "tenant-1", 3, "sre", true);
        seed("t2-carol", "tenant-2", 3, "sre", false);
    }

    private void seed(String sub, String tenant, int level, String role, boolean disabled) {
        if (users.find(sub) != null) return;
        users.insert(sub, "x", tenant, level, role);
        if (disabled) users.setDisabled(sub, true);
    }

    private IncidentRequests.Create create(String clientId, String query) {
        return new IncidentRequests.Create(clientId, "标题", query, "payment-service",
                "50041_PAY_GATEWAY_502", "prod", "", "manual", "");
    }

    /** Patch 有 12 个位置参数，测试里用三个具名工厂避免"数错位"（数错的测试比没测试更危险）。 */
    private static IncidentRequests.Patch owner(String sub) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, null, sub,
                null, null, null, null);
    }

    private static IncidentRequests.Patch status(String status, String note) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, status, null,
                null, null, null, note);
    }

    private static IncidentRequests.Patch content(String title, String query, String diag, String timeline) {
        return new IncidentRequests.Patch(title, query, null, null, null, null, null, null,
                null, diag, timeline, null);
    }

    private static IncidentRequests.Patch diagnosis(String diag, String timeline) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, null, null,
                null, diag, timeline, null);
    }

    private static IncidentRequests.Patch metadata(String title) {
        return new IncidentRequests.Patch(title, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private static IncidentRequests.Patch closeNote(String note) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, null, null,
                null, null, null, note);
    }

    private static IncidentRequests.Patch priority(String priority) {
        return new IncidentRequests.Patch(null, null, null, null, null, null, null, null,
                priority, null, null, null);
    }

    @Test
    void createIsIdempotentPerTenant() {
        var first = service.create(ALICE, create("cid-1", "支付网关 502 排查"));
        assertTrue(first.created());
        assertEquals(Incident.STATUS_OPEN, first.incident().status());
        assertEquals("t1-alice", first.incident().owner());

        var replay = service.create(ALICE, create("cid-1", "支付网关 502 排查"));
        assertFalse(replay.created(), "同一 clientId 重放不得再建一条");
        assertEquals(first.incident().id(), replay.incident().id());

        var otherTenant = service.create(ACME, create("cid-1", "支付网关 502 排查"));
        assertTrue(otherTenant.created());
        assertNotEquals(first.incident().id(), otherTenant.incident().id(),
                "唯一键是 (tenant, client_id)，不同租户各自独立");
    }

    @Test
    void listAndGetNeverCrossTenants() {
        var mine = service.create(ALICE, create("cid-t1", "订单超时 50012_DB_TIMEOUT"));
        assertTrue(service.list(ALICE).stream().anyMatch(i -> i.id().equals(mine.incident().id())));
        assertTrue(service.list(ACME).stream().noneMatch(i -> i.id().equals(mine.incident().id())));

        var ex = assertThrows(ResponseStatusException.class,
                () -> service.get(ACME, mine.incident().id()));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void assignmentOnlyAcceptsActiveUserInSameTenant() {
        var inc = service.create(ALICE, create("cid-assign", "支付网关 502 排查")).incident();

        var ok = service.patch(ALICE, inc.id(), owner("t1-bob"));
        assertEquals("t1-bob", ok.owner());

        var foreign = assertThrows(ResponseStatusException.class,
                () -> service.patch(ALICE, inc.id(), owner("t2-carol")));
        assertEquals(HttpStatus.BAD_REQUEST, foreign.getStatusCode());

        var disabled = assertThrows(ResponseStatusException.class,
                () -> service.patch(ALICE, inc.id(), owner("t1-off")));
        assertEquals(HttpStatus.BAD_REQUEST, disabled.getStatusCode());
    }

    @Test
    void responsibilityActionsRequireCreatorOwnerOrPlatform() {
        var inc = service.create(ALICE, create("cid-perm", "支付网关 502 排查")).incident();
        assertThrows(ResponseStatusException.class,
                () -> service.patch(BOB, inc.id(), status("closed", null)));

        var closed = service.patch(ALICE, inc.id(), status("closed", "已恢复"));
        assertEquals(Incident.STATUS_CLOSED, closed.status());
        assertNotNull(closed.closedAt());
        assertEquals("t1-alice", closed.closedBy());

        var reopened = service.patch(ADMIN, inc.id(), status("open", null));
        assertEquals(Incident.STATUS_OPEN, reopened.status());
        assertNull(reopened.closedAt());
    }

    @Test
    void collaboratorsCanWriteDiagnosisButNotIncidentMetadata() {
        var inc = service.create(ALICE, create("cid-collab", "支付网关 502 排查")).incident();
        String diag = "{\"state\":\"done\",\"answer\":\"协作结论\"}";
        String timeline = "[{\"kind\":\"diagnosis\"}]";

        var saved = service.patch(BOB, inc.id(), diagnosis(diag, timeline));
        assertEquals(diag, saved.diagnosisJson());
        assertEquals(timeline, saved.timelineJson());

        var ex = assertThrows(ResponseStatusException.class,
                () -> service.patch(BOB, inc.id(), metadata("非负责人改标题")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode(),
                "同租户协作者只能写诊断/时间线，不能改事故元数据");
    }

    @Test
    void closeNoteRequiresResponsibilityPermission() {
        var inc = service.create(ALICE, create("cid-note", "支付网关 502 排查")).incident();
        var denied = assertThrows(ResponseStatusException.class,
                () -> service.patch(BOB, inc.id(), closeNote("协作者改写处置结论")));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode(),
                "closeNote 是结单的一部分，不能绕过 created_by / owner / platform");
        assertNull(service.get(ALICE, inc.id()).closeNote());

        assertEquals("已恢复", service.patch(ALICE, inc.id(), closeNote("已恢复")).closeNote());
    }

    @Test
    void diagnosisAndContentRoundTrip() {
        var inc = service.create(ALICE, create("cid-diag", "支付网关 502 排查")).incident();
        String diag = "{\"state\":\"done\",\"answer\":\"结论\"}";
        var saved = service.patch(ALICE, inc.id(),
                content("新标题", "支付网关 502 50041 排查", diag, "[{\"kind\":\"diagnosis\"}]"));
        assertEquals("新标题", saved.title());
        assertEquals(diag, saved.diagnosisJson());

        var reloaded = service.get(ALICE, inc.id());
        assertEquals(diag, reloaded.diagnosisJson());
        assertEquals("[{\"kind\":\"diagnosis\"}]", reloaded.timelineJson());

        List<Incident> list = service.list(ALICE);
        assertTrue(list.stream().anyMatch(i -> i.id().equals(inc.id()) && "新标题".equals(i.title())));
    }

    @Test
    void closedIncidentRejectsContentRewriteUntilReopened() {
        var inc = service.create(ALICE, create("cid-closed", "支付网关 502 排查")).incident();
        service.patch(ALICE, inc.id(), status("closed", "done"));
        var ex = assertThrows(ResponseStatusException.class, () -> service.patch(ALICE, inc.id(),
                content(null, "改写后的输入", "{}", null)));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void closedIncidentIsFrozenUntilReopened() {
        var inc = service.create(ALICE, create("cid-frozen", "支付网关 502 排查")).incident();
        service.patch(ALICE, inc.id(), status("closed", "done"));

        assertAll(
                () -> assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                        () -> service.patch(ALICE, inc.id(), metadata("结单后改标题"))).getStatusCode()),
                () -> assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                        () -> service.patch(ALICE, inc.id(), owner("t1-bob"))).getStatusCode()),
                () -> assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                        () -> service.patch(ALICE, inc.id(), priority("P1"))).getStatusCode()),
                () -> assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                        () -> service.patch(ALICE, inc.id(), closeNote("结单后改处置结论"))).getStatusCode()),
                () -> assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                        () -> service.patch(ALICE, inc.id(), status("closed", "再次结单"))).getStatusCode()));

        var frozen = service.get(ALICE, inc.id());
        assertEquals("标题", frozen.title(), "结单后标题不得被改写");
        assertEquals("t1-alice", frozen.owner(), "结单后负责人不得被改写");
        assertEquals("done", frozen.closeNote(), "结单后处置结论不得被改写");

        var mixed = assertThrows(ResponseStatusException.class, () -> service.patch(ADMIN, inc.id(),
                new IncidentRequests.Patch("重开并改标题", null, null, null, null, null,
                        "open", null, null, null, null, null)));
        assertEquals(HttpStatus.CONFLICT, mixed.getStatusCode(), "重开不得夹带内容修改");

        var reopened = service.patch(ADMIN, inc.id(), status("open", null));
        assertEquals(Incident.STATUS_OPEN, reopened.status());
        assertEquals("结单后改标题", service.patch(ALICE, inc.id(), metadata("结单后改标题")).title());
    }
}
