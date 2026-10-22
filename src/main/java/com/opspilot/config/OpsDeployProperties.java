package com.opspilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 部署安全开关（G0-W0.1，2026-10-22 运维审查）。
 *
 * <p><b>为什么单独成组而不是塞进 {@link OpsPilotProperties}</b>：这组开关描述的是
 * <b>"这次部署处在什么形态"</b>（生产 / 本机演示），不是业务参数。它要被部署校验、JWT 签发、
 * 启动脚本共用，而 {@code OpsPilotProperties} 是九个子 record 的业务聚合——把部署形态混进去，
 * 会让"改一个阈值"和"改一次部署纪律"落在同一份配置对象上。
 *
 * <p>默认值必须向后兼容：两项都缺省 {@code false}，于是本机演示路径（scripts/run.sh、
 * start_gateway.sh 不带 OPSPILOT_STRICT）行为与加这个开关之前完全一致；生产由 .env 显式
 * {@code OPSPILOT_STRICT=true} 收紧。{@code @DefaultValue} 是必需的：整个
 * {@code opspilot.deploy.*} 缺省时 Spring 不会给 record 的 boolean 填默认值，而是直接
 * 绑定失败（与 {@code OpsPilotProperties} 的九参绑定同一个坑）。
 */
@ConfigurationProperties(prefix = "opspilot.deploy")
public record OpsDeployProperties(
        /** true=生产形态：启动期硬校验（JWT 密钥强度、H2 口令、真实模型、live 存储、监听地址）。 */
        @DefaultValue("false") boolean strict,
        /** strict 下允许对 0.0.0.0/:: 绑定（仅容器/反向代理形态需要；裸机生产应保持 false）。 */
        @DefaultValue("false") boolean allowPublicBind
) {
}
