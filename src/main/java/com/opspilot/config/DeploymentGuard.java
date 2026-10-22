package com.opspilot.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 部署期安全校验（G0-W0.1，2026-10-22 运维审查）：{@code OPSPILOT_STRICT=true} 时，
 * 任何一条"安全默认没到位"都必须在<b>启动期</b>失败，并且报错要<b>点名环境变量</b>。
 *
 * <p>为什么必须放在启动期：本仓反复吃过同一类亏——配置写错不会报错，只会"看起来启动了、
 * 实际走错"（JWT 占位密钥照样能签发、H2 空口令照样建库、mock 模型照样出答案）。等到上线后
 * 才发现，代价是一条已经对外服务的路径要回滚重来。启动失败是最便宜的失败。
 *
 * <p>为什么非 strict 不拦：本机演示/单测路径长期依赖 {@code change-me} 占位值与 mock 模型，
 * 把它们也拦下等于让"零依赖演示"这条腿断掉。所以 strict 是<b>生产形态的显式声明</b>，
 * 不配就是演示；配了就必须真达标（不做"配了 strict 但只警告"的半吊子）。
 *
 * <p>校验清单与理由：
 * <ul>
 *   <li>{@code JWT_SECRET}：≥43 字符（HS256 256-bit 密钥的 base64 最短形态）且不含占位标记。
 *       弱密钥可被离线暴力破解，伪造 token 即绕过全部鉴权；</li>
 *   <li>{@code H2_DB_PASSWORD}：非空。空口令 = 任何能读数据目录的进程都能改账号/口令；</li>
 *   <li>{@code DASHSCOPE_API_KEY} / {@code DASHSCOPE_MODE}：必须真实 live，strict 部署不得
 *       退回 mock（mock 输出是词法近似，会被当成真实排障结论消费）；</li>
 *   <li>{@code STORAGE_MODE}：必须 live。memory 模式不承诺检索质量，只适用于演示；</li>
 *   <li>{@code SERVER_ADDRESS}：非 0.0.0.0/::，除非显式 {@code OPSPILOT_ALLOW_PUBLIC_BIND=true}。
 *       裸机对外监听会把面板/接口直接暴露在网络上（鉴权是 JWT，不是网络边界）。</li>
 * </ul>
 *
 * <p>所有违规<b>一次性收集后合并抛出</b>：生产上换完一版配置才发现下一个错，是最低效的修法。
 */
@Component
public class DeploymentGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DeploymentGuard.class);

    /** HS256 要求 256-bit 密钥；43 是 32 字节 base64 编码后的最短长度（无填充）。 */
    public static final int MIN_JWT_SECRET_LENGTH = 43;

    /** 占位密钥的识别标记：.env.example / 历史文档里出现过的示例值形态。 */
    private static final List<String> PLACEHOLDER_MARKERS =
            List.of("change-me", "changeme", "replace-me", "your-");

    private final OpsPilotProperties props;
    private final OpsDeployProperties deploy;
    private final Environment env;

    public DeploymentGuard(OpsPilotProperties props, OpsDeployProperties deploy, Environment env) {
        this.props = props;
        this.deploy = deploy;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        if (!deploy.strict()) {
            // 演示/开发形态：不拦启动。但要留下一条可搜索的痕迹——"没开 strict"必须是显式事实，
            // 而不是被默认值悄悄带走。
            log.info("opspilot.deploy.strict=false：跳过部署期安全校验（仅限本机演示/开发；"
                    + "生产部署须设 OPSPILOT_STRICT=true）");
            return;
        }

        List<String> violations = new ArrayList<>();
        checkJwtSecret(violations);
        checkH2Password(violations);
        checkLiveModel(violations);
        checkLiveStorage(violations);
        checkListenAddress(violations);

        if (!violations.isEmpty()) {
            // 一次列全：生产上按"改一个→重启→下一个"的节奏修配置是最慢的路径。
            throw new IllegalStateException("OPSPILOT_STRICT=true 部署校验失败，拒绝启动（共 "
                    + violations.size() + " 项）：\n  - " + String.join("\n  - ", violations)
                    + "\n修法：在 .env 中补齐上述变量后重启；仅本机演示可显式设 OPSPILOT_STRICT=false。");
        }
        log.info("OPSPILOT_STRICT=true 部署校验通过：JWT 密钥强度 / H2 口令 / live 模型 / live 存储 / 监听地址均达标");
    }

    /** JWT 密钥：强长度 + 非占位。占位值与弱密钥是"能签发一切"的单点后门。 */
    private void checkJwtSecret(List<String> violations) {
        String secret = props.jwt() == null ? null : props.jwt().secret();
        if (secret == null || secret.isBlank()) {
            violations.add("JWT_SECRET 未设置（凭据只从环境读取，见 .env.example）");
            return;
        }
        if (secret.length() < MIN_JWT_SECRET_LENGTH) {
            violations.add("JWT_SECRET 过短（当前 " + secret.length() + " 字符，strict 要求 ≥"
                    + MIN_JWT_SECRET_LENGTH + " 字符；可用 openssl rand -base64 48 生成）");
        }
        String lower = secret.toLowerCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (lower.contains(marker)) {
                violations.add("JWT_SECRET 仍是占位值（命中 \"" + marker + "\"），strict 部署拒绝弱密钥");
                return;   // 同一变量只报一条，避免刷屏
            }
        }
    }

    /** H2 主库口令：空口令等于把账号主库交给有文件读权限的任何人。 */
    private void checkH2Password(List<String> violations) {
        String password = env.getProperty("spring.datasource.password", "");
        if (password == null || password.isBlank()) {
            violations.add("H2_DB_PASSWORD 未设置（spring.datasource.password 为空；用户主库不允许空口令）");
        }
    }

    /** 模型：strict 必须接真实 DashScope，不得靠 auto 回落 mock 出"看起来像答案"的近似文本。 */
    private void checkLiveModel(List<String> violations) {
        if (props.dashscope() == null || !props.dashscope().live()) {
            violations.add("DASHSCOPE_API_KEY 未配置或 DASHSCOPE_MODE=mock（strict 部署必须走真实模型）");
        }
    }

    /** 存储：memory 模式不承诺检索质量（ADR-0022），只能用于演示。 */
    private void checkLiveStorage(List<String> violations) {
        if (props.storage().inMemory()) {
            violations.add("STORAGE_MODE=memory（strict 部署必须为 live：ES/Qdrant/Redis 真实中间件）");
        }
    }

    /**
     * 监听地址：0.0.0.0/:: = 对所有网卡开放。裸机生产应只绑环回、由反代终结 TLS；
     * 容器形态（compose 端口映射）确需对外绑定时，显式 {@code OPSPILOT_ALLOW_PUBLIC_BIND=true} 才能放行。
     */
    private void checkListenAddress(List<String> violations) {
        String address = env.getProperty("server.address", "127.0.0.1");
        if (address == null || address.isBlank()) {
            return;   // 缺省即 application.yml 的 127.0.0.1，安全侧
        }
        String a = address.trim();
        boolean wildcard = "0.0.0.0".equals(a) || "::".equals(a) || "[::]".equals(a)
                || "0:0:0:0:0:0:0:0".equals(a);
        if (wildcard && !deploy.allowPublicBind()) {
            violations.add("SERVER_ADDRESS=" + a + " 对所有网卡开放，但 OPSPILOT_ALLOW_PUBLIC_BIND=false"
                    + "（裸机生产应绑 127.0.0.1；容器/反代形态才显式置 allow-public-bind=true）");
        }
    }
}
