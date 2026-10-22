package com.opspilot.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 指标单一事实源（ADRs 0016）：计数同时写给控制台面板的 snapshot 与 Prometheus 抓取面，
 * 锁的是"两份出口永远一致"与"新实例状态可解析"两件事。
 *
 * 为什么锁：
 * ① snapshot 的键集是**面板契约的锁定面**（check_panel_contract.sh 逐字 grep put("<key>" 与
 *    index.html 磁贴对齐）——这里把它钉成单测，改键的人先看到红，而不是 CI 跑到 shell 那层才看到；
 * ② 派生比率在"零请求"时必须是 0 而不能是 NaN：NaN 写进 /actuator/prometheus 抓取体会让部分
 *    客户端解析失败，而空负载恰是新实例被 prometheus 首次抓取时的真实状态。
 */
class OpsMetricsTest {

    private OpsMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        // 每个用例一个独立 registry：计数器/gauge 按名字注册，共享 registry 会让用例间互相污染
        registry = new SimpleMeterRegistry();
        metrics = new OpsMetrics(registry);
    }

    @Test
    void snapshotKeysAreThePanelContractSurface() {
        Map<String, Object> snap = metrics.snapshot();
        assertEquals(Set.of("total_requests", "llm_calls", "llm_rate_limited", "llm_retries",
                "llm_network_errors", "dedup_aggregated", "l1_cache_hits", "l2_cache_hits",
                "es_only_requests", "sop_fallbacks", "retrieval_timeouts", "low_confidence_refusals",
                "verbatim_masked"), snap.keySet(),
                "snapshot 键集是面板契约面：增删键必须同步 index.html 磁贴");
        snap.forEach((k, v) -> assertInstanceOf(Long.class, v,
                "面板对指标做整数累加与阈值比较，值类型必须是 Long（Counter.count() 是 double，须收敛）"));
    }

    @Test
    void oneIncrementLandsInBothExports() {
        metrics.request();
        metrics.llmCall();
        metrics.llmRateLimited();
        metrics.llmRetry();
        metrics.llmNetworkError();
        metrics.dedupAggregated();
        metrics.l1Hit();
        metrics.l2Hit();
        metrics.esOnly();
        metrics.sopFallback();
        metrics.retrievalTimeout();
        metrics.lowConfidence();
        metrics.verbatimMasked(3);

        Map<String, Object> snap = metrics.snapshot();
        assertEquals(1L, snap.get("total_requests"));
        assertEquals(1L, snap.get("llm_calls"));
        assertEquals(1L, snap.get("llm_rate_limited"));
        assertEquals(1L, snap.get("llm_retries"));
        assertEquals(1L, snap.get("llm_network_errors"));
        assertEquals(1L, snap.get("dedup_aggregated"));
        assertEquals(1L, snap.get("l1_cache_hits"));
        assertEquals(1L, snap.get("l2_cache_hits"));
        assertEquals(1L, snap.get("es_only_requests"));
        assertEquals(1L, snap.get("sop_fallbacks"));
        assertEquals(1L, snap.get("retrieval_timeouts"));
        assertEquals(1L, snap.get("low_confidence_refusals"));
        assertEquals(3L, snap.get("verbatim_masked"), "verbatimMasked(n) 按句累计，一次加 n 而非 1");

        assertEquals(1.0, registry.get("aiops.requests.total").counter().count());
        assertEquals(1.0, registry.get("aiops.llm.calls").counter().count());
        assertEquals(1.0, registry.get("aiops.llm.rate_limited").counter().count());
        assertEquals(1.0, registry.get("aiops.llm.retries").counter().count());
        assertEquals(1.0, registry.get("aiops.llm.network_errors").counter().count());
        assertEquals(1.0, registry.get("aiops.dedup.aggregated").counter().count());
        assertEquals(1.0, registry.get("aiops.cache.l1.hits").counter().count());
        assertEquals(1.0, registry.get("aiops.cache.l2.hits").counter().count());
        assertEquals(1.0, registry.get("aiops.es_only.requests").counter().count());
        assertEquals(1.0, registry.get("aiops.sop.fallbacks").counter().count());
        assertEquals(1.0, registry.get("aiops.retrieval.timeouts").counter().count());
        assertEquals(1.0, registry.get("aiops.guard.refusals").counter().count());
        assertEquals(3.0, registry.get("aiops.guard.verbatim_masked").counter().count());
    }

    @Test
    void ratiosAreZeroBeforeAnyRequest() {
        assertEquals(0.0, registry.get("aiops.storm.suppression.ratio").gauge().value());
        assertEquals(0.0, registry.get("aiops.cache.hit.rate").gauge().value());
        assertEquals(0.0, registry.get("aiops.cache.l1.hit.rate").gauge().value());
        assertEquals(0.0, registry.get("aiops.cache.l2.hit.rate").gauge().value());
    }

    @Test
    void suppressionRatioIsDedupShareOfAllRequests() {
        for (int i = 0; i < 10; i++) {
            metrics.request();
        }
        for (int i = 0; i < 4; i++) {
            metrics.dedupAggregated();
        }
        assertEquals(0.4, registry.get("aiops.storm.suppression.ratio").gauge().value(), 1e-9,
                "抑制率 = 被合并请求数 / 总请求数（未合并的请求也计入分母）");
    }

    @Test
    void cacheHitRatesSplitL1FromL2() {
        for (int i = 0; i < 10; i++) {
            metrics.request();
        }
        for (int i = 0; i < 3; i++) {
            metrics.l1Hit();
        }
        for (int i = 0; i < 2; i++) {
            metrics.l2Hit();
        }
        assertEquals(0.5, registry.get("aiops.cache.hit.rate").gauge().value(), 1e-9,
                "总命中率 = (L1+L2) / 总请求数");
        assertEquals(0.3, registry.get("aiops.cache.l1.hit.rate").gauge().value(), 1e-9,
                "L1 是精确缓存：指纹级命中，与 L2 语义命中分开报");
        assertEquals(0.2, registry.get("aiops.cache.l2.hit.rate").gauge().value(), 1e-9);
    }

    /**
     * G0：失败请求按异常类分桶进 Prometheus，但**不进 snapshot**（面板契约键集已锁死）。
     * 标签 reason 是有限词表（异常类名）；message/query 等高基数字段绝不能进 label。
     */
    @Test
    void failedRequestsAreTaggedAndStayOutOfPanelSnapshot() {
        metrics.requestFailed("RuntimeException");
        metrics.requestFailed("RuntimeException");
        metrics.requestFailed(null);

        assertEquals(2.0, registry.get("aiops.requests.failed")
                .tag("reason", "RuntimeException").counter().count());
        assertEquals(1.0, registry.get("aiops.requests.failed")
                .tag("reason", "unknown").counter().count(), "null reason 归一 unknown，不允许空标签");
        assertFalse(metrics.snapshot().containsKey("requests_failed"),
                "失败计数只进 Prometheus；进 snapshot 会打破面板契约");
    }

    /**
     * G1-W1.4：SLO 信号（TTFT/请求耗时 Timer + 五个失败/拒绝计数）只进 Prometheus，不进 snapshot。
     * 锁四件事：新 meter 真的注册；TTFT 带 P50/P95/P99 与直方图；成败分桶；负耗时被夹到 0。
     */
    @Test
    void sloMetersArePrometheusOnly() {
        metrics.ttft(120);
        metrics.ttft(300);
        metrics.ttft(-5);
        metrics.requestDuration(50, true);
        metrics.requestDuration(20, false);
        metrics.requestAborted();
        metrics.admissionRejected();
        metrics.cacheWriteFailed();
        metrics.rerankFailed();
        metrics.sopMiss();

        var ttft = registry.get("aiops.ttft.seconds").timer();
        assertEquals(3, ttft.count(), "三次 ttft 都要落进同一个序列");
        assertEquals(420.0, ttft.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 1e-6,
                "负耗时夹到 0：否则 P99 会被时钟回拨/算错的负值拉低");
        assertEquals(3, ttft.takeSnapshot().percentileValues().length, "P50/P95/P99 必须可读");
        // 直方图桶由 registry 提供（SimpleMeterRegistry 不产 percentile histogram），
        // 故"抓取体里真的出现 bucket"单独用 PrometheusMeterRegistry 断言（见下一条用例）。

        assertEquals(1, registry.get("aiops.request.seconds").tag("outcome", "success").timer().count());
        assertEquals(1, registry.get("aiops.request.seconds").tag("outcome", "failure").timer().count(),
                "成败必须分桶：失败常瞬时返回，混在一起会把 P99 拉低、掩盖慢失败");
        assertEquals(1.0, registry.get("aiops.requests.aborted").counter().count());
        assertEquals(1.0, registry.get("aiops.admission.rejected").counter().count());
        assertEquals(1.0, registry.get("aiops.cache.write.fail").counter().count());
        assertEquals(1.0, registry.get("aiops.rerank.fail").counter().count());
        assertEquals(1.0, registry.get("aiops.sop.miss").counter().count());

        for (String key : Set.of("ttft", "request_seconds", "requests_aborted", "admission_rejected",
                "cache_write_fail", "rerank_fail", "sop_miss")) {
            assertFalse(metrics.snapshot().containsKey(key),
                    "SLO 信号只进 Prometheus；进 snapshot 会打破面板契约（键 " + key + "）");
        }
    }

    /**
     * 分位/直方图/计数器名必须出现在 **Prometheus 抓取体**里——那才是它们唯一的消费者。
     * 用真 PrometheusMeterRegistry 而不是 SimpleMeterRegistry：直方图桶由 registry 提供，
     * Simple 形态下看不到桶，会给出"配置了但抓取体里没有"的假绿。
     */
    @Test
    void prometheusScrapeExposesSloMeters() {
        var prom = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        OpsMetrics m = new OpsMetrics(prom);
        m.ttft(120);
        m.ttft(4000);
        m.requestDuration(50, true);
        m.requestAborted();
        m.admissionRejected();
        m.cacheWriteFailed();
        m.rerankFailed();
        m.sopMiss();

        String text = prom.scrape();
        // Prometheus 形态下 publishPercentileHistogram 生效 → 导出为直方图桶（分位由服务端从桶算，
        // 比三个固定分位更强）；客户端分位数在 SimpleMeterRegistry 用例里断言，两者不重复。
        assertTrue(text.contains("aiops_ttft_seconds_bucket"),
                "直方图桶缺失：SLO 侧就只能读三个固定分位，算不了任意窗口分位");
        assertTrue(text.contains("aiops_ttft_seconds_count") && text.contains("aiops_ttft_seconds_sum"),
                "直方图必须带 _count/_sum，否则 rate()/分位都算不出来");
        assertTrue(text.contains("aiops_request_seconds_count"), "请求耗时 Timer 要能被 count/rate 消费");
        assertTrue(text.contains("outcome=\"success\""), "成败分桶必须落在 label 上");
        for (String name : new String[]{"aiops_requests_aborted_total", "aiops_admission_rejected_total",
                "aiops_cache_write_fail_total", "aiops_rerank_fail_total", "aiops_sop_miss_total"}) {
            assertTrue(text.contains(name), "Prometheus 计数器名缺失: " + name);
        }
    }

    @Test
    void degradedRequestsCountAsTotalButNeverAsHit() {
        for (int i = 0; i < 4; i++) {
            metrics.request();
        }
        metrics.esOnly();
        metrics.sopFallback();
        assertEquals(4L, metrics.snapshot().get("total_requests"),
                "降级请求仍是处理完成的请求（分母要真实，比率才不会虚高）");
        assertEquals(0.0, registry.get("aiops.cache.hit.rate").gauge().value(), 1e-9,
                "ES-only 与 SOP 兜底不是缓存命中，混进来会让命中率虚高");
    }
}
