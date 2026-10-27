package com.opspilot.gateway;

import tools.jackson.databind.ObjectMapper;
import com.opspilot.auth.UserContext;
import com.opspilot.common.ClientAbortedException;
import com.opspilot.action.Action;
import com.opspilot.action.ReadOnlyActionExtractor;
import com.opspilot.cache.L1CacheService;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.gateway.dto.ChatRequest;
import com.opspilot.gateway.dto.Gate;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.llm.LlmClient;
import com.opspilot.llm.PromptAssembler;
import com.opspilot.llm.VerbatimStreamFilter;
import com.opspilot.metrics.AuditService;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.metrics.StageTimings;
import com.opspilot.resilience.AdmissionGate;
import com.opspilot.resilience.DegradationState;
import com.opspilot.resilience.DegradationState.Level;
import com.opspilot.resilience.QuotaService;
import com.opspilot.resilience.SopFallbackService;
import com.opspilot.retrieval.HybridSearchService;
import com.opspilot.retrieval.ScoredChunk;
import com.opspilot.retrieval.SearchOutcome;
import com.opspilot.storm.FingerprintService;
import com.opspilot.storm.SingleFlight;
import com.opspilot.storm.SlidingWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 排障编排核心（P1-P4 之后自 CopilotController 抽出，sink 化）：
 * quota/计数前置 → 指纹 → Single-Flight → 滑动窗口 → L1 → L2 → 检索（tenant+level 双硬过滤）
 * → 置信度门控 → LLM 流式/降级直出 → 缓存回写 → 审计。
 * /api/v1/copilot/chat/stream（SSE）与 /v1/chat/completions（OpenAI）共享本链路——
 * 缓存/风暴收敛/熔断/吊销/配额/审计在两个协议面语义完全一致。
 */
