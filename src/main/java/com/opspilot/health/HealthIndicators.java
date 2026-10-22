package com.opspilot.health;

import com.opspilot.config.OpsDeployProperties;
import com.opspilot.config.OpsPilotProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 本项目自己实现的健康指示器（G1-W1.4，2026-10-22 运维审查）。
 *
 * <p>为什么不用 Boot 的现成实现：
 * <ul>
 *   <li><b>账号库</b>：Boot 的 {@code DataSourceHealthIndicator} 走"通用 DataSource 探测"，
 *       报的是"连接池能拿到连接"；这里要的是**这一条 SQL**（{@code SELECT 1}）——语义单一、
 *       失败摘要可读、行为可单测。故 application.yml 关掉 {@code management.health.db}，
 *       由本类接管 {@code db} 组件键（不关会与 Boot 的 {@code dbHealthContributor} 同名，
 *       两个 bean 映射到同一个键是**静默覆盖**，谁赢取决于注册顺序）。</li>
 *   <li><b>模型后端</b>：Boot 没有"dashscope 是 live 还是 mock"这种概念。本指示器**只读配置、
 *       不发任何请求**——健康探针绝不能变成对收费接口的探活：那既花钱、又给上游添压、
 *       还会在配额上留痕，而探针本该是零副作用的。</li>
 * </ul>
 *
 * <p>两者都自吞异常返回 DOWN：健康检查是"回答状态"的动作，绝不能把服务拖死或抛给调用方。
 */
public final class HealthIndicators {

    private HealthIndicators() {
    }

    /**
     * 账号库（H2）连通性：{@code SELECT 1} 只验"连接可用"。
     *
     * <p>为什么不顺带验 schema：建表由启动期 {@code spring.sql.init} 完成，真缺表时
     * {@code UserStore} 的第一条查询就会炸。健康面再查一遍 schema 只是把同一个事实读两次，
     * 还会让"库连不上"与"表结构不对"两种故障混进同一个 DOWN 里，值班分不清该重启还是该修数据。
     */
    public static HealthIndicator db(JdbcTemplate jdbc) {
        return () -> {
            try {
                Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
                if (one == null || one != 1) {
                    return Health.down().withDetail("detail", "SELECT 1 → " + one).build();
                }
                return Health.up().withDetail("detail", "SELECT 1 ok").build();
            } catch (Exception e) {
                return Health.down().withDetail("detail", brief(e)).build();
            }
        };
    }

    /**
     * 模型后端形态：live → UP；mock → UP + detail=mock；<b>strict 下 mock → DOWN</b>。
     *
     * <p>为什么 mock 平时算 UP：mock 是 ADR-0022 的零依赖形态，功能完整（词法向量 + 模板生成），
     * 它只是"不是神经语义"。把它报 DOWN 会让本机演示与 CI 形态永远红，而那种红会训练人忽略健康面。
     *
     * <p>为什么 strict 下必须 DOWN：strict 是"这次部署自称生产"的声明，而 mock 后端在生产
     * 等于答案质量不可信。启动守卫（DeploymentGuard）本应拦住这种组合，这里是**运行期兜底**：
     * 守卫只在启动期看一眼，指示器每次探活都在回答"此刻是什么形态"——配置被改坏、装配被绕开时
     * 仍要如实报 DOWN，不能拿"启动时检查过"当运行期的证据。
     */
    public static HealthIndicator backend(OpsPilotProperties props, OpsDeployProperties deploy) {
        return () -> {
            if (props.dashscope().live()) {
                return Health.up().withDetail("detail", "live").build();
            }
            if (deploy != null && deploy.strict()) {
                return Health.down()
                        .withDetail("detail", "mock（strict 形态要求真实模型：设 DASHSCOPE_API_KEY "
                                + "或 DASHSCOPE_MODE=live）")
                        .build();
            }
            return Health.up().withDetail("detail", "mock").build();
        };
    }

    private static String brief(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }
}
