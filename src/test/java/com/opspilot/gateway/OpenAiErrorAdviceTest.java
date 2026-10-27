package com.opspilot.gateway;

import com.opspilot.metrics.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * /v1 面 400 类留痕的回归锁（2026-09-28，探索性验收实测"同面 415 落审计、400 不落"的不对称）。
 *
 * 口径沿用全局（`GlobalExceptionHandler` 类注释）：**400 类落 `ev=invalid`，404/405 属扫描噪音不落**；
 * 429（配额/限流）与 401（守卫）各有专属留痕面，不在本 advice 重复。
 */
class OpenAiErrorAdviceTest {

    private final AuditService audit = mock(AuditService.class);
    private final OpenAiErrorAdvice advice = new OpenAiErrorAdvice(audit);

    private static MockHttpServletRequest req() {
        return new MockHttpServletRequest("POST", "/v1/chat/completions");
    }

    @Test
    void malformedBodyIsAuditedAsInvalid() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        advice.unreadable(new HttpMessageNotReadableException("boom", (org.springframework.http.HttpInputMessage) null), req(), resp);

        assertEquals(400, resp.getStatus());
        verify(audit, timeout(1_000)).logInvalid(isNull(), eq("/v1/chat/completions"), anyString());
        assertTrue(resp.getContentAsString().contains("invalid_request_error"),
                "形状仍是 OpenAI 信封: " + resp.getContentAsString());
    }

    /** 业务 400（非流式 / content parts 非法 / Content-Type 缺失）：同一面必须同样留痕。 */
    @Test
    void businessBadRequestIsAuditedToo() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        advice.status(new ResponseStatusException(HttpStatus.BAD_REQUEST, "stream=true required"), req(), resp);

        assertEquals(400, resp.getStatus());
        verify(audit, timeout(1_000)).logInvalid(isNull(), eq("/v1/chat/completions"), contains("stream=true required"));
    }

    /** 反向：429 不是"输入校验失败"，不得混进 ev=invalid（它走专属路径，配额面另有留痕）。 */
    @Test
    void rateLimitIsNotLoggedAsInvalid() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        advice.status(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "quota exceeded"), req(), resp);

        assertEquals(429, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("rate_limit_error"));
        verify(audit, never()).logInvalid(any(), anyString(), anyString());
    }

    /** 415 的留痕口径保持原样（QA 第五轮 P1 的成果，别在收敛形状时改掉）。 */
    @Test
    void unsupportedMediaTypeStillAudited() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        advice.mediaType(new org.springframework.web.HttpMediaTypeNotSupportedException("text/plain"), req(), resp);

        assertEquals(415, resp.getStatus());
        verify(audit, timeout(1_000)).logInvalid(isNull(), eq("/v1/chat/completions"), contains("Content-Type 不被支持"));
        assertTrue(resp.getContentAsString().contains("invalid_request_error"));
    }
}
