package com.opspilot.gateway;

import com.opspilot.auth.UserContext;
import com.opspilot.config.AdmissionProperties;
import com.opspilot.resilience.AdmissionGate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** /v1 入口面：OpenAI 请求 → 内部 ChatRequest 映射、流式约束、content 类型边界、models 形状。 */
class OpenAiControllerTest {

    private final ChatOrchestrator orchestrator = mock(ChatOrchestrator.class);
    private final AdmissionGate admission = new AdmissionGate(new AdmissionProperties(32, 4, 1));
    private final OpenAiController controller = new OpenAiController(orchestrator, null, admission);

    private static MockHttpServletRequest withUser() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(UserContext.REQUEST_ATTR,
                new UserContext("sre-full", "sre", 3, "tenant-internal"));
        return req;
    }

    @Test
    void takesLastUserMessageIgnoringSystemAndHistory() {
        controller.chatCompletions(new OpenAiController.ChatCompletionsReq("opspilot",
                List.of(new OpenAiController.Msg("system", "你是个助手"),
                        new OpenAiController.Msg("user", "第一问"),
                        new OpenAiController.Msg("assistant", "答"),
                        new OpenAiController.Msg("user", "50012_DB_TIMEOUT 怎么查")), true),
                withUser(), new MockHttpServletResponse());

        var captor = ArgumentCaptor.forClass(com.opspilot.gateway.dto.ChatRequest.class);
        verify(orchestrator).submit(captor.capture(), any(), any(), eq("openai"), any());
        assertEquals("50012_DB_TIMEOUT 怎么查", captor.getValue().query());
    }

    /** QA 第五轮 P2：官方 content parts 数组（SDK 多模态模板形态）必须拼接接受。 */
    @Test
    void textContentPartsAreJoinedIntoSingleQuery() {
        controller.chatCompletions(new OpenAiController.ChatCompletionsReq("opspilot",
                List.of(new OpenAiController.Msg("user", List.of(
                        Map.of("type", "text", "text", "how to fix "),
                        Map.of("type", "text", "text", "50012_DB_TIMEOUT")))), true),
                withUser(), new MockHttpServletResponse());

        var captor = ArgumentCaptor.forClass(com.opspilot.gateway.dto.ChatRequest.class);
        verify(orchestrator).submit(captor.capture(), any(), any(), eq("openai"), any());
        assertEquals("how to fix 50012_DB_TIMEOUT", captor.getValue().query());
    }

    /** QA 第五轮 P2：数字 content 曾被 Jackson 静默强转并真实烧配额——现 400 且零配额。 */
    @Test
    void numericContentIsRejectedBeforeQuota() {
        var ex = assertThrows(ResponseStatusException.class, () -> controller.chatCompletions(
                new OpenAiController.ChatCompletionsReq("opspilot",
                        List.of(new OpenAiController.Msg("user", 1337)), true), withUser(), null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(orchestrator, never()).precheck(any());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void nonStreamIsRejected() {
        var ex = assertThrows(ResponseStatusException.class, () -> controller.chatCompletions(
                new OpenAiController.ChatCompletionsReq("m",
                        List.of(new OpenAiController.Msg("user", "q")), false), withUser(), null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void missingUserMessageIs400() {
        for (List<OpenAiController.Msg> msgs : List.<List<OpenAiController.Msg>>of(List.of(),
                List.of(new OpenAiController.Msg("system", "只有 system")))) {
            var ex = assertThrows(ResponseStatusException.class, () -> controller.chatCompletions(
                    new OpenAiController.ChatCompletionsReq("m", msgs, true), withUser(), null));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        }
        verify(orchestrator, never()).precheck(any());
    }

    @Test
    void quotaPrecheckHappensBeforeSubmit() {
        controller.chatCompletions(new OpenAiController.ChatCompletionsReq(null,
                List.of(new OpenAiController.Msg("user", "q")), true), withUser(), new MockHttpServletResponse());
        var inOrder = inOrder(orchestrator);
        inOrder.verify(orchestrator).precheck(any());
        inOrder.verify(orchestrator).submit(any(), any(), any(), anyString(), any());
    }

    /** G1：租户许可耗尽必须 429 + Retry-After（不能等 SSE 已返回才拒绝）。 */
    @Test
    void admissionExhaustionReturns429WithRetryAfter() {
        AdmissionGate oneSlot = new AdmissionGate(new AdmissionProperties(4, 1, 2));
        OpenAiController c = new OpenAiController(orchestrator, null, oneSlot);
        MockHttpServletResponse first = new MockHttpServletResponse();
        c.chatCompletions(new OpenAiController.ChatCompletionsReq("opspilot",
                List.of(new OpenAiController.Msg("user", "q1")), true), withUser(), first);

        MockHttpServletResponse second = new MockHttpServletResponse();
        var ex = assertThrows(ResponseStatusException.class, () -> c.chatCompletions(
                new OpenAiController.ChatCompletionsReq("opspilot",
                        List.of(new OpenAiController.Msg("user", "q2")), true), withUser(), second));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
        assertEquals("2", second.getHeader("Retry-After"), "429 必须告诉客户端多久后可重试");
    }

    private static BuildProperties buildProps(String version, String time) {
        var p = new java.util.Properties();
        p.setProperty("version", version);
        p.setProperty("time", time);
        return new BuildProperties(p);
    }

    /** QA 第五轮 P3：models 须含 created（BuildProperties 时间源；无文件时 0）。 */
    @Test
    @SuppressWarnings("unchecked")
    void modelsAdvertiseOpspilotWithCreated() {
        var withBuild = new OpenAiController(orchestrator, buildProps("1.0.0", "2026-09-12T00:00:00Z"), admission);
        var data1 = (List<Map<String, Object>>) withBuild.models().get("data");
        assertEquals("opspilot", data1.get(0).get("id"));
        assertEquals(Instant.parse("2026-09-12T00:00:00Z").getEpochSecond(), data1.get(0).get("created"));

        var data0 = (List<Map<String, Object>>) controller.models().get("data");
        assertEquals(0L, data0.get(0).get("created"), "无 build-info 回退 0");
    }
}
