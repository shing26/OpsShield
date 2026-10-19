package com.opspilot.config;

import com.opspilot.resilience.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * M1（生产就绪度 2026-09-12）：启动时打印生效配置摘要——**凭据一律脱敏**
 * （只说 set/absent 或长度，绝不落值；本类没有任何凭据字面量，凭据状态在拼接前
 * 先归约为脱敏字符串）。排障价值："现在到底跑的什么配置"不再靠猜
 * （live 首跑曾因报告硬编码 mock 元数据而暴露此需求，metrics 的 backend 自报是局部解，
 * 这里补启动时的全局面）。该摘要本身会被 H1 的 APP_FILE 持久化，成为启动快照存档。
 */
@Component
public class StartupConfigSummary implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigSummary.class);

    private final OpsPilotProperties props;
    private final QuotaService quota;
    private final Environment env;

    public StartupConfigSummary(OpsPilotProperties props, QuotaService quota, Environment env) {
        this.props = props;
        this.quota = quota;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        var ds = props.dashscope();
        boolean live = ds.live();
        // 脱敏归约先于任何字符串拼接：这些变量只含 set/absent(len=N) 状态，不含凭据值
        String apiKeyState = secretOf(ds.apiKey());
        String jwtSecretState = props.jwt() == null ? "absent" : secretOf(props.jwt().secret());
        String h2State = envSecretOf("H2_DB_PASSWORD");
        String esState = envSecretOf("ES_PASSWORD");
        String qdrantState = envSecretOf("QDRANT_API_KEY");
        String redisState = envSecretOf("REDIS_PASSWORD");

        log.info(String.join("\n",
                "=== 生效配置摘要（凭据脱敏，H1 持久化至 logs/app.log） ===",
                // storage 与 dashscope.mode 并列打印：两者共同决定"这次跑的是哪条链路"。
                // memory 模式额外点明"不承诺检索质量"——否则同一句 mode=mock 会让人以为
                // 后端与 live 只差一个 API key（ADR-0022 决策 5）。
                "storage=" + props.storage().mode()
                        + (props.storage().inMemory()
                                ? " backend=in-memory（ES/Qdrant/Redis 全部由内存实现承接，不承诺检索质量）"
                                : " backend=redis+es+qdrant"),
                "mode=" + (live ? "live" : "mock") + " backend=" + backendOf(live, ds),
                "dashscope.apiKey=" + apiKeyState + " jwt.secret=" + jwtSecretState,
                "h2.db=" + env.getProperty("spring.datasource.url", "?") + " h2.password=" + h2State,
                "es=" + esOf() + " es.password=" + esState,
                "qdrant=" + qdrantOf() + " qdrant.apiKey=" + qdrantState,
                "redis=" + env.getProperty("spring.data.redis.host", env.getProperty("spring.redis.host", "?"))
                        + " redis.password=" + redisState,
                "retrieval: legTimeout=" + props.retrieval().legTimeoutMs() + "ms minRelevance=" + props.retrieval().minRelevance(),
                "degrade: inflight=" + props.degrade().inflightThreshold() + " llmFail=" + props.degrade().llmFailureThreshold()
                        + " cooldown=" + props.degrade().llmOpenSeconds() + "s",
                "cache: l1Ttl=" + props.cache().l1TtlHours() + "h | quota=" + quota.dailyLimit() + "/day | jwt.ttl="
                        + props.jwt().ttlSeconds() + "s"));
    }

    private static String backendOf(boolean live, OpsPilotProperties.DashScope ds) {
        return live ? "dashscope:" + ds.embeddingModel() + "/" + ds.rerankModel() + "/" + ds.llmModel()
                : "mock-lexical-hash/mock-idf-coverage/mock-template";
    }

    /** 凭据脱敏：只披露"是否配置 + 长度"，绝不落值（Mimosa 约束与 OPS §2 纪律同源）。 */
    private static String secretOf(String secret) {
        return secret == null || secret.isBlank() ? "absent" : "set(len=" + secret.length() + ")";
    }

    private static String envSecretOf(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? "absent" : "set(len=" + v.length() + ")";
    }

    private String esOf() {
        var es = props.es();
        return es == null ? "?" : es.uri() + "/" + es.index();
    }

    private String qdrantOf() {
        var q = props.qdrant();
        return q == null ? "?" : q.host() + ":" + q.grpcPort() + "/" + q.collection();
    }
}
