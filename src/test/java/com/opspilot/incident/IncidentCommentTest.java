package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 事故评论（ADR-0034）的硬约束：append-only、并发不丢话、服务端 @ 解析、
 * 正文/条数边界、跨租户 404、评论不改事故字段。
 *
 * <p>这些判据一旦放松，讨论区就会退化成「后写赢的时间线」或「客户端说了算的点名」——
 * 正是契约里 forbidden 的两件事。
 */
@SpringBootTest(properties = {
        "opspilot.storage.mode=memory", "opspilot.dashscope.mode=mock",
        "opspilot.probes.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:incident-comments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.clean-disabled=true"})
class IncidentCommentTest {

    @org.springframework.test.context.DynamicPropertySource
    static void jwtSecret(org.springframework.test.context.DynamicPropertyRegistry r) {
        r.add("JWT_SECRET", () -> "incidentcomments".repeat(3));
    }

    @Autowired IncidentService service;
    @Autowired IncidentStore store;
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
        for (int i = 1; i <= 11; i++) {
            seed("t1-u" + String.format("%02d", i), "tenant-1", 3, "sre", false);
        }
    }

    private void seed(String sub, String tenant, int level, String role, boolean disabled) {
        if (users.find(sub) != null) return;
        users.insert(sub, "x", tenant, level, role);
        if (disabled) users.setDisabled(sub, true);
    }

    private Incident create(String clientId) {
        var req = new IncidentRequests.Create(clientId, "标题", "支付网关 502 排查",
                "payment-service", "50041_PAY_GATEWAY_502", "prod", "", "manual", "");
        return service.create(ALICE, req).incident();
    }

    private static IncidentRequests.Comment comment(String body) {
        return new IncidentRequests.Comment(body, null);
    }

    private static IncidentRequests.Comment comment(String body, List<String> clientMentions) {
        return new IncidentRequests.Comment(body, clientMentions);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("comments");
    }

    // ---- 追加语义 ----

    @Test
    void eachCommentIsOneInsertAndOrderingIsAscending() {
        Incident inc = create("cid-append");

        Map<String, Object> first = service.addComment(ALICE, inc.id(), comment("先看一眼连接池"));
        Map<String, Object> second = service.addComment(BOB, inc.id(), comment("命令执行过了，连接数已回落"));

        long firstId = ((Number) first.get("commentId")).longValue();
        long secondId = ((Number) second.get("commentId")).longValue();
        assertTrue(secondId > firstId, "append-only：后一条的 commentId 必须更大");

        Map<String, Object> page = service.listComments(ALICE, inc.id(), 0L);
        List<Map<String, Object>> rows = rows(page);
        assertEquals(2, rows.size(), "两条评论必须都在，不能被后写覆盖");
        assertEquals("先看一眼连接池", rows.get(0).get("body"));
        assertEquals("命令执行过了，连接数已回落", rows.get(1).get("body"));
        assertEquals("t1-alice", rows.get(0).get("author"));
        assertEquals("t1-bob", rows.get(1).get("author"));
        assertEquals(secondId, ((Number) page.get("nextSince")).longValue());
    }

    @Test
    void tenConcurrentCommentsAllSurvive() throws Exception {
        Incident inc = create("cid-concurrent");
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    Map<String, Object> saved = service.addComment(ALICE, inc.id(),
                            comment("并发评论 " + idx));
                    return ((Number) saved.get("commentId")).longValue();
                }));
            }
            start.countDown();
            for (Future<Long> f : futures) f.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Map<String, Object>> rows = rows(service.listComments(ALICE, inc.id(), 0L));
        assertEquals(n, rows.size(), "并发 10 条必须 10 条都在（insert-only，不是先查再写）");
        assertEquals(n, rows.stream().map(r -> r.get("body")).distinct().count(),
                "10 条正文必须各不相同，说明没有互相覆盖");
        assertEquals(n, rows.stream().map(r -> r.get("commentId")).distinct().count(),
                "10 条必须各自拿到独立 commentId");
    }

    // ---- 分页 ----

    @Test
    void sinceWatermarkReturnsOnlyNewerAndCapsAt100() {
        Incident inc = create("cid-page");
        long first = ((Number) service.addComment(ALICE, inc.id(), comment("第 1 条")).get("commentId"))
                .longValue();
        service.addComment(ALICE, inc.id(), comment("第 2 条"));
        service.addComment(ALICE, inc.id(), comment("第 3 条"));

        Map<String, Object> page = service.listComments(ALICE, inc.id(), first);
        assertEquals(2, rows(page).size(), "since 是 commentId 水位，只返回更新的评论");
        assertEquals("第 2 条", rows(page).get(0).get("body"));

        Incident big = create("cid-page-cap");
        for (int i = 0; i < 105; i++) {
            store.insertComment(ALICE.tenantId(), big.id(), ALICE.sub(), "批量 " + i, "[]",
                    Instant.now().plusMillis(i));
        }
        Map<String, Object> firstPage = service.listComments(ALICE, big.id(), 0L);
        assertEquals(100, rows(firstPage).size(), "单次读取上限 100");
        long next = ((Number) firstPage.get("nextSince")).longValue();
        Map<String, Object> secondPage = service.listComments(ALICE, big.id(), next);
        assertEquals(5, rows(secondPage).size());
    }

    // ---- 正文边界 ----

    @Test
    void bodyMustBeOneToTwoThousandCodePointsAfterTrim() {
        Incident inc = create("cid-body");

        for (String bad : new String[]{"", "   ", "\n\t "}) {
            var ex = assertThrows(ResponseStatusException.class,
                    () -> service.addComment(ALICE, inc.id(), comment(bad)));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode(), "空正文必须 400");
        }
        assertEquals(0, rows(service.listComments(ALICE, inc.id(), 0L)).size());

        String max = "字".repeat(2000);
        Map<String, Object> saved = service.addComment(ALICE, inc.id(), comment("  " + max + "  "));
        assertEquals(max, saved.get("body"), "去首尾空白后 2000 字应通过，并原样落库 trim 后的正文");

        var tooLong = assertThrows(ResponseStatusException.class,
                () -> service.addComment(ALICE, inc.id(), comment("字".repeat(2001))));
        assertEquals(HttpStatus.BAD_REQUEST, tooLong.getStatusCode());
    }

    @Test
    void perIncidentCapIs200AndNamesTheFallback() {
        Incident inc = create("cid-cap");
        for (int i = 0; i < 200; i++) {
            store.insertComment(ALICE.tenantId(), inc.id(), ALICE.sub(), "占位 " + i, "[]", Instant.now());
        }

        var ex = assertThrows(ResponseStatusException.class,
                () -> service.addComment(ALICE, inc.id(), comment("第 201 条")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("请在结单备注里汇总"),
                "409 必须提示「请在结单备注里汇总」，不能只说超限");
        assertEquals(200, store.countComments(ALICE.tenantId(), inc.id()));
    }

    // ---- @ 解析 ----

    @Test
    void mentionsAreParsedFromBodyServerSideAndStoredAsJsonArray() {
        Incident inc = create("cid-mention");

        Map<String, Object> saved = service.addComment(ALICE, inc.id(),
                comment("请 @t1-bob 复核，用户名里的连字符也要认"));

        assertEquals(List.of("t1-bob"), saved.get("mentions"));
        String raw = jdbc.queryForObject("SELECT mentions_json FROM incident_comments "
                + "WHERE tenant = ? AND incident_id = ?", String.class, ALICE.tenantId(), inc.id());
        assertEquals("[\"t1-bob\"]", raw, "落库必须是服务端解析出的 JSON 数组");
    }

    @Test
    void clientSuppliedMentionsAreOnlyAHintAndNeverAuthoritative() {
        Incident inc = create("cid-mention-hint");

        // 正文没有 @，但客户端硬塞一个数组：不得落库（否则前端可伪造点名）。
        Map<String, Object> saved = service.addComment(ALICE, inc.id(),
                comment("正文里没有点名", List.of("t1-bob")));
        assertEquals(List.of(), saved.get("mentions"));

        // 正文有 @，客户端数组不同：以正文为准。
        Map<String, Object> fromBody = service.addComment(ALICE, inc.id(),
                comment("@t1-bob 看下", List.of("t2-carol")));
        assertEquals(List.of("t1-bob"), fromBody.get("mentions"));
    }

    @Test
    void unknownDisabledAndCrossTenantMentionsAre400WithTheUsername() {
        Incident inc = create("cid-mention-invalid");

        var unknown = assertThrows(ResponseStatusException.class,
                () -> service.addComment(ALICE, inc.id(), comment("@nobody 看下")));
        assertEquals(HttpStatus.BAD_REQUEST, unknown.getStatusCode());
        assertTrue(unknown.getReason().contains("nobody"), "400 必须点名具体用户名");

        var disabled = assertThrows(ResponseStatusException.class,
                () -> service.addComment(ALICE, inc.id(), comment("@t1-off 看下")));
        assertEquals(HttpStatus.BAD_REQUEST, disabled.getStatusCode());
        assertTrue(disabled.getReason().contains("t1-off"));

        var crossTenant = assertThrows(ResponseStatusException.class,
                () -> service.addComment(ALICE, inc.id(), comment("@t2-carol 看下")));
        assertEquals(HttpStatus.BAD_REQUEST, crossTenant.getStatusCode());
        assertTrue(crossTenant.getReason().contains("t2-carol"));

        assertEquals(0, rows(service.listComments(ALICE, inc.id(), 0L)).size(),
                "解析失败不得留下半条评论");
    }

    @Test
    void mentionsAreDedupedAndCappedAtTen() {
        Incident inc = create("cid-mention-count");

        Map<String, Object> dup = service.addComment(ALICE, inc.id(),
                comment("@t1-bob @t1-bob @t1-alice"));
        assertEquals(List.of("t1-bob", "t1-alice"), dup.get("mentions"), "同一评论内去重且保持出现顺序");

        StringBuilder eleven = new StringBuilder();
        for (int i = 1; i <= 11; i++) eleven.append("@t1-u").append(String.format("%02d", i)).append(' ');
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.addComment(ALICE, inc.id(), comment(eleven.toString())));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("10"), "超过 10 人必须 400 并说明上限");
    }

    @Test
    void emailAddressesAreNotMistakenForMentions() {
        Incident inc = create("cid-mention-email");
        Map<String, Object> saved = service.addComment(ALICE, inc.id(),
                comment("邮件发到 ops@t1-bob.com，不需要点名"));
        assertEquals(List.of(), saved.get("mentions"));
    }

    // ---- 权限 / 租户 / 不变量 ----

    @Test
    void anySameTenantCollaboratorCanCommentButCrossTenantIs404() {
        Incident inc = create("cid-perm");

        // BOB 既不是 created_by 也不是 owner，仍可评论（协作者口径）。
        assertDoesNotThrow(() -> service.addComment(BOB, inc.id(), comment("我也补一条")));

        var denied = assertThrows(ResponseStatusException.class,
                () -> service.addComment(ACME, inc.id(), comment("跨租户插话")));
        assertEquals(HttpStatus.NOT_FOUND, denied.getStatusCode(), "跨租户必须 404，不泄漏存在性");

        var deniedRead = assertThrows(ResponseStatusException.class,
                () -> service.listComments(ACME, inc.id(), 0L));
        assertEquals(HttpStatus.NOT_FOUND, deniedRead.getStatusCode());

        assertEquals(1, rows(service.listComments(ALICE, inc.id(), 0L)).size(),
                "越权请求不得落任何评论");
    }

    @Test
    void commentingDoesNotTouchIncidentFieldsOrTimeline() {
        Incident before = create("cid-invariant");
        String timelineBefore = before.timelineJson();
        String diagnosisBefore = before.diagnosisJson();

        service.addComment(BOB, before.id(), comment("@t1-alice 看下这个"));
        Incident after = service.get(ALICE, before.id());

        assertEquals(before.status(), after.status());
        assertEquals(before.owner(), after.owner());
        assertEquals(before.priority(), after.priority());
        assertEquals(before.updatedAt(), after.updatedAt(), "评论不得改写事故的 updated_at");
        assertEquals(timelineBefore, after.timelineJson(), "评论不得写 timeline（那是状态快照）");
        assertEquals(diagnosisBefore, after.diagnosisJson());
        assertEquals(0, service.assignments(ALICE, before.id()).size(), "评论不得产生责任事实");
    }

    @Test
    void lastCommentIdsIsTenantScopedAndOnlyReturnsMaxId() {
        Incident mine = create("cid-watermark");
        Incident other = service.create(ACME, new IncidentRequests.Create("cid-watermark-acme", "标题",
                "别的租户排查", "", "", "", "", "manual", "")).incident();
        service.addComment(ALICE, mine.id(), comment("第一条"));
        Map<String, Object> second = service.addComment(ALICE, mine.id(), comment("第二条"));
        service.addComment(ACME, other.id(), comment("外租户"));

        Map<String, Long> ids = service.lastCommentIds(ALICE);
        assertEquals(((Number) second.get("commentId")).longValue(), ids.get(mine.id()));
        assertFalse(ids.containsKey(other.id()), "未读水位不得看到其他租户的事故");
    }
}
