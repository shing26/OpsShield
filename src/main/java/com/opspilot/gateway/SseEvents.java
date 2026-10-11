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

    public void emitMeta(SseEmitter emitter, String fp, String cacheHit, Level level,
                         boolean fastPath, boolean deduplicated, long retrievalMs) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fingerprint", fp);
        meta.put("cache_hit", cacheHit);
        meta.put("degradation_level", level.name());
        meta.put("fast_path", fastPath);
        meta.put("deduplicated", deduplicated);
        meta.put("retrieval_ms", retrievalMs);
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
                         List<com.opspilot.action.Action> actions) {
        long ttftMs = (firstTokenNano - t0) / 1_000_000;
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("ttft_ms", ttftMs);
        done.put("refs", refs);
        // ADRs 0017：只读行动契约随 done 下发（键恒给，空表给 []——客户端不必判存在性）；
        // 每条命令都摘自已引用的 chunk 原文并经只读策略核准，网关自身不执行
        done.put("actions", actions == null ? List.of() : actions);
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
