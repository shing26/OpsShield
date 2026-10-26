package com.opspilot.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 错误响应体的形状锁（2026-09-28，探索性验收实测"同一提交里跑着四种形状，文档只声明两种"）。
 *
 * 本类锁的是**形状**本身；"哪个出口用哪一种"由调用方各自的分支锁（见 JwtAuthFilterTest、
 * GlobalExceptionHandlerTest、OpenAiControllerTest）。这样形状改动会让所有出口一起红，
 * 而不是只红一个面——这正是收敛到单点定义的意义。
 */
class ErrorBodiesTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode parse(MockHttpServletResponse resp) throws Exception {
        assertEquals("application/json;charset=UTF-8", resp.getContentType());
        return M.readTree(resp.getContentAsString());
    }

    @Test
    void apiShapeIsCodePlusMessageOnly() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ErrorBodies.writeApi(resp, 401, "UNAUTHORIZED", "missing bearer token");
        assertEquals(401, resp.getStatus());
        JsonNode body = parse(resp);
        assertEquals("UNAUTHORIZED", body.get("code").asText());
        assertEquals("missing bearer token", body.get("message").asText());
        assertEquals(2, body.size(), "非 /v1 面只允许 code+message 两个字段，实际: " + body);
        assertFalse(body.has("timestamp") || body.has("path") || body.has("error"),
                "不得掺入 Spring 默认错误体字段（timestamp/path/error）: " + body);
    }

    @Test
    void openAiShapeIsEnvelopeAndOmitsCodeUnlessGiven() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ErrorBodies.writeOpenAi(resp, 400, "Malformed JSON request body", "invalid_request_error", null);
        assertEquals(400, resp.getStatus());
        JsonNode err = parse(resp).get("error");
        assertEquals("Malformed JSON request body", err.get("message").asText());
        assertEquals("invalid_request_error", err.get("type").asText());
        assertFalse(err.has("code"), "code 为 null 时不得落字段（OpenAI 规范允许缺省，塞状态码属语义漂移）");
    }

    @Test
    void openAiShapeCarriesMachineCodeWhenProvided() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        ErrorBodies.writeOpenAi(resp, 401, "Invalid API key.", "authentication_error", "invalid_api_key");
        JsonNode err = parse(resp).get("error");
        assertEquals("invalid_api_key", err.get("code").asText());
    }
}
