package com.opspilot.incident;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 事故响应时限（ADR-0033）：阈值只在配置里定义，代码不写死分钟数，前端更不做兜底。
 *
 * <p>绑定前缀 {@code opspilot.incident}，实际阈值位于 {@code opspilot.incident.sla.*}；
 * 由 {@link IncidentSlaConfiguration} 显式 {@code @EnableConfigurationProperties} 注册
 * （record 的构造器绑定不能靠组件扫描）。缺配置或越界必须在启动期拒绝：SLA 静默走错
 * 比启动失败更难发现——"超时提醒"会变成永远不提醒或永远告警。
 */
@Validated
@ConfigurationProperties(prefix = "opspilot.incident")
public record IncidentSlaProperties(@Valid @NotNull Sla sla) {

    public record Sla(
            @Min(1) long p1Minutes,
            @Min(1) long p2Minutes,
            @Min(1) long p3Minutes,
            @DecimalMin(value = "0.01", message = "sla.due-soon-ratio 必须 > 0")
            @DecimalMax(value = "0.99", message = "sla.due-soon-ratio 必须 < 1")
            double dueSoonRatio) {
    }

    /** 空优先级 = 不设时限；未知优先级同样不臆造阈值。 */
    public Duration window(String priority) {
        if (priority == null || priority.isBlank() || sla == null) return null;
        return switch (priority) {
            case "P1" -> Duration.ofMinutes(sla.p1Minutes());
            case "P2" -> Duration.ofMinutes(sla.p2Minutes());
            case "P3" -> Duration.ofMinutes(sla.p3Minutes());
            default -> null;
        };
    }

    /** due_soon 提前量 = 响应窗口的 due-soon-ratio；"20% 窗口"来自配置而不是代码常量。 */
    public Duration dueSoonLead(Duration window) {
        return Duration.ofNanos((long) (window.toNanos() * sla.dueSoonRatio()));
    }
}
