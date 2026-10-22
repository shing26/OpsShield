package com.opspilot.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * 轻量内联指标：风暴收敛与缓存命中的实证口径（A2-5 / A3-5 验收依据）。
 *
 * <p><b>单一事实源</b>（ADRs 0016）：计数由 Micrometer {@link Counter} 持有，一份数供两个出口——
 * {@link #snapshot()} 写给 {@code /state} 的 metrics 字段（控制台面板的取值源），以及 {@code aiops.*}
 * 层级名暴露在 {@code /actuator/prometheus} 供抓取。此前是裸 {@code AtomicLong}，只有前一个出口。
 * 两个出口的命名各自演化：snapshot 面向人读（snake_case 键），meter 面向时序库（层级名）。
 *
 * <p>面板契约把 snapshot 的键名锁死了（check_panel_contract.sh 逐字 grep {@code put("<key>"}，
 * 增删键必须同步 index.html 的磁贴）；Prometheus 侧不经过 snapshot，不受该契约约束。
 *
 * <p>G1-W1.4（2026-10-22）：补 SLO 直接信号——TTFT/请求耗时两个 {@link Timer} 与五个
 * 失败/拒绝计数器，全部 <b>Prometheus-only</b>（不进 snapshot）。理由有二：
 * ① 面板契约的键集已锁死，磁贴是"累计值 + 阈值比较"的形态，塞不下分位数；
 * ② 这些量的消费者是 SLO 记录规则（ops/alerting/rules/slo.yml），它读的是窗口内的
 * 比率与分位，本来就是抓取侧的活。**只提供 API，不改调用方**（接线归编排层，见 task-10 边界）。
 */
@Component
public class OpsMetrics {

    private final Counter llmCalls;
    private final Counter llmRateLimited;
    private final Counter dedupAggregated;
    private final Counter l1CacheHits;
    private final Counter l2CacheHits;
    private final Counter esOnlyRequests;
    private final Counter sopFallbacks;
    private final Counter retrievalTimeouts;
    private final Counter lowConfidenceRefusals;
    private final Counter totalRequests;
    // H3（生产就绪度 2026-09-12）：重试可观测性——retry=退避后二次尝试的次数；
    // networkError=终态网络类失败（429 终态仍走 llm_rate_limited，不重复计）
    private final Counter llmRetries;
    private final Counter llmNetworkErrors;
    // 生成质量包 Q2=C：逐字导出被出口护栏掩码的句数（按句累计，非按请求）
    private final Counter verbatimMasked;
    // ADRs 0017：只读行动契约里被命令策略判拒的条数（Prometheus 侧，**不进 snapshot**——
    // 面板契约的键集已锁死，见 check_panel_contract.sh 第二层；这个是抓取侧的可观测性)
    private final Counter actionCommandsRejected;

    // G1-W1.4：SLO 直接信号。timer 记分布（分位/直方图），counter 记"发生了多少次"。
    private final Timer ttft;
    private final Counter requestsAborted;
    private final Counter admissionRejected;
    private final Counter cacheWriteFailures;
    private final Counter rerankFailures;
    private final Counter sopMisses;

    /** 失败请求按异常类分桶（Prometheus-only）：失败路径不走任何 snapshot 键，故不进面板契约。 */
    private final MeterRegistry registry;

    public OpsMetrics(MeterRegistry registry) {
        this.registry = registry;
        // G0：预注册 reason=none 的 0 值基数，让 /actuator/prometheus 在"还没失败过"时也暴露
        // aiops_requests_failed_total——否则序列要等首次失败才出现，抓取侧的空值会被误读成
        // "指标没接上"，而生产门禁/告警规则也拿不到基线。
        registry.counter("aiops.requests.failed", "reason", "none");
        // G1-W1.4：SLO 信号注册。三条预注册理由与上面的 reason=none 一致——序列要等首次
        // 事件才出现的话，抓取侧会把"还没发生过"误读成"指标没接上"，而 SLO 规则正好在
        // "还没失败"时最需要一条 0 基线（否则空向量 vs 0 在 PromQL 里是两种不同的事实）。
        registry.timer("aiops.request.seconds", "outcome", "success");
        registry.timer("aiops.request.seconds", "outcome", "failure");
        // TTFT 是"用户感知快慢"的第一手信号：均值会被少数慢请求淹没，故同时给
        // P50/P95/P99 与直方图——直方图让 Prometheus 侧能算任意窗口分位，而不只这三个点。
        ttft = Timer.builder("aiops.ttft.seconds")
                .description("首 token 延迟（SSE 首个 delta 相对请求开始）")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
        requestsAborted = counter(registry, "aiops.requests.aborted",
                "客户端中途断开（SSE 取消）的请求数");
        admissionRejected = counter(registry, "aiops.admission.rejected",
                "准入被拒（配额/限流/降级门控）的请求数");
        cacheWriteFailures = counter(registry, "aiops.cache.write.fail",
                "缓存写入失败次数（读路径仍可用，但命中率会虚高，必须可观测）");
        rerankFailures = counter(registry, "aiops.rerank.fail",
                "精排失败、退回 RRF 原始序的次数");
        sopMisses = counter(registry, "aiops.sop.miss",
                "SOP 兜底未命中（无可用 SOP 可直出）的次数");
        totalRequests = counter(registry, "aiops.requests.total", "处理完成的请求总数");
        llmCalls = counter(registry, "aiops.llm.calls", "实际发起的 LLM 调用次数");
        llmRateLimited = counter(registry, "aiops.llm.rate_limited", "LLM 侧限流（429）命中次数");
        llmRetries = counter(registry, "aiops.llm.retries", "退避后二次尝试的次数");
        llmNetworkErrors = counter(registry, "aiops.llm.network_errors", "终态网络类失败次数");
        dedupAggregated = counter(registry, "aiops.dedup.aggregated", "被 SingleFlight 合并掉的重复请求数");
        l1CacheHits = counter(registry, "aiops.cache.l1.hits", "L1 精确缓存命中数（指纹级）");
        l2CacheHits = counter(registry, "aiops.cache.l2.hits", "L2 语义缓存命中数（向量相似）");
        esOnlyRequests = counter(registry, "aiops.es_only.requests", "L1 档位下仅走 ES 倒排的请求数");
        sopFallbacks = counter(registry, "aiops.sop.fallbacks", "L2 档位直出静态 SOP 的兜底次数");
        retrievalTimeouts = counter(registry, "aiops.retrieval.timeouts", "检索阶段超时次数");
        lowConfidenceRefusals = counter(registry, "aiops.guard.refusals", "置信度不足拒答次数");
        verbatimMasked = counter(registry, "aiops.guard.verbatim_masked",
                "VerbatimGuard 拦截次数：逐字导出中被出口护栏掩码的句数（按句累计）");
        actionCommandsRejected = counter(registry, "aiops.guard.action_commands_rejected",
                "只读行动契约里被命令策略判拒的命令条数（ADRs 0017：判拒即丢弃，不静默）");
        // 派生比率：无分母时给 0 而不是 NaN——NaN 写进抓取体会让部分 Prometheus 客户端解析失败，
        // 而"还没来过请求"恰是新实例最常见的状态
        gauge(registry, "aiops.storm.suppression.ratio", this, m -> m.ratio(m.dedupAggregated, m.totalRequests),
                "告警风暴抑制率 = 被合并请求数 / 总请求数");
        gauge(registry, "aiops.cache.hit.rate", this,
                m -> m.ratio(m.l1CacheHits.count() + m.l2CacheHits.count(), m.totalRequests),
                "双层缓存总命中率 = (L1+L2) / 总请求数");
        gauge(registry, "aiops.cache.l1.hit.rate", this, m -> m.ratio(m.l1CacheHits, m.totalRequests),
                "L1 精确缓存命中率（指纹级命中与总请求之比）");
        gauge(registry, "aiops.cache.l2.hit.rate", this, m -> m.ratio(m.l2CacheHits, m.totalRequests),
                "L2 语义缓存命中率（语义相似命中与总请求之比）");
    }

    public void llmCall() { llmCalls.increment(); }
    public void llmRateLimited() { llmRateLimited.increment(); }
    public void llmRetry() { llmRetries.increment(); }
    public void llmNetworkError() { llmNetworkErrors.increment(); }
    public void dedupAggregated() { dedupAggregated.increment(); }
    public void l1Hit() { l1CacheHits.increment(); }
    public void l2Hit() { l2CacheHits.increment(); }
    public void esOnly() { esOnlyRequests.increment(); }
    public void sopFallback() { sopFallbacks.increment(); }
    public void retrievalTimeout() { retrievalTimeouts.increment(); }
    public void lowConfidence() { lowConfidenceRefusals.increment(); }
    public void request() { totalRequests.increment(); }
    public void verbatimMasked(int n) { verbatimMasked.increment(n); }
    public void actionCommandRejected() { actionCommandsRejected.increment(); }

    /**
     * 失败请求计数（2026-10-22，G0）：与 {@code aiops.requests.total} 分开，才能算出
     * "进入编排但没成功"的比例；标签只取异常类名（有限词表），不取 message/query（高基数）。
     * 只进 Prometheus，不进 snapshot——面板契约的键集已锁死，且失败明细属于抓取侧。
     */
    public void requestFailed(String reason) {
        String tag = reason == null || reason.isBlank() ? "unknown" : reason;
        registry.counter("aiops.requests.failed", "reason", tag).increment();
    }

    // ---- G1-W1.4：SLO 信号写入面（只提供 API，接线归编排层）---------------------------

    /**
     * 首 token 延迟（毫秒）。负值按 0 记：时钟回拨或调用方算错时，负耗时不该污染分位数——
     * P99 一旦被负数拉低，SLO 就会在真实劣化时保持"健康"。
     */
    public void ttft(long ms) {
        ttft.record(Duration.ofMillis(Math.max(0, ms)));
    }

    /**
     * 编排请求总耗时（毫秒）+ 成败。按 outcome 分桶而不是只记一个序列：失败常常"瞬时返回"
     * （4xx/熔断/准入），混在一起会把总 P99 拉低、掩盖"慢失败"——SLO 要读的是成功请求的尾延迟。
     */
    public void requestDuration(long ms, boolean success) {
        registry.timer("aiops.request.seconds", "outcome", success ? "success" : "failure")
                .record(Duration.ofMillis(Math.max(0, ms)));
    }

    public void requestAborted() { requestsAborted.increment(); }

    public void admissionRejected() { admissionRejected.increment(); }

    public void cacheWriteFailed() { cacheWriteFailures.increment(); }

    public void rerankFailed() { rerankFailures.increment(); }

    public void sopMiss() { sopMisses.increment(); }

    public long llmCallsValue() { return count(llmCalls); }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total_requests", count(totalRequests));
        m.put("llm_calls", count(llmCalls));
        m.put("llm_rate_limited", count(llmRateLimited));
        m.put("llm_retries", count(llmRetries));
        m.put("llm_network_errors", count(llmNetworkErrors));
        m.put("dedup_aggregated", count(dedupAggregated));
        m.put("l1_cache_hits", count(l1CacheHits));
        m.put("l2_cache_hits", count(l2CacheHits));
        m.put("es_only_requests", count(esOnlyRequests));
        m.put("sop_fallbacks", count(sopFallbacks));
        m.put("retrieval_timeouts", count(retrievalTimeouts));
        m.put("low_confidence_refusals", count(lowConfidenceRefusals));
        m.put("verbatim_masked", count(verbatimMasked));
        return m;
    }

    /** 比率：无分母给 0，不给 NaN（NaN 写进抓取体会让部分 Prometheus 客户端解析失败）。 */
    private double ratio(Counter numerator, Counter denominator) {
        return ratio(numerator.count(), denominator);
    }

    private double ratio(double numerator, Counter denominator) {
        double den = denominator.count();
        return den <= 0 ? 0.0 : numerator / den;
    }

    private static long count(Counter c) { return (long) c.count(); }

    private static Counter counter(MeterRegistry registry, String name, String desc) {
        return Counter.builder(name).description(desc).register(registry);
    }

    private static <T> void gauge(MeterRegistry registry, String name, T obj, ToDoubleFunction<T> value,
            String desc) {
        Gauge.builder(name, obj, value).description(desc).register(registry);
    }
}
