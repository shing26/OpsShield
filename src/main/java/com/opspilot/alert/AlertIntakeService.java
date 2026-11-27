package com.opspilot.alert;

import com.opspilot.auth.UserContext;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.incident.Incident;
import com.opspilot.incident.IncidentRequests;
import com.opspilot.incident.IncidentService;
import com.opspilot.incident.IncidentView;
import com.opspilot.metrics.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * 告警台账（ADR-0035）：把每一条真实告警请求落成可查、可一键建单的一行。
 *
 * <p><b>身份</b>：{@code (tenant, alertId)}。{@code alertId} 由桥按
 * {@code alertname|job|error_code|status} 派生、不含租户；面板跨租户可见，因此同一 alertId
 * 在两个租户是**两行**（契约 R1）。tenant 只来自 JWT，客户端永远不能指定。
 *
 * <p><b>写入是 best-effort</b>：{@link #record} 捕获全部异常并只记 warn——台账坏了不能拖垮
 * 排障（AC2）。计划清理同理：失败只 warn，留给下一次每日任务。
 */
@Service
public class AlertIntakeService {

    private static final Logger log = LoggerFactory.getLogger(AlertIntakeService.class);
    private static final int MAX_SUMMARY = 200;
    private static final int MAX_TRACE = 64;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final Set<String> PRIORITIES = Set.of("P1", "P2", "P3");

    private final AlertIntakeStore store;
    private final IncidentService incidents;
    private final AuditService audit;
    private final OpsPilotProperties props;
    private final ObjectMapper json;

    public AlertIntakeService(AlertIntakeStore store, IncidentService incidents, AuditService audit,
                              OpsPilotProperties props, ObjectMapper json) {
        this.store = store;
        this.incidents = incidents;
        this.audit = audit;
        this.props = props;
        this.json = json;
    }

    /**
     * 幂等写入一条告警（best-effort）：已存在则 {@code seen_count+1}、刷新
     * {@code last_seen / summary / service / env / error_code}；不存在则插入
     * {@code seen_count=1}。**任何异常只 warn，绝不向调用方抛。**
     */
    public void record(String tenant, String alertId, String traceId, String summary, String service,
                       String env, String errorCode, String alertStatus) {
        if (tenant == null || tenant.isBlank() || alertId == null || alertId.isBlank()) return;
        // 缺席（null/blank）与空串必须区分：touch 用 COALESCE 保留旧值，否则同一 alertId
        // 后续不带某字段的请求会把台账逐字段刷成空串，trace_id 与审计的关联键静默丢失（P1-1）。
        String s = truncOrNull(summary, MAX_SUMMARY);
        String t = truncOrNull(traceId, MAX_TRACE);
        String svc = truncOrNull(service, 128);
        String e = truncOrNull(env, 32);
        String code = truncOrNull(errorCode, 64);
        String status = truncOrNull(alertStatus, 32);
        Instant now = Instant.now();
        try {
            int updated = store.touch(tenant, alertId, t, s, svc, e, code, status, now);
            if (updated == 0) {
                try {
                    // insert 时 trace_id 落 null（列可空）；其余文本列 NOT NULL，缺席落空串默认值。
                    store.insert(new AlertIntake(tenant, alertId, t, orEmpty(svc), orEmpty(e),
                            orEmpty(code), orEmpty(s), orEmpty(status), now, now, 1, null));
                } catch (DuplicateKeyException race) {
                    // 并发首次写入：另一条请求已插入，补一次计数即可
                    store.touch(tenant, alertId, t, s, svc, e, code, status, now);
                }
            }
        } catch (Exception ex) {
            // 台账是诊断链路的副产物：坏了只留痕，不能让排障请求失败（AC2）
            log.warn("alert intake record failed (best-effort): tenant={} alertId={} err={}",
                    tenant, alertId, ex.toString());
        }
    }

    /**
     * 面板读取（AC4）：跨租户、{@code last_seen DESC}。返回
     * {@code {alerts, nextSince, retention_days, max_per_tenant, truncated}}。
     * 无数据时 {@code alerts=[]}，不造样例。
     */
    public Map<String, Object> recent(long sinceMs, int limit) {
        int cap = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<AlertIntake> rows = store.recent(Math.max(0L, sinceMs), cap + 1);
        boolean truncated = rows.size() > cap;
        if (truncated) rows = rows.subList(0, cap);
        long nextSince = rows.isEmpty()
                ? Math.max(0L, sinceMs)
                : rows.get(0).lastSeen().toEpochMilli();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alerts", rows.stream().map(this::alertView).toList());
        out.put("nextSince", nextSince);
        out.put("retention_days", props.alert().retentionDays());
        out.put("max_per_tenant", props.alert().maxPerTenant());
        out.put("truncated", truncated);
        return out;
    }

    /**
     * 历史回看（ADR-0047）：跨租户键集分页，顺序
     * {@code (last_seen DESC, tenant DESC, alert_id DESC)}。
     *
     * <p>游标只承载排序位置，不含授权语义；tenant 永远不是可见性开关。
     * 默认 limit=50，合法 1..200；非法游标/越界或非数字 limit 一律 400。
     */
    public Map<String, Object> history(String cursor, String limit) {
        int cap = parseHistoryLimit(limit);
        AlertHistoryCursor after = cursor == null ? null : AlertHistoryCursor.decode(cursor);
        List<AlertIntake> fetched = store.history(after, cap + 1);
        boolean hasMore = fetched.size() > cap;
        List<AlertIntake> kept = hasMore ? fetched.subList(0, cap) : fetched;
        String nextCursor = null;
        if (hasMore) {
            AlertIntake last = kept.get(kept.size() - 1);
            nextCursor = AlertHistoryCursor.encode(last.lastSeen(), last.tenant(), last.alertId());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alerts", kept.stream().map(this::alertView).toList());
        out.put("nextCursor", nextCursor);
        out.put("hasMore", hasMore);
        out.put("retention_days", props.alert().retentionDays());
        out.put("max_per_tenant", props.alert().maxPerTenant());
        return out;
    }

    /** limit 缺省 50，合法 1..200；空串、非数字、越界一律 400（只用于历史端点）。 */
    private static int parseHistoryLimit(String raw) {
        if (raw == null) return DEFAULT_HISTORY_LIMIT;
        String value = raw.trim();
        if (value.isEmpty() || !value.matches("\\d{1,3}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须是 1..200 的整数");
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > MAX_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit 必须是 1..200 的整数");
        }
        return parsed;
    }

    /**
     * 一键建单（AC5 / R1）：只认 {@code (tenant, alertId)} 已存在的告警行。
     * 事故字段全部取自台账行，不接受请求体拼装；首次 201 并回写 {@code incident_id}，
     * 重复调用返回同一 {@code id}（200）。建单租户 = 告警行所属租户，而非操作者 JWT 租户。
     */
    public IncidentService.Created createIncident(UserContext operator, String tenant, String alertId,
                                                  String priority) {
        String target = tenant == null ? "" : tenant.trim();
        if (target.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "tenant 必填：alertId 不含租户，同一告警在不同租户是不同事故");
        }
        AlertIntake row = store.find(target, alertId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "该租户没有这条告警；不凭空建单"));
        if (row.incidentId() != null && !row.incidentId().isBlank()) {
            var existing = incidents.findInTenant(target, row.incidentId());
            if (existing.isPresent()) return new IncidentService.Created(existing.get(), false);
        }
        String prio = priority == null ? "" : priority.trim();
        if (!prio.isEmpty() && !PRIORITIES.contains(prio)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "priority 只能是空 / P1 / P2 / P3");
        }
        if (row.summary() == null || row.summary().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "告警行缺少摘要，无法建单");
        }
        // 字段只来自 alert_intake；clientId 用告警身份派生，保证重复点击同一条告警不再造第二张单。
        IncidentRequests.Create req = new IncidentRequests.Create(
                "alert-" + row.alertId(), null, row.summary(), row.service(), row.errorCode(),
                row.env(), row.alertId(), "alert", prio);
        IncidentService.Created created = incidents.createForTenant(operator, target, req);
        store.markIncident(target, alertId, created.incident().id());
        audit.logAdmin(operator, "alerts/incident", "ok",
                "tenant=" + target + " alert=" + alertId + " id=" + created.incident().id());
        return created;
    }

    /** 与事故端点相同的视图形状（AdminController 直接回给面板）。 */
    public Map<String, Object> incidentView(Incident incident) {
        return IncidentView.of(incident, json);
    }

    /** 每日清理（AC6）：过期行 + 每租户超限的最旧行；阈值全部来自配置。 */
    public CleanupResult cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(props.alert().retentionDays()));
        int expired = store.deleteOlderThan(cutoff);
        int overflow = 0;
        for (String tenant : store.tenants()) {
            overflow += store.deleteOverflow(tenant, props.alert().maxPerTenant());
        }
        audit.logAdmin(null, "alert_retention", "ok",
                "expired=" + expired + " overflow=" + overflow);
        return new CleanupResult(expired, overflow);
    }

    @Scheduled(cron = "${opspilot.alert.retention-cron:0 17 3 * * *}")
    public void scheduledCleanup() {
        try {
            cleanup();
        } catch (Exception e) {
            log.warn("alert retention cleanup failed (will retry tomorrow): {}", e.toString());
        }
    }

    public record CleanupResult(int expired, int overflow) {
    }

    private Map<String, Object> alertView(AlertIntake a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", a.tenant());
        m.put("alertId", a.alertId());
        m.put("traceId", a.traceId());
        m.put("service", a.service());
        m.put("env", a.env());
        m.put("errorCode", a.errorCode());
        m.put("summary", a.summary());
        m.put("alertStatus", a.alertStatus());
        m.put("firstSeen", a.firstSeen() == null ? null : a.firstSeen().toEpochMilli());
        m.put("lastSeen", a.lastSeen() == null ? null : a.lastSeen().toEpochMilli());
        m.put("seenCount", a.seenCount());
        m.put("incidentId", a.incidentId());
        return m;
    }

    /**
     * 截断，但把「缺席」（null / 全空白）保留为 null 而不是空串：调用方（{@code touch}）
     * 依赖 null 让 {@code COALESCE} 保留旧值。空串仍是有效输入，照常落库。
     */
    static String truncOrNull(String s, int max) {
        if (s == null || s.isBlank()) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** insert 用：{@code alert_intake} 的文本列是 NOT NULL，缺席时落列默认值空串。 */
    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
