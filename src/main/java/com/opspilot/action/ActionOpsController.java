package com.opspilot.action;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import jakarta.servlet.http.HttpServletRequest;

/** L1 受控行动契约端点（ADR-0028 规格 §5）。确认 = 面板已认证会话内点击（§0 裁决 1）。 */
@RestController
@RequestMapping("/api/v1/copilot/actions")
public class ActionOpsController {

    private final ActionOpsService ops;

    public ActionOpsController(ActionOpsService ops) {
        this.ops = ops;
    }

    public record ProposeReq(String ref, String command) {}
    public record ConfirmReq(String opId, String token) {}

    /** L1 提案：返回一次性确认 token（明文只出现这一次；URL 只定位不承载权限——§0 裁决 3）。 */
    @PostMapping("/propose")
    public Map<String, Object> propose(@RequestBody ProposeReq req, HttpServletRequest http) {
        UserContext user = JwtAuthFilter.from(http);
        ActionOpsService.ProposeResult r = ops.propose(user, req.ref(), req.command());
        return Map.of("op_id", r.opId(), "confirm_token", r.confirmToken(),
                "expires_at", r.expiresAt(),
                "note", "token 一次性且窗口 15 分钟；确认须由不同 principal 在面板完成");
    }

    /** L1 确认（面板）：confirmer≠proposer / 租户匹配 / 密级足够 / token 原子消费 / 窗口内。 */
    @PostMapping("/confirm")
    public ActionOpsService.StatusResult confirm(@RequestBody ConfirmReq req,
                                                 HttpServletRequest http) {
        return ops.confirm(JwtAuthFilter.from(http), req.opId(), req.token());
    }

    /** 本租户待确认 op 列表（面板确认入口的数据面；?state= 可过滤）。 */
    @GetMapping
    public java.util.List<ActionOpsService.StatusResult> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String state,
            HttpServletRequest http) {
        return ops.list(JwtAuthFilter.from(http), state);
    }

    /** 解冻（开关重开后由人操作）：frozen → confirmed → 自动派发。 */
    @PostMapping("/{opId}/unfreeze")
    public ActionOpsService.StatusResult unfreeze(@PathVariable String opId, HttpServletRequest http) {
        return ops.unfreeze(JwtAuthFilter.from(http), opId);
    }

    /** 取消：frozen → expired（不再执行）。 */
    @PostMapping("/{opId}/cancel")
    public ActionOpsService.StatusResult cancel(@PathVariable String opId, HttpServletRequest http) {
        return ops.cancel(JwtAuthFilter.from(http), opId);
    }

    @GetMapping("/{opId}")
    public ActionOpsService.StatusResult status(@PathVariable String opId, HttpServletRequest http) {
        return ops.status(JwtAuthFilter.from(http), opId);
    }

    /** 判定/守卫失败 = 4xx（客户端可决策），不 5xx；stage 供红队区分打在哪一层。 */
    @ExceptionHandler(ActionOpsService.ActionRejectedException.class)
    public ResponseEntity<Map<String, String>> rejected(ActionOpsService.ActionRejectedException e) {
        return ResponseEntity.status("self_confirm".equals(e.stage) ||
                "confirmer_not_authorized".equals(e.stage) || "cross_tenant".equals(e.stage)
                || "op_level_insufficient".equals(e.stage)
                ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "ACTION_REJECTED", "stage", e.stage,
                        "message", e.getMessage()));
    }
}
