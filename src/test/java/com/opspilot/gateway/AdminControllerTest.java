package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.auth.UserStore;
import com.opspilot.cache.L1CacheService;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.BackupStatusService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.health.HealthProbe;
import com.opspilot.ingest.IngestionRunner;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.resilience.DegradationState;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * QA 台账 2026-09-11 P0-2/P1-1 的回归锁：admin 面授权 = DB role=="platform" ∧ auth_level>=3。
 * 旧口径"level≥3 即管理员"让外来租户 L3（sre-acme）握有重建共享索引/清全局缓存的权力，
 * 且 metrics 完全无门禁——权限维度必须含 role（信任域），level 只是密级不是信任。
 * 6 端点 × 3 身份 = 18 格矩阵，只放行 platform 列。
 */
class AdminControllerTest {

    private static final UserContext PLATFORM =
            new UserContext("sre-full", "platform", 3, "tenant-internal");
    private static final UserContext CUSTOMER_L3 =
            new UserContext("sre-acme", "sre", 3, "tenant-acme");   // 同密级、外来租户
    private static final UserContext PLAIN_L1 =
            new UserContext("sre-limited", "sre", 1, "tenant-internal");

    private AdminController controller;
    private com.opspilot.metrics.AuditService audit;
    private UserStore users;
    private BackupStatusService backupStatus;

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tmp;

    @BeforeEach
    void setUp() {
        OpsMetrics metrics = mock(OpsMetrics.class);
        when(metrics.snapshot()).thenReturn(Map.of("total_requests", 1));
        DegradationState degrade = mock(DegradationState.class);
        when(degrade.current()).thenReturn(DegradationState.Level.L0);
        L1CacheService l1 = mock(L1CacheService.class);
        L2SemanticCacheService l2 = mock(L2SemanticCacheService.class);
        OpsPilotProperties props = new OpsPilotProperties(
                new OpsPilotProperties.DashScope(
                        "http://x", null, null, 0, null, null, 0, 3000, 15000, "mock"),
                null, null, null, null, null, null, null);
        IngestionRunner ingestion = mock(IngestionRunner.class);
        when(ingestion.reingestAsync()).thenReturn(true);
        users = mock(UserStore.class);
        when(users.backup(any())).thenReturn("backup/ok.zip");
        HealthProbe probe = mock(HealthProbe.class);
        when(probe.summary()).thenReturn(Map.of("status", "UP"));
        audit = mock(com.opspilot.metrics.AuditService.class);
        when(audit.recentSince(anyLong(), anyInt())).thenReturn(
                new com.opspilot.metrics.AuditService.RecentResult(List.of(), 0, false));
        // 备份状态在单测里用 mock：真实实现会读工作目录的 backup/.last-success.json，
        // 那会让 /state 断言依赖本机是否刚跑过备份（环境相关的假红/假绿）。
        backupStatus = mock(BackupStatusService.class);
        when(backupStatus.snapshot()).thenReturn(new BackupStatusService.Status(0L, -1.0, false));
        controller = new AdminController(metrics, degrade, l1, l2, props, ingestion, users, probe, audit,
                new com.opspilot.storm.SingleFlightRegistry(),
                mock(com.opspilot.resilience.QuotaService.class), backupStatus, null);
    }

