package com.opspilot.metrics;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import jakarta.annotation.PostConstruct;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计写失败计数（G0，2026-10-22 运维审查）。
 *
 * <p>审计的失败语义是"不阻断业务"——{@code AuditService.writeEvent} 只 catch 序列化异常，
 * 而 logback {@code RollingFileAppender} 的磁盘写失败只进 StatusManager、不抛给调用方。
 * 于是磁盘满/权限错时：业务照常 200、审计静默丢行、值班毫无感知。本类把这条静默失败
 * 变成可告警面：
 * <ul>
 *   <li>logback 的 ERROR 级 Status 事件（appender 打开/滚动/写入失败都会走到这里）→ +1；</li>
 *   <li>审计行序列化失败（{@code AuditService} 显式 catch）→ 调 {@link #markFailure()}。</li>
 * </ul>
 * 用 <b>单调递增的 gauge</b> 而不是 counter 的原因：logback StatusListener 在 Spring 之外，
 * 拿不到 MeterRegistry；静态 AtomicLong + gauge 是零依赖的最小桥。告警侧用
 * {@code delta(aiops_audit_write_failures[5m]) > 0} 判"近期有新增失败"。
 *
 * <p>本类只统计 ERROR 状态，不区分是哪个 appender——当前审计是唯一挂了失败语义的
 * 关键 appender；若未来出现误报，再按 origin/context 细分，不预先复杂化。
 */
@Component
public class AuditWriteFailures implements MeterBinder, StatusListener {

    /** 进程生命周期内累计失败数（gauge 只增；delta 由 Prometheus 计算）。 */
    private static final AtomicLong FAILURES = new AtomicLong();

    /** 供 {@link AuditService} 的 catch 分支调用；测试也直接用它。 */
    public static void markFailure() {
        FAILURES.incrementAndGet();
    }

    /** 只读（测试/排障）：当前累计失败数。 */
    public static long failureCount() {
        return FAILURES.get();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        // FunctionCounter 在同一单调 AtomicLong 上导出 counter 语义（Prometheus 名带 _total），
        // 这样 alert 规则可以直接用 increase(...[5m])，不必对 gauge 做 delta。
        FunctionCounter.builder("aiops.audit.write.failures", FAILURES, AtomicLong::get)
                .description("审计写失败累计数（logback appender ERROR + 序列化失败）")
                .register(registry);
    }

    /** Spring 初始化时挂到 logback：appender 的 Status 事件不走 SLF4J，只能这样接。 */
    @PostConstruct
    public void register() {
        try {
            if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
                ctx.getStatusManager().add(this);
            }
        } catch (Exception ignored) {
            // 指标桥接失败不能影响启动；失败本身会有 Gauge 恒 0 表现，M1 再补自检
        }
    }

    @Override
    public void addStatusEvent(Status status) {
        if (status != null && status.getLevel() == Status.ERROR) {
            markFailure();
        }
    }
}
