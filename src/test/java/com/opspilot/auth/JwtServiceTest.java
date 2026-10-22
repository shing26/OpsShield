package com.opspilot.auth;

import com.opspilot.config.OpsDeployProperties;
import com.opspilot.config.OpsPilotProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 密钥门（G0-W0.1，2026-10-22 运维审查）：blank 一律拒绝；strict 下拒绝占位/短密钥；
 * 非 strict（本机演示）保持旧行为不回归。错误信息必须点名 {@code JWT_SECRET}——
 * "启动失败但不知道去配哪个变量"是这轮审查要修的运行可信问题之一。
 *
 * <p>变异验证：去掉 strict 的长度判断 → {@code strictRejectsShortSecret} 红；
 * 去掉占位标记判断 → {@code strictRejectsPlaceholderSecret} 红；
 * 把 strict 判断改成无条件 → 演示路径用例 {@code nonStrictStillAcceptsDemoPlaceholder} 红。
 */
class JwtServiceTest {

    /** 与 application.yml 数值面一致的最小合法 props（本测试只关心 jwt.secret）。 */
    private static OpsPilotProperties props(String secret) {
        return new OpsPilotProperties(
                new OpsPilotProperties.DashScope("https://dashscope.aliyuncs.com", "k", "text-embedding-v3",
                        1024, "gte-rerank-v2", "qwen-plus", 60, 3000, 15000, "live"),
                new OpsPilotProperties.Es("http://localhost:9200", "opspilot-chunks-read", null, null, 3000, 10000),
                new OpsPilotProperties.Qdrant("localhost", 6334, "opspilot-vectors-live", "semantic-cache", null),
                new OpsPilotProperties.Jwt(secret, 86400),
                new OpsPilotProperties.Cache(2, 0.95),
                new OpsPilotProperties.Storm(30, 60),
                new OpsPilotProperties.Retrieval(50, 50, 60, 20, 3, 4500, 0.2),
                new OpsPilotProperties.Degrade(40, 3, 60));
    }

    private static JwtService strict(String secret) {
        return new JwtService(props(secret), new OpsDeployProperties(true, false));
    }

    @Test
    void blankSecretIsRejectedInEveryMode() {
        for (String blank : new String[]{null, "", "   "}) {
            for (boolean strict : new boolean[]{false, true}) {
                IllegalStateException e = assertThrows(IllegalStateException.class,
                        () -> new JwtService(props(blank), new OpsDeployProperties(strict, false)),
                        "blank 在任何形态下都必须拒绝, strict=" + strict);
                assertTrue(e.getMessage().contains("JWT_SECRET"),
                        "报错必须点名 JWT_SECRET（否则运维不知道该配哪个变量）: " + e.getMessage());
            }
        }
    }

    @Test
    void strictRejectsPlaceholderSecret() {
        String placeholder = "change-me-" + "x".repeat(40);   // 长度够，但仍是 .env.example 的占位形态
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> strict(placeholder));
        assertTrue(e.getMessage().contains("JWT_SECRET"), e.getMessage());
        assertTrue(e.getMessage().contains("占位"), "报错要点明是占位值而不是笼统的弱密钥: " + e.getMessage());
    }

    @Test
    void strictRejectsShortSecret() {
        String shortSecret = "s".repeat(JwtService.MIN_SECRET_LENGTH - 1);   // 42 字符：够 HS256 字节数，但不够口子
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> strict(shortSecret));
        assertTrue(e.getMessage().contains("JWT_SECRET"), e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(JwtService.MIN_SECRET_LENGTH)),
                "报错要给出目标长度（" + JwtService.MIN_SECRET_LENGTH + "）：" + e.getMessage());
    }

    @Test
    void strictAcceptsStrongSecretAndRoundTrips() {
        JwtService jwt = strict("s".repeat(JwtService.MIN_SECRET_LENGTH));
        String token = jwt.issue("sre-full", "platform", "default", 3, 1, 60);
        var claims = jwt.parse(token);
        assertEquals("sre-full", claims.getSubject());
        assertEquals("platform", claims.get("role", String.class));
        assertEquals(3, claims.get("auth_level", Integer.class));
    }

    @Test
    void nonStrictStillAcceptsDemoPlaceholder() {
        // 本机演示/单测形态：.env.example 的占位值必须照常可用，否则演示路径直接起不来（回归）
        JwtService jwt = new JwtService(props("change-me-" + "x".repeat(40)));
        String token = jwt.issue("demo", "platform", "default", 3, 1, 60);
        assertEquals("demo", jwt.parse(token).getSubject());
    }
}
