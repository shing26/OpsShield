package com.opspilot.alert;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 告警历史游标：不透明 base64url，格式
 * {@code v1|epochMicros|base64url(tenant)|base64url(alertId)}，无 padding。
 *
 * <p>时间用微秒而不是毫秒：同一毫秒内的多行若只按毫秒比较，键集边界会漏行。
 * 游标只承载「上一页最后一行的排序键」，不是授权凭据；解析失败一律 400，
 * 调用方不得记录游标原文。
 */
final class AlertHistoryCursor {

    static final String INVALID = "游标不合法";

    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int SEGMENTS = 4;
    private static final int MAX_TENANT = 64;
    private static final int MAX_ALERT_ID = 128;

    private final long epochMicros;
    private final String tenant;
    private final String alertId;

    private AlertHistoryCursor(long epochMicros, String tenant, String alertId) {
        this.epochMicros = epochMicros;
        this.tenant = tenant;
        this.alertId = alertId;
    }

    long epochMicros() {
        return epochMicros;
    }

    Instant lastSeen() {
        return fromMicros(epochMicros);
    }

    String tenant() {
        return tenant;
    }

    String alertId() {
        return alertId;
    }

    static String encode(Instant lastSeen, String tenant, String alertId) {
        String payload = String.join("|", "v1", Long.toString(toMicros(lastSeen)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(tenant.getBytes(StandardCharsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(alertId.getBytes(StandardCharsets.UTF_8)));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    static AlertHistoryCursor decode(String raw) {
        if (raw == null || raw.isEmpty() || !URL_SAFE.matcher(raw).matches()) throw invalid();
        String payload;
        try {
            payload = utf8(Base64.getUrlDecoder().decode(raw));
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw invalid();
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != SEGMENTS || !"v1".equals(parts[0])) throw invalid();
        long micros;
        try {
            micros = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw invalid();
        }
        if (micros < 0) throw invalid();
        String tenant = decodeSegment(parts[2]);
        String alertId = decodeSegment(parts[3]);
        if (tenant.isEmpty() || tenant.length() > MAX_TENANT) throw invalid();
        if (alertId.isEmpty() || alertId.length() > MAX_ALERT_ID) throw invalid();
        return new AlertHistoryCursor(micros, tenant, alertId);
    }

    private static String decodeSegment(String segment) {
        if (segment.isEmpty() || !URL_SAFE.matcher(segment).matches()) throw invalid();
        try {
            return utf8(Base64.getUrlDecoder().decode(segment));
        } catch (IllegalArgumentException | CharacterCodingException e) {
            throw invalid();
        }
    }

    /** 严格 UTF-8：坏字节不得被替换成 U+FFFD 后当成合法 tenant/alertId。 */
    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    static long toMicros(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
                instant.getNano() / 1_000L);
    }

    static Instant fromMicros(long micros) {
        long seconds = Math.floorDiv(micros, 1_000_000L);
        long remMicros = Math.floorMod(micros, 1_000_000L);
        return Instant.ofEpochSecond(seconds, remMicros * 1_000L);
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID);
    }
}
