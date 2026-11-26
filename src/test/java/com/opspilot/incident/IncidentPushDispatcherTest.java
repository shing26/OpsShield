package com.opspilot.incident;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 持久化 outbox 投递（ADR-0045）：真实 HTTP、真实 JDBC，不 mock 传输层。
 *
 * <p>覆盖 200 / 5xx 退避 / 429 Retry-After / 400 死信 / 次数上限 / 重启续投 /
 * 302 不跟随 / 超时 / 载荷与签名 / 频道删除 / 每频道限速。
 */
class IncidentPushDispatcherTest {

    private static final Instant NOON = Instant.parse("2026-11-25T04:00:00Z");

    private record Fixture(DataSource ds, JdbcTemplate jdbc, IncidentPushOutboxStore store,
                           SimpleMeterRegistry registry) {}

    private static Fixture fixture() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:push-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        return new Fixture(ds, jdbc, new IncidentPushOutboxStore(jdbc), new SimpleMeterRegistry());
    }

    private static IncidentNotificationProperties props(
            String channelId, String url, String format, String secret) {
        Map<String, IncidentNotificationProperties.Channel> channels = channelId == null
                ? Map.of()
                : Map.of(channelId, new IncidentNotificationProperties.Channel(
                        format, url, secret, "P3", List.of(), "", true, 5));
        return new IncidentNotificationProperties("", "generic", 256, 3000, "22:00-08:00",
                "Asia/Shanghai", 90, "0 17 4 * * *", channels, "P2",
                "https://ui.example.test", "", 8, 1000, 300000, 1000, 20, 1000);
    }

    private static IncidentPushDispatcher dispatcher(IncidentNotificationProperties props,
                                                     Fixture f) {
        IncidentPushDispatcher d = new IncidentPushDispatcher(props, f.store(),
                new IncidentNotificationMetrics(f.registry()), JsonMapper.builder().build());
        d.init();
        return d;
    }

    private static IncidentPushOutbox row(String channel, String eventKey, int attempts) {
        return new IncidentPushOutbox(0L, "tenant-1", eventKey, "INC-1", "created", "t1-alice",
                "P1", "支付网关 502", null, channel, IncidentPushOutbox.STATUS_PENDING,
                attempts, NOON, null, null, null, null, NOON);
    }

    private static Incident incident(String id, String priority) {
        Instant at = Instant.parse("2026-11-25T04:00:00Z");
        return new Incident(id, "tenant-1", "cid-" + id, "支付网关 502", "secret-query", "payment",
                "50041", "prod", "", "manual", priority, Incident.STATUS_OPEN, "t1-alice", "t1-alice",
                at, at, null, null, null, null, null, null, Incident.SLA_OK);
    }

    private static HttpServer server(AtomicReference<String> body, AtomicInteger hits,
                                     CountDownLatch latch, int status, long delayMs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            hits.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            latch.countDown();
        });
        server.start();
        return server;
    }

    @Test
    void sendsOnceAndPayloadCarriesDeepLinkButNoQueryOrSecret() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = server(body, hits, latch, 200, 0);
        Fixture f = fixture();
        IncidentNotificationProperties props = props("alpha",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "generic", "s3cr3t");
        IncidentPushDispatcher d = dispatcher(props, f);
        try {
            f.store().insertIfAbsent(row("alpha", "c:INC-1", 0));
            assertEquals(1, d.pollOnce(NOON));
            assertTrue(latch.await(3, TimeUnit.SECONDS), "应收到一次 POST");
            assertEquals(1, hits.get());

            String raw = body.get();
            JsonNode json = JsonMapper.builder().build().readTree(raw);
            assertEquals("INC-1", json.get("incidentId").asString());
            assertTrue(json.get("url").asString().endsWith("/?inc=INC-1"), "载荷必须带深链");
            assertFalse(raw.contains("query"), "载荷不得包含 query");
            assertFalse(raw.contains("s3cr3t"), "载荷不得包含 secret");
            assertTrue(json.get("text").asString().contains("负责人：t1-alice"));

            IncidentPushOutbox saved = f.store().find("tenant-1", 1).orElseThrow();
            assertEquals(IncidentPushOutbox.STATUS_SENT, saved.status());
            assertEquals(1, saved.attempts());
            assertEquals(1.0, f.registry().counter("aiops.incident.push",
                    "channel", "alpha", "result", "ok").count());
            assertEquals(0.0, f.registry().counter("aiops.incident.notify.webhook",
                    "result", "ok").count(), "旧指标只对 default 通道映射，alpha 不应增加");
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void idempotencyKeyAndHmacHeadersAreSet() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> idem = new AtomicReference<>();
        AtomicReference<String> timestamp = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            idem.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            timestamp.set(exchange.getRequestHeaders().getFirst("X-OpsPilot-Timestamp"));
            signature.set(exchange.getRequestHeaders().getFirst("X-OpsPilot-Signature"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            latch.countDown();
        });
        server.start();
        Fixture f = fixture();
        String secret = "hmac-secret";
        IncidentPushDispatcher d = dispatcher(props("alpha",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "generic", secret), f);
        try {
            f.store().insertIfAbsent(row("alpha", "n:k-1", 0));
            d.pollOnce(NOON);
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals("n:k-1:alpha", idem.get());
            assertNotNull(timestamp.get());
            String expected = "sha256=" + IncidentPushPayload.signature(
                    secret, Long.parseLong(timestamp.get()), body.get());
            assertEquals(expected, signature.get(), "签名原文必须是 timestamp + '.' + rawBody");
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void feishuEnvelopeIsTextOnly() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = server(body, new AtomicInteger(), latch, 200, 0);
        Fixture f = fixture();
        IncidentPushDispatcher d = dispatcher(props("fs",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "feishu", ""), f);
        try {
            f.store().insertIfAbsent(row("fs", "c:INC-1", 0));
            d.pollOnce(NOON);
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            String raw = body.get();
            assertTrue(raw.contains("\"msg_type\":\"text\""));
            assertTrue(raw.contains("\"content\""));
            assertFalse(raw.contains("deliveryId"), "feishu 只允许 text 信封");
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void serverErrorRetriesWithBackoffAndEventuallySucceedsAfterRestart() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(new AtomicReference<>(), hits, new CountDownLatch(1), 500, 0);
        Fixture f = fixture();
        IncidentPushDispatcher d = dispatcher(props("alpha",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "generic", ""), f);
        try {
            f.store().insertIfAbsent(row("alpha", "c:INC-1", 0));
            assertEquals(1, d.pollOnce(NOON));
            IncidentPushOutbox after = f.store().find("tenant-1", 1).orElseThrow();
            assertEquals(IncidentPushOutbox.STATUS_PENDING, after.status());
            assertEquals(1, after.attempts());
            assertTrue(after.nextAttemptAt().isAfter(NOON), "5xx 必须排到未来重试");
            assertEquals(1.0, f.registry().counter("aiops.incident.push",
                    "channel", "alpha", "result", "retry").count());
            d.shutdown();

            // 换一个 dispatcher 实例（模拟重启），上游恢复 200 → 同一行继续投递。
            server.stop(0);
            CountDownLatch ok = new CountDownLatch(1);
            HttpServer healthy = server(new AtomicReference<>(), hits, ok, 200, 0);
            IncidentPushDispatcher restarted = dispatcher(props("alpha",
                    "http://127.0.0.1:" + healthy.getAddress().getPort() + "/hook", "generic", ""), f);
            try {
                restarted.pollOnce(after.nextAttemptAt().plusMillis(1));
                assertTrue(ok.await(3, TimeUnit.SECONDS), "重启后 pending 必须继续投递");
                assertEquals(IncidentPushOutbox.STATUS_SENT,
                        f.store().find("tenant-1", 1).orElseThrow().status());
                assertEquals(2, hits.get(), "同一行只成功投递一次（总数=失败1+成功1）");
            } finally {
                restarted.shutdown();
                healthy.stop(0);
            }
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void tooManyRequestsHonoursRetryAfter() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Retry-After", "2");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
            latch.countDown();
        });
        server.start();
        Fixture f = fixture();
        IncidentPushDispatcher d = dispatcher(props("alpha",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "generic", ""), f);
        try {
            f.store().insertIfAbsent(row("alpha", "c:INC-1", 0));
            d.pollOnce(NOON);
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            IncidentPushOutbox after = f.store().find("tenant-1", 1).orElseThrow();
            assertEquals(IncidentPushOutbox.STATUS_PENDING, after.status());
            assertFalse(after.nextAttemptAt().isBefore(NOON.plusSeconds(2)),
                    "Retry-After: 2 必须至少等到 2s 后");
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void clientErrorBecomesDeadWithoutRetry() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(new AtomicReference<>(), hits, new CountDownLatch(1), 400, 0);
        Fixture f = fixture();
        IncidentPushDispatcher d = dispatcher(props("alpha",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "generic", ""), f);
        try {
            f.store().insertIfAbsent(row("alpha", "c:INC-1", 0));
            d.pollOnce(NOON);
            IncidentPushOutbox after = f.store().find("tenant-1", 1).orElseThrow();
            assertEquals(IncidentPushOutbox.STATUS_DEAD, after.status());
            assertEquals(400, after.lastStatus());
            d.pollOnce(NOON.plusSeconds(3600));
            assertEquals(1, hits.get(), "dead 后不得重试");
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void attemptLimitBecomesDead() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(new AtomicReference<>(), hits, new CountDownLatch(1), 500, 0);
        Fixture f = fixture();
        IncidentPushDispatcher d = dispatcher(props("alpha",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "generic", ""), f);
        try {
            f.store().insertIfAbsent(row("alpha", "c:INC-1", 7));
            d.pollOnce(NOON);
            IncidentPushOutbox after = f.store().find("tenant-1", 1).orElseThrow();
            assertEquals(IncidentPushOutbox.STATUS_DEAD, after.status());
            assertEquals(8, after.attempts(), "第 8 次失败后必须 dead");
            assertEquals(1.0, f.registry().counter("aiops.incident.push",
                    "channel", "alpha", "result", "dead").count());
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void redirectIsNeverFollowed() throws Exception {
        AtomicInteger followed = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Location", "/other");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/other", exchange -> {
            followed.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        Fixture f = fixture();
        IncidentPushDispatcher d = dispatcher(props("alpha",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "generic", ""), f);
        try {
            f.store().insertIfAbsent(row("alpha", "c:INC-1", 0));
            d.pollOnce(NOON);
            Thread.sleep(100);
            assertEquals(0, followed.get(), "必须禁止重定向");
            assertEquals(IncidentPushOutbox.STATUS_DEAD,
                    f.store().find("tenant-1", 1).orElseThrow().status());
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void timeoutRetriesInsteadOfDying() throws Exception {
        HttpServer server = server(new AtomicReference<>(), new AtomicInteger(),
                new CountDownLatch(1), 200, 1500);
        Fixture f = fixture();
        IncidentNotificationProperties props = new IncidentNotificationProperties(
                "", "generic", 256, 3000, "22:00-08:00", "Asia/Shanghai", 90,
                "0 17 4 * * *",
                Map.of("alpha", new IncidentNotificationProperties.Channel(
                        "generic", "http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                        "", "P3", List.of(), "", true, 5)),
                "P2", "", "", 8, 1000, 300000, 200, 20, 1000);
        IncidentPushDispatcher d = dispatcher(props, f);
        try {
            f.store().insertIfAbsent(row("alpha", "c:INC-1", 0));
            d.pollOnce(NOON);
            IncidentPushOutbox after = f.store().find("tenant-1", 1).orElseThrow();
            assertEquals(IncidentPushOutbox.STATUS_PENDING, after.status());
            assertEquals(1, after.attempts());
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void removedChannelMarksPendingDead() {
        Fixture f = fixture();
        IncidentPushDispatcher d = dispatcher(props(null, "", "generic", ""), f);
        try {
            f.store().insertIfAbsent(row("removed", "c:INC-1", 0));
            d.pollOnce(NOON);
            IncidentPushOutbox after = f.store().find("tenant-1", 1).orElseThrow();
            assertEquals(IncidentPushOutbox.STATUS_DEAD, after.status());
            assertTrue(after.lastError().contains("channel not configured"));
        } finally {
            d.shutdown();
        }
    }

    @Test
    void perChannelRateLimitDefersExcessRows() {
        Fixture f = fixture();
        IncidentPushDispatcher d = dispatcher(props("alpha", "http://127.0.0.1:1/hook", "generic", ""), f);
        try {
            for (int i = 0; i < 8; i++) {
                f.store().insertIfAbsent(row("alpha", "c:INC-" + i, 0));
            }
            assertEquals(5, d.pollOnce(NOON), "默认 5 条/秒，第一轮最多尝试 5 行");
            assertEquals(5, f.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM incident_push_outbox WHERE attempts = 1", Integer.class));
        } finally {
            d.shutdown();
        }
    }

    @Test
    void pendingGaugeAndOldMetricBaselinesAreRegistered() {
        Fixture f = fixture();
        IncidentPushDispatcher d = dispatcher(props("alpha", "http://127.0.0.1:1/hook", "generic", ""), f);
        try {
            assertNotNull(f.registry().find("aiops.incident.push.pending").tag("channel", "alpha").gauge());
            assertEquals(0.0, f.registry().counter("aiops.incident.notify.webhook",
                    "result", "dropped").count(), "旧 dropped 序列保留为 0 基线，但不再产生");
        } finally {
            d.shutdown();
        }
    }

    @Test
    void sanitizerRemovesUrlQueryUserinfoSecretAndTruncates() {
        String userInfo = "fake" + "-pass";
        String url = "https://user:" + userInfo + "@alerts.example.test/hook?token=abc123";
        String secret = "fake" + "-secret";
        String raw = "POST " + url + " failed with " + secret + " " + "x".repeat(600);
        String safe = IncidentPushPayload.sanitize(raw, url, secret);
        assertFalse(safe.contains("abc123"), "query 必须脱敏");
        assertFalse(safe.contains(userInfo), "userinfo 必须脱敏");
        assertFalse(safe.contains(secret), "secret 必须脱敏");
        assertTrue(safe.length() <= 500, "last_error 必须截断 500");
        assertEquals("https://alerts.example.test/hook",
                IncidentPushPayload.safeUrl(url));
    }

    @Test
    void legacyWebhookUrlStillMapsToDefaultChannelAndOldMetric() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = server(new AtomicReference<>(), hits, latch, 200, 0);
        Fixture f = fixture();
        IncidentNotificationProperties props = new IncidentNotificationProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", "generic",
                256, 3000, "22:00-08:00", "Asia/Shanghai", 90, "0 17 4 * * *",
                Map.of(), "P2", "", "", 8, 1000, 300000, 1000, 20, 1000);
        IncidentPushDispatcher d = dispatcher(props, f);
        try {
            f.store().insertIfAbsent(row("default", "c:INC-1", 0));
            d.pollOnce(NOON);
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(1, hits.get());
            assertEquals(1.0, f.registry().counter("aiops.incident.notify.webhook",
                    "result", "ok").count(), "旧 webhook-url 的 default 通道仍映射旧指标");
            assertEquals(1.0, f.registry().counter("aiops.incident.push",
                    "channel", "default", "result", "ok").count());
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void quietHoursSuppressNonP1ButNeverP1() {
        Fixture f = fixture();
        IncidentNotificationProperties props = new IncidentNotificationProperties(
                "", "generic", 256, 3000, "22:00-08:00", "Asia/Shanghai", 90,
                "0 17 4 * * *",
                Map.of("quiet", new IncidentNotificationProperties.Channel(
                        "generic", "http://127.0.0.1:9/hook", "", "P3", List.of(), "", true, 5)),
                "P2", "", "", 8, 1000, 300000, 1000, 20, 1000);
        IncidentPushService service = new IncidentPushService(f.store(), props,
                new IncidentNotificationMetrics(f.registry()));
        Instant night = Instant.parse("2026-11-25T15:00:00Z"); // 23:00 Asia/Shanghai

        service.onCreated(incident("INC-Q2", "P2"), night);
        assertEquals(0, f.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident_push_outbox WHERE incident_id = 'INC-Q2'", Integer.class),
                "静默期内非 P1 不建行");
        assertEquals(1.0, f.registry().counter("aiops.incident.push",
                "channel", "quiet", "result", "suppressed").count());

        service.onCreated(incident("INC-Q1", "P1"), night);
        assertEquals(1, f.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident_push_outbox WHERE incident_id = 'INC-Q1'", Integer.class),
                "P1 不受静默期约束");
    }

    @Test
    void channelMinPriorityKindFilterAndDedupeApply() {
        Fixture f = fixture();
        IncidentNotificationProperties props = new IncidentNotificationProperties(
                "", "generic", 256, 3000, "22:00-08:00", "Asia/Shanghai", 90,
                "0 17 4 * * *",
                Map.of("p1only", new IncidentNotificationProperties.Channel(
                                "generic", "http://127.0.0.1:9/hook", "", "P1", List.of(), "", true, 5),
                        "assigned", new IncidentNotificationProperties.Channel(
                                "generic", "http://127.0.0.1:9/hook", "", "P3",
                                List.of(IncidentNotification.KIND_ASSIGNED), "", true, 5)),
                "P2", "", "", 8, 1000, 300000, 1000, 20, 1000);
        IncidentPushService service = new IncidentPushService(f.store(), props,
                new IncidentNotificationMetrics(f.registry()));
        Instant noon = Instant.parse("2026-11-25T04:00:00Z");

        service.onCreated(incident("INC-M2", "P2"), noon);
        assertEquals(0, f.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident_push_outbox WHERE incident_id = 'INC-M2'", Integer.class),
                "P2 不达 min-priority=P1，且 assigned 频道不接受 created");

        service.onCreated(incident("INC-M1", "P1"), noon);
        service.onCreated(incident("INC-M1", "P1"), noon);
        assertEquals(1, f.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident_push_outbox WHERE incident_id = 'INC-M1'", Integer.class),
                "同一 (tenant,event_key,channel) 重复入队是 no-op");

        service.onNotification(incident("INC-M1", "P1"), IncidentNotification.KIND_ASSIGNED,
                "assigned:INC-M1:t1-bob:1", "t1-bob", noon);
        assertEquals(1, f.jdbc().queryForObject(
                "SELECT COUNT(*) FROM incident_push_outbox WHERE incident_id = 'INC-M1'"
                        + " AND channel_id = 'assigned'", Integer.class));
    }
}
