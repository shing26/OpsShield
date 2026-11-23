package com.opspilot.incident;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 显式注册 {@link IncidentSlaProperties}（ADR-0033）。
 *
 * <p>为什么不能只写 {@code @Component} + {@code @ConfigurationProperties}：record 只有构造器绑定
 * 一条路，而 Spring Boot 的构造器绑定只对 {@code @EnableConfigurationProperties} /
 * 配置扫描注册的类生效；把 record 当普通组件扫进去会被当成"按参数自动装配"，
 * 启动即因找不到 {@code Sla} bean 失败（实测）。显式注册让绑定面确定，缺配置在启动期就会报错。
 */
@Configuration
@EnableConfigurationProperties(IncidentSlaProperties.class)
class IncidentSlaConfiguration {
}
