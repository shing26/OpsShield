package com.opspilot.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 部署期安全校验（G0-W0.1，2026-10-22 运维审查）：
 * {@code OPSPILOT_STRICT=true} 时五类"安全默认没到位"必须<b>启动期失败且点名环境变量</b>；
 * 非 strict 演示路径不得被误拦（回归锁）；{@code OPSPILOT_ALLOW_PUBLIC_BIND} 是绑 0.0.0.0 的
 * 唯一逃生门。
 *
 * <p>直接构造 guard 而不起 Spring 上下文：本类只做属性读取与判定，没有容器协作面。
 * Environment 用 {@link MockEnvironment} 模拟 {@code server.address} / {@code spring.datasource.password}
 * （真实运行里它们分别来自 {@code SERVER_ADDRESS} / {@code H2_DB_PASSWORD}）。
 */
class DeploymentGuardTest {

    private static final String STRONG_SECRET = "s".repeat(43);
    private static final String H2_PASSWORD = "h2-strong-password";

    /** 与 application.yml 数值面一致的最小合法 props；只暴露本测试关心的三个维度。 */
    private static OpsPilotProperties props(String jwtSecret, boolean liveModel, String storageMode) {
        return new OpsPilotProperties(
                new OpsPilotProperties.DashScope("https://dashscope.aliyuncs.com",
                        liveModel ? "sk-real-key" : "", "text-embedding-v3", 1024,
                        "gte-rerank-v2", "qwen-plus", 60, 3000, 15000,
                        liveModel ? "live" : "mock"),
                new OpsPilotProperties.Storage(storageMode),
                new OpsPilotProperties.Es("http://localhost:9200", "opspilot-chunks-read", null, null, 3000, 10000),
                new OpsPilotProperties.Qdrant("localhost", 6334, "opspilot-vectors-live", "semantic-cache", null),
                new OpsPilotProperties.Jwt(jwtSecret, 86400),
                new OpsPilotProperties.Cache(2, 0.95),
                new OpsPilotProperties.Storm(30, 60),
                new OpsPilotProperties.Retrieval(50, 50, 60, 20, 3, 4500, 0.2),
                new OpsPilotProperties.Degrade(40, 3, 60));
    }

    private static MockEnvironment compliantEnv() {
        return new MockEnvironment()
                .withProperty("server.address", "127.0.0.1")
                .withProperty("spring.datasource.password", H2_PASSWORD);
    }

    private static void assertViolation(OpsPilotProperties props, OpsDeployProperties deploy,
                                        MockEnvironment env, String expectedEnvName) {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DeploymentGuard(props, deploy, env).afterPropertiesSet());
        assertTrue(e.getMessage().contains(expectedEnvName),
                "报错要点名 " + expectedEnvName + "（只说启动失败等于没修）: " + e.getMessage());
    }

    @Test
    void compliantDeployPasses() {
        assertDoesNotThrow(() -> new DeploymentGuard(
                props(STRONG_SECRET, true, "live"), new OpsDeployProperties(true, false),
                compliantEnv()).afterPropertiesSet());
    }

    @Test
    void placeholderJwtSecretIsRejected() {
        assertViolation(props("change-me-" + "x".repeat(40), true, "live"),
                new OpsDeployProperties(true, false), compliantEnv(), "JWT_SECRET");
    }

    @Test
    void shortJwtSecretIsRejected() {
        assertViolation(props("s".repeat(42), true, "live"),
                new OpsDeployProperties(true, false), compliantEnv(), "JWT_SECRET");
    }

    @Test
    void missingH2PasswordIsRejected() {
        MockEnvironment env = new MockEnvironment().withProperty("server.address", "127.0.0.1");
        assertViolation(props(STRONG_SECRET, true, "live"),
                new OpsDeployProperties(true, false), env, "H2_DB_PASSWORD");
    }

    @Test
    void mockModelIsRejected() {
        assertViolation(props(STRONG_SECRET, false, "live"),
                new OpsDeployProperties(true, false), compliantEnv(), "DASHSCOPE");
    }

    @Test
    void memoryStorageIsRejected() {
        assertViolation(props(STRONG_SECRET, true, "memory"),
                new OpsDeployProperties(true, false), compliantEnv(), "STORAGE_MODE");
    }

    @Test
    void wildcardBindIsRejectedUnlessExplicitlyAllowed() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("spring.datasource.password", H2_PASSWORD);
        assertViolation(props(STRONG_SECRET, true, "live"),
                new OpsDeployProperties(true, false), env, "SERVER_ADDRESS");
        assertViolation(props(STRONG_SECRET, true, "live"),
                new OpsDeployProperties(true, false),
                new MockEnvironment().withProperty("server.address", "::")
                        .withProperty("spring.datasource.password", H2_PASSWORD),
                "SERVER_ADDRESS");
    }

    @Test
    void wildcardBindPassesWhenOptedInForContainerOrProxy() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.address", "0.0.0.0")
                .withProperty("spring.datasource.password", H2_PASSWORD);
        assertDoesNotThrow(() -> new DeploymentGuard(
                props(STRONG_SECRET, true, "live"), new OpsDeployProperties(true, true), env)
                .afterPropertiesSet());
    }

    @Test
    void missingServerAddressFallsBackToSafeLoopback() {
        // server.address 缺省 → application.yml 的 127.0.0.1，属安全侧，不算违规
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.password", H2_PASSWORD);
        assertDoesNotThrow(() -> new DeploymentGuard(
                props(STRONG_SECRET, true, "live"), new OpsDeployProperties(true, false), env)
                .afterPropertiesSet());
    }

    @Test
    void nonStrictDemoPathIsNotBlocked() {
        // 演示形态（默认 false）：占位密钥 + mock 模型 + memory 存储 + 0.0.0.0 一律不拦——
        // 拦下等于让零依赖演示/单测路径断掉（回归锁）。
        MockEnvironment env = new MockEnvironment().withProperty("server.address", "0.0.0.0");
        assertDoesNotThrow(() -> new DeploymentGuard(
                props("change-me-demo", false, "memory"), new OpsDeployProperties(false, false), env)
                .afterPropertiesSet());
    }

    @Test
    void allViolationsAreCollectedInOneMessage() {
        // 一次列全：生产上按"改一个→重启→下一个"修配置是最慢的路径
        MockEnvironment env = new MockEnvironment().withProperty("server.address", "0.0.0.0");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new DeploymentGuard(props("", false, "memory"),
                        new OpsDeployProperties(true, false), env).afterPropertiesSet());
        String msg = e.getMessage();
        assertTrue(msg.contains("JWT_SECRET"), msg);
        assertTrue(msg.contains("H2_DB_PASSWORD"), msg);
        assertTrue(msg.contains("DASHSCOPE"), msg);
        assertTrue(msg.contains("STORAGE_MODE"), msg);
        assertTrue(msg.contains("SERVER_ADDRESS"), msg);
    }
}
