package com.opspilot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * opspilot.* 配置聚合，全部不可变 record。
 *
 * <p>P2（2026-09-19 外部评审核实采纳）：非法值必须在<b>启动期拒绝</b>，不得静默生效。
 * 此前全 record 零校验，实测锐边：{@code inflight-threshold=-1} 会让降级状态机的
 * {@code inflight >= threshold} 恒真 = <b>永久 L1（向量腿永远被摘）且无任何告警</b>；
 * {@code llm-timeout-seconds=0} 等于每次调用立即超时→熔断常开。约束只挡"会让系统静默走错"
 * 的值；边界值（如 min-relevance=0 表示关闭拒答门控）是合法语义，不挡。
 * 验收：{@code OpsPilotPropertiesValidationTest}（ApplicationContextRunner 断言启动失败
 * 且报错含字段名；正例用全量合法值）＋ 活体启动复验（打包 jar 带
 * {@code --opspilot.degrade.inflight-threshold=-1} → exit=1 且报错点名该字段；
 * 真实 yml 合法值启动零校验告警、推进到 Tomcat/H2 才因本机未起 Redis 中止）。
 *
 * <p>B3（2026-09-21 补）：{@code connect-timeout-ms} / {@code read-timeout-ms} 是
 * <b>socket 层的最后一道</b>。此前只有 LLM 一路有显式超时（{@code LlmClient} 自己
 * connect 5s + 请求级 {@code llm-timeout-seconds}），而精排、embedding、ES 三路全靠
 * 底层客户端默认值——那些默认值多数是"无限等"，一个卡住的连接会一直占着线程。
 * <p>取值口径：<b>socket 超时必须比业务级预算松</b>。检索腿的业务预算是
 * {@code retrieval.leg-timeout-ms}（4500ms，取 embedding 观测 P99 3.7s + 余量），
 * 若 socket 读超时也压到同一量级，两者会赛跑，最后抛出的是原始 socket 异常而不是
 * 业务降级——降级状态机就收不到它该收的信号。所以这里是"兜底"不是"SLO"。
 */
