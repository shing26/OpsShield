package com.opspilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.opspilot.config.AdmissionProperties;
import com.opspilot.config.OpsDeployProperties;
import com.opspilot.config.OpsPilotProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// G0-W0.1：部署安全开关（opspilot.deploy.*）与业务配置分开注册，便于 DeploymentGuard/JwtService 共用
// G1：准入参数（opspilot.admission.*）同样独立注册；@EnableScheduling 供 L2 过期清理使用
@EnableConfigurationProperties({OpsPilotProperties.class, OpsDeployProperties.class, AdmissionProperties.class})
@EnableScheduling
public class OpsPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpsPilotApplication.class, args);
    }
}
