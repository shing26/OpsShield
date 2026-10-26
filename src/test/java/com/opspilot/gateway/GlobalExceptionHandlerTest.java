package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.metrics.AuditService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * QA P2-1/P2-2 回归锁（错误形状）：SSE 端点校验失败时，错误文案必须真正到达客户端。
 * 旧实现返回 ResponseEntity<Map>，在 Accept: text/event-stream 下被内容协商吞成
 * 406→裸 400 空 body（小周实测"改请求改到怀疑自己"）；现直写 JSON 字节绕开协商。
 */
class GlobalExceptionHandlerTest {

    private final AuditService audit = mock(AuditService.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(audit);

    /** 仅需一个带参方法做 MethodParameter 载体；异常本身只消费 BindingResult。 */
    static void dummyBindingTarget(String query) {}

    private static MethodArgumentNotValidException invalid(String field, String msg) throws Exception {
        Method m = GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyBindingTarget", String.class);
        MethodParameter mp = new MethodParameter(m, 0);
        BeanPropertyBindingResult br = new BeanPropertyBindingResult(new Object(), "chatRequest");
        br.addError(new FieldError("chatRequest", field, msg));
        return new MethodArgumentNotValidException(mp, br);
    }

    @Test
    void validationErrorOnSseAcceptStillReachesClientBody() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/chat/stream");
        req.addHeader("Accept", "text/event-stream");
        req.setAttribute(UserContext.REQUEST_ATTR,
                new UserContext("sre-x", "sre", 1, "tenant-internal"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        handler.onValidation(invalid("query", "不能为空"), req, resp);

        assertEquals(400, resp.getStatus());
        assertTrue(resp.getContentType().startsWith("application/json"),
                "直写必须钉死 JSON 内容类型（不随 Accept 协商）: " + resp.getContentType());
        String body = resp.getContentAsString();
        assertTrue(body.contains("query") && body.contains("不能为空"), "文案必达: " + body);
        verify(audit).logInvalid(any(UserContext.class), eq("/api/v1/copilot/chat/stream"), contains("不能为空"));
    }

    @Test
    void statusAndGenericErrorsAreDirectWritten() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/reingest");
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        handler.onStatus(new ResponseStatusException(HttpStatus.FORBIDDEN, "需要平台管理员凭证"), req, r1);
        assertEquals(403, r1.getStatus());
        assertTrue(r1.getContentAsString().contains("HTTP_403"));

        MockHttpServletResponse r2 = new MockHttpServletResponse();
        handler.onOther(new RuntimeException("jdbc leaked // SELECT * FROM users"), req, r2);
        assertEquals(500, r2.getStatus());
        assertFalse(r2.getContentAsString().contains("SELECT"), "内部细节不得外泄");
        verifyNoInteractions(audit); // onOther 不产 invalid 事件（栈进日志，事件只归 400）
    }

    /** QA 第五轮 P1：/v1 合法 JSON 无 Content-Type 曾 500 server_error——现 415 + OpenAI 形状。 */
    @Test
    void unsupportedMediaTypeIs415AndOpenAiShapeOnV1() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse r = new MockHttpServletResponse();
        handler.onMediaTypeNotSupported(new HttpMediaTypeNotSupportedException("text/plain"), req, r);
        assertEquals(415, r.getStatus());
        String body = r.getContentAsString();
        assertTrue(body.contains("\"error\"") && body.contains("invalid_request_error"), body);
        verify(audit).logInvalid(isNull(), eq("/v1/chat/completions"), contains("Content-Type"));
    }