@Validated
@ConfigurationProperties(prefix = "opspilot")
public record OpsPilotProperties(
        @Valid DashScope dashscope,
        @Valid Storage storage,
        @Valid Es es,
        @Valid Qdrant qdrant,
        @Valid Jwt jwt,
        @Valid Cache cache,
        @Valid Storm storm,
        @Valid Retrieval retrieval,
        @Valid Degrade degrade
) {

    /**
     * 规范构造（九参，含 {@code storage}）上显式标注 {@code @ConstructorBinding}。
     *
     * <p>为什么必须显式：本 record 另有一个八参便捷构造（见下），于是 Spring 无法自行判定
     * 该用哪个——实测表现为启动期 {@code No default constructor found}，
     * 连带 {@code OpsPilotPropertiesValidationTest} 全红。标注之后绑定面是确定的：
     * 配置绑定只走九参规范构造，八参那个纯给单测用。
     */
    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public OpsPilotProperties {
    }

    /**
     * 八参便捷构造：**存储模式缺省为 live**。
     *
     * <p>为什么留这个口：本 record 被大量单测按位置构造，而"这次跑的是哪个存储后端"在那些用例里
     * 从不关心（它们只验领域逻辑）。让它们全部补一个 {@code new Storage("live")} 参数，
     * 只会把一次装配变更摊成几十处与断言无关的噪音。缺省值与 {@code application.yml} 的
     * {@code ${STORAGE_MODE:live}} 和装配侧 {@code matchIfMissing=true} 三处一致——
     * 这三个"默认 live"必须同口径，否则"没配就是 live"会在不同路径上给出不同答案。
     */
    public OpsPilotProperties(DashScope dashscope, Es es, Qdrant qdrant, Jwt jwt, Cache cache,
                              Storm storm, Retrieval retrieval, Degrade degrade) {
        this(dashscope, new Storage("live"), es, qdrant, jwt, cache, storm, retrieval, degrade);
    }

    /**
     * 存储后端开关（ADR-0022）：{@code live}=真中间件（ES/Qdrant/Redis，缺则启动失败），
     * {@code memory}=内存实现，零外部依赖。
     *
     * <p><b>为什么不是 {@code auto}</b>：key 的有无是**配置期事实**（一次定死），中间件可达性是
     * **运行期事实**（会抖）。把 auto 套到会抖的信号上，会出现"同一台机器、同一份代码、两次启动
     * 跑在不同后端"，而页面与审计里的数字长得一模一样——这正是本仓库反复在防的那类假绿。
     *
     * <p>写错值在**启动期**拒绝（与 {@code dashscope.mode} 同范式）：静默按 live 处理会让
     * "我以为跑的是内存模式"变成一条查不出来的错觉。
     */
    public record Storage(
            @Pattern(regexp = "live|memory", flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "storage.mode 只接受 live|memory（不区分大小写）")
            String mode) {
        /** 空值归一到 live：让 {@code new Storage(null)} 与"没配"表现一致。 */
        public Storage {
            if (mode == null || mode.isBlank()) mode = "live";
        }

        public boolean inMemory() {
            return "memory".equalsIgnoreCase(mode);
        }
    }

    /**
     * {@code storage} 的**空值兜底**：整个 {@code opspilot.storage.*} 缺失时，Spring 会把嵌套
     * record 绑成 {@code null}（不是绑成"字段全空"的实例），于是任何 {@code props.storage().inMemory()}
     * 都会 NPE。
     *
     * <p>这不是理论风险——2026-10-19 落地时被 {@code storageDefaultsToLiveWhenUnset} 当场抓到。
     * 修法选在访问器而不是每个调用点：默认值只该有一处，否则"没配就是 live"这件事会随着调用点
     * 增加而漏掉。与 {@code application.yml} 的 {@code ${STORAGE_MODE:live}}、装配侧
     * {@code matchIfMissing=true} 三处同口径。
     */
    @Override
    public Storage storage() {
        return storage == null ? new Storage("live") : storage;
    }

    public record DashScope(String baseUrl, String apiKey, String embeddingModel,
                            @Min(1) int embeddingDim, String rerankModel, String llmModel,
                            @Min(1) int llmTimeoutSeconds,
                            @Min(1) int connectTimeoutMs, @Min(1) int readTimeoutMs,
                            @Pattern(regexp = "auto|live|mock", flags = Pattern.Flag.CASE_INSENSITIVE,
                                    message = "dashscope.mode 只接受 auto|live|mock（不区分大小写）；" +
                                            "写错会静默退回按 key 判定，故启动期拒绝")
                            String mode) {
        /** auto: 有 Key 走 live，否则 mock（机制验证用，词法向量非神经语义）。 */
        public boolean live() {
            if ("live".equalsIgnoreCase(mode)) return true;
            if ("mock".equalsIgnoreCase(mode)) return false;
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Es(String uri, String index, String username, String password,
                     @Min(1) int connectTimeoutMs, @Min(1) int readTimeoutMs) {}

    public record Qdrant(String host, @Min(1) @Max(65535) int grpcPort, String collection,
                         String cacheCollection, String apiKey) {}

    public record Jwt(String secret, @Min(1) long ttlSeconds) {}

    public record Cache(@Min(1) int l1TtlHours, @Min(0) @Max(1) double l2Threshold) {}

    public record Storm(@Min(1) int windowSeconds, @Min(1) int alertWindowSeconds) {}

    public record Retrieval(@Min(1) int esTopK, @Min(1) int qdrantTopK, @Min(1) int rrfK,
                            @Min(1) int rerankTopK, @Min(1) int finalTopK,
                            @Min(1) int legTimeoutMs, @Min(0) @Max(1) double minRelevance) {}

    public record Degrade(@Min(1) int inflightThreshold, @Min(1) int llmFailureThreshold,
                          @Min(1) int llmOpenSeconds) {}
}
