package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.gateway.dto.Gate;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** ChatSink 的 SSE 实现：全部帧格式委托 SseEvents（原 /chat/stream 协议零变化）。 */
public class SseChatSink implements ChatSink {

    private final SseEmitter emitter;
    private final SseEvents events;
    /** 客户端断开/超时（G1）：编排层据此停止生成，不再把 token 写给已死的连接。 */
    private final AtomicBoolean aborted = new AtomicBoolean(false);
    /** 正常收尾标记：onCompletion 在 emitDone 之后触发，不能把它误判成 abort。 */
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public SseChatSink(SseEmitter emitter, SseEvents events) {
        this.emitter = emitter;
        this.events = events;
        // 回调只置位，不抛/不做 I/O：emitter 生命周期回调里抛异常会被 Spring 吞掉，
        // 但会污染日志；置位由编排层的下一个 token 检查点处理。
        emitter.onError(t -> aborted.set(true));
        emitter.onTimeout(() -> aborted.set(true));
        emitter.onCompletion(() -> {
            if (!completed.get()) aborted.set(true);
        });
    }

    @Override
    public boolean aborted() {
        return aborted.get();
    }

    @Override
    public void meta(Meta meta) {
        if (aborted.get()) return;
        events.emitMeta(emitter, meta.fingerprint(), meta.cacheHit(), meta.level(), meta.fastPath(),
                meta.deduplicated(), meta.retrievalMs(), meta.refused(), gateWire(meta.gate()));
    }

    @Override
    public void delta(String token) {
        if (aborted.get()) return;
        events.emitDelta(emitter, token);
    }

    @Override
    public void deliver(DeliveryOutcome outcome) {
        // 客户端已断：不再发协议帧，但必须把 emitter 收掉（否则连接句柄与回调挂到超时）。
        if (aborted.get()) {
            completed.set(true);
            emitter.complete();
            return;
        }
        completed.set(true);
        if (outcome instanceof DeliveryOutcome.Completed completedOutcome) {
            AnswerPayload payload = completedOutcome.payload();
            if (completedOutcome.answerDelivery() == AnswerDelivery.BUFFERED) {
                events.streamInChunks(emitter, payload.answer());
            }
            TtftTiming timing = completedOutcome.timing();
            events.emitDone(emitter, timing.t0(), timing.firstTokenNano(), payload.refs(),
                    payload.actionsOrEmpty(), payload.nearMissOrEmpty());
        } else if (outcome instanceof DeliveryOutcome.Failed failed) {
            events.emitError(emitter, failed.refs(), failed.actions());
        }
    }

    private static String gateWire(Gate gate) {
        return switch (gate) {
            case NONE -> "none";
            case CLARIFY -> "clarify";
            case REFUSE -> "refuse";
        };
    }
}
