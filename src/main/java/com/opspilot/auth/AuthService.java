package com.opspilot.auth;

import java.time.Duration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.store.KvStore;

/**
 * 登录与凭证签发（P2）：凭据校验走 H2 主库（UserStore），暴力防护走 Redis
 * （限流数据易失可容忍——丢了最坏是重新计数，不放行错误凭据）。
 * 统一错误语义（未知用户/错密码/disabled 同为无效凭据），防账号枚举。
 */
@Service
public class AuthService {

    public static class BadCredentialsException extends RuntimeException {
        public BadCredentialsException() { super("invalid username or password"); }
    }
    public static class LoginLockedException extends RuntimeException {
        public LoginLockedException() { super("too many failed attempts"); }
    }

    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final UserStore users;
    private final JwtService jwt;
    private final KvStore store;
    private final OpsPilotProperties props;
    private final com.opspilot.metrics.AuditService audit;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);

    public AuthService(UserStore users, JwtService jwt, KvStore store, OpsPilotProperties props,
                       com.opspilot.metrics.AuditService audit) {
        this.users = users;
        this.jwt = jwt;
        this.store = store;
        this.props = props;
        this.audit = audit;
    }

    /** 校验通过返回 24h JWT（携带签发时刻的 tver）；失败抛 Bad/Locked。失败必留痕（QA P1-2）。 */
    public String login(String sub, String password) {
        String failKey = "auth:fail:" + sub;
        if (store.readLong(failKey) >= MAX_FAILURES) {
            audit.logAuthDenied(sub, "/api/v1/auth/login", "login_failed", "locked");
            throw new LoginLockedException();
        }

        UserStore.Credential c = users.credential(sub);
        if (c == null || c.disabled() || password == null
                || !bcrypt.matches(password, c.passBcrypt())) {
            // TTL 只在首次失败时设（KvStore.increment 的 ttlOnCreate），窗口不会因持续失败被续期
            long n = store.increment(failKey, WINDOW);
            if (n >= MAX_FAILURES) {
                audit.logAuthDenied(sub, "/api/v1/auth/login", "login_failed", "locked");
                throw new LoginLockedException();
            }
            audit.logAuthDenied(sub, "/api/v1/auth/login", "login_failed", "bad_credentials");
            throw new BadCredentialsException(); // 不区分"无此人/错密码/已禁用"，防枚举（对外话术统一，审计内部可辨）
        }
        store.delete(failKey);
        return jwt.issue(c.sub(), c.role(), c.tenant(), c.authLevel(), c.tokenVer(),
                props.jwt().ttlSeconds());
    }

    public String hash(String rawPassword) {
        return bcrypt.encode(rawPassword);
    }
}
