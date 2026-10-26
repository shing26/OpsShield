package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.config.AdmissionProperties;
import com.opspilot.gateway.dto.ChatRequest;
import com.opspilot.resilience.AdmissionGate;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 配额 × 准入的顺序锁（#31）。
 *
 * <p>历史实现是「先扣配额、再拿准入许可」，于是被准入 429 的请求照样消耗当日配额——突发并发下
 * 用户没得到任何服务却把 5000/天 打光，之后连单发也被拒，与 ADR-0026 决策 4 相反。
 *
 * <p>这里用真实 {@link AdmissionGate}（不是 mock）：要验的正是「信号量有没有被慢性泄漏」，
 * mock 掉它就只能验调用顺序、验不出泄漏。
 */
class CopilotControllerTest {

    private final ChatOrchestrator orchestrator = mock(ChatOrchestrator.class);
    private final SseEvents events = mock(SseEvents.class);
    private final AdmissionGate admission = spy(new AdmissionGate(new AdmissionProperties(1, 1, 1)));
    private final CopilotController controller = new CopilotController(orchestrator, events, admission);

    private static MockHttpServletRequest withUser() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.REQUEST_ATTR,
                new UserContext("sre-full", "sre", 3, "tenant-internal"));
        return req;
    }

    private static ChatRequest body() {
        return new ChatRequest("50012_DB_TIMEOUT 怎么查", "manual", "", "prod", null);
    }

    /** 准入拒绝 → 429，且**一次配额都不扣**（precheck 根本不该被调用）。 */
    @Test
    void admissionRejectionDoesNotConsumeQuota() {
        assertNotNull(admission.tryAcquire("tenant-internal"), "先占满唯一许可");
        var ex = assertThrows(ResponseStatusException.class, () -> controller.chatStream(
                body(), withUser(), new MockHttpServletResponse()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        verify(orchestrator, never()).precheck(any());
        verify(orchestrator).recordAdmissionRejected();
    }

    /** 配额拒绝 → 429，但已拿到的准入许可必须当场归还（否则每次拒绝泄漏一个槽位）。 */
    @Test
    void quotaRejectionReleasesAdmissionPermit() {
        doThrow(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "日配额已用尽"))
                .when(orchestrator).precheck(any());
        assertThrows(ResponseStatusException.class, () -> controller.chatStream(
                body(), withUser(), new MockHttpServletResponse()));
        assertEquals(1, admission.availableGlobalPermits(), "全局许可必须归还");
        assertEquals(1, admission.availableTenantPermits("tenant-internal"), "租户许可必须归还");
        verify(orchestrator, never()).submit(any(), any(), any(), any(), any());
    }

    /** 正常路径：先拿许可、再扣配额、最后把许可交给编排（由编排 finally 释放）。 */
    @Test
    void permitIsAcquiredBeforeQuotaIsConsumed() {
        controller.chatStream(body(), withUser(), new MockHttpServletResponse());
        InOrder inOrder = inOrder(admission, orchestrator);
        inOrder.verify(admission).tryAcquire("tenant-internal");
        inOrder.verify(orchestrator).precheck(any());
        verify(orchestrator).submit(any(), any(), any(), eq("sse"), any());
    }
}
