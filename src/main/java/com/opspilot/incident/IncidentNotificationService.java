package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * 事故通知到人（ADR-0037）：站内持久化 + 可选群机器人外发。
 *
 * <p><b>原子性</b>：三个 {@code on*} 入口都是 {@code MANDATORY}——它们只能在
 * {@link IncidentService#transfer}/{@link IncidentService#patch} 与
 * {@link IncidentSlaScanner#scan} 的事务里被调用。事故变更回滚时通知行一起消失，
 * 不会出现"事故改了没人被通知"或"通知发了事故没改"。
 *
 * <p><b>去重</b>：唯一键 {@code (tenant, recipient, dedupe_key)} 冲突按正常分支吞掉；
 * 去重键里只含事故 ID、事件与分钟桶，不含任何用户输入正文。
 *
 * <p><b>外发</b>：只有真正落库的通知才注册 {@code afterCommit} 回调；外发失败
 * 只记 WARN 与指标，不影响接口结果，也不回滚事务。
 */
@Service
public class IncidentNotificationService {

    private static final Logger log = LoggerFactory.getLogger(IncidentNotificationService.class);
    private static final int CLEANUP_LIMIT = 5000;
    private static final int LIST_DEFAULT = 50;
    private static final int LIST_MAX = 100;

    private final IncidentNotificationStore store;
    private final UserStore users;
    private final IncidentNotificationMetrics metrics;
    private final IncidentWebhookDispatcher dispatcher;
    private final IncidentNotificationProperties props;

    public IncidentNotificationService(IncidentNotificationStore store, UserStore users,
                                       IncidentNotificationMetrics metrics,
                                       IncidentWebhookDispatcher dispatcher,
                                       IncidentNotificationProperties props) {
        this.store = store;
        this.users = users;
        this.metrics = metrics;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    // ---- 写入面：只能挂在事故变更的事务上 ----

    /** 指派/转交：只通知新负责人；操作人本人不收。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onAssigned(UserContext actor, Incident incident, String toOwner) {
        String recipient = blankToNull(toOwner);
        if (recipient == null || recipient.equals(actor.sub())) return;
        Instant now = Instant.now();
        String dedupe = "assigned:" + incident.id() + ":" + recipient + ":" + minuteBucket(now);
        create(incident, List.of(recipient), IncidentNotification.KIND_ASSIGNED, dedupe, now);
    }

    /** 重开：当前负责人 + 创建人；操作人本人不收；同一事件同一人只一行。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReopened(UserContext actor, Incident incident) {
        Instant now = Instant.now();
        List<String> recipients = recipients(incident, actor.sub());
        String dedupe = "reopened:" + incident.id() + ":" + minuteBucket(now);
        create(incident, recipients, IncidentNotification.KIND_REOPENED, dedupe, now);
    }

    /** SLA 跃迁：当前负责人 + 创建人；空负责人只发创建人。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onSla(Incident incident, String state) {
        String kind = switch (state) {
            case Incident.SLA_DUE_SOON -> IncidentNotification.KIND_SLA_DUE_SOON;
            case Incident.SLA_BREACHED -> IncidentNotification.KIND_SLA_BREACHED;
            default -> null;
        };
        if (kind == null) return;
        Instant now = Instant.now();
        String dedupe = "sla:" + incident.id() + ":" + state;
        create(incident, recipients(incident, null), kind, dedupe, now);
    }

    private void create(Incident incident, List<String> recipients, String kind,
                        String dedupeKey, Instant now) {
        if (recipients.isEmpty()) return;
        List<IncidentNotification> inserted = new ArrayList<>();
        for (String recipient : recipients) {
            // 收件人必须在本租户且启用；被猜到的跨租户 sub 不会收到任何行。
            if (!receivable(incident.tenant(), recipient)) continue;
            IncidentNotification row = new IncidentNotification(0L, incident.tenant(), recipient,
                    incident.id(), kind, incident.title(), nz(incident.priority()),
                    incident.dueAt(), dedupeKey, now, null);
            if (store.insert(row)) {
                metrics.notification(kind);
                inserted.add(row);
            }
        }
        if (!inserted.isEmpty()) afterCommit(inserted);
    }

    /** 外发必须在提交后：回滚的事务里不产生任何 webhook 请求。 */
    private void afterCommit(List<IncidentNotification> rows) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            rows.forEach(dispatcher::dispatch);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                rows.forEach(dispatcher::dispatch);
            }
        });
    }

    private static List<String> recipients(Incident incident, String exclude) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addRecipient(out, incident.owner(), exclude);
        addRecipient(out, incident.createdBy(), exclude);
        return new ArrayList<>(out);
    }

    private static void addRecipient(LinkedHashSet<String> out, String sub, String exclude) {
        String value = blankToNull(sub);
        if (value != null && !value.equals(exclude)) out.add(value);
    }

    private boolean receivable(String tenant, String sub) {
        UserStore.User u = users.find(sub);
        return u != null && !u.disabled() && tenant.equals(u.tenant());
    }

    private static long minuteBucket(Instant now) {
        return now.getEpochSecond() / 60;
    }

    // ---- 读取/已读面：tenant 与 recipient 只来自 JWT ----

    public List<IncidentNotification> list(UserContext user, boolean unreadOnly, Integer limit) {
        requireUser(user);
        int cap = limit == null ? LIST_DEFAULT : Math.max(1, Math.min(limit, LIST_MAX));
        return store.list(user.tenantId(), user.sub(), unreadOnly, cap);
    }

    public int unread(UserContext user) {
        requireUser(user);
        return store.unreadCount(user.tenantId(), user.sub());
    }

    /** 返回 false 仅表示"不存在或不是本人"；已读过仍返回 true（幂等 204）。 */
    @Transactional
    public boolean markRead(UserContext user, long id) {
        requireUser(user);
        Instant now = Instant.now();
        int updated = store.markRead(user.tenantId(), user.sub(), id, now);
        return updated > 0 || store.exists(user.tenantId(), user.sub(), id);
    }

    @Transactional
    public int readAll(UserContext user) {
        requireUser(user);
        return store.markAllRead(user.tenantId(), user.sub(), Instant.now());
    }

    // ---- 留存 ----

    @Transactional
    public int cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(props.retentionDays()));
        return store.deleteOlderThan(cutoff, CLEANUP_LIMIT);
    }

    @Scheduled(cron = "${opspilot.incident.notify.retention-cron:0 17 4 * * *}")
    public void scheduledCleanup() {
        try {
            int deleted = cleanup();
            if (deleted > 0) log.info("incident notification retention: {} row(s) deleted", deleted);
        } catch (RuntimeException e) {
            // 清理失败只 WARN：留存不是在线请求的前置条件，下一轮会再试。
            log.warn("incident notification retention cleanup failed: {}", e.toString());
        }
    }

    private static void requireUser(UserContext user) {
        if (user == null || user.authLevel() < 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "auth_level 非法（须 >=1）");
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
