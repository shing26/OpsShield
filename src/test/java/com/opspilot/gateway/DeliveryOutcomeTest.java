package com.opspilot.gateway;

import com.opspilot.action.Action;
import com.opspilot.gateway.dto.AnswerPayload;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryOutcomeTest {

    @Test
    void completedCouplesTimingPayloadAndAnswerDelivery() {
        TtftTiming timing = new TtftTiming(10L, 25L);
        AnswerPayload payload = payload("流式答案");

        DeliveryOutcome.Completed completed =
                new DeliveryOutcome.Completed(timing, payload, AnswerDelivery.STREAMED);

        assertSame(timing, completed.timing());
        assertSame(payload, completed.payload());
        assertEquals(AnswerDelivery.STREAMED, completed.answerDelivery());
    }

    @Test
    void failedRetainsRecoveredEvidenceWithoutSuccessFields() {
        RuntimeException error = new RuntimeException("upstream failed");
        AnswerPayload.Ref ref = new AnswerPayload.Ref("rb-001::s1", "排查手册", "order-service");
        Action action = new Action(1, "查线程", "jstack <pid>", "bash",
                "rb-001::s1", "排查手册 > 步骤", "order-service");
        List<AnswerPayload.Ref> refs = new ArrayList<>(List.of(ref));
        List<Action> actions = new ArrayList<>(List.of(action));

        DeliveryOutcome.Failed failed = new DeliveryOutcome.Failed(error, refs, actions);
        refs.clear();
        actions.clear();

        assertSame(error, failed.error());
        assertEquals(List.of(ref), failed.refs(), "失败终态必须保留异常前已取得的引用");
        assertEquals(List.of(action), failed.actions(), "失败终态必须保留异常前已取得的行动契约");
    }

    private static AnswerPayload payload(String answer) {
        return new AnswerPayload(answer, List.of(), List.of(), "hybrid", false, 3,
                "tenant-internal", false, List.of());
    }
}
