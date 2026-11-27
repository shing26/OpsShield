package com.opspilot.incident;

import java.net.URI;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 事故通知与外发配置（ADR-0037 / ADR-0045）。前缀 {@code opspilot.incident.notify.*}。
 *
 * <p>默认关闭外发：既没有旧 {@code webhook-url}，也没有 {@code channels}。站内通知始终可用。
 * 旧配置 {@code webhook-url} 映射成 {@code default} 单通道；新旧同时出现直接启动失败——
 * "到底发哪"必须能从配置一眼回答，不能靠优先级猜测。
 *
 * <p>所有 URL / 优先级 / 格式 / 静默期都在构造期校验：配置错误应该是启动失败，
 * 而不是运行到第一次告警时才发现"发不出去"。
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
        @DefaultValue("0 17 4 * * *") String retentionCron,
        @DefaultValue Map<String, Channel> channels,
        @DefaultValue("P2") String createdMinPriority,
        @DefaultValue("") String uiBaseUrl,
        @DefaultValue("") String allowedHosts,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("1000") long baseBackoffMs,
        @DefaultValue("300000") long maxBackoffMs,
        @DefaultValue("5000") long requestTimeoutMs,
        @DefaultValue("20") int batchSize,
        @DefaultValue("1000") long pollIntervalMs) {

    public static final String FORMAT_GENERIC = "generic";
    public static final String FORMAT_FEISHU = "feishu";
    public static final String DEFAULT_CHANNEL = "default";

    private static final Set<String> PRIORITIES = Set.of("", "P1", "P2", "P3");
    private static final Set<String> KINDS = Set.of(
            "created", IncidentNotification.KIND_ASSIGNED, IncidentNotification.KIND_SLA_DUE_SOON,
            IncidentNotification.KIND_SLA_BREACHED, IncidentNotification.KIND_REOPENED);

    /**
     * 一条 IM 通道。{@code url} 为空 = 该通道禁用；{@code min-priority} 默认 P3（P1/P2/P3 都发），
     * {@code kinds} 为空 = 全部事件。{@code quiet-hours} 为空时继承全局静默期。
     */
    public record Channel(
            @DefaultValue("generic") String format,
            @DefaultValue("") String url,
            @DefaultValue("") String secret,
            @DefaultValue("P3") String minPriority,
            @DefaultValue List<String> kinds,
            @DefaultValue("") String quietHours,
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5") int rateLimitPerSecond) {

        public Channel {
            if (!FORMAT_GENERIC.equals(format) && !FORMAT_FEISHU.equals(format)) {
                throw new IllegalArgumentException(
                        "opspilot.incident.notify.channels.*.format 只能是 generic 或 feishu");
            }
            if (!PRIORITIES.contains(minPriority)) {
                throw new IllegalArgumentException(
                        "opspilot.incident.notify.channels.*.min-priority 只能是 P1/P2/P3 或空");
            }
            if (rateLimitPerSecond < 1) {
                throw new IllegalArgumentException(
                        "opspilot.incident.notify.channels.*.rate-limit-per-second 必须 >= 1");
            }
            List<String> checkedKinds = new ArrayList<>();
            for (String kind : kinds == null ? List.<String>of() : kinds) {
                String value = kind == null ? "" : kind.trim();
                if (!KINDS.contains(value)) {
                    throw new IllegalArgumentException(
                            "opspilot.incident.notify.channels.*.kinds 含未知事件: " + kind);
                }
                checkedKinds.add(value);
            }
            kinds = List.copyOf(new LinkedHashSet<>(checkedKinds));
            if (quietHours != null && !quietHours.isBlank()) parseQuietHours(quietHours);
        }
    }

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
        if (!PRIORITIES.contains(createdMinPriority)) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify.created-min-priority 只能是 P1/P2/P3 或空");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("opspilot.incident.notify.max-attempts 必须 >= 1");
        }
        if (baseBackoffMs < 1 || maxBackoffMs < baseBackoffMs) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify.base-backoff-ms/max-backoff-ms 非法");
        }
        if (requestTimeoutMs < 1) {
            throw new IllegalArgumentException("opspilot.incident.notify.request-timeout-ms 必须 >= 1");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("opspilot.incident.notify.batch-size 必须 >= 1");
        }
        if (pollIntervalMs < 1) {
            throw new IllegalArgumentException("opspilot.incident.notify.poll-interval-ms 必须 >= 1");
        }
        ZoneId.of(timezone);
        parseQuietHours(quietHours);
        validateUiBaseUrl(uiBaseUrl);

        Map<String, Channel> normalized = new LinkedHashMap<>();
        if (channels != null) {
            for (Map.Entry<String, Channel> entry : channels.entrySet()) {
                String id = entry.getKey() == null ? "" : entry.getKey().trim();
                if (!id.matches("[A-Za-z0-9._-]{1,64}")) {
                    throw new IllegalArgumentException(
                            "opspilot.incident.notify.channels.<id> 非法（1..64，字母数字 . _ -）");
                }
                Channel channel = entry.getValue();
                if (channel == null) {
                    throw new IllegalArgumentException(
                            "opspilot.incident.notify.channels." + id + " 不能为空");
                }
                validateWebhookUrl(channel.url(), "channels." + id + ".url");
                normalized.put(id, channel);
            }
        }
        if (webhookUrl != null && !webhookUrl.isBlank() && !normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify.webhook-url 与 channels 不能同时配置（到底发哪必须唯一）");
        }
        validateWebhookUrl(webhookUrl, "webhook-url");
        channels = Collections.unmodifiableMap(normalized);

        List<String> allowed = new ArrayList<>();
        if (allowedHosts != null) {
            for (String part : allowedHosts.split(",")) {
                String value = part.trim().toLowerCase(Locale.ROOT);
                if (!value.isEmpty()) allowed.add(value);
            }
        }
        if (!allowed.isEmpty()) {
            // 紧凑构造器里 record 字段尚未赋值（channels/webhookUrl 仍是 null），
            // 这里必须用已校验的局部值，不能调 effectiveChannels()。
            Map<String, Channel> effective = webhookUrl != null && !webhookUrl.isBlank()
                    ? Map.of(DEFAULT_CHANNEL, new Channel(webhookFormat, webhookUrl.trim(), "",
                            "P3", List.of(), "", true, 5))
                    : normalized;
            for (Map.Entry<String, Channel> entry : effective.entrySet()) {
                String url = entry.getValue().url();
                if (url == null || url.isBlank()) continue;
                String host = URI.create(url.trim()).getHost().toLowerCase(Locale.ROOT);
                if (!allowed.contains(host)) {
                    throw new IllegalArgumentException(
                            "opspilot.incident.notify.allowed-hosts 不包含通道 "
                                    + entry.getKey() + " 的 host: " + host);
                }
            }
        }
    }

    /** 旧单 webhook 通道是否启用。 */
    public boolean webhookEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /** 实际生效的通道表：旧配置映射为 default；新配置原样返回。 */
    public Map<String, Channel> effectiveChannels() {
        if (!webhookEnabled()) return channels;
        return Map.of(DEFAULT_CHANNEL, new Channel(webhookFormat, webhookUrl.trim(), "", "P3",
                List.of(), "", true, 5));
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    public List<String> allowedHostList() {
        if (allowedHosts == null || allowedHosts.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : allowedHosts.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) out.add(value);
        }
        return List.copyOf(out);
    }

    /** 静默期按全局时区解释；通道可覆写自己的窗口。 */
    public boolean quietAt(java.time.Instant instant) {
        return quietAt(instant, quietHours);
    }

    public boolean quietAt(java.time.Instant instant, String window) {
        QuietWindow w = parseQuietHours(window);
        LocalTime t = instant.atZone(zone()).toLocalTime();
        if (w.start().equals(w.end())) return true;
        if (w.start().isBefore(w.end())) {
            return !t.isBefore(w.start()) && t.isBefore(w.end());
        }
        return !t.isBefore(w.start()) || t.isBefore(w.end());
    }

    public record QuietWindow(LocalTime start, LocalTime end) {}

    static QuietWindow parseQuietHours(String raw) {
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

    static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        if (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.equals("localhost") || value.equals("::1")
                || value.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        // 只认点分十进制 IPv4；不能用 startsWith("127.") ——
        // 否则 127.example.com / 127.0.0.1.evil.com 会被误判为回环并放行明文 HTTP。
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4 || !parts[0].equals("127")) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) return false;
            }
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /** URL 启动校验：绝对 http(s)、非 loopback 必须 https、禁 userinfo/fragment。 */
    static void validateWebhookUrl(String raw, String field) {
        if (raw == null || raw.isBlank()) return;
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("opspilot.incident.notify." + field + " 不是合法 URL", e);
        }
        if (!uri.isAbsolute() || uri.getHost() == null) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify." + field + " 必须是带 host 的绝对 URL");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify." + field + " 只允许 http/https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify." + field + " 禁止 userinfo");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify." + field + " 禁止 fragment");
        }
        if (scheme.equals("http") && !isLoopbackHost(uri.getHost())) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify." + field + " 非 loopback 必须使用 https");
        }
    }

    private static void validateUiBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return;
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("opspilot.incident.notify.ui-base-url 不是合法 URL", e);
        }
        if (!uri.isAbsolute() || uri.getHost() == null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException(
                    "opspilot.incident.notify.ui-base-url 必须是 http(s) 绝对 URL");
        }
    }
}
