package com.opspilot.incident;

import com.opspilot.auth.UserContext;
import com.opspilot.metrics.AuditService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SLA 提醒扫描（ADR-0033）：把"快到时限 / 已超时"从派生值变成可审计的状态跃迁。
 *
 * <p><b>为什么是定时扫描而不是请求时计算</b>：没有请求的时间段里状态也必须能变，
 * 否则"超时提醒"只在有人刷新时出现，等于没有提醒。扫描是单实例进程内的
 * （与 SingleFlight 同一边界），不加分布式锁——多实例部署会出现重复扫描，
 * 但条件更新保证同一状态只落一次审计，重复扫描最多是空转。
 *
 * <p><b>幂等</b>：只做 {@code ok → due_soon → breached} 单向跃迁，
 * 每次跃迁都是带旧状态的 {@link IncidentStore#transitionSla} 条件更新，
 * 只有更新成功的那个线程写审计行。已结单事故由 SQL 的 {@code status = 'open'} 排除。
 *
 * <p><b>租户边界</b>：扫描是系统任务，没有调用方 JWT。先枚举有到期单据的租户
 * （只取 tenant 键），随后每张单的读取、条件更新与审计都带该 tenant；
 * 这是本切片唯一一处跨租户读，故单独成方法并在此注明。
 */
@Component
public class IncidentSlaScanner {

    private static final Logger log = LoggerFactory.getLogger(IncidentSlaScanner.class);

    private final IncidentStore store;
    private final IncidentSlaProperties sla;
    private final AuditService audit;

    public IncidentSlaScanner(IncidentStore store, IncidentSlaProperties sla, AuditService audit) {
        this.store = store;
        this.sla = sla;
        this.audit = audit;
    }

    /** 默认 60s 一次；间隔与首启延迟都可在 {@code opspilot.incident.sla.scan-*} 覆写。 */
    @Scheduled(fixedDelayString = "${opspilot.incident.sla.scan-interval-ms:60000}",
            initialDelayString = "${opspilot.incident.sla.scan-initial-delay-ms:60000}")
    public void scheduledScan() {
        try {
            int moved = scan(Instant.now());
            if (moved > 0) log.info("incident SLA scan: {} state transition(s)", moved);
        } catch (RuntimeException e) {
            // 单次扫描失败不能拖死调度线程；下一轮会重试同一批（条件更新保证不重复审计）。
            log.warn("incident SLA scan failed: {}", e.toString());
        }
    }

    /**
     * 用调用方给定的时钟跑一轮，返回跃迁条数。测试用它做确定性断言，
     * 生产由 {@link #scheduledScan()} 传 {@link Instant#now()}。
     */
    @Transactional
    public int scan(Instant now) {
        int moved = 0;
        for (String tenant : store.tenantsWithOpenDue()) {
            for (Incident inc : store.listOpenDue(tenant)) {
                String from = inc.slaState() == null ? Incident.SLA_OK : inc.slaState();
                String to = nextState(inc, from, now);
                if (to == null) continue;
                if (!store.transitionSla(tenant, inc.id(), from, to, now)) continue;
                audit.logIncident(system(tenant), inc.id(), "sla", to,
                        inc.priority() + " due " + inc.dueAt());
                moved++;
            }
        }
        return moved;
    }

    /** 只前进不后退：breached 是终态（重开/改优先级时由 Service 显式重置为 ok）。 */
    private String nextState(Incident inc, String from, Instant now) {
        Instant dueAt = inc.dueAt();
        if (dueAt == null || Incident.SLA_BREACHED.equals(from)) return null;
        boolean overdue = now.isAfter(dueAt);
        if (Incident.SLA_DUE_SOON.equals(from)) {
            return overdue ? Incident.SLA_BREACHED : null;
        }
        if (overdue) return Incident.SLA_BREACHED;
        Duration window = sla.window(inc.priority());
        if (window == null) return null;
        Instant soonAt = dueAt.minus(sla.dueSoonLead(window));
        return now.isAfter(soonAt) ? Incident.SLA_DUE_SOON : null;
    }

    private static UserContext system(String tenant) {
        return new UserContext("system:sla", "system", 0, tenant);
    }
}
