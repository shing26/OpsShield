package com.opspilot.incident;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 事故通知配置（ADR-0037）。前缀 {@code opspilot.incident.notify.*}。
 *
 * <p>默认关闭外发（{@code webhook-url} 为空），站内通知始终可用。静默期、留存天数、
 * 队列容量都走配置：阈值写死在代码里会让"没发"与"发错时间"变成不可解释的行为。
 */
@ConfigurationProperties(prefix = "opspilot.incident.notify")
public record IncidentNotificationProperties(
        @DefaultValue("") String webhookUrl,
        @DefaultValue("generic") String webhookFormat,
        @DefaultValue("256") int queueCapacity,
        @DefaultValue("3000") long webhookTimeoutMs,
        @DefaultValue("22:00-08:00") String quietHours,
        @DefaultValue("Asia/Shanghai") String timezone,
        @DefaultValue("90") int retentionDays,
        @DefaultValue("0 17 4 * * *") String retentionCron) {

    public static final String FORMAT_GENERIC = "generic";
    public static final String FORMAT_FEISHU = "feishu";

    public IncidentNotificationProperties {
        if (!FORMAT_GENERIC.equals(webhookFormat) && !FORMAT_FEISHU.equals(webhookFormat)) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify.webhook-format 只能是 generic 或 feishu");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("opspilot.incident.notify.queue-capacity 必须 >= 1");
        }
        if (webhookTimeoutMs < 1) {
            throw new IllegalArgumentException("opspilot.incident.notify.webhook-timeout-ms 必须 >= 1");
        }
        if (retentionDays < 1) {
            throw new IllegalArgumentException("opspilot.incident.notify.retention-days 必须 >= 1");
        }
        ZoneId.of(timezone);
        parseQuietHours(quietHours);
    }

    public boolean webhookEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    /** 静默期按配置时区解释；跨午夜窗口（默认 22:00–08:00）也必须成立。 */
    public boolean quietAt(java.time.Instant instant) {
        QuietWindow w = parseQuietHours(quietHours);
        LocalTime t = instant.atZone(zone()).toLocalTime();
        if (w.start().equals(w.end())) return true;
        if (w.start().isBefore(w.end())) {
            return !t.isBefore(w.start()) && t.isBefore(w.end());
        }
        return !t.isBefore(w.start()) || t.isBefore(w.end());
    }

    public record QuietWindow(LocalTime start, LocalTime end) {}

    private static QuietWindow parseQuietHours(String raw) {
        String value = raw == null ? "" : raw.trim();
        int dash = value.indexOf('-');
        if (dash <= 0 || dash != value.lastIndexOf('-')) {
            throw new IllegalArgumentException("opspilot.incident.notify.quiet-hours 必须是 HH:mm-HH:mm");
        }
        try {
            return new QuietWindow(
                    LocalTime.parse(value.substring(0, dash).trim()),
                    LocalTime.parse(value.substring(dash + 1).trim()));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("opspilot.incident.notify.quiet-hours 必须是 HH:mm-HH:mm", e);
        }
    }
}
