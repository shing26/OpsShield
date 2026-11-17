package com.opspilot.domain;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * 领域选择的唯一入口（ADR-0031 D3）：配置期单值 {@code opspilot.domain} / {@code OPSPILOT_DOMAIN}，
 * 解析出唯一的 {@link DomainPack} 供 {@code IngestionRunner} / {@code MockIdfInitializer} /
 * {@code ReadOnlyActionExtractor} 构造器注入。
 *
 * <p><b>显式且 fail-closed</b>：只认空白（未配置 → sre）、{@code sre}、{@code dba} 三个值；
 * 未知 id、含 {@code /} 或 {@code ..}、含首尾空白、大写（如 {@code DBA}）一律
 * {@link IllegalStateException} 并点名 id。大小写敏感是刻意的——领域是配置期事实，不是
 * 可以"猜一下"的输入（与 {@code opspilot.storage.mode} 的大小写不敏感语义不同）。
 * 没有全局单例、没有 {@code current()}、没有"未初始化回落 SRE"：消费者拿不到 pack 就构造不出来。
 */
@Component
public final class DomainRegistry {

    private static final Set<String> KNOWN_IDS = Set.of("sre", "dba");

    private final DomainPack pack;

    public DomainRegistry(@Value("${opspilot.domain:}") String domain) {
        this.pack = DomainPack.load(resolve(domain));
    }

    /** 本次部署唯一的领域包；由本组件以 lite {@code @Bean} 暴露给 Spring 构造器注入消费者。 */
    @Bean
    public DomainPack pack() {
        return pack;
    }

    /** 配置期选域：空=默认 sre（不是"解析失败后回落"），其余必须精确匹配已知小写 id。 */
    static String resolve(String domain) {
        String selected = domain == null ? "" : domain;
        if (selected.isEmpty()) {
            return "sre";
        }
        if (selected.strip().length() != selected.length()
                || !KNOWN_IDS.contains(selected)
                || selected.contains("/") || selected.contains("\\") || selected.contains("..")) {
            throw new IllegalStateException(
                    "opspilot.domain 取值非法：" + selected
                            + "（只接受空/sre/dba；大小写敏感，不接受路径或空白）");
        }
        return selected;
    }
}
