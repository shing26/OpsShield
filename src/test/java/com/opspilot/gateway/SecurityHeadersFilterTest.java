package com.opspilot.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全响应头（G0-W0.1，2026-10-22 运维审查）。裸机直连 8081 的形态没有反代补头，
 * 这组头必须由网关自己给；且必须在 chain 之前写入——异常路径（advice 直写 response）
 * 同样是浏览器要解释的响应，不能只有成功路径带防护。
 *
 * <p>变异验证：删掉任意一个头 → 对应用例红；把 setHeader 移到 chain 之后 →
 * {@code headersArePresentWhenDownstreamRejects} 红。
 */
class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void allProtectiveHeadersArePresentOnSuccess() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (rq, rs) -> { });

        assertEquals("nosniff", resp.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", resp.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", resp.getHeader("Referrer-Policy"));
        assertEquals("geolocation=(), microphone=(), camera=()", resp.getHeader("Permissions-Policy"));
    }

    @Test
    void headersArePresentWhenDownstreamRejects() throws Exception {
        // 401/413 等"提前返回"的响应同样要给头（防护不能只覆盖 happy path）
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/chat");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(req, resp, (rq, rs) -> {
            // FilterChain 的形参是 Servlet* 接口，写状态码要转回 HttpServletResponse
            jakarta.servlet.http.HttpServletResponse http = (jakarta.servlet.http.HttpServletResponse) rs;
            http.setStatus(401);
            http.getWriter().write("{\"code\":\"UNAUTHORIZED\"}");
        });

        assertEquals(401, resp.getStatus());
        assertEquals("DENY", resp.getHeader("X-Frame-Options"));
        assertEquals("nosniff", resp.getHeader("X-Content-Type-Options"));
    }

    @Test
    void existingHeaderFromUpstreamProxyIsNotOverwritten() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setHeader("X-Frame-Options", "SAMEORIGIN");   // 反代给了更具体的策略
        filter.doFilter(req, resp, (rq, rs) -> { });

        assertEquals("SAMEORIGIN", resp.getHeader("X-Frame-Options"), "不得覆盖上游更具体的策略");
    }
}
