package com.opspilot.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import com.opspilot.gateway.dto.AnswerPayload;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ChatSink 的 OpenAI chat.completions 流式实现（/v1 协议面）：首帧 delta.role=assistant，
 * 逐 token content 帧；_refs_ 与只读行动契约在 OpenAI 纯文本协议里没有结构化帧位，
 * 都以 markdown 注入答案尾部（与 ADR-0007 同思路降级渲染），再发
 * finish_reason=stop 空帧 + data: [DONE]。meta/ttft 在 OpenAI 帧无对应位，丢弃
 * （取舍记录于 ADR-0007）。异常不断裸 TCP：提示文本帧 + stop + [DONE] 正常收尾。
 */
public class OpenAiChatSink implements ChatSink {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatSink.class);

    /** OpenAI SSE chunk 线格式（record + Jackson 注解对齐规范字段名）。
     *  Choice 也须 NON_NULL：缺注解时 finish_reason:null 上线（QA 第五轮 P3 线卫生）；
     *  stop 帧 delta 规范为 {}（OpenAI 官方流式口径），空串会导致 delta:{"content":""}。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Chunk(String id, String object, long created, String model, List<Choice> choices) {
        static Chunk delta(String id, String model, String role, String content) {
            return new Chunk(id, "chat.completion.chunk", System.currentTimeMillis() / 1000, model,
                    List.of(new Choice(0, new Delta(role, content), null)));
        }
        static Chunk stop(String id, String model) {
            return new Chunk(id, "chat.completion.chunk", System.currentTimeMillis() / 1000, model,
                    List.of(new Choice(0, new Delta(null, null), "stop")));
        }
        @JsonInclude(JsonInclude.Include.NON_NULL)
        record Delta(String role, String content) {}
        @JsonInclude(JsonInclude.Include.NON_NULL)
        record Choice(int index, Delta delta, @JsonProperty("finish_reason") String finishReason) {}
    }

    private final SseEmitter emitter;
    private final String requestId;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean roleSent = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    /** G1：客户端断开/超时——编排层停止生成，避免继续烧上游 token。 */
    private final AtomicBoolean aborted = new AtomicBoolean(false);

    public OpenAiChatSink(SseEmitter emitter, String model) {
        this.emitter = emitter;
        this.model = model;
        this.requestId = "chatcmpl-" + java.util.UUID.randomUUID().toString().replace("-", "");
        emitter.onError(t -> aborted.set(true));
        emitter.onTimeout(() -> aborted.set(true));
        emitter.onCompletion(() -> {
            if (!finished.get()) aborted.set(true);
        });
    }

    @Override
    public boolean aborted() {
        return aborted.get();
    }

    @Override
    public void meta(Meta meta) {
        // OpenAI 帧无元信息位：有意丢弃（客户端不识别自定义事件；溯源经 refs 注入正文保展示）
    }

    @Override
    public void delta(String token) {
        if (aborted.get()) return;
        boolean first = roleSent.compareAndSet(false, true);
        send(Chunk.delta(requestId, model, first ? "assistant" : null, token));
    }

    @Override
    public void deliver(DeliveryOutcome outcome) {
        if (aborted.get()) {
            // 客户端已断：不再发任何帧，只把连接收掉（onCompletion 会因 !finished 置 abort，
            // 编排层据此跳过缓存写与"成功"语义）。
            finished.set(true);
            emitter.complete();
            return;
        }
        if (outcome instanceof DeliveryOutcome.Failed failed) {
            deliverFailure(failed.refs(), failed.actions());
            return;
        }
        if (!(outcome instanceof DeliveryOutcome.Completed completed)) {
            return;
        }
        AnswerPayload payload = completed.payload();
        if (completed.answerDelivery() == AnswerDelivery.BUFFERED) {
            delta(payload.answer());
        }
        // 空契约不发帧：OpenAI 客户端把空 content 帧也当一次增量渲染，平白多一跳
        String act = actionMarkdown(payload.actionsOrEmpty());
        if (!act.isEmpty()) {
            send(Chunk.delta(requestId, model, null, act));
        }
        List<AnswerPayload.Ref> refs = payload.refs();
        if (refs != null && !refs.isEmpty()) {
            StringBuilder sb = new StringBuilder("\n\n## 参考来源\n");
            for (AnswerPayload.Ref r : refs) {
                sb.append("- ").append(r.breadcrumb()).append("（").append(r.service()).append("）\n");
            }
            send(Chunk.delta(requestId, model, null, sb.toString()));
        }
        send(Chunk.stop(requestId, model));
        sendDone();
    }

    private void deliverFailure(List<AnswerPayload.Ref> refs,
                                List<com.opspilot.action.Action> actions) {
        StringBuilder sb = new StringBuilder();
        // ADRs 0017：异常帧同样带契约（生成侧失败不该连坐吞掉已到手的只读建议）
        sb.append(actionMarkdown(actions));
        if (refs != null && !refs.isEmpty()) {
            sb.append("\n\n## 参考来源\n");
            for (AnswerPayload.Ref r : refs) {
                sb.append("- ").append(r.breadcrumb()).append("（").append(r.service()).append("）\n");
            }
        }
        sb.append("\n\n【系统提示】本轮回答因网关内部异常中断，请稍后重试或联系值班 SRE。");
        send(Chunk.delta(requestId, model, null, sb.toString()));
        send(Chunk.stop(requestId, model));
        sendDone();
    }

    /** 只读行动契约 → 正文 markdown（OpenAI 面没有结构化帧位，与 refs 同思路降级渲染）。 */
    private static String actionMarkdown(List<com.opspilot.action.Action> actions) {
        if (actions == null || actions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## 只读行动契约\n");
        for (com.opspilot.action.Action a : actions) {
            sb.append(a.n()).append(". ").append(a.step()).append("\n   `").append(a.command()).append("`\n");
        }
        return sb.toString();
    }

    private void send(Chunk chunk) {
        if (finished.get()) return;
        try {
            emitter.send(SseEmitter.event().data(mapper.writeValueAsString(chunk), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE send failed (client gone): {}", e.getMessage()); // 客户端断开不致命
        }
    }

    private void sendDone() {
        if (!finished.compareAndSet(false, true)) return;
        try {
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE done failed: {}", e.getMessage());
        }
    }
}
