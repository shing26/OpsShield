package com.opspilot.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import com.opspilot.config.OpsDeployProperties;
import com.opspilot.config.OpsPilotProperties;

/**
 * JWT 签发/验签的统一实现（P2）：登录服务与鉴权过滤器共用一个密钥出口，
 * 杜绝两处各自 build key 的漂移。HS256 对称密钥仅适用于单实例内部工具
 * （持密者可签发）——生产多实例/跨服务需换 RS256 非对称验签（记 ADR-0005 欠账）。
 *
 * <p><b>G0-W0.1（2026-10-22 审查）补齐两道密钥门</b>：
 * <ul>
 *   <li><b>blank 一律拒绝</b>：此前只判 null/blank 一种形态——这没问题，但错误信息必须点名
 *       {@code JWT_SECRET}，否则运维只知道"启动失败"却不知道去配哪个变量；</li>
 *   <li><b>strict 下拒绝占位/短密钥</b>：{@code change-me-*} 这类示例值照签不误，而 JWT 是
 *       <b>唯一</b>的鉴权凭据——占位密钥等于把签发权交给所有读过 .env.example 的人。
 *       非 strict（本机演示）保持旧行为不回归；length &lt; 32 字节时 JJWT 自己抛
 *       {@code WeakKeyException}，那是库的兜底，不是本类新增的门槛。</li>
 * </ul>
 * 判定口径与 {@code DeploymentGuard} 同源（那边是启动期汇总报全，这边是 bean 构造期单点报错），
 * 两处都点名 {@code JWT_SECRET}，避免"错误信息在哪个出口长什么样"再次分叉。
 */
@Component
// strict 形态把 DeploymentGuard 排在前面：它一次列全所有违规；否则本 bean 的构造期单点报错
// 会抢在汇总校验之前，运维只能"改一个→重启→看下一个"（与 DeploymentGuard 的设计承诺相反）。
@DependsOn("deploymentGuard")
public class JwtService {

    /** 与 {@code DeploymentGuard.MIN_JWT_SECRET_LENGTH} 同值：HS256 256-bit 的 base64 最短长度。 */
    public static final int MIN_SECRET_LENGTH = 43;

    /** 占位密钥标记，与 DeploymentGuard 同一份口径（识别 .env.example 的示例值形态）。 */
    private static final List<String> PLACEHOLDER_MARKERS =
            List.of("change-me", "changeme", "replace-me", "your-");

    private final SecretKey key;

    /**
     * Spring 注入用构造：strict 从 {@code opspilot.deploy.strict} 读取。
     *
     * <p>显式 {@code @Autowired}：本类另有一个单参便捷构造（给单测），Spring 无法自行判定
     * 该用哪个——不标注时多构造器的 bean 创建会失败。
     */
    @Autowired
    public JwtService(OpsPilotProperties props, OpsDeployProperties deploy) {
        this(props, deploy.strict());
    }

    /** 单测便捷构造：默认按非 strict 解析，行为与 G0 之前完全一致（演示路径不回归）。 */
    public JwtService(OpsPilotProperties props) {
        this(props, false);
    }

    private JwtService(OpsPilotProperties props, boolean strict) {
        String secret = props.jwt() == null ? null : props.jwt().secret();
        if (secret == null || secret.isBlank()) {
            // blank 在任何形态下都不接受：没有密钥就签不出可验证的 token，启动即失败胜过带病运行
            throw new IllegalStateException("JWT_SECRET 环境变量未设置（凭据只从环境读取，见 .env.example）");
        }
        if (strict) {
            rejectWeakSecret(secret);
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * strict 形态的密钥强度门：短密钥 / 占位值都在此拒绝，错误信息点名 JWT_SECRET。
     * 放在这里而不是只在 DeploymentGuard：JwtService 也可能被非 Spring 装配直接构造（CLI/单测）。
     */
    private static void rejectWeakSecret(String secret) {
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("JWT_SECRET 过短（当前 " + secret.length() + " 字符，"
                    + "OPSPILOT_STRICT=true 要求 ≥" + MIN_SECRET_LENGTH + " 字符；可用 openssl rand -base64 48 生成）");
        }
        String lower = secret.toLowerCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                throw new IllegalStateException("JWT_SECRET 仍是占位值（命中 \"" + marker + "\"），"
                        + "OPSPILOT_STRICT=true 拒绝弱密钥；请在 .env 中换成长随机串");
            }
        }
    }

    /** 签发短时凭证；tver=签发时的用户 token_ver，过滤器逐请求比对实现全局吊销。 */
    public String issue(String sub, String role, String tenant, int authLevel, int tokenVer, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder().subject(sub)
                .claim("role", role)
                .claim("tenant_id", tenant)
                .claim("auth_level", authLevel)
                .claim("tver", tokenVer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }
}
