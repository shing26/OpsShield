package com.opspilot.incident;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 外发路由与 outbox 写入（ADR-0045）。
 *
 * <p><b>事务</b>：{@link #onCreated} / {@link #onNotification} 只被事故变更事务调用，
 * 自己不开事务——outbox 行必须与事故行、站内通知行同生共死。
 *
 * <p><b>路由</b>：事件类型 × 频道 {@code kinds} × 优先级下限 × 静默期。被静默期抑制的
 * 事件不建行，但必须有指标与结构化日志；其余跳过（如优先级不足）是正常过滤，不计数。
 */
@Service
public class IncidentPushService {

    private static final Logger log = LoggerFactory.getLogger(IncidentPushService.class);
    private static final int CLEANUP_LIMIT = 5000;
    private static final int LIST_DEFAULT = 50;
    private static final int LIST_MAX = 200;
    private static final java.util.Set<String> STATUSES = java.util.Set.of(
            IncidentPushOutbox.STATUS_PENDING, IncidentPushOutbox.STATUS_SENT,
            IncidentPushOutbox.STATUS_DEAD);

    private final IncidentPushOutboxStore store;
    private final IncidentNotificationProperties props;
    private final IncidentNotificationMetrics metrics;

    public IncidentPushService(IncidentPushOutboxStore store,
                               IncidentNotificationProperties props,
                               IncidentNotificationMetrics metrics) {
        this.store = store;
        this.props = props;
        this.metrics = metrics;
    }

    /** 建单事件：事务内调用。先过 created-min-priority，再逐通道路由。 */
    public void onCreated(Incident incident) {
        onCreated(incident, Instant.now());
    }

    /** 显式时钟重载：测试用固定时刻证明静默期边界，生产走 {@link #onCreated(Incident)}。 */
    void onCreated(Incident incident, Instant now) {
        if (incident == null || !meets(incident.priority(), props.createdMinPriority())) return;
        enqueue(incident, IncidentPushOutbox.KIND_CREATED, "c:" + incident.id(),
                nz(incident.owner()), now);
    }

    /** 站内通知事件：事务内调用；同一 dedupeKey 的多收件人天然合并为一行/频道。 */
    public void onNotification(Incident incident, String kind, String dedupeKey, String recipient) {
        onNotification(incident, kind, dedupeKey, recipient, Instant.now());
    }

    void onNotification(Incident incident, String kind, String dedupeKey, String recipient, Instant now) {
        if (incident == null || kind == null || dedupeKey == null) return;
        String owner = incident.owner() == null || incident.owner().isBlank()
                ? nz(recipient) : incident.owner();
        enqueue(incident, kind, "n:" + dedupeKey, owner, now);
    }

    private void enqueue(Incident incident, String kind, String eventKey, String recipient, Instant now) {
        Map<String, IncidentNotificationProperties.Channel> channels = props.effectiveChannels();
        if (channels.isEmpty()) return;
        for (Map.Entry<String, IncidentNotificationProperties.Channel> entry : channels.entrySet()) {
            String channelId = entry.getKey();
            IncidentNotificationProperties.Channel channel = entry.getValue();
            if (!channel.enabled() || channel.url() == null || channel.url().isBlank()) continue;
            if (!meets(incident.priority(), channel.minPriority())) continue;
            if (!channel.kinds().isEmpty() && !channel.kinds().contains(kind)) continue;
            String window = channel.quietHours() == null || channel.quietHours().isBlank()
                    ? props.quietHours() : channel.quietHours();
            if (!"P1".equals(incident.priority()) && props.quietAt(now, window)) {
                suppressed(channelId, incident, kind);
                continue;
            }
            IncidentPushOutbox row = new IncidentPushOutbox(
                    0L, incident.tenant(), eventKey, incident.id(), kind, nz(recipient),
                    nz(incident.priority()), IncidentNotificationService.safeTitle(incident),
                    incident.dueAt(), channelId, IncidentPushOutbox.STATUS_PENDING, 0, now,
                    null, null, null, null, now);
            store.insertIfAbsent(row);
        }
    }

    private void suppressed(String channelId, Incident incident, String kind) {
        metrics.push(channelId, "suppressed");
        if (IncidentNotificationProperties.DEFAULT_CHANNEL.equals(channelId)) {
            metrics.webhook("suppressed");
        }
        log.info("incident push suppressed reason=quiet_hours tenant={} incident={} channel={} kind={}",
                incident.tenant(), incident.id(), channelId, kind);
    }

    /** 优先级序：P1 &lt; P2 &lt; P3 &lt; 空；数值越小越高。 */
    static int rank(String priority) {
        return switch (priority == null ? "" : priority) {
            case "P1" -> 1;
            case "P2" -> 2;
            case "P3" -> 3;
            default -> 4;
        };
    }

    /** 事件优先级是否达到下限（空优先级永远达不到任何下限）。 */
    static boolean meets(String priority, String minPriority) {
        int p = rank(priority);
        return p < 4 && p <= rank(minPriority);
    }

    // ---- 运维面 ----

    public List<IncidentPushOutbox> list(String tenant, String status, String channel, Integer limit) {
        if (tenant == null || tenant.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenant 必填");
        }
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        if (normalizedStatus != null && !STATUSES.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "status 只能是 pending/sent/dead");
        }
        int capped = limit == null ? LIST_DEFAULT : Math.max(1, Math.min(limit, LIST_MAX));
        return store.list(tenant.trim(), normalizedStatus,
                channel == null ? null : channel.trim(), capped);
    }

    /** 手动重试；跨租户/不存在 404，sent 409，其余非 dead/pending 也 409。 */
    @Transactional
    public IncidentPushOutbox retry(String tenant, long id) {
        if (tenant == null || tenant.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tenant 必填");
        }
        Optional<IncidentPushOutbox> found = store.find(tenant.trim(), id);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "投递记录不存在");
        }
        IncidentPushOutbox row = found.get();
        if (IncidentPushOutbox.STATUS_SENT.equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已投递的记录不能重试");
        }
        if (!IncidentPushOutbox.STATUS_DEAD.equals(row.status())
                && !IncidentPushOutbox.STATUS_PENDING.equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该状态不能重试");
        }
        int updated = store.resetForRetry(tenant.trim(), id, Instant.now());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "投递状态已被其他操作改变");
        }
        return store.find(tenant.trim(), id).orElseThrow();
    }

    public int cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(props.retentionDays()));
        return store.deleteTerminalOlderThan(cutoff, CLEANUP_LIMIT);
    }

    @Scheduled(cron = "${opspilot.incident.notify.push-retention-cron:0 27 4 * * *}")
    public void scheduledCleanup() {
        try {
            int deleted = cleanup();
            if (deleted > 0) log.info("incident push outbox retention: {} row(s) deleted", deleted);
        } catch (RuntimeException e) {
            log.warn("incident push outbox retention cleanup failed: {}", e.toString());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
