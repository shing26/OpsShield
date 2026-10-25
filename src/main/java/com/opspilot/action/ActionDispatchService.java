package com.opspilot.action;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * L1 签名派发（ADR-0028 规格 §6/§9）：confirmed → dispatched 系统自动完成。
 *
 * <p><b>签名材料与 reference verifier 完全一致</b>（跨语言契约由 ActionDispatchServiceTest
 * 里的固定向量钉死——Java 签 ↔ Python 验）：
 * {@code HMAC-SHA256(key, op_id + "." + payload_sha256 + "." + expires_at)}。
 * payload = 命令原文的 UTF-8 字节；常量时间比较只存在于验签侧（verifier），签发侧是纯计算。
 *
 * <p><b>失败语义（规格 §2 评审增补 3）</b>：webhook 投递失败 → state=failed，
 * **不自动重试**（自动重试 = 绕过人确认的后门）；重新执行须新 op_id 重新提案 + 重新人确认。
 * webhook 未配置时 op 停在 confirmed（派发挂起，面板可见），不假装已派发。
 */
@Service
public class ActionDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ActionDispatchService.class);

    private final JdbcTemplate jdbc;
    private final byte[] dispatchKey;        // hex；未配置=null（派发挂起）
    private final String webhookUrl;         // 未配置=null
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public ActionDispatchService(JdbcTemplate jdbc,
                                 @Value("${OPSPILOT_DISPATCH_KEY:}") String keyHex,
                                 @Value("${opspilot.l1.dispatch-webhook:}") String webhookUrl) {
        this.jdbc = jdbc;
        this.dispatchKey = keyHex == null || keyHex.isBlank() ? null : HexFormat.of().parseHex(keyHex);
        this.webhookUrl = webhookUrl == null || webhookUrl.isBlank() ? null : webhookUrl;
        if ((dispatchKey == null) != (this.webhookUrl == null)) {
            log.warn("L1 派发半配置：key 与 webhook 必须同时设置才启用（当前 key={} webhook={}）",
                    dispatchKey != null, this.webhookUrl != null);
        }
    }

    /** 回调验签用（同 key 只读暴露给同包端点；不做拷贝，避免再一份密钥生命周期）。 */
    byte[] dispatchKeyForVerify() {
        return dispatchKey;
    }

    public boolean enabled() {
        return dispatchKey != null && webhookUrl != null;
    }

    /** 与 reference_verifier.expected_signature 同材料（跨语言向量钉死）。 */
    public static String expectedSignature(byte[] key, String opId, String payloadSha, String expiresAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String msg = opId + "." + payloadSha + "." + expiresAt;
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

    /** confirmed op 的自动派发：签名 → 落库 dispatched → POST webhook → 回填结果。 */
    public void dispatchConfirmed(String opId) {
        if (!enabled()) {
            log.info("L1 派发未启用（缺 key 或 webhook）：op {} 停在 confirmed", opId);
            return;
        }
        var row = jdbc.queryForMap(
                "SELECT state, command, tenant FROM action_ops WHERE op_id = ?", opId);
        if (!"confirmed".equals(row.get("state"))) {
            return;                      // 幂等：只派发 confirmed
        }
        String command = (String) row.get("command");
        String payloadSha = sha256Hex(command);
        Instant expires = Instant.now().plusSeconds(15 * 60);
        String expiresAt = expires.toString();
        String hmac = expectedSignature(dispatchKey, opId, payloadSha, expiresAt);
        int updated = jdbc.update(
                "UPDATE action_ops SET state='dispatched', dispatch_target=?, dispatch_payload_sha256=?, "
                + "dispatch_hmac=?, dispatch_expires_at=?, dispatched_at=? "
                + "WHERE op_id=? AND state='confirmed'",
                webhookUrl, payloadSha, hmac, java.sql.Timestamp.from(expires),
                java.sql.Timestamp.from(Instant.now()), opId);
        if (updated != 1) {
            return;                      // 并发保护：CAS 只派发一次
        }
        String body = "{\"op_id\":\"" + opId + "\",\"command\":\"" + jsonEscape(command)
                + "\",\"payload_sha256\":\"" + payloadSha + "\",\"expires_at\":\"" + expiresAt
                + "\",\"hmac\":\"" + hmac + "\"}";
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                            .uri(URI.create(webhookUrl))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                fail(opId, "webhook HTTP " + resp.statusCode());
            }
        } catch (Exception e) {
            fail(opId, "webhook 传输失败: " + e.getClass().getSimpleName());
        }
    }

    /** 执行侧回调：dispatched → executed|failed（终态；幂等保护由 WHERE state 带上）。 */
    public boolean callback(String opId, String status, String detail) {
        String target = "executed".equals(status) ? "executed" : "failed";
        return jdbc.update("UPDATE action_ops SET state=?, dispatch_result=? "
                        + "WHERE op_id=? AND state='dispatched'",
                target, detail == null ? "" : detail.substring(0, Math.min(detail.length(), 500)), opId) == 1;
    }

    private void fail(String opId, String reason) {
        // 不自动重试（规格 §2）：失败留痕，重新执行须新 op_id + 重新人确认
        jdbc.update("UPDATE action_ops SET state='failed', dispatch_result=? "
                + "WHERE op_id=? AND state='dispatched'", reason, opId);
        log.warn("L1 派发失败（不自动重试）：{} {}", opId, reason);
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
