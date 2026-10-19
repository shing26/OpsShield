package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.gateway.dto.Gate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** ChatSink 的 SSE 实现：全部帧格式委托 SseEvents（原 /chat/stream 协议零变化）。 */
public class SseChatSink implements ChatSink {

    private final SseEmitter emitter;
    private final SseEvents events;

    public SseChatSink(SseEmitter emitter, SseEvents events) {
        this.emitter = emitter;
        this.events = events;
    }

    @Override
    public void meta(Meta meta) {
        events.emitMeta(emitter, meta.fingerprint(), meta.cacheHit(), meta.level(), meta.fastPath(),
                meta.deduplicated(), meta.retrievalMs(), meta.refused(), gateWire(meta.gate()));
    }

    @Override
    public void delta(String token) {
        events.emitDelta(emitter, token);
    }

    @Override
    public void deliver(DeliveryOutcome outcome) {
        if (outcome instanceof DeliveryOutcome.Completed completed) {
            AnswerPayload payload = completed.payload();
            if (completed.answerDelivery() == AnswerDelivery.BUFFERED) {
                events.streamInChunks(emitter, payload.answer());
            }
            TtftTiming timing = completed.timing();
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
