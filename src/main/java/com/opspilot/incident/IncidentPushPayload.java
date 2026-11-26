package com.opspilot.incident;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * 外发载荷与脱敏（ADR-0045）。
 *
 * <p>载荷只含事故定位信息：事件、事故 ID/标题、优先级、负责人、时限、深链。
 * query、诊断正文、评论正文、引用片段与 secret 一律不进入载荷；日志与 {@code last_error}
 * 走同一套脱敏函数，避免"载荷干净但错误串把 URL/密钥带出去"。
 */
public final class IncidentPushPayload {

    public record Body(String text, String incidentId, String kind, String priority,
                       String recipient, String url, long deliveryId) {}

    private static final int MAX_ERROR = 500;
    private static final Pattern QUERY = Pattern.compile("\\?[^\\s\"']*");
    private static final Pattern USERINFO = Pattern.compile("(?i)(https?://)[^/\\s@]+@");

    private IncidentPushPayload() {}

    /** generic 的扁平载荷（键序固定，便于接收端对照契约）。 */
    public static String generic(ObjectMapper json, Body body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", body.text());
        out.put("incidentId", body.incidentId());
        out.put("kind", body.kind());
        out.put("priority", body.priority());
        out.put("recipient", body.recipient());
        out.put("url", body.url());
        out.put("deliveryId", body.deliveryId());
        return json.writeValueAsString(out);
    }

    /** feishu 文本消息信封：只允许 msg_type/content.text 两个顶层语义键。 */
    public static String feishu(ObjectMapper json, Body body) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("text", body.text());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("msg_type", "text");
        out.put("content", content);
        return json.writeValueAsString(out);
    }

    public static String body(String format, ObjectMapper json, Body body) {
        return IncidentNotificationProperties.FORMAT_FEISHU.equals(format)
                ? feishu(json, body) : generic(json, body);
    }

    public static String deepLink(String uiBaseUrl, String incidentId) {
        if (uiBaseUrl == null || uiBaseUrl.isBlank()) return "";
        String base = uiBaseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/?inc=" + incidentId;
    }

    /** 站内通知的文本（旧测试直接复用；与 outbox 文本保持同一措辞）。 */
    static String text(IncidentNotification n) {
        return text(n.kind(), n.incidentId(), n.title(), n.priority(), "", n.dueAt(), "");
    }

    static String text(IncidentPushOutbox row, String url) {
        return text(row.kind(), row.incidentId(), row.title(), row.priority(),
                row.recipient(), row.dueAt(), url);
    }

    private static String text(String kind, String incidentId, String title, String priority,
                               String recipient, Instant dueAt, String url) {
        String event = switch (kind == null ? "" : kind) {
            case IncidentPushOutbox.KIND_CREATED -> "新建";
            case IncidentNotification.KIND_ASSIGNED -> "指派";
            case IncidentNotification.KIND_SLA_DUE_SOON -> "SLA 临期";
            case IncidentNotification.KIND_SLA_BREACHED -> "SLA 超时";
            case IncidentNotification.KIND_REOPENED -> "重新打开";
            default -> kind == null ? "" : kind;
        };
        String p = priority == null || priority.isBlank() ? "未定" : priority;
        String owner = recipient == null || recipient.isBlank() ? "未指派" : recipient;
        String due = dueAt == null ? "未设时限" : dueAt.toString();
        StringBuilder sb = new StringBuilder();
        sb.append("[OpsPilot] 事故通知 · ").append(event).append('\n')
                .append("事故：").append(incidentId).append(' ').append(nz(title)).append('\n')
                .append("优先级：").append(p).append('\n')
                .append("负责人：").append(owner).append('\n')
                .append("时限：").append(due);
        if (url != null && !url.isBlank()) {
            sb.append('\n').append("链接：").append(url);
        }
        return sb.toString();
    }

    /** timestamp + "." + rawBody 的 HMAC-SHA256 十六进制摘要。 */
    public static String signature(String secret, long timestamp, String rawBody) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + rawBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /**
     * 错误串脱敏：去掉 URL query/userinfo、替换 secret、截断 500。
     *
     * <p>用"整串塞进异常消息"的驱动/上游库很常见（HttpClient 的 connect 异常就带 URI），
     * 所以不能只保证我们自己不拼 URL——错误对象本身也要过一遍。
     */
    public static String sanitize(Throwable error, String url, String secret) {
        if (error == null) return "";
        String raw = error.getClass().getSimpleName();
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            raw += ": " + error.getMessage();
        }
        return sanitize(raw, url, secret);
    }

    public static String sanitize(String raw, String url, String secret) {
        String value = raw == null ? "" : raw;
        if (secret != null && !secret.isBlank()) {
            value = value.replace(secret, "***");
        }
        if (url != null && !url.isBlank()) {
            value = value.replace(url, safeUrl(url));
        }
        value = USERINFO.matcher(value).replaceAll("$1***@");
        value = QUERY.matcher(value).replaceAll("?***");
        value = value.replaceAll("(?i)(x-opspilot-signature\\s*[:=]\\s*)\\S+", "$1***");
        return value.length() <= MAX_ERROR ? value : value.substring(0, MAX_ERROR);
    }

    /** 日志用安全 URL：只保留 scheme/host/port/path，去掉 userinfo/query/fragment。 */
    public static String safeUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            StringBuilder sb = new StringBuilder();
            if (uri.getScheme() != null) sb.append(uri.getScheme()).append("://");
            if (uri.getHost() != null) sb.append(uri.getHost());
            if (uri.getPort() >= 0) sb.append(':').append(uri.getPort());
            if (uri.getPath() != null && !uri.getPath().isBlank()) sb.append(uri.getPath());
            return sb.toString();
        } catch (RuntimeException e) {
            return "<invalid-url>";
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
