package com.opspilot.gateway;

import com.opspilot.resilience.DegradationState.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * meta / done 帧的**键集与次序**锁（ADR-0020 / ADR-0023）。
 *
 * <p>为什么要单独立一个：{@code refused}（2026-10-18）与 {@code gate}（2026-10-19）都是新增键，
 * 而这两个帧是**对外协议面**——既有消费方（troubleshoot.html 与 acceptance_a3 的帧形状断言）读的
 * 是这套键。新增键**只许追加不许插队**，否则老客户端按位读会错位；同时既有键一个都不许少
 * （少一个，面板的降级灯与 A3 的 TTFT 口径就会静默失明，而"静默失明"正是本仓反复登记过的那类故障）。
 */
class SseEventsTest {

    /** 捕获型 emitter：记录帧名与载荷对象，不触碰真实 HTTP。 */
    static class Capturing extends SseEmitter {
        final List<String> names = new ArrayList<>();
        final List<Map<?, ?>> payloads = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            for (Object o : builder.build()) {
                Object d = ((DataWithMediaType) o).getData();
                if (d instanceof String s && s.startsWith("event:")) {
                    // Spring 把帧名与随后的 "data:" 前缀并成同一个字符串条目（"event:meta\ndata:"），
                    // 故只取第一行、去掉前缀
                    names.add(s.substring("event:".length()).split("\n", 2)[0].trim());
                } else if (d instanceof Map<?, ?> m) {
                    payloads.add(m);
                }
            }
        }
    }

    private static final List<String> EXPECTED_KEYS =
            List.of("fingerprint", "cache_hit", "degradation_level", "fast_path",
                    "deduplicated", "retrieval_ms", "refused", "gate");

    @Test
    void metaFrameCarriesEightKeysInStableOrder() {
        Capturing em = new Capturing();
        new SseEvents().emitMeta(em, "fp-1", "L1", Level.L0, true, false, 42, false, "none");

        assertEquals(List.of("meta"), em.names, "帧名必须是 meta");
        assertEquals(1, em.payloads.size(), "一次 emitMeta 只发一帧");
        assertEquals(EXPECTED_KEYS, new ArrayList<>(em.payloads.get(0).keySet()),
                "键集与次序：refused / gate 依次追加在末尾，既有 6 键不得增删或换位");
    }

    @Test
    void refusedIsPassedThroughNotHardcoded() {
        Capturing yes = new Capturing();
        new SseEvents().emitMeta(yes, "fp", "none", Level.L0, false, false, 0, true, "refuse");
        assertEquals(Boolean.TRUE, yes.payloads.get(0).get("refused"),
                "拒答/澄清路径：帧上必须是 true——客户端据此渲染拒答态，不必再匹配话术");

        Capturing no = new Capturing();
        new SseEvents().emitMeta(no, "fp", "none", Level.L0, false, false, 0, false, "none");
        assertEquals(Boolean.FALSE, no.payloads.get(0).get("refused"),
                "正常路径：必须是 false，不得恒真");
    }

    /**
     * gate 三态透传（ADR-0023）：{@code refused} 只说"没答"，说不清"为什么没答"——
     * 回指澄清（零检索零 LLM）与无据拒答（检索跑完但低于阈值）的成因与代价都不同，
     * 而 CONTEXT.md 明确写着"澄清不是拒答"。这一条锁的就是两者在帧上不许被压成同一个值。
     */
    @Test
    void gateDistinguishesClarifyFromRefuse() {
        Capturing clarify = new Capturing();
        new SseEvents().emitMeta(clarify, "fp", "none", Level.L0, false, false, 0, true, "clarify");
        assertEquals("clarify", clarify.payloads.get(0).get("gate"),
                "回指澄清：gate 必须是 clarify（不是 refuse）");

        Capturing refuse = new Capturing();
        new SseEvents().emitMeta(refuse, "fp", "none", Level.L0, false, false, 2762, true, "refuse");
        assertEquals("refuse", refuse.payloads.get(0).get("gate"),
                "无据拒答：gate 必须是 refuse");
        assertEquals(2762L, refuse.payloads.get(0).get("retrieval_ms"),
                "拒答是检索跑完之后判的——retrieval_ms 不得为 0（那是澄清的形态）");
    }

    /**
     * done 帧的 near_miss（ADR-0023）：键**恒给**（空给 {@code []}），客户端不必判存在性——
     * 这与 actions 同口径；而"客户端要判键在不在"正是 troubleshoot.html 那段判拒答死代码的成因。
     */
    @Test
    void doneFrameAlwaysCarriesNearMissKey() {
        Capturing empty = new Capturing();
        new SseEvents().emitDone(empty, 0, 1_000_000, List.of(), List.of(), null);
        assertEquals(List.of("ttft_ms", "refs", "actions", "near_miss"),
                new ArrayList<>(empty.payloads.get(0).keySet()),
                "done 键集与次序：near_miss 追加在末尾，null 必须兜底成空表");
        assertEquals(List.of(), empty.payloads.get(0).get("near_miss"), "null → []");

        Capturing miss = new Capturing();
        new SseEvents().emitDone(miss, 0, 1_000_000, List.of(), List.of(),
                List.of(new com.opspilot.gateway.dto.AnswerPayload.NearMiss(
                        "order-service", List.of("50141_CONNECTION_POOL_EXHAUSTED"))));
        assertEquals(1, ((List<?>) miss.payloads.get(0).get("near_miss")).size(),
                "拒答路径：候选标识符必须原样送出");

        // 键集断言（ADR-0023 理由④）：候选**只准带** service 与 error_codes。给它加一个 text /
        // chunkId 字段看起来无害，但那正是"把低置信度内容摆出来"的入口——用户会拿它当答案用，
        // 而系统无法保证那几段不是别的故障的手册。故在此序列化后钉住键集。
        String json;
        try {
            json = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(miss.payloads.get(0).get("near_miss"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("near_miss 必须可序列化", e);
        }
        assertEquals("[{\"service\":\"order-service\",\"errorCodes\":[\"50141_CONNECTION_POOL_EXHAUSTED\"]}]",
                json, "near_miss 的线上形态只准有 service 与 errorCodes 两个键（不得夹带正文/chunk_id）");
    }
}