    /** QA 第五轮 P1：参数类型错（since=abc/1e18）曾落 500 兜底——现 400 + INVALID_REQUEST + 留痕。 */
    @Test
    void typeMismatchIs400WithAuditTrail() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/audit/recent");
        MethodParameter mp = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyBindingTarget", String.class), 0);
        var ex = new MethodArgumentTypeMismatchException("abc", Long.class, "since", mp,
                new NumberFormatException("abc"));
        MockHttpServletResponse r = new MockHttpServletResponse();
        handler.onTypeMismatch(ex, req, r);
        assertEquals(400, r.getStatus());
        String body = r.getContentAsString();
        assertTrue(body.contains("INVALID_REQUEST") && body.contains("since"), body);
        verify(audit).logInvalid(isNull(), eq("/api/v1/admin/audit/recent"), contains("since"));
    }

    /** QA 第五轮 P1：POST-only 端点被 GET 曾落 500——现 405 + Allow 头，不落审计。 */
    @Test
    void wrongMethodIs405WithAllowHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/cache/flush");
        MockHttpServletResponse r = new MockHttpServletResponse();
        handler.onMethodNotSupported(
                new HttpRequestMethodNotSupportedException("GET", List.of("POST")), req, r);
        assertEquals(405, r.getStatus());
        assertEquals("POST", r.getHeader("Allow"));
        verifyNoInteractions(audit);
    }

    /** /v1 面的错误经全局 handler 也必须保持 OpenAI 形状（方法/协商异常进不了专属 advice）。 */
    @Test
    void methodErrorsOnV1KeepOpenAiShape() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/v1/chat/completions");
        MockHttpServletResponse r = new MockHttpServletResponse();
        handler.onMethodNotSupported(
                new HttpRequestMethodNotSupportedException("PUT", List.of("POST")), req, r);
        assertEquals(405, r.getStatus());
        String body = r.getContentAsString();
        assertTrue(body.contains("\"error\"") && body.contains("invalid_request_error"), body);
        assertFalse(body.contains("INTERNAL_ERROR"), "全局形状不得漏到 /v1");
    }

    /** QA 第五轮 P2：favicon 与未知静态路径曾 500——现 404 且零审计噪音。 */
    @Test
    void missingStaticResourceIs404WithoutAudit() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/favicon.ico");
        MockHttpServletResponse r = new MockHttpServletResponse();
        handler.onNoResource(new NoResourceFoundException(HttpMethod.GET, "favicon.ico", "No static resource favicon.ico."), req, r);
        assertEquals(404, r.getStatus());
        assertTrue(r.getContentAsString().contains("NOT_FOUND"));
        verifyNoInteractions(audit);
    }

    /** F1（2026-09-13 模块验证）：copilot 面请求体字段类型错曾落 500——现 400 + INVALID_REQUEST + 留痕。 */
    @Test
    void unreadableBodyOnCopilotFaceIs400WithAudit() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/search");
        req.setAttribute(UserContext.REQUEST_ATTR,
                new UserContext("sre-x", "sre", 3, "tenant-internal"));
        MockHttpServletResponse r = new MockHttpServletResponse();
        var ex = new HttpMessageNotReadableException(
                "JSON parse error: Cannot deserialize value of type `java.lang.String` from Object value",
                (org.springframework.http.HttpInputMessage) null);
        handler.onUnreadable(ex, req, r);
        assertEquals(400, r.getStatus());
        String body = r.getContentAsString();
        assertTrue(body.contains("INVALID_REQUEST"), body);
        assertFalse(body.contains("Cannot deserialize") || body.contains("java.lang.String"),
                "解析 cause 细节不得外泄: " + body);
        verify(audit).logInvalid(any(UserContext.class), eq("/api/v1/copilot/search"), contains("请求体"));
    }

    /** F2（2026-09-13 公开前复验）：Accept: application/json 打 SSE 端点 → 协商异常曾落 500，现 406。 */
    @Test
    void notAcceptableOnV1Is406Not500() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse r = new MockHttpServletResponse();
        handler.onNotAcceptable(new HttpMediaTypeNotAcceptableException("No acceptable representation"), req, r);
        assertEquals(406, r.getStatus());
        String body = r.getContentAsString();
        assertTrue(body.contains("\"error\"") && body.contains("text/event-stream"), body);
        verifyNoInteractions(audit); // 协议噪音，对齐 404/405 不落审计先例
    }

    /** 纵深防御：即便畸形 body 异常漏到全局 handler，/v1 面也必须保持 OpenAI 形状（非 {code,message}）。 */
    @Test
    void unreadableBodyOnV1KeepsOpenAiShape() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse r = new MockHttpServletResponse();
        handler.onUnreadable(new HttpMessageNotReadableException("boom", (org.springframework.http.HttpInputMessage) null), req, r);
        assertEquals(400, r.getStatus());
        String body = r.getContentAsString();
        assertTrue(body.contains("\"error\"") && body.contains("invalid_request_error"), body);
        assertFalse(body.contains("INVALID_REQUEST"), "全局形状不得漏到 /v1");
    }
}
