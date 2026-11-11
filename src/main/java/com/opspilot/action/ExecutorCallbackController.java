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

    /** N-10：回调 status 合法值闭集。执行侧只能报这两种终态；任何其它值（含 success / OK /
     *  Executed 大小写变体）都是调用方输入错误，不是状态机可解释的事件。 */
    static final java.util.Set<String> VALID_STATUSES = java.util.Set.of("executed", "failed");

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
        // 1.5) status 白名单（N-10）：位置是刻意的，两条约束同时成立——
        //   (a) 必须在**签名校验之后**：否则未认证调用方能拿 400/401 的差异当状态合法性探针，
        //       并破坏「未认证一律 401」的不变式；
        //   (b) 必须在**nonce 消耗（callbackFreshnessReason）之前**：未知状态若先占坑 nonce，
        //       调用方改正后同 nonce 重试会被误判 nonce_reused，把可纠正的输入错误变成不可恢复的终态风险。
        //   status 参与签名材料，验签通过即证明该值由持钥方发出；此处只做闭集判定。
        if (!VALID_STATUSES.contains(req.status())) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_STATUS"));
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
