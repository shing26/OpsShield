package com.opspilot.incident;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 事故历史游标：不透明 base64url，格式 {@code v1|rank|epochMicros|incidentId|mine|status}。
 *
 * <p>游标只携带「上一页最后一行的排序键 + 本次筛选」：不含 tenant，不能当授权凭据。
 * tenant 永远来自 JWT；换租户使用同一游标只会得到新租户自己的页。
 *
 * <p>时间用微秒而不是毫秒：同一毫秒内的多行若只按毫秒比较，会漏页。
 * 解析失败一律 400，绝不把损坏输入变成 500；调用方也不得记录游标原文。
 */
final class IncidentCursor {

    static final String INVALID = "游标不合法";
    static final String FILTER_MISMATCH = "游标与筛选条件不匹配";

    /** 服务端生成的 ID 形状：INC-MMdd-NNNN。游标里的 ID 必须满足它。 */
    private static final Pattern ID_SHAPE = Pattern.compile("INC-\\d{4}-\\d{4}");
    private static final Pattern URL_SAFE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final int SEGMENTS = 6;

    private final int rank;
    private final long epochMicros;
    private final String incidentId;

    private IncidentCursor(int rank, long epochMicros, String incidentId) {
        this.rank = rank;
        this.epochMicros = epochMicros;
        this.incidentId = incidentId;
    }

    int rank() {
        return rank;
    }

    long epochMicros() {
        return epochMicros;
    }

    String incidentId() {
        return incidentId;
    }

    Instant updatedAt() {
        return fromMicros(epochMicros);
    }

    static String encode(int rank, Instant updatedAt, String incidentId, String mine, String status) {
        long micros = toMicros(updatedAt);
        String payload = String.join("|", "v1", Integer.toString(rank), Long.toString(micros),
                incidentId, mine, status);
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码并校验。{@code mine}/{@code status} 是本次请求的规范化值（空串=未传），
     * 与游标内记录不一致即 400——分页不能跨筛选复用。
     */
    static IncidentCursor decode(String raw, String mine, String status) {
        if (raw == null || raw.isEmpty() || !URL_SAFE.matcher(raw).matches()) throw invalid();
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != SEGMENTS || !"v1".equals(parts[0])) throw invalid();
        int rank;
        long micros;
        try {
            rank = Integer.parseInt(parts[1]);
            micros = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw invalid();
        }
        String id = parts[3];
        if (rank < 0 || rank > 2 || micros < 0 || !ID_SHAPE.matcher(id).matches()) throw invalid();
        String cursorMine = parts[4];
        String cursorStatus = parts[5];
        if (!"".equals(cursorMine) && !"true".equals(cursorMine) && !"false".equals(cursorMine)) {
            throw invalid();
        }
        if (!"".equals(cursorStatus) && !Incident.STATUS_OPEN.equals(cursorStatus)
                && !Incident.STATUS_CLOSED.equals(cursorStatus)) {
            throw invalid();
        }
        if (!cursorMine.equals(mine) || !cursorStatus.equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, FILTER_MISMATCH);
        }
        return new IncidentCursor(rank, micros, id);
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
