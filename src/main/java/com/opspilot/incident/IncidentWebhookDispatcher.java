package com.opspilot.incident;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 可选群机器人外发（ADR-0037）。
 *
 * <p><b>边界</b>：只在事务提交后由调用方把已落库的通知交给这里；这里只做
 * "有界队列 + 单线程单次尝试"。不重试、不回落到调用线程、不阻塞请求线程——
 * webhook 慢或挂掉只影响外发，不影响站内通知与事故操作。
 *
 * <p>载荷只含事故 ID、标题、优先级、时限与事件类型；query、诊断正文、评论正文、
 * 引用片段禁止进入载荷。
 */
@Component
public class IncidentWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(IncidentWebhookDispatcher.class);

    private final IncidentNotificationProperties props;
    private final ObjectMapper json;
    private final IncidentNotificationMetrics metrics;

    private HttpClient http;
    private ThreadPoolExecutor executor;

    public IncidentWebhookDispatcher(IncidentNotificationProperties props, ObjectMapper json,
                                     IncidentNotificationMetrics metrics) {
        this.props = props;
        this.json = json;
        this.metrics = metrics;
    }

    @PostConstruct
    void init() {
        if (!props.webhookEnabled()) return;
        Duration timeout = Duration.ofMillis(props.webhookTimeoutMs());
        http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        AtomicInteger seq = new AtomicInteger();
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(props.queueCapacity()),
                r -> {
                    Thread t = new Thread(r, "incident-webhook-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdownNow();
    }

    /**
     * 入队一次外发。方法本身只做 O(1) 入队；队列满按丢弃计数，绝不回落到调用线程。
     */
    public void dispatch(IncidentNotification n) {
        if (!props.webhookEnabled()) return;
        if (props.quietAt(n.createdAt()) && !"P1".equals(n.priority())) {
            metrics.webhook("suppressed");
            // "没发"必须可查：指标给计数，日志给原因（唯一抑制原因是静默期）。
            log.info("incident webhook suppressed=quiet_hours incident={} priority={}",
                    n.incidentId(), n.priority());
            return;
        }
        if (executor == null) return;
        try {
            executor.execute(() -> send(n));
        } catch (RejectedExecutionException e) {
            metrics.webhook("dropped");
            log.warn("incident webhook queue full, dropped notification for {}", n.incidentId());
        }
    }

    private void send(IncidentNotification n) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(props.webhookUrl()))
                    .timeout(Duration.ofMillis(props.webhookTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload(n)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                metrics.webhook("ok");
            } else {
                metrics.webhook("fail");
                log.warn("incident webhook returned HTTP {} for notification {}",
                        response.statusCode(), n.incidentId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metrics.webhook("fail");
        } catch (Exception e) {
            metrics.webhook("fail");
            log.warn("incident webhook failed for notification {}: {}", n.incidentId(), e.toString());
        }
    }

    /** generic={"text":...}；feishu={"msg_type":"text","content":{"text":...}}。 */
    private String payload(IncidentNotification n) {
        String text = text(n);
        if (IncidentNotificationProperties.FORMAT_FEISHU.equals(props.webhookFormat())) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("text", text);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msg_type", "text");
            body.put("content", content);
            return json.writeValueAsString(body);
        }
        return json.writeValueAsString(Map.of("text", text));
    }

    static String text(IncidentNotification n) {
        String event = switch (n.kind()) {
            case IncidentNotification.KIND_ASSIGNED -> "指派";
            case IncidentNotification.KIND_SLA_DUE_SOON -> "SLA 临期";
            case IncidentNotification.KIND_SLA_BREACHED -> "SLA 超时";
            case IncidentNotification.KIND_REOPENED -> "重新打开";
            default -> n.kind();
        };
        String priority = n.priority() == null || n.priority().isBlank() ? "未定" : n.priority();
        String due = n.dueAt() == null ? "未设时限" : n.dueAt().toString();
        return "[OpsPilot] 事故通知 · " + event + "\n"
                + "事故：" + n.incidentId() + " " + n.title() + "\n"
                + "优先级：" + priority + "\n"
                + "时限：" + due;
    }
}
