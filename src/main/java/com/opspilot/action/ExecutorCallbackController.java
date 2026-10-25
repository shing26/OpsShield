package com.opspilot.action;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** 执行侧回调（ADR-0028 §5）：dispatched → executed|failed（终态）。HMAC 鉴权。 */
@RestController
public class ExecutorCallbackController {

    private final ActionDispatchService dispatch;

    public ExecutorCallbackController(ActionDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    public record CallbackReq(String opId, String status, String detail) {}

    /**
     * 鉴权：X-Dispatch-Signature = HMAC-SHA256(DISPATCH_KEY, 原始请求体)——
     * 与派发共用一把 key，常量时间比较；未启用派发时本端点 503（fail-closed）。
     */
    @PostMapping("/api/v1/integrations/executor/callback")
    public ResponseEntity<Map<String, String>> callback(@RequestBody String rawBody,
            @RequestHeader(value = "X-Dispatch-Signature", required = false) String sig)
            throws Exception {
        if (!dispatch.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "DISPATCH_DISABLED"));
        }
        if (sig == null || !constantTimeEquals(sig, hmacOf(rawBody))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "BAD_SIGNATURE"));
        }
        CallbackReq req = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(rawBody, CallbackReq.class);
        boolean ok = dispatch.callback(req.opId(), req.status(), req.detail());
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(Map.of("code", ok ? "OK" : "STATE_CONFLICT"));
    }

    private String hmacOf(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(dispatch.dispatchKeyForVerify(), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
