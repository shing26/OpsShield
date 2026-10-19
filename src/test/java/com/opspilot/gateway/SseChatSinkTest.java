package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import com.opspilot.gateway.dto.Gate;
import com.opspilot.resilience.DegradationState.Level;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SseChatSinkTest {

    @Test
    void metaProjectsEveryGateToItsStableWireValue() {
        assertGateWire(Gate.NONE, "none");
        assertGateWire(Gate.CLARIFY, "clarify");
        assertGateWire(Gate.REFUSE, "refuse");
    }

    @Test
    void bufferedCompletionEmitsBodyBeforeDone() {
        SseEventsTest.Capturing emitter = new SseEventsTest.Capturing();
        SseChatSink sink = new SseChatSink(emitter, new SseEvents());
        AnswerPayload payload = new AnswerPayload("完整答案", List.of(), List.of(), "sop_fallback",
                false, 3, "tenant-internal", false, List.of());

        sink.deliver(new DeliveryOutcome.Completed(
                new TtftTiming(0L, 1L), payload, AnswerDelivery.BUFFERED));

        assertEquals(List.of("delta", "done"), emitter.names, "缓冲正文必须先于 done 帧");
        assertEquals("完整答案", emitter.payloads.get(0).get("token"),
                "buffer 模式必须由 sink 下发载荷正文");
        assertEquals(List.of("ttft_ms", "refs", "actions", "near_miss"),
                List.copyOf(emitter.payloads.get(1).keySet()), "done 帧键集不得变化");
    }

    private static void assertGateWire(Gate gate, String expectedWire) {
        SseEventsTest.Capturing emitter = new SseEventsTest.Capturing();
        SseChatSink sink = new SseChatSink(emitter, new SseEvents());

        sink.meta(new Meta("fp", "none", Level.L0, false, false, 0, false, gate));

        assertEquals(expectedWire, emitter.payloads.get(0).get("gate"));
    }
}