@Component
public class ChatOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ChatOrchestrator.class);

    private final L1CacheService l1;
    private final L2SemanticCacheService l2;
    private final FingerprintService fingerprintService;
    private final SlidingWindow slidingWindow;
    private final SingleFlight singleFlight;
    private final HybridSearchService searchService;
    private final EmbeddingClient embedding;
    private final LlmClient llm;
    private final PromptAssembler promptAssembler;
    private final DegradationState degrade;
    private final SopFallbackService sopFallback;
    private final OpsMetrics metrics;
    private final ExecutorService vt;
    private final OpsPilotProperties props;
    private final AuditService audit;
    private final QuotaService quota;
    private final ReadOnlyActionExtractor actionExtractor;
    private final ObjectMapper mapper = new ObjectMapper();

    /** G1：在途编排数（@PreDestroy 优雅排空用；与降级状态机的 inflight 口径不同——后者含等待中的 leader/follower 语义）。 */
    private final AtomicInteger inFlight = new AtomicInteger();
    /** G1：收到 SIGTERM 后不再接新编排；在途请求最多再等 30s。 */
    private volatile boolean shuttingDown = false;

    public ChatOrchestrator(L1CacheService l1, L2SemanticCacheService l2,
                            FingerprintService fingerprintService, SlidingWindow slidingWindow,
                            SingleFlight singleFlight, HybridSearchService searchService,
                            EmbeddingClient embedding, LlmClient llm, PromptAssembler promptAssembler,
                            DegradationState degrade, SopFallbackService sopFallback,
                            OpsMetrics metrics, ExecutorService virtualThreadExecutor,
                            OpsPilotProperties props, AuditService audit, QuotaService quota,
                            ReadOnlyActionExtractor actionExtractor) {
        this.l1 = l1; this.l2 = l2; this.fingerprintService = fingerprintService;
        this.slidingWindow = slidingWindow; this.singleFlight = singleFlight;
        this.searchService = searchService; this.embedding = embedding; this.llm = llm;
        this.promptAssembler = promptAssembler; this.degrade = degrade; this.sopFallback = sopFallback;
        this.metrics = metrics; this.vt = virtualThreadExecutor; this.props = props;
        this.audit = audit; this.quota = quota; this.actionExtractor = actionExtractor;
    }

    /** 同步前置（必须在 servlet 线程调用）：配额 429 与请求计数要能在 HTTP 响应面上抛出。 */
    public void precheck(UserContext user) {
        quota.checkAndConsume(user.sub());
        metrics.request();
    }

    /** 准入拒绝计数（Controller 在 429 前调用；Gate 本身不依赖注册表，保持可单测）。 */
    public void recordAdmissionRejected() {
        metrics.admissionRejected();
    }

    /** 兼容旧调用（单测/内部工具）：不占准入许可。 */
    public void submit(ChatRequest req, UserContext user, ChatSink sink, String via) {
        submit(req, user, sink, via, null);
    }

    /** 异步编排：错误经 Failed 终态收尾（协议各自决定呈现），degrade.enter/exit 维护在飞计数。
     *  H2 + OP-A7：MDC 是 thread-local，编排切到虚拟线程后必须显式重挂 request_id 与 trace_id——
     *  异步段日志与审计行才能与 servlet 线程的请求日志用同一组 id 关联。
     *
     *  <p><b>G1 准入/排空</b>：permit 由 Controller 在 servlet 线程非阻塞获取（拿不到直接 429，
     *  不能等到 SSE 已返回才拒绝）；本方法保证**任何出口**（成功/失败/异常/拒绝执行）都释放一次。
     *  shutdown 后不再接新编排：给在途请求 30s 收敛，超出由 JVM 退出收尾。
     */
    public void submit(ChatRequest req, UserContext user, ChatSink sink, String via,
                       AdmissionGate.Permit permit) {
        if (shuttingDown) {
            if (permit != null) permit.release();
            sink.deliver(new DeliveryOutcome.Failed(
                    new IllegalStateException("gateway is shutting down"), List.of(), List.of()));
            return;
        }
        inFlight.incrementAndGet();
        String requestId = org.slf4j.MDC.get(com.opspilot.metrics.RequestIdFilter.KEY);
        String traceId = org.slf4j.MDC.get(com.opspilot.metrics.RequestIdFilter.TRACE_KEY);
        try {
            vt.execute(() -> {
                if (requestId != null) {
                    org.slf4j.MDC.put(com.opspilot.metrics.RequestIdFilter.KEY, requestId);
                }
                if (traceId != null) {
                    org.slf4j.MDC.put(com.opspilot.metrics.RequestIdFilter.TRACE_KEY, traceId);
                }
                degrade.enter();
                long startedNano = System.nanoTime();
                try {
                    handle(req, user, sink, via);
                    metrics.requestDuration((System.nanoTime() - startedNano) / 1_000_000, true);
                } catch (ClientAbortedException aborted) {
                    // 客户端已断：不计 LLM 失败（不是上游的错），不写缓存；审计由 chat_failed 的
                    // err_class=ClientAbortedException 表达，delivered=false 由 logChatFailed 固定带上。
                    log.info("client aborted: tenant={} via={}", user.tenantId(), via);
                    auditAborted(req, user, via, aborted);
                    metrics.requestAborted();
                    metrics.requestDuration((System.nanoTime() - startedNano) / 1_000_000, false);
                    sink.deliver(new DeliveryOutcome.Failed(aborted, List.of(), List.of()));
                } catch (FailedWithContract fc) {
                    logWarn(fc.getCause());
                    auditFailed(req, user, via, fc.getCause());
                    metrics.requestDuration((System.nanoTime() - startedNano) / 1_000_000, false);
                    sink.deliver(new DeliveryOutcome.Failed(fc.getCause(), fc.refs(), fc.actions()));
                } catch (Exception e) {
                    logWarn(e);
                    auditFailed(req, user, via, e);
                    metrics.requestDuration((System.nanoTime() - startedNano) / 1_000_000, false);
                    sink.deliver(new DeliveryOutcome.Failed(e, List.of(), List.of()));
                } finally {
                    degrade.exit();
                    inFlight.decrementAndGet();
                    if (permit != null) permit.release();
                    org.slf4j.MDC.remove(com.opspilot.metrics.RequestIdFilter.KEY);
                    org.slf4j.MDC.remove(com.opspilot.metrics.RequestIdFilter.TRACE_KEY);
                }
            });
        } catch (RuntimeException rejected) {
            // 执行器已 shutdown/拒绝：必须把计数与许可还回去，否则排空永远等不到 0。
            inFlight.decrementAndGet();
            if (permit != null) permit.release();
            throw rejected;
        }
    }

    /**
     * 优雅停机（G1）：Tomcat 收到 SIGTERM 后先停接新请求，本方法等在途编排收敛。
     * 30s 是 application.yml 的 lifecycle timeout 与 compose stop_grace_period 的内层预算；
     * 超时后由容器/JVM 收尾，audit 可能丢最后一段——这是已登记的边界，不假装能等无限久。
     */
    @PreDestroy
    public void drainOnShutdown() {
        shuttingDown = true;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int left = inFlight.get();
        if (left > 0) {
            log.warn("shutdown drain timeout: {} request(s) still in flight after 30s", left);
        } else {
            log.info("shutdown drain complete");
        }
    }

    private void handle(ChatRequest req, UserContext user, ChatSink sink, String via) throws Exception {
        // G1：SSE 客户端可能在首字节前就断开（等待检索/上游时最常见）。入口先判一次，
        // 避免为一个已死的连接跑完检索、精排、LLM 与缓存写。
        if (sink.aborted()) {
            throw new ClientAbortedException("client disconnected before pipeline");
        }
        String query = req.query();
        String source = req.sourceOrDefault();
        // 身份拆分（2026-10-17，台账 §3.1）：调用方给了稳定 alertId 就用它当不透明身份，
        // 不再从人类可读文案里推断"这是不是同一条告警"——外部规则模板会把 $value 渲染进注解，
        // 同一故障每次评估都不一样。alertId 缺失（人工提问、既有自举源）才回退文本指纹，
        // 逐字保持今天的行为；L1、SingleFlight、滑动窗口、审计、meta 全部消费同一个 fp。
        String alertId = req.alertIdOrNull();
        String fp = alertId != null
                ? fingerprintService.identity(alertId)
                : fingerprintService.fingerprint(req.service(), req.env(), query);
        long t0 = System.nanoTime();

        // Single-Flight 为主闸门：同 (tenant, fingerprint, authLevel) 并发仅 1 个 leader 穿透，
        // 缓存检查在 leader 内部完成，杜绝「L1 检查后、flight 移除前」的竞态重复穿透。
        // 2026-09-11 QA P0-1：key 曾缺 tenant——跨租户同密级并发风暴下 follower 回放 leader
        // 全文+引用（引擎层过滤管不到"共享在途结果"这条旁路）。权限维度必须完备：
        // 租户×密级（ADR-0008），与 L1 key 的 cache:l1:<tenant>:<level>: 同构。
        String sfKey = singleFlightKey(user, fp);
        SingleFlight.Registration reg = singleFlight.getOrCreate(sfKey);
        if (!reg.leader()) {
            String shared = reg.future().get(90, TimeUnit.SECONDS);
            metrics.dedupAggregated();
            replay(sink, shared, "none", true, fp, t0, user, query, via, source);
            return;
        }

        // 回指/追问澄清（2026-09-28，探索性验收查出的摩擦）：本系统单轮无会话，把"刚才那个怎么办"
        // 当独立 query 硬检索会命中一篇无关复盘且答得自信——对"可信"的伤害远大于多问一句。
        // **位置在缓存之前**：本门判的是"输入形态"，与缓存状态无关；放在缓存之后会让修复前落进 L1 的
        // 旧答案继续回放（实测踩到：清缓存才生效，而"下次重灌"不是可靠前提）。
        // 唯一例外是 L2：过载/熔断时 SOP 直出仍是更好的答案，澄清不该抢在它前面。
        // 注意必须**在下面的 try 之内**：单飞登记靠 finally 收尾，放到 try 外会让异常路径漏掉
        // `singleFlight.finish`，同指纹后续请求将阻塞在永不完成的 future 上（90s 超时）。
        try {
            SlidingWindow.WindowResult win = slidingWindow.tryAcquire(fp, source);
            if (!win.first()) metrics.dedupAggregated();

            Level level = degrade.current();
            if (level != Level.L2 && ClarificationGate.needsClarification(query)) {
                String clarify = ClarificationGate.MESSAGE;
                AnswerPayload p = new AnswerPayload(clarify, List.of(), List.of(), "clarify", false,
                        user.authLevel(), user.tenantId(), true, List.of());
                String json = mapper.writeValueAsString(p);
                // 回指澄清在载荷里同样标 refused=true（CONTEXT.md 回指澄清条）——帧上如实带出，
                // 客户端因此不必靠匹配澄清话术来区分"系统没答"
                // gate=clarify（ADR-0023）：与无据拒答（gate=refuse）分开——两者的成因与代价都不同
                sink.meta(new Meta(fp, "none", level, false, false, 0, true, p.gate()));
                long clarifyNano = System.nanoTime();
                sink.delta(clarify);
                sink.deliver(new DeliveryOutcome.Completed(
                        new TtftTiming(t0, clarifyNano), p, AnswerDelivery.STREAMED));
                // **刻意不写 L1**（2026-09-28 code-review 抓出）：本门在 `l1.get` 之前，L0/L1 期刚写的
                // 条目永不可读＝死写；而 L2 期门让位、`l1.get` 反会回放它——澄清抢在 SOP 直出之前，
                // 复刻 A2-6 的已修坑（"降级期答案入缓存 → 同 query 仍回放"）。同指纹 follower 已由
                // `reg.future().complete` 覆盖，缓存没有增益。
                reg.future().complete(json);
                audit.log(user, "chat", via, source, query, fp, "none", "clarify", true, 0,
                        (System.nanoTime() - t0) / 1_000_000, null, null,
                        null,   // 检索与 LLM 都未发生：不落 stage_ms（"没测"≠"测得为 0"，与 SOP 直出同口径）
                        level.name());
                return;
            }

            String cached = l1.get(user.tenantId(), user.authLevel(), fp);
            if (cached != null) {
                metrics.l1Hit();
                reg.future().complete(cached);
                replay(sink, cached, "L1", false, fp, t0, user, query, via, source);
                return;
            }
            // G1：L2 档位直出 SOP，语义检索/缓存写入都没有意义——先查 L2 只会多等一次 embedding
            // 超时（上游整体挂掉时首字节从 0 变成 15s）。L2 档位跳过 lookup/store。
            L2SemanticCacheService.CacheHit l2hit = level == Level.L2
                    ? null
                    : l2.lookup(query, user.tenantId(), user.authLevel());
            if (l2hit != null) {
                metrics.l2Hit();
                String json = l2hit.payloadJson();   // 含 refs，回放溯源完整
                l1.put(user.tenantId(), user.authLevel(), fp, json);
                reg.future().complete(json);
                replay(sink, json, "L2", false, fp, t0, user, query, via, source);
                return;
            }
            String json = runPipeline(req, user, sink, fp, t0, via, source);
            reg.future().complete(json);
        } catch (Exception e) {
            reg.future().completeExceptionally(e);
            throw e;
        } finally {
            singleFlight.finish(sfKey, reg.future());
        }
    }

    /** leader 全链路：降级判定 → 检索 → LLM 流式（或 SOP 直出）→ 写缓存。返回答案 JSON。 */
    private String runPipeline(ChatRequest req, UserContext user, ChatSink sink,
                               String fp, long t0, String via, String source) throws Exception {
        Level level = degrade.current();
        String query = req.query();

        // Level 2：熔断 LLM，直出静态 SOP（分片流式，保留打字机体验）。
        // P4：SOP 直出**不写 L1**——降级期答案是应急兜底，若入缓存，恢复 auto 后同 query
        // 仍会回放 SOP（A2-6 实测坑，DEMO.md 幕⑥同源）；成本护栏因此只保真内容进缓存。
        if (level == Level.L2) {
            metrics.sopFallback();
            String sop = sopFallback.lookup(query, req.service(), user.tenantId());
            if (sop == null) metrics.sopMiss();
            String answer = sop != null ? sop : "系统高负载，已触发熔断降级，暂无可用止损清单，请联系值班 SRE。";
            AnswerPayload p = new AnswerPayload(answer, List.of(), List.of(), "sop_fallback", false,
                    user.authLevel(), user.tenantId(), false, List.of());
            String json = mapper.writeValueAsString(p);
            sink.meta(new Meta(fp, "none", level, false, false, 0, false, p.gate()));
            long sopFirstDeltaNano = System.nanoTime();
            sink.deliver(new DeliveryOutcome.Completed(
                    new TtftTiming(t0, sopFirstDeltaNano), p, AnswerDelivery.BUFFERED));
            audit.log(user, "chat", via, source, query, fp, "none", "sop_fallback", false, user.authLevel(),
                    (System.nanoTime() - t0) / 1_000_000, null, null,
                    null,   // SOP 直出：检索与 LLM 都未发生，无分段可测（null = 没测，非"测得为 0"）
                    level.name());
            return json;
        }

        // 检索（L1 降级走 es_only；tenant+authLevel 双硬过滤在引擎层）
        String mode = level == Level.L1 ? "es_only" : "hybrid";
        SearchOutcome outcome = searchService.search(query, user.tenantId(), user.authLevel(), mode);
        // 检索可能耗时数秒：回来后再判一次，断连请求不该继续花 LLM 配额。
        if (sink.aborted()) {
            throw new ClientAbortedException("client disconnected during retrieval");
        }
        List<ScoredChunk> chunks = outcome.chunks();

        // 置信度空态门控：零召回，或非快路径且 Top-1 相关度低于阈值 → 显式拒答，不调 LLM。
        double minRel = props.retrieval().minRelevance();
        boolean lowConfidence = chunks.isEmpty()
                || (!outcome.fastPath() && outcome.topRelevance() < minRel);
        if (lowConfidence) {
            metrics.lowConfidence();
            // QA P2-5：分数与阈值是内部量纲，外显=门控 oracle（攻击者可精调话术卡到阈值之上）；
            // 只进日志与 metrics，对外话术不回显。
            log.debug("low-confidence refusal: top={} threshold={} empty={}",
                    outcome.topRelevance(), minRel, chunks.isEmpty());
            String reason = chunks.isEmpty() ? "未匹配到任何参考" : "检索置信度不足";
            String refusal = "当前知识库无足够相关的参考（" + reason + "），无法可靠作答。"
                    + "请补充错误码（如 50012_DB_TIMEOUT）或服务名后重试，或联系值班 SRE。";
            // ADR-0023：拒答时给"最接近的候选"——**只带标识符**（service + error_codes），不带
            // chunk_id / 面包屑 / 正文。给的是"下一步该问什么"，不是"低置信度的答案"：一旦把内容
            // 摆出来，用户就会拿它当答案用，而系统无法保证那几段不是**别的故障**的手册。候选取自
            // **已经按 tenant×authLevel 过滤过**的检索结果，且只带覆盖清单本来就要公开的标识符，
            // 故不新增越权面。零召回时为空表，页面自动退回覆盖清单。
            List<AnswerPayload.NearMiss> nearMiss = chunks.stream().limit(3)
                    .map(c -> new AnswerPayload.NearMiss(c.service(),
                            c.errorCodes() == null ? List.of() : c.errorCodes()))
                    .distinct()
                    .toList();
            // maxAuthLevel=0：拒答不触达任何语料（回放路径的审计 max_level 由此而来，live QA F-1）
            AnswerPayload p = new AnswerPayload(refusal, List.of(), List.of(), outcome.mode(), false, 0,
                    user.tenantId(), true, nearMiss);
            String json = mapper.writeValueAsString(p);
            sink.meta(new Meta(fp, "none", level, false, false, outcome.tookMs(), true, p.gate()));
            long refusalNano = System.nanoTime();
            sink.delta(refusal);
            sink.deliver(new DeliveryOutcome.Completed(
                    new TtftTiming(t0, refusalNano), p, AnswerDelivery.STREAMED));
            l1.put(user.tenantId(), user.authLevel(), fp, json);
            audit.log(user, "chat", via, source, query, fp, "none", outcome.mode(), true, 0,
                    (System.nanoTime() - t0) / 1_000_000, null, null, 0, 0,
                    stageTimings(outcome, 0, 0, 0),   // 拒答在 LLM 前 return：llm/l2_store 恒 0（测了且为 0）
                    level.name());
            return json;
        }

        int maxAuth = chunks.stream().mapToInt(ScoredChunk::authLevel).max().orElse(user.authLevel());

        sink.meta(new Meta(fp, "none", level, outcome.fastPath(), false,
                outcome.tookMs(), false, Gate.NONE));

        // 只读行动契约（ADRs 0017）：从**本轮引用的同一批 chunk** 逐字抽，与 refs 同源同权，
        // 因此租户/密级闸不在这里重设。放这里（LLM 之前）而非答完后：契约与"引用了什么"绑定，
        // 与答案生成成败解耦——LLM 失败走异常路径时本次请求照样带得出契约。
        List<Action> actions = actionExtractor.extract(chunks);

        // LLM 流式（verbatim 出口护栏，生成质量包 Q2=C）：token 经句级缓冲+超阈掩码后才下发。
        // 设卡一处即可：L1/L2 写入与 SF future.complete 消费的都是 guard.finish() 掩码版——
        // 缓存回放与 follower 天然干净（ADR-0010）。firstTokenNano 记录首个**下发**帧。
        metrics.llmCall();
        AtomicLong firstTokenNano = new AtomicLong(0);
        List<AnswerPayload.Ref> refs = new ArrayList<>();
        for (ScoredChunk c : chunks) {
            refs.add(new AnswerPayload.Ref(c.chunkId(), c.breadcrumb(), c.service()));
        }
        VerbatimStreamFilter guard = new VerbatimStreamFilter(
                chunks.stream().map(ScoredChunk::text).toList(),
                text -> {
                    // G1：客户端断开后立即停止消费上游流——继续跑到 done 是纯成本，
                    // 且会把无人接收的请求留在 inflight 里推高降级档。
                    if (sink.aborted()) {
                        throw new ClientAbortedException("client disconnected");
                    }
                    firstTokenNano.compareAndSet(0, System.nanoTime());
                    sink.delta(text);
                });
        try {
            long llmStartNano = System.nanoTime();
            llm.streamChat(promptAssembler.build(query, chunks), guard::accept);
            String answer = guard.finish();
            int masked = guard.maskedCount();
            degrade.llmSuccess();
            if (masked > 0) metrics.verbatimMasked(masked);
            AnswerPayload p = new AnswerPayload(answer, refs, actions, outcome.mode(), outcome.fastPath(),
                    maxAuth, user.tenantId(), false, List.of());
            String json = mapper.writeValueAsString(p);
            l1.put(user.tenantId(), user.authLevel(), fp, json);
            long ftNano = firstTokenNano.get();
            long llmMs = (System.nanoTime() - llmStartNano) / 1_000_000;
            long llmTtftMs = ftNano == 0 ? llmMs : (ftNano - llmStartNano) / 1_000_000;
            // G1 交付顺序：先给客户端 done，再写审计，最后异步写 L2。
            // 此前 L2 写入（内含一次同步 embedding，读超时 15s）夹在 LLM 与 deliver 之间：
            // 缓存写失败会把"已生成的答案"变成 error 帧，还会被 catch 记成 LLM 失败并误开全局熔断。
            // TTFT 从请求进入（t0）到首个下发 token；ftNano==0 时用当前时刻（非流式收尾仍算一次可测值）。
            metrics.ttft(ftNano == 0 ? 0 : (ftNano - t0) / 1_000_000);
            sink.deliver(new DeliveryOutcome.Completed(
                    new TtftTiming(t0, ftNano == 0 ? System.nanoTime() : ftNano), p,
                    AnswerDelivery.STREAMED));
            // 口径变更（G1）：L2 写入已异步，stage_ms.l2_store 记录的是"入队开销"而不是磁盘/网络写入耗时。
            // 仍要测：入队也可能因执行器拒绝/队列慢而变慢；把它留成 0 会违反本仓"0=测了且为 0"的纪律。
            long l2EnqueueNano = System.nanoTime();
            asyncStoreL2(query, json, maxAuth, user.tenantId());
            long l2EnqueueMs = (System.nanoTime() - l2EnqueueNano) / 1_000_000;
            audit.log(user, "chat", via, source, query, fp, "none", outcome.mode(), false, maxAuth,
                    (System.nanoTime() - t0) / 1_000_000, null, masked > 0 ? masked : null,
                    refs.size(), actions.size(),
                    stageTimings(outcome, llmTtftMs, llmMs, l2EnqueueMs), level.name());
            return json;
        } catch (ClientAbortedException aborted) {
            // 不是上游失败：不推进熔断计数，直接把控制流交给 submit 的 aborted 分支。
            throw aborted;
        } catch (LlmClient.LlmRateLimitedException e) {
            metrics.llmRateLimited();
            degrade.llmFailure();
            throw new FailedWithContract(e, List.copyOf(refs), actions);
        } catch (Exception e) {
            degrade.llmFailure();
            throw new FailedWithContract(e, List.copyOf(refs), actions);
        }
    }

    /**
     * L2 写入移出请求路径（G1）：embedding 是外部调用（读超时 15s），缓存写失败不应把
     * 已交付的答案变成 error 帧，更不应计入 LLM 熔断。失败只影响"下次能否命中"，降级为
     * debug + 指标（M1 接线 cache_write_fail）；绝不回抛给客户端。
     *
     * <p>用同一个虚拟线程执行器（而非新线程池）：它已有 shutdown 生命周期，随进程一起收；
     * 写缓存任务短暂且不占用请求线程。执行器已 shutdown 时静默跳过——进程正在退出，
     * 缓存写没有"持久化"义务（Qdrant 是派生数据，L1 有 TTL）。
     */
    private void asyncStoreL2(String query, String json, int maxAuth, String tenant) {
        try {
            vt.execute(() -> {
                try {
                    float[] qv = embedding.embedOne(query);
                    l2.store(query, qv, json, maxAuth, tenant);
                } catch (Exception e) {
                    metrics.cacheWriteFailed();
                    log.debug("L2 async store failed (non-fatal): {}", e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            log.debug("L2 async store skipped (executor shutting down)");
        }
    }

    /** 客户端中断的审计收口：与失败同 schema，但 err_class 明确是 ClientAborted（不是上游故障）。 */
    private void auditAborted(ChatRequest req, UserContext user, String via, Throwable e) {
        try {
            audit.logChatFailed(user, via, req.sourceOrDefault(), req.query(), null, e,
                    degrade.current().name());
        } catch (Exception auditError) {
            log.warn("chat_aborted audit failed: {}", auditError.toString());
        }
    }

    /** 失败请求的审计收口：审计行只留可聚合摘要，异常对象交给 logWarn 落堆栈。 */
    private void auditFailed(ChatRequest req, UserContext user, String via, Throwable e) {
        try {
            audit.logChatFailed(user, via, req.sourceOrDefault(), req.query(), null, e,
                    degrade.current().name());
            metrics.requestFailed(e.getClass().getSimpleName());
        } catch (Exception auditError) {
            // 审计失败不阻断 error 帧（与 AuditService 同纪律）：业务收尾优先。
            log.warn("chat_failed audit failed: {}", auditError.toString());
        }
    }

    /** 服务端日志带异常 message 供运维归因（LlmClient 的 message 自拼：状态码/网络错类，
     *  不含凭据；对外 error 帧仍是脱敏固定话术，SLO 面零泄露）。控制符压平成空格再截断：
     *  上游 message 里若带换行，落日志就是可伪造的行（2026-10-11 评审批）。 */
    private static void logWarn(Throwable e) {
        String m = String.valueOf(e.getMessage()).replaceAll("[\\r\\n\\t]+", " ");
        log.warn("stream error: {}: {}", e.getClass().getSimpleName(),
                m.substring(0, Math.min(300, m.length())), e);
    }

    /**
     * 生成侧异常 + 异常前已经到手的检索与契约成果（ADRs 0017）。
     *
     * <p>为什么要包一层：error 帧必须只有一个出口——{@link #submit} 的 catch。内层各处 catch
     * 只负责把成果挂上去，不自己发帧，否则同一次请求会落下两个 error 帧（第二个被
     * emitter.complete 之后静默吞掉，排查时只看见一个，语义却已经错了）。
     */
    static final class FailedWithContract extends RuntimeException {
        private final List<AnswerPayload.Ref> refs;
        private final List<Action> actions;

        FailedWithContract(Throwable cause, List<AnswerPayload.Ref> refs, List<Action> actions) {
            super(cause);
            this.refs = refs;
            this.actions = actions;
        }

        List<AnswerPayload.Ref> refs() { return refs; }

        List<Action> actions() { return actions; }
    }

    /** Single-Flight 组键 = 租户+指纹+密级（P0-1 修复：租户是第一字段，跨租户永不共组）。 */
    static String singleFlightKey(UserContext user, String fp) {
        return user.tenantId() + ":" + fp + ":" + user.authLevel();
    }

    /**
     * 缓存回放 / Single-Flight 共享答案 → 分片打字机下发（帧格式由 sink 决定）。
     * 入口绊线 fail-closed：载荷租户与请求者不符 → 拒绝回放、error 收尾、审计告警。
     * sfKey 掺租户后本不应触发；触发即意味着又出现了新的无租户共享旁路——宁误伤不泄露。
     */
    void replay(ChatSink sink, String json, String cacheHit, boolean deduplicated,
                String fp, long t0, UserContext user, String query, String via, String source) throws Exception {
        // 档位只观察一次并复用：current() 现在同时是转移落痕的观察点，重复调用无意义（也不贵，但没必要）
        Level lvl = degrade.current();
        AnswerPayload p = mapper.readValue(json, AnswerPayload.class);
        if (!user.tenantId().equals(p.tenant())) {
            log.error("shared replay tenant mismatch: payload={} requester={}", p.tenant(), user.tenantId());
            audit.log(user, "chat", via, source, query, fp, "dedup_guard", p.mode(), true, 0,
                    (System.nanoTime() - t0) / 1_000_000, null, null, null,   // 回放路径：无检索/LLM 分段
                    lvl.name());
            sink.deliver(new DeliveryOutcome.Failed(
                    new IllegalStateException("shared replay tenant mismatch"), List.of(), List.of()));
            return;
        }
        // refused / gate / near_miss **一律取载荷里的值**（不硬编码）：拒答也写 L1，回放一条被
        // 拒答的 query 时客户端收到的仍是拒答话术——2026-09-28 live QA F-1 正是"帧上说没拒答、
        // 话术却在拒答"的失真，那个坑不能从帧这一侧再开一次（ADR-0020）。gate 与 near_miss
        // 同理：回放一条澄清、一条拒答，帧上的闸门与候选必须与首答一致（ADR-0023）。
        sink.meta(new Meta(fp, cacheHit, lvl, p.fastPath(), deduplicated, 0, p.refused(), p.gate()));
        long replayFirstDeltaNano = System.nanoTime();
        sink.deliver(new DeliveryOutcome.Completed(
                new TtftTiming(t0, replayFirstDeltaNano), p, AnswerDelivery.BUFFERED));
        // src_tenant 随行（QA P1-2"命中来源"）：与 tenant 相等=正常同租户回放；
        // grep 不等即可发现任何新的跨租户共享旁路。
        audit.log(user, "chat", via, source, query, fp, cacheHit + (deduplicated ? "+dedup" : ""),
                p.mode(), p.refused(), p.maxAuthLevel(), (System.nanoTime() - t0) / 1_000_000, p.tenant(), null,
                p.refs() == null ? 0 : p.refs().size(), p.actionsOrEmpty().size(),
                null,   // 回放路径：无检索/LLM 分段
                lvl.name());
    }

    /** G2：把检索分腿 + LLM + L2 写入装配成审计行的 stage_ms（口径见 StageTimings）。 */
    private static StageTimings stageTimings(SearchOutcome outcome, long llmTtftMs, long llmMs, long l2StoreMs) {
        var legs = outcome.legs();
        return new StageTimings((int) outcome.tookMs(),
                legs.esMs(), legs.vectorMs(), legs.rrfMs(), legs.rerankMs(),
                (int) llmTtftMs, (int) llmMs, (int) l2StoreMs);
    }
}
