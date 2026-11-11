package com.opspilot.action;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AC-1 / N-7：配置绑定回归锁（**不改绑定，只加锁**）。
 *
 * <p>锁的形状：把**文档与红队使用的环境变量拼写**与**代码里真实的 {@code @Value} 键**钉在一起。
 * 键从 {@link ActionOpsService} 构造器参数上的 {@link Value} 注解**反射读出**，不在本文件硬抄
 * 字面值——硬抄就锁不住"有人改了 {@code @Value}"：改了代码、测试仍绿，等于没锁。
 *
 * <p><b>两条硬要求缺一不可</b>（漏了会得到"绑定坏了"的假结论，见契约 AC-1 的表）：
 * <ol>
 *   <li>属性源名字必须是 {@link StandardEnvironment#SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME}
 *       （即 {@code systemEnvironment}）——否则 Boot 不启用环境变量专用映射器；</li>
 *   <li>必须调用 {@link ConfigurationPropertySources#attach}——裸
 *       {@code SystemEnvironmentPropertySource} 只做点/连字符→下划线，**不剥连字符**；
 *       漏了这一步，{@code OPSPILOT_L1_ALLOWEDTARGETS} 与 {@code OPSPILOT_L1_ALLOWED_TARGETS}
 *       都会解析成 null。</li>
 * </ol>
 * 结论（Boot 4.1.2 实测）：两种拼写都能绑定到 {@code opspilot.l1.allowed-targets}。
 *
 * <p>刻意不用 {@code @SpringBootTest}：本锁与上下文装配无关，起容器只是变慢并引入无关耦合；
 * 裸 {@code ApplicationContextRunner} 也不加载 {@code application.yml}，同样不适用。
 */
class ActionL1BindingRegressionTest {

    /** 文档与红队使用的无下划线写法（这是本锁要钉住的"外部契约拼写"）。 */
    private static final String DOC_SPELLING = "OPSPILOT_L1_ALLOWEDTARGETS";

    /** 反射读出 ActionOpsService 构造器参数上 @Value 占位符里的键（形如 opspilot.l1.…）。 */
    private static String readAllowedTargetsValueKey() throws Exception {
        Constructor<?> ctor = ActionOpsService.class.getConstructors()[0];
        for (Parameter p : ctor.getParameters()) {
            Value v = p.getAnnotation(Value.class);
            if (v == null) continue;
            String expr = v.value();                       // 形如 ${opspilot.l1.allowed-targets:}
            int start = expr.indexOf("${");
            if (start < 0) continue;
            int end = expr.length();
            int colon = expr.indexOf(':', start);
            int brace = expr.indexOf('}', start);
            if (colon >= 0 && colon < brace) end = colon;
            else if (brace >= 0) end = brace;
            String key = expr.substring(start + 2, end).trim();
            if (key.startsWith("opspilot.l1.")) return key;   // 只认配置键，不认裸环境变量名
        }
        fail("@Value 注解里找不到 opspilot.l1.* 键——绑定契约已变，回归锁需要重新审视");
        return null;                                       // unreachable
    }

    /**
     * 构造真实解析路径：只含一个环境变量的 {@code systemEnvironment} 源 + attach。
     * 先移除默认的系统源，避免本机 shell 里恰好设了同名变量而污染正/负向断言。
     */
    private static StandardEnvironment envWithOnly(String name, String value) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(name, value)));
        ConfigurationPropertySources.attach(env);
        return env;
    }

    /**
     * 正极性：设了文档拼写 → 反射读出的真实键能解析出该值。
     * 若有人改了 {@code @Value} 键，或框架不再接受该拼写，本用例转红。
     */
    @Test
    void documentedSpellingBindsToTheRealValueKey() throws Exception {
        String key = readAllowedTargetsValueKey();
        assertTrue(key.startsWith("opspilot.l1."),
                "代码里的键应是规范配置名（而非裸环境变量名），实际=" + key);

        StandardEnvironment env = envWithOnly(DOC_SPELLING, "deployment/inventory-api");
        assertEquals("deployment/inventory-api", env.getProperty(key),
                "文档拼写 " + DOC_SPELLING + " 必须能解析到 @Value 的真实键 " + key
                        + "（systemEnvironment 源名 + ConfigurationPropertySources.attach 缺一不可）");
    }

    /**
     * 负向对照：一个不可能绑定的名字不得解析出该键——证明正极性不是"任何键都返回真"的空断言。
     */
    @Test
    void bogusSpellingDoesNotBind() throws Exception {
        String key = readAllowedTargetsValueKey();
        StandardEnvironment env = envWithOnly("OPSPILOT_L1_ALLOWED_TARGETS_BOGUS", "deployment/inventory-api");
        assertNull(env.getProperty(key),
                "不存在的环境变量名不得绑定 " + key + "——否则正极性用例是空断言");
    }

    /**
     * 等价性：连字符下划线写法（由真实键机械推导，不硬抄）同样解析到该值。
     * 与文档一致：两种写法等价，勿改成仅下划线形态。
     */
    @Test
    void underscoredSpellingIsEquivalent() throws Exception {
        String key = readAllowedTargetsValueKey();
        String underscored = key.toUpperCase(java.util.Locale.ROOT)
                .replace('.', '_').replace('-', '_');      // opspilot.l1.allowed-targets → OPSPILOT_L1_ALLOWED_TARGETS

        StandardEnvironment env = envWithOnly(underscored, "deployment/inventory-api");
        assertEquals("deployment/inventory-api", env.getProperty(key),
                "连字符下划线写法 " + underscored + " 与 " + DOC_SPELLING + " 应等价");
    }
}
