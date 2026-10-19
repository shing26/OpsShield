package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.resilience.DegradationState.Level;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 事件原语（meta/delta/done）：从 CopilotController 抽出。
 * 目的：Controller 只因编排变化，事件帧格式只因协议变化——拆开 Divergent Change 的两个轴。
 * 口径约定：done.ttft_ms 恒为 first_token 口径（调用方传入首个 delta 的时刻），
 * 由 acceptance_a3 A3-6 双源断言锁死。
 */
@Component
public class SseEvents {

    private static final Logger log = LoggerFactory.getLogger(SseEvents.class);

    /**
     * meta 帧（8 键，2026-10-19 起）。新增键的位置固定在末尾：既有键的次序是既有客户端
     * （troubleshoot.html 与 A3 断言）读惯的形态，新增键只许追加不许插队。
     *
     * <p>为什么必须有这个键（ADR-0020）：拒答路径此前发的是与正常路径**逐字无差别**的 meta
     * 帧，客户端只能靠匹配拒答话术来区分——那是拿话术当协议，话术一改就静默失效（troubleshoot.html
     * 里那段判拒答的分支就是这么变成死代码的）。而 {@code refused} 本来就是 {@link AnswerPayload}
     * 的存储合同字段，它只是没被送上帧。
     *
     * <p>{@code gate}（{@code none|clarify|refuse}，ADR-0023）：{@code refused} 只说"没答"，
     * 说不清"为什么没答"。回指澄清与无据拒答的成因、代价都不同（零检索零 LLM vs 检索跑完但
     * 低于阈值），CONTEXT.md 明确写着"澄清不是拒答"——只给 {@code refused} 会让页面只能把两者
     * 渲染成同一个徽标。**不得用 {@code retrieval_ms == 0} 反推澄清**：那是拿性能量当状态量，
     * 任何一次缓存命中都会让它误判。
     */
    public void emitMeta(SseEmitter emitter, String fp, String cacheHit, Level level,
                         boolean fastPath, boolean deduplicated, long retrievalMs, boolean refused,
                         String gate) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fingerprint", fp);
        meta.put("cache_hit", cacheHit);
        meta.put("degradation_level", level.name());
        meta.put("fast_path", fastPath);
        meta.put("deduplicated", deduplicated);
        meta.put("retrieval_ms", retrievalMs);
        meta.put("refused", refused);
        meta.put("gate", gate);
        trySend(emitter, "meta", meta);
    }

    /** 完整答案分片下发，保留打字机流式体验（缓存回放/降级直出用）。 */
    public void streamInChunks(SseEmitter emitter, String answer) {
        for (int i = 0; i < answer.length(); i += 48) {
            emitDelta(emitter, answer.substring(i, Math.min(i + 48, answer.length())));
        }
    }

    public void emitDelta(SseEmitter emitter, String token) {
        trySend(emitter, "delta", Map.of("token", token));
    }

    public void emitDone(SseEmitter emitter, long t0, long firstTokenNano, List<AnswerPayload.Ref> refs,
                         List<com.opspilot.action.Action> actions,
                         List<AnswerPayload.NearMiss> nearMiss) {
        long ttftMs = (firstTokenNano - t0) / 1_000_000;
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("ttft_ms", ttftMs);
        done.put("refs", refs);
        // ADRs 0017：只读行动契约随 done 下发（键恒给，空表给 []——客户端不必判存在性）；
        // 每条命令都摘自已引用的 chunk 原文并经只读策略核准，网关自身不执行
        done.put("actions", actions == null ? List.of() : actions);
        // ADR-0023：拒答时的候选标识符（键恒给，空表给 []）——只带 service 与 error_codes，
        // 不带 chunk_id/面包屑/正文：给的是"下一步该问什么"，不是"低置信度的答案"
        done.put("near_miss", nearMiss == null ? List.of() : nearMiss);
        trySend(emitter, "done", done);
        completeQuietly(emitter);
    }

    /**
     * 异常收尾帧：固定话术（脱敏）+ 异常前已到手的溯源与只读契约。
     *
     * <p>为什么要带成果：DashScope 欠费、上游 5xx 这类生成侧故障发生时，检索与契约抽取
     * 已经跑完且不依赖 LLM——把它一并吞掉等于让客户端在最需要行动清单的时候拿到空手。
     * 无成果时两个键都不落，帧形态与历史一致（rb-111 手册描述的 error 帧形态不变）。
     */
    public void emitError(SseEmitter emitter, List<AnswerPayload.Ref> refs,
                          List<com.opspilot.action.Action> actions) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", "PIPELINE_ERROR");
        err.put("message", "排障链路异常，请重试或联系值班 SRE");
        if (refs != null && !refs.isEmpty()) {
            err.put("refs", refs);
        }
        if (actions != null && !actions.isEmpty()) {
            err.put("actions", actions);
        }
        trySend(emitter, "error", err);
        completeQuietly(emitter);
    }

    /**
     * 客户端超时/断连后 emitter 已被容器收尾，第二次 {@code complete()} 会抛
     * {@link IllegalStateException}——与 {@link #trySend} 同口径静默（收尾权归先到者，
     * 异常帧丢失只是噪音，degrade 计数在编排层 finally 兜底不受影响）。
     */
    private static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException already) {
            log.debug("SSE already completed (client gone): {}", already.getMessage());
        }
    }

    public void trySend(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed (client gone): {}", e.getMessage());
        }
    }
}
