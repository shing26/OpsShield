package com.opspilot.incident;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 群机器人外发（ADR-0037）：单次尝试、3s 级超时、有界队列、静默期与载荷边界。
 *
 * <p>用本机 HttpServer 而不是 mock：要证明的是"真的发了一次 HTTP"以及
 * "慢/满时请求线程不被拖住"，mock 掉传输层这两条都不成立。
 */
class IncidentWebhookDispatcherTest {

    private static final Instant NOON_SHANGHAI = Instant.parse("2026-11-25T04:00:00Z");
    private static final Instant NIGHT_SHANGHAI = Instant.parse("2026-11-25T15:00:00Z");

    private static IncidentWebhookDispatcher dispatcher(IncidentNotificationProperties props,
                                                        SimpleMeterRegistry registry) {
        IncidentWebhookDispatcher d = new IncidentWebhookDispatcher(props,
                JsonMapper.builder().build(), new IncidentNotificationMetrics(registry));
        d.init();
        return d;
    }

    private static IncidentNotification notification(String kind, String priority, Instant createdAt) {
        return new IncidentNotification(7L, "tenant-1", "t1-bob", "INC-1", kind,
                "支付网关 502", priority, null, "k", createdAt, null);
    }

    private static IncidentNotificationProperties props(String url, String format, int capacity,
                                                        long timeoutMs, String quiet) {
        return new IncidentNotificationProperties(url, format, capacity, timeoutMs, quiet,
                "Asia/Shanghai", 90, "0 17 4 * * *");
    }

    private static HttpServer server(AtomicReference<String> body, AtomicInteger hits,
                                     CountDownLatch latch, long delayMs) throws IOException {
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
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            latch.countDown();
        });
        server.start();
        return server;
    }

    @Test
    void genericPayloadIsSentOnceAndCarriesNoQueryText() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = server(body, hits, latch, 0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IncidentWebhookDispatcher d = dispatcher(
                props("http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                        "generic", 8, 3000, "22:00-08:00"), registry);
        try {
            d.dispatch(notification(IncidentNotification.KIND_ASSIGNED, "P2", NOON_SHANGHAI));
            assertTrue(latch.await(3, TimeUnit.SECONDS), "应在超时前收到一次 webhook");
            assertEquals(1, hits.get(), "单次尝试：恰好一次 POST");
            String json = body.get();
            assertTrue(json.contains("\"text\""), "generic 格式必须是 {\"text\":...}");
            assertTrue(json.contains("INC-1") && json.contains("支付网关 502"));
            assertFalse(json.contains("query") || json.contains("排查"), "载荷不得包含 query/诊断正文");
            long deadline = System.currentTimeMillis() + 3000;
            while (registry.counter("aiops.incident.notify.webhook", "result", "ok").count() < 1
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1.0, registry.counter("aiops.incident.notify.webhook", "result", "ok").count());
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void feishuPayloadUsesTextEnvelope() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicInteger hits = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        HttpServer server = server(body, hits, latch, 0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IncidentWebhookDispatcher d = dispatcher(
                props("http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                        "feishu", 8, 3000, "22:00-08:00"), registry);
        try {
            d.dispatch(notification(IncidentNotification.KIND_SLA_BREACHED, "P1", NOON_SHANGHAI));
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            String json = body.get();
            assertTrue(json.contains("\"msg_type\":\"text\""));
            assertTrue(json.contains("\"content\"") && json.contains("\"text\""));
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void quietHoursSuppressNonP1WithoutSending() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = server(new AtomicReference<>(), hits, new CountDownLatch(1), 0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IncidentWebhookDispatcher d = dispatcher(
                props("http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                        "generic", 8, 3000, "22:00-08:00"), registry);
        try {
            d.dispatch(notification(IncidentNotification.KIND_ASSIGNED, "P2", NIGHT_SHANGHAI));
            d.dispatch(notification(IncidentNotification.KIND_ASSIGNED, "P1", NIGHT_SHANGHAI));
            long deadline = System.currentTimeMillis() + 3000;
            while (hits.get() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertEquals(1, hits.get(), "P1 不受静默期约束，P2 必须不发");
            assertEquals(1.0, registry.counter("aiops.incident.notify.webhook", "result", "suppressed").count());
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void slowWebhookDoesNotBlockCallerAndTimesOut() throws Exception {
        HttpServer server = server(new AtomicReference<>(), new AtomicInteger(), new CountDownLatch(1), 2000);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IncidentWebhookDispatcher d = dispatcher(
                props("http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                        "generic", 8, 200, "22:00-08:00"), registry);
        try {
            long start = System.nanoTime();
            d.dispatch(notification(IncidentNotification.KIND_ASSIGNED, "P2", NOON_SHANGHAI));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < 150, "dispatch 只入队，不得阻塞请求线程（实际 " + elapsedMs + "ms）");

            long deadline = System.currentTimeMillis() + 3000;
            while (registry.counter("aiops.incident.notify.webhook", "result", "fail").count() < 1
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1.0, registry.counter("aiops.incident.notify.webhook", "result", "fail").count(),
                    "读取超时必须记 fail，且不重试");
        } finally {
            d.shutdown();
            server.stop(0);
        }
    }

    @Test
    void boundedQueueDropsInsteadOfFallingBackToCaller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            entered.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        IncidentWebhookDispatcher d = dispatcher(
                props("http://127.0.0.1:" + server.getAddress().getPort() + "/hook",
                        "generic", 1, 3000, "22:00-08:00"), registry);
        try {
            d.dispatch(notification(IncidentNotification.KIND_ASSIGNED, "P2", NOON_SHANGHAI));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            d.dispatch(notification(IncidentNotification.KIND_ASSIGNED, "P2", NOON_SHANGHAI));
            d.dispatch(notification(IncidentNotification.KIND_ASSIGNED, "P2", NOON_SHANGHAI));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(elapsedMs < 150, "队列满时不得回落到调用线程（实际 " + elapsedMs + "ms）");
            assertTrue(registry.counter("aiops.incident.notify.webhook", "result", "dropped").count() >= 1,
                    "溢出必须计入 dropped");
        } finally {
            release.countDown();
            d.shutdown();
            server.stop(0);
        }
    }
}
