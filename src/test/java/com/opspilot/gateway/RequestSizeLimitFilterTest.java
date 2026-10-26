package com.opspilot.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 请求体 1MB 上限（G0-W0.1，2026-10-22 运维审查）。两个长度来源都要覆盖：
 * Content-Length 超限直接 413（不读 body），chunked（无 Content-Length）走计数流，累计超限同样 413。
 * 错误形状必须走 {@code ErrorBodies}（/api = code/message，/v1 = OpenAI 信封），
 * 且超限路径**不得**把请求交给下游（否则 Jackson 会把它读进内存，上限就白设了）。
 *
 * <p>变异验证：把 chunked 分支删掉 → {@code chunkedOverLimitIsRejected} 红；
 * 413 改成 400 → 形状断言红；上限改大 1 字节 → 边界用例红。
 */
class RequestSizeLimitFilterTest {

    private static final int MAX = (int) RequestSizeLimitFilter.MAX_BODY_BYTES;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter();

    @Test
    void smallBodyPassesThroughAndStaysReadable() throws Exception {
        String json = "{\"query\":\"上游限流怎么排查\"}";
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/chat");
        req.setContent(json.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicReference<String> seen = new AtomicReference<>();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(req, resp, recording(seen, invoked));

        assertTrue(invoked.get(), "未超限请求必须照常进下游");
        assertEquals(json, seen.get(), "包装后的 body 必须内容不变、编码不变");
        assertEquals("{\"query\":\"上游限流怎么排查\"}", seen.get());
        assertEquals(200, resp.getStatus());
    }

    @Test
    void bodyExactlyAtLimitPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/chat");
        req.setContent(new byte[MAX]);   // 恰好 1MB：合法（上限含等号）
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(req, resp, recording(new AtomicReference<>(), invoked));

        assertTrue(invoked.get());
        assertEquals(200, resp.getStatus());
    }

    @Test
    void declaredContentLengthOverLimitIsRejectedWithoutReadingBody() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/chat");
        req.setContent(new byte[MAX + 1]);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(req, resp, recording(new AtomicReference<>(), invoked));

        assertFalse(invoked.get(), "超限请求不得进下游（否则 body 仍会被读进内存）");
        assertEquals(413, resp.getStatus());
        assertEquals("application/json;charset=UTF-8", resp.getContentType());
        JsonNode body = MAPPER.readTree(resp.getContentAsString());
        assertEquals("PAYLOAD_TOO_LARGE", body.get("code").asText());
        assertTrue(body.get("message").asText().contains("1MB"), body.toString());
    }

    @Test
    void openAiFaceKeepsEnvelopeShapeOn413() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/v1/chat/completions");
        req.setContent(new byte[MAX + 1]);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        filter.doFilter(req, resp, recording(new AtomicReference<>(), new AtomicBoolean()));

        assertEquals(413, resp.getStatus());
        JsonNode body = MAPPER.readTree(resp.getContentAsString());
        assertTrue(body.has("error"), "OpenAI 兼容面必须保持 {\"error\":{...}} 信封: " + body);
        assertEquals("invalid_request_error", body.get("error").get("type").asText());
        assertFalse(body.has("code"), "不得把 /api 的 code/message 形状混进来: " + body);
    }

    @Test
    void chunkedOverLimitIsRejectedByCountingStream() throws Exception {
        // 无 Content-Length（Transfer-Encoding: chunked 的等义模拟）：必须边读边计数
        byte[] big = new byte[MAX + 1];
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/chat") {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        req.setContent(big);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(req, resp, recording(new AtomicReference<>(), invoked));

        assertFalse(invoked.get());
        assertEquals(413, resp.getStatus());
        assertEquals("PAYLOAD_TOO_LARGE", MAPPER.readTree(resp.getContentAsString()).get("code").asText());
    }

    @Test
    void chunkedSmallBodyPasses() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/copilot/chat") {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        req.setContent("{\"q\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(req, resp, recording(new AtomicReference<>(), invoked));

        assertTrue(invoked.get());
        assertEquals(200, resp.getStatus());
    }

    @Test
    void nonGuardedPathIsNotTouched() throws Exception {
        // 静态面板/actuator 不走本过滤器：它们的 body 语义不在本仓契约内
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/actuator/health");
        req.setContent(new byte[MAX + 1]);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(req, resp, recording(new AtomicReference<>(), invoked));

        assertTrue(invoked.get(), "未守卫路径必须原样放行（上限只覆盖 /api/** 与 /v1/**）");
        assertEquals(200, resp.getStatus());
    }

    @Test
    void bodylessGetIsPassedThroughWithoutBuffering() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/admin/state");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();
        filter.doFilter(req, resp, recording(new AtomicReference<>(), invoked));
        assertTrue(invoked.get());
    }

    /** 记录下游是否被调用、以及下游从（可能被包装的）请求里读到的 body 文本。 */
    private static FilterChain recording(AtomicReference<String> seen, AtomicBoolean invoked) {
        return (request, response) -> {
            invoked.set(true);
            seen.set(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        };
    }
}
