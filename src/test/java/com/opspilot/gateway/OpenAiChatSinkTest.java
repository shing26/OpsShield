package com.opspilot.gateway;

import com.opspilot.gateway.dto.AnswerPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;

/** P 收尾：OpenAI 帧协议正确性锁——role 首帧/内容帧/refs 注入/stop/[DONE]/异常收尾/断开容错。 */
class OpenAiChatSinkTest {

    /** 捕获型 emitter：记录每个 data 值与 complete 调用，不触碰真实 HTTP。 */
    static class Capturing extends SseEmitter {
        final List<String> frames = new ArrayList<>();
        boolean completed = false;

        @Override
        public void send(SseEventBuilder builder) {
            // 一个逻辑帧的 build() 会展开成多个载体 entry（event 前缀/data/mediaType），
            // 测试只收 JSON chunk 与 [DONE] 两类载荷
            for (Object o : builder.build()) {
                Object d = ((DataWithMediaType) o).getData();
                if (d instanceof String s && (s.startsWith("{") || s.equals("[DONE]"))) {
                    frames.add(s);
                }
            }
        }
        @Override
        public void complete() { completed = true; }
    }

    @Test
    void frameSequenceMatchesOpenaiContract() {
        Capturing em = new Capturing();
        OpenAiChatSink sink = new OpenAiChatSink(em, "opspilot");

        sink.delta("你好");
        sink.delta("，同学");
        // actions 传空表：本用例只锁 refs 注入与帧序，行动契约另有专项用例
        sink.done(0L, 1L, List.of(new AnswerPayload.Ref("rb-001::x", "订单超时手册", "order-service")),
                List.of(), List.of());

        // 空契约不发帧（无 actions 的 done 仍是 5 帧）：平白一个空 content 帧会让客户端多渲染一跳
        assertEquals(5, em.frames.size(), "role+content / content / refs / stop / [DONE]");
        String first = em.frames.get(0);
        assertTrue(first.contains("\"role\":\"assistant\""), "首帧必须携带 role");
        assertTrue(first.contains("\"object\":\"chat.completion.chunk\""));
        assertTrue(em.frames.get(1).contains("，同学"));
        // QA 第五轮线卫生：内容帧不得携带 finish_reason:null（Choice 层 NON_NULL）
        assertFalse(em.frames.get(1).contains("finish_reason"), "内容帧无 finish_reason 键");
        assertTrue(em.frames.get(2).contains("## 参考来源"), "refs 必须以 markdown 注入正文尾部（溯源保留）");
        assertTrue(em.frames.get(2).contains("order-service"));
        assertTrue(em.frames.get(3).contains("\"finish_reason\":\"stop\""), "stop 帧");
        assertFalse(em.frames.get(3).contains("\"content\""), "stop 帧 delta 规范为 {}（空串是私货）");
        assertFalse(em.frames.get(3).contains(":null"), "stop 帧无 null 泄漏");
        assertEquals("[DONE]", em.frames.get(4));
    }

    @Test
    void doneAndFailFastFrames() {
        Capturing em = new Capturing();
        OpenAiChatSink sink = new OpenAiChatSink(em, "opspilot");
        sink.delta("半句");
        sink.error(new RuntimeException("upstream boom"), List.of(), List.of());

        String joined = String.join("|", em.frames);
        assertTrue(joined.contains("【系统提示】"), "异常必须以文本帧呈现而非裸断流");
        assertTrue(joined.contains("\"finish_reason\":\"stop\""));
        assertTrue(em.frames.get(em.frames.size() - 1).equals("[DONE]"));
        assertTrue(em.completed);
        // error 收尾后再投递必须静默（finished 闩）
        int before = em.frames.size();
        sink.delta("late");
        assertEquals(before, em.frames.size(), "[DONE] 后不得再发帧");
    }

    @Test
    void errorFrameCarriesReadOnlyContractWhenModelFailed() {
        Capturing em = new Capturing();
        OpenAiChatSink sink = new OpenAiChatSink(em, "opspilot");
        sink.delta("半句");
        // ADRs 0017：模型侧失败（欠费/5xx）时，检索与契约已经跑完——不该连坐吞掉
        sink.error(new RuntimeException("model arrears"), List.of(), List.of(
                new com.opspilot.action.Action(1, "第一步：查进程", "jstack <pid> | grep -A 15 pool",
                        "bash", "rb-012::s1", "线程池耗尽排查手册 > 排查步骤", "order-service")));

        String joined = String.join("|", em.frames);
        assertTrue(joined.contains("## 只读行动契约"), "异常帧仍要带行动契约: " + joined);
        assertTrue(joined.contains("jstack <pid> | grep -A 15 pool"), "契约命令逐字下发");
        assertTrue(joined.contains("【系统提示】"), "异常提示仍在");
    }

    @Test
    void emptyContractEmitsNoFrame() {
        Capturing em = new Capturing();
        OpenAiChatSink sink = new OpenAiChatSink(em, "opspilot");
        sink.delta("答案");
        sink.done(0L, 1L, List.of(), List.of(), List.of());

        assertEquals(3, em.frames.size(), "无 refs、无契约：role+content / stop / [DONE]，不得凭空多一跳空帧");
        assertFalse(String.join("|", em.frames).contains("只读行动契约"), "空契约不得渲染标题");
    }

    @Test
    void metaIsDroppedWithoutSideEffects() {
        Capturing em = new Capturing();
        new OpenAiChatSink(em, "opspilot")
                .meta("fp", "none", com.opspilot.resilience.DegradationState.Level.L0, false, false, 0, false,
                        "none");
        assertTrue(em.frames.isEmpty(), "meta 在 OpenAI 帧无位置：丢弃且不产生输出");
    }
}
