package com.opspilot.gateway;

import com.opspilot.action.Action;
import com.opspilot.gateway.dto.AnswerPayload;
import java.util.List;
import java.util.Objects;

/**
 * 一次回答交付的唯一终态。
 */
public sealed interface DeliveryOutcome permits DeliveryOutcome.Completed, DeliveryOutcome.Failed {

    record Completed(TtftTiming timing, AnswerPayload payload, AnswerDelivery answerDelivery)
            implements DeliveryOutcome {
        public Completed {
            Objects.requireNonNull(timing, "timing");
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(answerDelivery, "answerDelivery");
        }
    }

    record Failed(Throwable error, List<AnswerPayload.Ref> refs, List<Action> actions)
            implements DeliveryOutcome {
        public Failed {
            Objects.requireNonNull(error, "error");
            refs = refs == null ? List.of() : List.copyOf(refs);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }
}
