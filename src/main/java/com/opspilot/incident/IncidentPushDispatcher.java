package com.opspilot.incident;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 持久化 outbox 投递器（ADR-0045）。取代 ADR-0037 的"有界队列 + 单次尝试"。
 *
 * <p><b>边界</b>：单实例、单线程 {@code @Scheduled}；不引入 Redis/MQ/分布式锁。
 * 请求线程只写 outbox，永不发 HTTP。任何失败都落回数据库（pending/dead），
 * 进程重启后 pending 行继续投递。
 *
 * <p><b>投递语义</b>：at-least-once。2xx sent；429 读 Retry-After；5xx/408/超时指数退避；
 * 其他 4xx/非法 URL/通道不存在 dead；attempts 达上限 dead。
 */
@Component
public class IncidentPushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(IncidentPushDispatcher.class);
    private static final long MAX_RETRY_AFTER_SECONDS = 300;

    private final IncidentNotificationProperties props;
    private final IncidentPushOutboxStore store;
    private final IncidentNotificationMetrics metrics;
    private final ObjectMapper json;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    private HttpClient http;

    public IncidentPushDispatcher(IncidentNotificationProperties props,
                                  IncidentPushOutboxStore store,
                                  IncidentNotificationMetrics metrics,
                                  ObjectMapper json) {
        this.props = props;
        this.store = store;
        this.metrics = metrics;
        this.json = json;
    }

    @PostConstruct
    void init() {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.requestTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        for (String channel : props.effectiveChannels().keySet()) {
            metrics.registerChannel(channel, () -> {
                try {
                    return store.countPending(channel);
                } catch (RuntimeException e) {
                    return 0;
                }
            });
        }
    }

    @PreDestroy
    void shutdown() {
        if (http != null) {
            try {
                http.close();
            } catch (RuntimeException ignored) {
                // 关闭失败不影响退出；在途请求由 JVM 生命周期回收。
            }
        }
    }

    /** 默认 1s 轮询；测试可把间隔调大后直接调用 {@link #pollOnce(Instant)}。 */
    @Scheduled(fixedDelayString = "${opspilot.incident.notify.poll-interval-ms:1000}",
            initialDelayString = "${opspilot.incident.notify.poll-initial-delay-ms:1000}")
    public void scheduledPoll() {
        try {
            pollOnce(Instant.now());
        } catch (RuntimeException e) {
            // 单轮失败不能拖死调度线程；下一轮会重新取同一批 pending。
            log.warn("incident push poll failed: {}", e.toString());
        }
    }

    /** 跑一轮；返回本轮实际尝试发送的行数（测试用）。 */
    public int pollOnce(Instant now) {
        List<IncidentPushOutbox> due = store.findDue(now, props.batchSize());
        Map<String, IncidentNotificationProperties.Channel> channels = props.effectiveChannels();
        int attempted = 0;
        for (IncidentPushOutbox row : due) {
            IncidentNotificationProperties.Channel channel = channels.get(row.channelId());
            if (channel == null || !channel.enabled()
                    || channel.url() == null || channel.url().isBlank()) {
                dead(row.id(), null, "channel not configured or disabled", row.channelId());
                continue;
            }
            if (row.attempts() >= props.maxAttempts()) {
                dead(row.id(), null, "max attempts reached", row.channelId());
                continue;
            }
            if (!bucket(row.channelId(), channel.rateLimitPerSecond()).tryAcquire()) continue;

            Instant claimNext = now.plusMillis(props.requestTimeoutMs());
            if (store.claim(row.id(), now, claimNext) != 1) {
                // 已被别的调度拿走，或状态已变：本轮不发。
                continue;
            }
            attempted++;
            send(row, channel, row.attempts() + 1, now);
        }
        return attempted;
    }

    private void send(IncidentPushOutbox row, IncidentNotificationProperties.Channel channel,
                      int attempt, Instant now) {
        URI uri;
        try {
            uri = URI.create(channel.url().trim());
            IncidentNotificationProperties.validateWebhookUrl(channel.url(), "runtime");
            List<String> allowed = props.allowedHostList();
            if (!allowed.isEmpty() && !allowed.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                dead(row.id(), null, "host not allowed: " + uri.getHost(), row.channelId());
                return;
            }
        } catch (RuntimeException e) {
            dead(row.id(), null, IncidentPushPayload.sanitize(e, channel.url(), channel.secret()),
                    row.channelId());
            return;
        }

        String url = IncidentPushPayload.deepLink(props.uiBaseUrl(), row.incidentId());
        String body = IncidentPushPayload.body(channel.format(), json,
                new IncidentPushPayload.Body(IncidentPushPayload.text(row, url),
                        row.incidentId(), row.kind(), row.priority(), row.recipient(),
                        url, row.id()));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(props.requestTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", row.eventKey() + ":" + row.channelId())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (channel.secret() != null && !channel.secret().isBlank()) {
            long timestamp = Instant.now().getEpochSecond();
            builder.header("X-OpsPilot-Timestamp", Long.toString(timestamp));
            builder.header("X-OpsPilot-Signature",
                    "sha256=" + IncidentPushPayload.signature(channel.secret(), timestamp, body));
        }

        long start = System.nanoTime();
        try {
            HttpResponse<Void> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());
            metrics.pushLatency(row.channelId(), System.nanoTime() - start);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                store.markSent(row.id(), status, Instant.now());
                metrics.push(row.channelId(), "ok");
                metrics.pushSuccess(row.channelId(), Instant.now());
                if (isDefault(row.channelId())) metrics.webhook("ok");
                log.info("incident push sent id={} incident={} channel={} status={}",
                        row.id(), row.incidentId(), row.channelId(), status);
                return;
            }
            Instant receivedAt = Instant.now();
            if (status == 429) {
                long delay = retryAfterSeconds(response, receivedAt)
                        .orElseGet(() -> backoffSeconds(attempt));
                Instant next = receivedAt.plusSeconds(delay);
                store.markRetry(row.id(), status, "HTTP 429; retry after " + delay + "s", next);
                metrics.push(row.channelId(), "retry");
                if (isDefault(row.channelId())) metrics.webhook("fail");
                log.info("incident push retry id={} incident={} channel={} status=429 delay_s={}",
                        row.id(), row.incidentId(), row.channelId(), delay);
                return;
            }
            if (status >= 500 || status == 408) {
                retryOrDead(row, attempt, status, "HTTP " + status);
                return;
            }
            dead(row.id(), status, "HTTP " + status, row.channelId());
        } catch (HttpTimeoutException e) {
            metrics.pushLatency(row.channelId(), System.nanoTime() - start);
            retryOrDead(row, attempt, null,
                    IncidentPushPayload.sanitize(e, channel.url(), channel.secret()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.pushLatency(row.channelId(), System.nanoTime() - start);
            retryOrDead(row, attempt, null, "interrupted");
        } catch (Exception e) {
            metrics.pushLatency(row.channelId(), System.nanoTime() - start);
            retryOrDead(row, attempt, null,
                    IncidentPushPayload.sanitize(e, channel.url(), channel.secret()));
        }
    }

    private void retryOrDead(IncidentPushOutbox row, int attempt, Integer status, String error) {
        if (attempt >= props.maxAttempts()) {
            dead(row.id(), status, error, row.channelId());
            return;
        }
        Instant next = Instant.now().plusSeconds(backoffSeconds(attempt));
        String safe = IncidentPushPayload.sanitize(error, null, null);
        store.markRetry(row.id(), status, safe, next);
        metrics.push(row.channelId(), "retry");
        if (isDefault(row.channelId())) metrics.webhook("fail");
        log.warn("incident push retry id={} incident={} channel={} attempt={} error={}",
                row.id(), row.incidentId(), row.channelId(), attempt, safe);
    }

    private void dead(long id, Integer status, String error, String channel) {
        String safe = IncidentPushPayload.sanitize(error, null, null);
        store.markDead(id, status, safe, Instant.now());
        metrics.push(channel, "dead");
        if (isDefault(channel)) metrics.webhook("fail");
        log.warn("incident push dead id={} channel={} status={} error={}", id, channel, status, safe);
    }

    /** 1s × 2^(n-1)，±20% jitter，封顶 max-backoff-ms。 */
    private long backoffSeconds(int attempt) {
        double base = props.baseBackoffMs() * Math.pow(2, Math.max(0, attempt - 1));
        double jitter = ThreadLocalRandom.current().nextDouble(0.8, 1.2);
        long millis = Math.min((long) (base * jitter), props.maxBackoffMs());
        return Math.max(1, (millis + 999) / 1000);
    }

    private java.util.Optional<Long> retryAfterSeconds(HttpResponse<?> response, Instant reference) {
        String raw = response.headers().firstValue("Retry-After").orElse("").trim();
        if (raw.isEmpty()) return java.util.Optional.empty();
        try {
            long seconds = Long.parseLong(raw);
            return java.util.Optional.of(clampRetryAfter(seconds));
        } catch (NumberFormatException ignored) {
            // HTTP-date 形态（RFC1123）
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = Math.max(0,
                    Duration.between(reference.atZone(ZoneOffset.UTC), at).getSeconds());
            return java.util.Optional.of(clampRetryAfter(seconds));
        } catch (DateTimeParseException e) {
            return java.util.Optional.empty();
        }
    }

    private static long clampRetryAfter(long seconds) {
        return Math.max(0, Math.min(seconds, MAX_RETRY_AFTER_SECONDS));
    }

    private TokenBucket bucket(String channel, int rate) {
        return buckets.computeIfAbsent(channel, ignored -> new TokenBucket(rate));
    }

    private static boolean isDefault(String channel) {
        return IncidentNotificationProperties.DEFAULT_CHANNEL.equals(channel);
    }

    /** 每频道令牌桶；单线程调度，简单同步即可。 */
    private static final class TokenBucket {
        private final int rate;
        private double tokens;
        private long lastNanos;

        TokenBucket(int rate) {
            this.rate = rate;
            this.tokens = rate;
            this.lastNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            double elapsed = (now - lastNanos) / 1_000_000_000.0;
            tokens = Math.min(rate, tokens + elapsed * rate);
            lastNanos = now;
            if (tokens < 1.0) return false;
            tokens -= 1.0;
            return true;
        }
    }
}
