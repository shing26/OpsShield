package com.opspilot.incident;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 显式注册通知配置（ADR-0037）。
 *
 * <p>与 {@link IncidentSlaConfiguration} 同因：record 的构造器绑定只对
 * {@code @EnableConfigurationProperties} 生效，靠组件扫描会被当成按参数自动装配。
 */
@Configuration
@EnableConfigurationProperties(IncidentNotificationProperties.class)
class IncidentNotificationConfiguration {
}
