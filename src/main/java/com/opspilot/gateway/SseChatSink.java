package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.resilience.DegradationState.Level;
import java.util.List;
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
    public void meta(String fp, String cacheHit, Level level, boolean fastPath,
                     boolean deduplicated, long retrievalMs, boolean refused, String gate) {
        events.emitMeta(emitter, fp, cacheHit, level, fastPath, deduplicated, retrievalMs, refused, gate);
    }

    @Override
    public void delta(String token) {
        events.emitDelta(emitter, token);
    }

    @Override
    public void streamInChunks(String answer) {
        events.streamInChunks(emitter, answer);
    }

    @Override
    public void done(long t0, long firstTokenNano, List<AnswerPayload.Ref> refs,
                     List<com.opspilot.action.Action> actions,
                     List<AnswerPayload.NearMiss> nearMiss) {
        events.emitDone(emitter, t0, firstTokenNano, refs, actions, nearMiss);
    }

    @Override
    public void error(Throwable t, List<AnswerPayload.Ref> refs,
                      List<com.opspilot.action.Action> actions) {
        events.emitError(emitter, refs, actions);
    }
}
