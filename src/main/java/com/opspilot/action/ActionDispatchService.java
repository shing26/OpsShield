package com.opspilot.action;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * L1 签名派发（ADR-0028 规格 §6/§9 + 2026-10-25 评裁决三条契约）。
 *
 * <p><b>契约 1（回调终态语义，选 A）</b>：执行系统只发一次终态回调，由执行侧保证。
 * OpsShield 首次回调定终态；后续回调（含 failed-to-executed 修正）一律 409，
 * <b>并落审计 action_callback_conflict</b>——被拒的回调不是黑洞，事件里带着现有终态。
 *
 * <p><b>契约 2（防重放 + domain separation）</b>：回调 HMAC 消息为
 * callback:op_id:status:ts:nonce；ts 窗口 ±300s，nonce 一生一次（dispatch_nonces 表）。
 * 派发签名消息加 dispatch: 前缀——两个方向的签名即使共用 key 也不可互换。
 *
 * <p><b>契约 3（dispatch 期限在 confirm 时定）</b>：confirm 即写 dispatch_expires_at
 *（confirmed_at + 窗口），过期后不可再派发（惰性转 expired，须新 op_id 重新人确认）。
 *
 * <p>Clock 可注入（红队重放/过期用例的时间机器）；webhook connect 3s / read 5s、
 * 不跟随重定向（3xx 即 failed）；投递失败不自动重试（规格 §2）。
 */
