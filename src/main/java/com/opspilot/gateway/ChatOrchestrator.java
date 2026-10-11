package com.opspilot.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opspilot.auth.UserContext;
import com.opspilot.action.Action;
import com.opspilot.action.ReadOnlyActionExtractor;
import com.opspilot.cache.L1CacheService;
import com.opspilot.cache.L2SemanticCacheService;
import com.opspilot.config.OpsPilotProperties;
import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.gateway.dto.ChatRequest;
import com.opspilot.llm.EmbeddingClient;
import com.opspilot.llm.LlmClient;
import com.opspilot.llm.PromptAssembler;
import com.opspilot.llm.VerbatimStreamFilter;
import com.opspilot.metrics.AuditService;
import com.opspilot.metrics.OpsMetrics;
import com.opspilot.metrics.StageTimings;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    /** 异步编排：错误经 sink.error 收尾（协议各自决定呈现），degrade.enter/exit 维护在飞计数。
     *  H2 + OP-A7：MDC 是 thread-local，编排切到虚拟线程后必须显式重挂 request_id 与 trace_id——
     *  异步段日志与审计行才能与 servlet 线程的请求日志用同一组 id 关联。 */
    public void submit(ChatRequest req, UserContext user, ChatSink sink, String via) {
        String requestId = org.slf4j.MDC.get(com.opspilot.metrics.RequestIdFilter.KEY);
        String traceId = org.slf4j.MDC.get(com.opspilot.metrics.RequestIdFilter.TRACE_KEY);
        vt.execute(() -> {
            if (requestId != null) {
                org.slf4j.MDC.put(com.opspilot.metrics.RequestIdFilter.KEY, requestId);
            }
            if (traceId != null) {
                org.slf4j.MDC.put(com.opspilot.metrics.RequestIdFilter.TRACE_KEY, traceId);
            }
            degrade.enter();
            try {
                handle(req, user, sink, via);
            } catch (FailedWithContract fc) {
                logWarn(fc.getCause());
                sink.error(fc.getCause(), fc.refs(), fc.actions());
            } catch (Exception e) {
                logWarn(e);
                sink.error(e, List.of(), List.of());
            } finally {
                degrade.exit();
                org.slf4j.MDC.remove(com.opspilot.metrics.RequestIdFilter.KEY);
                org.slf4j.MDC.remove(com.opspilot.metrics.RequestIdFilter.TRACE_KEY);
            }
        });
    }

    private void handle(ChatRequest req, UserContext user, ChatSink sink, String via) throws Exception {
        String query = req.query();
        String source = req.sourceOrDefault();
        String fp = fingerprintService.fingerprint(req.service(), req.env(), query);
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
                        user.authLevel(), user.tenantId(), true);
                String json = mapper.writeValueAsString(p);
                sink.meta(fp, "none", level, false, false, 0);
                long clarifyNano = System.nanoTime();
                sink.delta(clarify);
                sink.done(t0, clarifyNano, List.of(), List.of());
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

            String cached = l1.get(user.tenantId(), user.authLevel(), query);
            if (cached != null) {
                metrics.l1Hit();
                reg.future().complete(cached);
                replay(sink, cached, "L1", false, fp, t0, user, query, via, source);
                return;
            }
            L2SemanticCacheService.CacheHit l2hit = l2.lookup(query, user.tenantId(), user.authLevel());
            if (l2hit != null) {
                metrics.l2Hit();
                String json = l2hit.payloadJson();   // 含 refs，回放溯源完整
                l1.put(user.tenantId(), user.authLevel(), query, json);
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
            String answer = sop != null ? sop : "系统高负载，已触发熔断降级，暂无可用止损清单，请联系值班 SRE。";
            AnswerPayload p = new AnswerPayload(answer, List.of(), List.of(), "sop_fallback", false,
                    user.authLevel(), user.tenantId(), false);
            String json = mapper.writeValueAsString(p);
            sink.meta(fp, "none", level, false, false, 0);
            long sopFirstDeltaNano = System.nanoTime();
            sink.streamInChunks(answer);
            sink.done(t0, sopFirstDeltaNano, List.of(), List.of());
            audit.log(user, "chat", via, source, query, fp, "none", "sop_fallback", false, user.authLevel(),
                    (System.nanoTime() - t0) / 1_000_000, null, null,
                    null,   // SOP 直出：检索与 LLM 都未发生，无分段可测（null = 没测，非"测得为 0"）
                    level.name());
            return json;
        }

        // 检索（L1 降级走 es_only；tenant+authLevel 双硬过滤在引擎层）
        String mode = level == Level.L1 ? "es_only" : "hybrid";
        SearchOutcome outcome = searchService.search(query, user.tenantId(), user.authLevel(), mode);
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
            // maxAuthLevel=0：拒答不触达任何语料（回放路径的审计 max_level 由此而来，live QA F-1）
            AnswerPayload p = new AnswerPayload(refusal, List.of(), List.of(), outcome.mode(), false, 0,
                    user.tenantId(), true);
            String json = mapper.writeValueAsString(p);
            sink.meta(fp, "none", level, false, false, outcome.tookMs());
            long refusalNano = System.nanoTime();
            sink.delta(refusal);
            sink.done(t0, refusalNano, List.of(), List.of());
            l1.put(user.tenantId(), user.authLevel(), query, json);
            audit.log(user, "chat", via, source, query, fp, "none", outcome.mode(), true, 0,
                    (System.nanoTime() - t0) / 1_000_000, null, null,
                    stageTimings(outcome, 0, 0, 0),   // 拒答在 LLM 前 return：llm/l2_store 恒 0（测了且为 0）
                    level.name());
            return json;
        }

        int maxAuth = chunks.stream().mapToInt(ScoredChunk::authLevel).max().orElse(user.authLevel());

        sink.meta(fp, "none", level, outcome.fastPath(), false, outcome.tookMs());

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
                    maxAuth, user.tenantId(), false);
            String json = mapper.writeValueAsString(p);
            l1.put(user.tenantId(), user.authLevel(), query, json);
            // L2 写入：存完整 payload（含 refs）。**注意这里是第二次 embedding 调用**——
            // 检索腿里那次算出的向量没有被回传复用（腿的返回面只有 chunk 列表），故同一次请求
            // 会 embed 两次。G2 的 stage_ms.l2_store 把这段成本显式化，供后续决定是否值得回传复用。
            long l2StartNano = System.nanoTime();
            float[] qv = embedding.embedOne(query);
            l2.store(query, qv, json, maxAuth, user.tenantId());
            long l2StoreMs = (System.nanoTime() - l2StartNano) / 1_000_000;
            long ftNano = firstTokenNano.get();
            long llmMs = (System.nanoTime() - llmStartNano) / 1_000_000;
            long llmTtftMs = ftNano == 0 ? llmMs : (ftNano - llmStartNano) / 1_000_000;
            sink.done(t0, ftNano == 0 ? System.nanoTime() : ftNano, refs, actions);
            audit.log(user, "chat", via, source, query, fp, "none", outcome.mode(), false, maxAuth,
                    (System.nanoTime() - t0) / 1_000_000, null, masked > 0 ? masked : null,
                    stageTimings(outcome, llmTtftMs, llmMs, l2StoreMs), level.name());
            return json;
        } catch (LlmClient.LlmRateLimitedException e) {
            metrics.llmRateLimited();
            degrade.llmFailure();
            throw new FailedWithContract(e, List.copyOf(refs), actions);
        } catch (Exception e) {
            degrade.llmFailure();
            throw new FailedWithContract(e, List.copyOf(refs), actions);
        }
    }

    /** 服务端日志带异常 message 供运维归因（LlmClient 的 message 自拼：状态码/网络错类，
     *  不含凭据；对外 error 帧仍是脱敏固定话术，SLO 面零泄露）。控制符压平成空格再截断：
     *  上游 message 里若带换行，落日志就是可伪造的行（2026-10-11 评审批）。 */
    private static void logWarn(Throwable e) {
        String m = String.valueOf(e.getMessage()).replaceAll("[\\r\\n\\t]+", " ");
        log.warn("stream error: {}: {}", e.getClass().getSimpleName(),
                m.substring(0, Math.min(300, m.length())));
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
            sink.error(new IllegalStateException("shared replay tenant mismatch"),
                    List.of(), List.of());
            return;
        }
        sink.meta(fp, cacheHit, lvl, p.fastPath(), deduplicated, 0);
        long replayFirstDeltaNano = System.nanoTime();
        sink.streamInChunks(p.answer());
        sink.done(t0, replayFirstDeltaNano, p.refs(), p.actionsOrEmpty());
        // src_tenant 随行（QA P1-2"命中来源"）：与 tenant 相等=正常同租户回放；
        // grep 不等即可发现任何新的跨租户共享旁路。
        audit.log(user, "chat", via, source, query, fp, cacheHit + (deduplicated ? "+dedup" : ""),
                p.mode(), p.refused(), p.maxAuthLevel(), (System.nanoTime() - t0) / 1_000_000, p.tenant(), null,
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
