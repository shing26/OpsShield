package com.opspilot.gateway;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 登录面错误形状的回归锁（2026-09-28，探索性验收实测：`POST /api/v1/auth/login` 错口令返回的是
 * `application/problem+json`（`{type,title,status,detail,instance}`）——全仓**第三种**错误形状，
 * 既非文档声明的 `{code,message}`，也非 /v1 的 OpenAI 信封，还回显 `instance` 路径）。
 *
 * 根因：两个 `@ExceptionHandler` 返回 `ResponseStatusException` 对象，被 Spring 按 ErrorResponse 渲染，
 * 绕过了统一形状。修法是直写 `ErrorBodies`。本测试**直接调 handler 方法**（不启 MVC 栈）锁形状。
 */
class AuthControllerTest {

    /** 只测错误形状，不经 AuthService（两个 handler 不碰 auth 字段）。 */
    private final AuthController controller = new AuthController(null);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void badCredentialsUseCodeMessageShapeNotProblemJson() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.bad(resp);

        assertEquals(401, resp.getStatus());
        assertEquals("application/json;charset=UTF-8", resp.getContentType(),
                "不得是 application/problem+json");
        JsonNode body = mapper.readTree(resp.getContentAsString());
        assertEquals("UNAUTHORIZED", body.get("code").asText());
        assertEquals("invalid username or password", body.get("message").asText());
        assertFalse(body.has("type") || body.has("title") || body.has("instance"),
                "不得落 ErrorResponse 的字段（type/title/instance）：" + body);
    }

    @Test
    void loginLockedUsesSameShape() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        controller.locked(resp);

        assertEquals(429, resp.getStatus());
        JsonNode body = mapper.readTree(resp.getContentAsString());
        assertEquals("TOO_MANY_REQUESTS", body.get("code").asText());
        assertEquals("temporarily locked", body.get("message").asText());
    }

    /**
     * G0-W0.1：登录面是**未鉴权**面，字段长度必须有上限（请求体 1MB 过滤器是整包兜底，
     * 字段级契约才能让客户端拿到点名字段的 400）。边界取 128：正常账号/口令远小于它，
     * 又不是任意长——超长只可能是畸形/攻击输入。
     */
    @Test
    void loginRequestFieldsAreCappedAt128() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertTrue(validator.validate(new AuthController.LoginReq("u".repeat(128), "p".repeat(128))).isEmpty(),
                "128 字符是合法边界（含等号）");
        assertFalse(validator.validate(new AuthController.LoginReq("u".repeat(129), "p")).isEmpty(),
                "username 超 128 应拒");
        assertFalse(validator.validate(new AuthController.LoginReq("u", "p".repeat(129))).isEmpty(),
                "password 超 128 应拒");
        // @NotBlank 的既有语义不能被 @Size 的加入破坏
        assertFalse(validator.validate(new AuthController.LoginReq(" ", "p")).isEmpty());
        assertFalse(validator.validate(new AuthController.LoginReq("u", "")).isEmpty());
    }
}
