package com.opspilot.auth;

import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.KvStore;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P2 登录面：bcrypt 校验、统一错误语义（防枚举）、失败限流 5/15min → 429。
 *
 * <p>2026-10-19（ADR-0022）：失败计数从 {@code RedissonClient} 的 RAtomicLong 换成
 * {@link KvStore} 接缝。断言语义一字未改——限流窗口仍是"首次失败起 15 分钟、不因持续失败续期"，
 * 这条语义现在由 {@code KvStore.increment} 的 {@code ttlOnCreate} 承载。
 */
class AuthServiceTest {

    private UserStore store;
    private KvStore kv;
    private AuthService auth;
    private String goodHash;
    private com.opspilot.metrics.AuditService audit;

    @BeforeEach
    void setUp() {
        OpsPilotProperties props = new OpsPilotProperties(
                null, null, null, new OpsPilotProperties.Jwt("unit-test-secret-at-least-32-bytes-long!!", 3600),
                null, null, null, null);
        store = mock(UserStore.class);
        kv = mock(KvStore.class);
        audit = mock(com.opspilot.metrics.AuditService.class);
        auth = new AuthService(store, new JwtService(props), kv, props, audit);
        goodHash = auth.hash("correct horse battery staple");
    }

    /** QA P1-2：登录失败（对外统一话术）在审计内部必须可辨 bad_credentials/locked。 */
    @Test
    void failedLoginLeavesAuditTrailWithDiscriminatingReason() {
        seedUser(false);
        when(kv.readLong(anyString())).thenReturn(0L);
        when(kv.increment(anyString(), any(Duration.class))).thenReturn(1L);
        assertThrows(AuthService.BadCredentialsException.class, () -> auth.login("alice", "wrong"));
        verify(audit).logAuthDenied("alice", "/api/v1/auth/login", "login_failed", "bad_credentials");

        when(kv.readLong(anyString())).thenReturn(5L);
        assertThrows(AuthService.LoginLockedException.class, () -> auth.login("alice", "whatever"));
        verify(audit).logAuthDenied("alice", "/api/v1/auth/login", "login_failed", "locked");
    }

    private void seedUser(boolean disabled) {
        when(store.credential("alice")).thenReturn(new UserStore.Credential(
                "alice", "tenant-internal", 3, "sre", disabled, 1, goodHash));
    }

    @Test
    void validCredentialsIssueToken() {
        seedUser(false);
        when(kv.readLong(anyString())).thenReturn(0L);
        String tok = auth.login("alice", "correct horse battery staple");
        assertTrue(tok.split("\\.").length == 3, "应为三段 JWT");
        verify(kv).delete("auth:fail:alice"); // 成功清零失败计数
    }

    @Test
    void unknownUserAndWrongPasswordShareSameError() {
        when(kv.readLong(anyString())).thenReturn(0L);
        when(kv.increment(anyString(), any(Duration.class))).thenReturn(1L);
        when(store.credential("ghost")).thenReturn(null);
        seedUser(false);
        // 语义一致 → 攻击者无法用响应差异枚举账号存在性
        assertThrows(AuthService.BadCredentialsException.class, () -> auth.login("ghost", "x"));
        assertThrows(AuthService.BadCredentialsException.class, () -> auth.login("alice", "wrong"));
    }

    @Test
    void disabledUserCannotLogin() {
        when(kv.readLong(anyString())).thenReturn(0L);
        when(kv.increment(anyString(), any(Duration.class))).thenReturn(1L);
        seedUser(true);
        assertThrows(AuthService.BadCredentialsException.class,
                () -> auth.login("alice", "correct horse battery staple"));
    }

    @Test
    void fiveFailuresLockLogin() {
        seedUser(false);
        when(kv.readLong(anyString())).thenReturn(5L);
        assertThrows(AuthService.LoginLockedException.class,
                () -> auth.login("alice", "correct horse battery staple"));
        // 锁定先于口令校验：正确口令也被拒
        verify(store, never()).credential(anyString());
    }
}
