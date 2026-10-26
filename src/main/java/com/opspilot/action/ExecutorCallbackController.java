package com.opspilot.action;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行侧回调（ADR-0028 §5 + 2025-10-25 契约 1/2）：dispatched → executed|failed 终态。
 * 鉴权三件套：HMAC（callback: 前缀 + ts + nonce，常量时间）→ 时间窗 ±300s →
 * nonce 一生一次。首次定终态；后续回调 409 且**落审计 action_callback_conflict**
 * （不静默——被拒的修正请求对运维可见）。机器调用方无 UserContext，审计 sub 为空。
 */
@RestController
public class ExecutorCallbackController {

    private final ActionDispatchService dispatch;
    private final com.opspilot.metrics.AuditService audit;
    private final com.opspilot.metrics.L1Metrics l1Metrics;

    public ExecutorCallbackController(ActionDispatchService dispatch,
                                      com.opspilot.metrics.AuditService audit,
                                      com.opspilot.metrics.L1Metrics l1Metrics) {
        this.dispatch = dispatch;
        this.audit = audit;
        this.l1Metrics = l1Metrics;
    }

    public record CallbackReq(String opId, String status, String detail, Long ts, String nonce) {}

    @PostMapping("/api/v1/integrations/executor/callback")
    public ResponseEntity<Map<String, String>> callback(@RequestBody CallbackReq req,
            @RequestHeader(value = "X-Dispatch-Signature", required = false) String sig) {
        if (!dispatch.callbackEnabled()) {          // 豁免 l1 开关：只有缺 key 才 503
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "DISPATCH_KEY_MISSING"));
        }
        if (req.opId() == null || req.ts() == null || req.nonce() == null || req.status() == null) {
            return ResponseEntity.badRequest().body(Map.of("code", "MISSING_FIELDS"));
        }
        // 1) 签名（契约 2：callback: 前缀 + ts + nonce，常量时间比较）
        String material = ActionDispatchService.callbackSignatureMaterial(
                req.opId(), req.status(), req.ts(), req.nonce());
        if (sig == null || !dispatch.signatureMatches(sig, material)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "UNAUTHORIZED", "reject_reason", "bad_signature"));
        }
        // 2) 时间窗 + nonce 一生一次（分层 reject_reason：两个 401 打不同层）
        String reason = dispatch.callbackFreshnessReason(req.ts(), req.nonce());
        if (reason != null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "UNAUTHORIZED", "reject_reason", reason));
        }
        // 3) 终态（契约 1·选 A）：首次定终态；后续 409 + 冲突审计（不静默）
        boolean first = dispatch.callback(req.opId(), req.status(), req.detail());
        if (!first) {
            String existing = dispatch.existingState(req.opId());
            l1Metrics.callbackConflict(dispatch.tenantOf(req.opId()));
            audit.log(null, "action_callback_conflict", "executor", "manual",
                    req.opId() + " " + req.status() + " 现有终态=" + existing,
                    null, "none", null, false, 0, 0, null, null, null, null);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "TERMINAL_STATE", "existing", existing));
        }
        audit.log(null, "action_result", "executor", "manual",
                req.opId() + " " + req.status() + " " + req.detail(),
                null, "none", null, false, 0, 0, null, null, null, null);
        return ResponseEntity.ok(Map.of("code", "OK"));
    }
}
