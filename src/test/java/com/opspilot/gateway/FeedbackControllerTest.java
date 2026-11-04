package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.gateway.dto.FeedbackRequest;
import com.opspilot.metrics.AuditService;
import com.opspilot.metrics.OpsMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 反馈端点的三态锁：认证与密级防线不得被绕过，合法反馈必须原样落审计。
 *
 * 为什么这三条值得锁：反馈是"人 → 系统"的唯一人工信号入口，而它同时是"复盘→知识回灌"
 * 在案债务的前置。若它静默失效（无痕、或被未认证者灌入），那条债务链会断在这里而无人察觉。
 *
 * <p>2026-11-05 起反馈多了一个出口：Prometheus 的 {@code aiops.feedback{verdict}}。它和审计行
 * **同生同死**——要么都写，要么都不写。拒绝路径（403）必须两个出口都没动过，否则"被拒绝的
 * 反馈"会污染质量闭环的分子，让复核队列看到一条根本不存在的反馈。
 */
class FeedbackControllerTest {

    /** 32 位十六进制（与 FingerprintService 的 SHA-256 前 128 位一致）。 */
    private static final String FP_VALID = "ab3fdc9c0e1a4f8e9c1d2b3a4c5d6e7f";

    private final AuditService audit = mock(AuditService.class);
    private final OpsMetrics metrics = mock(OpsMetrics.class);
    private final FeedbackController controller = new FeedbackController(audit, metrics);

    private static MockHttpServletRequest withUser(int level) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.REQUEST_ATTR, new UserContext("u", "sre", level, "tenant-demo"));
        return req;
    }

    /** 无身份属性 = filter 未参与（绕过路径）：必须 403，且不得写任何审计行。 */
    @Test
    void unauthenticatedIsForbiddenWithoutAuditing() {
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.feedback(new FeedbackRequest("fp-1", "down", null),
                        new MockHttpServletRequest()));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(audit);
        verifyNoInteractions(metrics);
    }

    @Test
    void zeroAndNegativeLevelsAreForbiddenWithoutAuditing() {
        for (int level : new int[]{0, -1}) {
            var ex = assertThrows(ResponseStatusException.class,
                    () -> controller.feedback(new FeedbackRequest("fp-1", "down", null), withUser(level)),
                    "level=" + level + " 必须被 Controller 兜底拒绝");
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        }
        verifyNoInteractions(audit);
        verifyNoInteractions(metrics);
    }

    @Test
    void validFeedbackIsAuditedWithIdentityAndVerdict() {
        var resp = controller.feedback(new FeedbackRequest(FP_VALID, "down", "答案里的错误码是编的"),
                withUser(2));
        assertEquals(Boolean.TRUE, resp.get("ok"));
        verify(audit).logFeedback(argThat(u -> "tenant-demo".equals(u.tenantId()) && u.authLevel() == 2),
                eq(FP_VALID), eq("down"), eq("答案里的错误码是编的"));
        verify(metrics).feedback("down");
    }

    /** 未知 fingerprint 不是错误：系统不认识它也不该崩——反馈的价值在"收下"，不在"先校验"。 */
    @Test
    void unknownFingerprintIsAcceptedNotRejected() {
        var resp = controller.feedback(new FeedbackRequest("00000000000000000000000000000000", "up", null),
                withUser(1));
        assertEquals(Boolean.TRUE, resp.get("ok"));
        verify(audit).logFeedback(any(), eq("00000000000000000000000000000000"), eq("up"), isNull());
        verify(metrics).feedback("up");
    }

    /**
     * F-5（2026-09-28，live QA）：fingerprint 形状非法 → 400 + `ev=invalid` 留痕。
     *
     * 为什么值得一条 MockMvc 用例（本类其余都是直调 controller）：形状校验的拒绝发生在
     * **MVC 校验层**（`@Pattern` → MethodArgumentNotValidException → 全局 advice），直调
     * controller 锁不住它；而验收口径是"HTTP 400 且 body 含 INVALID_REQUEST、审计 +1"。
     */
    @Test
    void malformedFingerprintIsRejectedWith400AndAudited() throws Exception {
        AuditService audit2 = mock(AuditService.class);
        OpsMetrics metrics2 = mock(OpsMetrics.class);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new FeedbackController(audit2, metrics2))
                .setControllerAdvice(new GlobalExceptionHandler(audit2))
                .build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/copilot/feedback")
                        .contentType("application/json")
                        .content("{\"fingerprint\":\"not-a-fingerprint!!\",\"verdict\":\"down\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verify(audit2).logInvalid(isNull(), eq("/api/v1/copilot/feedback"), contains("fingerprint"));
        // 校验层就拒掉的行不该在指标里留下痕迹——否则复核队列会去追一条从未被接收的反馈。
        verifyNoInteractions(metrics2);
    }
}