    private static MockHttpServletRequest withUser(UserContext u) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (u != null) req.setAttribute(UserContext.REQUEST_ATTR, u);
        return req;
    }

    /** 端点调用表：路径 → 触发函数（request 注入身份）。Ops Console 两端点自动入矩阵。 */
    private Map<String, Function<MockHttpServletRequest, ?>> endpoints() {
        return Map.of(
                "POST /cache/flush", r -> controller.flushCache(r),
                "GET  /metrics",     r -> controller.metrics(r),
                "GET  /health",      r -> controller.health(r),
                "GET  /state",       r -> controller.state(r),
                "GET  /audit/recent", r -> controller.auditRecent(0, 50, r),
                "POST /degrade",     r -> controller.degrade(Map.of("level", "auto"), r),
                "POST /reingest",    r -> controller.reingest(r),
                "POST /backup",      r -> controller.backup(Map.of("to", "backup/x.zip"), r));
    }

    @Test
    void platformAdminPassesAllEndpoints() {
        endpoints().forEach((name, call) ->
                assertDoesNotThrow(() -> call.apply(withUser(PLATFORM)), name + " 平台管理员应放行"));
    }

    @Test
    void customerTenantL3ForbiddenOnAllEndpoints() {
        // P0-2 事故格：外来租户 L3 曾真实执行过 reingest/cache-flush
        endpoints().forEach((name, call) -> {
            var ex = assertThrows(ResponseStatusException.class,
                    () -> call.apply(withUser(CUSTOMER_L3)),
                    name + " 外来租户 L3 必须 403（level 高≠可信，缺 role 维度即 P0-2）");
            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode(), name);
        });
    }

    @Test
    void plainL1ForbiddenOnAllEndpoints() {
        endpoints().forEach((name, call) -> assertThrows(ResponseStatusException.class,
                () -> call.apply(withUser(PLAIN_L1)), name + " L1 必须 403"));
    }

    @Test
    void anonymousForbiddenOnAllEndpoints() {
        endpoints().forEach((name, call) -> assertThrows(ResponseStatusException.class,
                () -> call.apply(withUser(null)), name + " 无身份必须 403"));
    }

    /** 破坏性动作在 403 时绝不可触达执行面（reingest 被误触发的教训）。 */
    @Test
    void forbiddenRequestsNeverReachDestructiveCollaborators() {
        MockHttpServletRequest acme = withUser(CUSTOMER_L3);
        assertThrows(ResponseStatusException.class, () -> controller.reingest(acme));
        assertThrows(ResponseStatusException.class, () -> controller.flushCache(acme));
        assertThrows(ResponseStatusException.class, () -> controller.backup(Map.of("to", "backup/x.zip"), acme));
        verifyNoInteractions(users);
    }

    /** QA P1-2：被拒与成功都要留痕（ev=admin outcome=refused/ok）。 */
    @Test
    void adminActionsLeaveAuditTrail() {
        assertThrows(ResponseStatusException.class, () -> controller.reingest(withUser(CUSTOMER_L3)));
        verify(audit).logAdmin(eq(CUSTOMER_L3), eq("reingest"), eq("refused"), isNull());
        assertDoesNotThrow(() -> controller.reingest(withUser(PLATFORM)));
        verify(audit).logAdmin(eq(PLATFORM), eq("reingest"), eq("ok"), isNull());
    }

    /** Ops Console /state 形状契约（面板键源；改名演练由 CI 契约脚本兜底，这里锁 Java 侧装配）。 */
    @Test
    @SuppressWarnings("unchecked")
    void stateSnapshotCarriesPanelContractKeys() {
        var s = controller.state(withUser(PLATFORM));
        assertThatKeysPresent(s, "ts", "build", "metrics", "health", "runtime");
        var build = (Map<String, Object>) s.get("build");
        assertThatKeysPresent(build, "version", "time", "jvm", "uptime_s", "pid", "commit");
        assertEquals("dev", build.get("version"), "无 build-info 时回退 dev（CI test 阶段路径）");
        // G1-W1.4：commit 来自环境注入（OPSPILOT_BUILD_COMMIT / GIT_COMMIT），缺省 unknown
        assertEquals(AdminController.buildCommit(System.getenv()), build.get("commit"));
        var rt = (Map<String, Object>) s.get("runtime");
        assertThatKeysPresent(rt, "inflight", "sf_groups", "sf_keys_top", "degradation", "quota", "reingest", "backup");
        // 备份可信度与 gauge 同源：面板与告警算出的"备份龄"必须是同一个数
        var bk = (Map<String, Object>) rt.get("backup");
        assertThatKeysPresent(bk, "last_success", "age_hours", "verified");
        assertEquals(0L, bk.get("last_success"));
        assertEquals(-1.0, bk.get("age_hours"), "无记录用 -1 与『刚备份过』区分");
        assertEquals(Boolean.FALSE, bk.get("verified"));
        var deg = (Map<String, Object>) rt.get("degradation");
        // 两个阈值必须**成对**暴露：只给一个笼统的 threshold，会让外部把 L2 的阈值误当 L1 触发线
        assertThatKeysPresent(deg, "level", "manual", "failures", "cooldown_s",
                "llm_failure_threshold", "inflight_threshold");
        assertEquals(3, deg.get("llm_failure_threshold"), "LLM 熔断失败阈值（L2 触发）");
        assertEquals(40, deg.get("inflight_threshold"), "在途降级阈值（L1 触发）");
        // 复用 metrics 的装配源：backend 三元组必须在 metrics 子块内
        var mx = (Map<String, Object>) s.get("metrics");
        assertTrue(mx.containsKey("backend") && mx.containsKey("degradation_level"));
    }

    @Test
    void auditRecentProxiesCursorAndTruncated() {
        when(audit.recentSince(41, 50)).thenReturn(
                new com.opspilot.metrics.AuditService.RecentResult(
                        List.of(Map.of("seq", 42L, "ev", "chat")), 42, true));
        var r = controller.auditRecent(41, 50, withUser(PLATFORM));
        assertEquals(42L, r.get("max_seq"));
        assertEquals(Boolean.TRUE, r.get("truncated"));
        assertEquals(1, ((java.util.List<?>) r.get("events")).size());
    }

    /** G0：POST /admin/backup 的响应必须能自证产物（sha256 + verified），而不是只说"写好了"。 */
    @Test
    void backupResponseCarriesDigestAndVerification() throws Exception {
        java.nio.file.Path zip = tmp.resolve("users-test.zip");
        try (var out = java.nio.file.Files.newOutputStream(zip);
             var zos = new java.util.zip.ZipOutputStream(out)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("users.mv.db"));
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
        }
        when(users.backup(any())).thenReturn(zip.toString());

        var r = controller.backup(Map.of("to", "backup/users-test.zip"), withUser(PLATFORM));
        assertEquals(zip.toString(), r.get("backup"));
        String sha = (String) r.get("sha256");
        assertEquals(64, sha.length(), "sha256 是 32 字节的十六进制");
        assertEquals(BackupStatusService.sha256(zip), sha, "摘要必须与落盘字节一致");
        assertEquals(Boolean.TRUE, r.get("verified"));
    }

    /** 宿主看不到产物（容器卷未映射）时：不 500，但必须如实给 verified=false，不能假绿。 */
    @Test
    void backupResponseDoesNotLieWhenArtifactMissing() {
        when(users.backup(any())).thenReturn(tmp.resolve("missing.zip").toString());
        var r = controller.backup(Map.of("to", "backup/missing.zip"), withUser(PLATFORM));
        assertEquals("", r.get("sha256"));
        assertEquals(Boolean.FALSE, r.get("verified"));
    }

    /**
     * G1-W1.4：build commit 的来源优先级与缺省值。
     * 用 Map 直测纯函数而不是改环境变量：环境在测试进程里是全局且不可靠的，
     * 而"哪个键优先、空白怎么办"正是要锁住的判据。
     */
    @Test
    void buildCommitPrefersExplicitEnvAndDefaultsToUnknown() {
        assertEquals("unknown", AdminController.buildCommit(Map.of()));
        assertEquals("unknown", AdminController.buildCommit(Map.of("OPSPILOT_BUILD_COMMIT", "  ")),
                "空白等于没注入：面板要能区分没注入与注入了空值");
        assertEquals("abc123", AdminController.buildCommit(Map.of("GIT_COMMIT", "abc123")));
        assertEquals("explicit", AdminController.buildCommit(
                        Map.of("GIT_COMMIT", "ci", "OPSPILOT_BUILD_COMMIT", "explicit")),
                "两个都设时显式键优先");
        assertEquals(64, AdminController.buildCommit(Map.of("GIT_COMMIT", "x".repeat(80))).length(),
                "畸形长值截断，不让 /state 被撑大");
    }

    private static void assertThatKeysPresent(Map<String, Object> m, String... keys) {
        for (String k : keys) assertTrue(m.containsKey(k), "缺键 " + k);
    }

    /** QA P2-2：白名单拒绝是客户端错误（400 可操作文案），不再以 500 污染错误率 SLO。 */
    @Test
    void illegalBackupPathIsClientErrorNotServerBug() {
        when(users.backup(any())).thenThrow(new IllegalArgumentException("路径必须为 backup/ 目录下的合法 .zip: ../evil"));
        var ex = assertThrows(ResponseStatusException.class,
                () -> controller.backup(Map.of("to", "../evil.zip"), withUser(PLATFORM)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("backup/"), "文案应告知合法路径形状: " + ex.getReason());
        verify(audit).logAdmin(eq(PLATFORM), eq("backup"), eq("rejected"), anyString());
    }
}
