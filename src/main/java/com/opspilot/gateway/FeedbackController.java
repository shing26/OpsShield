package com.opspilot.gateway;

import com.opspilot.auth.JwtAuthFilter;
import com.opspilot.auth.UserContext;
import com.opspilot.gateway.dto.FeedbackRequest;
import com.opspilot.metrics.AuditService;
import com.opspilot.metrics.OpsMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 答案反馈端点（闭环前置）：把"这个答案不对"变成系统可读的信号。
 *
 * 为什么需要它：在此之前人**无法**把"答错了"告诉系统——答案对错的唯一人工信号只存在于验收期，
 * 而"复盘 → 知识回灌"（`OPS.md` §5.1 在案债务，触发线=同一根因被人工复盘 ≥2 次）正缺这个入口：
 * 没有反馈，就没人知道该复盘哪一条。它同时补上"人机协同"缺口里唯一真实的那一处（其余如 HITL
 * 审批对只读系统是空转，刻意不做）。
 *
 * 设计要点：
 * - **按 fingerprint 归档**（理由见 {@link FeedbackRequest} 与 `AuditService.logFeedback`）：
 *   标识"问题"而非"某次生成"。
 * - **不占配额**：反馈是治理信号不是成本。若占配额，用户会在配额耗尽时放弃上报坏答案，
 *   而那恰恰是最该被听到的声音。与 `/search`（评测路径豁免配额）同一判断逻辑。
 * - **审计走辅助写入器** `ev="feedback"`，不扩展 chat 那条 13 参 `log()`——反馈是另一种事件，
 *   没有 cache_hit/mode/took_ms 可言，硬塞会让那条记录长出一排无意义的默认值。
 */
@RestController
@RequestMapping("/api/v1/copilot")
public class FeedbackController {

    private final AuditService audit;
    private final OpsMetrics metrics;

    public FeedbackController(AuditService audit, OpsMetrics metrics) {
        this.audit = audit;
        this.metrics = metrics;
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@Valid @RequestBody FeedbackRequest req, HttpServletRequest http) {
        UserContext user = JwtAuthFilter.from(http);
        // 纵深防御：JwtAuthFilter 已在入口拒绝无效凭证（401）；此处兜底任何绕过 filter 的调用路径
        // （与 SearchController 同款写法——单测直接调 controller 时 filter 不参与）。
        if (user == null || user.authLevel() < 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "auth_level 非法（须 >=1）");
        }
        audit.logFeedback(user, req.fingerprint(), req.verdict(), req.note());
        // 反馈不只是"记一行日志"：Prometheus 侧给一条 0 基线的 up/down 计数，
        // 复核队列（offline/feedback_review.py）与抓取侧 PromQL 都以它为入口。
        // 先审计后计数：审计行是事实，指标是派生——反过来的话计数失败就没痕可查。
        metrics.feedback(req.verdict());
        return Map.of("ok", true);
    }
}
