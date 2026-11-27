package com.opspilot.alert;

import com.opspilot.auth.UserContext;
import com.opspilot.gateway.AdminController;
import com.opspilot.metrics.AuditService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 告警历史键集分页（ADR-0047 / 契约 alert-history-cursor）。
 *
 * <p>钉住：全序并集无重复/无遗漏、同毫秒不同微秒可翻页、跨租户同名 alertId、
 * 非法游标 400、非平台 403、limit 边界、touch/删除后的快照边界。
 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:alerthistory;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "opspilot.alert.retention-days=30", "opspilot.alert.max-per-tenant=500"})
class AlertHistoryCursorTest {

    @org.springframework.test.context.DynamicPropertySource
    static void jwtSecret(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "alerthistory".repeat(3));
    }

    private static final UserContext PLATFORM = new UserContext("sre-full", "platform", 3, "tenant-internal");
    private static final UserContext CUSTOMER_L3 = new UserContext("sre-acme", "sre", 3, "tenant-acme");
    private static final Instant BASE = Instant.parse("2026-11-27T10:00:00Z");

    @Autowired AlertIntakeService service;
    @Autowired AlertIntakeStore store;
    @Autowired AdminController admin;
    @Autowired AuditService audit;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearLedger() {
        store.deleteOlderThan(Instant.now().plus(1, ChronoUnit.DAYS));
    }

    @Test
    void keysetPagesUnionEqualsFullOrderWithoutDuplicates() {
        insert("t-a", "dup", BASE);
        insert("t-b", "dup", BASE);
        insert("t-a", "same-1", BASE);
        insert("t-b", "a-mid", BASE.minusSeconds(1));
        insert("t-a", "z-old", BASE.minusSeconds(1));
        insert("t-a", "mid", BASE.minusMillis(1500));
        insert("t-a", "a-old", BASE.minusSeconds(2));
        insert("t-b", "z-old", BASE.minusSeconds(3));

        List<String> expected = store.history(null, 100).stream()
                .sorted(order()).map(AlertHistoryCursorTest::key).toList();
        assertEquals(8, expected.size());
        assertEquals("t-b/dup", expected.get(0), "同一 last_seen 必须按 tenant DESC, alertId DESC 定序");
        assertEquals("t-a/same-1", expected.get(1));
        assertEquals("t-a/dup", expected.get(2));

        List<String> seen = new ArrayList<>();
        String cursor = null;
        int pageNo = 0;
        while (true) {
            Map<String, Object> page = service.history(cursor, "2");
            List<Map<String, Object>> rows = rows(page);
            boolean hasMore = (Boolean) page.get("hasMore");
            String next = (String) page.get("nextCursor");
            assertEquals(hasMore, next != null, "hasMore=false 与 nextCursor=null 必须同时成立");
            assertTrue(rows.size() <= 2);
            for (int i = 0; i < rows.size(); i++) {
                assertEquals(expected.get(pageNo * 2 + i), key(rows.get(i)),
                        "第 " + pageNo + " 页第 " + i + " 行顺序必须与全序一致");
            }
            rows.forEach(r -> seen.add(key(r)));
            if (!hasMore) break;
            cursor = next;
            pageNo++;
            assertTrue(pageNo < 10, "分页必须收敛，不能死循环");
        }
        assertEquals(expected, seen, "并集必须等于全量且顺序一致");
        assertEquals(8, new LinkedHashSet<>(seen).size(), "翻页不得重复");
    }

    @Test
    void cursorUsesMicrosecondsSoSameMillisecondRowsDoNotDrop() {
        Instant early = BASE.plusNanos(123_456_000L);
        Instant late = BASE.plusNanos(123_457_000L); // 同一毫秒，晚 1 微秒
        insert("t-us", "early", early);
        insert("t-us", "late", late);

        AlertIntake earlyRow = store.find("t-us", "early").orElseThrow();
        AlertIntake lateRow = store.find("t-us", "late").orElseThrow();
        assertNotEquals(AlertHistoryCursor.toMicros(earlyRow.lastSeen()),
                AlertHistoryCursor.toMicros(lateRow.lastSeen()), "H2/PG 必须保留微秒差异");

        Map<String, Object> first = service.history(null, "1");
        assertEquals("late", rows(first).get(0).get("alertId"));
        String cursor = (String) first.get("nextCursor");
        assertEquals(AlertHistoryCursor.toMicros(lateRow.lastSeen()),
                AlertHistoryCursor.decode(cursor).epochMicros(),
                "游标时间必须取数据库回读行的微秒值，不得用毫秒截断");

        Map<String, Object> second = service.history(cursor, "1");
        assertEquals("early", rows(second).get(0).get("alertId"), "同一毫秒内相差 1 微秒的行不得丢");
        assertNull(second.get("nextCursor"));
        assertEquals(false, second.get("hasMore"));
    }

    @Test
    void bothEndpointsExposeMicrosecondSortKeyForSameMillisecondRows() {
        Instant early = BASE.plusNanos(222_456_000L);
        Instant late = BASE.plusNanos(222_457_000L);
        insert("t-ms", "early", early);
        insert("t-ms", "late", late);

        List<Map<String, Object>> historyRows = rows(service.history(null, "50"));
        List<Map<String, Object>> recentRows = rows(service.recent(0, 50));
        assertEquals(List.of("late", "early"),
                historyRows.stream().map(r -> r.get("alertId")).toList(),
                "history 顺序必须与微秒排序一致");
        assertEquals(List.of("late", "early"),
                recentRows.stream().map(r -> r.get("alertId")).toList(),
                "recent 顺序也必须一致");
        assertEquals(historyRows.get(0).get("lastSeen"), historyRows.get(1).get("lastSeen"),
                "两行必须落在同一毫秒（显示字段相同）");
        assertEquals(((Number) historyRows.get(0).get("lastSeenMicros")).longValue() - 1,
                ((Number) historyRows.get(1).get("lastSeenMicros")).longValue(),
                "lastSeenMicros 必须保留 1 微秒差异");
        assertEquals(historyRows, recentRows, "两个端点的行字段（含 lastSeenMicros）必须逐字段一致");

        AlertIntake lateRow = store.find("t-ms", "late").orElseThrow();
        assertEquals(AlertHistoryCursor.toMicros(lateRow.lastSeen()),
                ((Number) historyRows.get(0).get("lastSeenMicros")).longValue(),
                "lastSeenMicros 必须取数据库回读值");
    }

    @Test
    void invalidCursorsAreBadRequestAndNeverFiveHundred() {
        insert("t-x", "a", BASE);
        insert("t-x", "b", BASE.minusSeconds(1));
        String valid = (String) service.history(null, "1").get("nextCursor");
        assertNotNull(valid);

        List<String> bad = List.of(
                "not-base64!",
                "ab",
                valid.substring(0, Math.max(1, valid.length() / 2)),
                outer("v2|123|" + seg("t-x") + "|" + seg("a")),
                outer("v1|123|" + seg("t-x")),
                outer("v1|123||" + seg("a")),
                outer("v1|123|" + seg("t-x") + "|"),
                outer("v1|123|!!!|" + seg("a")),
                outer("v1|abc|" + seg("t-x") + "|" + seg("a")),
                outer("v1|-1|" + seg("t-x") + "|" + seg("a")),
                AlertHistoryCursor.encode(BASE, "t".repeat(65), "a"),
                AlertHistoryCursor.encode(BASE, "t", "a".repeat(129)));
        for (String cursor : bad) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.history(cursor, "50"), "非法游标必须 400: " + cursor);
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }

        assertDoesNotThrow(() -> AlertHistoryCursor.decode(
                AlertHistoryCursor.encode(BASE, "t".repeat(64), "a".repeat(128))),
                "tenant=64 / alertId=128 是合法上界");
    }

    @Test
    void nonPlatformIsForbiddenAndRefusalIsAuditedEvenWithValidCursor() {
        insert("t-gate", "a", BASE);
        insert("t-gate", "b", BASE.minusSeconds(1));
        String cursor = (String) service.history(null, "1").get("nextCursor");

        var forbidden = assertThrows(ResponseStatusException.class,
                () -> admin.alertsHistory(cursor, "50", withUser(CUSTOMER_L3)));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode(), "游标不能扩大可见范围");
        assertTrue(audit.recentSince(0, 200).events().stream().anyMatch(e ->
                        "alerts/history".equals(e.get("action")) && "refused".equals(e.get("outcome"))),
                "非平台身份被拒必须留痕");

        Map<String, Object> ok = admin.alertsHistory(cursor, "50", withUser(PLATFORM));
        assertEquals(1, rows(ok).size(), "平台身份带合法游标必须正常翻页");
    }

    @Test
    void limitDefaultsToFiftyAndRejectsOutOfRangeOrNonNumeric() {
        for (int i = 0; i < 51; i++) insert("t-limit", "a-" + i, BASE.minusSeconds(i));

        Map<String, Object> first = service.history(null, null);
        assertEquals(50, rows(first).size(), "limit 缺省必须是 50");
        assertEquals(true, first.get("hasMore"));
        assertNotNull(first.get("nextCursor"));

        assertEquals(1, rows(service.history(null, "1")).size());
        assertEquals(51, rows(service.history(null, "200")).size());
        assertEquals(false, service.history(null, "200").get("hasMore"));

        for (String bad : List.of("0", "201", "abc", "", "  ", "50.0", "-1")) {
            ResponseStatusException e = assertThrows(ResponseStatusException.class,
                    () -> service.history(null, bad), "limit 非法必须 400: " + bad);
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        }
    }

    @Test
    void historyResponseShapeMatchesRecentAndKeepsRecentBehaviorIntact() {
        insert("t-shape", "a", BASE);
        insert("t-shape", "b", BASE.minusSeconds(1));

        Map<String, Object> history = service.history(null, "50");
        assertEquals(List.of("alerts", "nextCursor", "hasMore", "retention_days", "max_per_tenant"),
                new ArrayList<>(history.keySet()), "历史响应字段与顺序固定");
        assertEquals(30, history.get("retention_days"));
        assertEquals(500, history.get("max_per_tenant"));
        assertFalse(history.containsKey("truncated"), "history 不复制 recent 的 truncated");
        assertTrue(rows(history).get(0).containsKey("lastSeenMicros"),
                "history 行必须暴露 lastSeenMicros");

        Map<String, Object> recent = service.recent(0, 50);
        assertEquals(List.of("alerts", "nextSince", "retention_days", "max_per_tenant", "truncated"),
                new ArrayList<>(recent.keySet()), "/alerts/recent 响应字段零改动");
        assertTrue(rows(recent).get(0).containsKey("lastSeenMicros"),
                "recent 行必须暴露 lastSeenMicros");
        assertEquals(rows(recent).get(0), rows(history).get(0), "每行字段必须与 recent 逐字段一致");

        var clamped = admin.alertsRecent(0, 0, withUser(PLATFORM));
        assertEquals(1, rows(clamped).size(), "/alerts/recent 的 limit 夹取行为不得改动");
        assertEquals(true, clamped.get("truncated"));
    }

    @Test
    void touchedUnloadedRowMovesToFirstPageAndDoesNotRepeat() {
        insert("t-touch", "p0", BASE);
        insert("t-touch", "p1", BASE.minusSeconds(1));
        insert("t-touch", "p2", BASE.minusSeconds(2));
        insert("t-touch", "p3", BASE.minusSeconds(3));

        Map<String, Object> first = service.history(null, "2");
        assertEquals(List.of("p0", "p1"), rows(first).stream().map(r -> r.get("alertId")).toList());
        String cursor = (String) first.get("nextCursor");

        store.touch("t-touch", "p3", null, null, null, null, null, null, BASE.plusSeconds(3600));
        Map<String, Object> second = service.history(cursor, "2");
        assertEquals(List.of("p2"), rows(second).stream().map(r -> r.get("alertId")).toList(),
                "被 touch 到最新的行不应在后续页重复出现");
        assertFalse(rows(second).stream().anyMatch(r -> "p3".equals(r.get("alertId"))));
        assertEquals(false, second.get("hasMore"));

        Map<String, Object> refreshed = service.history(null, "2");
        assertEquals(List.of("p3", "p0"), rows(refreshed).stream().map(r -> r.get("alertId")).toList(),
                "刷新后 touch 过的行必须出现在第一页");
    }

    @Test
    void deletingUnloadedRowDoesNotBreakPagingOrDuplicate() {
        insert("t-del", "p0", BASE);
        insert("t-del", "p1", BASE.minusSeconds(1));
        insert("t-del", "p2", BASE.minusSeconds(2));
        insert("t-del", "p3", BASE.minusSeconds(3));
        insert("t-del", "p4", BASE.minusSeconds(4));

        Map<String, Object> first = service.history(null, "2");
        String cursor = (String) first.get("nextCursor");
        jdbc.update("DELETE FROM alert_intake WHERE tenant = ? AND alert_id = ?", "t-del", "p2");

        List<String> paged = new ArrayList<>(rows(first).stream().map(r -> (String) r.get("alertId")).toList());
        String next = cursor;
        while (next != null) {
            Map<String, Object> page = service.history(next, "2");
            rows(page).forEach(r -> paged.add((String) r.get("alertId")));
            next = (String) page.get("nextCursor");
        }
        assertEquals(List.of("p0", "p1", "p3", "p4"), paged, "删除中间行后翻页不报错、不重复");
    }

    private void insert(String tenant, String alertId, Instant lastSeen) {
        store.insert(new AlertIntake(tenant, alertId, null, "svc", "prod", "", "摘要", "firing",
                lastSeen, lastSeen, 1, null));
    }

    private static Comparator<AlertIntake> order() {
        return Comparator.comparing(AlertIntake::lastSeen).reversed()
                .thenComparing(AlertIntake::tenant, Comparator.reverseOrder())
                .thenComparing(AlertIntake::alertId, Comparator.reverseOrder());
    }

    private static String key(AlertIntake row) {
        return row.tenant() + "/" + row.alertId();
    }

    private static String key(Map<String, Object> row) {
        return row.get("tenant") + "/" + row.get("alertId");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("alerts");
    }

    private static String seg(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String outer(String payload) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequest withUser(UserContext u) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.REQUEST_ATTR, u);
        return req;
    }
}
