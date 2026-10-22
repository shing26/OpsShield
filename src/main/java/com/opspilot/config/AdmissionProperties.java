package com.opspilot.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 准入控制参数（G1，2026-10-22）：LLM 是稀缺资源，入口必须有全局/租户两级硬上限。
 *
 * <p>为什么与 {@link OpsPilotProperties} 分开：准入是**部署面**参数（32/4 这类值随机器与上游配额变），
 * 而 OpsPilotProperties 是业务域配置；分开还能让 Controller/AdmissionGate 只依赖一个窄 record，
 * 不必在每个测试里构造 14 参数的巨型 record。
 *
 * <p>默认值依据：G0 的 48 并发唯一指纹实测在 3 秒内把全局降级打到 L2（项目自己的台账
 * production-readiness-2026-09-12.md:491-492），而 DashScope 的并发流上限通常按账号档位给；
 * 32 是"单机 + 一个账号"的保守起点，4 是单租户不应占满全局的默认份额。上线按真实配额调。
 */
@ConfigurationProperties("opspilot.admission")
public record AdmissionProperties(
        @DefaultValue("32") @Min(1) int globalPermits,
        @DefaultValue("4") @Min(1) int tenantPermits,
        @DefaultValue("1") @Min(1) int retryAfterSeconds) {}
