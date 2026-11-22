package com.opspilot.alert;

import com.opspilot.auth.UserContext;
import com.opspilot.gateway.AdminController;
import com.opspilot.incident.Incident;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.IncidentStore;
import com.opspilot.metrics.AuditService;
import com.opspilot.config.OpsPilotProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 告警台账的硬约束（ADR-0035 / 契约 AC1-AC7）：
 * upsert 幂等与计数、跨租户两行、截断、面板形状与门禁、建单四态、字段只来自台账行、
 * 保留窗口边界（30 天 / 每租户 2 条——测试把 max-per-tenant 调小以便快速触发）。
 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:alertintake;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "opspilot.alert.retention-days=30", "opspilot.alert.max-per-tenant=2"})
class AlertIntakeServiceTest {

    @org.springframework.test.context.DynamicPropertySource
    static void jwtSecret(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "alertintake".repeat(3));
    }

    private static final UserContext PLATFORM = new UserContext("sre-full", "platform", 3, "tenant-internal");
    private static final UserContext CUSTOMER_L3 = new UserContext("sre-acme", "sre", 3, "tenant-acme");

    @Autowired AlertIntakeService service;
    @Autowired AlertIntakeStore store;
    @Autowired IncidentStore incidentStore;
    @Autowired IncidentService incidents;
    @Autowired AuditService audit;
    @Autowired AdminController admin;

    @BeforeEach
    void clearLedger() {
        // 用"未来时刻"清空全部行：测试之间不共享台账，避免 max-per-tenant=2 的互相干扰。
        store.deleteOlderThan(Instant.now().plus(1, ChronoUnit.HOURS));
    }

    @Test
    void recordIsIdempotentAndRefreshesMutableFields() {
        service.record("t-idem", "alert-1", "trace-aaaaaaaa", "第一版摘要", "svc-a", "dev", null, null);
        AlertIntake first = store.find("t-idem", "alert-1").orElseThrow();
        assertEquals(1, first.seenCount());
        assertEquals(first.firstSeen(), first.lastSeen());

        service.record("t-idem", "alert-1", "trace-bbbbbbbb", "第二版摘要", "svc-b", "prod", null, null);
        AlertIntake second = store.find("t-idem", "alert-1").orElseThrow();
        assertEquals(2, second.seenCount(), "同一条告警再次到达必须只 +1，不新增行");
        assertEquals(first.firstSeen(), second.firstSeen(), "first_seen 不得被刷新");
        assertFalse(second.lastSeen().isBefore(first.lastSeen()), "last_seen 必须前进");
        assertEquals("第二版摘要", second.summary());
        assertEquals("svc-b", second.service());
        assertEquals("prod", second.env());
    }

    @Test
    void sameAlertIdInTwoTenantsIsTwoRows() {
        service.record("t-a", "same-alert", null, "租户 A", "svc", "prod", null, null);
        service.record("t-b", "same-alert", null, "租户 B", "svc", "prod", null, null);
        assertTrue(store.find("t-a", "same-alert").isPresent());
        assertTrue(store.find("t-b", "same-alert").isPresent());

        Map<String, Object> page = service.recent(0, 50);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("alerts");
        assertEquals(2, rows.size(), "alertId 不含租户，两个租户必须是两行");
        assertEquals(List.of("t-a", "t-b"), rows.stream().map(r -> r.get("tenant")).sorted().toList());
    }

    @Test
    void summaryAndTraceAreTruncated() {
        service.record("t-trunc", "alert-trunc", "t".repeat(100), "x".repeat(300), "svc", "prod", null, null);
        AlertIntake row = store.find("t-trunc", "alert-trunc").orElseThrow();
        assertEquals(200, row.summary().length(), "summary 截 200 字");
        assertEquals(64, row.traceId().length(), "trace_id 截 64");
    }

    @Test
    void absentFieldsKeepPreviousValuesInsteadOfBeingBlanked() {
        // P1-1 回归：先带 trace/summary 等落库，再用同一 alertId 打一次"什么字段都没带"的请求。
        service.record("t-keep", "alert-keep", "am-8401327", "原始摘要", "svc-a", "prod", "E1", "firing");
        service.record("t-keep", "alert-keep", null, null, null, null, null, null);
        // blank 与 null 等价：缺席，不得覆盖旧值。
        service.record("t-keep", "alert-keep", "   ", "  ", "", null, " ", "");

        AlertIntake row = store.find("t-keep", "alert-keep").orElseThrow();
        assertEquals(3, row.seenCount(), "同一条告警每次到达只 +1");
        assertEquals("am-8401327", row.traceId(), "缺席的 trace_id 必须保留旧值（审计关联键不能丢）");
        assertEquals("原始摘要", row.summary(), "缺席的 summary 必须保留旧值");
        assertEquals("svc-a", row.service(), "缺席的 service 必须保留旧值");
        assertEquals("prod", row.env(), "缺席的 env 必须保留旧值");
        assertEquals("E1", row.errorCode(), "缺席的 error_code 必须保留旧值");
        assertEquals("firing", row.alertStatus(), "缺席的 alert_status 必须保留旧值");
    }

    @Test
    void firstRecordWithAbsentTraceStoresNullNotEmptyString() {
        service.record("t-null", "alert-null", null, "摘要", "svc", "prod", null, null);
        AlertIntake row = store.find("t-null", "alert-null").orElseThrow();
        assertNull(row.traceId(), "首次就缺席的 trace_id 必须落 null（不是空串）");
        assertEquals(1, row.seenCount());
    }

    @Test
    void recentReturnsEmptyArrayAndRetentionWindowWithoutSamples() {
        Map<String, Object> page = service.recent(0, 50);
        assertEquals(List.of(), page.get("alerts"), "无告警必须返回空数组，不造样例");
        assertEquals(0L, page.get("nextSince"));
        assertEquals(30, page.get("retention_days"));
        assertEquals(2, page.get("max_per_tenant"));
        assertEquals(false, page.get("truncated"));
    }

    @Test
    void recentIsCrossTenantDescendingAndBounded() {
        Instant now = Instant.now();
        insert("t-r", "a-old", now.minus(3, ChronoUnit.HOURS));
        insert("t-r", "b-mid", now.minus(2, ChronoUnit.HOURS));
        insert("t-s", "c-new", now.minus(1, ChronoUnit.HOURS));

        Map<String, Object> page = service.recent(0, 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("alerts");
        assertEquals(2, rows.size());
        assertEquals("c-new", rows.get(0).get("alertId"), "必须 last_seen DESC 且跨租户");
        assertEquals("b-mid", rows.get(1).get("alertId"));
        assertEquals(true, page.get("truncated"), "超过 limit 必须显式 truncated");
        assertTrue(rows.get(0).containsKey("tenant"));
    }

    @Test
    void cleanupDeletesExpiredRowsAndPerTenantOverflow() {
        Instant now = Instant.now();
        insert("t-keep", "expired", now.minus(31, ChronoUnit.DAYS));
        insert("t-keep", "oldest", now.minus(3, ChronoUnit.HOURS));
        insert("t-keep", "middle", now.minus(2, ChronoUnit.HOURS));
        insert("t-keep", "newest", now.minus(1, ChronoUnit.HOURS));

        AlertIntakeService.CleanupResult r = service.cleanup();
        assertEquals(1, r.expired(), "超过 30 天的行必须删除");
        assertEquals(1, r.overflow(), "每租户最多 2 条，最旧的一条必须删除");
        assertTrue(store.find("t-keep", "expired").isEmpty());
        assertTrue(store.find("t-keep", "oldest").isEmpty());
        assertTrue(store.find("t-keep", "middle").isPresent());
        assertTrue(store.find("t-keep", "newest").isPresent());

        boolean audited = audit.recentSince(0, 200).events().stream()
                .anyMatch(e -> "alert_retention".equals(e.get("action")));
        assertTrue(audited, "清理必须落 ev=admin action=alert_retention");
    }

    @Test
    void createIncidentUsesAlertRowFieldsAndIsIdempotent() {
        service.record("t-create", "alert-create", "trace-x", "支付网关 502", "payment-service",
                "prod", "50041_PAY_GATEWAY_502", "firing");

        IncidentService.Created first = service.createIncident(PLATFORM, "t-create", "alert-create", "P2");
        assertTrue(first.created(), "首次建单返回 created=true（控制器 201）");
        Incident inc = first.incident();
        assertEquals("t-create", inc.tenant(), "建单租户必须是告警行所属租户，不是平台 JWT 租户");
        assertEquals("alert", inc.source());
        assertEquals("alert-create", inc.alertId());
        assertEquals("payment-service", inc.service());
        assertEquals("50041_PAY_GATEWAY_502", inc.errorCode());
        assertEquals("prod", inc.env());
        assertEquals("支付网关 502", inc.query(), "query=summary，来自台账行");
        assertEquals("P2", inc.priority());
        assertEquals("sre-full", inc.createdBy(), "created_by 记实际操作人");
        assertEquals(inc.id(), store.find("t-create", "alert-create").orElseThrow().incidentId());

        IncidentService.Created again = service.createIncident(PLATFORM, "t-create", "alert-create", "");
        assertFalse(again.created(), "重复调用返回 created=false（控制器 200）");
        assertEquals(inc.id(), again.incident().id(), "重复点击不得产生第二张事故单");
        assertEquals("P2", again.incident().priority(), "已存在事故单以库中 incident_id 为准");
    }

    @Test
    void createIncidentPersistsAlertDerivedClientId() {
        // P1-2 回归：幂等键必须是 "alert-" + alertId，而不是前端随机 UUID 或 null。
        service.record("t-client", "alert-client", null, "客户端键", "svc", "prod", null, null);
        service.createIncident(PLATFORM, "t-client", "alert-client", "");

        var saved = incidentStore.findByClientId("t-client", "alert-alert-client");
        assertTrue(saved.isPresent(), "事故单必须以 client_id=\"alert-\"+alertId 落库");
        assertEquals("alert-alert-client", saved.orElseThrow().clientId());
    }

    @Test
    void concurrentCreateIncidentOnSameAlertYieldsExactlyOneIncident() throws Exception {
        // P1-2 回归：该行 incident_id 初始为 null，8 个线程同时建单必须只落一张。
        // 不能让用例先建一张再并发（那样第二次起会被 incidentId 短路，测不到幂等键）。
        service.record("t-race", "alert-race", null, "并发建单", "svc", "prod", null, null);
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IncidentService.Created>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.createIncident(PLATFORM, "t-race", "alert-race", "");
                }));
            }
            start.countDown();
            List<IncidentService.Created> results = new ArrayList<>();
            for (Future<IncidentService.Created> f : futures) results.add(f.get(30, TimeUnit.SECONDS));

            Set<String> ids = results.stream().map(r -> r.incident().id()).collect(Collectors.toSet());
            assertEquals(1, ids.size(), "同一告警并发建单必须返回同一张事故单");
            assertEquals(1L, results.stream().filter(IncidentService.Created::created).count(),
                    "8 个并发调用里只有一个真正创建，其余走幂等重放");
            assertEquals(1L, incidentStore.list("t-race", 60).stream()
                            .filter(i -> "alert-race".equals(i.alertId())).count(),
                    "同一告警并发建单只能落一张事故单");
            assertEquals("alert-alert-race",
                    incidentStore.findByClientId("t-race", "alert-alert-race").orElseThrow().clientId());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void createIncidentRejectsUnknownTenantPairAndBadPriority() {
        service.record("t-404", "alert-404", null, "摘要", "svc", "prod", null, null);

        var missing = assertThrows(ResponseStatusException.class,
                () -> service.createIncident(PLATFORM, null, "alert-404", ""));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());
        assertTrue(missing.getReason().contains("同一告警在不同租户是不同事故"),
                "缺 tenant 的错误信息必须点明租户维度（R1）");

        var wrongTenant = assertThrows(ResponseStatusException.class,
                () -> service.createIncident(PLATFORM, "t-other", "alert-404", ""));
        assertEquals(HttpStatus.NOT_FOUND, wrongTenant.getStatusCode(), "(tenant, alertId) 未命中必须 404");

        var badPriority = assertThrows(ResponseStatusException.class,
                () -> service.createIncident(PLATFORM, "t-404", "alert-404", "P9"));
        assertEquals(HttpStatus.BAD_REQUEST, badPriority.getStatusCode());
    }

    @Test
    void adminGateMatchesStateAndMissingTenantIsFourHundred() {
        MockHttpServletRequest platform = withUser(PLATFORM);
        MockHttpServletRequest customer = withUser(CUSTOMER_L3);

        var forbidden = assertThrows(ResponseStatusException.class,
                () -> admin.alertsRecent(0, 50, customer));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode(), "非平台身份读告警面板必须 403");

        var noTenant = assertThrows(ResponseStatusException.class,
                () -> admin.alertIncident("a", null, Map.of(), platform));
        assertEquals(HttpStatus.BAD_REQUEST, noTenant.getStatusCode());

        var createForbidden = assertThrows(ResponseStatusException.class,
                () -> admin.alertIncident("a", "t-x", Map.of(), customer));
        assertEquals(HttpStatus.FORBIDDEN, createForbidden.getStatusCode(), "非平台身份建单必须 403");
    }

    @Test
    void adminCreateIgnoresRequestBodyFieldsAndReturns201Then200() {
        service.record("t-http", "alert-http", null, "台账里的摘要", "台账服务", "staging", "E-HTTP", "firing");
        MockHttpServletRequest platform = withUser(PLATFORM);

        // 恶意/冗余请求体：service/query/errorCode 都不得覆盖台账行（契约 AC5）。
        var evil = Map.<String, Object>of(
                "priority", "P1", "service", "伪造服务", "query", "伪造问题", "errorCode", "伪造码");
        var first = admin.alertIncident("alert-http", "t-http", evil, platform);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        assertEquals("台账服务", first.getBody().get("service"));
        assertEquals("台账里的摘要", first.getBody().get("query"));
        assertEquals("E-HTTP", first.getBody().get("errorCode"));
        assertEquals("P1", first.getBody().get("priority"));

        var second = admin.alertIncident("alert-http", "t-http", Map.of(), platform);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertEquals(first.getBody().get("id"), second.getBody().get("id"));
    }

    private void insert(String tenant, String alertId, Instant lastSeen) {
        store.insert(new AlertIntake(tenant, alertId, null, "svc", "prod", "", "摘要", "firing",
                lastSeen, lastSeen, 1, null));
    }

    private static MockHttpServletRequest withUser(UserContext u) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.REQUEST_ATTR, u);
        return req;
    }
}
