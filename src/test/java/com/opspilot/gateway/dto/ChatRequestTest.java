package com.opspilot.gateway.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code alertId} 形状契约锁（2026-10-17 拆身份）：字段可选、空白按未提供、
 * 非法形状经 {@code GlobalExceptionHandler} 转 400 + 留痕（与 {@code FeedbackRequest} 同范式）。
 *
 * <p>变异验证：把缺省分支删掉（空白不再归一）→ 第二条必红；把正则放宽到任意字符串 → 第四条必红。
 */
class ChatRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static ChatRequest req(String alertId) {
        return new ChatRequest("上游模型限流", "alert", "opspilot", "local", alertId);
    }

    @Test
    void omittedAlertIdIsValidAndFallsBackToTextFingerprint() {
        ChatRequest r = new ChatRequest("q", "manual", null, null, null);
        assertTrue(validator.validate(r).isEmpty(), "alertId 是可选字段，缺省必须合法");
        assertNull(r.alertIdOrNull());
    }

    @Test
    void blankAlertIdIsTreatedAsAbsentNotAsA400() {
        for (String blank : new String[]{"", "   "}) {
            ChatRequest r = req(blank);
            assertTrue(validator.validate(r).isEmpty(), "空白按未提供（X-Trace-Id 同款）: " + blank);
            assertNull(r.alertIdOrNull(), "空白必须归一到缺席: " + blank);
        }
    }

    @Test
    void wellFormedAlertIdPassesConstraints() {
        assertTrue(validator.validate(req("c817f438192dbb63f17859b36e149f81")).isEmpty());
        assertTrue(validator.validate(req("OpsPilot.upstream-rate|firing")).isEmpty());
    }

    @Test
    void malformedAlertIdIsRejectedAs400() {
        assertFalse(validator.validate(req("short")).isEmpty(), "短于 8 位应拒");
        assertFalse(validator.validate(req("bad id with spaces")).isEmpty(), "空白分隔应拒");
        assertFalse(validator.validate(req("x".repeat(129))).isEmpty(), "超 128 位应拒");
    }

    /**
     * 线格式锁（2026-10-17 live 验收抓到）：请求体字段名是 camelCase {@code alertId}
     * （Jackson 默认命名策略）。发 {@code alert_id} 不会报 400——它被静默忽略，身份退回
     * 文本指纹，于是"同一 alertId 的 fp 仍随文案变化"这个缺陷在单测里看不出来。
     * 该用例把契约钉在反序列化层，而不是只测 record 构造器。
     */
    @Test
    void jsonWireNameIsCamelCaseAlertId() throws Exception {
        // 与 Spring Boot 自动配置的 ObjectMapper 对齐：默认关闭 FAIL_ON_UNKNOWN_PROPERTIES，
        // 所以发错字段名不是 400，而是**静默忽略**（2026-10-17 live 验收的真实失效方式）。
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        ChatRequest camel = mapper.readValue(
                "{\"query\":\"q\",\"source\":\"alert\",\"service\":\"opspilot\",\"env\":\"local\","
                        + "\"alertId\":\"c817f438192dbb63f17859b36e149f81\"}", ChatRequest.class);
        assertEquals("c817f438192dbb63f17859b36e149f81", camel.alertIdOrNull());

        ChatRequest snake = mapper.readValue(
                "{\"query\":\"q\",\"source\":\"alert\",\"service\":\"opspilot\",\"env\":\"local\","
                        + "\"alert_id\":\"c817f438192dbb63f17859b36e149f81\"}", ChatRequest.class);
        assertNull(snake.alertIdOrNull(),
                "snake_case 不是线格式（会被静默忽略）；若要加兼容别名，必须先改这条锁");
    }

    /**
     * G0-W0.1：query/service/env 的长度上限（8192/128/32）。
     *
     * <p>这三项会进缓存键、审计行与面板元数据——无上限时一条超长 query 就能把审计行撑爆，
     * 并且 1MB 的请求体过滤器只会在"整包"层面拒绝，没法告诉客户端是哪个字段超了。
     * 边界值按"含等号"测：8192 合法、8193 拒绝，避免把上限实现成"小于"。
     */
    @Test
    void oversizedQueryServiceEnvAreRejectedAs400() {
        assertTrue(validator.validate(new ChatRequest("q".repeat(8192), "manual",
                "s".repeat(128), "e".repeat(32), null)).isEmpty(), "边界值（8192/128/32）必须合法");
        assertFalse(validator.validate(new ChatRequest("q".repeat(8193), "manual", null, null, null)).isEmpty(),
                "query 超 8192 应拒");
        assertFalse(validator.validate(new ChatRequest("q", "manual", "s".repeat(129), null, null)).isEmpty(),
                "service 超 128 应拒");
        assertFalse(validator.validate(new ChatRequest("q", "manual", null, "e".repeat(33), null)).isEmpty(),
                "env 超 32 应拒");
    }

    /** 空值仍合法：上限不改变"可选字段"语义（@Size 对 null 通过）。 */
    @Test
    void lengthCapsDoNotMakeOptionalFieldsRequired() {
        assertTrue(validator.validate(new ChatRequest("q", null, null, null, null)).isEmpty());
    }
}