@Service
public class ActionDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ActionDispatchService.class);
    static final long CALLBACK_WINDOW_S = 300;        // 回调时间窗 ±5 分钟

    private final JdbcTemplate jdbc;
    private final byte[] dispatchKey;                 // 当前签名 key；未配置=null（派发挂起）
    private final byte[] previousKey;                 // 轮换窗口内仍接受的旧 key（只验不签）
    private final String webhookUrl;
    private final boolean l1Enabled;                  // 灰度开关，默认 false（红队全绿前不开）
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 测试/红队注入时间机器；生产恒 systemUTC。 */
    Clock clock = Clock.systemUTC();

    @org.springframework.beans.factory.annotation.Autowired
    public ActionDispatchService(JdbcTemplate jdbc,
                                 @Value("${OPSPILOT_DISPATCH_KEY:}") String keyHex,
                                 @Value("${OPSPILOT_DISPATCH_KEY_FILE:}") String keyFile,
                                 @Value("${OPSPILOT_DISPATCH_KEY_PREVIOUS_FILE:}") String prevKeyFile,
                                 @Value("${opspilot.l1.dispatch-webhook:}") String webhookUrl,
                                 @Value("${opspilot.l1.enabled:false}") boolean l1Enabled) {
        this.jdbc = jdbc;
        this.dispatchKey = loadKey(keyHex, keyFile, "DISPATCH_KEY");
        this.previousKey = loadKey("", prevKeyFile, "DISPATCH_KEY_PREVIOUS_FILE");
        this.webhookUrl = webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl;
        this.l1Enabled = l1Enabled;
        if (this.dispatchKey != null && this.previousKey != null) {
            log.info("L1 派发密钥轮换窗口开启：新 key 签名，旧 key 仍可验（回调不断流）");
        }
    }

    /** 测试/嵌入用：直接给 key（不读文件）。 */
    ActionDispatchService(JdbcTemplate jdbc, String keyHex, String webhookUrl, boolean l1Enabled) {
        this(jdbc, keyHex, "", "", webhookUrl, l1Enabled);
    }

    /**
     * 密钥来源（基本卫生）：环境变量明文仅限开发；生产用 _FILE 读挂载 Secret。
     * 两处都配 → 环境变量优先（便于紧急轮换），但日志会提示并存。
     */
    private static byte[] loadKey(String hex, String file, String label) {
        try {
            if (hex != null && !hex.isBlank()) {
                return HexFormat.of().parseHex(hex.trim());
            }
            if (file != null && !file.isBlank()) {
                return HexFormat.of().parseHex(
                        java.nio.file.Files.readString(java.nio.file.Path.of(file.trim())).trim());
            }
        } catch (Exception e) {
            throw new IllegalStateException(label + " 读取/解析失败: " + e.getMessage(), e);
        }
        return null;
    }

    /** 签名比对：当前 key 或（轮换窗口内）旧 key——常量时间比较。 */
    boolean signatureMatches(String signature, String material) {
        if (dispatchKey != null && MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                hmac(dispatchKey, material).getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        return previousKey != null && MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                hmac(previousKey, material).getBytes(StandardCharsets.UTF_8));
    }

    public boolean dispatchEnabled() {
        return l1Enabled && dispatchKey != null && webhookUrl != null;
    }

    /** 回调豁免 l1 开关（2026-10-25 评裁决 1·选 A）：开关只管 propose/confirm/dispatch
     * 三个**发起入口**；回调是收尾通道——误伤会让在途 dispatched 永久悬挂、审计链断裂。
     * 回调的安全边界 = HMAC + 时间窗 + nonce + 状态机，不靠开关。 */
    public boolean callbackEnabled() {
        return dispatchKey != null;
    }

    public boolean l1Enabled() {
        return l1Enabled;
    }

    byte[] dispatchKeyForVerify() {
        return dispatchKey;
    }

    /** 与 reference_verifier.expected_signature 同材料（dispatch: 前缀，跨语言向量钉死）。 */
    public static String expectedSignature(byte[] key, String opId, String payloadSha, String expiresAt) {
        return hmac(key, "dispatch:" + opId + "." + payloadSha + "." + expiresAt);
    }

    /** 回调方向的消息（callback: 前缀 + ts + nonce——与派发签名不可互换）。 */
    public static String callbackSignatureMaterial(String opId, String status, long ts, String nonce) {
        return "callback:" + opId + ":" + status + ":" + ts + ":" + nonce;
    }

    static String hmac(byte[] key, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** confirm 成功即写派发期限（契约 3）：confirmed_at + 窗口——过期不可派发。 */
    void stampDispatchDeadline(String opId) {
        jdbc.update("UPDATE action_ops SET dispatch_expires_at=? "
                        + "WHERE op_id=? AND state='confirmed'",
                java.sql.Timestamp.from(Instant.now(clock).plusSeconds(15 * 60)), opId);
    }

    /** confirmed op 的自动派发：期限检查 → 签名 → CAS dispatched → POST webhook。 */
    public void dispatchConfirmed(String opId) {
        if (!dispatchEnabled()) {
            log.info("L1 派发未启用（开关/key/webhook 任缺）：op {} 停在 confirmed", opId);
            return;
        }
        var row = jdbc.queryForMap(
                "SELECT state, command, dispatch_expires_at FROM action_ops WHERE op_id = ?", opId);
        if (!"confirmed".equals(row.get("state"))) {
            return;                                          // 幂等：只派发 confirmed
        }
        java.sql.Timestamp deadline = (java.sql.Timestamp) row.get("dispatch_expires_at");
        if (deadline == null || Instant.now(clock).isAfter(deadline.toInstant())) {
            // 契约 3：过期不可派发——惰性转 expired，重跑须新 op_id + 重新人确认
            jdbc.update("UPDATE action_ops SET state='expired' WHERE op_id=? AND state='confirmed'", opId);
            log.warn("L1 派发期限已过：{} 转 expired（须重新提案）", opId);
            return;
        }
        String command = (String) row.get("command");
        String payloadSha = sha256Hex(command);
        String expiresAt = deadline.toInstant().toString();
        String sig = expectedSignature(dispatchKey, opId, payloadSha, expiresAt);
        int updated = jdbc.update(
                "UPDATE action_ops SET state='dispatched', dispatch_target=?, dispatch_payload_sha256=?, "
                + "dispatch_hmac=?, dispatched_at=? "
                + "WHERE op_id=? AND state='confirmed'",
                webhookUrl, payloadSha, sig, java.sql.Timestamp.from(Instant.now(clock)), opId);
        if (updated != 1) {
            return;                                          // 并发保护：CAS 只派发一次
        }
        String body = "{\"op_id\":\"" + opId + "\",\"command\":\"" + jsonEscape(command)
                + "\",\"payload_sha256\":\"" + payloadSha + "\",\"expires_at\":\"" + expiresAt
                + "\",\"hmac\":\"" + sig + "\"}";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(webhookUrl))
                            .timeout(Duration.ofSeconds(5))       // read 5s；connect 3s 已在 client
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {                 // 3xx 不跟随：直接 failed
                fail(opId, "webhook HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            fail(opId, "webhook 传输失败: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 执行侧回调（契约 1·选 A）：首次定终态 executed|failed；任何后续回调（含
     * failed-to-executed 修正）→ false（409），不静默——controller 落审计
     * action_callback_conflict（带现有终态），运维能看到被拒的修正请求。
     */
    public boolean callback(String opId, String status, String detail) {
        String target = "executed".equals(status) ? "executed" : "failed";
        return jdbc.update("UPDATE action_ops SET state=?, dispatch_result=? "
                        + "WHERE op_id=? AND state='dispatched'",
                target, detail == null ? "" : detail.substring(0, Math.min(detail.length(), 500)),
                opId) == 1;
    }

    /** 冲突审计用：op 当前状态（不存在返回 absent）。 */
    public String existingState(String opId) {
        var l = jdbc.queryForList("SELECT state FROM action_ops WHERE op_id = ?", String.class, opId);
        return l.isEmpty() ? "absent" : l.get(0);
    }

    /** 回调时间窗与 nonce（契约 2）。→ null=通过；否则返回分层 reject_reason：
     * ts_out_of_window / nonce_reused——两个 401 打不同层，红队按 reason 断言防假绿。 */
    public String callbackFreshnessReason(long ts, String nonce) {
        long now = Instant.now(clock).getEpochSecond();
        if (Math.abs(now - ts) > CALLBACK_WINDOW_S) {
            return "ts_out_of_window";
        }
        try {
            jdbc.update("INSERT INTO dispatch_nonces (nonce, seen_at) VALUES (?, ?)",
                    nonce, java.sql.Timestamp.from(Instant.now(clock)));
            return null;                                      // 首见即占坑
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return "nonce_reused";                            // nonce 重放
        }
    }

    private void fail(String opId, String reason) {
        // 不自动重试（规格 §2）：失败留痕 + 日志告警面（Alertmanager 规则接盲点 4）
        jdbc.update("UPDATE action_ops SET state='failed', dispatch_result=? "
                + "WHERE op_id=? AND state='dispatched'", reason, opId);
        log.warn("L1 派发失败（不自动重试）：{} {}", opId, reason);
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
